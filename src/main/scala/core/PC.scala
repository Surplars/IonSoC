package soc.core

import chisel3._

class PC(XLEN: Int, RESET_VECTOR: BigInt) extends Module {
	val io = IO(new Bundle {
		val branch_en = Input(Bool())
		val branch_target = Input(UInt(XLEN.W))
		val stall = Input(Bool())

		val fetch_en = Output(Bool())
		val pc = Output(UInt(XLEN.W))
	})

	val rst = RegInit(true.B)
	val ProgramCounter = RegInit(RESET_VECTOR.U(XLEN.W))

	io.pc :=  ProgramCounter
	io.fetch_en := true.B

	when (rst) {
		ProgramCounter := RESET_VECTOR.U
		io.fetch_en := false.B
		rst := false.B
	} .elsewhen (io.branch_en) {
		ProgramCounter := io.branch_target
		io.fetch_en := true.B
	} .elsewhen (io.stall) {
		ProgramCounter := ProgramCounter
		io.fetch_en := true.B
	} .otherwise {
		ProgramCounter := ProgramCounter + 4.U
		io.fetch_en := true.B
	}
}

