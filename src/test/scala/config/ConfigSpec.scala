package config

import org.scalatest.funsuite.AnyFunSuite
import soc.config.Config

class ConfigSpec extends AnyFunSuite {
    test("default MMIO region order matches IonSoC slave connection order") {
        assert(Config.MMIORegions.map(_.name) == Seq("debug", "rom", "sram", "uart", "clint"))
    }

    test("reserved device regions include optional MMIO ranges") {
        assert(Config.deviceRegions.map(_.name) == Seq("debug", "rom", "uart", "clint"))
    }
}
