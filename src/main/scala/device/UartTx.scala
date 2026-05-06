package soc.device

import chisel3._
import chisel3.util._

import soc.bus.tilelink._

class UartTx(params: TLParams) extends Module {
    val io = IO(new Bundle {
        val tl = Flipped(new TLBundle(params))
        val tx_valid = Output(Bool())
        val tx_byte  = Output(UInt(8.W))
    })

    private val beatBytes = params.dataWidth / 8

    // TL response pipeline
    val respValid  = RegInit(false.B)
    val respOpcode = RegInit(TLOpcode.AccessAck)
    val respSize   = RegInit(0.U(params.sizeBits.W))
    val respSource = RegInit(0.U(params.sourceBits.W))

    // TX output (one-cycle pulse on write)
    val txValid = RegInit(false.B)
    val txByte  = RegInit(0.U(8.W))

    val canAccept = !respValid && !txValid
    io.tl.a.ready := canAccept

    when(io.tl.a.fire) {
        val isWrite = io.tl.a.bits.opcode === TLOpcode.PutFullData ||
                      io.tl.a.bits.opcode === TLOpcode.PutPartialData
        txValid  := isWrite
        txByte   := io.tl.a.bits.data(7, 0)
        respValid := true.B
        respOpcode := Mux(isWrite, TLOpcode.AccessAck, TLOpcode.AccessAckData)
        respSize   := io.tl.a.bits.size
        respSource := io.tl.a.bits.source
    }

    // Release TX pulse after one cycle
    when(txValid) {
        txValid := false.B
    }

    io.tl.d.valid        := respValid
    io.tl.d.bits.opcode  := respOpcode
    io.tl.d.bits.param   := 0.U
    io.tl.d.bits.size    := respSize
    io.tl.d.bits.source  := respSource
    io.tl.d.bits.sink    := 0.U
    io.tl.d.bits.denied  := false.B
    io.tl.d.bits.data    := 0.U
    io.tl.d.bits.corrupt := false.B

    when(io.tl.d.fire) {
        respValid := false.B
    }

    io.tx_valid := txValid
    io.tx_byte  := txByte
}
