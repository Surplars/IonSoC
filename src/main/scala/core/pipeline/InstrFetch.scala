package soc.core.pipeline

import chisel3._

class InstrFetch extends Module {
	val io = IO(new Bundle {
		val pc       = Input(UInt(64.W))
		val instr    = Input(UInt(32.W))
	})

	// Placeholder implementation
	val pcReg = RegInit(0.U(64.W))
	pcReg := io.pc

}
