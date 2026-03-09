package soc.core

import chisel3._
import pipeline.BranchPredictor
import pipeline.BranchInfo

class PC(XLEN: Int, RESET_VECTOR: BigInt) extends Module {
    val io = IO(new Bundle {
        val br_info    = Input(new BranchInfo(XLEN))
        val stall      = Input(Bool())
        val trap_valid = Input(Bool())
        val trap_ret   = Input(Bool())
        val trap_pc    = Input(UInt(XLEN.W))
        val trap_epc   = Input(UInt(XLEN.W))

        val fetch_en   = Output(Bool())
        val pc_out     = Output(UInt(XLEN.W))
        val pred_taken = Output(Bool())
        val redirect   = Output(Bool())
    })

    val rst            = RegInit(true.B)
    val ProgramCounter = RegInit(RESET_VECTOR.U(XLEN.W))
    val bpu            = Module(new BranchPredictor(512))
    val redirect       = io.br_info.redirect || io.trap_valid || io.trap_ret

    bpu.io.req_pc := ProgramCounter

    val pc_p4   = ProgramCounter + 4.U
    val pred_pc = Mux(bpu.io.pred_taken, bpu.io.pred_target, pc_p4)

    when(rst) {
        ProgramCounter := RESET_VECTOR.U
        io.fetch_en    := false.B
        rst            := false.B
    }.elsewhen(redirect) {
        ProgramCounter := Mux(io.trap_ret, io.trap_epc, Mux(io.trap_valid, io.trap_pc, io.br_info.target))
        io.fetch_en    := true.B
    }.elsewhen(io.stall) {
        ProgramCounter := ProgramCounter
        io.fetch_en    := true.B
    }.otherwise {
        ProgramCounter := pred_pc
        io.fetch_en    := true.B
    }

    io.pc_out     := ProgramCounter
    io.fetch_en   := true.B
    io.pred_taken := bpu.io.pred_taken
    io.redirect   := redirect

    bpu.io.update_valid  := io.br_info.valid
    bpu.io.update_pc     := io.br_info.pc
    bpu.io.update_taken  := io.br_info.taken
    bpu.io.update_target := io.br_info.target
    bpu.io.update_is_br  := io.br_info.is_branch
}
