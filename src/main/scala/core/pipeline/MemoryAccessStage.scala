package soc.core.pipeline

import chisel3._
import chisel3.util._

import soc.config.Config

class MemoryAccessStage(XLEN: Int = 64) extends Module {
    val io = IO(new Bundle {
        val in    = Input(new MemoryAccessInfo(XLEN))
        val cfg   = Input(new MemorySystemConfig(XLEN))
        val out   = Output(new MemoryAccessInfo(XLEN))
        val fault = Output(new MemoryFaultInfo(XLEN))
    })

    val inDebug = io.in.vaddr >= Config.DebugBase.U(XLEN.W) && io.in.vaddr < (Config.DebugBase + Config.DebugSize).U(XLEN.W)
    val inRom   = io.in.vaddr >= Config.RomBase.U(XLEN.W) && io.in.vaddr < (Config.RomBase + Config.RomSize).U(XLEN.W)
    val inSram  = io.in.vaddr >= Config.SramBase.U(XLEN.W) && io.in.vaddr < (Config.SramBase + Config.ramDepth).U(XLEN.W)
    val inUart  = io.in.vaddr >= Config.UartBase.U(XLEN.W) && io.in.vaddr < (Config.UartBase + Config.UartSize).U(XLEN.W)

    val translateEnabled = io.in.valid && (io.cfg.satp =/= 0.U) && !io.in.attrs.device

    io.out := io.in
    io.out.paddr := io.in.vaddr
    io.out.attrs.cacheable  := inSram
    io.out.attrs.device     := inUart || inDebug
    io.out.attrs.bufferable := inSram
    io.out.attrs.allocate   := inSram
    io.out.attrs.translate  := translateEnabled
    io.out.attrs.executable := inRom

    io.fault.valid := false.B
    io.fault.cause := 0.U
    io.fault.value := io.in.vaddr
}