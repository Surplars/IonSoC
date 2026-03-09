package soc.core

import chisel3._

import csr.CSRFile
import pipeline._
import soc.bus.tilelink.TLParams
import soc.bus.tilelink.TLTransTracker
import soc.bus.tilelink.TLBundle
import soc.bus.tilelink.TLSystemXbar
import soc.memory.cache.L1Cache

class Core(XLEN: Int, hartID: Int) extends Module {
    private val tlParams = TLParams()
    private val dbusParams = tlParams.copy(sourceBits = tlParams.sourceBits + 1)

    val io = IO(new Bundle {
        val instr = Input(UInt(32.W))

        val fetch_en = Output(Bool())
        val pc       = Output(UInt(XLEN.W))

        val IBus = new TLBundle(tlParams)
        val DBus = new TLBundle(dbusParams)
    })

    val icache = Module(new L1Cache(tlParams, 256))
    val dcache = Module(new L1Cache(tlParams, 256))
    val tracker = Module(new TLTransTracker(tlParams, maxInFlight = 1 << tlParams.sourceBits))
    val dbusXbar = Module(new TLSystemXbar(tlParams))

    val pc       = Module(new PC(XLEN, soc.config.Config.resetVector))
    val register = Module(new RegisterFile(XLEN))
    val csr      = Module(new CSRFile(XLEN, hartID))

    val ifetch  = Module(new InstrFetch(XLEN))
    val idecode = Module(new InstrDecode(XLEN))
    val alu     = Module(new ALU(XLEN))
    val lsu     = Module(new LSU(XLEN))
    val wb      = Module(new WirteBack(XLEN))

	// Global Signals
	val global_stall = lsu.io.stall_req

    dontTouch(register.io)
    dontTouch(idecode.io)
    dontTouch(wb.io)

    dcache.io.cpu <> lsu.io.dcache
    lsu.io.mmio <> tracker.io.master
    dbusXbar.io.masters(0) <> dcache.io.bus
    dbusXbar.io.masters(1) <> tracker.io.tl
    io.DBus <> dbusXbar.io.slave
    io.IBus <> DontCare
	icache.io <> DontCare

    // pc
    io.pc            := pc.io.pc_out
    io.fetch_en      := pc.io.fetch_en
    pc.io.stall      := global_stall
    pc.io.trap_valid := lsu.io.trap_info_out.valid
    pc.io.trap_pc    := csr.io.tvec_out
    pc.io.trap_ret   := lsu.io.trap_info_out.is_ret
    pc.io.trap_epc   := csr.io.epc_out
    pc.io.br_info <> alu.io.br_info
    // register
    register.io.rs1_addr   := idecode.io.reg_rd_rs1
    register.io.rs2_addr   := idecode.io.reg_rd_rs2
    register.io.write_en   := wb.io.reg_wb.reg_write
    register.io.write_addr := wb.io.reg_wb.rd
    register.io.write_data := wb.io.reg_wb.data
    // CSR
    csr.io.valid      := alu.io.csr_valid
    csr.io.cmd        := alu.io.csr_cmd
    csr.io.addr       := alu.io.csr_addr
    csr.io.write      := alu.io.csr_write
    csr.io.wdata      := alu.io.csr_wdata
    csr.io.trap_valid := lsu.io.trap_info_out.valid
    csr.io.trap_pc    := lsu.io.trap_info_out.pc
    csr.io.trap_cause := lsu.io.trap_info_out.cause
    csr.io.trap_value := lsu.io.trap_info_out.value
    csr.io.is_ret     := lsu.io.trap_info_out.is_ret
    csr.io.ie_out     := DontCare
    // ifetch
    ifetch.io.stall         := global_stall
    ifetch.io.pc            := pc.io.pc_out
    ifetch.io.instr_in      := io.instr
    ifetch.io.pred_taken_in := pc.io.pred_taken
    ifetch.io.redirect      := pc.io.redirect
    ifetch.io.trap_valid    := wb.io.trap_info.valid
    // idcode
    idecode.io.valid_in      := ifetch.io.valid
    idecode.io.stall         := global_stall
    idecode.io.trap_valid    := wb.io.trap_info.valid
    idecode.io.redirect      := ifetch.io.redirect
    idecode.io.pc_in         := ifetch.io.pc_out
    idecode.io.instr_in      := ifetch.io.instr_out
    idecode.io.pred_taken_in := ifetch.io.pred_taken_out
    idecode.io.reg_rs1_data  := register.io.rs1_data
    idecode.io.reg_rs2_data  := register.io.rs2_data
    // alu
    alu.io.valid_in       := idecode.io.valid_out
    alu.io.stall          := global_stall
    alu.io.trap_valid     := wb.io.trap_info.valid
    alu.io.pc_in          := idecode.io.pc_out
    alu.io.next_pc_in     := idecode.io.pc_in
    alu.io.pred_taken_in  := idecode.io.pred_taken_out
    alu.io.decoded_in     := idecode.io.decoded_out
    alu.io.trap_info_in   := idecode.io.trap_info
    alu.io.csr_illegal    := csr.io.illegal
	alu.io.fwd.load_valid := lsu.io.load_data_valid
	alu.io.fwd.load_data  := lsu.io.load_data
    alu.io.fwd.reg_write  := lsu.io.mem_out.reg_write
    alu.io.fwd.rd         := lsu.io.mem_out.rd
    alu.io.fwd.alu_result := lsu.io.mem_out.result
    alu.io.csr_rdata      := csr.io.rdata
    // mem
    lsu.io.pc_in        := alu.io.pc_out
    lsu.io.valid_in     := alu.io.valid_out
    lsu.io.trap_valid   := wb.io.trap_info.valid
    lsu.io.alu_out      := alu.io.alu_out
    lsu.io.trap_info_in := alu.io.trap_info_out
    lsu.io.mem_cfg      := csr.io.mem_cfg_out
    // write back
    wb.io.pc_in     := lsu.io.pc_out
    wb.io.valid_in  := lsu.io.valid_out
    wb.io.mem_in    := lsu.io.mem_out
    wb.io.trap_info := lsu.io.trap_info_out
}
