package soc.core

import chisel3._
import soc.core.pipeline.BranchInfo
import soc.isa.CSR

class SatpWriteBarrier(XLEN: Int) extends Module {
    val io = IO(new Bundle {
        val decodeValid = Input(Bool())
        val decodeCsrWrite = Input(Bool())
        val decodeCsrAddr = Input(UInt(12.W))
        val lsuMemoryIdle = Input(Bool())

        val commitValid = Input(Bool())
        val commitCsrWrite = Input(Bool())
        val commitCsrAddr = Input(UInt(12.W))
        val commitPc = Input(UInt(XLEN.W))
        val commitInstrLen = Input(UInt(2.W))

        val holdDecode = Output(Bool())
        val frontendFlush = Output(Bool())
        val redirect = Output(new BranchInfo(XLEN))
    })

    private val decodeSatpWrite = io.decodeValid && io.decodeCsrWrite && io.decodeCsrAddr === CSR.SATP

    io.holdDecode := decodeSatpWrite && !io.lsuMemoryIdle
    io.frontendFlush := false.B
    io.redirect.pc := 0.U
    io.redirect.valid := false.B
    io.redirect.is_branch := false.B
    io.redirect.taken := false.B
    io.redirect.target := 0.U
    io.redirect.redirect := false.B
}
