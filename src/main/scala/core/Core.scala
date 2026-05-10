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
import soc.debug.DebugCacheControl
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
    private val hasFrontendQueue = features.frontendQueueEntries > 0
    private val nMasters = 2 + (if (hasICache) 1 else 0) // dcache, tracker, optional icache
    private val dbusParams = tlParams.copy(sourceBits = tlParams.sourceBits + log2Ceil(nMasters))

    val io = IO(new Bundle {
        val instr = Input(UInt(64.W))
        val debug_instr = Output(UInt(32.W))

        val fetch_en = Output(Bool())
        val pc       = Output(UInt(XLEN.W))
        val debug_retire = Output(Bool())
        val debug_stall = Output(Bool())
        val debug_ifetch_stall = Output(Bool())
        val debug_lsu_stall = Output(Bool())
        val debug_lsu_load_stall = Output(Bool())
        val debug_lsu_store_stall = Output(Bool())
        val debug_lsu_mmio_stall = Output(Bool())
        val debug_lsu_atomic_stall = Output(Bool())
        val debug_lsu_fence_stall = Output(Bool())
        val debug_branch_valid = Output(Bool())
        val debug_branch_taken = Output(Bool())
        val debug_branch_redirect = Output(Bool())
        val debug_branch_pred_taken = Output(Bool())
        val debug_branch_pred_correct = Output(Bool())

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
        val debug_cache = Flipped(new DebugCacheControl)
    })

    val dcache: HasCacheCoreIO = if (hasDCache) {
        Module(new L1Cache(tlParams, features.dCacheSets))
    } else {
        Module(new UncachedTileLinkBridge(tlParams))
    }
    val tracker = Module(new TLTransTracker(tlParams, maxInFlight = 1 << tlParams.sourceBits))
    val dbusXbar = Module(new TLSystemXbar(tlParams, nMasters))
    val icache = if (hasICache) Some(Module(new L1Cache(tlParams, features.iCacheSets))) else None

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
    val debugDcachePending = RegInit(false.B)
    val debugDcacheIssued = RegInit(false.B)
    val debugDcacheAck = WireInit(false.B)
    val debugDcacheErr = RegInit(false.B)
    val debugIcachePending = RegInit(false.B)
    val debugIcacheIssued = RegInit(false.B)
    val debugIcacheAck = WireInit(false.B)
    val debugIcacheErr = RegInit(false.B)

    when(io.debug_cache.dcacheReq && !debugDcachePending) {
        debugDcachePending := true.B
        debugDcacheIssued := false.B
        debugDcacheErr := false.B
    }.elsewhen(debugDcacheAck) {
        debugDcachePending := false.B
        debugDcacheIssued := false.B
    }
    when(io.debug_cache.icacheReq && !debugIcachePending) {
        debugIcachePending := true.B
        debugIcacheIssued := false.B
        debugIcacheErr := false.B
    }.elsewhen(debugIcacheAck) {
        debugIcachePending := false.B
        debugIcacheIssued := false.B
    }

    val fenceIPending = RegInit(false.B)
    val fenceIFlushIssued = RegInit(false.B)
    val fenceIStart = fenceIReq && !fenceIPending && !fenceIFlushIssued
    val fenceIInvalidateFire = WireInit(false.B)
    val fenceIAck = WireInit(false.B)

    when(fenceIAck) {
        fenceIPending := false.B
    }.elsewhen(fenceIStart) {
        fenceIPending := true.B
    }
    when(fenceIAck) {
        fenceIFlushIssued := false.B
    }.elsewhen(fenceIInvalidateFire) {
        fenceIFlushIssued := true.B
    }

    // fence.i is a frontend barrier: redirect to the fall-through PC, drain
    // stale fetches, then release only after the I-cache reports completion.
    val fenceIActive = fenceIPending || fenceIStart || fenceIFlushIssued
    val fenceIHold = fenceIActive
    dontTouch(fenceIReq)
    dontTouch(fenceIStart)
    dontTouch(fenceIPending)
    dontTouch(fenceIFlushIssued)
    dontTouch(fenceIInvalidateFire)
    dontTouch(fenceIAck)
    dontTouch(fenceIActive)
    dontTouch(fenceIHold)

    dcache.io.cpu <> lsu.io.dcache
    val debugDcacheInvalidateValid = debugDcachePending && !debugDcacheIssued
    dcache.io.invalidate.valid := hasDCache.B && debugDcacheInvalidateValid
    dcache.io.invalidate.bits := false.B
    when(debugDcacheInvalidateValid && dcache.io.invalidate.fire) {
        debugDcacheIssued := true.B
    }
    when(debugDcachePending && debugDcacheIssued && dcache.io.cpu.resp.fire) {
        debugDcacheErr := dcache.io.cpu.resp.bits.err
        debugDcacheAck := true.B
    }
    when(!hasDCache.B && debugDcachePending) {
        debugDcacheErr := false.B
        debugDcacheAck := true.B
    }
    lsu.io.mmio <> tracker.io.master
    dbusXbar.io.masters(0) <> dcache.io.bus
    dbusXbar.io.masters(1) <> tracker.io.tl

    if (hasICache) {
        val cache = icache.get
        dbusXbar.io.masters(2) <> cache.io.bus
        // Debug-driven I-cache maintenance shares the cache CPU port with IF.
        // Let normal fence.i and any already-issued fetch response drain before
        // taking ownership, otherwise a debug request can hide the response that
        // would complete a frontend/cache maintenance transaction.
        val debugIcacheUsesPort = debugIcachePending && !fenceIActive && !ifetch.io.fetch_stall
        val debugIcacheReqValid = debugIcacheUsesPort && !debugIcacheIssued
        val debugIcacheRespReady = debugIcacheUsesPort && debugIcacheIssued
        val debugIcacheReqBits = WireInit(ifetch.io.cache.req.bits)
        debugIcacheReqBits.addr := 0.U
        debugIcacheReqBits.vaddr := 0.U
        debugIcacheReqBits.cmd := soc.memory.cache.CacheCmd.Read
        debugIcacheReqBits.wdata := 0.U
        debugIcacheReqBits.mask := 0.U
        debugIcacheReqBits.size := 0.U
        debugIcacheReqBits.signed := false.B
        debugIcacheReqBits.fence := false.B
        debugIcacheReqBits.fencei := true.B
        debugIcacheReqBits.atomic := false.B
        debugIcacheReqBits.cacheable := true.B
        debugIcacheReqBits.device := false.B
        cache.io.cpu.req.valid := Mux(debugIcacheUsesPort, debugIcacheReqValid, ifetch.io.cache.req.valid)
        cache.io.cpu.req.bits := Mux(debugIcacheUsesPort, debugIcacheReqBits, ifetch.io.cache.req.bits)
        ifetch.io.cache.req.ready := !debugIcacheUsesPort && cache.io.cpu.req.ready
        ifetch.io.cache.resp.valid := !debugIcacheUsesPort && cache.io.cpu.resp.valid
        ifetch.io.cache.resp.bits := cache.io.cpu.resp.bits
        cache.io.cpu.resp.ready := Mux(debugIcacheUsesPort, debugIcacheRespReady, ifetch.io.cache.resp.ready)
        cache.io.invalidate.valid := (fenceIStart || fenceIPending) && !fenceIFlushIssued
        cache.io.invalidate.bits := true.B
        fenceIInvalidateFire := cache.io.invalidate.fire
        fenceIAck := fenceIFlushIssued && cache.io.cpu.resp.fire
        when(debugIcacheReqValid && cache.io.cpu.req.fire) {
            debugIcacheIssued := true.B
        }
        when(debugIcacheRespReady && cache.io.cpu.resp.fire) {
            debugIcacheErr := cache.io.cpu.resp.bits.err
            debugIcacheAck := true.B
        }
    } else {
        ifetch.io.cache.req.ready := false.B
        ifetch.io.cache.resp.valid := false.B
        ifetch.io.cache.resp.bits.rdata := 0.U
        ifetch.io.cache.resp.bits.err := false.B
        fenceIAck := fenceIPending || fenceIStart
        when(debugIcachePending) {
            debugIcacheAck := true.B
            debugIcacheErr := false.B
        }
    }

    io.debug_cache.dcacheAck := debugDcacheAck
    io.debug_cache.icacheAck := debugIcacheAck
    io.debug_cache.dcacheBusy := debugDcachePending
    io.debug_cache.icacheBusy := debugIcachePending
    io.debug_cache.dcacheErr := debugDcacheErr
    io.debug_cache.icacheErr := debugIcacheErr

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

    val debugCacheHold = debugDcachePending || debugIcachePending
    val frontend_flush    = Wire(Bool())
    val ifetchQueueReady  = Wire(Bool())
    val decodeInputValid  = Wire(Bool())
    val frontendQueueFlush = Wire(Bool())
    val frontendStarved   = !decodeInputValid && ifetch.io.fetch_stall
    global_stall := pipe_stall || decodeUsesPending || frontendStarved || fenceIHold || debugHalted || debugCacheHold

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
	    val redirect_flush    = combined_trap || ret_redirect
        frontend_flush         := redirect_flush || fenceIActive || debugIcachePending
        val pipeline_flush    = frontend_flush

    // pc
    io.pc            := pc.io.pc_out
    io.fetch_en      := pc.io.fetch_en
    pc.io.stall      := ifetch.io.fetch_stall || !ifetchQueueReady || fenceIHold || debugHalted || debugCacheHold
	    pc.io.trap_valid := combined_trap
    pc.io.trap_pc    := Mux(has_pipeline_trap, csr.io.tvec_out, interruptTarget)
    pc.io.trap_ret   := ret_redirect
    pc.io.trap_epc   := csr.io.epc_out
    pc.io.instr_len  := ifetch.io.pc_step_len
    val pcBrInfo = WireInit(alu.io.br_info)
    when(fenceIStart) {
        val fenceStep = Mux(alu.io.instr_len_out === 2.U, 2.U(XLEN.W), 4.U(XLEN.W))
        pcBrInfo.valid := false.B
        pcBrInfo.is_branch := false.B
        pcBrInfo.taken := true.B
        pcBrInfo.target := alu.io.pc_out + fenceStep
        pcBrInfo.redirect := true.B
    }
    pc.io.br_info <> pcBrInfo
    frontendQueueFlush := frontend_flush || pc.io.redirect
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
    io.debug_retire := wb.io.valid_in && !wb.io.trap_info.valid
    io.debug_stall := global_stall
    io.debug_ifetch_stall := ifetch.io.fetch_stall
    io.debug_lsu_stall := lsu.io.stall_req
    io.debug_lsu_load_stall := lsu.io.stall_load
    io.debug_lsu_store_stall := lsu.io.stall_store
    io.debug_lsu_mmio_stall := lsu.io.stall_mmio
    io.debug_lsu_atomic_stall := lsu.io.stall_atomic
    io.debug_lsu_fence_stall := lsu.io.stall_fence
    io.debug_branch_valid := alu.io.br_info.valid
    io.debug_branch_taken := alu.io.br_info.taken
    io.debug_branch_redirect := alu.io.br_info.redirect
    io.debug_branch_pred_taken := alu.io.br_info.valid && alu.io.pred_taken_in
    io.debug_branch_pred_correct := alu.io.br_info.valid && alu.io.pred_taken_in && alu.io.br_info.taken && !alu.io.br_info.redirect
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
    csr.io.perf.retire := io.debug_retire
    csr.io.perf.globalStall := global_stall
    csr.io.perf.ifetchStall := ifetch.io.fetch_stall
    csr.io.perf.lsuStall := lsu.io.stall_req
    csr.io.perf.lsuLoadStall := lsu.io.stall_load
    csr.io.perf.lsuStoreStall := lsu.io.stall_store
    csr.io.perf.lsuMmioStall := lsu.io.stall_mmio
    csr.io.perf.lsuAtomicStall := lsu.io.stall_atomic
    csr.io.perf.lsuFenceStall := lsu.io.stall_fence
    csr.io.perf.branch := alu.io.br_info.valid
    csr.io.perf.branchTaken := alu.io.br_info.valid && alu.io.br_info.taken
    csr.io.perf.branchRedirect := alu.io.br_info.valid && alu.io.br_info.redirect
    csr.io.perf.branchPredTaken := alu.io.br_info.valid && alu.io.pred_taken_in
    csr.io.perf.branchPredCorrect := alu.io.br_info.valid && alu.io.pred_taken_in && alu.io.br_info.taken && !alu.io.br_info.redirect
    // ifetch
    ifetch.io.stall         := !ifetchQueueReady || debugDcachePending || (debugHalted && !debugIcachePending)
    ifetch.io.pc            := pc.io.pc_out
    ifetch.io.instr_in      := io.instr
    ifetch.io.pred_taken_in := pc.io.pred_taken
    ifetch.io.pred_target_in := pc.io.pred_target
    ifetch.io.redirect      := pc.io.redirect
    ifetch.io.trap_valid    := frontend_flush
    io.debug_instr          := ifetch.io.instr_out

    val fetchEntry = Wire(new FrontendQueueEntry(XLEN))
    fetchEntry.pc := ifetch.io.pc_out
    fetchEntry.instr := ifetch.io.instr_out
    fetchEntry.instrLen := ifetch.io.instr_len
    fetchEntry.predTaken := ifetch.io.pred_taken_out
    fetchEntry.predTarget := ifetch.io.pred_target_out

    val decodeEntry = Wire(new FrontendQueueEntry(XLEN))
    if (hasFrontendQueue) {
        val frontendQueue = Module(new FrontendQueue(XLEN, features.frontendQueueEntries))
        frontendQueue.io.flush := frontendQueueFlush
        frontendQueue.io.enq.valid := ifetch.io.valid && !frontendQueueFlush
        frontendQueue.io.enq.bits := fetchEntry
        frontendQueue.io.deq.ready := !frontendQueueFlush && !(pipe_stall || decodeUsesPending || debugHalted)
        ifetchQueueReady := frontendQueue.io.enq.ready
        decodeInputValid := frontendQueue.io.deq.valid
        decodeEntry := frontendQueue.io.deq.bits
    } else {
        ifetchQueueReady := !(pipe_stall || decodeUsesPending || debugDcachePending || (debugHalted && !debugIcachePending))
        decodeInputValid := ifetch.io.valid
        decodeEntry := fetchEntry
    }

    // idcode
    idecode.io.valid_in      := decodeInputValid
    idecode.io.stall         := pipe_stall || decodeUsesPending || debugHalted
	    idecode.io.trap_valid    := frontend_flush
    idecode.io.redirect      := ifetch.io.redirect
    idecode.io.pc_in         := decodeEntry.pc
    idecode.io.instr_in      := decodeEntry.instr
    idecode.io.instr_len_in  := decodeEntry.instrLen
    idecode.io.priv          := csr.io.mem_cfg_out.priv
    idecode.io.pred_taken_in := decodeEntry.predTaken
    idecode.io.pred_target_in := decodeEntry.predTarget
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
	    alu.io.trap_valid     := redirect_flush
    alu.io.pc_in          := idecode.io.pc_out
    alu.io.next_pc_in     := idecode.io.pc_in
    alu.io.pred_taken_in  := idecode.io.pred_taken_out
    alu.io.pred_target_in := idecode.io.pred_target_out
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
