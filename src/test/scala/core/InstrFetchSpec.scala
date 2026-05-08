package core

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite
import soc.core.pipeline.InstrFetch

class InstrFetchSpec extends AnyFunSuite with ChiselSim {
    private def init(dut: InstrFetch): Unit = {
        dut.io.pc.poke("h80000000".U)
        dut.io.instr_in.poke("h00000013".U)
        dut.io.pred_taken_in.poke(false.B)
        dut.io.redirect.poke(false.B)
        dut.io.trap_valid.poke(false.B)
        dut.io.stall.poke(false.B)
        dut.io.cache.req.ready.poke(false.B)
        dut.io.cache.resp.valid.poke(false.B)
        dut.io.cache.resp.bits.rdata.poke(0.U)
        dut.io.cache.resp.bits.err.poke(false.B)
    }

    test("InstrFetch expands compressed low and high halfwords") {
        simulate(new InstrFetch(64, useCompressed = true)) { dut =>
            init(dut)

            dut.io.pc.poke("h80000000".U)
            dut.io.instr_in.poke("h00850001".U) // high c.addi x1,1; low c.nop
            dut.clock.step()
            dut.io.valid.expect(true.B)
            dut.io.instr_len.expect(2.U)
            dut.io.instr_out.expect("h00000013".U)

            dut.io.pc.poke("h80000002".U)
            dut.io.instr_in.poke("h00850001".U)
            dut.clock.step()
            dut.io.valid.expect(true.B)
            dut.io.instr_len.expect(2.U)
            dut.io.instr_out.expect("h00108093".U)
        }
    }

    test("InstrFetch leaves normal 32-bit instructions unchanged") {
        simulate(new InstrFetch(64, useCompressed = true)) { dut =>
            init(dut)

            dut.io.instr_in.poke("h00500113".U)
            dut.clock.step()
            dut.io.valid.expect(true.B)
            dut.io.instr_len.expect(0.U)
            dut.io.instr_out.expect("h00500113".U)
        }
    }

    test("InstrFetch expands common compressed memory and register forms") {
        simulate(new InstrFetch(64, useCompressed = true)) { dut =>
            init(dut)

            dut.io.instr_in.poke("h00004104".U) // c.lw x9, 0(x10)
            dut.clock.step()
            dut.io.instr_len.expect(2.U)
            dut.io.instr_out.expect("h00052483".U)

            dut.io.instr_in.poke("h0000c104".U) // c.sw x9, 0(x10)
            dut.clock.step()
            dut.io.instr_len.expect(2.U)
            dut.io.instr_out.expect("h00952023".U)

            dut.io.instr_in.poke("h0000908a".U) // c.add x1, x2
            dut.clock.step()
            dut.io.instr_len.expect(2.U)
            dut.io.instr_out.expect("h002080b3".U)
        }
    }

    test("InstrFetch expands RV64C stack and word arithmetic forms") {
        simulate(new InstrFetch(64, useCompressed = true)) { dut =>
            init(dut)

            dut.io.instr_in.poke("h000064c2".U) // c.ldsp x9, 16(sp)
            dut.clock.step()
            dut.io.instr_len.expect(2.U)
            dut.io.instr_out.expect("h01013483".U)

            dut.io.instr_in.poke("h0000ec26".U) // c.sdsp x9, 24(sp)
            dut.clock.step()
            dut.io.instr_len.expect(2.U)
            dut.io.instr_out.expect("h00913c23".U)

            dut.io.instr_in.poke("h00009ca9".U) // c.addw x9, x10
            dut.clock.step()
            dut.io.instr_len.expect(2.U)
            dut.io.instr_out.expect("h00a484bb".U)

            dut.io.instr_in.poke("h00009c89".U) // c.subw x9, x10
            dut.clock.step()
            dut.io.instr_len.expect(2.U)
            dut.io.instr_out.expect("h40a484bb".U)
        }
    }

    test("InstrFetch turns illegal compressed encodings into illegal 32-bit instructions") {
        simulate(new InstrFetch(64, useCompressed = true)) { dut =>
            init(dut)

            dut.io.instr_in.poke("h00000000".U) // reserved compressed encoding
            dut.clock.step()
            dut.io.instr_len.expect(2.U)
            dut.io.instr_out.expect("h00000000".U)
        }
    }
}
