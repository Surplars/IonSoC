package soc.debug

import chisel3._

class DebugIO extends Bundle {
    val pc = Output(UInt(64.W))
    val instr = Output(UInt(32.W))
}

class DebugCacheControl extends Bundle {
    val dcacheReq = Output(Bool())
    val icacheReq = Output(Bool())
    val dcacheAck = Input(Bool())
    val icacheAck = Input(Bool())
    val dcacheBusy = Input(Bool())
    val icacheBusy = Input(Bool())
    val dcacheErr = Input(Bool())
    val icacheErr = Input(Bool())
}
