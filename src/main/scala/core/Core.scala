package soc.core

import chisel3._

import pipeline._

class Core(XLEN: Int) extends Module {
	val io = IO(new Bundle {
		val instr = Input(UInt(32.W))

		val fetch_en = Output(Bool())
		val pc = Output(UInt(XLEN.W))
	})

	val pc = Module(new PC(XLEN, soc.config.Config.resetVector))
	val register = Module(new Register(XLEN))

	val ifetch = Module(new InstrFetch(XLEN))
	val idecode = Module(new InstrDecode(XLEN))

	dontTouch(register.io)
	dontTouch(ifetch.io)
	dontTouch(idecode.io)

	register.io <> DontCare

	// pc
	io.pc := pc.io.pc
	io.fetch_en := pc.io.fetch_en
	pc.io.branch_en := false.B
	pc.io.branch_target := 0.U
	pc.io.stall := false.B

	ifetch.io.pc := pc.io.pc
	ifetch.io.instr_in := io.instr

	idecode.io.valid := ifetch.io.valid
	idecode.io.pc_in := ifetch.io.pc_out
	idecode.io.instr_in := ifetch.io.instr_out
	idecode.io.decoded_out := DontCare
}


