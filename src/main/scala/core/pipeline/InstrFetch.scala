package soc.core.pipeline

import chisel3._
import chisel3.util._
import soc.isa.Common
import soc.isa.Compressed
import soc.memory.CacheReq
import soc.memory.CacheResp
import soc.memory.cache.CacheCmd

class InstrFetch(XLEN: Int, useCache: Boolean = false, useCompressed: Boolean = false) extends Module {
    val io = IO(new Bundle {
        val pc            = Input(UInt(XLEN.W))
        val instr_in      = Input(UInt(64.W))
        val pred_taken_in = Input(Bool())
        val pred_target_in = Input(UInt(XLEN.W))
        val redirect      = Input(Bool())
        val trap_valid    = Input(Bool())
        val stall         = Input(Bool())

        val valid          = Output(Bool())
        val pc_out         = Output(UInt(XLEN.W))
        val instr_out      = Output(UInt(32.W))
        val instr_len      = Output(UInt(2.W))
        val pc_step_len    = Output(UInt(2.W))
        val pred_taken_out = Output(Bool())
        val pred_target_out = Output(UInt(XLEN.W))
        val fetch_stall    = Output(Bool())
        val cache_busy     = Output(Bool())

        val cache = new Bundle {
            val req  = Decoupled(new CacheReq(XLEN, XLEN))
            val resp = Flipped(Decoupled(new CacheResp(XLEN)))
        }
    })

    private val beatOffsetBits = log2Ceil(XLEN / 8)

    io.cache.req.valid          := false.B
    io.cache.req.bits.addr      := io.pc
    io.cache.req.bits.vaddr     := io.pc
    io.cache.req.bits.cmd       := CacheCmd.Read
    io.cache.req.bits.wdata     := 0.U
    io.cache.req.bits.mask      := Fill(XLEN / 8, 1.U(1.W))
    io.cache.req.bits.size      := beatOffsetBits.U
    io.cache.req.bits.signed    := false.B
    io.cache.req.bits.fence     := false.B
    io.cache.req.bits.fencei    := false.B
    io.cache.req.bits.atomic    := false.B
    io.cache.req.bits.cacheable := true.B
    io.cache.req.bits.device    := false.B
    io.cache.resp.ready         := true.B

    val directUpdate = !io.stall
    private def selectAndExpand(raw: UInt, pc: UInt, byteOffset: UInt): (UInt, UInt) = {
        val shift = Cat(byteOffset, 0.U(3.W))
        val rawInstr = (raw >> shift)(31, 0)
        if (useCompressed) {
            val half = rawInstr(15, 0)
            val isCompressed = half(1, 0) =/= "b11".U
            val expanded = Compressed.expand(half)
            val instr = Mux(expanded._2, expanded._1, Common.instrIllegal)
            (Mux(isCompressed, instr, rawInstr), Mux(isCompressed, 2.U(2.W), 0.U(2.W)))
        } else {
            (rawInstr, 0.U(2.W))
        }
    }
    // Direct ROM supplies the word containing PC plus the following word, so
    // bit 1 is enough to assemble 32-bit instructions starting at a high halfword.
    val directExpanded = selectAndExpand(io.instr_in, io.pc, Cat(0.U(1.W), io.pc(1, 0)))
    val directInstr = Mux(io.redirect, Common.instrNop, directExpanded._1)
    val directLen = Mux(io.redirect, 0.U(2.W), directExpanded._2)

    io.fetch_stall := false.B
    io.cache_busy  := false.B
    io.pc_step_len    := directExpanded._2
    io.valid          := RegEnable(!io.redirect && !io.trap_valid, false.B, directUpdate)
    io.pc_out         := RegEnable(io.pc, 0.U, directUpdate)
    io.instr_out      := RegEnable(directInstr, 0.U, directUpdate)
    io.instr_len      := RegEnable(directLen, 0.U, directUpdate)
    io.pred_taken_out := RegEnable(io.pred_taken_in, false.B, directUpdate)
    io.pred_target_out := RegEnable(io.pred_target_in, 0.U, directUpdate)

    if (useCache) {
        val sIdle :: sFirstReq :: sFirstWait :: sSecondReq :: sSecondWait :: sPrefetchWait :: Nil = Enum(6)
        val state = RegInit(sIdle)
        val reqPc = RegInit(0.U(XLEN.W))
        val prefetchPc = RegInit(0.U(XLEN.W))
        val reqPredTaken = RegInit(false.B)
        val reqPredTarget = RegInit(0.U(XLEN.W))
        val dropResp = RegInit(false.B)
        val crossBeatLowHalf = RegInit(0.U(16.W))
        // Small direct-mapped buffer for recently returned instruction beats.
        // It is intentionally above the I-cache protocol: fence.i/trap/debug
        // invalidation clears it, while normal branch redirects can reuse beats
        // that were already fetched from the coherent I-cache path.
        val beatBufferEntries = 4
        val beatBufferIndexBits = log2Ceil(beatBufferEntries)
        val beatBufferValid = RegInit(VecInit(Seq.fill(beatBufferEntries)(false.B)))
        val beatBufferAddr = Reg(Vec(beatBufferEntries, UInt((XLEN - beatOffsetBits).W)))
        val beatBufferData = Reg(Vec(beatBufferEntries, UInt(XLEN.W)))

        val flush = io.redirect || io.trap_valid
        val pcBeatAddr = io.pc(XLEN - 1, beatOffsetBits)
        val pcBufferIdx = pcBeatAddr(beatBufferIndexBits - 1, 0)
        val beatBufferHit = beatBufferValid(pcBufferIdx) && beatBufferAddr(pcBufferIdx) === pcBeatAddr
        val bufferedBeatData = beatBufferData(pcBufferIdx)
        val bufferedExpanded = selectAndExpand(bufferedBeatData, io.pc, io.pc(beatOffsetBits - 1, 0))
        val bufferedFirstHalf = (bufferedBeatData >> Cat(io.pc(beatOffsetBits - 1, 0), 0.U(3.W)))(15, 0)
        val bufferedNeedsCrossBeat = if (useCompressed) {
            io.pc(beatOffsetBits - 1, 0) === ((XLEN / 8) - 2).U && bufferedFirstHalf(1, 0) === "b11".U
        } else {
            false.B
        }
        val canServeBuffered = state === sIdle && !io.stall && !flush && beatBufferHit && !bufferedNeedsCrossBeat
        val canIssue = state === sIdle && !io.stall && !flush && !canServeBuffered
        val bufferedStepBytes = Mux(bufferedExpanded._2 === 2.U, 2.U(XLEN.W), 4.U(XLEN.W))
        val bufferedNextPc = io.pc + bufferedStepBytes
        val bufferedNextBeatAddr = bufferedNextPc(XLEN - 1, beatOffsetBits)
        // Do not speculatively emit next-beat responses as pipeline
        // instructions. The earlier prefetch path reused the normal response
        // output and could mis-pair a prefetched beat with the current PC when
        // 16-bit and 32-bit instructions straddled a 64-bit fetch boundary.
        // Keep the local beat buffer for same-beat reuse; a future prefetcher
        // should tag responses and fill the buffer without driving io.valid.
        val canPrefetchNextBeat = false.B

        when(io.trap_valid) {
            beatBufferValid := VecInit(Seq.fill(beatBufferEntries)(false.B))
        }

        when(canIssue) {
            reqPc := io.pc
            reqPredTaken := io.pred_taken_in
            reqPredTarget := io.pred_target_in
            dropResp := false.B
            state := Mux(io.cache.req.ready, sFirstWait, sFirstReq)
        }

        when(canPrefetchNextBeat && io.cache.req.ready) {
            prefetchPc := bufferedNextPc
            dropResp := false.B
            state := sPrefetchWait
        }

        when((state === sFirstWait || state === sSecondWait || state === sPrefetchWait) && flush) {
            dropResp := true.B
        }

        // Issue the first cache request directly from idle when possible.  This
        // removes a fixed frontend bubble without assuming combinational cache
        // data: the L1 still performs a registered SyncReadMem lookup before
        // responding.
        io.cache.req.valid := (canIssue || canPrefetchNextBeat || state === sFirstReq || state === sSecondReq) && !flush
        val secondReqPc = ((reqPc >> beatOffsetBits.U) + 1.U) << beatOffsetBits.U
        val cacheReqPc = Mux(
            canPrefetchNextBeat,
            bufferedNextPc,
            Mux(state === sSecondReq, secondReqPc, Mux(canIssue, io.pc, reqPc))
        )
        io.cache.req.bits.addr := cacheReqPc
        io.cache.req.bits.vaddr := cacheReqPc
        // Keep the response channel drainable even after a frontend flush has
        // cancelled the matching fetch. Otherwise a late cache response can
        // leave the I-cache in its response state and block fence.i invalidation.
        io.cache.resp.ready := !io.stall || flush

        when((state === sFirstReq || state === sSecondReq) && flush) {
            dropResp := false.B
            state := sIdle
        }.elsewhen(state === sFirstReq && io.cache.req.fire) {
            state := sFirstWait
        }.elsewhen(state === sSecondReq && io.cache.req.fire) {
            state := sSecondWait
        }

        val respFire = io.cache.resp.valid && io.cache.resp.ready
        val instantFirstResp = canIssue && io.cache.req.ready && respFire
        val instantSecondResp = state === sSecondReq && io.cache.req.ready && respFire && !flush
        val instantPrefetchResp = canPrefetchNextBeat && io.cache.req.ready && respFire
        val firstRespPc = Mux(instantFirstResp, io.pc, reqPc)
        val prefetchRespPc = Mux(instantPrefetchResp, bufferedNextPc, prefetchPc)
        val respExpanded = selectAndExpand(io.cache.resp.bits.rdata, firstRespPc, firstRespPc(beatOffsetBits - 1, 0))
        val prefetchExpanded = selectAndExpand(io.cache.resp.bits.rdata, prefetchRespPc, prefetchRespPc(beatOffsetBits - 1, 0))
        val firstHalf = (io.cache.resp.bits.rdata >> Cat(firstRespPc(beatOffsetBits - 1, 0), 0.U(3.W)))(15, 0)
        val needsCrossBeat = if (useCompressed) {
            firstRespPc(beatOffsetBits - 1, 0) === ((XLEN / 8) - 2).U && firstHalf(1, 0) === "b11".U
        } else {
            false.B
        }
        val acceptFirstResp =
            (state === sFirstWait || instantFirstResp) && respFire && !needsCrossBeat && !dropResp &&
                !io.cache.resp.bits.err && !flush
        val acceptSecondResp =
            (state === sSecondWait || instantSecondResp) && respFire && !dropResp && !io.cache.resp.bits.err && !flush
        val acceptPrefetchResp =
            (state === sPrefetchWait || instantPrefetchResp) && respFire && !dropResp && !io.cache.resp.bits.err && !flush
        val assembledCrossBeat = Cat(io.cache.resp.bits.rdata(15, 0), crossBeatLowHalf)
        val acceptResp = acceptFirstResp || acceptSecondResp || acceptPrefetchResp
        val acceptedInstr = Mux(acceptSecondResp, assembledCrossBeat, Mux(acceptPrefetchResp, prefetchExpanded._1, respExpanded._1))
        val acceptedLen = Mux(acceptSecondResp, 0.U(2.W), Mux(acceptPrefetchResp, prefetchExpanded._2, respExpanded._2))
        val acceptedPc = Mux(acceptPrefetchResp, prefetchRespPc, firstRespPc)

        when(acceptFirstResp) {
            val respBeatAddr = firstRespPc(XLEN - 1, beatOffsetBits)
            val respBufferIdx = respBeatAddr(beatBufferIndexBits - 1, 0)
            beatBufferValid(respBufferIdx) := true.B
            beatBufferAddr(respBufferIdx) := respBeatAddr
            beatBufferData(respBufferIdx) := io.cache.resp.bits.rdata
        }.elsewhen(acceptSecondResp) {
            val respBeatAddr = secondReqPc(XLEN - 1, beatOffsetBits)
            val respBufferIdx = respBeatAddr(beatBufferIndexBits - 1, 0)
            beatBufferValid(respBufferIdx) := true.B
            beatBufferAddr(respBufferIdx) := respBeatAddr
            beatBufferData(respBufferIdx) := io.cache.resp.bits.rdata
        }.elsewhen(acceptPrefetchResp) {
            val respBeatAddr = prefetchRespPc(XLEN - 1, beatOffsetBits)
            val respBufferIdx = respBeatAddr(beatBufferIndexBits - 1, 0)
            beatBufferValid(respBufferIdx) := true.B
            beatBufferAddr(respBufferIdx) := respBeatAddr
            beatBufferData(respBufferIdx) := io.cache.resp.bits.rdata
        }

        when(instantFirstResp) {
            when(io.cache.resp.bits.err || flush) {
                dropResp := false.B
                state := sIdle
            }.elsewhen(needsCrossBeat) {
                crossBeatLowHalf := firstHalf
                state := sSecondReq
            }.otherwise {
                dropResp := false.B
                state := sIdle
            }
        }.elsewhen(instantSecondResp) {
            dropResp := false.B
            state := sIdle
        }.elsewhen(instantPrefetchResp) {
            dropResp := false.B
            state := sIdle
        }.elsewhen(state === sFirstWait && respFire) {
            when(dropResp || io.cache.resp.bits.err || flush) {
                dropResp := false.B
                state := sIdle
            }.elsewhen(needsCrossBeat) {
                crossBeatLowHalf := firstHalf
                state := sSecondReq
            }.otherwise {
                dropResp := false.B
                state := sIdle
            }
        }.elsewhen(state === sSecondWait && respFire) {
            dropResp := false.B
            state := sIdle
        }.elsewhen(state === sPrefetchWait && respFire) {
            dropResp := false.B
            state := sIdle
        }

        val responseAdvancesPc = acceptResp && !io.stall
        io.fetch_stall := canIssue || ((state =/= sIdle) && !responseAdvancesPc)
        io.cache_busy := state =/= sIdle || canIssue
        // The PC consumes the step length in the same cycle that a cache
        // response is accepted. Use the just-decoded length instead of the
        // registered previous instruction length, otherwise a 16-bit
        // instruction following a 32-bit instruction advances by 4 bytes.
        val outputLen = Mux(canServeBuffered, bufferedExpanded._2, acceptedLen)
        io.pc_step_len := Mux(canServeBuffered || acceptResp, outputLen, io.instr_len)
        io.valid := canServeBuffered || acceptResp
        io.pc_out := Mux(canServeBuffered, io.pc, acceptedPc)
        io.instr_out := Mux(canServeBuffered, bufferedExpanded._1, acceptedInstr)
        io.instr_len := Mux(canServeBuffered, bufferedExpanded._2, acceptedLen)
        io.pred_taken_out := Mux(
            canServeBuffered,
            io.pred_taken_in,
            Mux(acceptPrefetchResp, io.pred_taken_in, reqPredTaken)
        )
        io.pred_target_out := Mux(
            canServeBuffered,
            io.pred_target_in,
            Mux(acceptPrefetchResp, io.pred_target_in, reqPredTarget)
        )
    }
}
