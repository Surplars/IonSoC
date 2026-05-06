package memory

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite
import soc.bus.tilelink.TLParams
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

class L1CacheSpec extends AnyFunSuite with ChiselSim {
    private val params = TLParams(addrWidth = 32, dataWidth = 64, sourceBits = 4, sinkBits = 1, sizeBits = 3)

    test("L1Cache reports TileLink denied refill as a cache response error") {
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
    }
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

class UncachedTileLinkBridgeSpec extends AnyFunSuite with ChiselSim {
    private val params = TLParams(addrWidth = 32, dataWidth = 64, sourceBits = 4, sinkBits = 1, sizeBits = 3)

    test("UncachedTileLinkBridge reports TileLink denied as a cache response error") {
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
