package soc.isa

import chisel3._
import chisel3.util._

import soc.config.Config

case class InstrTableEntry(
    name: String,
    opcode: UInt,
    funct3: Option[UInt] = None,
    funct7: Option[UInt] = None,
    requiredExt: Set[Extension.Value],
    op1_sel: Option[OpSel.Type],
    op2_sel: Option[OpSel.Type],
    ctrlGen: () => CtrlSignals
)

object InstrTable {
    val table: Seq[InstrTableEntry] = Seq(
        InstrTableEntry(
            name = "ADDI",
            opcode = Opcode.op_imm,
            funct3 = Some(Funct3.I.addSub),
            funct7 = None,
            requiredExt = Set(Extension.RV64I),
            op1_sel = Some(OpSel.RS1),
            op2_sel = Some(OpSel.IMM),
            ctrlGen = () => {
                val ctrl = WireInit(0.U.asTypeOf(new CtrlSignals))
                ctrl.alu_op    := ALUOps.add.asUInt
                ctrl.reg_write := true.B
                ctrl.mem_read  := false.B
                ctrl.mem_write := false.B
                ctrl.branch    := false.B
                ctrl.jump_en   := false.B
                ctrl
            }
        ),
        InstrTableEntry(
            name = "ADD",
            opcode = Opcode.op,
            funct3 = Some(Funct3.I.addSub),
            funct7 = Some(Funct7.zero),
            requiredExt = Set(Extension.RV64I),
            op1_sel = Some(OpSel.RS1),
            op2_sel = Some(OpSel.RS2),
            ctrlGen = () => {
                val ctrl = WireInit(0.U.asTypeOf(new CtrlSignals))
                ctrl.alu_op    := ALUOps.add.asUInt
                ctrl.reg_write := true.B
                ctrl.mem_read  := false.B
                ctrl.mem_write := false.B
                ctrl.branch    := false.B
                ctrl.jump_en   := false.B
                ctrl
            }
        ),
        InstrTableEntry(
            name = "SUB",
            opcode = Opcode.op,
            funct3 = Some(Funct3.I.addSub),
            funct7 = Some(Funct7.nzero),
            requiredExt = Set(Extension.RV64I),
            op1_sel = Some(OpSel.RS1),
            op2_sel = Some(OpSel.RS2),
            ctrlGen = () => {
                val ctrl = WireInit(0.U.asTypeOf(new CtrlSignals))
                ctrl.alu_op    := ALUOps.sub.asUInt
                ctrl.reg_write := true.B
                ctrl.mem_read  := false.B
                ctrl.mem_write := false.B
                ctrl.branch    := false.B
                ctrl.jump_en   := false.B
                ctrl
            }
        ),
    )
}
