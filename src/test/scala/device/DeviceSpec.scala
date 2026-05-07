package device

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite
import soc.bus.tilelink._
import soc.device.{CLINT, TLError, TLROM}

class DeviceSpec extends AnyFunSuite with ChiselSim {
    private val params = TLParams(addrWidth = 32, dataWidth = 64, sourceBits = 4, sinkBits = 1, sizeBits = 3)

    private def driveDefaults(dut: CLINT): Unit = {
        dut.io.tl.a.valid.poke(false.B)
        dut.io.tl.a.bits.opcode.poke(0.U)
        dut.io.tl.a.bits.param.poke(0.U)
        dut.io.tl.a.bits.size.poke(3.U)
        dut.io.tl.a.bits.source.poke(0.U)
        dut.io.tl.a.bits.address.poke(0.U)
        dut.io.tl.a.bits.mask.poke(0.U)
        dut.io.tl.a.bits.data.poke(0.U)
        dut.io.tl.a.bits.corrupt.poke(false.B)
        dut.io.tl.d.ready.poke(false.B)
    }

    private def driveDefaults(dut: TLError): Unit = {
        dut.io.tl.a.valid.poke(false.B)
        dut.io.tl.a.bits.opcode.poke(0.U)
        dut.io.tl.a.bits.param.poke(0.U)
        dut.io.tl.a.bits.size.poke(3.U)
        dut.io.tl.a.bits.source.poke(0.U)
        dut.io.tl.a.bits.address.poke(0.U)
        dut.io.tl.a.bits.mask.poke(0.U)
        dut.io.tl.a.bits.data.poke(0.U)
        dut.io.tl.a.bits.corrupt.poke(false.B)
        dut.io.tl.d.ready.poke(false.B)
    }

    private def driveDefaults(dut: TLROM): Unit = {
        dut.io.tl.a.valid.poke(false.B)
        dut.io.tl.a.bits.opcode.poke(0.U)
        dut.io.tl.a.bits.param.poke(0.U)
        dut.io.tl.a.bits.size.poke(3.U)
        dut.io.tl.a.bits.source.poke(0.U)
        dut.io.tl.a.bits.address.poke(0.U)
        dut.io.tl.a.bits.mask.poke(0.U)
        dut.io.tl.a.bits.data.poke(0.U)
        dut.io.tl.a.bits.corrupt.poke(false.B)
        dut.io.tl.d.ready.poke(false.B)
    }

    private def clintWrite(dut: CLINT, address: BigInt, mask: BigInt, data: BigInt): Unit = {
        dut.io.tl.d.ready.poke(true.B)
        dut.io.tl.a.bits.opcode.poke(TLOpcode.PutPartialData)
        dut.io.tl.a.bits.size.poke(2.U)
        dut.io.tl.a.bits.source.poke(3.U)
        dut.io.tl.a.bits.address.poke(address.U)
        dut.io.tl.a.bits.mask.poke(mask.U)
        dut.io.tl.a.bits.data.poke(data.U)
        dut.io.tl.a.valid.poke(true.B)
        dut.io.tl.a.ready.expect(true.B)
        dut.clock.step()
        dut.io.tl.a.valid.poke(false.B)
        dut.io.tl.d.valid.expect(true.B)
        dut.io.tl.d.bits.opcode.expect(TLOpcode.AccessAck)
        dut.io.tl.d.bits.source.expect(3.U)
        dut.io.tl.d.bits.denied.expect(false.B)
        dut.clock.step()
    }

    private def clintRead(dut: CLINT, address: BigInt): BigInt = {
        dut.io.tl.d.ready.poke(true.B)
        dut.io.tl.a.bits.opcode.poke(TLOpcode.Get)
        dut.io.tl.a.bits.size.poke(3.U)
        dut.io.tl.a.bits.source.poke(5.U)
        dut.io.tl.a.bits.address.poke(address.U)
        dut.io.tl.a.bits.mask.poke("hff".U)
        dut.io.tl.a.bits.data.poke(0.U)
        dut.io.tl.a.valid.poke(true.B)
        dut.io.tl.a.ready.expect(true.B)
        dut.clock.step()
        dut.io.tl.a.valid.poke(false.B)
        dut.io.tl.d.valid.expect(true.B)
        dut.io.tl.d.bits.opcode.expect(TLOpcode.AccessAckData)
        dut.io.tl.d.bits.source.expect(5.U)
        dut.io.tl.d.bits.denied.expect(false.B)
        val data = dut.io.tl.d.bits.data.peek().litValue
        dut.clock.step()
        data
    }

    test("Device tilelink blocks behave as expected") {
        simulate(new CLINT(params)) { dut =>
            driveDefaults(dut)

            clintWrite(dut, address = 0x4004, mask = 0xf0, data = BigInt("1234567800000000", 16))
            clintWrite(dut, address = 0x4000, mask = 0x0f, data = BigInt("00000000deadbeef", 16))

            val fromLowAddress = clintRead(dut, 0x4000)
            assert(fromLowAddress == BigInt("12345678deadbeef", 16))

            val fromHighAddress = clintRead(dut, 0x4004)
            assert(fromHighAddress == BigInt("12345678deadbeef", 16))
        }

        simulate(new TLError(params)) { dut =>
            driveDefaults(dut)
            dut.io.tl.d.ready.poke(true.B)

            dut.io.tl.a.bits.opcode.poke(TLOpcode.Get)
            dut.io.tl.a.bits.size.poke(3.U)
            dut.io.tl.a.bits.source.poke(2.U)
            dut.io.tl.a.bits.address.poke("h1000".U)
            dut.io.tl.a.bits.mask.poke("hff".U)
            dut.io.tl.a.valid.poke(true.B)
            dut.io.tl.a.ready.expect(true.B)
            dut.clock.step()
            dut.io.tl.a.valid.poke(false.B)
            dut.io.tl.d.valid.expect(true.B)
            dut.io.tl.d.bits.opcode.expect(TLOpcode.AccessAckData)
            dut.io.tl.d.bits.source.expect(2.U)
            dut.io.tl.d.bits.denied.expect(true.B)
            dut.clock.step()

            dut.io.tl.a.bits.opcode.poke(TLOpcode.PutFullData)
            dut.io.tl.a.bits.size.poke(3.U)
            dut.io.tl.a.bits.source.poke(7.U)
            dut.io.tl.a.bits.address.poke("h1008".U)
            dut.io.tl.a.bits.mask.poke("hff".U)
            dut.io.tl.a.bits.data.poke("h0123456789abcdef".U)
            dut.io.tl.a.valid.poke(true.B)
            dut.io.tl.a.ready.expect(true.B)
            dut.clock.step()
            dut.io.tl.a.valid.poke(false.B)
            dut.io.tl.d.valid.expect(true.B)
            dut.io.tl.d.bits.opcode.expect(TLOpcode.AccessAck)
            dut.io.tl.d.bits.source.expect(7.U)
            dut.io.tl.d.bits.denied.expect(true.B)
            dut.clock.step()
        }

        simulate(new TLROM(params)) { dut =>
            driveDefaults(dut)
            dut.io.tl.d.ready.poke(true.B)

            dut.io.tl.a.bits.opcode.poke(TLOpcode.Get)
            dut.io.tl.a.bits.size.poke(3.U)
            dut.io.tl.a.bits.source.poke(1.U)
            dut.io.tl.a.bits.address.poke("h80000000".U)
            dut.io.tl.a.bits.mask.poke("hff".U)
            dut.io.tl.a.valid.poke(true.B)
            dut.io.tl.a.ready.expect(true.B)
            dut.clock.step()
            dut.io.tl.a.valid.poke(false.B)
            dut.io.tl.d.valid.expect(true.B)
            dut.io.tl.d.bits.opcode.expect(TLOpcode.AccessAckData)
            dut.io.tl.d.bits.source.expect(1.U)
            dut.io.tl.d.bits.denied.expect(false.B)
            dut.clock.step()

            dut.io.tl.a.bits.opcode.poke(TLOpcode.PutFullData)
            dut.io.tl.a.bits.source.poke(2.U)
            dut.io.tl.a.bits.address.poke("h80000008".U)
            dut.io.tl.a.bits.mask.poke("hff".U)
            dut.io.tl.a.bits.data.poke("h0123456789abcdef".U)
            dut.io.tl.a.valid.poke(true.B)
            dut.io.tl.a.ready.expect(true.B)
            dut.clock.step()
            dut.io.tl.a.valid.poke(false.B)
            dut.io.tl.d.valid.expect(true.B)
            dut.io.tl.d.bits.opcode.expect(TLOpcode.AccessAck)
            dut.io.tl.d.bits.source.expect(2.U)
            dut.io.tl.d.bits.denied.expect(true.B)
            dut.clock.step()
        }
    }
}
