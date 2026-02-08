package soc

import chisel3._
import _root_.circt.stage.ChiselStage

import soc.config.Config
import soc.core._
import soc.device.BROM
import difftest._

class IonSoC extends Module {
    val io = IO(new Bundle {
		val debug = new soc.debug.DebugIO
		val logCtrl = new LogCtrlIO
		val perfInfo = new PerfCtrlIO
		val uart = new UARTIO
	})

	val core = Module(new Core(Config.XLEN))
    val brom = Module(new BROM(Config.XLEN, Config.romDepth, Config.romInit))
	// val ram = Module(new AXI4LiteSram(Config.ramDepth, Config.ramDataBytes))

	brom.io.fetch_en := core.io.fetch_en
	brom.io.addr := core.io.pc
	core.io.instr := brom.io.instr

	// Debug ports
	io.debug.pc := core.io.pc
	io.debug.instr := core.io.instr
	// Difftest ports
	io.uart.in.valid := false.B
	io.uart.out.valid := false.B
	io.uart.out.ch := 0.U
}
