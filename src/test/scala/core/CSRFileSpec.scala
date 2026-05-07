package core

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite
import soc.core.csr.CSRFile
import soc.core.pipeline.CSROps
import soc.isa.{CSR, MCause}

class CSRFileSpec extends AnyFunSuite with ChiselSim {
    private val xlen = 64
    private val mieMask = (1L << 3) | (1L << 7) | (1L << 11)
    private val sInterruptMask = (1L << 1) | (1L << 5) | (1L << 9)

    private def init(dut: CSRFile): Unit = {
        dut.io.valid.poke(false.B)
        dut.io.cmd.poke(CSROps.RW.asUInt)
        dut.io.addr.poke(0.U)
        dut.io.write.poke(false.B)
        dut.io.wdata.poke(0.U)
        dut.io.trap_valid.poke(false.B)
        dut.io.trap_pc.poke(0.U)
        dut.io.trap_cause.poke(0.U)
        dut.io.trap_value.poke(0.U)
        dut.io.is_ret.poke(false.B)
        dut.io.msip.poke(false.B)
        dut.io.mtip.poke(false.B)
        dut.io.meip.poke(false.B)
        dut.io.ssip.poke(false.B)
        dut.io.stip.poke(false.B)
        dut.io.seip.poke(false.B)
    }

    private def writeCsr(dut: CSRFile, addr: UInt, value: BigInt): Unit = {
        dut.io.valid.poke(true.B)
        dut.io.write.poke(true.B)
        dut.io.cmd.poke(CSROps.RW.asUInt)
        dut.io.addr.poke(addr)
        dut.io.wdata.poke(value.U)
        dut.clock.step()
        dut.io.valid.poke(false.B)
        dut.io.write.poke(false.B)
        dut.io.wdata.poke(0.U)
    }

    test("CSRFile arbitrates machine interrupts by privileged priority") {
        simulate(new CSRFile(xlen, hartID = 0)) { dut =>
            init(dut)
            writeCsr(dut, CSR.MTVEC, BigInt("80000100", 16) | 1)
            writeCsr(dut, CSR.MIE, mieMask)
            writeCsr(dut, CSR.MSTATUS, 1 << 3)

            dut.io.msip.poke(true.B)
            dut.io.mtip.poke(true.B)
            dut.io.meip.poke(true.B)
            dut.io.interrupt.expect(true.B)
            dut.io.interrupt_cause.expect(MCause.MachineExtInt)
            dut.io.tvec_out.expect(BigInt("80000100", 16) + 4 * 11)

            dut.io.meip.poke(false.B)
            dut.io.interrupt_cause.expect(MCause.MachineSoftInt)
            dut.io.tvec_out.expect(BigInt("80000100", 16) + 4 * 3)

            dut.io.msip.poke(false.B)
            dut.io.interrupt_cause.expect(MCause.MachineTimerInt)
            dut.io.tvec_out.expect(BigInt("80000100", 16) + 4 * 7)
        }
    }

    test("CSRFile exposes delegated supervisor interrupt CSR views without taking M trap") {
        simulate(new CSRFile(xlen, hartID = 0)) { dut =>
            init(dut)
            writeCsr(dut, CSR.MIDELEG, sInterruptMask)
            writeCsr(dut, CSR.SIE, sInterruptMask)
            writeCsr(dut, CSR.MSTATUS, 1 << 3)

            dut.io.seip.poke(true.B)
            dut.io.interrupt.expect(false.B)

            dut.io.addr.poke(CSR.SIP)
            dut.io.rdata.expect(1 << 9)

            dut.io.seip.poke(false.B)
            dut.io.ssip.poke(true.B)
            dut.io.addr.poke(CSR.SIP)
            dut.io.rdata.expect(1 << 1)
        }
    }
}
