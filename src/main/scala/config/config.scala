package soc.config

import chisel3._

import soc.isa.Extension

object InterruptControllerKind extends Enumeration {
    val None, PLIC, AIA = Value
}

case class SoCFeatures(
    mmu: Boolean = false,
    iCache: Boolean = false,
    dCache: Boolean = true,
    uart: Boolean = true,
    clint: Boolean = true,
    interruptController: InterruptControllerKind.Value = InterruptControllerKind.PLIC
)

object SoCProfiles {
    val MinimalMCU: SoCFeatures = SoCFeatures(
        mmu = false,
        iCache = false,
        dCache = false,
        uart = true,
        clint = true,
        interruptController = InterruptControllerKind.None
    )

    val LinuxCapablePLIC: SoCFeatures = SoCFeatures(
        mmu = true,
        iCache = true,
        dCache = true,
        uart = true,
        clint = true,
        interruptController = InterruptControllerKind.PLIC
    )

    val ModernAIA: SoCFeatures = LinuxCapablePLIC.copy(interruptController = InterruptControllerKind.AIA)
}

case class AddressRegion(name: String, base: BigInt, size: BigInt) {
    def contains(addr: UInt, addrWidth: Int): Bool = {
        addr >= base.U(addrWidth.W) && addr < (base + size).U(addrWidth.W)
    }
}

object Config {
    val XLEN: Int                        = 64
    val enabledExt: Set[Extension.Value] = Set(Extension.RV64I, Extension.Zicsr, Extension.Zifencei, Extension.RV64M, Extension.RV64A)
    val features: SoCFeatures            = SoCFeatures()

    val DebugBase: BigInt = 0x00000000L
    val DebugSize: BigInt = 0x4000L
    val RomBase: BigInt   = 0x80000000L
    val RomSize: BigInt   = 0x20000L
    val SramBase: BigInt  = 0x10000000L
    val SramSize: BigInt  = 0x10000L    // 64 KB
    val UartBase: BigInt  = 0x10010000L // after SRAM region
    val UartSize: BigInt  = 0x1000L
    val ClintBase: BigInt = 0x2000000L  // CLINT base
    val ClintSize: BigInt = 0x10000L    // 64 KB
    val PlicBase: BigInt  = 0x0c000000L
    val PlicSize: BigInt  = 0x4000000L  // standard PLIC aperture
    val plicSources: Int  = 31
    val UartPlicSource: Int = 1

    val romDepth: Int     = 16384 // 16 KB
    val ramDepth: Int     = 65536 // 64 KB
    val ramDataBytes: Int = XLEN / 8
    val romInit: Seq[Int] = Seq.fill(romDepth)(0)

    val resetVector: BigInt = RomBase

    val DebugRegion: AddressRegion = AddressRegion("debug", DebugBase, DebugSize)
    val RomRegion: AddressRegion   = AddressRegion("rom", RomBase, RomSize)
    val SramRegion: AddressRegion  = AddressRegion("sram", SramBase, SramSize)
    val UartRegion: AddressRegion  = AddressRegion("uart", UartBase, UartSize)
    val ClintRegion: AddressRegion = AddressRegion("clint", ClintBase, ClintSize)
    val PlicRegion: AddressRegion  = AddressRegion("plic", PlicBase, PlicSize)

    val alwaysOnRegions: Seq[AddressRegion]                           = Seq(DebugRegion, RomRegion, SramRegion)
    def optionalRegionsFor(features: SoCFeatures): Seq[AddressRegion] =
        Seq(
            Option.when(features.uart)(UartRegion),
            Option.when(features.clint)(ClintRegion),
            Option.when(features.interruptController == InterruptControllerKind.PLIC)(PlicRegion)
        ).flatten

    def mmioRegionsFor(features: SoCFeatures): Seq[AddressRegion] = alwaysOnRegions ++ optionalRegionsFor(features)
    def mmioMapFor(features: SoCFeatures): Seq[(BigInt, BigInt)]  =
        mmioRegionsFor(features).map(region => (region.base, region.size))
    def addrMapFor(features: SoCFeatures): Seq[UInt => Bool] = mkAddrMap(mmioRegionsFor(features), XLEN)

    val optionalRegions: Seq[AddressRegion] = optionalRegionsFor(features)
    val MMIORegions: Seq[AddressRegion]     = alwaysOnRegions ++ optionalRegions
    val MMIOMap: Seq[(BigInt, BigInt)]      = MMIORegions.map(region => (region.base, region.size))
    val addrMap: Seq[UInt => Bool]          = mkAddrMap(MMIORegions, XLEN)

    // Reserved MMIO ranges remain device/uncached even when the device is not
    // instantiated, so accesses reach TLXbar and receive a denied response.
    val deviceRegions: Seq[AddressRegion] = Seq(DebugRegion, RomRegion, UartRegion, ClintRegion, PlicRegion)

    private def mkAddrMap(ranges: Seq[AddressRegion], addrWidth: Int): Seq[UInt => Bool] = {
        ranges.map(region => (addr: UInt) => region.contains(addr, addrWidth))
    }

}
