package soc.device

import chisel3._
import chisel3.util._
import soc.config.Config

class ROM extends BlackBox {
	val io = IO(new Bundle {
		val en = Input(Bool())
		val pc_in  = Input(UInt(Config.XLEN.W))
		val instr_out = Output(UInt(32.W))
	})
}

class BROM(XLEN: Int, depth: Int, init: Seq[Int]) extends Module {
    require(init.length == depth, "Initialization sequence length must match ROM depth")

    val io = IO(new Bundle {
        val fetch_en = Input(Bool())
        val addr     = Input(UInt(XLEN.W))
        val instr    = Output(UInt(32.W))
    })

	val rom = Module(new ROM)

	rom.io.en := io.fetch_en
	rom.io.pc_in := io.addr
	io.instr := rom.io.instr_out
}

