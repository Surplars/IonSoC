package soc.debug

import chisel3._

class DebugIO extends Bundle {
	val pc = Output(UInt(64.W))
	val instr = Output(UInt(32.W))

}


