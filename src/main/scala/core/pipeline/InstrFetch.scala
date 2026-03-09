package soc.core.pipeline

import chisel3._
import chisel3.util._
import soc.isa.Common

class InstrFetch(XLEN: Int) extends Module {
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
    })

	val update_en = !io.stall
    val instr = WireInit(soc.isa.Common.instrNop)
    instr := Mux(io.redirect, soc.isa.Common.instrNop, io.instr_in)

    io.valid          := RegNext(!io.redirect && !io.trap_valid, false.B)
    io.pc_out         := RegEnable(io.pc, 0.U, update_en)
    io.instr_out      := RegEnable(instr, 0.U, update_en)
    io.pred_taken_out := RegEnable(io.pred_taken_in, false.B, update_en)
}
