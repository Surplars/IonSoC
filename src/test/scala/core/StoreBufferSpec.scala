package core

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite
import soc.core.pipeline.StoreBuffer

class StoreBufferSpec extends AnyFunSuite with ChiselSim {
    test("StoreBuffer forwards the newest matching store for duplicate addresses") {
        simulate(new StoreBuffer(entries = 4, AW = 64, DW = 64)) { dut =>
            dut.io.enq_valid.poke(false.B)
            dut.io.enq_pc.poke(0.U)
            dut.io.enq_vaddr.poke(0.U)
            dut.io.enq_addr.poke(0.U)
            dut.io.enq_data.poke(0.U)
            dut.io.enq_mask.poke(0.U)
            dut.io.enq_size.poke(3.U)
            dut.io.search_addr.poke(0.U)
            dut.io.search_mask.poke(0.U)
            dut.io.deq_ready.poke(false.B)

            dut.io.enq_addr.poke("h1000".U)
            dut.io.enq_mask.poke("hff".U)

            dut.io.enq_data.poke("h1111111111111111".U)
            dut.io.enq_valid.poke(true.B)
            dut.io.enq_ready.expect(true.B)
            dut.clock.step()

            dut.io.enq_data.poke("h2222222222222222".U)
            dut.io.enq_ready.expect(true.B)
            dut.clock.step()
            dut.io.enq_valid.poke(false.B)

            dut.io.search_addr.poke("h1000".U)
            dut.io.search_mask.poke("hff".U)
            dut.io.search_hit.expect(true.B)
            dut.io.search_data.expect(BigInt("2222222222222222", 16))
        }
    }
}
