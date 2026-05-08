package soc.core.pipeline

import chisel3._
import chisel3.util._
import soc.isa.Common
import soc.memory.CacheReq
import soc.memory.CacheResp
import soc.memory.cache.CacheCmd

class InstrFetch(XLEN: Int, useCache: Boolean = false) extends Module {
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
    val directInstr = Mux(io.redirect, Common.instrNop, io.instr_in)

    io.fetch_stall := false.B
    io.valid          := RegEnable(!io.redirect && !io.trap_valid, false.B, directUpdate)
    io.pc_out         := RegEnable(io.pc, 0.U, directUpdate)
    io.instr_out      := RegEnable(directInstr, 0.U, directUpdate)
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
        val acceptResp = respFire && !dropResp && !io.cache.resp.bits.err && !io.redirect && !io.trap_valid

        when(respFire) {
            pending := false.B
            sent := false.B
            dropResp := false.B
            releasePc := acceptResp
        }

        io.fetch_stall := pending || canIssue
        io.valid := RegEnable(acceptResp, false.B, !io.stall)
        io.pc_out := RegEnable(reqPc, 0.U, acceptResp)
        io.instr_out := RegEnable(respInstr, 0.U, acceptResp)
        io.pred_taken_out := RegEnable(reqPredTaken, false.B, acceptResp)
    }
}
