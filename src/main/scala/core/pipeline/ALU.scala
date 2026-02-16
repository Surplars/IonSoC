package soc.core.pipeline

import chisel3._
import chisel3.util._

import soc.isa.CtrlSignals

class ALU extends Module {
    val io = IO(new Bundle {
        val op1     = Input(UInt(64.W))
        val op2     = Input(UInt(64.W))
        val ctrl_in = Input(new CtrlSignals)

        val result = Output(UInt(64.W))
    })

    io.result := 0.U // Placeholder
}
