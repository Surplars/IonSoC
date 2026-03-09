package soc.isa

import chisel3._

object PrivilegeLevel {
    val User       = 0.U(2.W)
    val Supervisor = 1.U(2.W)
    val Hypervisor = 2.U(2.W) // reserved for future use, not implemented in this design
    val Machine    = 3.U(2.W)
}

object CSR {
    // Supervisor Protection and Translation
    val SATP = 0x180.U(12.W)
    // Machine Information Registers
    val MVENDORID = 0xf11.U(12.W)
    val MARCHID   = 0xf12.U(12.W)
    val MIMPID    = 0xf13.U(12.W)
    val MHARTID   = 0xf14.U(12.W)
    // Machine Trap Setup
    val MSTATUS    = 0x300.U(12.W)
    val MISA       = 0x301.U(12.W)
    val MEDELEG    = 0x302.U(12.W)
    val MIDELEG    = 0x303.U(12.W)
    val MIE        = 0x304.U(12.W)
    val MTVEC      = 0x305.U(12.W)
    val MCOUNTEREN = 0x306.U(12.W)
    // Machine Trap Handling
    val MSCRATCH = 0x340.U(12.W)
    val MEPC     = 0x341.U(12.W)
    val MCAUSE   = 0x342.U(12.W)
    val MTVAL    = 0x343.U(12.W)
    val MIP      = 0x344.U(12.W)
    // Machine Memory Protection
    val PMPcfg0  = 0x3a0.U(12.W)
    val PMPaddr0 = 0x3b0.U(12.W)
    // Machine Non-Maskable Interrupt Handling
    val MNSCRATCH = 0x740.U(12.W)
    val MNEPC     = 0x741.U(12.W)
    val MNCAUSE   = 0x742.U(12.W)
    val MNSTATUS  = 0x744.U(12.W)
    // Machine Counter/Timers
    val MCYCLE   = 0xb00.U(12.W)
    val MINSTRET = 0xb02.U(12.W)
}

object MCause {
    private def int(code: Int, XLEN: Int = 64): UInt = {
        (BigInt(0) << (XLEN - 1) | code).U(XLEN.W)
    }
    private def exc(code: Int, XLEN: Int = 64): UInt = {
        (BigInt(1) << (XLEN - 1) | code).U(XLEN.W)
    }

    val InstrAddrMisaligned = exc(0)
    val InstrAccessFault    = exc(1)
    val IllegalInstr        = exc(2)
    val Breakpoint          = exc(3)
    val LoadAddrMisaligned  = exc(4)
    val LoadAccessFault     = exc(5)
    val StoreAddrMisaligned = exc(6)
    val StoreAccessFault    = exc(7)
    val EcallFromUMode      = exc(8)
    val EcallFromSMode      = exc(9)
    val EcallFromMMode      = exc(11)
    val InstrPageFault      = exc(12)
    val LoadPageFault       = exc(13)
    val StorePageFault      = exc(15)

    val SupervisorSoftInt  = int(1)
    val SupervisorTimerInt = int(5)
    val SupervisorExtInt   = int(9)
    val MachineSoftInt     = int(3)
    val MachineTimerInt    = int(7)
    val MachineExtInt      = int(11)
}
