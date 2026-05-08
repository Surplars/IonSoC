package soc.core.pipeline

import chisel3._
import chisel3.util._

import soc.config.Config
import soc.isa.MCause
import soc.isa.PrivilegeLevel

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
    val isLoad = io.in.op === MemOpType.Load || io.in.op === MemOpType.LR
    val isStore = io.in.op === MemOpType.Store || io.in.op === MemOpType.SC || io.in.op === MemOpType.AMO

    val pmp0Cfg = io.cfg.pmpcfg0(7, 0)
    val pmp0Read = pmp0Cfg(0)
    val pmp0Write = pmp0Cfg(1)
    val pmp0Lock = pmp0Cfg(7)
    val pmp0Mode = pmp0Cfg(4, 3)

    val accessBytes = 1.U(XLEN.W) << io.in.size
    val accessStart = io.in.vaddr
    val accessEnd = io.in.vaddr + accessBytes - 1.U
    val pmpAddrBytes = (io.cfg.pmpaddr0 << 2)(XLEN - 1, 0)

    val pmp0TorMatch = accessEnd < pmpAddrBytes
    val pmp0Na4Match = accessStart >= pmpAddrBytes && accessEnd < pmpAddrBytes + 4.U

    // NAPOT encodes the region size in the low bits of pmpaddr. The generated
    // byte mask covers the naturally aligned region selected by pmpaddr0.
    val pmp0NapotMask = (((io.cfg.pmpaddr0 ^ (io.cfg.pmpaddr0 + 1.U)) << 2) | 3.U)(XLEN - 1, 0)
    val pmp0NapotBase = pmpAddrBytes & ~pmp0NapotMask
    val pmp0NapotMatch = (accessStart & ~pmp0NapotMask) === pmp0NapotBase &&
        (accessEnd & ~pmp0NapotMask) === pmp0NapotBase

    val pmp0Match = MuxLookup(pmp0Mode, false.B)(
        Seq(
            1.U -> pmp0TorMatch,
            2.U -> pmp0Na4Match,
            3.U -> pmp0NapotMatch
        )
    )
    val pmp0PermOk = Mux(isStore, pmp0Write, pmp0Read)
    val pmpFault = io.in.valid && (isLoad || isStore) && Mux(
        io.cfg.priv === PrivilegeLevel.Machine,
        pmp0Lock && pmp0Match && !pmp0PermOk,
        !pmp0Match || !pmp0PermOk
    )

    io.out := io.in
    io.out.paddr := io.in.vaddr
    io.out.attrs.cacheable  := inSram
    io.out.attrs.device     := inDevice
    io.out.attrs.bufferable := inSram
    io.out.attrs.allocate   := inSram
    io.out.attrs.translate  := translateEnabled
    io.out.attrs.executable := inRom

    io.fault.valid := pmpFault
    io.fault.cause := Mux(isStore, MCause.StoreAccessFault, MCause.LoadAccessFault)
    io.fault.value := io.in.vaddr
}
