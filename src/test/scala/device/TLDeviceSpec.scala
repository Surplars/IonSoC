package device

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite
import soc.bus.tilelink.TLOpcode
import soc.device.{TLError, TLROM}

class TLDeviceSpec extends AnyFunSuite with ChiselSim with TileLinkDeviceTestUtils {
    test("TLError returns denied responses for reads and writes") {
        simulate(new TLError(deviceParams)) { dut =>
            initTlSlave(dut.io.tl)
            dut.io.tl.d.ready.poke(true.B)

            readTl(dut.io.tl, dut.clock, address = 0x1000, source = 2, denied = true)

            writeTl(
                tl = dut.io.tl,
                clock = dut.clock,
                address = 0x1008,
                data = BigInt("0123456789abcdef", 16),
                mask = 0xff,
                source = 7,
                opcode = TLOpcode.PutFullData,
                denied = true
            )
        }
    }

    test("TLROM allows reads and denies writes") {
        simulate(new TLROM(deviceParams)) { dut =>
            initTlSlave(dut.io.tl)

            readTl(dut.io.tl, dut.clock, address = BigInt("80000000", 16), source = 1)

            writeTl(
                tl = dut.io.tl,
                clock = dut.clock,
                address = BigInt("80000008", 16),
                data = BigInt("0123456789abcdef", 16),
                mask = 0xff,
                source = 2,
                opcode = TLOpcode.PutFullData,
                denied = true
            )
        }
    }
}
