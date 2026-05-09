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
        val redirect      = Input(Bool())
        val trap_valid    = Input(Bool())
        val stall         = Input(Bool())

        val valid          = Output(Bool())
        val pc_out         = Output(UInt(XLEN.W))
        val instr_out      = Output(UInt(32.W))
        val instr_len      = Output(UInt(2.W))
        val pc_step_len    = Output(UInt(2.W))
        val pred_taken_out = Output(Bool())
        val fetch_stall    = Output(Bool())

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
    io.pc_step_len    := directExpanded._2
    io.valid          := RegEnable(!io.redirect && !io.trap_valid, false.B, directUpdate)
    io.pc_out         := RegEnable(io.pc, 0.U, directUpdate)
    io.instr_out      := RegEnable(directInstr, 0.U, directUpdate)
    io.instr_len      := RegEnable(directLen, 0.U, directUpdate)
    io.pred_taken_out := RegEnable(io.pred_taken_in, false.B, directUpdate)

    if (useCache) {
        val sIdle :: sFirstReq :: sFirstWait :: sSecondReq :: sSecondWait :: sRelease :: Nil = Enum(6)
        val state = RegInit(sIdle)
        val reqPc = RegInit(0.U(XLEN.W))
        val reqPredTaken = RegInit(false.B)
        val dropResp = RegInit(false.B)
        val crossBeatLowHalf = RegInit(0.U(16.W))
        val releaseLen = RegInit(0.U(2.W))

        val flush = io.redirect || io.trap_valid
        val canIssue = state === sIdle && !io.stall && !flush
        when(canIssue) {
            reqPc := io.pc
            reqPredTaken := io.pred_taken_in
            dropResp := false.B
            state := sFirstReq
        }

        when(state === sRelease && !io.stall) {
            state := sIdle
        }

        when((state === sFirstWait || state === sSecondWait) && flush) {
            dropResp := true.B
        }

        io.cache.req.valid := (state === sFirstReq || state === sSecondReq) && !flush
        val secondReqPc = reqPc + 2.U
        val cacheReqPc = Mux(state === sSecondReq, secondReqPc, reqPc)
        io.cache.req.bits.addr := cacheReqPc
        io.cache.req.bits.vaddr := cacheReqPc
        // Keep the response channel drainable even after a frontend flush has
        // cancelled the matching fetch. Otherwise a late cache response can
        // leave the I-cache in its response state and block fence.i invalidation.
        io.cache.resp.ready := !io.stall

        when((state === sFirstReq || state === sSecondReq) && flush) {
            dropResp := false.B
            state := sIdle
        }.elsewhen(state === sFirstReq && io.cache.req.fire) {
            state := sFirstWait
        }.elsewhen(state === sSecondReq && io.cache.req.fire) {
            state := sSecondWait
        }

        val respFire = io.cache.resp.valid && io.cache.resp.ready
        val respExpanded = selectAndExpand(io.cache.resp.bits.rdata, reqPc, reqPc(beatOffsetBits - 1, 0))
        val firstHalf = (io.cache.resp.bits.rdata >> Cat(reqPc(beatOffsetBits - 1, 0), 0.U(3.W)))(15, 0)
        val needsCrossBeat = if (useCompressed) {
            reqPc(beatOffsetBits - 1, 0) === ((XLEN / 8) - 2).U && firstHalf(1, 0) === "b11".U
        } else {
            false.B
        }
        val acceptFirstResp =
            state === sFirstWait && respFire && !needsCrossBeat && !dropResp && !io.cache.resp.bits.err && !flush
        val acceptSecondResp =
            state === sSecondWait && respFire && !dropResp && !io.cache.resp.bits.err && !flush
        val assembledCrossBeat = Cat(io.cache.resp.bits.rdata(15, 0), crossBeatLowHalf)
        val acceptResp = acceptFirstResp || acceptSecondResp
        val acceptedInstr = Mux(acceptSecondResp, assembledCrossBeat, respExpanded._1)
        val acceptedLen = Mux(acceptSecondResp, 0.U(2.W), respExpanded._2)

        when(acceptResp) {
            releaseLen := acceptedLen
        }

        when(state === sFirstWait && respFire) {
            when(dropResp || io.cache.resp.bits.err || flush) {
                dropResp := false.B
                state := sIdle
            }.elsewhen(needsCrossBeat) {
                crossBeatLowHalf := firstHalf
                state := sSecondReq
            }.otherwise {
                dropResp := false.B
                state := sRelease
            }
        }.elsewhen(state === sSecondWait && respFire) {
            dropResp := false.B
            state := Mux(dropResp || io.cache.resp.bits.err || flush, sIdle, sRelease)
        }

        io.fetch_stall := canIssue || (state =/= sIdle && state =/= sRelease)
        // The PC consumes the step length in the same cycle that a cache
        // response is accepted. Use the just-decoded length instead of the
        // registered previous instruction length, otherwise a 16-bit
        // instruction following a 32-bit instruction advances by 4 bytes.
        io.pc_step_len := Mux(acceptResp, acceptedLen, Mux(state === sRelease, releaseLen, io.instr_len))
        io.valid := RegEnable(acceptResp, false.B, !io.stall)
        io.pc_out := RegEnable(reqPc, 0.U, acceptResp)
        io.instr_out := RegEnable(acceptedInstr, 0.U, acceptResp)
        io.instr_len := RegEnable(acceptedLen, 0.U, acceptResp)
        io.pred_taken_out := RegEnable(reqPredTaken, false.B, acceptResp)
    }
}
