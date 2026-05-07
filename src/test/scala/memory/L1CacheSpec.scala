package memory

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite
import soc.bus.tilelink.{TLParams, TLRAM}
import soc.device.TLError
import soc.memory.cache.{CacheCmd, L1Cache, UncachedTileLinkBridge}

class CacheDeniedHarness(params: TLParams) extends Module {
    val io = IO(new Bundle {
        val req  = Flipped(Decoupled(new soc.memory.CacheReq(params.addrWidth, params.dataWidth)))
        val resp = Decoupled(new soc.memory.CacheResp(params.dataWidth))
    })

    val cache = Module(new L1Cache(params, nSets = 4))
    val error = Module(new TLError(params))

    cache.io.cpu.req <> io.req
    io.resp <> cache.io.cpu.resp
    error.io.tl <> cache.io.bus
}

class UncachedDeniedHarness(params: TLParams) extends Module {
    val io = IO(new Bundle {
        val req  = Flipped(Decoupled(new soc.memory.CacheReq(params.addrWidth, params.dataWidth)))
        val resp = Decoupled(new soc.memory.CacheResp(params.dataWidth))
    })

    val bridge = Module(new UncachedTileLinkBridge(params))
    val error = Module(new TLError(params))

    bridge.io.cpu.req <> io.req
    io.resp <> bridge.io.cpu.resp
    error.io.tl <> bridge.io.bus
}

class CacheRamHarness(params: TLParams) extends Module {
    val io = IO(new Bundle {
        val req  = Flipped(Decoupled(new soc.memory.CacheReq(params.addrWidth, params.dataWidth)))
        val resp = Decoupled(new soc.memory.CacheResp(params.dataWidth))
    })

    val cache = Module(new L1Cache(params, nSets = 4))
    val ram = Module(new TLRAM(params, sizeBytes = 4096))

    cache.io.cpu.req <> io.req
    io.resp <> cache.io.cpu.resp
    ram.io.tl <> cache.io.bus
}

class L1CacheSpec extends AnyFunSuite with ChiselSim {
    private val params = TLParams(addrWidth = 32, dataWidth = 64, sourceBits = 4, sinkBits = 1, sizeBits = 3)

    private def pokeReq(
        dut: CacheRamHarness,
        addr: BigInt,
        cmd: CacheCmd.Type,
        data: BigInt = 0,
        mask: BigInt = 0xff
    ): Unit = {
        dut.io.req.bits.addr.poke(addr.U)
        dut.io.req.bits.vaddr.poke(addr.U)
        dut.io.req.bits.cmd.poke(cmd)
        dut.io.req.bits.wdata.poke(data.U)
        dut.io.req.bits.mask.poke(mask.U)
        dut.io.req.bits.size.poke(3.U)
        dut.io.req.bits.signed.poke(false.B)
        dut.io.req.bits.fence.poke(false.B)
        dut.io.req.bits.fencei.poke(false.B)
        dut.io.req.bits.atomic.poke(false.B)
        dut.io.req.bits.cacheable.poke(true.B)
        dut.io.req.bits.device.poke(false.B)
    }

    test("Cache hit response remains stable under CPU backpressure") {
        simulate(new CacheRamHarness(params)) { dut =>
            dut.io.req.valid.poke(false.B)
            dut.io.resp.ready.poke(true.B)

            pokeReq(dut, addr = 0x80, cmd = CacheCmd.Write, data = BigInt("1122334455667788", 16))
            dut.io.req.valid.poke(true.B)
            dut.io.req.ready.expect(true.B)
            dut.clock.step()
            dut.io.req.valid.poke(false.B)

            var writeDone = false
            for (_ <- 0 until 20 if !writeDone) {
                if (dut.io.resp.valid.peek().litToBoolean) {
                    dut.io.resp.bits.err.expect(false.B)
                    writeDone = true
                }
                dut.clock.step()
            }
            assert(writeDone, "cache write miss did not complete")

            pokeReq(dut, addr = 0x80, cmd = CacheCmd.Read)
            dut.io.resp.ready.poke(false.B)
            dut.io.req.valid.poke(true.B)
            dut.io.req.ready.expect(true.B)
            dut.clock.step()
            dut.io.req.valid.poke(false.B)

            var sawHeldResp = false
            for (_ <- 0 until 10 if !sawHeldResp) {
                if (dut.io.resp.valid.peek().litToBoolean) {
                    dut.io.resp.bits.err.expect(false.B)
                    dut.io.resp.bits.rdata.expect("h1122334455667788".U)
                    dut.clock.step(3)
                    dut.io.resp.valid.expect(true.B)
                    dut.io.resp.bits.rdata.expect("h1122334455667788".U)
                    sawHeldResp = true
                } else {
                    dut.clock.step()
                }
            }
            assert(sawHeldResp, "cache hit response was not observed")

            dut.io.resp.ready.poke(true.B)
            dut.clock.step()
        }
    }

    test("Cache bridges report denied responses") {
        simulate(new CacheDeniedHarness(params)) { dut =>
            dut.io.req.valid.poke(false.B)
            dut.io.req.bits.addr.poke(0.U)
            dut.io.req.bits.vaddr.poke(0.U)
            dut.io.req.bits.cmd.poke(CacheCmd.Read)
            dut.io.req.bits.wdata.poke(0.U)
            dut.io.req.bits.mask.poke("hff".U)
            dut.io.req.bits.size.poke(3.U)
            dut.io.req.bits.signed.poke(false.B)
            dut.io.req.bits.fence.poke(false.B)
            dut.io.req.bits.fencei.poke(false.B)
            dut.io.req.bits.atomic.poke(false.B)
            dut.io.req.bits.cacheable.poke(true.B)
            dut.io.req.bits.device.poke(false.B)
            dut.io.resp.ready.poke(true.B)

            dut.io.req.bits.addr.poke("h1000".U)
            dut.io.req.bits.vaddr.poke("h1000".U)
            dut.io.req.valid.poke(true.B)
            dut.io.req.ready.expect(true.B)
            dut.clock.step()
            dut.io.req.valid.poke(false.B)

            var sawResp = false
            for (_ <- 0 until 20 if !sawResp) {
                if (dut.io.resp.valid.peek().litToBoolean) {
                    dut.io.resp.bits.err.expect(true.B)
                    sawResp = true
                }
                dut.clock.step()
            }
            assert(sawResp, "cache did not produce a response for denied refill")
        }

        simulate(new UncachedDeniedHarness(params)) { dut =>
            dut.io.req.valid.poke(false.B)
            dut.io.req.bits.addr.poke("h3000".U)
            dut.io.req.bits.vaddr.poke("h3000".U)
            dut.io.req.bits.cmd.poke(CacheCmd.Read)
            dut.io.req.bits.wdata.poke(0.U)
            dut.io.req.bits.mask.poke("hff".U)
            dut.io.req.bits.size.poke(3.U)
            dut.io.req.bits.signed.poke(false.B)
            dut.io.req.bits.fence.poke(false.B)
            dut.io.req.bits.fencei.poke(false.B)
            dut.io.req.bits.atomic.poke(false.B)
            dut.io.req.bits.cacheable.poke(true.B)
            dut.io.req.bits.device.poke(false.B)
            dut.io.resp.ready.poke(true.B)

            dut.io.req.valid.poke(true.B)
            dut.io.req.ready.expect(true.B)
            dut.clock.step()
            dut.io.req.valid.poke(false.B)

            var sawResp = false
            for (_ <- 0 until 8 if !sawResp) {
                if (dut.io.resp.valid.peek().litToBoolean) {
                    dut.io.resp.bits.err.expect(true.B)
                    sawResp = true
                }
                dut.clock.step()
            }
            assert(sawResp, "uncached bridge did not produce a response")
        }
    }
}
