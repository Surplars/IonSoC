package soc.isa

import chisel3._

object Common {
    val instrNop = "h00000013".U(32.W) // addi x0, x0, 0
}

class CtrlSignals extends Bundle {
    val alu_op    = UInt(5.W)
    val reg_write = Bool()
    val mem_read  = Bool()
    val mem_write = Bool()
    val branch    = Bool()
    val jump_en   = Bool()
}

object OpSel extends ChiselEnum {
    val RS1, RS2, IMM, PC, CSR, ZERO, MEM = Value
}

class DecodedInstr extends Bundle {
    val ctrl = new CtrlSignals
    val op1  = UInt(64.W)
    val op2  = UInt(64.W)
    val rd   = UInt(5.W)
}

object ALUOps extends ChiselEnum {
    val add, sub, or, and, xor, sll, slt, sltu, srl, sra = Value
}

object Extension extends Enumeration {
    type Extension = Value

    val RV32I, RV64I, Zicsr               = Value
    val Zifencei                          = Value
    val RV32M, RV32A, RV32F, RV32D, RV32Q = Value
    val RV64M, RV64A, RV64F, RV64D, RV64Q = Value
    val RV32Zfh, Zawrs                    = Value
    val RV64Zfh                           = Value
}

object Opcode {
    val op_imm   = "b0010011".U(7.W)
    val branch   = "b1100011".U(7.W)
    val load     = "b0000011".U(7.W)
    val store    = "b0100011".U(7.W)
    val misc_mem = "b0001111".U(7.W)
    val system   = "b1110011".U(7.W)
    val op       = "b0110011".U(7.W)
}

object Funct3 {
    object I {
        val addSub = "b000".U(3.W)
        val sll    = "b001".U(3.W)
        val slt    = "b010".U(3.W)
        val sltu   = "b011".U(3.W)
        val xor    = "b100".U(3.W)
        val srlSra = "b101".U(3.W)
        val or     = "b110".U(3.W)
        val and    = "b111".U(3.W)
    }

    object M {
        val mul    = "b000".U(3.W)
        val mulh   = "b001".U(3.W)
        val mulhsu = "b010".U(3.W)
        val mulhu  = "b011".U(3.W)
        val div    = "b100".U(3.W)
        val divu   = "b101".U(3.W)
        val rem    = "b110".U(3.W)
        val remu   = "b111".U(3.W)
    }
}

object Funct7 {
    val zero  = "b0000000".U(7.W)
    val nzero = "b0100000".U(7.W)
}
