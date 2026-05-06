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

    private def inRegion(region: soc.config.AddressRegion): Bool = region.contains(io.in.vaddr, XLEN)

    val inRom    = inRegion(Config.RomRegion)
    val inSram   = inRegion(Config.SramRegion)
    val inDevice = Config.deviceRegions.map(inRegion).reduce(_ || _)

    val translateEnabled = io.in.valid && io.cfg.mmu_en && (io.cfg.satp =/= 0.U) && !io.in.attrs.device

    io.out := io.in
    io.out.paddr := io.in.vaddr
    io.out.attrs.cacheable  := inSram
    io.out.attrs.device     := inDevice
    io.out.attrs.bufferable := inSram
    io.out.attrs.allocate   := inSram
    io.out.attrs.translate  := translateEnabled
    io.out.attrs.executable := inRom

    io.fault.valid := false.B
    io.fault.cause := 0.U
    io.fault.value := io.in.vaddr
}
