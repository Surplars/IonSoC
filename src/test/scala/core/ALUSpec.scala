package core

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite
import soc.core.pipeline._

class ALUSpec extends AnyFunSuite with ChiselSim {
    private def init(dut: ALU): Unit = {
        dut.io.pc_in.poke("h80000000".U)
        dut.io.next_pc_in.poke("h80000004".U)
        dut.io.valid_in.poke(true.B)
        dut.io.trap_valid.poke(false.B)
        dut.io.pred_taken_in.poke(false.B)
        dut.io.stall.poke(false.B)

        dut.io.trap_info_in.valid.poke(false.B)
        dut.io.trap_info_in.pc.poke(0.U)
        dut.io.trap_info_in.cause.poke(0.U)
        dut.io.trap_info_in.value.poke(0.U)
        dut.io.trap_info_in.is_ret.poke(false.B)
        dut.io.trap_info_in.ret_type.poke(TrapReturnType.None)

        dut.io.decoded_in.rs1.poke(0.U)
        dut.io.decoded_in.rs2.poke(0.U)
        dut.io.decoded_in.op1.poke(0.U)
        dut.io.decoded_in.op2.poke(0.U)
        dut.io.decoded_in.rd.poke(1.U)
        dut.io.decoded_in.funct3.poke(0.U)
        dut.io.decoded_in.op2_sel.poke(OpSel.IMM)
        dut.io.decoded_in.br_imm.poke(0.U)
        dut.io.decoded_in.mem_imm.poke(0.U)
        dut.io.decoded_in.ctrl.alu_op.poke(ALUOps.ADD)
        dut.io.decoded_in.ctrl.reg_write.poke(true.B)
        dut.io.decoded_in.ctrl.mem_read.poke(false.B)
        dut.io.decoded_in.ctrl.mem_write.poke(false.B)
        dut.io.decoded_in.ctrl.mem_fence.poke(false.B)
        dut.io.decoded_in.ctrl.mem_fence_i.poke(false.B)
        dut.io.decoded_in.ctrl.mem_atomic.poke(false.B)
        dut.io.decoded_in.ctrl.csr_op.poke(CSROps.None)
        dut.io.decoded_in.ctrl.branch_type.poke(BranchType.None)

        dut.io.fwd.load_valid.poke(false.B)
        dut.io.fwd.load_data.poke(0.U)
        dut.io.fwd.rd.poke(0.U)
        dut.io.fwd.alu_result.poke(0.U)
        dut.io.fwd.reg_write.poke(false.B)
        dut.io.csr_rdata.poke(0.U)
        dut.io.csr_illegal.poke(false.B)
    }

    private def stepAlu(dut: ALU, op: ALUOps.Type, lhs: BigInt, rhs: BigInt): Unit = {
        dut.io.decoded_in.ctrl.alu_op.poke(op)
        dut.io.decoded_in.op1.poke(lhs.U)
        dut.io.decoded_in.op2.poke(rhs.U)
        dut.clock.step()
    }

    test("ALU uses six-bit shift amounts for RV64 shifts") {
        simulate(new ALU(64)) { dut =>
            init(dut)

            stepAlu(dut, ALUOps.SLL, 1, 63)
            dut.io.alu_out.result.expect(BigInt("8000000000000000", 16))

            stepAlu(dut, ALUOps.SRL, BigInt("8000000000000000", 16), 63)
            dut.io.alu_out.result.expect(1.U)
        }
    }

    test("ALU word shifts use five-bit amounts and sign extend the 32-bit result") {
        simulate(new ALU(64)) { dut =>
            init(dut)

            stepAlu(dut, ALUOps.SLLW, 1, 31)
            dut.io.alu_out.result.expect(BigInt("ffffffff80000000", 16))

            stepAlu(dut, ALUOps.SRLW, BigInt("80000000", 16), 1)
            dut.io.alu_out.result.expect(BigInt("0000000040000000", 16))
        }
    }

    test("ALU suppresses CSR write side effects for CSR read forms") {
        simulate(new ALU(64)) { dut =>
            init(dut)

            dut.io.decoded_in.ctrl.csr_op.poke(CSROps.RS)
            dut.io.decoded_in.op1.poke("hf14".U) // mhartid
            dut.io.decoded_in.rs2.poke(0.U)
            dut.clock.step()
            dut.io.csr_valid.expect(true.B)
            dut.io.csr_write.expect(false.B)

            dut.io.decoded_in.ctrl.csr_op.poke(CSROps.RW)
            dut.io.decoded_in.rs2.poke(0.U)
            dut.clock.step()
            dut.io.csr_valid.expect(true.B)
            dut.io.csr_write.expect(true.B)

            dut.io.decoded_in.ctrl.csr_op.poke(CSROps.RSI)
            dut.io.decoded_in.rs2.poke(0.U)
            dut.clock.step()
            dut.io.csr_valid.expect(true.B)
            dut.io.csr_write.expect(false.B)
        }
    }

    test("ALU executes RV64M multiply variants") {
        simulate(new ALU(64)) { dut =>
            init(dut)

            stepAlu(dut, ALUOps.MUL, BigInt("ffffffffffffffff", 16), 3)
            dut.io.alu_out.result.expect(BigInt("fffffffffffffffd", 16))

            stepAlu(dut, ALUOps.MULH, BigInt("ffffffffffffffff", 16), 3)
            dut.io.alu_out.result.expect(BigInt("ffffffffffffffff", 16))

            stepAlu(dut, ALUOps.MULHSU, BigInt("ffffffffffffffff", 16), 3)
            dut.io.alu_out.result.expect(BigInt("ffffffffffffffff", 16))

            stepAlu(dut, ALUOps.MULHU, BigInt("ffffffffffffffff", 16), 2)
            dut.io.alu_out.result.expect(1.U)

            stepAlu(dut, ALUOps.MULW, BigInt("ffffffffffffffff", 16), 2)
            dut.io.alu_out.result.expect(BigInt("fffffffffffffffe", 16))
        }
    }

    test("ALU executes RV64M divide and remainder edge cases") {
        simulate(new ALU(64)) { dut =>
            init(dut)

            stepAlu(dut, ALUOps.DIV, 10, 3)
            dut.io.alu_out.result.expect(3.U)

            stepAlu(dut, ALUOps.REM, 10, 3)
            dut.io.alu_out.result.expect(1.U)

            stepAlu(dut, ALUOps.DIVU, 10, 0)
            dut.io.alu_out.result.expect(BigInt("ffffffffffffffff", 16))

            stepAlu(dut, ALUOps.REMU, 10, 0)
            dut.io.alu_out.result.expect(10.U)

            stepAlu(dut, ALUOps.DIV, BigInt("8000000000000000", 16), BigInt("ffffffffffffffff", 16))
            dut.io.alu_out.result.expect(BigInt("8000000000000000", 16))

            stepAlu(dut, ALUOps.REM, BigInt("8000000000000000", 16), BigInt("ffffffffffffffff", 16))
            dut.io.alu_out.result.expect(0.U)

            stepAlu(dut, ALUOps.DIVW, BigInt("fffffffffffffffe", 16), 2)
            dut.io.alu_out.result.expect(BigInt("ffffffffffffffff", 16))

            stepAlu(dut, ALUOps.REMUW, BigInt("ffffffffffffffff", 16), 2)
            dut.io.alu_out.result.expect(1.U)
        }
    }
}
