package soc.core.pipeline

import chisel3._
import chisel3.util._

import soc.core.pipeline.ALUOut
import soc.bus.tilelink.TLParams
import soc.bus.tilelink.TLOpcode
import soc.isa.Funct3
import soc.isa.MCause
import soc.memory.CacheReq
import soc.memory.CacheResp
import soc.memory.MmioMaster
import soc.memory.cache.CacheCmd

class LSU(XLEN: Int = 64) extends Module {
    val io = IO(new Bundle {
        val pc_in        = Input(UInt(XLEN.W))
        val valid_in     = Input(Bool())
        val trap_valid   = Input(Bool())
        val alu_out      = Input(new ALUOut(XLEN))
        val trap_info_in = Input(new TrapInfo(XLEN))
        val mem_cfg      = Input(new MemorySystemConfig(XLEN))

        val dcache = new Bundle {
            val req  = Decoupled(new CacheReq(XLEN, XLEN))
            val resp = Flipped(Decoupled(new CacheResp(XLEN)))
        }
        val mmio = new MmioMaster(TLParams())

        val pc_out        = Output(UInt(XLEN.W))
        val valid_out     = Output(Bool())
        val mem_out       = Output(new MemOut(XLEN))
        val trap_info_out = Output(new TrapInfo(XLEN))

        val stall_req       = Output(Bool())
        val load_data_valid = Output(Bool())
        val load_data       = Output(UInt(XLEN.W))
    })

    private val beatOffsetBits = log2Ceil(XLEN / 8)

    private def formatLoadData(rawData: UInt, access: MemoryAccessInfo): UInt = {
        val shiftBytes = access.paddr(beatOffsetBits - 1, 0)
        val shiftBits  = Cat(shiftBytes, 0.U(3.W))
        val shifted    = (rawData >> shiftBits)(XLEN - 1, 0)
        val byteData   = shifted(7, 0)
        val halfData   = shifted(15, 0)
        val wordData   = shifted(31, 0)

        MuxLookup(access.size, shifted)(
            Seq(
                0.U -> Mux(access.signed, Cat(Fill(XLEN - 8, byteData(7)), byteData), Cat(0.U((XLEN - 8).W), byteData)),
                1.U -> Mux(access.signed, Cat(Fill(XLEN - 16, halfData(15)), halfData), Cat(0.U((XLEN - 16).W), halfData)),
                2.U -> Mux(access.signed, Cat(Fill(XLEN - 32, wordData(31)), wordData), Cat(0.U((XLEN - 32).W), wordData)),
                3.U -> shifted
            )
        )
    }

    val valid_inst  = io.valid_in && !io.trap_valid && !io.trap_info_in.valid
    val accessStage = Module(new MemoryAccessStage(XLEN))
    accessStage.io.in  := io.alu_out.mem
    accessStage.io.cfg := io.mem_cfg

    val memAccess     = accessStage.io.out
    val stageFault    = accessStage.io.fault
    val is_load       = valid_inst && memAccess.op === MemOpType.Load
    val is_store      = valid_inst && memAccess.op === MemOpType.Store
    val is_device     = memAccess.attrs.device
    val addr          = memAccess.paddr
    val strb          = memAccess.mask
    val wdata_aligned = memAccess.wdata

    val misaligned = MuxLookup(memAccess.size, false.B)(
        Seq(
            0.U -> false.B,
            1.U -> (addr(0) =/= 0.U(1.W)),
            2.U -> (addr(1, 0) =/= 0.U(2.W)),
            3.U -> (addr(2, 0) =/= 0.U(3.W))
        )
    )
    val mem_addr_exception = valid_inst && memAccess.valid && (is_load || is_store) && misaligned

    val trap_info = WireInit(io.trap_info_in)
    when(mem_addr_exception && !io.trap_info_in.valid) {
        trap_info.valid  := true.B
        trap_info.pc     := io.pc_in
        trap_info.cause  := Mux(is_store, MCause.StoreAddrMisaligned, MCause.LoadAddrMisaligned)
        trap_info.value  := memAccess.vaddr
        trap_info.is_ret := false.B
    }.elsewhen(stageFault.valid && !io.trap_info_in.valid) {
        trap_info.valid  := true.B
        trap_info.pc     := io.pc_in
        trap_info.cause  := stageFault.cause
        trap_info.value  := stageFault.value
        trap_info.is_ret := false.B
    }

    val storeBuffer = Module(new StoreBuffer(4, XLEN, XLEN))

    storeBuffer.io.enq_valid := is_store && !is_device && !mem_addr_exception
    storeBuffer.io.enq_addr  := addr
    storeBuffer.io.enq_data  := wdata_aligned
    storeBuffer.io.enq_mask  := strb

    storeBuffer.io.search_addr := Mux(is_load && !is_device, addr, 0.U)
    storeBuffer.io.search_mask := Mux(is_load && !is_device, memAccess.mask, 0.U)

    val load_hit_sb    = is_load && !is_device && storeBuffer.io.search_hit
    val load_miss_sb   = is_load && !is_device && !storeBuffer.io.search_hit
    val sb_has_data    = storeBuffer.io.deq_valid
    val new_cache_load = is_load && !is_device && !load_hit_sb && !mem_addr_exception
    val new_mmio_req   = memAccess.valid && !mem_addr_exception && is_device && (is_load || is_store)

    val cacheLoadPending = RegInit(false.B)
    val cacheLoadSent    = RegInit(false.B)
    val cacheLoadAccess  = RegInit(0.U.asTypeOf(new MemoryAccessInfo(XLEN)))

    val mmioPending = RegInit(false.B)
    val mmioSent    = RegInit(false.B)
    val mmioAccess  = RegInit(0.U.asTypeOf(new MemoryAccessInfo(XLEN)))

    when(!cacheLoadPending && new_cache_load) {
        cacheLoadPending := true.B
        cacheLoadSent    := false.B
        cacheLoadAccess  := memAccess
    }

    when(!mmioPending && new_mmio_req) {
        mmioPending := true.B
        mmioSent    := false.B
        mmioAccess  := memAccess
    }

    val do_cache_load_req = cacheLoadPending && !cacheLoadSent
    val do_mmio_req       = mmioPending && !mmioSent
    val do_store_drain    = sb_has_data && !cacheLoadPending && !mmioPending && !new_cache_load && !new_mmio_req

    io.dcache.req.valid          := do_cache_load_req || do_store_drain
    io.dcache.req.bits.addr      := Mux(do_cache_load_req, cacheLoadAccess.paddr, storeBuffer.io.deq_addr)
    io.dcache.req.bits.vaddr     := Mux(do_cache_load_req, cacheLoadAccess.vaddr, storeBuffer.io.deq_addr)
    io.dcache.req.bits.cmd       := Mux(do_cache_load_req, CacheCmd.Read, CacheCmd.Write)
    io.dcache.req.bits.wdata     := storeBuffer.io.deq_data
    io.dcache.req.bits.mask      := storeBuffer.io.deq_mask
    io.dcache.req.bits.size      := Mux(do_cache_load_req, cacheLoadAccess.size, 3.U)
    io.dcache.req.bits.signed    := Mux(do_cache_load_req, cacheLoadAccess.signed, false.B)
    io.dcache.req.bits.fence     := false.B
    io.dcache.req.bits.fencei    := false.B
    io.dcache.req.bits.atomic    := false.B
    io.dcache.req.bits.cacheable := true.B
    io.dcache.req.bits.device    := false.B

    io.mmio.req_valid := do_mmio_req
    io.mmio.req_addr  := mmioAccess.paddr
    io.mmio.req_cmd   := Mux(mmioAccess.op === MemOpType.Load, TLOpcode.Get, TLOpcode.PutPartialData)
    io.mmio.req_data  := mmioAccess.wdata
    io.mmio.req_mask  := mmioAccess.mask
    io.mmio.req_size  := mmioAccess.size

    val cache_load_fire  = do_cache_load_req && io.dcache.req.ready
    val store_drain_fire = do_store_drain && io.dcache.req.ready
    val mmio_req_fire    = do_mmio_req && io.mmio.req_ready

    // Cache ready 时，才能真实出队 Store 数据
    storeBuffer.io.deq_ready := store_drain_fire

    io.dcache.resp.ready := true.B

    when(cache_load_fire) {
        cacheLoadSent := true.B
    }
    when(mmio_req_fire) {
        mmioSent := true.B
    }

    when(cacheLoadPending && cacheLoadSent && io.dcache.resp.valid) {
        cacheLoadPending := false.B
        cacheLoadSent    := false.B
    }
    when(mmioPending && mmioSent && io.mmio.resp_valid) {
        mmioPending := false.B
        mmioSent    := false.B
    }

    val stall_sb_full         = is_store && !mem_addr_exception && !storeBuffer.io.enq_ready
    val stall_wait_cache_load = new_cache_load || cacheLoadPending
    val stall_wait_mmio       = new_mmio_req || mmioPending

    io.stall_req := stall_sb_full || stall_wait_cache_load || stall_wait_mmio

    val wb_data        = Wire(UInt(XLEN.W))
    val default_result = io.alu_out.result
    wb_data := 0.U

    val is_load_resp      = cacheLoadPending && cacheLoadSent && io.dcache.resp.valid
    val is_mmio_load_resp = mmioPending && mmioSent && mmioAccess.op === MemOpType.Load && io.mmio.resp_valid
    val load_data_valid   = load_hit_sb || is_load_resp || is_mmio_load_resp
    val load_data         = Mux(
        load_hit_sb,
        formatLoadData(storeBuffer.io.search_data, memAccess),
        Mux(is_load_resp, formatLoadData(io.dcache.resp.bits.rdata, cacheLoadAccess), Mux(is_mmio_load_resp, formatLoadData(io.mmio.resp_data, mmioAccess), 0.U))
    )
    val stall_valid   = RegInit(false.B)
    val stall_wb_data = RegInit(0.U(XLEN.W))

    when(is_load_resp) {
        wb_data := formatLoadData(io.dcache.resp.bits.rdata, cacheLoadAccess)
    }.elsewhen(is_mmio_load_resp) {
        wb_data := formatLoadData(io.mmio.resp_data, mmioAccess)
    }.elsewhen(load_hit_sb) {
        wb_data := formatLoadData(storeBuffer.io.search_data, memAccess)
    }.elsewhen(!io.stall_req && stall_valid) {
        wb_data := stall_wb_data
    }.otherwise {
        wb_data := default_result
    }

    when(io.stall_req) {
        stall_valid   := true.B
        stall_wb_data := wb_data
    }.otherwise {
        stall_valid   := false.B
        stall_wb_data := 0.U
    }

    // 我们的 stall_req 已经把恢复放行包含进去了，这里仅用 !io.stall_req 即可
    val update_en = !io.stall_req
    val out_reg_rd    = RegNext(io.alu_out.rd, 0.U)
    val out_reg_write = RegNext(io.alu_out.reg_write && !io.stall_req, false.B)
    val out_result    = RegNext(wb_data, 0.U)
    // val out_result    = RegNext(Mux(load_data_valid, load_data, wb_data), 0.U)
    val out_trap      = RegEnable(trap_info, 0.U.asTypeOf(io.trap_info_in), update_en)
    val out_pc        = RegEnable(io.pc_in, 0.U, update_en)

    io.valid_out         := RegNext(valid_inst && !trap_info.valid && update_en, false.B)
    io.mem_out.rd        := out_reg_rd
    io.mem_out.reg_write := out_reg_write
    io.mem_out.result    := out_result
    io.pc_out            := out_pc
    io.trap_info_out     := out_trap

    io.load_data_valid := load_data_valid
    io.load_data       := load_data

    dontTouch(io.mem_cfg)
}
