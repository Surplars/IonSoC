package soc.core.csr

import chisel3._
import chisel3.util._
import soc.isa.CSR
import soc.core.pipeline.CSROps
import soc.core.pipeline.MemorySystemConfig
import soc.isa.PrivilegeLevel

object MStatus {
    val MIE  = 3
    val MPIE = 7
    val MPP  = 11

    def setMIE(mstatus: UInt, enable: Bool): UInt = {
        val mask = (1 << MIE).U
        Mux(enable, mstatus | mask, mstatus & ~mask)
    }
    def setMPIE(mstatus: UInt, enable: Bool): UInt = {
        val mask = (1 << MPIE).U
        Mux(enable, mstatus | mask, mstatus & ~mask)
    }
    def setMPP(mstatus: UInt, level: UInt): UInt = {
        val mask = (3 << MPP).U
        (mstatus & ~mask) | ((level & 3.U) << MPP)
    }
}

class CSRFile(XLEN: Int = 64, hartID: Int) extends Module {
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
        // 输出给Core的状态
        val epc_out  = Output(UInt(XLEN.W))
        val tvec_out = Output(UInt(XLEN.W))
        val ie_out   = Output(Bool()) // MIE全局中断使能
        val mem_cfg_out = Output(new MemorySystemConfig(XLEN))
    })

    val CurrentPrivLevel = RegInit(PrivilegeLevel.Machine)
    val misa_val         = WireInit(0.U(XLEN.W))
    misa_val := (2.U(2.W) << (XLEN - 2)) | (1.U << 8) | (1.U << 12) // RV64IM

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
    val satp      = RegInit(0.U(XLEN.W)) // 虚拟内存相关寄存器，暂不实现功能，仅提供读写接口

    val mapping = Map(
        CSR.MVENDORID  -> 0.U(XLEN.W),
        CSR.MARCHID    -> 0.U(XLEN.W),
        CSR.MIMPID     -> 0.U(XLEN.W),
        CSR.MHARTID    -> hartID.U(XLEN.W),
        CSR.MSTATUS    -> mstatus,
        CSR.MISA       -> misa_val,
        CSR.MEDELEG    -> medeleg,
        CSR.MIDELEG    -> mideleg,
        CSR.MIE        -> mstatus(MStatus.MIE), // MIE 位在 mstatus 的第 3 位
        CSR.MTVEC      -> mtvec,
        CSR.MCOUNTEREN -> 0.U(XLEN.W),          // 不允许用户模式访问性能计数器
        CSR.MSCRATCH   -> mscratch,
        CSR.MEPC       -> mepc,
        CSR.MCAUSE     -> mcause,
        CSR.MTVAL      -> mtval,
        CSR.MIP        -> 0.U(XLEN.W),          // 不提供 MIP 寄存器功能
        CSR.PMPcfg0    -> pmpcfg0,
        CSR.PMPaddr0   -> pmpaddr0,
        CSR.MNSCRATCH  -> mnscratch,            // None-Maskable中断下的寄存器
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
        (io.cmd === CSROps.RW.asUInt || io.cmd === CSROps.RS.asUInt || io.cmd === CSROps.RC.asUInt)

    when(do_write) {
        switch(io.addr) {
            is(CSR.MSTATUS) {
                mstatus := wdata_final
            }
            is(CSR.MEDELEG) {
                medeleg := wdata_final
            }
            is(CSR.MIDELEG) {
                mideleg := wdata_final
            }
            is(CSR.MTVEC) {
                mtvec := wdata_final
            }
            is(CSR.MEPC) {
                mepc := wdata_final
            }
            is(CSR.MCAUSE) {
                mcause := wdata_final
            }
            is(CSR.MTVAL) {
                mtval := wdata_final
            }
            is(CSR.MSCRATCH) {
                mscratch := wdata_final
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

    // 处理中断和异常
    when(io.trap_valid) {
        val curMie = (mstatus(MStatus.MIE)) & 1.U
        val trapMstatus = MStatus.setMPP(MStatus.setMIE(MStatus.setMPIE(mstatus, curMie.asBool), false.B), CurrentPrivLevel)
        mepc   := io.trap_pc
        mcause := io.trap_cause
        mtval  := io.trap_value
        mstatus := trapMstatus
        CurrentPrivLevel := PrivilegeLevel.Machine // 切换到机器模式
    }.elsewhen(io.is_ret) {
        // 处理 MRET 指令，恢复到 mepc 指向的地址
        mstatus := MStatus.setMPP(MStatus.setMIE(mstatus, mstatus(MStatus.MPIE)), 0.U)
        CurrentPrivLevel := mstatus(MStatus.MPP) // 恢复特权级
    }

    io.epc_out  := mepc
    io.tvec_out := mtvec
    io.ie_out   := mstatus(MStatus.MIE) // MIE 位
    io.mem_cfg_out.priv := CurrentPrivLevel
    io.mem_cfg_out.satp := satp
    io.mem_cfg_out.pmpcfg0 := pmpcfg0
    io.mem_cfg_out.pmpaddr0 := pmpaddr0
    io.mem_cfg_out.mxr := false.B
    io.mem_cfg_out.sum := false.B
    io.mem_cfg_out.mprv := false.B
}
