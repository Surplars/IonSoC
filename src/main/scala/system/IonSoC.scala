package soc

import chisel3._
import _root_.circt.stage.ChiselStage

import soc.config.Config
import soc.core._

import soc.device.BROM
import soc.device.Sram
import soc.device.SramIO

import soc.bus.AXI4Seq
// import difftest._

class IonSoC extends Module {
    val io = IO(new Bundle {
		val debug = new soc.debug.DebugIO
		// val logCtrl = new LogCtrlIO
		// val perfInfo = new PerfCtrlIO
		// val uart = new UARTIO
	})

	val core = Module(new Core(Config.XLEN))
    val brom = Module(new BROM(Config.XLEN, Config.romDepth, Config.romInit))
	val sram = Module(new Sram(Config.AXI_ADDR_WIDTH, Config.AXI_DATA_WIDTH, Config.ramDepth, Config.ramDataBytes))

	// val sramAXI = Module(new AXI4Seq(Config.AXI_ADDR_WIDTH, Config.AXI_DATA_WIDTH, new SramIO(Config.AXI_ADDR_WIDTH, Config.ramDataBytes)))

	brom.io.fetch_en := core.io.fetch_en
	brom.io.addr := core.io.pc
	core.io.instr := brom.io.instr

	// sramAXI.io.master <> DontCare
	// sramAXI.io.slave <> sram.io
	sram.io <> DontCare
	dontTouch(sram.io)

	// Debug ports
	io.debug.pc := core.io.pc
	io.debug.instr := core.io.instr
	// Difftest ports
	// io.uart.in.valid := false.B
	// io.uart.out.valid := false.B
	// io.uart.out.ch := 0.U
}


