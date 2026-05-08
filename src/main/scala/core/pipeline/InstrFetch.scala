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
        val instr_in      = Input(UInt(32.W))
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
    private def selectAndExpand(raw: UInt, pc: UInt): (UInt, UInt) = {
        if (useCompressed) {
            val half = Mux(pc(1), raw(31, 16), raw(15, 0))
            val isCompressed = half(1, 0) =/= "b11".U
            val expanded = Compressed.expand(half)
            val instr = Mux(expanded._2, expanded._1, Common.instrIllegal)
            (Mux(isCompressed, instr, raw), Mux(isCompressed, 2.U(2.W), 0.U(2.W)))
        } else {
            (raw, 0.U(2.W))
        }
    }
    val directExpanded = selectAndExpand(io.instr_in, io.pc)
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
        val pending = RegInit(false.B)
        val sent = RegInit(false.B)
        val reqPc = RegInit(0.U(XLEN.W))
        val reqPredTaken = RegInit(false.B)
        val dropResp = RegInit(false.B)
        val releasePc = RegInit(false.B)

        val canIssue = !pending && !releasePc && !io.stall && !io.redirect && !io.trap_valid
        when(canIssue) {
            pending := true.B
            sent := false.B
            reqPc := io.pc
            reqPredTaken := io.pred_taken_in
            dropResp := false.B
        }

        when(releasePc && !io.stall) {
            releasePc := false.B
        }

        when(pending && (io.redirect || io.trap_valid)) {
            when(sent) {
                dropResp := true.B
            }.otherwise {
                pending := false.B
                dropResp := false.B
            }
        }

        io.cache.req.valid := pending && !sent
        io.cache.req.bits.addr := reqPc
        io.cache.req.bits.vaddr := reqPc
        io.cache.resp.ready := !io.stall
        when(io.cache.req.fire) {
            sent := true.B
        }

        val respFire = pending && sent && io.cache.resp.valid && io.cache.resp.ready
        val respShift = Cat(reqPc(beatOffsetBits - 1, 0), 0.U(3.W))
        val respInstr = (io.cache.resp.bits.rdata >> respShift)(31, 0)
        val respExpanded = selectAndExpand(respInstr, reqPc)
        val acceptResp = respFire && !dropResp && !io.cache.resp.bits.err && !io.redirect && !io.trap_valid

        when(respFire) {
            pending := false.B
            sent := false.B
            dropResp := false.B
            releasePc := acceptResp
        }

        io.fetch_stall := pending || canIssue
        io.pc_step_len := io.instr_len
        io.valid := RegEnable(acceptResp, false.B, !io.stall)
        io.pc_out := RegEnable(reqPc, 0.U, acceptResp)
        io.instr_out := RegEnable(respExpanded._1, 0.U, acceptResp)
        io.instr_len := RegEnable(respExpanded._2, 0.U, acceptResp)
        io.pred_taken_out := RegEnable(reqPredTaken, false.B, acceptResp)
    }
}
