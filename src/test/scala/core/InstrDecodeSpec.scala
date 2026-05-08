package core

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite
import soc.core.pipeline._
import soc.isa.{Extension, MCause, PrivilegeLevel}

class InstrDecodeSpec extends AnyFunSuite with ChiselSim {
    private def init(dut: InstrDecode): Unit = {
        dut.io.valid_in.poke(true.B)
        dut.io.trap_valid.poke(false.B)
        dut.io.pc_in.poke("h80000000".U)
        dut.io.instr_in.poke("h00000013".U)
        dut.io.priv.poke(PrivilegeLevel.Machine)
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

    test("InstrDecode reports ECALL cause for the current privilege mode") {
        simulate(new InstrDecode(64)) { dut =>
            init(dut)
            dut.io.instr_in.poke("h00000073".U) // ecall

            dut.io.priv.poke(PrivilegeLevel.Machine)
            dut.clock.step()
            dut.io.trap_info.valid.expect(true.B)
            dut.io.trap_info.cause.expect(MCause.EcallFromMMode)

            dut.io.priv.poke(PrivilegeLevel.Supervisor)
            dut.clock.step()
            dut.io.trap_info.valid.expect(true.B)
            dut.io.trap_info.cause.expect(MCause.EcallFromSMode)

            dut.io.priv.poke(PrivilegeLevel.User)
            dut.clock.step()
            dut.io.trap_info.valid.expect(true.B)
            dut.io.trap_info.cause.expect(MCause.EcallFromUMode)
        }
    }

    test("InstrDecode gates M and Zifencei instructions by enabled extensions") {
        simulate(new InstrDecode(64, Set(Extension.RV64I))) { dut =>
            init(dut)
            dut.io.instr_in.poke("h022081b3".U) // mul x3, x1, x2
            dut.clock.step()
            dut.io.trap_info.valid.expect(true.B)

            init(dut)
            dut.io.instr_in.poke("h0000100f".U) // fence.i
            dut.clock.step()
            dut.io.trap_info.valid.expect(true.B)
        }

        simulate(new InstrDecode(64, Set(Extension.RV64I, Extension.RV64M, Extension.Zifencei))) { dut =>
            init(dut)
            dut.io.instr_in.poke("h022081b3".U) // mul x3, x1, x2
            dut.clock.step()
            dut.io.trap_info.valid.expect(false.B)
            dut.io.decoded_out.ctrl.alu_op.expect(ALUOps.MUL)

            init(dut)
            dut.io.instr_in.poke("h0000100f".U) // fence.i
            dut.clock.step()
            dut.io.trap_info.valid.expect(false.B)
            dut.io.decoded_out.ctrl.mem_fence_i.expect(true.B)
        }
    }

    test("InstrDecode gates RV64A atomic instructions by enabled extensions") {
        simulate(new InstrDecode(64, Set(Extension.RV64I))) { dut =>
            init(dut)
            dut.io.instr_in.poke("h1400b1af".U) // lr.d.aq x3, (x1)
            dut.clock.step()
            dut.io.trap_info.valid.expect(true.B)
        }

        simulate(new InstrDecode(64, Set(Extension.RV64I, Extension.RV64A))) { dut =>
            init(dut)
            dut.io.instr_in.poke("h1400b1af".U) // lr.d.aq x3, (x1)
            dut.clock.step()
            dut.io.trap_info.valid.expect(false.B)
            dut.io.decoded_out.ctrl.mem_atomic.expect(true.B)
            dut.io.decoded_out.atomic.expect(AtomicOpType.LR)
            dut.io.decoded_out.aq.expect(true.B)
            dut.io.decoded_out.rl.expect(false.B)

            init(dut)
            dut.io.instr_in.poke("h1a20b1af".U) // sc.d.rl x3, x2, (x1)
            dut.clock.step()
            dut.io.trap_info.valid.expect(false.B)
            dut.io.decoded_out.ctrl.mem_atomic.expect(true.B)
            dut.io.decoded_out.atomic.expect(AtomicOpType.SC)
            dut.io.decoded_out.aq.expect(false.B)
            dut.io.decoded_out.rl.expect(true.B)

            init(dut)
            dut.io.instr_in.poke("h0620b1af".U) // amoadd.d.aqrl x3, x2, (x1)
            dut.clock.step()
            dut.io.trap_info.valid.expect(false.B)
            dut.io.decoded_out.ctrl.mem_atomic.expect(true.B)
            dut.io.decoded_out.atomic.expect(AtomicOpType.Add)
            dut.io.decoded_out.aq.expect(true.B)
            dut.io.decoded_out.rl.expect(true.B)

            init(dut)
            dut.io.instr_in.poke("h1420b1af".U) // reserved: lr.d with rs2 != x0
            dut.clock.step()
            dut.io.trap_info.valid.expect(true.B)
        }
    }
}
