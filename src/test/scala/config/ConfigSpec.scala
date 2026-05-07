package config

import org.scalatest.funsuite.AnyFunSuite
import soc.config.{Config, SoCFeatures}

class ConfigSpec extends AnyFunSuite {
    test("default MMIO region order matches IonSoC slave connection order") {
        assert(Config.MMIORegions.map(_.name) == Seq("debug", "rom", "sram", "uart", "clint"))
    }

    test("reserved device regions include optional MMIO ranges") {
        assert(Config.deviceRegions.map(_.name) == Seq("debug", "rom", "uart", "clint"))
    }

    test("parameterized MMIO regions follow device feature switches") {
        assert(Config.mmioRegionsFor(SoCFeatures(uart = false, clint = true)).map(_.name) == Seq("debug", "rom", "sram", "clint"))
        assert(Config.mmioRegionsFor(SoCFeatures(uart = true, clint = false)).map(_.name) == Seq("debug", "rom", "sram", "uart"))
        assert(Config.mmioRegionsFor(SoCFeatures(uart = false, clint = false)).map(_.name) == Seq("debug", "rom", "sram"))
    }
}
