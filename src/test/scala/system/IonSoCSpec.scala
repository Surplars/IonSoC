package system

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite
import soc.IonSoC
import soc.config.{InterruptControllerKind, SoCFeatures}
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
}
