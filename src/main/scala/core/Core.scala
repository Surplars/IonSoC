package soc.core

import chisel3._
import chisel3.util._

import csr.CSRFile
import pipeline._
import soc.bus.tilelink.TLParams
import soc.bus.tilelink.TLTransTracker
import soc.bus.tilelink.TLBundle
import soc.bus.tilelink.TLSystemXbar
import soc.memory.cache.HasCacheCoreIO
import soc.memory.cache.L1Cache
import soc.memory.cache.UncachedTileLinkBridge
import soc.config.Config
import soc.config.SoCFeatures
import soc.isa.Extension

class Core(
    XLEN: Int,
    hartID: Int,
    features: SoCFeatures = Config.features,
    enabledExt: Set[Extension.Value] = Config.enabledExt
) extends Module {
    private val tlParams = TLParams()
    private val hasICache = features.iCache
    private val hasDCache = features.dCache
    private val nMasters = 2 + (if (hasICache) 1 else 0) // dcache, tracker, optional icache
    private val dbusParams = tlParams.copy(sourceBits = tlParams.sourceBits + log2Ceil(nMasters))

    val io = IO(new Bundle {
        val instr = Input(UInt(32.W))

        val fetch_en = Output(Bool())
        val pc       = Output(UInt(XLEN.W))

        val IBus = new TLBundle(tlParams)
        val DBus = new TLBundle(dbusParams)

        val msip = Input(Bool())
        val mtip = Input(Bool())
        val meip = Input(Bool())
        val ssip = Input(Bool())
        val stip = Input(Bool())
        val seip = Input(Bool())
        val debug_haltreq = Input(Bool())
        val debug_resumereq = Input(Bool())
        val debug_halted = Output(Bool())
        val debug_gpr_addr = Input(UInt(5.W))
        val debug_gpr_rdata = Output(UInt(XLEN.W))
        val debug_gpr_write = Input(Bool())
        val debug_gpr_wdata = Input(UInt(XLEN.W))
        val debug_csr_addr = Input(UInt(12.W))
        val debug_csr_rdata = Output(UInt(XLEN.W))
        val debug_csr_valid = Output(Bool())
        val debug_csr_writable = Output(Bool())
        val debug_csr_write = Input(Bool())
        val debug_csr_wdata = Input(UInt(XLEN.W))
    })

    val dcache: HasCacheCoreIO = if (hasDCache) Module(new L1Cache(tlParams, 256)) else Module(new UncachedTileLinkBridge(tlParams))
    val tracker = Module(new TLTransTracker(tlParams, maxInFlight = 1 << tlParams.sourceBits))
    val dbusXbar = Module(new TLSystemXbar(tlParams, nMasters))
    val icache = if (hasICache) Some(Module(new L1Cache(tlParams, 256))) else None

    val pc       = Module(new PC(XLEN, soc.config.Config.resetVector))
    val register = Module(new RegisterFile(XLEN))
    val csr      = Module(new CSRFile(XLEN, hartID, enabledExt, features))

    val ifetch  = Module(new InstrFetch(XLEN, useCache = hasICache, useCompressed = enabledExt.contains(Extension.C)))
    val idecode = Module(new InstrDecode(XLEN, enabledExt))
    val alu     = Module(new ALU(XLEN))
    val lsu     = Module(new LSU(XLEN))
    val wb      = Module(new WirteBack(XLEN))

    // Global stall includes both memory-stage backpressure and optional
    // instruction-cache fetch backpressure.
    val global_stall = Wire(Bool())
    val pipe_stall = Wire(Bool())
    val debugHalted = RegInit(false.B)

    dontTouch(register.io)
    dontTouch(idecode.io)
    dontTouch(wb.io)

    val fenceIReq = alu.io.valid_out && alu.io.alu_out.mem.valid && alu.io.alu_out.mem.op === MemOpType.FenceI
    val fenceIPending = RegInit(false.B)
    val fenceIAck = WireInit(false.B)
    when(fenceIReq) {
        fenceIPending := true.B
    }.elsewhen(fenceIAck) {
        fenceIPending := false.B
    }
    val fenceIActive = fenceIPending || fenceIReq
    val fenceIHold = fenceIActive && !fenceIAck

    dcache.io.cpu <> lsu.io.dcache
    dcache.io.invalidate.valid := false.B
    dcache.io.invalidate.bits := false.B
    lsu.io.mmio <> tracker.io.master
    dbusXbar.io.masters(0) <> dcache.io.bus
    dbusXbar.io.masters(1) <> tracker.io.tl

    if (hasICache) {
        val cache = icache.get
        dbusXbar.io.masters(2) <> cache.io.bus
        cache.io.cpu <> ifetch.io.cache
        cache.io.invalidate.valid := fenceIPending || fenceIReq
        cache.io.invalidate.bits := true.B
        fenceIAck := cache.io.invalidate.fire
    } else {
        ifetch.io.cache.req.ready := false.B
        ifetch.io.cache.resp.valid := false.B
        ifetch.io.cache.resp.bits.rdata := 0.U
        ifetch.io.cache.resp.bits.err := false.B
        fenceIAck := fenceIPending || fenceIReq
    }

    io.DBus <> dbusXbar.io.slave
    io.IBus <> DontCare

    val aluMemOp = alu.io.alu_out.mem.op
    def isLoadLikeOp(op: MemOpType.Type): Bool =
        op === MemOpType.Load || op === MemOpType.LR || op === MemOpType.SC || op === MemOpType.AMO

    def usesRd(rs1: UInt, rs2: UInt, rd: UInt): Bool =
        rd =/= 0.U && ((rs1 === rd && rs1 =/= 0.U) || (rs2 === rd && rs2 =/= 0.U))

    val loadLikePending = RegInit(false.B)
    val loadLikePendingRd = RegInit(0.U(5.W))
    val aluLoadLike = alu.io.valid_out && alu.io.alu_out.reg_write && alu.io.alu_out.rd =/= 0.U &&
        isLoadLikeOp(aluMemOp)
    val loadLikeComplete =
        loadLikePending && (lsu.io.load_data_valid ||
            (wb.io.reg_wb.reg_write && wb.io.reg_wb.rd === loadLikePendingRd))
    val decodeUsesPending =
        loadLikePending && idecode.io.valid_out &&
            usesRd(idecode.io.decoded_out.rs1, idecode.io.decoded_out.rs2, loadLikePendingRd)
    when(loadLikeComplete) {
        loadLikePending := false.B
        loadLikePendingRd := 0.U
    }.elsewhen(aluLoadLike) {
        loadLikePending := true.B
        loadLikePendingRd := alu.io.alu_out.rd
    }

    val aluResultFwdValid = RegNext(
        alu.io.valid_out && alu.io.alu_out.reg_write && alu.io.alu_out.rd =/= 0.U &&
            !isLoadLikeOp(aluMemOp),
        false.B
    )
    val aluResultFwdRd = RegEnable(alu.io.alu_out.rd, 0.U, alu.io.valid_out)
    val aluResultFwdData = RegEnable(alu.io.alu_out.result, 0.U, alu.io.valid_out)
    val aluResultPrevValid = RegNext(aluResultFwdValid, false.B)
    val aluResultPrevRd = RegNext(aluResultFwdRd, 0.U)
    val aluResultPrevData = RegNext(aluResultFwdData, 0.U)

    pipe_stall := lsu.io.stall_req
    when(io.debug_resumereq) {
        debugHalted := false.B
    }.elsewhen(io.debug_haltreq && !pipe_stall) {
        debugHalted := true.B
    }
    io.debug_halted := debugHalted

    global_stall := pipe_stall || decodeUsesPending || ifetch.io.fetch_stall || fenceIHold || debugHalted

    // Interrupt inputs. Supervisor-level lines are reserved for the future
    // S-mode trap path and can be tied off by the SoC until a controller exists.
    csr.io.msip := io.msip
    csr.io.mtip := io.mtip
    csr.io.meip := io.meip
    csr.io.ssip := io.ssip
    csr.io.stip := io.stip
    csr.io.seip := io.seip

	    // Combine pipeline traps with external interrupts. External interrupts are
	    // latched before redirect so CSR and PC consume the same trap PC.
	    val has_pipeline_trap = lsu.io.trap_info_out.valid
	    val has_ret           = lsu.io.trap_info_out.is_ret
        val retConsumed       = RegInit(false.B)
	    val ret_redirect      = has_ret && !retConsumed
        when(ret_redirect) {
            retConsumed := true.B
        }.elsewhen(!has_ret) {
            retConsumed := false.B
        }
        val interruptPending = RegInit(false.B)
        val interruptPc      = RegInit(0.U(XLEN.W))
        val interruptCause   = RegInit(0.U(XLEN.W))
        val interruptTarget  = RegInit(0.U(XLEN.W))
        val interrupt_fire   = interruptPending && !pipe_stall && !has_pipeline_trap && !ret_redirect
        val interrupt_detect = csr.io.interrupt && !pipe_stall && !has_pipeline_trap && !ret_redirect && !interruptPending
        when(interrupt_fire) {
            interruptPending := false.B
        }.elsewhen(interrupt_detect) {
            interruptPending := true.B
            interruptPc      := pc.io.pc_out
            interruptCause   := csr.io.interrupt_cause
            interruptTarget  := csr.io.tvec_out
        }
	    val combined_trap     = has_pipeline_trap || interrupt_fire
	    val pipeline_flush    = combined_trap || ret_redirect || fenceIActive

    // pc
    io.pc            := pc.io.pc_out
    io.fetch_en      := pc.io.fetch_en
    pc.io.stall      := global_stall
	    pc.io.trap_valid := combined_trap
    pc.io.trap_pc    := Mux(has_pipeline_trap, csr.io.tvec_out, interruptTarget)
	    pc.io.trap_ret   := ret_redirect
    pc.io.trap_epc   := csr.io.epc_out
    pc.io.instr_len  := ifetch.io.pc_step_len
    pc.io.br_info <> alu.io.br_info
    // register
    register.io.rs1_addr   := idecode.io.reg_rd_rs1
    register.io.rs2_addr   := idecode.io.reg_rd_rs2
    register.io.write_en   := wb.io.reg_wb.reg_write
    register.io.write_addr := wb.io.reg_wb.rd
    register.io.write_data := wb.io.reg_wb.data
    register.io.debug_addr := io.debug_gpr_addr
    register.io.debug_write := io.debug_gpr_write && debugHalted
    register.io.debug_wdata := io.debug_gpr_wdata
    io.debug_gpr_rdata := register.io.debug_rdata
    // CSR
    csr.io.valid      := alu.io.csr_valid
    csr.io.cmd        := alu.io.csr_cmd
    csr.io.addr       := alu.io.csr_addr
    csr.io.write      := alu.io.csr_write
    csr.io.wdata      := alu.io.csr_wdata
    csr.io.debug_addr := io.debug_csr_addr
    csr.io.debug_write := io.debug_csr_write && debugHalted
    csr.io.debug_wdata := io.debug_csr_wdata
    io.debug_csr_rdata := csr.io.debug_rdata
    io.debug_csr_valid := csr.io.debug_valid
    io.debug_csr_writable := csr.io.debug_writable
    csr.io.trap_valid := combined_trap
    // Exceptions take priority over interrupts for pc/cause
    csr.io.trap_pc    := Mux(has_pipeline_trap, lsu.io.trap_info_out.pc, interruptPc)
    csr.io.trap_cause := Mux(has_pipeline_trap, lsu.io.trap_info_out.cause, interruptCause)
    csr.io.trap_value := Mux(has_pipeline_trap, lsu.io.trap_info_out.value, 0.U)
    csr.io.is_ret     := ret_redirect
    csr.io.ret_type   := lsu.io.trap_info_out.ret_type
    csr.io.ie_out     := DontCare
    // ifetch
    ifetch.io.stall         := pipe_stall || decodeUsesPending || debugHalted
    ifetch.io.pc            := pc.io.pc_out
    ifetch.io.instr_in      := io.instr
    ifetch.io.pred_taken_in := pc.io.pred_taken
    ifetch.io.redirect      := pc.io.redirect
	    ifetch.io.trap_valid    := pipeline_flush
    // idcode
    idecode.io.valid_in      := ifetch.io.valid
    idecode.io.stall         := pipe_stall || decodeUsesPending || debugHalted
	    idecode.io.trap_valid    := pipeline_flush
    idecode.io.redirect      := ifetch.io.redirect
    idecode.io.pc_in         := ifetch.io.pc_out
    idecode.io.instr_in      := ifetch.io.instr_out
    idecode.io.instr_len_in  := ifetch.io.instr_len
    idecode.io.priv          := csr.io.mem_cfg_out.priv
    idecode.io.pred_taken_in := ifetch.io.pred_taken_out
    val aluBypassValid = alu.io.valid_out && alu.io.alu_out.reg_write && alu.io.alu_out.rd =/= 0.U &&
        !isLoadLikeOp(aluMemOp)
    def bypassSource(valid: Bool, rd: UInt, data: UInt): FwdSource = {
        val source = Wire(new FwdSource(XLEN))
        source.valid := valid && rd =/= 0.U
        source.rd := rd
        source.data := data
        source
    }
    val decodeBypassSources = Seq(
        bypassSource(wb.io.reg_wb.reg_write, wb.io.reg_wb.rd, wb.io.reg_wb.data),
        bypassSource(lsu.io.mem_out.reg_write, lsu.io.mem_out.rd, lsu.io.mem_out.result),
        bypassSource(aluBypassValid, alu.io.alu_out.rd, alu.io.alu_out.result)
    )
    def decodeBypass(addr: UInt, regData: UInt): UInt = {
        decodeBypassSources.foldLeft(Mux(addr === 0.U, 0.U, regData)) { (data, source) =>
            Mux(addr =/= 0.U && source.valid && addr === source.rd, source.data, data)
        }
    }
    val lsuFwd = bypassSource(lsu.io.mem_out.reg_write, lsu.io.mem_out.rd, lsu.io.mem_out.result)
    val aluFwd = bypassSource(aluResultFwdValid, aluResultFwdRd, aluResultFwdData)
    val prevAluFwd = bypassSource(aluResultPrevValid, aluResultPrevRd, aluResultPrevData)
    val wbFwd = bypassSource(wb.io.reg_wb.reg_write, wb.io.reg_wb.rd, wb.io.reg_wb.data)
    def driveFwdSource(targetValid: Bool, targetRd: UInt, targetData: UInt, source: FwdSource): Unit = {
        targetValid := source.valid
        targetRd := source.rd
        targetData := source.data
    }
    idecode.io.reg_rs1_data  := decodeBypass(idecode.io.reg_rd_rs1, register.io.rs1_data)
    idecode.io.reg_rs2_data  := decodeBypass(idecode.io.reg_rd_rs2, register.io.rs2_data)
    // alu
    alu.io.valid_in       := idecode.io.valid_out && !decodeUsesPending
    alu.io.stall          := pipe_stall || debugHalted
	    alu.io.trap_valid     := pipeline_flush
    alu.io.pc_in          := idecode.io.pc_out
    alu.io.next_pc_in     := idecode.io.pc_in
    alu.io.pred_taken_in  := idecode.io.pred_taken_out
    alu.io.decoded_in     := idecode.io.decoded_out
    alu.io.trap_info_in   := idecode.io.trap_info
    alu.io.csr_illegal    := csr.io.illegal
    alu.io.fwd.load_valid := lsu.io.load_data_valid
	alu.io.fwd.load_data  := lsu.io.load_data
    alu.io.fwd.reg_write  := aluFwd.valid || lsuFwd.valid
    alu.io.fwd.rd         := Mux(aluFwd.valid, aluFwd.rd, lsuFwd.rd)
    alu.io.fwd.alu_result := Mux(aluFwd.valid, aluFwd.data, lsuFwd.data)
    driveFwdSource(alu.io.fwd.wb_reg_write, alu.io.fwd.wb_rd, alu.io.fwd.wb_data, wbFwd)
    driveFwdSource(alu.io.fwd.prev_reg_write, alu.io.fwd.prev_rd, alu.io.fwd.prev_data, prevAluFwd)
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
