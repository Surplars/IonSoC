package soc.core.pipeline

import chisel3._
import chisel3.util._

import soc.config.Config
import soc.isa.Opcode
import soc.isa.CtrlSignals
import soc.isa.Extension
import soc.isa.InstrTable
import soc.isa.InstrTableEntry
import soc.isa.DecodedInstr
import soc.isa.OpSel

class InstrDecode(XLEN: Int = 64) extends Module {
    val io = IO(new Bundle {
        val valid    = Input(Bool())
        val pc_in    = Input(UInt(XLEN.W))
        val instr_in = Input(UInt(32.W))

        // val reg_rs1 = Output(UInt(5.W))
        // val reg_rs2 = Output(UInt(5.W))

        val decoded_out = Output(new DecodedInstr)
    })

    val opcode = io.instr_in(6, 0)
    val funct3 = io.instr_in(14, 12)
    val funct7 = io.instr_in(31, 25)
    val imm    = WireInit(0.U(XLEN.W))
    val rs1    = io.instr_in(19, 15)
    val rs2    = io.instr_in(24, 20)
    val rd     = io.instr_in(11, 7)

    val ctrl           = Wire(new CtrlSignals)
    val decoded        = Wire(new DecodedInstr)
    val defaultDecoded = 0.U.asTypeOf(decoded)

    val op1_out = WireInit(0.U(5.W))
    val op2_out = WireInit(0.U(5.W))

    imm := MuxLookup(opcode, 0.U(XLEN.W))(
        Seq(
            Opcode.op_imm -> Cat(Fill(XLEN - 20, io.instr_in(31)), io.instr_in(31, 20)),
            Opcode.branch -> Cat(
                Fill(XLEN - 19, io.instr_in(31)),
                io.instr_in(31),
                io.instr_in(7),
                io.instr_in(30, 25),
                io.instr_in(11, 8),
                0.U(1.W)
            ),
            Opcode.load     -> Cat(Fill(XLEN - 20, io.instr_in(31)), io.instr_in(31, 20)),
            Opcode.store    -> Cat(Fill(XLEN - 20, io.instr_in(31)), io.instr_in(31), io.instr_in(30, 25), io.instr_in(11, 8)),
            Opcode.misc_mem -> Cat(Fill(XLEN - 27, 0.U), io.instr_in(19, 15)),
            Opcode.system   -> Cat(Fill(XLEN - 27, 0.U), io.instr_in(19, 15)),
            Opcode.op       -> Cat(Fill(XLEN - 27, 0.U), io.instr_in(19, 15))
        )
    )

    def isFeatureSupported(f: Extension.Value, enabled: Set[Extension.Value]): Boolean = f match {
        case Extension.RV32I => enabled.contains(Extension.RV64I) || enabled.contains(Extension.RV32I)
        case Extension.RV32M => enabled.contains(Extension.RV64M) || enabled.contains(Extension.RV32M)
        case Extension.RV32A => enabled.contains(Extension.RV64A) || enabled.contains(Extension.RV32A)
        case Extension.RV64I => enabled.contains(Extension.RV64I)
        case Extension.RV64M => enabled.contains(Extension.RV64M)
        case Extension.RV64A => enabled.contains(Extension.RV64A)
        case Extension.Zicsr => enabled.contains(Extension.Zicsr)
        case other           => enabled.contains(other)
    }

    def entryEnabled(required: Set[Extension.Value], enabled: Set[Extension.Value]): Boolean = {
        required.forall(f => isFeatureSupported(f, enabled))
    }

    // 过滤出需要的指令表项
    val filtered: Seq[InstrTableEntry] = InstrTable.table.filter(d => entryEnabled(d.requiredExt, Config.enabledExt))

    // 找到匹配的指令表项
    val matches: Seq[Bool] = filtered.map { d =>
        val opMatch = opcode === d.opcode
        val f3Match = d.funct3.map(f3 => funct3 === f3).getOrElse(true.B)
        val f7Match = d.funct7.map(f7 => funct7 === f7).getOrElse(true.B)
        opMatch && f3Match && f7Match
    }
    // 生成操作数选择信号
    val op1_lits = filtered.map { d =>
        d.op1_sel match {
            case Some(sel) => sel
            case None      => OpSel.ZERO
        }
    }
    val op2_lits = filtered.map { d =>
        d.op2_sel match {
            case Some(sel) => sel
            case None      => OpSel.ZERO
        }
    }
    // 生成对应指令的控制信号
    val ctrlBundles: Seq[CtrlSignals] = filtered.map { d =>
        d.ctrlGen()
    }

    val ctrlVecBundle = VecInit(ctrlBundles)
    ctrl := Mux1H(VecInit(matches), ctrlVecBundle)
    val op1SelPacked = Mux1H(VecInit(matches), VecInit(op1_lits))
    val op2SelPacked = Mux1H(VecInit(matches), VecInit(op2_lits))
    // val op1Sel = Mux(op1SelPacked =/= 0.U, op1SelPacked, OpSel.ZERO)
    // val op2Sel = Mux(op2SelPacked =/= 0.U, op2SelPacked, OpSel.ZERO)

    op1_out := MuxLookup(op1SelPacked, 0.U)(
        Seq(
            OpSel.RS1  -> rs1,
            OpSel.RS2  -> rs2,
            OpSel.IMM  -> imm,
            OpSel.PC   -> 0.U,
            OpSel.CSR  -> 0.U,
            OpSel.ZERO -> 0.U,
            OpSel.MEM  -> 0.U
        )
    )
    op2_out := MuxLookup(op2SelPacked, 0.U)(
        Seq(
            OpSel.RS1  -> rs1,
            OpSel.RS2  -> rs2,
            OpSel.IMM  -> imm,
            OpSel.PC   -> 0.U,
            OpSel.CSR  -> 0.U,
            OpSel.ZERO -> 0.U,
            OpSel.MEM  -> 0.U
        )
    )

    decoded.ctrl := ctrl
    decoded.op1  := op1_out
    decoded.op2  := op2_out
    decoded.rd   := rd

    io.decoded_out := Mux(io.valid, decoded, defaultDecoded)

    assert(PopCount(matches) <= 1.U, "Multiple instruction matches found in InstrDecode!")
}
