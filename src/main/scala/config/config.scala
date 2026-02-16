package soc.config

import chisel3._

import soc.isa.Extension

object Config {
    val XLEN: Int                        = 64
    val enabledExt: Set[Extension.Value] = Set(Extension.RV64I, Extension.RV64M)

    val romDepth: Int     = 16384 // 16 KB
    val ramDepth: Int     = 65536 // 64 KB
    val ramDataBytes: Int = XLEN / 8
    val romInit: Seq[Int] = Seq.fill(romDepth)(0)

    val resetVector: BigInt = 0x00000000

    val AXI_ADDR_WIDTH: Int = 32
    val AXI_DATA_WIDTH: Int = XLEN
}
