package soc

import chisel3._
import _root_.circt.stage.ChiselStage

import soc.config.Config
import soc.core._

import soc.device.BROM
import soc.device.TLRegister
// import soc.device.Sram
// import soc.device.SramIO
import soc.bus.tilelink.TLXbar
import soc.bus.tilelink.TLParams
import soc.bus.tilelink.TLRAM
// import difftest._

class IonSoC extends Module {
    private val tlParams = TLParams()
    private val dbusParams = tlParams.copy(sourceBits = tlParams.sourceBits + 1)

    val io = IO(new Bundle {
        val debug = new soc.debug.DebugIO
        // val logCtrl = new LogCtrlIO
        // val perfInfo = new PerfCtrlIO
        // val uart = new UARTIO
    })

    val core = Module(new Core(Config.XLEN, hartID = 0))
    val brom = Module(new BROM(Config.XLEN, Config.romDepth, Config.romInit))
    // val sram = Module(new Sram(32, Config.XLEN, Config.ramDepth, Config.ramDataBytes))
    val sram = Module(new TLRAM(dbusParams, Config.ramDepth))
    val uart = Module(new TLRegister(dbusParams))

    // val sramAXI = Module(new AXI4Seq(Config.AXI_ADDR_WIDTH, Config.AXI_DATA_WIDTH, new SramIO(Config.AXI_ADDR_WIDTH, Config.ramDataBytes)))
    val TLCrossbar = Module(new TLXbar(dbusParams, 1, Config.MMIOMap.length, Config.addrMap))

    brom.io.fetch_en := core.io.fetch_en
    brom.io.addr     := core.io.pc
    core.io.instr    := brom.io.instr

    TLCrossbar.io.masters(0) <> core.io.DBus
    TLCrossbar.io.slaves(2) <> sram.io.tl
    TLCrossbar.io.slaves(3) <> uart.io.tl

    for (i <- Seq(0, 1)) {
        TLCrossbar.io.slaves(i).a.ready        := false.B
        TLCrossbar.io.slaves(i).d.valid        := false.B
        TLCrossbar.io.slaves(i).d.bits.opcode  := 0.U
        TLCrossbar.io.slaves(i).d.bits.param   := 0.U
        TLCrossbar.io.slaves(i).d.bits.size    := 0.U
        TLCrossbar.io.slaves(i).d.bits.source  := 0.U
        TLCrossbar.io.slaves(i).d.bits.sink    := 0.U
        TLCrossbar.io.slaves(i).d.bits.denied  := true.B
        TLCrossbar.io.slaves(i).d.bits.data    := 0.U
        TLCrossbar.io.slaves(i).d.bits.corrupt := false.B
    }

    core.io.IBus <> DontCare
    dontTouch(sram.io)
    dontTouch(uart.io)

    // Debug ports
    io.debug.pc    := core.io.pc
    io.debug.instr := core.io.instr
    // Difftest ports
    // io.uart.in.valid := false.B
    // io.uart.out.valid := false.B
    // io.uart.out.ch := 0.U
}
