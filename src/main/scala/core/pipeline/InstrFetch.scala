package soc.core.pipeline

import chisel3._
import soc.isa.Common

class InstrFetch(XLEN: Int) extends Module {
    val io = IO(new Bundle {
        val pc       = Input(UInt(XLEN.W))
        val instr_in = Input(UInt(32.W))

        val valid     = Output(Bool())
        val pc_out    = Output(UInt(XLEN.W))
        val instr_out = Output(UInt(32.W))
    })

    // Placeholder implementation
    val pcReg    = RegInit(0.U(XLEN.W))
    val instrReg = RegInit(Common.instrNop)

    pcReg := io.pc
	instrReg := io.instr_in

    io.valid     := true.B
    io.pc_out    := pcReg
    io.instr_out := instrReg
}
