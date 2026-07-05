package core

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite
import soc.core.SatpWriteBarrier
import soc.isa.CSR

class SatpWriteBarrierSpec extends AnyFunSuite with ChiselSim {
    private def init(dut: SatpWriteBarrier): Unit = {
        dut.io.decodeValid.poke(false.B)
        dut.io.decodeCsrWrite.poke(false.B)
        dut.io.decodeCsrAddr.poke(0.U)
        dut.io.lsuMemoryIdle.poke(true.B)
        dut.io.commitValid.poke(false.B)
        dut.io.commitCsrWrite.poke(false.B)
        dut.io.commitCsrAddr.poke(0.U)
        dut.io.commitPc.poke(0.U)
        dut.io.commitInstrLen.poke(0.U)
    }

    test("holds a decoded satp write until LSU memory operations are idle") {
        simulate(new SatpWriteBarrier(64)) { dut =>
            init(dut)

            dut.io.decodeValid.poke(true.B)
            dut.io.decodeCsrWrite.poke(true.B)
            dut.io.decodeCsrAddr.poke(CSR.SATP)
            dut.io.lsuMemoryIdle.poke(false.B)
            dut.io.holdDecode.expect(true.B)

            dut.io.decodeCsrAddr.poke(CSR.SSTATUS)
            dut.io.holdDecode.expect(false.B)

            dut.io.decodeCsrAddr.poke(CSR.SATP)
            dut.io.lsuMemoryIdle.poke(true.B)
            dut.io.holdDecode.expect(false.B)
        }
    }

    test("does not redirect the frontend when satp commits") {
        simulate(new SatpWriteBarrier(64)) { dut =>
            init(dut)

            dut.io.commitValid.poke(true.B)
            dut.io.commitCsrWrite.poke(true.B)
            dut.io.commitCsrAddr.poke(CSR.SATP)
            dut.io.commitPc.poke("hffffffff80001000".U)
            dut.io.commitInstrLen.poke(0.U)
            dut.io.frontendFlush.expect(false.B)
            dut.io.redirect.valid.expect(false.B)
        }
    }
}
