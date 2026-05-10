package core

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite
import soc.core.pipeline.InstrFetch

class InstrFetchSpec extends AnyFunSuite with ChiselSim {
    private def init(dut: InstrFetch): Unit = {
        dut.io.pc.poke("h80000000".U)
        dut.io.instr_in.poke("h0000000000000013".U)
        dut.io.pred_taken_in.poke(false.B)
        dut.io.pred_target_in.poke(0.U)
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
            dut.io.instr_in.poke("h0000000000850001".U) // high c.addi x1,1; low c.nop
            dut.clock.step()
            dut.io.valid.expect(true.B)
            dut.io.instr_len.expect(2.U)
            dut.io.instr_out.expect("h00000013".U)

            dut.io.pc.poke("h80000002".U)
            dut.io.instr_in.poke("h0000000000850001".U)
            dut.clock.step()
            dut.io.valid.expect(true.B)
            dut.io.instr_len.expect(2.U)
            dut.io.instr_out.expect("h00108093".U)
        }
    }

    test("InstrFetch leaves normal 32-bit instructions unchanged") {
        simulate(new InstrFetch(64, useCompressed = true)) { dut =>
            init(dut)

            dut.io.instr_in.poke("h0000000000500113".U)
            dut.clock.step()
            dut.io.valid.expect(true.B)
            dut.io.instr_len.expect(0.U)
            dut.io.instr_out.expect("h00500113".U)
        }
    }

    test("InstrFetch assembles a 32-bit instruction starting at a high halfword") {
        simulate(new InstrFetch(64, useCompressed = true)) { dut =>
            init(dut)

            dut.io.pc.poke("h80000002".U)
            dut.io.instr_in.poke("h0000005001130001".U)
            dut.clock.step()
            dut.io.valid.expect(true.B)
            dut.io.instr_len.expect(0.U)
            dut.io.instr_out.expect("h00500113".U)
        }
    }

    test("InstrFetch cache path assembles a 32-bit instruction crossing a 64-bit beat") {
        simulate(new InstrFetch(64, useCache = true, useCompressed = true)) { dut =>
            init(dut)

            dut.io.pc.poke("h1000000e".U)
            dut.io.cache.req.ready.poke(true.B)

            dut.io.cache.req.valid.expect(true.B)
            dut.io.cache.req.bits.addr.expect("h1000000e".U)

            dut.clock.step()
            dut.io.cache.resp.valid.poke(true.B)
            dut.io.cache.resp.bits.rdata.poke("ha2af000000000000".U)
            dut.clock.step()

            dut.io.cache.resp.valid.poke(false.B)
            dut.io.cache.req.valid.expect(true.B)
            dut.io.cache.req.bits.addr.expect("h10000010".U)

            dut.clock.step()
            dut.io.cache.resp.valid.poke(true.B)
            dut.io.cache.resp.bits.rdata.poke("h0000000000000062".U)
            dut.clock.step()

            dut.io.cache.resp.valid.poke(false.B)
            dut.io.valid.expect(true.B)
            dut.io.instr_len.expect(0.U)
            dut.io.instr_out.expect("h0062a2af".U)
        }
    }

    test("InstrFetch cache path reports current compressed length for PC stepping") {
        simulate(new InstrFetch(64, useCache = true, useCompressed = true)) { dut =>
            init(dut)
            dut.io.cache.req.ready.poke(true.B)

            dut.io.pc.poke("h4000f1d4".U)
            dut.clock.step()
            dut.io.cache.resp.bits.rdata.poke("h0000100ffe62c3e3".U) // previous blt; fence.i
            dut.io.cache.resp.bits.err.poke(false.B)
            dut.io.cache.resp.valid.poke(true.B)
            dut.clock.step()
            dut.io.valid.expect(true.B)
            dut.io.instr_out.expect("h0000100f".U)
            dut.io.instr_len.expect(0.U)
            dut.io.pc_step_len.expect(0.U)

            dut.io.cache.resp.valid.poke(false.B)
            dut.clock.step()
            dut.io.pc.poke("h4000f1d8".U)
            dut.clock.step()
            dut.io.cache.resp.bits.rdata.poke("h0000000000008082".U) // c.jr ra; c.unimp; c.unimp
            dut.io.cache.resp.valid.poke(true.B)
            dut.clock.step()
            dut.io.valid.expect(true.B)
            dut.io.instr_out.expect("h00008067".U) // c.jr ra expands to jalr x0, 0(ra)
            dut.io.instr_len.expect(2.U)
            dut.io.pc_step_len.expect(2.U)
        }
    }

    test("InstrFetch cache path reuses buffered 64-bit beat for sequential PC") {
        simulate(new InstrFetch(64, useCache = true, useCompressed = true)) { dut =>
            init(dut)
            dut.io.cache.req.ready.poke(true.B)

            dut.io.pc.poke("h1000".U)
            dut.io.cache.req.valid.expect(true.B)
            dut.io.cache.req.bits.addr.expect("h1000".U)

            dut.clock.step()
            dut.io.cache.resp.bits.rdata.poke("h0071019300500113".U) // addi x2,0,5; addi x3,x2,7
            dut.io.cache.resp.valid.poke(true.B)
            dut.clock.step()
            dut.io.valid.expect(true.B)
            dut.io.instr_out.expect("h00500113".U)
            dut.io.pc_step_len.expect(0.U)

            dut.io.cache.resp.valid.poke(false.B)
            dut.io.pc.poke("h1004".U)
            dut.clock.step()
            dut.io.valid.expect(true.B)
            dut.io.instr_out.expect("h00710193".U)
            dut.io.cache.req.valid.expect(true.B)
            dut.io.cache.req.bits.addr.expect("h1008".U)
            dut.io.fetch_stall.expect(false.B)
        }
    }

    test("InstrFetch cache path skips next-beat prefetch on taken prediction") {
        simulate(new InstrFetch(64, useCache = true, useCompressed = true)) { dut =>
            init(dut)
            dut.io.cache.req.ready.poke(true.B)

            dut.io.pc.poke("h1000".U)
            dut.clock.step()
            dut.io.cache.resp.bits.rdata.poke("h0071019300500113".U) // addi x2,0,5; addi x3,x2,7
            dut.io.cache.resp.valid.poke(true.B)
            dut.clock.step()

            dut.io.cache.resp.valid.poke(false.B)
            dut.io.pc.poke("h1004".U)
            dut.io.pred_taken_in.poke(true.B)
            dut.io.pred_target_in.poke("h2000".U)
            dut.clock.step()
            dut.io.valid.expect(true.B)
            dut.io.instr_out.expect("h00710193".U)
            dut.io.cache.req.valid.expect(false.B)
            dut.io.fetch_stall.expect(false.B)
        }
    }

    test("InstrFetch expands common compressed memory and register forms") {
        simulate(new InstrFetch(64, useCompressed = true)) { dut =>
            init(dut)

            dut.io.instr_in.poke("h0000000000004104".U) // c.lw x9, 0(x10)
            dut.clock.step()
            dut.io.instr_len.expect(2.U)
            dut.io.instr_out.expect("h00052483".U)

            dut.io.instr_in.poke("h000000000000c104".U) // c.sw x9, 0(x10)
            dut.clock.step()
            dut.io.instr_len.expect(2.U)
            dut.io.instr_out.expect("h00952023".U)

            dut.io.instr_in.poke("h000000000000908a".U) // c.add x1, x2
            dut.clock.step()
            dut.io.instr_len.expect(2.U)
            dut.io.instr_out.expect("h002080b3".U)
        }
    }

    test("InstrFetch expands RV64C stack and word arithmetic forms") {
        simulate(new InstrFetch(64, useCompressed = true)) { dut =>
            init(dut)

            dut.io.instr_in.poke("h00000000000064c2".U) // c.ldsp x9, 16(sp)
            dut.clock.step()
            dut.io.instr_len.expect(2.U)
            dut.io.instr_out.expect("h01013483".U)

            dut.io.instr_in.poke("h000000000000ec26".U) // c.sdsp x9, 24(sp)
            dut.clock.step()
            dut.io.instr_len.expect(2.U)
            dut.io.instr_out.expect("h00913c23".U)

            dut.io.instr_in.poke("h0000000000009ca9".U) // c.addw x9, x10
            dut.clock.step()
            dut.io.instr_len.expect(2.U)
            dut.io.instr_out.expect("h00a484bb".U)

            dut.io.instr_in.poke("h0000000000009c89".U) // c.subw x9, x10
            dut.clock.step()
            dut.io.instr_len.expect(2.U)
            dut.io.instr_out.expect("h40a484bb".U)
        }
    }

    test("InstrFetch turns illegal compressed encodings into illegal 32-bit instructions") {
        simulate(new InstrFetch(64, useCompressed = true)) { dut =>
            init(dut)

            dut.io.instr_in.poke("h0000000000000000".U) // reserved compressed encoding
            dut.clock.step()
            dut.io.instr_len.expect(2.U)
            dut.io.instr_out.expect("h00000000".U)
        }
    }
}
