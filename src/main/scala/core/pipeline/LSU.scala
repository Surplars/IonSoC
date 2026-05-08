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

    private def shiftedData(rawData: UInt, access: MemoryAccessInfo): UInt = {
        val shiftBytes = access.paddr(beatOffsetBits - 1, 0)
        val shiftBits  = Cat(shiftBytes, 0.U(3.W))
        (rawData >> shiftBits)(XLEN - 1, 0)
    }

    private def formatAtomicReadData(rawData: UInt, access: MemoryAccessInfo): UInt = {
        val shifted = shiftedData(rawData, access)
        Mux(access.size === 2.U, Cat(Fill(XLEN - 32, shifted(31)), shifted(31, 0)), shifted)
    }

    private def formatAtomicWdata(value: UInt, access: MemoryAccessInfo): UInt = {
        val shiftBits = Cat(access.paddr(beatOffsetBits - 1, 0), 0.U(3.W))
        Mux(access.size === 2.U, Fill(2, value(31, 0)), value) << shiftBits
    }

    private def computeAtomicWrite(oldValue: UInt, access: MemoryAccessInfo): UInt = {
        val srcValue = shiftedData(access.wdata, access)
        val result64 = Wire(UInt(XLEN.W))
        val oldS64 = oldValue.asSInt
        val srcS64 = srcValue.asSInt
        result64 := MuxLookup(access.atomic, srcValue)(
            Seq(
                AtomicOpType.Swap -> srcValue,
                AtomicOpType.Add  -> (oldValue + srcValue),
                AtomicOpType.Xor  -> (oldValue ^ srcValue),
                AtomicOpType.And  -> (oldValue & srcValue),
                AtomicOpType.Or   -> (oldValue | srcValue),
                AtomicOpType.Min  -> Mux(oldS64 < srcS64, oldValue, srcValue),
                AtomicOpType.Max  -> Mux(oldS64 >= srcS64, oldValue, srcValue),
                AtomicOpType.MinU -> Mux(oldValue < srcValue, oldValue, srcValue),
                AtomicOpType.MaxU -> Mux(oldValue >= srcValue, oldValue, srcValue)
            )
        )

        val oldWord = oldValue(31, 0)
        val srcWord = srcValue(31, 0)
        val result32 = Wire(UInt(32.W))
        result32 := MuxLookup(access.atomic, srcWord)(
            Seq(
                AtomicOpType.Swap -> srcWord,
                AtomicOpType.Add  -> (oldWord + srcWord),
                AtomicOpType.Xor  -> (oldWord ^ srcWord),
                AtomicOpType.And  -> (oldWord & srcWord),
                AtomicOpType.Or   -> (oldWord | srcWord),
                AtomicOpType.Min  -> Mux(oldWord.asSInt < srcWord.asSInt, oldWord, srcWord),
                AtomicOpType.Max  -> Mux(oldWord.asSInt >= srcWord.asSInt, oldWord, srcWord),
                AtomicOpType.MinU -> Mux(oldWord < srcWord, oldWord, srcWord),
                AtomicOpType.MaxU -> Mux(oldWord >= srcWord, oldWord, srcWord)
            )
        )

        Mux(access.size === 2.U, Cat(Fill(XLEN - 32, result32(31)), result32), result64)
    }

    val valid_inst  = io.valid_in && !io.trap_valid && !io.trap_info_in.valid
    val accessStage = Module(new MemoryAccessStage(XLEN))
    accessStage.io.in  := io.alu_out.mem
    accessStage.io.cfg := io.mem_cfg

    val memAccess     = accessStage.io.out
    val stageFault    = accessStage.io.fault
    val is_load       = valid_inst && memAccess.op === MemOpType.Load
    val is_store      = valid_inst && memAccess.op === MemOpType.Store
    val is_lr         = valid_inst && memAccess.op === MemOpType.LR
    val is_sc         = valid_inst && memAccess.op === MemOpType.SC
    val is_amo        = valid_inst && memAccess.op === MemOpType.AMO
    val is_atomic     = is_lr || is_sc || is_amo
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
    val mem_addr_exception = valid_inst && memAccess.valid && (is_load || is_store || is_atomic) && misaligned
    val atomic_device_fault = valid_inst && memAccess.valid && is_atomic && is_device
    val access_fault = stageFault.valid

    val trap_info = WireInit(io.trap_info_in)
    when(mem_addr_exception && !io.trap_info_in.valid) {
        trap_info.valid  := true.B
        trap_info.pc     := io.pc_in
        trap_info.cause  := Mux(is_store || is_sc || is_amo, MCause.StoreAddrMisaligned, MCause.LoadAddrMisaligned)
        trap_info.value  := memAccess.vaddr
        trap_info.is_ret := false.B
        trap_info.ret_type := TrapReturnType.None
    }.elsewhen(atomic_device_fault && !io.trap_info_in.valid) {
        trap_info.valid  := true.B
        trap_info.pc     := io.pc_in
        trap_info.cause  := Mux(is_lr, MCause.LoadAccessFault, MCause.StoreAccessFault)
        trap_info.value  := memAccess.vaddr
        trap_info.is_ret := false.B
        trap_info.ret_type := TrapReturnType.None
    }.elsewhen(stageFault.valid && !io.trap_info_in.valid) {
        trap_info.valid  := true.B
        trap_info.pc     := io.pc_in
        trap_info.cause  := stageFault.cause
        trap_info.value  := stageFault.value
        trap_info.is_ret := false.B
        trap_info.ret_type := TrapReturnType.None
    }

    val mmio_err_valid  = RegInit(false.B)
    val mmio_err_pc     = RegInit(0.U(XLEN.W))
    val mmio_err_cause  = RegInit(0.U(XLEN.W))
    val mmio_err_value  = RegInit(0.U(XLEN.W))
    val cache_err_valid = RegInit(false.B)
    val cache_err_pc    = RegInit(0.U(XLEN.W))
    val cache_err_cause = RegInit(MCause.LoadAccessFault)
    val cache_err_value = RegInit(0.U(XLEN.W))
    val store_err_valid = RegInit(false.B)
    val store_err_pc    = RegInit(0.U(XLEN.W))
    val store_err_value = RegInit(0.U(XLEN.W))
    val pending_mem_trap = mmio_err_valid || cache_err_valid || store_err_valid

    val storeBuffer = Module(new StoreBuffer(4, XLEN, XLEN))
    // Multi-cycle memory operations hold the upstream pipeline stage while the
    // request is outstanding. This bit prevents the held instruction from being
    // accepted again on the release cycle.
    val inputConsumed = RegInit(false.B)
    val consumedPc = RegInit(0.U(XLEN.W))
    val consumedOp = RegInit(MemOpType.None)
    val consumedAddr = RegInit(0.U(XLEN.W))
    val consumedAtomic = RegInit(AtomicOpType.None)
    val consumedRd = RegInit(0.U(5.W))
    val sameConsumedInput = inputConsumed &&
        consumedPc === io.pc_in &&
        consumedOp === memAccess.op &&
        consumedAddr === memAccess.paddr &&
        consumedAtomic === memAccess.atomic &&
        consumedRd === io.alu_out.rd

    storeBuffer.io.enq_valid := is_store && !is_device && !mem_addr_exception && !access_fault && !pending_mem_trap && !sameConsumedInput
    storeBuffer.io.enq_pc    := io.pc_in
    storeBuffer.io.enq_vaddr := memAccess.vaddr
    storeBuffer.io.enq_addr  := addr
    storeBuffer.io.enq_data  := wdata_aligned
    storeBuffer.io.enq_mask  := strb
    storeBuffer.io.enq_size  := memAccess.size

    storeBuffer.io.search_addr := Mux(is_load && !is_device && !access_fault, addr, 0.U)
    storeBuffer.io.search_mask := Mux(is_load && !is_device && !access_fault, memAccess.mask, 0.U)

    val load_hit_sb    = is_load && !is_device && !access_fault && storeBuffer.io.search_hit
    val load_miss_sb   = is_load && !is_device && !storeBuffer.io.search_hit
    val sb_has_data    = storeBuffer.io.deq_valid
    val raw_cache_load = is_load && !is_device && !load_hit_sb && !mem_addr_exception && !access_fault && !sameConsumedInput
    val raw_mmio_req   = memAccess.valid && !mem_addr_exception && !access_fault && is_device && (is_load || is_store) && !sameConsumedInput

    val cacheLoadPending = RegInit(false.B)
    val cacheLoadSent    = RegInit(false.B)
    val cacheLoadAccess  = RegInit(0.U.asTypeOf(new MemoryAccessInfo(XLEN)))
    val cacheLoadPc      = RegInit(0.U(XLEN.W))
    val cacheLoadRd      = RegInit(0.U(5.W))
    val cacheLoadRegWrite = RegInit(false.B)

    val mmioPending = RegInit(false.B)
    val mmioSent    = RegInit(false.B)
    val mmioAccess  = RegInit(0.U.asTypeOf(new MemoryAccessInfo(XLEN)))
    val mmioPc      = RegInit(0.U(XLEN.W))
    val mmioRd      = RegInit(0.U(5.W))
    val mmioRegWrite = RegInit(false.B)

    val storeDrainPending = RegInit(false.B)
    val storeDrainPc      = RegInit(0.U(XLEN.W))
    val storeDrainVaddr   = RegInit(0.U(XLEN.W))

    // Single-hart LR/SC reservation. Coherence-aware invalidation can replace this when multi-hart support lands.
    val reservationValid = RegInit(false.B)
    val reservationAddr  = RegInit(0.U(XLEN.W))
    val reservationSize  = RegInit(0.U(3.W))

    // Atomics are serialized here as cache read plus optional cache write; TileLink Atomic opcodes are not used yet.
    val atomicPending = RegInit(false.B)
    val atomicReadSent = RegInit(false.B)
    val atomicWriteSent = RegInit(false.B)
    val atomicDoWrite = RegInit(false.B)
    val atomicAccess = RegInit(0.U.asTypeOf(new MemoryAccessInfo(XLEN)))
    val atomicPc = RegInit(0.U(XLEN.W))
    val atomicRd = RegInit(0.U(5.W))
    val atomicRegWrite = RegInit(false.B)
    val atomicOldData = RegInit(0.U(XLEN.W))
    val atomicWriteData = RegInit(0.U(XLEN.W))
    val atomicRespValid = RegInit(false.B)
    val atomicRespData = RegInit(0.U(XLEN.W))
    val atomicRespErr = RegInit(false.B)

    val raw_atomic_req = memAccess.valid && !mem_addr_exception && !atomic_device_fault && !access_fault && !is_device && is_atomic && !sameConsumedInput

    val new_atomic_req = raw_atomic_req && !atomicPending && !sb_has_data && !storeDrainPending && !cacheLoadPending && !mmioPending && !pending_mem_trap
    val new_cache_load = raw_cache_load && !cacheLoadPending && !atomicPending && !storeDrainPending && !pending_mem_trap
    val new_mmio_req   = raw_mmio_req && !mmioPending && !atomicPending && !pending_mem_trap

    when(!cacheLoadPending && new_cache_load) {
        cacheLoadPending := true.B
        cacheLoadSent    := false.B
        cacheLoadAccess  := memAccess
        cacheLoadPc      := io.pc_in
        cacheLoadRd      := io.alu_out.rd
        cacheLoadRegWrite := io.alu_out.reg_write
        inputConsumed := true.B
        consumedPc := io.pc_in
        consumedOp := memAccess.op
        consumedAddr := memAccess.paddr
        consumedAtomic := memAccess.atomic
        consumedRd := io.alu_out.rd
    }

    when(!mmioPending && new_mmio_req) {
        mmioPending := true.B
        mmioSent    := false.B
        mmioAccess  := memAccess
        mmioPc      := io.pc_in
        mmioRd      := io.alu_out.rd
        mmioRegWrite := io.alu_out.reg_write
        inputConsumed := true.B
        consumedPc := io.pc_in
        consumedOp := memAccess.op
        consumedAddr := memAccess.paddr
        consumedAtomic := memAccess.atomic
        consumedRd := io.alu_out.rd
    }

    when(!atomicPending && new_atomic_req) {
        atomicPending   := true.B
        atomicReadSent  := false.B
        atomicWriteSent := false.B
        atomicDoWrite   := false.B
        atomicAccess    := memAccess
        atomicPc        := io.pc_in
        atomicRd        := io.alu_out.rd
        atomicRegWrite  := io.alu_out.reg_write
        inputConsumed   := true.B
        consumedPc      := io.pc_in
        consumedOp      := memAccess.op
        consumedAddr    := memAccess.paddr
        consumedAtomic  := memAccess.atomic
        consumedRd      := io.alu_out.rd
    }

    val do_cache_load_req = cacheLoadPending && !cacheLoadSent
    val do_mmio_req       = mmioPending && !mmioSent
    val do_atomic_read_req = atomicPending && !atomicReadSent
    val do_atomic_write_req = atomicPending && atomicReadSent && atomicDoWrite && !atomicWriteSent
    val do_store_drain    =
        sb_has_data && !storeDrainPending && !cacheLoadPending && !mmioPending && !atomicPending && !new_cache_load && !new_mmio_req && !new_atomic_req && !pending_mem_trap

    val cacheReqAddr = Mux(
        do_cache_load_req,
        cacheLoadAccess.paddr,
        Mux(do_atomic_read_req || do_atomic_write_req, atomicAccess.paddr, storeBuffer.io.deq_addr)
    )
    val cacheReqVaddr = Mux(
        do_cache_load_req,
        cacheLoadAccess.vaddr,
        Mux(do_atomic_read_req || do_atomic_write_req, atomicAccess.vaddr, storeBuffer.io.deq_addr)
    )
    val cacheReqIsWrite = do_store_drain || do_atomic_write_req

    io.dcache.req.valid          := do_cache_load_req || do_store_drain || do_atomic_read_req || do_atomic_write_req
    io.dcache.req.bits.addr      := cacheReqAddr
    io.dcache.req.bits.vaddr     := cacheReqVaddr
    io.dcache.req.bits.cmd       := Mux(cacheReqIsWrite, CacheCmd.Write, CacheCmd.Read)
    io.dcache.req.bits.wdata     := Mux(do_atomic_write_req, atomicWriteData, storeBuffer.io.deq_data)
    io.dcache.req.bits.mask      := Mux(do_atomic_read_req || do_atomic_write_req, atomicAccess.mask, storeBuffer.io.deq_mask)
    io.dcache.req.bits.size      := Mux(do_cache_load_req, cacheLoadAccess.size, Mux(do_atomic_read_req || do_atomic_write_req, atomicAccess.size, storeBuffer.io.deq_size))
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
    val atomic_read_fire = do_atomic_read_req && io.dcache.req.ready
    val atomic_write_fire = do_atomic_write_req && io.dcache.req.ready
    val mmio_req_fire    = do_mmio_req && io.mmio.req_ready

    // Cache ready 时，才能真实出队 Store 数据
    storeBuffer.io.deq_ready := store_drain_fire

    io.dcache.resp.ready := true.B

    when(cache_load_fire) {
        cacheLoadSent := true.B
    }
    when(store_drain_fire) {
        storeDrainPending := true.B
        storeDrainPc      := storeBuffer.io.deq_pc
        storeDrainVaddr   := storeBuffer.io.deq_vaddr
        reservationValid  := false.B
    }
    when(atomic_read_fire) {
        atomicReadSent := true.B
    }
    when(atomic_write_fire) {
        atomicWriteSent := true.B
        reservationValid := false.B
    }
    when(mmio_req_fire) {
        mmioSent := true.B
    }

    when(cacheLoadPending && cacheLoadSent && io.dcache.resp.valid) {
        cacheLoadPending := false.B
        cacheLoadSent    := false.B
    }
    when(storeDrainPending && io.dcache.resp.valid) {
        storeDrainPending := false.B
    }
    when(atomicPending && atomicReadSent && io.dcache.resp.valid && !atomicDoWrite) {
        val oldValue = formatAtomicReadData(io.dcache.resp.bits.rdata, atomicAccess)
        val reservationHit = reservationValid &&
            reservationAddr === atomicAccess.paddr &&
            reservationSize === atomicAccess.size
        atomicOldData := oldValue
        atomicRespErr := io.dcache.resp.bits.err
        atomicRespValid := true.B
        when(io.dcache.resp.bits.err) {
            atomicRespData := 0.U
            atomicPending := false.B
            atomicReadSent := false.B
        }.elsewhen(atomicAccess.op === MemOpType.LR) {
            reservationValid := true.B
            reservationAddr  := atomicAccess.paddr
            reservationSize  := atomicAccess.size
            atomicRespData   := oldValue
            atomicPending    := false.B
            atomicReadSent   := false.B
        }.elsewhen(atomicAccess.op === MemOpType.SC) {
            when(reservationHit) {
                atomicWriteData := atomicAccess.wdata
                atomicDoWrite := true.B
                atomicRespValid := false.B
            }.otherwise {
                atomicRespData := 1.U
                atomicPending := false.B
                atomicReadSent := false.B
                reservationValid := false.B
            }
        }.otherwise {
            val newValue = computeAtomicWrite(oldValue, atomicAccess)
            atomicWriteData := formatAtomicWdata(newValue, atomicAccess)
            atomicRespData := oldValue
            atomicDoWrite := true.B
            atomicRespValid := false.B
        }
    }
    when(atomicPending && atomicReadSent && atomicDoWrite && atomicWriteSent && io.dcache.resp.valid) {
        atomicRespErr := io.dcache.resp.bits.err
        atomicRespValid := true.B
        when(atomicAccess.op === MemOpType.SC) {
            atomicRespData := Mux(io.dcache.resp.bits.err, 1.U, 0.U)
        }.otherwise {
            atomicRespData := atomicOldData
        }
        atomicPending := false.B
        atomicReadSent := false.B
        atomicWriteSent := false.B
        atomicDoWrite := false.B
    }
    when(mmioPending && mmioSent && io.mmio.resp_valid) {
        mmioPending := false.B
        mmioSent    := false.B
    }

    val completing_cache_load  = cacheLoadPending && cacheLoadSent && io.dcache.resp.valid
    val completing_store_drain = storeDrainPending && io.dcache.resp.valid
    val completing_mmio        = mmioPending && mmioSent && io.mmio.resp_valid

    val stall_sb_full          = is_store && !mem_addr_exception && !access_fault && !storeBuffer.io.enq_ready
    val stall_wait_store_drain = raw_cache_load && storeDrainPending
    val stall_wait_atomic_store_drain = raw_atomic_req && (sb_has_data || storeDrainPending)
    val stall_wait_cache_load  = new_cache_load || cacheLoadPending || stall_wait_store_drain
    val stall_wait_mmio        = new_mmio_req || mmioPending
    val stall_wait_atomic      = new_atomic_req || atomicPending

    io.stall_req := stall_sb_full || stall_wait_cache_load || stall_wait_mmio || stall_wait_atomic || stall_wait_atomic_store_drain
    when(inputConsumed && !io.stall_req) {
        inputConsumed := false.B
    }

    val wb_data        = Wire(UInt(XLEN.W))
    val default_result = io.alu_out.result
    wb_data := 0.U

    val is_load_resp      = completing_cache_load
    val is_mmio_load_resp = completing_mmio && mmioAccess.op === MemOpType.Load
    val is_cache_resp_err = completing_cache_load && io.dcache.resp.bits.err
    val is_store_resp_err = completing_store_drain && io.dcache.resp.bits.err
    val is_mmio_resp_err  = completing_mmio && io.mmio.resp_err
    val is_atomic_resp = atomicRespValid
    val load_data_valid   = load_hit_sb || (is_load_resp && !is_cache_resp_err) || (is_mmio_load_resp && !is_mmio_resp_err) || (is_atomic_resp && !atomicRespErr)
    val load_data         = Mux(
        load_hit_sb,
        formatLoadData(storeBuffer.io.search_data, memAccess),
        Mux(
            is_load_resp,
            formatLoadData(io.dcache.resp.bits.rdata, cacheLoadAccess),
            Mux(is_mmio_load_resp, formatLoadData(io.mmio.resp_data, mmioAccess), Mux(is_atomic_resp, atomicRespData, 0.U))
        )
    )
    val stall_valid      = RegInit(false.B)
    val stall_wb_data    = RegInit(0.U(XLEN.W))
    val stall_load_valid = RegInit(false.B)

    when(is_load_resp && !is_cache_resp_err) {
        wb_data := formatLoadData(io.dcache.resp.bits.rdata, cacheLoadAccess)
    }.elsewhen(is_mmio_load_resp && !is_mmio_resp_err) {
        wb_data := formatLoadData(io.mmio.resp_data, mmioAccess)
    }.elsewhen(is_atomic_resp && !atomicRespErr) {
        wb_data := atomicRespData
    }.elsewhen(load_hit_sb) {
        wb_data := formatLoadData(storeBuffer.io.search_data, memAccess)
    }.elsewhen(!io.stall_req && stall_valid) {
        wb_data := stall_wb_data
    }.otherwise {
        wb_data := default_result
    }

    when(io.stall_req) {
        stall_valid      := true.B
        stall_wb_data    := wb_data
        stall_load_valid := load_data_valid
    }.otherwise {
        stall_valid      := false.B
        stall_wb_data    := 0.U
        stall_load_valid := false.B
    }

    when(is_mmio_resp_err) {
        mmio_err_valid := true.B
        mmio_err_pc    := mmioPc
        mmio_err_cause := Mux(mmioAccess.op === MemOpType.Store, MCause.StoreAccessFault, MCause.LoadAccessFault)
        mmio_err_value := mmioAccess.vaddr
    }.elsewhen(!io.stall_req && mmio_err_valid) {
        mmio_err_valid := false.B
    }

    when(is_cache_resp_err) {
        cache_err_valid := true.B
        cache_err_pc    := cacheLoadPc
        cache_err_cause := MCause.LoadAccessFault
        cache_err_value := cacheLoadAccess.vaddr
    }.elsewhen(!io.stall_req && cache_err_valid) {
        cache_err_valid := false.B
    }

    when(is_store_resp_err) {
        store_err_valid := true.B
        store_err_pc    := storeDrainPc
        store_err_value := storeDrainVaddr
    }.elsewhen(!io.stall_req && store_err_valid) {
        store_err_valid := false.B
    }
    when(atomicRespValid && atomicRespErr) {
        cache_err_valid := true.B
        cache_err_pc    := atomicPc
        cache_err_cause := Mux(atomicAccess.op === MemOpType.LR, MCause.LoadAccessFault, MCause.StoreAccessFault)
        cache_err_value := atomicAccess.vaddr
    }
    when(!atomicPending && atomicRespValid) {
        atomicRespValid := false.B
    }

    val update_en = !io.stall_req
    val writeback_en = update_en || load_data_valid
    val response_rd = Mux(
        is_load_resp,
        cacheLoadRd,
        Mux(is_mmio_load_resp, mmioRd, Mux(is_atomic_resp, atomicRd, io.alu_out.rd))
    )
    val response_reg_write = Mux(
        is_load_resp,
        cacheLoadRegWrite,
        Mux(is_mmio_load_resp, mmioRegWrite, Mux(is_atomic_resp, atomicRegWrite, io.alu_out.reg_write))
    )
    val out_reg_rd    = RegNext(response_rd, 0.U)
    val out_reg_write = RegNext(response_reg_write && writeback_en, false.B)
    val out_result    = RegNext(wb_data, 0.U)
    // val out_result    = RegNext(Mux(load_data_valid, load_data, wb_data), 0.U)
    val final_trap_info = WireInit(trap_info)
    when(cache_err_valid) {
        final_trap_info.valid  := true.B
        final_trap_info.pc     := cache_err_pc
        final_trap_info.cause  := cache_err_cause
        final_trap_info.value  := cache_err_value
        final_trap_info.is_ret := false.B
        final_trap_info.ret_type := TrapReturnType.None
    }.elsewhen(store_err_valid) {
        final_trap_info.valid  := true.B
        final_trap_info.pc     := store_err_pc
        final_trap_info.cause  := MCause.StoreAccessFault
        final_trap_info.value  := store_err_value
        final_trap_info.is_ret := false.B
        final_trap_info.ret_type := TrapReturnType.None
    }.elsewhen(mmio_err_valid) {
        final_trap_info.valid  := true.B
        final_trap_info.pc     := mmio_err_pc
        final_trap_info.cause  := mmio_err_cause
        final_trap_info.value  := mmio_err_value
        final_trap_info.is_ret := false.B
        final_trap_info.ret_type := TrapReturnType.None
    }

    val out_trap      = RegEnable(final_trap_info, 0.U.asTypeOf(io.trap_info_in), update_en)
    val out_pc        = RegEnable(io.pc_in, 0.U, update_en)

    io.valid_out         := RegNext((valid_inst && writeback_en || load_data_valid) && !final_trap_info.valid, false.B)
    io.mem_out.rd        := Mux(load_data_valid, response_rd, out_reg_rd)
    io.mem_out.reg_write := Mux(load_data_valid, response_reg_write, out_reg_write)
    io.mem_out.result    := Mux(load_data_valid, load_data, out_result)
    io.pc_out            := out_pc
    io.trap_info_out     := out_trap

    io.load_data_valid := load_data_valid || (!io.stall_req && stall_valid && stall_load_valid)
    io.load_data       := Mux(!io.stall_req && stall_valid && stall_load_valid, stall_wb_data, load_data)

    dontTouch(io.mem_cfg)
}
