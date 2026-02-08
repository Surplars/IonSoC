package soc.core

import chisel3._

class Core(XLEN: Int) extends Module {
	val io = IO(new Bundle {
		val instr = Input(UInt(32.W))

		val fetch_en = Output(Bool())
		val pc = Output(UInt(XLEN.W))
	})

	val pc = Module(new PC(XLEN, soc.config.Config.resetVector))

	// pc
	io.pc := pc.io.pc
	io.fetch_en := pc.io.fetch_en

	pc.io.branch_en := false.B
	pc.io.branch_target := 0.U
	pc.io.stall := false.B
}

