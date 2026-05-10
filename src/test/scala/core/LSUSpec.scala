package core

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite
import soc.core.pipeline._
import soc.isa.{MCause, PrivilegeLevel}
import soc.memory.cache.CacheCmd

class LSUSpec extends AnyFunSuite with ChiselSim {
    private val pmpNapotRwx = 0x1f

    private def init(dut: LSU): Unit = {
        dut.io.pc_in.poke("h80000000".U)
        dut.io.valid_in.poke(false.B)
        dut.io.trap_valid.poke(false.B)
        dut.io.trap_info_in.valid.poke(false.B)
        dut.io.trap_info_in.pc.poke(0.U)
        dut.io.trap_info_in.cause.poke(0.U)
        dut.io.trap_info_in.value.poke(0.U)
        dut.io.trap_info_in.is_ret.poke(false.B)
        dut.io.trap_info_in.ret_type.poke(TrapReturnType.None)

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
        for (i <- 0 until 8) {
            dut.io.mem_cfg.pmpaddr(i).poke(0.U)
        }
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

    private def driveFence(dut: LSU): Unit = {
        dut.io.valid_in.poke(true.B)
        dut.io.alu_out.mem.valid.poke(true.B)
        dut.io.alu_out.mem.op.poke(MemOpType.Fence)
        dut.io.alu_out.mem.vaddr.poke(0.U)
        dut.io.alu_out.mem.paddr.poke(0.U)
        dut.io.alu_out.mem.size.poke(0.U)
        dut.io.alu_out.mem.mask.poke(0.U)
        dut.io.alu_out.mem.wdata.poke(0.U)
    }

    private def driveAtomic(dut: LSU, op: MemOpType.Type, atomic: AtomicOpType.Type, addr: BigInt, data: BigInt = 0, size: Int = 3): Unit = {
        val mask = if (size == 2) 0x0f else 0xff
        dut.io.valid_in.poke(true.B)
        dut.io.alu_out.reg_write.poke(true.B)
        dut.io.alu_out.mem.valid.poke(true.B)
        dut.io.alu_out.mem.op.poke(op)
        dut.io.alu_out.mem.atomic.poke(atomic)
        dut.io.alu_out.mem.vaddr.poke(addr.U)
        dut.io.alu_out.mem.paddr.poke(addr.U)
        dut.io.alu_out.mem.size.poke(size.U)
        dut.io.alu_out.mem.mask.poke(mask.U)
        dut.io.alu_out.mem.wdata.poke(data.U)
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

    test("LSU drains stores before issuing a D-cache fence") {
        simulate(new LSU(64)) { dut =>
            init(dut)

            driveStore(dut, BigInt("10000030", 16), BigInt("1122334455667788", 16))
            dut.clock.step()

            driveFence(dut)
            dut.io.stall_req.expect(true.B)
            dut.io.dcache.req.valid.expect(true.B)
            dut.io.dcache.req.bits.cmd.expect(CacheCmd.Write)
            dut.clock.step()

            dut.io.dcache.resp.valid.poke(true.B)
            dut.clock.step()
            dut.io.dcache.resp.valid.poke(false.B)

            var sawFenceReq = false
            for (_ <- 0 until 4 if !sawFenceReq) {
                dut.io.stall_req.expect(true.B)
                if (dut.io.dcache.req.valid.peek().litToBoolean && dut.io.dcache.req.bits.fence.peek().litToBoolean) {
                    sawFenceReq = true
                } else {
                    dut.clock.step()
                }
            }
            assert(sawFenceReq, "LSU did not issue D-cache fence after draining stores")
            dut.clock.step()

            dut.io.dcache.resp.valid.poke(true.B)
            dut.clock.step()
            dut.io.dcache.resp.valid.poke(false.B)

            dut.io.stall_req.expect(false.B)
        }
    }

    test("LSU blocks supervisor memory access without a matching PMP entry") {
        simulate(new LSU(64)) { dut =>
            init(dut)

            dut.io.mem_cfg.priv.poke(PrivilegeLevel.Supervisor)
            dut.io.pc_in.poke("h80000100".U)
            driveLoad(dut, BigInt("10000000", 16))
            dut.io.dcache.req.valid.expect(false.B)
            dut.io.mmio.req_valid.expect(false.B)

            dut.clock.step()
            dut.io.trap_info_out.valid.expect(true.B)
            dut.io.trap_info_out.pc.expect(BigInt("80000100", 16))
            dut.io.trap_info_out.cause.expect(MCause.LoadAccessFault)
            dut.io.trap_info_out.value.expect(BigInt("10000000", 16))
        }
    }

    test("LSU permits supervisor memory access through an all-address NAPOT PMP entry") {
        simulate(new LSU(64)) { dut =>
            init(dut)

            dut.io.mem_cfg.priv.poke(PrivilegeLevel.Supervisor)
            dut.io.mem_cfg.pmpcfg0.poke(pmpNapotRwx.U)
            dut.io.mem_cfg.pmpaddr(0).poke(BigInt("ffffffffffffffff", 16).U)
            driveLoad(dut, BigInt("10000008", 16))
            dut.clock.step()

            dut.io.trap_info_out.valid.expect(false.B)
            dut.io.dcache.req.valid.expect(true.B)
            dut.io.dcache.req.bits.cmd.expect(CacheCmd.Read)
            dut.io.dcache.req.bits.addr.expect(BigInt("10000008", 16))
        }
    }

    test("LSU reports supervisor store PMP denials as StoreAccessFault without enqueuing") {
        simulate(new LSU(64)) { dut =>
            init(dut)

            dut.io.mem_cfg.priv.poke(PrivilegeLevel.Supervisor)
            dut.io.mem_cfg.pmpcfg0.poke(0x19.U) // NAPOT, read-only
            dut.io.mem_cfg.pmpaddr(0).poke(BigInt("ffffffffffffffff", 16).U)
            dut.io.pc_in.poke("h80000140".U)
            driveStore(dut, BigInt("10000010", 16), BigInt("feedfacecafebeef", 16))
            dut.io.dcache.req.valid.expect(false.B)

            dut.clock.step()
            dut.io.trap_info_out.valid.expect(true.B)
            dut.io.trap_info_out.pc.expect(BigInt("80000140", 16))
            dut.io.trap_info_out.cause.expect(MCause.StoreAccessFault)
            dut.io.trap_info_out.value.expect(BigInt("10000010", 16))

            driveNoMem(dut)
            dut.clock.step()
            dut.io.dcache.req.valid.expect(false.B)
        }
    }

    test("LSU uses the first matching TOR PMP entry") {
        simulate(new LSU(64)) { dut =>
            init(dut)

            dut.io.mem_cfg.priv.poke(PrivilegeLevel.Supervisor)
            dut.io.mem_cfg.pmpcfg0.poke(BigInt("090f00", 16).U) // entry1 RWX TOR, entry2 R-only TOR
            dut.io.mem_cfg.pmpaddr(1).poke((BigInt("40000000", 16) >> 2).U)
            dut.io.mem_cfg.pmpaddr(2).poke((BigInt("40010000", 16) >> 2).U)
            driveStore(dut, BigInt("40000008", 16), BigInt("12345678", 16))
            dut.io.dcache.req.valid.expect(false.B)

            dut.clock.step()
            dut.io.trap_info_out.valid.expect(true.B)
            dut.io.trap_info_out.cause.expect(MCause.StoreAccessFault)
            dut.io.trap_info_out.value.expect(BigInt("40000008", 16))
        }
    }

    test("LSU completes LR/SC reservation success and writes store data") {
        simulate(new LSU(64)) { dut =>
            init(dut)

            driveAtomic(dut, MemOpType.LR, AtomicOpType.LR, BigInt("10000000", 16))
            dut.clock.step()
            dut.io.dcache.req.valid.expect(true.B)
            dut.io.dcache.req.bits.cmd.expect(CacheCmd.Read)
            dut.clock.step()

            dut.io.dcache.resp.valid.poke(true.B)
            dut.io.dcache.resp.bits.rdata.poke(BigInt("1122334455667788", 16).U)
            dut.clock.step()
            dut.io.dcache.resp.valid.poke(false.B)
            dut.io.load_data_valid.expect(true.B)
            dut.io.load_data.expect(BigInt("1122334455667788", 16))

            driveAtomic(dut, MemOpType.SC, AtomicOpType.SC, BigInt("10000000", 16), BigInt("aaaabbbbccccdddd", 16))
            dut.clock.step()
            dut.io.dcache.req.valid.expect(true.B)
            dut.io.dcache.req.bits.cmd.expect(CacheCmd.Read)
            dut.clock.step()

            dut.io.dcache.resp.valid.poke(true.B)
            dut.io.dcache.resp.bits.rdata.poke(BigInt("1122334455667788", 16).U)
            dut.clock.step()
            dut.io.dcache.resp.valid.poke(false.B)
            dut.io.dcache.req.valid.expect(true.B)
            dut.io.dcache.req.bits.cmd.expect(CacheCmd.Write)
            dut.io.dcache.req.bits.wdata.expect(BigInt("aaaabbbbccccdddd", 16))
            dut.clock.step()

            dut.io.dcache.resp.valid.poke(true.B)
            dut.clock.step()
            dut.io.dcache.resp.valid.poke(false.B)
            dut.io.load_data_valid.expect(true.B)
            dut.io.load_data.expect(0.U)
        }
    }

    test("LSU fails SC without a matching reservation") {
        simulate(new LSU(64)) { dut =>
            init(dut)

            driveAtomic(dut, MemOpType.SC, AtomicOpType.SC, BigInt("10000008", 16), BigInt("1234", 16))
            dut.clock.step()
            dut.io.dcache.req.valid.expect(true.B)
            dut.io.dcache.req.bits.cmd.expect(CacheCmd.Read)
            dut.clock.step()

            dut.io.dcache.resp.valid.poke(true.B)
            dut.io.dcache.resp.bits.rdata.poke(BigInt("feedfacecafebeef", 16).U)
            dut.clock.step()
            dut.io.dcache.resp.valid.poke(false.B)
            dut.io.dcache.req.valid.expect(false.B)
            dut.io.load_data_valid.expect(true.B)
            dut.io.load_data.expect(1.U)
        }
    }

    test("LSU performs AMOADD.D and returns the old value") {
        simulate(new LSU(64)) { dut =>
            init(dut)

            driveAtomic(dut, MemOpType.AMO, AtomicOpType.Add, BigInt("10000010", 16), 5)
            dut.clock.step()
            dut.io.dcache.req.valid.expect(true.B)
            dut.io.dcache.req.bits.cmd.expect(CacheCmd.Read)
            dut.clock.step()

            dut.io.dcache.resp.valid.poke(true.B)
            dut.io.dcache.resp.bits.rdata.poke(7.U)
            dut.clock.step()
            dut.io.dcache.resp.valid.poke(false.B)
            dut.io.dcache.req.valid.expect(true.B)
            dut.io.dcache.req.bits.cmd.expect(CacheCmd.Write)
            dut.io.dcache.req.bits.wdata.expect(12.U)
            dut.clock.step()

            dut.io.dcache.resp.valid.poke(true.B)
            dut.clock.step()
            dut.io.dcache.resp.valid.poke(false.B)
            dut.io.load_data_valid.expect(true.B)
            dut.io.load_data.expect(7.U)
        }
    }

    test("LSU performs AMOSWAP.W with sign-extended old value") {
        simulate(new LSU(64)) { dut =>
            init(dut)

            driveAtomic(dut, MemOpType.AMO, AtomicOpType.Swap, BigInt("10000018", 16), BigInt("0000000012345678", 16), size = 2)
            dut.clock.step()
            dut.clock.step()

            dut.io.dcache.resp.valid.poke(true.B)
            dut.io.dcache.resp.bits.rdata.poke(BigInt("0000000080000001", 16).U)
            dut.clock.step()
            dut.io.dcache.resp.valid.poke(false.B)
            dut.io.dcache.req.valid.expect(true.B)
            dut.io.dcache.req.bits.cmd.expect(CacheCmd.Write)
            dut.io.dcache.req.bits.mask.expect("h0f".U)
            dut.io.dcache.req.bits.wdata.expect(BigInt("1234567812345678", 16))
            dut.clock.step()

            dut.io.dcache.resp.valid.poke(true.B)
            dut.clock.step()
            dut.io.dcache.resp.valid.poke(false.B)
            dut.io.load_data_valid.expect(true.B)
            dut.io.load_data.expect(BigInt("ffffffff80000001", 16))
        }
    }

    test("LSU reports atomic misalignment with load/store-specific causes") {
        simulate(new LSU(64)) { dut =>
            init(dut)

            dut.io.pc_in.poke("h80000200".U)
            driveAtomic(dut, MemOpType.LR, AtomicOpType.LR, BigInt("10000004", 16))
            dut.clock.step()
            dut.io.trap_info_out.valid.expect(true.B)
            dut.io.trap_info_out.pc.expect(BigInt("80000200", 16))
            dut.io.trap_info_out.cause.expect(MCause.LoadAddrMisaligned)
            dut.io.trap_info_out.value.expect(BigInt("10000004", 16))

            init(dut)
            dut.io.pc_in.poke("h80000208".U)
            driveAtomic(dut, MemOpType.SC, AtomicOpType.SC, BigInt("10000004", 16), 1)
            dut.clock.step()
            dut.io.trap_info_out.valid.expect(true.B)
            dut.io.trap_info_out.pc.expect(BigInt("80000208", 16))
            dut.io.trap_info_out.cause.expect(MCause.StoreAddrMisaligned)
            dut.io.trap_info_out.value.expect(BigInt("10000004", 16))
        }
    }

    test("LSU rejects atomics to device regions") {
        simulate(new LSU(64)) { dut =>
            init(dut)

            dut.io.pc_in.poke("h80000240".U)
            driveAtomic(dut, MemOpType.AMO, AtomicOpType.Add, BigInt("10010000", 16), 1)
            dut.clock.step()
            dut.io.dcache.req.valid.expect(false.B)
            dut.io.mmio.req_valid.expect(false.B)
            dut.io.trap_info_out.valid.expect(true.B)
            dut.io.trap_info_out.pc.expect(BigInt("80000240", 16))
            dut.io.trap_info_out.cause.expect(MCause.StoreAccessFault)
            dut.io.trap_info_out.value.expect(BigInt("10010000", 16))
        }
    }

    test("LSU clears LR reservation after an intervening store drain") {
        simulate(new LSU(64)) { dut =>
            init(dut)

            driveAtomic(dut, MemOpType.LR, AtomicOpType.LR, BigInt("10000020", 16))
            dut.clock.step()
            dut.clock.step()
            dut.io.dcache.resp.valid.poke(true.B)
            dut.io.dcache.resp.bits.rdata.poke(BigInt("1111222233334444", 16).U)
            dut.clock.step()
            dut.io.dcache.resp.valid.poke(false.B)
            dut.io.load_data_valid.expect(true.B)

            driveStore(dut, BigInt("10000028", 16), BigInt("aaaabbbbccccdddd", 16))
            dut.clock.step()

            driveNoMem(dut)
            dut.io.dcache.req.valid.expect(true.B)
            dut.io.dcache.req.bits.cmd.expect(CacheCmd.Write)
            dut.clock.step()
            dut.io.dcache.resp.valid.poke(true.B)
            dut.clock.step()
            dut.io.dcache.resp.valid.poke(false.B)

            driveAtomic(dut, MemOpType.SC, AtomicOpType.SC, BigInt("10000020", 16), BigInt("5555666677778888", 16))
            dut.clock.step()
            dut.io.dcache.req.valid.expect(true.B)
            dut.io.dcache.req.bits.cmd.expect(CacheCmd.Read)
            dut.clock.step()

            dut.io.dcache.resp.valid.poke(true.B)
            dut.io.dcache.resp.bits.rdata.poke(BigInt("1111222233334444", 16).U)
            dut.clock.step()
            dut.io.dcache.resp.valid.poke(false.B)
            dut.io.dcache.req.valid.expect(false.B)
            dut.io.load_data_valid.expect(true.B)
            dut.io.load_data.expect(1.U)
        }
    }
}
