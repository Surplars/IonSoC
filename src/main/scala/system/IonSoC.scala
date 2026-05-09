package soc

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

import soc.config.Config
import soc.config.InterruptControllerKind
import soc.config.SoCFeatures
import soc.core._
import soc.device.BROM
import soc.device.TLROM
import soc.device.UartTx
import soc.device.CLINT
import soc.debug.DebugModule
import soc.debug.JtagTap
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
        val uart_rx_valid = Input(Bool())
        val uart_rx_byte  = Input(UInt(8.W))
        val uart_tx   = Output(Bool())
        val uart_byte = Output(UInt(8.W))
        val ext_irq_sources = Input(Vec(Config.plicSources + 1, Bool()))
        val jtag_tms = Input(Bool())
        val jtag_tck = Input(Bool())
        val jtag_tdi = Input(Bool())
        val jtag_tdo = Output(Bool())
    })

    val core  = Module(new Core(Config.XLEN, hartID = 0, features, enabledExt))
    val brom  = Module(new BROM(Config.XLEN, Config.romDepth, Config.romInit))
    val sram  = Module(new TLRAM(dbusParams, features.sramSizeBytes, features.sramBase))
    val debugModule = Module(new DebugModule(dbusParams))
    val tlrom = Module(new TLROM(dbusParams))
    val uart  = if (features.uart) Some(Module(new UartTx(dbusParams))) else None
    val clint = if (features.clint) Some(Module(new CLINT(dbusParams))) else None
    val plic  = if (features.interruptController == InterruptControllerKind.PLIC) Some(Module(new PLIC(dbusParams, Config.plicSources))) else None
    val jtag  = Module(new JtagTap(drLen = Config.XLEN))

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
    core.io.debug_haltreq := debugModule.io.haltreq
    core.io.debug_resumereq := debugModule.io.resumereq
    core.io.debug_gpr_addr := debugModule.io.gpr_addr
    core.io.debug_gpr_write := debugModule.io.gpr_write
    core.io.debug_gpr_wdata := debugModule.io.gpr_wdata
    core.io.debug_csr_addr := debugModule.io.csr_addr
    core.io.debug_csr_write := debugModule.io.csr_write
    core.io.debug_csr_wdata := debugModule.io.csr_wdata

    jtag.io.jtag.tms := io.jtag_tms
    jtag.io.jtag.tck := io.jtag_tck
    jtag.io.jtag.tdi := io.jtag_tdi
    io.jtag_tdo := jtag.io.jtag.tdo
    jtag.io.dr_in := Cat(0.U(23.W), 0.U(2.W), debugModule.io.dmi_rdata, 0.U(2.W))
    debugModule.io.dmi_valid := RegNext(jtag.io.update_dr, false.B)
    debugModule.io.dmi_write := jtag.io.dr_out(1, 0) === 2.U
    debugModule.io.dmi_addr  := jtag.io.dr_out(40, 34)
    debugModule.io.dmi_wdata := jtag.io.dr_out(33, 2)
    debugModule.io.hart_halted := core.io.debug_halted
    debugModule.io.gpr_rdata := core.io.debug_gpr_rdata
    debugModule.io.csr_rdata := core.io.debug_csr_rdata
    debugModule.io.csr_valid := core.io.debug_csr_valid
    debugModule.io.csr_writable := core.io.debug_csr_writable

    uart.foreach { device =>
        device.io.rx_valid := io.uart_rx_valid
        device.io.rx_byte  := io.uart_rx_byte
    }

    plic.foreach { device =>
        device.io.sources := io.ext_irq_sources
        uart.foreach { uartDevice =>
            device.io.sources(Config.UartPlicSource) := io.ext_irq_sources(Config.UartPlicSource) || uartDevice.io.irq
        }
    }

    TLCrossbar.io.masters(0) <> core.io.DBus

    private var slaveIndex = 0
    private def connectSlave(tl: TLBundle): Unit = {
        TLCrossbar.io.slaves(slaveIndex) <> tl
        slaveIndex += 1
    }

    connectSlave(debugModule.io.tl)
    connectSlave(tlrom.io.tl)
    connectSlave(sram.io.tl)
    uart.foreach(device => connectSlave(device.io.tl))
    clint.foreach(device => connectSlave(device.io.tl))
    plic.foreach(device => connectSlave(device.io.tl))
    require(slaveIndex == slaveCount, s"connected $slaveIndex slaves, expected $slaveCount")

    core.io.IBus <> DontCare
    dontTouch(sram.io)
    dontTouch(jtag.io)
    uart.foreach(device => dontTouch(device.io))
    clint.foreach(device => dontTouch(device.io))
    plic.foreach(device => dontTouch(device.io))

    // Debug ports
    io.debug.pc    := core.io.pc
    io.debug.instr := core.io.debug_instr

    // UART TX outputs
    io.uart_tx   := uart.map(_.io.tx_valid).getOrElse(false.B)
    io.uart_byte := uart.map(_.io.tx_byte).getOrElse(0.U)
}
