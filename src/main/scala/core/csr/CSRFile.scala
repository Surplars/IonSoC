package soc.core.csr

import chisel3._
import chisel3.util._
import soc.isa.CSR
import soc.isa.InterruptCauseCode
import soc.core.pipeline.CSROps
import soc.core.pipeline.MemorySystemConfig
import soc.isa.PrivilegeLevel
import soc.isa.Extension
import soc.config.Config
import soc.config.SoCFeatures

object MStatus {
	    val MIE  = 3
	    val MPIE = 7
	    val MPP  = 11

	    def setMIE(mstatus: UInt, enable: Bool): UInt = {
	        val mask = (BigInt(1) << MIE).U(mstatus.getWidth.W)
	        Mux(enable, mstatus | mask, mstatus & ~mask)
	    }
	    def setMPIE(mstatus: UInt, enable: Bool): UInt = {
	        val mask = (BigInt(1) << MPIE).U(mstatus.getWidth.W)
	        Mux(enable, mstatus | mask, mstatus & ~mask)
	    }
	    def setMPP(mstatus: UInt, level: UInt): UInt = {
	        val mask = (BigInt(3) << MPP).U(mstatus.getWidth.W)
	        (mstatus & ~mask) | ((level & 3.U) << MPP)
	    }
}

object SStatus {
    val SIE  = 1
    val SPIE = 5
    val SPP  = 8
}

class CSRFile(
    XLEN: Int = 64,
    hartID: Int,
    enabledExt: Set[Extension.Value] = Config.enabledExt,
    features: SoCFeatures = Config.features
) extends Module {
    val io = IO(new Bundle {
        // 读写接口
        val valid   = Input(Bool())
        val cmd     = Input(UInt(4.W))
        val addr    = Input(UInt(12.W))
        val write   = Input(Bool())
        val wdata   = Input(UInt(XLEN.W))
        val rdata   = Output(UInt(XLEN.W))
        val illegal = Output(Bool())
        // 硬件直接交互接口
        val trap_valid = Input(Bool())
        val trap_pc    = Input(UInt(XLEN.W))
        val trap_cause = Input(UInt(XLEN.W))
        val trap_value = Input(UInt(XLEN.W))
        val is_ret     = Input(Bool())
        // 中断信号
        val msip = Input(Bool())
        val mtip = Input(Bool())
        val meip = Input(Bool())
        val ssip = Input(Bool())
        val stip = Input(Bool())
        val seip = Input(Bool())
        // 输出给Core的状态
        val epc_out         = Output(UInt(XLEN.W))
        val tvec_out        = Output(UInt(XLEN.W))
        val interrupt_cause = Output(UInt(XLEN.W))
        val ie_out          = Output(Bool()) // MIE全局中断使能
        val interrupt       = Output(Bool()) // 是否有待处理的中断
        val mem_cfg_out = Output(new MemorySystemConfig(XLEN))
    })

    val CurrentPrivLevel = RegInit(PrivilegeLevel.Machine)
    private def misaExtensionMask(enabled: Set[Extension.Value]): BigInt = {
        def bit(letter: Char): BigInt = BigInt(1) << (letter.toLower - 'a')
        val base = BigInt(0) |
            (if (enabled.contains(Extension.RV32I) || enabled.contains(Extension.RV64I)) bit('i') else BigInt(0)) |
            (if (enabled.contains(Extension.RV32M) || enabled.contains(Extension.RV64M)) bit('m') else BigInt(0)) |
            (if (enabled.contains(Extension.RV32A) || enabled.contains(Extension.RV64A)) bit('a') else BigInt(0)) |
            (if (enabled.contains(Extension.RV32F) || enabled.contains(Extension.RV64F)) bit('f') else BigInt(0)) |
            (if (enabled.contains(Extension.RV32D) || enabled.contains(Extension.RV64D)) bit('d') else BigInt(0)) |
            (if (enabled.contains(Extension.RV32Q) || enabled.contains(Extension.RV64Q)) bit('q') else BigInt(0))
        base
    }

    val misa_val = ((BigInt(2) << (XLEN - 2)) | misaExtensionMask(enabledExt)).U(XLEN.W)

    val mstatus   = RegInit(0.U(XLEN.W))
    val mideleg   = RegInit(0.U(XLEN.W))
    val medeleg   = RegInit(0.U(XLEN.W))
    val mtvec     = RegInit(0.U(XLEN.W))
    val mepc      = RegInit(0.U(XLEN.W))
    val mcause    = RegInit(0.U(XLEN.W))
    val mtval     = RegInit(0.U(XLEN.W))
    val mscratch  = RegInit(0.U(XLEN.W))
    val pmpcfg0   = RegInit(0.U(XLEN.W))
    val pmpaddr0  = RegInit(0.U(XLEN.W))
    val mnscratch = RegInit(0.U(XLEN.W))
    val mnepc     = RegInit(0.U(XLEN.W))
    val mncause   = RegInit(0.U(XLEN.W))
    val mnstatus  = RegInit(0.U(XLEN.W))
    val satp      = RegInit(0.U(XLEN.W))
    val mie       = RegInit(0.U(XLEN.W))       // 中断使能寄存器 (MSIE=3, MTIE=7, MEIE=11)
    val stvec     = RegInit(0.U(XLEN.W))
    val sepc      = RegInit(0.U(XLEN.W))
    val scause    = RegInit(0.U(XLEN.W))
    val stval     = RegInit(0.U(XLEN.W))
    val sscratch  = RegInit(0.U(XLEN.W))
    val ssipSw    = RegInit(false.B)
    val mip       = WireInit(0.U(XLEN.W))      // 中断 pending，部分由硬件连线

    private def bitMask(bit: Int): UInt = (BigInt(1) << bit).U(XLEN.W)
    private def interruptCause(code: Int): UInt = ((BigInt(1) << (XLEN - 1)) | code).U(XLEN.W)

    val supervisorInterruptMask =
        bitMask(InterruptCauseCode.SupervisorSoft) |
            bitMask(InterruptCauseCode.SupervisorTimer) |
            bitMask(InterruptCauseCode.SupervisorExt)
    val writableMieMask =
        supervisorInterruptMask |
            bitMask(InterruptCauseCode.MachineSoft) |
            bitMask(InterruptCauseCode.MachineTimer) |
            bitMask(InterruptCauseCode.MachineExt)

    mip :=
        Mux(io.ssip || ssipSw, bitMask(InterruptCauseCode.SupervisorSoft), 0.U) |
            Mux(io.msip, bitMask(InterruptCauseCode.MachineSoft), 0.U) |
            Mux(io.stip, bitMask(InterruptCauseCode.SupervisorTimer), 0.U) |
            Mux(io.mtip, bitMask(InterruptCauseCode.MachineTimer), 0.U) |
            Mux(io.seip, bitMask(InterruptCauseCode.SupervisorExt), 0.U) |
            Mux(io.meip, bitMask(InterruptCauseCode.MachineExt), 0.U)

    val sstatus =
        (mstatus & (bitMask(SStatus.SIE) | bitMask(SStatus.SPIE) | bitMask(SStatus.SPP))) |
            (mstatus & (bitMask(13) | bitMask(14) | bitMask(15) | bitMask(18) | bitMask(19))) |
            (mstatus & (bitMask(63)))
    val sie = mie & mideleg & supervisorInterruptMask
    val sip = mip & mideleg & supervisorInterruptMask

    val mapping = Map(
        CSR.SSTATUS   -> sstatus,
        CSR.SIE       -> sie,
        CSR.STVEC     -> stvec,
        CSR.SSCRATCH  -> sscratch,
        CSR.SEPC      -> sepc,
        CSR.SCAUSE    -> scause,
        CSR.STVAL     -> stval,
        CSR.SIP       -> sip,
        CSR.MVENDORID  -> 0.U(XLEN.W),
        CSR.MARCHID    -> 0.U(XLEN.W),
        CSR.MIMPID     -> 0.U(XLEN.W),
        CSR.MHARTID    -> hartID.U(XLEN.W),
        CSR.MSTATUS    -> mstatus,
        CSR.MISA       -> misa_val,
        CSR.MEDELEG    -> medeleg,
        CSR.MIDELEG    -> mideleg,
        CSR.MIE        -> mie,
        CSR.MTVEC      -> mtvec,
        CSR.MCOUNTEREN -> 0.U(XLEN.W),
        CSR.MSCRATCH   -> mscratch,
        CSR.MEPC       -> mepc,
        CSR.MCAUSE     -> mcause,
        CSR.MTVAL      -> mtval,
        CSR.MIP        -> mip,
        CSR.PMPcfg0    -> pmpcfg0,
        CSR.PMPaddr0   -> pmpaddr0,
        CSR.MNSCRATCH  -> mnscratch,
        CSR.MNEPC      -> mnepc,
        CSR.MNCAUSE    -> mncause,
        CSR.MNSTATUS   -> mnstatus,
        CSR.SATP       -> satp
    )

    val rdata_pre = MuxLookup(io.addr, 0.U)(mapping.toSeq)

    val addr_valid = mapping.keys.map(_ === io.addr).reduce(_ || _)

    io.rdata   := rdata_pre
    io.illegal := io.valid && !addr_valid

    val wdata_final = MuxLookup(io.cmd, 0.U)(
        Seq(
            CSROps.RW.asUInt  -> io.wdata,                // 直接写入
            CSROps.RS.asUInt  -> (rdata_pre | io.wdata),  // 读
            CSROps.RC.asUInt  -> (rdata_pre & ~io.wdata), // 清除
            CSROps.RWI.asUInt -> io.wdata,                // 直接写入，rs2编码为zimm
            CSROps.RSI.asUInt -> (rdata_pre | io.wdata),  // 读，rs2编码为zimm
            CSROps.RCI.asUInt -> (rdata_pre & ~io.wdata)  // 清除，rs2编码为zimm
        )
    )

	    val do_write = io.valid && !io.illegal && io.write &&
	        (io.cmd === CSROps.RW.asUInt || io.cmd === CSROps.RS.asUInt || io.cmd === CSROps.RC.asUInt ||
	            io.cmd === CSROps.RWI.asUInt || io.cmd === CSROps.RSI.asUInt || io.cmd === CSROps.RCI.asUInt)

    when(do_write) {
        switch(io.addr) {
            is(CSR.MSTATUS) {
                mstatus := wdata_final
            }
            is(CSR.SSTATUS) {
                val sMask = bitMask(SStatus.SIE) | bitMask(SStatus.SPIE) | bitMask(SStatus.SPP)
                mstatus := (mstatus & ~sMask) | (wdata_final & sMask)
            }
            is(CSR.MEDELEG) {
                medeleg := wdata_final
            }
            is(CSR.MIDELEG) {
                mideleg := wdata_final & supervisorInterruptMask
            }
            is(CSR.MIE) {
                mie := wdata_final & writableMieMask
            }
            is(CSR.SIE) {
                mie := (mie & ~(mideleg & supervisorInterruptMask)) | (wdata_final & mideleg & supervisorInterruptMask)
            }
            is(CSR.MTVEC) {
                mtvec := wdata_final
            }
            is(CSR.STVEC) {
                stvec := wdata_final
            }
            is(CSR.MEPC) {
                mepc := wdata_final
            }
            is(CSR.SEPC) {
                sepc := wdata_final
            }
            is(CSR.MCAUSE) {
                mcause := wdata_final
            }
            is(CSR.SCAUSE) {
                scause := wdata_final
            }
            is(CSR.MTVAL) {
                mtval := wdata_final
            }
            is(CSR.STVAL) {
                stval := wdata_final
            }
            is(CSR.MSCRATCH) {
                mscratch := wdata_final
            }
            is(CSR.SSCRATCH) {
                sscratch := wdata_final
            }
            is(CSR.SIP) {
                when(mideleg(InterruptCauseCode.SupervisorSoft)) {
                    ssipSw := wdata_final(InterruptCauseCode.SupervisorSoft)
                }
            }
            is(CSR.PMPcfg0) {
                pmpcfg0 := wdata_final
            }
            is(CSR.PMPaddr0) {
                pmpaddr0 := wdata_final
            }
            is(CSR.MNSCRATCH) {
                mnscratch := wdata_final
            }
            is(CSR.MNSTATUS) {
                mnstatus := wdata_final
            }
            is(CSR.SATP) {
                satp := wdata_final
            }
        }
    }

    val pendingEnabled = mie & mip
    val machineVisiblePending = pendingEnabled & ~mideleg
    val machineInterruptCause = Wire(UInt(XLEN.W))
    machineInterruptCause := 0.U
    when(machineVisiblePending(InterruptCauseCode.MachineExt)) {
        machineInterruptCause := interruptCause(InterruptCauseCode.MachineExt)
    }.elsewhen(machineVisiblePending(InterruptCauseCode.MachineSoft)) {
        machineInterruptCause := interruptCause(InterruptCauseCode.MachineSoft)
    }.elsewhen(machineVisiblePending(InterruptCauseCode.MachineTimer)) {
        machineInterruptCause := interruptCause(InterruptCauseCode.MachineTimer)
    }.elsewhen(machineVisiblePending(InterruptCauseCode.SupervisorExt)) {
        machineInterruptCause := interruptCause(InterruptCauseCode.SupervisorExt)
    }.elsewhen(machineVisiblePending(InterruptCauseCode.SupervisorSoft)) {
        machineInterruptCause := interruptCause(InterruptCauseCode.SupervisorSoft)
    }.elsewhen(machineVisiblePending(InterruptCauseCode.SupervisorTimer)) {
        machineInterruptCause := interruptCause(InterruptCauseCode.SupervisorTimer)
    }
    val machineInterruptGlobalEnable = (CurrentPrivLevel < PrivilegeLevel.Machine) || mstatus(MStatus.MIE)
    val machineInterruptPending = machineVisiblePending =/= 0.U
    val machineInterrupt = machineInterruptPending && machineInterruptGlobalEnable

    val mtvecBase = mtvec & ~3.U(XLEN.W)
    val mtvecMode = mtvec(1, 0)
    val machineInterruptCode = machineInterruptCause(XLEN - 2, 0)
    val machineTrapVector = Mux(
        machineInterrupt && mtvecMode === 1.U,
        mtvecBase + (machineInterruptCode << 2),
        mtvecBase
    )

    // 处理中断和异常
    when(io.trap_valid) {
        val curMie = mstatus(MStatus.MIE)
        val trapMstatus = MStatus.setMPP(MStatus.setMIE(MStatus.setMPIE(mstatus, curMie), false.B), CurrentPrivLevel)
        mepc   := io.trap_pc
        mcause := io.trap_cause
        mtval  := io.trap_value
        mstatus := trapMstatus
        CurrentPrivLevel := PrivilegeLevel.Machine // 切换到机器模式
	    }.elsewhen(io.is_ret) {
	        // 处理 MRET 指令，恢复到 mepc 指向的地址
	        mstatus := MStatus.setMPIE(MStatus.setMPP(MStatus.setMIE(mstatus, mstatus(MStatus.MPIE)), 0.U), true.B)
	        CurrentPrivLevel := mstatus(MStatus.MPP) // 恢复特权级
	    }

    io.epc_out  := mepc
    io.tvec_out := machineTrapVector
    io.interrupt_cause := machineInterruptCause
    io.ie_out   := mstatus(MStatus.MIE)
    io.interrupt := machineInterrupt
    io.mem_cfg_out.priv := CurrentPrivLevel
    io.mem_cfg_out.mmu_en := features.mmu.B
    io.mem_cfg_out.satp := satp
    io.mem_cfg_out.pmpcfg0 := pmpcfg0
    io.mem_cfg_out.pmpaddr0 := pmpaddr0
    io.mem_cfg_out.mxr := false.B
    io.mem_cfg_out.sum := false.B
    io.mem_cfg_out.mprv := false.B
}
