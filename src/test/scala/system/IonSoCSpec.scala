package system

import chisel3.simulator.scalatest.ChiselSim
import chisel3._
import org.scalatest.funsuite.AnyFunSuite
import soc.IonSoC
import soc.config.{InterruptControllerKind, SoCFeatures}
import soc.debug.DebugModuleMap
import soc.isa.Extension

class IonSoCSpec extends AnyFunSuite with ChiselSim {
    private def tieOffExternalInterrupts(dut: IonSoC): Unit = {
        for (i <- 0 until dut.io.ext_irq_sources.length) {
            dut.io.ext_irq_sources(i).poke(false)
        }
        dut.io.uart_rx_valid.poke(false)
        dut.io.uart_rx_byte.poke(0)
        dut.io.jtag_tms.poke(true)
        dut.io.jtag_tck.poke(false)
        dut.io.jtag_tdi.poke(false)
    }

    test("IonSoC elaborates with instruction cache enabled") {
        simulate(new IonSoC(SoCFeatures(iCache = true))) { dut => tieOffExternalInterrupts(dut) }
    }

    test("IonSoC elaborates with optional MMIO devices disabled") {
        simulate(new IonSoC(SoCFeatures(uart = false, clint = false, interruptController = InterruptControllerKind.None))) { dut =>
            tieOffExternalInterrupts(dut)
        }
    }

    test("IonSoC elaborates with feature and ISA extension overrides") {
        simulate(new IonSoC(SoCFeatures(mmu = true, iCache = true, dCache = false), Set(Extension.RV64I, Extension.Zicsr))) { dut =>
            tieOffExternalInterrupts(dut)
        }
    }

    test("IonSoC elaborates with AIA placeholder configuration") {
        simulate(new IonSoC(SoCFeatures(interruptController = InterruptControllerKind.AIA))) { dut => tieOffExternalInterrupts(dut) }
    }

    test("Debug halt request freezes and resume releases the core PC") {
        simulate(new IonSoC()) { dut =>
            tieOffExternalInterrupts(dut)
            resetTap(dut)
            shiftIR(dut, value = 2, irLen = 5)

            dut.clock.step(4)
            val runningPc = dut.io.debug.pc.peek().litValue

            dmiWrite(dut, DebugModuleMap.DMControl, BigInt("80000001", 16))
            dut.clock.step(4)
            val haltedPc = dut.io.debug.pc.peek().litValue
            assert(haltedPc >= runningPc)
            dut.clock.step(8)
            dut.io.debug.pc.expect(haltedPc.U)

            dmiWrite(dut, DebugModuleMap.DMControl, BigInt("40000001", 16))
            dut.clock.step(6)
            assert(dut.io.debug.pc.peek().litValue != haltedPc)
        }
    }

    private def pulseTck(dut: IonSoC, tms: Boolean, tdi: Boolean = false): Boolean = {
        dut.io.jtag_tms.poke(tms.B)
        dut.io.jtag_tdi.poke(tdi.B)
        dut.io.jtag_tck.poke(true.B)
        dut.clock.step()
        val tdo = dut.io.jtag_tdo.peek().litToBoolean
        dut.io.jtag_tck.poke(false.B)
        dut.clock.step()
        tdo
    }

    private def resetTap(dut: IonSoC): Unit = {
        for (_ <- 0 until 5) pulseTck(dut, tms = true)
        pulseTck(dut, tms = false)
    }

    private def shiftIR(dut: IonSoC, value: Int, irLen: Int): Unit = {
        pulseTck(dut, tms = true)
        pulseTck(dut, tms = true)
        pulseTck(dut, tms = false)
        pulseTck(dut, tms = false)
        for (i <- 0 until irLen) {
            pulseTck(dut, tms = i == irLen - 1, tdi = ((value >> i) & 1) != 0)
        }
        pulseTck(dut, tms = true)
        pulseTck(dut, tms = false)
    }

    private def dmiWrite(dut: IonSoC, addr: Int, data: BigInt): Unit = {
        val packet = (data << 9) | (BigInt(addr) << 2) | 2
        pulseTck(dut, tms = true)
        pulseTck(dut, tms = false)
        pulseTck(dut, tms = false)
        for (i <- 0 until 64) {
            pulseTck(dut, tms = i == 63, tdi = ((packet >> i) & 1) != 0)
        }
        pulseTck(dut, tms = true)
        pulseTck(dut, tms = false)
    }
}
