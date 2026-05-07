package config

import org.scalatest.funsuite.AnyFunSuite
import soc.config.{Config, InterruptControllerKind, SoCFeatures, SoCProfiles}

class ConfigSpec extends AnyFunSuite {
    test("default MMIO region order matches IonSoC slave connection order") {
        assert(Config.MMIORegions.map(_.name) == Seq("debug", "rom", "sram", "uart", "clint", "plic"))
    }

    test("reserved device regions include optional MMIO ranges") {
        assert(Config.deviceRegions.map(_.name) == Seq("debug", "rom", "uart", "clint", "plic"))
    }

    test("parameterized MMIO regions follow device feature switches") {
        assert(Config.mmioRegionsFor(SoCFeatures(uart = false, clint = true)).map(_.name) == Seq("debug", "rom", "sram", "clint", "plic"))
        assert(Config.mmioRegionsFor(SoCFeatures(uart = true, clint = false)).map(_.name) == Seq("debug", "rom", "sram", "uart", "plic"))
        assert(Config.mmioRegionsFor(SoCFeatures(uart = false, clint = false)).map(_.name) == Seq("debug", "rom", "sram", "plic"))
        assert(
            Config.mmioRegionsFor(SoCFeatures(uart = false, clint = false, interruptController = InterruptControllerKind.None)).map(_.name) ==
                Seq("debug", "rom", "sram")
        )
    }

    test("named SoC profiles describe MCU and OS-capable configurations") {
        assert(Config.mmioRegionsFor(SoCProfiles.MinimalMCU).map(_.name) == Seq("debug", "rom", "sram", "uart", "clint"))
        assert(Config.mmioRegionsFor(SoCProfiles.LinuxCapablePLIC).map(_.name).contains("plic"))
        assert(!Config.mmioRegionsFor(SoCProfiles.ModernAIA).map(_.name).contains("plic"))
        assert(SoCProfiles.ModernAIA.mmu)
    }
}
