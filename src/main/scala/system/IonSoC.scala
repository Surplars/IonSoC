package soc

import chisel3._
import _root_.circt.stage.ChiselStage

import soc.config.Config
import soc.config.InterruptControllerKind
import soc.config.SoCFeatures
import soc.core._
import soc.device.BROM
import soc.device.TLROM
import soc.device.UartTx
import soc.device.CLINT
import soc.device.TLError
import soc.device.interrupt.PLIC
import soc.bus.tilelink.TLXbar
import soc.bus.tilelink.TLParams
import soc.bus.tilelink.TLRAM
import soc.bus.tilelink.TLBundle
import soc.isa.Extension

class IonSoC(
    features: SoCFeatures = Config.features,
    enabledExt: Set[Extension.Value] = Config.enabledExt
) extends Module {
    private val tlParams = TLParams()
    private val dbusParams = tlParams.copy(sourceBits = tlParams.sourceBits + 2)
    private val mmioRegions = Config.mmioRegionsFor(features)
    private val slaveCount = mmioRegions.length

    val io = IO(new Bundle {
        val debug     = new soc.debug.DebugIO
        val uart_tx   = Output(Bool())
        val uart_byte = Output(UInt(8.W))
        val ext_irq_sources = Input(Vec(Config.plicSources + 1, Bool()))
    })

    val core  = Module(new Core(Config.XLEN, hartID = 0, features, enabledExt))
    val brom  = Module(new BROM(Config.XLEN, Config.romDepth, Config.romInit))
    val sram  = Module(new TLRAM(dbusParams, Config.ramDepth))
    val debugError = Module(new TLError(dbusParams))
    val tlrom = Module(new TLROM(dbusParams))
    val uart  = if (features.uart) Some(Module(new UartTx(dbusParams))) else None
    val clint = if (features.clint) Some(Module(new CLINT(dbusParams))) else None
    val plic  = if (features.interruptController == InterruptControllerKind.PLIC) Some(Module(new PLIC(dbusParams, Config.plicSources))) else None

    val TLCrossbar = Module(new TLXbar(dbusParams, 1, slaveCount, Config.addrMapFor(features)))

    brom.io.fetch_en := core.io.fetch_en
    brom.io.addr     := core.io.pc
    core.io.instr    := brom.io.instr
    core.io.msip     := clint.map(_.io.msip).getOrElse(false.B)
    core.io.mtip     := clint.map(_.io.mtip).getOrElse(false.B)
    core.io.meip     := plic.map(_.io.meip).getOrElse(false.B)
    core.io.ssip     := false.B
    core.io.stip     := false.B
    core.io.seip     := plic.map(_.io.seip).getOrElse(false.B)

    plic.foreach { device =>
        device.io.sources := io.ext_irq_sources
    }

    TLCrossbar.io.masters(0) <> core.io.DBus

    private var slaveIndex = 0
    private def connectSlave(tl: TLBundle): Unit = {
        TLCrossbar.io.slaves(slaveIndex) <> tl
        slaveIndex += 1
    }

    connectSlave(debugError.io.tl)
    connectSlave(tlrom.io.tl)
    connectSlave(sram.io.tl)
    uart.foreach(device => connectSlave(device.io.tl))
    clint.foreach(device => connectSlave(device.io.tl))
    plic.foreach(device => connectSlave(device.io.tl))
    require(slaveIndex == slaveCount, s"connected $slaveIndex slaves, expected $slaveCount")

    core.io.IBus <> DontCare
    dontTouch(sram.io)
    uart.foreach(device => dontTouch(device.io))
    clint.foreach(device => dontTouch(device.io))
    plic.foreach(device => dontTouch(device.io))

    // Debug ports
    io.debug.pc    := core.io.pc
    io.debug.instr := core.io.instr

    // UART TX outputs
    io.uart_tx   := uart.map(_.io.tx_valid).getOrElse(false.B)
    io.uart_byte := uart.map(_.io.tx_byte).getOrElse(0.U)
}
