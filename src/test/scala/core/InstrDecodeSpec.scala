package core

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite
import soc.core.pipeline._

class InstrDecodeSpec extends AnyFunSuite with ChiselSim {
    private def init(dut: InstrDecode): Unit = {
        dut.io.valid_in.poke(true.B)
        dut.io.trap_valid.poke(false.B)
        dut.io.pc_in.poke("h80000000".U)
        dut.io.instr_in.poke("h00000013".U)
        dut.io.pred_taken_in.poke(false.B)
        dut.io.redirect.poke(false.B)
        dut.io.stall.poke(false.B)
        dut.io.reg_rs1_data.poke(1.U)
        dut.io.reg_rs2_data.poke(0.U)
    }

    test("InstrDecode accepts RV64 shift-immediate instructions with shamt bit 5 set") {
        simulate(new InstrDecode(64)) { dut =>
            init(dut)

            dut.io.instr_in.poke("h03f0d093".U) // srli x1, x1, 63
            dut.clock.step()
            dut.io.trap_info.valid.expect(false.B)
            dut.io.decoded_out.ctrl.alu_op.expect(ALUOps.SRL)
            dut.io.decoded_out.op2.expect(63.U)

            dut.io.instr_in.poke("h43f0d093".U) // srai x1, x1, 63
            dut.clock.step()
            dut.io.trap_info.valid.expect(false.B)
            dut.io.decoded_out.ctrl.alu_op.expect(ALUOps.SRA)
            dut.io.decoded_out.op2.expect(63.U)
        }
    }
}
