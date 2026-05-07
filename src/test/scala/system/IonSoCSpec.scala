package system

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite
import soc.IonSoC
import soc.config.SoCFeatures
import soc.isa.Extension

class IonSoCSpec extends AnyFunSuite with ChiselSim {
    test("IonSoC elaborates with instruction cache enabled") {
        simulate(new IonSoC(SoCFeatures(iCache = true))) { _ => }
    }

    test("IonSoC elaborates with optional MMIO devices disabled") {
        simulate(new IonSoC(SoCFeatures(uart = false, clint = false))) { _ => }
    }

    test("IonSoC elaborates with feature and ISA extension overrides") {
        simulate(new IonSoC(SoCFeatures(mmu = true, iCache = true, dCache = false), Set(Extension.RV64I, Extension.Zicsr))) { _ => }
    }
}
