package soc.core.pipeline

import chisel3._
import chisel3.util._

object OpSel extends ChiselEnum {
    val ZERO, RS1, RS2, IMM, PC, CSR, MEM = Value
}

object CSROps extends ChiselEnum {
    val None, RW, RS, RC, RWI, RSI, RCI = Value
}

object ALUOps extends ChiselEnum {
    val NOP, ADD, SUB, OR, AND, XOR, SLL, SLT, SLTU, SRL, SRA, ADDW, SUBW, SLLW, SRLW, SRAW = Value
}

object MemOpType extends ChiselEnum {
    val None, Load, Store, Fence, FenceI, LR, SC, AMO = Value
}

object AtomicOpType extends ChiselEnum {
    val None, Swap, Add, Xor, And, Or, Min, Max, MinU, MaxU = Value
}

class MemoryAttrs extends Bundle {
    val cacheable  = Bool()
    val device     = Bool()
    val bufferable = Bool()
    val allocate   = Bool()
    val translate  = Bool()
    val executable = Bool()
}

class MemoryAccessInfo(XLEN: Int) extends Bundle {
    val valid  = Bool()
    val op     = MemOpType.Type()
    val vaddr  = UInt(XLEN.W)
    val paddr  = UInt(XLEN.W)
    val size   = UInt(3.W)
    val signed = Bool()
    val mask   = UInt((XLEN / 8).W)
    val wdata  = UInt(XLEN.W)
    val atomic = AtomicOpType.Type()
    val aq     = Bool()
    val rl     = Bool()
    val attrs  = new MemoryAttrs
}

class MemorySystemConfig(XLEN: Int) extends Bundle {
    val priv    = UInt(2.W)
    val satp    = UInt(XLEN.W)
    val pmpcfg0 = UInt(XLEN.W)
    val pmpaddr0 = UInt(XLEN.W)
    val mxr     = Bool()
    val sum     = Bool()
    val mprv    = Bool()
}

class MemoryFaultInfo(XLEN: Int) extends Bundle {
    val valid = Bool()
    val cause = UInt(XLEN.W)
    val value = UInt(XLEN.W)
}

class TrapInfo(XLEN: Int) extends Bundle {
    val valid  = Bool()
    val pc     = UInt(XLEN.W)
    val cause  = UInt(XLEN.W)
    val value  = UInt(XLEN.W)
    val is_ret = Bool()
}

object BranchType extends ChiselEnum {
    val None                           = Value // 无分支
    val JAL                            = Value // 无条件直接分支
    val JALR                           = Value // 无条件间接分支
    val BEQ, BNE, BLT, BGE, BLTU, BGEU = Value // 条件分支
    val MRET, SRET, MNRET, ECALL       = Value // 机器/超级/用户模式下的返回指令
    val WFI                            = Value // 等待中断指令
}

class BranchInfo(XLEN: Int) extends Bundle {
    val pc        = UInt(XLEN.W) // 分支指令的PC
    val valid     = Bool()       // 是否分支指令
    val is_branch = Bool()       // 是否为条件分支指令
    val taken     = Bool()       // 实际是否跳转
    val target    = UInt(XLEN.W) // 实际目标地址
    val redirect  = Bool()       // 是否需要重定向
}

class InstrSignals extends Bundle {
    val alu_op      = ALUOps.Type()
    val reg_write   = Bool()
    val mem_read    = Bool()
    val mem_write   = Bool()
    val mem_fence   = Bool()
    val mem_fence_i = Bool()
    val mem_atomic  = Bool()
    val csr_op      = CSROps.Type()
    val branch_type = BranchType.Type()
}

class DecodedInstr(XLEN: Int) extends Bundle {
    val rs1     = UInt(5.W)
    val rs2     = UInt(5.W)
    val op1     = UInt(XLEN.W)
    val op2     = UInt(XLEN.W)
    val rd      = UInt(5.W)
    val ctrl    = new InstrSignals
    val funct3  = UInt(3.W)
    val op2_sel = OpSel.Type()
    val br_imm  = Output(UInt(XLEN.W))
    val mem_imm = Output(UInt(XLEN.W))
}

class ALUOut(XLEN: Int) extends Bundle {
    val result    = UInt(XLEN.W)
    val funct3    = UInt(3.W)
    val rd        = UInt(5.W)
    val reg_write = Bool()
    val mem_read  = Bool()
    val mem_write = Bool()
    val mem_addr  = UInt(XLEN.W)
    val mem       = new MemoryAccessInfo(XLEN)
}

class MemOut(XLEN: Int) extends Bundle {
    val result    = UInt(XLEN.W)
    val rd        = UInt(5.W)
    val reg_write = Bool()
}

class RegWrite(XLEN: Int) extends Bundle {
    val data      = UInt(XLEN.W)
    val rd        = UInt(5.W)
    val reg_write = Bool()
}

class FwdSignals(XLEN: Int) extends Bundle {
    val load_valid = Bool()
    val load_data  = UInt(XLEN.W)
    val rd         = UInt(5.W)
    val alu_result = UInt(XLEN.W)
    val reg_write  = Bool()
}
