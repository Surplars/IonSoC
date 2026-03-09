package soc.isa

import chisel3._
import chisel3.util._

import soc.core.pipeline.BranchType
import soc.core.pipeline.ALUOps
import soc.core.pipeline.OpSel
import soc.core.pipeline.CSROps

object Common {
    val instrNop = "h00000013".U(32.W) // addi x0, x0, 0
}

trait InstrProvider {
    val Y = true.B
    val N = false.B

    // List(valid, op1sel, op2sel, ALUOps, reg_write, mem_read, mem_write, CSROps, BranchType)
    // 普通 ALU 指令 不需要 Mem, CSR, Branch
    def ALU(
        op: ALUOps.Type,
        op1: OpSel.Type,
        op2: OpSel.Type,
        mem_read: Boolean = false,
        mem_write: Boolean = false,
        writeReg: Boolean = true,
        brType: BranchType.Type = BranchType.None
    ): List[Data] = {
        List(Y, op1, op2, op, writeReg.B, mem_read.B, mem_write.B, CSROps.None, brType)
    }
    // 内存访问指令
    def MEM(isLoad: Boolean, op1: OpSel.Type, op2: OpSel.Type, op: ALUOps.Type = ALUOps.NOP): List[Data] = {
        List(Y, op1, op2, op, isLoad.B, isLoad.B, (!isLoad).B, CSROps.None, BranchType.None)
    }
    // 分支指令
    def BR(brType: BranchType.Type): List[Data] = {
        List(Y, OpSel.RS1, OpSel.RS2, ALUOps.NOP, N, N, N, CSROps.None, brType)
    }
    // CSR指令
    def CSR(csrOp: CSROps.Type): List[Data] = {
        List(Y, OpSel.CSR, OpSel.RS1, ALUOps.NOP, Y, N, N, csrOp, BranchType.None)
    }

    def UStr(u: UInt, width: Int): String = {
        // u.litValue 获取 BigInt, toString(2) 转二进制
        val s = u.litValue.toString(2)
        // 补前导零
        if (s.length < width) "0" * (width - s.length) + s else s
    }

    def genPat(funct7: String, funct3: UInt, opcode: UInt): BitPat = {
        BitPat("b" + funct7 + "_?????_?????_" + UStr(funct3, 3) + "_?????_" + UStr(opcode, 7))
    }
    // 对于没有 funct7 的情况
    def genPat(funct3: UInt, opcode: UInt): BitPat = {
        BitPat("b???????_?????_?????_" + UStr(funct3, 3) + "_?????_" + UStr(opcode, 7))
    }
    // 只有 opcode 的情况
    def genPat(opcode: UInt): BitPat = {
        BitPat("b???????_?????_?????_???_?????_" + UStr(opcode, 7))
    }

    def instructions: Array[InstrEntry]
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
    val OP_IMM    = "b0010011".U(7.W)
    val OP_IMM_32 = "b0011011".U(7.W) // RV64I
    val BRANCH    = "b1100011".U(7.W)
    val LOAD      = "b0000011".U(7.W)
    val STORE     = "b0100011".U(7.W)
    val MISC_MEM  = "b0001111".U(7.W)
    val SYSTEM    = "b1110011".U(7.W)
    val OP        = "b0110011".U(7.W)
	val OP_32     = "b0111011".U(7.W) // RV64I
    val JAL       = "b1101111".U(7.W)
    val JALR      = "b1100111".U(7.W)
    val LUI       = "b0110111".U(7.W)
    val AUIPC     = "b0010111".U(7.W)
}

object Funct3 {
    object I {
        // BRANCH
        val BEQ  = "b000".U(3.W)
        val BNE  = "b001".U(3.W)
        val BLT  = "b100".U(3.W)
        val BGE  = "b101".U(3.W)
        val BLTU = "b110".U(3.W)
        val BGEU = "b111".U(3.W)
        // LOAD/STORE
        val LB  = "b000".U(3.W)
        val LH  = "b001".U(3.W)
        val LW  = "b010".U(3.W)
        val LBU = "b100".U(3.W)
        val LHU = "b101".U(3.W)
        val SB  = "b000".U(3.W)
        val SH  = "b001".U(3.W)
        val SW  = "b010".U(3.W)
        val LWU = "b110".U(3.W) // RV64I
        val LD  = "b011".U(3.W) // RV64I
        val SD  = "b011".U(3.W) // RV64I
        // OP-IMM
        val ADDI      = "b000".U(3.W)
        val SLTI      = "b010".U(3.W)
        val SLTIU     = "b011".U(3.W)
        val XORI      = "b100".U(3.W)
        val ORI       = "b110".U(3.W)
        val ANDI      = "b111".U(3.W)
        val SLLI      = "b001".U(3.W)
        val SRLI_SRAI = "b101".U(3.W)
        val ADDSUB    = "b000".U(3.W)
        val SLL       = "b001".U(3.W)
        val SLT       = "b010".U(3.W)
        val SLTU      = "b011".U(3.W)
        val XOR       = "b100".U(3.W)
        val SRL_SRA   = "b101".U(3.W)
        val OR        = "b110".U(3.W)
        val AND       = "b111".U(3.W)
		// OP-IMM-32 (RV64I)
		val ADDIW = "b000".U(3.W)
		val SLLIW = "b001".U(3.W)
		val SRLIW = "b101".U(3.W)
		val SRAIW = "b101".U(3.W)
		// OP-32 (RV64I)
		val ADDW  = "b000".U(3.W)
		val SLLW  = "b001".U(3.W)
		val SRLW  = "b101".U(3.W)
		val SRAW  = "b101".U(3.W)
    }

    object M {
        val MUL    = "b000".U(3.W)
        val MULH   = "b001".U(3.W)
        val MULHSU = "b010".U(3.W)
        val MULHU  = "b011".U(3.W)
        val DIV    = "b100".U(3.W)
        val DIVU   = "b101".U(3.W)
        val REM    = "b110".U(3.W)
        val REMU   = "b111".U(3.W)
    }
}

object Funct7 {
    val Z  = "0000000"
    val NZ = "0100000"
}
