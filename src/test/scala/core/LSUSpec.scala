package core

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite
import soc.core.pipeline._
import soc.isa.MCause
import soc.memory.cache.CacheCmd

class LSUSpec extends AnyFunSuite with ChiselSim {
    private def init(dut: LSU): Unit = {
        dut.io.pc_in.poke("h80000000".U)
        dut.io.valid_in.poke(false.B)
        dut.io.trap_valid.poke(false.B)
        dut.io.trap_info_in.valid.poke(false.B)
        dut.io.trap_info_in.pc.poke(0.U)
        dut.io.trap_info_in.cause.poke(0.U)
        dut.io.trap_info_in.value.poke(0.U)
        dut.io.trap_info_in.is_ret.poke(false.B)

        dut.io.alu_out.result.poke(0.U)
        dut.io.alu_out.funct3.poke(0.U)
        dut.io.alu_out.rd.poke(0.U)
        dut.io.alu_out.reg_write.poke(false.B)
        dut.io.alu_out.mem_read.poke(false.B)
        dut.io.alu_out.mem_write.poke(false.B)
        dut.io.alu_out.mem_addr.poke(0.U)
        dut.io.alu_out.mem.valid.poke(false.B)
        dut.io.alu_out.mem.op.poke(MemOpType.None)
        dut.io.alu_out.mem.vaddr.poke(0.U)
        dut.io.alu_out.mem.paddr.poke(0.U)
        dut.io.alu_out.mem.size.poke(3.U)
        dut.io.alu_out.mem.signed.poke(false.B)
        dut.io.alu_out.mem.mask.poke(0.U)
        dut.io.alu_out.mem.wdata.poke(0.U)
        dut.io.alu_out.mem.atomic.poke(AtomicOpType.None)
        dut.io.alu_out.mem.aq.poke(false.B)
        dut.io.alu_out.mem.rl.poke(false.B)
        dut.io.alu_out.mem.attrs.cacheable.poke(false.B)
        dut.io.alu_out.mem.attrs.device.poke(false.B)
        dut.io.alu_out.mem.attrs.bufferable.poke(false.B)
        dut.io.alu_out.mem.attrs.allocate.poke(false.B)
        dut.io.alu_out.mem.attrs.translate.poke(false.B)
        dut.io.alu_out.mem.attrs.executable.poke(false.B)

        dut.io.mem_cfg.priv.poke(3.U)
        dut.io.mem_cfg.mmu_en.poke(false.B)
        dut.io.mem_cfg.satp.poke(0.U)
        dut.io.mem_cfg.pmpcfg0.poke(0.U)
        dut.io.mem_cfg.pmpaddr0.poke(0.U)
        dut.io.mem_cfg.mxr.poke(false.B)
        dut.io.mem_cfg.sum.poke(false.B)
        dut.io.mem_cfg.mprv.poke(false.B)

        dut.io.dcache.req.ready.poke(true.B)
        dut.io.dcache.resp.valid.poke(false.B)
        dut.io.dcache.resp.bits.rdata.poke(0.U)
        dut.io.dcache.resp.bits.err.poke(false.B)

        dut.io.mmio.req_ready.poke(true.B)
        dut.io.mmio.resp_valid.poke(false.B)
        dut.io.mmio.resp_data.poke(0.U)
        dut.io.mmio.resp_cmd.poke(0.U)
        dut.io.mmio.resp_source.poke(0.U)
        dut.io.mmio.resp_err.poke(false.B)
    }

    private def driveStore(dut: LSU, addr: BigInt, data: BigInt): Unit = {
        dut.io.valid_in.poke(true.B)
        dut.io.alu_out.mem.valid.poke(true.B)
        dut.io.alu_out.mem.op.poke(MemOpType.Store)
        dut.io.alu_out.mem.vaddr.poke(addr.U)
        dut.io.alu_out.mem.paddr.poke(addr.U)
        dut.io.alu_out.mem.size.poke(3.U)
        dut.io.alu_out.mem.mask.poke("hff".U)
        dut.io.alu_out.mem.wdata.poke(data.U)
    }

    private def driveLoad(dut: LSU, addr: BigInt): Unit = {
        dut.io.valid_in.poke(true.B)
        dut.io.alu_out.mem.valid.poke(true.B)
        dut.io.alu_out.mem.op.poke(MemOpType.Load)
        dut.io.alu_out.mem.vaddr.poke(addr.U)
        dut.io.alu_out.mem.paddr.poke(addr.U)
        dut.io.alu_out.mem.size.poke(3.U)
        dut.io.alu_out.mem.mask.poke("hff".U)
        dut.io.alu_out.mem.signed.poke(false.B)
    }

    private def driveNoMem(dut: LSU): Unit = {
        dut.io.valid_in.poke(false.B)
        dut.io.alu_out.mem.valid.poke(false.B)
        dut.io.alu_out.mem.op.poke(MemOpType.None)
        dut.io.alu_out.mem.mask.poke(0.U)
        dut.io.alu_out.mem.wdata.poke(0.U)
    }

    test("LSU forwards a load from the newest store buffer entry without DCache access") {
        simulate(new LSU(64)) { dut =>
            init(dut)

            driveStore(dut, BigInt("10000000", 16), BigInt("1111111111111111", 16))
            dut.clock.step()

            driveStore(dut, BigInt("10000000", 16), BigInt("2222222222222222", 16))
            dut.clock.step()

            driveLoad(dut, BigInt("10000000", 16))
            dut.io.load_data_valid.expect(true.B)
            dut.io.load_data.expect(BigInt("2222222222222222", 16))
            dut.io.dcache.req.valid.expect(false.B)
        }
    }

    test("LSU reports store drain cache errors as StoreAccessFault") {
        simulate(new LSU(64)) { dut =>
            init(dut)

            dut.io.pc_in.poke("h80000040".U)
            driveStore(dut, BigInt("10000008", 16), BigInt("abcdef0123456789", 16))
            dut.clock.step()

            driveNoMem(dut)
            dut.io.dcache.req.valid.expect(true.B)
            dut.io.dcache.req.bits.cmd.expect(CacheCmd.Write)
            dut.io.dcache.req.bits.addr.expect(BigInt("10000008", 16))
            dut.clock.step()

            dut.io.dcache.resp.valid.poke(true.B)
            dut.io.dcache.resp.bits.err.poke(true.B)
            dut.clock.step()
            dut.io.dcache.resp.valid.poke(false.B)
            dut.io.dcache.resp.bits.err.poke(false.B)

            dut.clock.step()
            dut.io.trap_info_out.valid.expect(true.B)
            dut.io.trap_info_out.pc.expect(BigInt("80000040", 16))
            dut.io.trap_info_out.cause.expect(MCause.StoreAccessFault)
            dut.io.trap_info_out.value.expect(BigInt("10000008", 16))
        }
    }

    test("LSU reports cache load errors with the original load PC") {
        simulate(new LSU(64)) { dut =>
            init(dut)

            dut.io.pc_in.poke("h80000080".U)
            driveLoad(dut, BigInt("10000010", 16))
            dut.clock.step()

            dut.io.dcache.req.valid.expect(true.B)
            dut.io.dcache.req.bits.cmd.expect(CacheCmd.Read)
            dut.io.dcache.req.bits.addr.expect(BigInt("10000010", 16))
            dut.clock.step()

            dut.io.pc_in.poke("h80000088".U)
            dut.io.dcache.resp.valid.poke(true.B)
            dut.io.dcache.resp.bits.err.poke(true.B)
            dut.clock.step()
            dut.io.dcache.resp.valid.poke(false.B)
            dut.io.dcache.resp.bits.err.poke(false.B)

            dut.clock.step()
            dut.io.trap_info_out.valid.expect(true.B)
            dut.io.trap_info_out.pc.expect(BigInt("80000080", 16))
            dut.io.trap_info_out.cause.expect(MCause.LoadAccessFault)
            dut.io.trap_info_out.value.expect(BigInt("10000010", 16))
        }
    }

    test("LSU holds cache loads behind an outstanding store drain") {
        simulate(new LSU(64)) { dut =>
            init(dut)

            driveStore(dut, BigInt("10000018", 16), BigInt("123456789abcdef0", 16))
            dut.clock.step()

            driveNoMem(dut)
            dut.io.dcache.req.valid.expect(true.B)
            dut.io.dcache.req.bits.cmd.expect(CacheCmd.Write)
            dut.clock.step()

            driveLoad(dut, BigInt("10000020", 16))
            dut.io.stall_req.expect(true.B)
            dut.io.dcache.req.valid.expect(false.B)

            dut.io.dcache.resp.valid.poke(true.B)
            dut.io.dcache.resp.bits.err.poke(false.B)
            dut.clock.step()
            dut.io.dcache.resp.valid.poke(false.B)

            dut.io.stall_req.expect(true.B)
            dut.io.dcache.req.valid.expect(false.B)
            dut.clock.step()

            dut.io.dcache.req.valid.expect(true.B)
            dut.io.dcache.req.bits.cmd.expect(CacheCmd.Read)
            dut.io.dcache.req.bits.addr.expect(BigInt("10000020", 16))
        }
    }
}
