package bus

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite
import soc.bus.tilelink.{TLParams, TLRAM, TLXbar, TLOpcode}

class TLXbarHarness(params: TLParams) extends Module {
    val io = IO(new Bundle {
        val master = Flipped(new soc.bus.tilelink.TLBundle(params))
    })

    val xbar = Module(new TLXbar(
        params,
        nMasters = 1,
        nSlaves = 1,
        addrMap = Seq((addr: UInt) => addr >= "h1000".U && addr < "h2000".U)
    ))
    val ram = Module(new TLRAM(params, sizeBytes = 4096))

    xbar.io.masters(0) <> io.master
    xbar.io.slaves(0) <> ram.io.tl
}

class TLXbarSpec extends AnyFunSuite with ChiselSim {
    private val params = TLParams(addrWidth = 32, dataWidth = 64, sourceBits = 4, sinkBits = 1, sizeBits = 3)

    private def driveDefaults(dut: TLXbarHarness): Unit = {
        dut.io.master.a.valid.poke(false.B)
        dut.io.master.a.bits.opcode.poke(0.U)
        dut.io.master.a.bits.param.poke(0.U)
        dut.io.master.a.bits.size.poke(3.U)
        dut.io.master.a.bits.source.poke(0.U)
        dut.io.master.a.bits.address.poke(0.U)
        dut.io.master.a.bits.mask.poke(0.U)
        dut.io.master.a.bits.data.poke(0.U)
        dut.io.master.a.bits.corrupt.poke(false.B)
        dut.io.master.d.ready.poke(false.B)
    }

    test("TLXbar returns denied responses and still routes mapped requests") {
        simulate(new TLXbarHarness(params)) { dut =>
            driveDefaults(dut)
            dut.io.master.d.ready.poke(true.B)

            dut.io.master.a.bits.opcode.poke(TLOpcode.Get)
            dut.io.master.a.bits.size.poke(3.U)
            dut.io.master.a.bits.source.poke(9.U)
            dut.io.master.a.bits.address.poke("h3000".U)
            dut.io.master.a.bits.mask.poke("hff".U)
            dut.io.master.a.valid.poke(true.B)
            dut.io.master.a.ready.expect(true.B)
            dut.clock.step()
            dut.io.master.a.valid.poke(false.B)

            dut.io.master.d.valid.expect(true.B)
            dut.io.master.d.bits.opcode.expect(TLOpcode.AccessAckData)
            dut.io.master.d.bits.source.expect(9.U)
            dut.io.master.d.bits.denied.expect(true.B)

            dut.io.master.a.bits.opcode.poke(TLOpcode.PutFullData)
            dut.io.master.a.bits.size.poke(3.U)
            dut.io.master.a.bits.source.poke(2.U)
            dut.io.master.a.bits.address.poke("h1000".U)
            dut.io.master.a.bits.mask.poke("hff".U)
            dut.io.master.a.bits.data.poke("h0123456789abcdef".U)
            dut.io.master.a.valid.poke(true.B)
            dut.io.master.a.ready.expect(true.B)
            dut.clock.step()
            dut.io.master.a.valid.poke(false.B)

            var sawWriteAck = false
            for (_ <- 0 until 8 if !sawWriteAck) {
                if (dut.io.master.d.valid.peek().litToBoolean) {
                    dut.io.master.d.bits.opcode.expect(TLOpcode.AccessAck)
                    dut.io.master.d.bits.source.expect(2.U)
                    dut.io.master.d.bits.denied.expect(false.B)
                    sawWriteAck = true
                }
                dut.clock.step()
            }
            assert(sawWriteAck, "mapped write did not receive an ACK")

            dut.io.master.a.bits.opcode.poke(TLOpcode.Get)
            dut.io.master.a.bits.size.poke(3.U)
            dut.io.master.a.bits.source.poke(3.U)
            dut.io.master.a.bits.address.poke("h1000".U)
            dut.io.master.a.bits.mask.poke("hff".U)
            dut.io.master.a.bits.data.poke(0.U)
            dut.io.master.a.valid.poke(true.B)
            dut.io.master.a.ready.expect(true.B)
            dut.clock.step()
            dut.io.master.a.valid.poke(false.B)

            var sawReadAck = false
            for (_ <- 0 until 8 if !sawReadAck) {
                if (dut.io.master.d.valid.peek().litToBoolean) {
                    dut.io.master.d.bits.opcode.expect(TLOpcode.AccessAckData)
                    dut.io.master.d.bits.source.expect(3.U)
                    dut.io.master.d.bits.denied.expect(false.B)
                    dut.io.master.d.bits.data.expect(BigInt("0123456789abcdef", 16))
                    sawReadAck = true
                }
                dut.clock.step()
            }
            assert(sawReadAck, "mapped read did not receive data")

            dut.io.master.a.bits.opcode.poke(TLOpcode.Get)
            dut.io.master.a.bits.size.poke(0.U)
            dut.io.master.a.bits.source.poke(4.U)
            dut.io.master.a.bits.address.poke("h1005".U)
            dut.io.master.a.bits.mask.poke("h20".U)
            dut.io.master.a.bits.data.poke(0.U)
            dut.io.master.a.valid.poke(true.B)
            dut.io.master.a.ready.expect(true.B)
            dut.clock.step()
            dut.io.master.a.valid.poke(false.B)

            var sawByteReadAck = false
            for (_ <- 0 until 8 if !sawByteReadAck) {
                if (dut.io.master.d.valid.peek().litToBoolean) {
                    dut.io.master.d.bits.opcode.expect(TLOpcode.AccessAckData)
                    dut.io.master.d.bits.source.expect(4.U)
                    dut.io.master.d.bits.denied.expect(false.B)
                    dut.io.master.d.bits.data.expect(BigInt("0123456789abcdef", 16))
                    sawByteReadAck = true
                }
                dut.clock.step()
            }
            assert(sawByteReadAck, "mapped byte read did not preserve beat lanes")
        }
    }
}
