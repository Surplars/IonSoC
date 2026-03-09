package soc.core.pipeline

import chisel3._
import chisel3.util._

import soc.core.pipeline.InstrSignals
import soc.core.pipeline.ALUOps
import soc.core.pipeline.ALUOut
import soc.core.pipeline.FwdSignals
import soc.isa.MCause

class ALU(XLEN: Int = 64) extends Module {
    val io = IO(new Bundle {
        val pc_in         = Input(UInt(XLEN.W))
        val next_pc_in    = Input(UInt(XLEN.W)) // 将要到来的指令的PC，用于判断分支预测是否正确
        val valid_in      = Input(Bool())
        val trap_valid    = Input(Bool())
        val decoded_in    = Input(new DecodedInstr(XLEN))
        val pred_taken_in = Input(Bool())
        val trap_info_in  = Input(new TrapInfo(XLEN))
        val stall         = Input(Bool())

        val fwd = Input(new FwdSignals(XLEN))

        val valid_out     = Output(Bool())
        val alu_out       = Output(new ALUOut(XLEN))
        val pc_out        = Output(UInt(XLEN.W))
        val br_info       = Output(new BranchInfo(XLEN))
        val trap_info_out = Output(new TrapInfo(XLEN))
        // CSR读写
        val csr_valid   = Output(Bool())
        val csr_cmd     = Output(UInt(4.W))
        val csr_addr    = Output(UInt(12.W))
        val csr_write   = Output(Bool())
        val csr_wdata   = Output(UInt(XLEN.W))
        val csr_rdata   = Input(UInt(XLEN.W))
        val csr_illegal = Input(Bool())
    })

    val mem_read      = io.decoded_in.ctrl.mem_read
    val mem_write     = io.decoded_in.ctrl.mem_write
    val mem_fence     = io.decoded_in.ctrl.mem_fence
    val mem_fence_i   = io.decoded_in.ctrl.mem_fence_i
    val mem_atomic    = io.decoded_in.ctrl.mem_atomic
    val csr_op        = io.decoded_in.ctrl.csr_op
    val op2_sel       = io.decoded_in.op2_sel
    val mem_imm       = io.decoded_in.mem_imm
    val op1           = WireInit(0.U(XLEN.W))
    val op2           = WireInit(0.U(XLEN.W))
    val stall_op1     = RegInit(0.U(XLEN.W))
    val stall_op2     = RegInit(0.U(XLEN.W))
    val stall_valid   = RegInit(false.B)
    val alu_result    = WireInit(0.U(XLEN.W))
    val branch_target = WireInit(0.U(XLEN.W))
    val valid         = io.valid_in && !io.trap_valid

    io.csr_addr := 0.U
    val trap_info = WireInit(0.U.asTypeOf(io.trap_info_in))

    trap_info.valid  := valid && Mux(io.csr_illegal, true.B, io.trap_info_in.valid)
    trap_info.pc     := Mux(io.csr_illegal, io.pc_in, io.trap_info_in.pc)
    trap_info.cause  := Mux(io.csr_illegal, MCause.IllegalInstr, io.trap_info_in.cause)
    trap_info.value  := io.trap_info_in.value
    trap_info.is_ret := io.trap_info_in.is_ret

    // 数据转发，优先级：mem > wb > alu输入
    when(!valid) {
        op1 := 0.U
    }.elsewhen(stall_valid && !io.stall) {
        op1 := stall_op1
    }.elsewhen(csr_op =/= CSROps.None) { // CSR指令，op1来自CSR寄存器
        io.csr_addr := io.decoded_in.op1
        op1         := io.csr_rdata
    }.elsewhen(io.decoded_in.rs1 === 0.U) { // 立即数指令
        op1 := io.decoded_in.op1
    }.elsewhen(io.alu_out.reg_write && io.decoded_in.rs1 === io.alu_out.rd && io.fwd.load_valid) {
        op1 := io.fwd.load_data
    }.elsewhen(io.alu_out.reg_write && io.decoded_in.rs1 === io.alu_out.rd) {
        op1 := io.alu_out.result
    }.elsewhen(io.decoded_in.rs1 === io.fwd.rd && io.fwd.reg_write) {
        op1 := io.fwd.alu_result
    }.otherwise {
        op1 := io.decoded_in.op1
    }
    when(!valid) {
        op2 := 0.U
    }.elsewhen(stall_valid && !io.stall) {
        op2 := stall_op2
    }.elsewhen(csr_op === CSROps.RWI || csr_op === CSROps.RSI || csr_op === CSROps.RCI) {
        op2 := Cat(Fill(XLEN - 5, 0.U), io.decoded_in.rs2) // CSR zimm 指令，0扩展
    }.elsewhen(io.decoded_in.rs2 === 0.U) { // 立即数指令
        op2 := io.decoded_in.op2
    }.elsewhen(io.alu_out.reg_write && io.decoded_in.rs2 === io.alu_out.rd && io.fwd.load_valid) {
        op2 := io.fwd.load_data
    }.elsewhen(io.alu_out.reg_write && io.decoded_in.rs2 === io.alu_out.rd) {
        op2 := io.alu_out.result
    }.elsewhen(io.decoded_in.rs2 === io.fwd.rd && io.fwd.reg_write) {
        op2 := io.fwd.alu_result
    }.otherwise {
        op2 := io.decoded_in.op2
    }

    val op1_32 = op1(31, 0)
    val op2_32 = op2(31, 0)
    val shamt  = op2(4, 0).asUInt

    when(io.stall) {
        stall_valid := true.B
        stall_op1   := op1
        stall_op2   := op2
    }.otherwise {
        stall_valid := false.B
        stall_op1   := 0.U
        stall_op2   := 0.U
    }

    val branch_type  = io.decoded_in.ctrl.branch_type
    val branch_valid = valid && (branch_type =/= BranchType.None) && !io.stall
    val branch_is_br = branch_valid && (branch_type =/= BranchType.JAL)
    val branch_taken = MuxLookup(branch_type, false.B)(
        Seq(
            BranchType.None -> false.B,
            BranchType.JAL  -> true.B,
            BranchType.JALR -> true.B,
            BranchType.BEQ  -> (op1 === op2),
            BranchType.BNE  -> (op1 =/= op2),
            BranchType.BLT  -> (op1.asSInt < op2.asSInt),
            BranchType.BGE  -> (op1.asSInt >= op2.asSInt),
            BranchType.BLTU -> (op1 < op2),
            BranchType.BGEU -> (op1 >= op2)
        )
    )
    branch_target := Mux(
        (branch_type === BranchType.JAL || branch_type === BranchType.JALR),
        (op1 + op2),
        (io.pc_in + io.decoded_in.br_imm)
    )

    io.csr_valid := valid && csr_op =/= CSROps.None
    io.csr_write := io.csr_valid && io.csr_addr =/= 0.U
    io.csr_cmd   := Mux(io.csr_write, csr_op.asUInt, 0.U)
    io.csr_wdata := op2

    val mem_addr_calc = op1 + mem_imm
    val mem_size      = Mux(valid && (mem_read || mem_write), Cat(0.U(1.W), io.decoded_in.funct3(1, 0)), 0.U(3.W))
    val mem_signed    = mem_read && !io.decoded_in.funct3(2)
    val base_mask     = MuxLookup(mem_size, 0.U((XLEN / 8).W))(
        Seq(
            0.U -> 1.U,
            1.U -> 3.U,
            2.U -> 15.U,
            3.U -> 255.U
        )
    )
    val byteShift         = mem_addr_calc(log2Ceil(XLEN / 8) - 1, 0)
    val mem_mask          = base_mask << byteShift
    val mem_wdata_aligned = op2 << (byteShift << 3)
    val mem_op            = Mux(
        mem_read,
        MemOpType.Load,
        Mux(
            mem_write,
            MemOpType.Store,
            Mux(
                mem_fence,
                MemOpType.Fence,
                Mux(mem_fence_i, MemOpType.FenceI, Mux(mem_atomic, MemOpType.AMO, MemOpType.None))
            )
        )
    )

    alu_result := Mux(
        // 如果是无条件分支，则计算写回寄存器的值为PC+4，否则为正常的ALU运算结果
        (branch_type =/= BranchType.JAL && branch_type =/= BranchType.JALR),
        Mux(
            mem_write,
            op2,
            Mux(
                csr_op === CSROps.None,
                MuxLookup(io.decoded_in.ctrl.alu_op, 0.U)(
                    Seq(
                        ALUOps.ADD  -> (op1 + op2),
                        ALUOps.SUB  -> (op1 - op2),
                        ALUOps.SLL  -> (op1 << shamt),
                        ALUOps.SLT  -> (op1.asSInt < op2.asSInt).asUInt,
                        ALUOps.SLTU -> (op1 < op2).asUInt,
                        ALUOps.SRA  -> (op1.asSInt >> shamt).asUInt,
                        ALUOps.SRL  -> (op1 >> shamt),
                        ALUOps.AND  -> (op1 & op2),
                        ALUOps.OR   -> (op1 | op2),
                        ALUOps.XOR  -> (op1 ^ op2),
                        ALUOps.ADDW -> {
                            val sum33    = op1_32.asUInt +& op2_32.asUInt         // width = 33
                            val sum32    = sum33(31, 0)                           // 取低32位
                            val addw_res = Cat(Fill(32, sum32(31)), sum32).asUInt // 符号扩展到 64 位
                            addw_res
                        },
                        ALUOps.SUBW -> {
                            val diff33   = op1_32.asSInt - op2_32.asSInt // SInt，可能为 33 位
                            val diff32   = diff33.asUInt(31, 0)
                            val subw_res = Cat(Fill(32, diff32(31)), diff32)
                            subw_res
                        },
                        ALUOps.SLLW -> (op1_32 << shamt)(31, 0),
                        ALUOps.SRLW -> (op1_32 >> shamt)(31, 0),
                        ALUOps.SRAW -> (op1_32.asSInt >> shamt).asUInt(31, 0)
                    )
                ),
                op1 // CSR指令直接写回old CSR值
            )
        ),
        io.pc_in + 4.U
    )

    val update_en = !io.stall

    io.valid_out         := RegNext(valid && update_en, false.B)
    io.alu_out.funct3    := RegEnable(Mux(valid, io.decoded_in.funct3, 0.U), 0.U, update_en)
    io.alu_out.rd        := RegEnable(Mux(valid, io.decoded_in.rd, 0.U), 0.U, update_en)
    io.alu_out.reg_write := RegEnable(Mux(valid, io.decoded_in.ctrl.reg_write, false.B), false.B, update_en)
    io.alu_out.result    := RegEnable(Mux(valid, alu_result, 0.U), 0.U, update_en)
    io.alu_out.mem_read  := RegEnable(Mux(valid, mem_read, false.B), false.B, update_en)
    io.alu_out.mem_write := RegEnable(Mux(valid, mem_write, false.B), false.B, update_en)
    io.alu_out.mem_addr  := RegEnable(
        Mux(valid, Mux(mem_read || mem_write, mem_addr_calc, 0.U), 0.U),
        0.U,
        update_en
    )
    io.alu_out.mem.valid            := RegEnable(Mux(valid, mem_op =/= MemOpType.None, false.B), false.B, update_en)
    io.alu_out.mem.op               := RegEnable(Mux(valid, mem_op, MemOpType.None), MemOpType.None, update_en)
    io.alu_out.mem.vaddr            := RegEnable(Mux(valid, mem_addr_calc, 0.U), 0.U, update_en)
    io.alu_out.mem.paddr            := RegEnable(Mux(valid, mem_addr_calc, 0.U), 0.U, update_en)
    io.alu_out.mem.size             := RegEnable(mem_size, 0.U, update_en)
    io.alu_out.mem.signed           := RegEnable(mem_signed, false.B, update_en)
    io.alu_out.mem.mask             := RegEnable(mem_mask, 0.U, update_en)
    io.alu_out.mem.wdata            := RegEnable(mem_wdata_aligned, 0.U, update_en)
    io.alu_out.mem.atomic           := RegEnable(AtomicOpType.None, AtomicOpType.None, update_en)
    io.alu_out.mem.aq               := RegEnable(false.B, false.B, update_en)
    io.alu_out.mem.rl               := RegEnable(false.B, false.B, update_en)
    io.alu_out.mem.attrs.cacheable  := RegEnable(Mux(valid, mem_read || mem_write, false.B), false.B, update_en)
    io.alu_out.mem.attrs.device     := RegEnable(false.B, false.B, update_en)
    io.alu_out.mem.attrs.bufferable := RegEnable(true.B, true.B, update_en)
    io.alu_out.mem.attrs.allocate   := RegEnable(Mux(valid, mem_read || mem_write, false.B), false.B, update_en)
    io.alu_out.mem.attrs.translate  := RegEnable(Mux(valid, mem_read || mem_write, false.B), false.B, update_en)
    io.alu_out.mem.attrs.executable := RegEnable(false.B, false.B, update_en)

    io.br_info.pc        := Mux(branch_valid, io.pc_in, 0.U)
    io.br_info.valid     := branch_valid
    io.br_info.is_branch := branch_is_br
    io.br_info.taken     := branch_taken
    io.br_info.target    := branch_target
    io.br_info.redirect  := branch_valid && (branch_taken && (branch_target =/= io.next_pc_in))

    io.trap_info_out := RegEnable(trap_info, 0.U.asTypeOf(io.trap_info_out), update_en)
    io.pc_out        := RegEnable(io.pc_in, 0.U, update_en)
}
