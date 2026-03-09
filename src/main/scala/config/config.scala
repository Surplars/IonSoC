package soc.config

import chisel3._

import soc.isa.Extension

object Config {
    val XLEN: Int                        = 64
    val enabledExt: Set[Extension.Value] = Set(Extension.RV64I, Extension.Zicsr, Extension.RV64M)

    val DebugBase: BigInt = 0x00000000L
    val DebugSize: BigInt = 0x4000L
    val RomBase: BigInt   = 0x80000000L
    val RomSize: BigInt   = 0x20000L
    val SramBase: BigInt  = 0x10000000L
    val UartBase: BigInt  = 0x10001000L
    val UartSize: BigInt  = 0x1000L

    val romDepth: Int     = 16384 // 16 KB
    val ramDepth: Int     = 65536 // 64 KB
    val ramDataBytes: Int = XLEN / 8
    val romInit: Seq[Int] = Seq.fill(romDepth)(0)

    val resetVector: BigInt = 0x00000000

    // val AXI_ADDR_WIDTH: Int = 32
    // val AXI_DATA_WIDTH: Int = XLEN

    val MMIOMap: Seq[(BigInt, BigInt)] = Seq(
        (DebugBase, DebugSize),
        // ROM
        (RomBase, RomSize),
        // SRAM
        (SramBase, ramDepth),
        // UART
        (UartBase, UartSize)
    )
    val addrMap = mkAddrMap(MMIOMap, XLEN)

    private def mkAddrMap(ranges: Seq[(BigInt, BigInt)], addrWidth: Int): Seq[UInt => Bool] = {
        ranges.map { case (base, size) =>
            val start = base
            val end   = base + size
            (addr: UInt) => (addr >= start.U(addrWidth.W)) && (addr < end.U(addrWidth.W))
        }
    }

}
