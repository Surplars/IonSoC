package soc.memory.cache

import chisel3._
import chisel3.util._
import soc.bus.tilelink._
import soc.memory.CacheReq
import soc.memory.CacheResp

object CacheCmd extends ChiselEnum {
	val Read, Write = Value
}

class L1Cache(val params: TLParams, val nSets: Int = 512) extends Module {
    val io = IO(new Bundle {
        val cpu = new Bundle { // CPU接口
            val req  = Flipped(Decoupled(new CacheReq(params.addrWidth, params.dataWidth)))
            val resp = Decoupled(new CacheResp(params.dataWidth))
        }
        val bus = new TLBundle(params) // 内存接口
    })

    val offsetBits = log2Ceil(params.dataWidth / 8) // 64位=8字节 -> 3位
    val indexBits  = log2Ceil(nSets)              // 512行 -> 9位
    val tagBits    = params.addrWidth - indexBits - offsetBits

    // 分离地址
    def getIdx(addr: UInt) = addr(offsetBits + indexBits - 1, offsetBits)
    def getTag(addr: UInt) = addr(params.addrWidth - 1, offsetBits + indexBits)

    // SRAM 阵列 (简单起见，使用同步读写的 Mem)
    val validArray = RegInit(VecInit(Seq.fill(nSets)(false.B)))
    val dirtyArray = RegInit(VecInit(Seq.fill(nSets)(false.B)))
    val tagArray   = SyncReadMem(nSets, UInt(tagBits.W))
    val dataArray  = SyncReadMem(nSets, UInt(params.dataWidth.W))

    // 状态机
    val sIdle :: sCompare :: sWriteBackReq :: sWriteBackWait :: sRefillReq :: sRefillWait :: sResp :: Nil = Enum(7)
    val state = RegInit(sIdle)

    // 锁存 CPU 请求
    val reqReg   = Reg(new CacheReq(params.addrWidth, params.dataWidth))
    val reqIdx   = getIdx(reqReg.addr)
    val reqTag   = getTag(reqReg.addr)
    val victimTagReg  = Reg(UInt(tagBits.W))
    val victimDataReg = Reg(UInt(params.dataWidth.W))
    
    // 从 SRAM 读出的数据
    val readTag  = tagArray.read(getIdx(io.cpu.req.bits.addr), io.cpu.req.valid && state === sIdle)
    val readData = dataArray.read(getIdx(io.cpu.req.bits.addr), io.cpu.req.valid && state === sIdle)

    // 新增：用于在 Miss 处理后暂存刚拉回来的数据以立刻返回给 CPU
    val refillReg = RegInit(0.U(params.dataWidth.W))

    val hit = validArray(reqIdx) && (readTag === reqTag)
    val dirty = dirtyArray(reqIdx)

    // 接口默认值
    io.cpu.req.ready  := state === sIdle
    io.cpu.resp.valid := false.B
    io.cpu.resp.bits.rdata := Mux(state === sResp, refillReg, readData)

    io.bus.a.valid := false.B
    io.bus.a.bits  := DontCare
    io.bus.d.ready := false.B

    switch(state) {
        is(sIdle) {
            when(io.cpu.req.valid) {
                reqReg := io.cpu.req.bits
                assert(!io.cpu.req.bits.device && io.cpu.req.bits.cacheable, "L1Cache: device/uncached request must bypass cache")
                assert(!io.cpu.req.bits.atomic, "L1Cache: atomic request not supported yet")
                when(io.cpu.req.bits.fence || io.cpu.req.bits.fencei) {
                    refillReg := 0.U
                    state := sResp
                }.otherwise {
                    state := sCompare
                }
            }
        }
        is(sCompare) {
            when(hit) {
                io.cpu.resp.valid := true.B
                // 默认的 io.cpu.resp.bits.rdata 为 readData 也就是当拍被 SRAM 丢出来的命中数据
                when(reqReg.cmd === CacheCmd.Write && io.cpu.resp.ready) {
                    // 写命中: 应用掩码修改现行 Cache 数据
                    val maskedData = (reqReg.wdata & FillInterleaved(8, reqReg.mask)) | (readData & ~FillInterleaved(8, reqReg.mask))
                    dataArray.write(reqIdx, maskedData)
                    dirtyArray(reqIdx) := true.B
                }
                when(io.cpu.resp.ready) {
                    state := sIdle
                }
            }.otherwise {
                when(validArray(reqIdx) && dirty) {
                    victimTagReg  := readTag
                    victimDataReg := readData
                }
                // Miss处理：有脏数据必须先写出，否则直接抓新缓存行
                state := Mux(validArray(reqIdx) && dirty, sWriteBackReq, sRefillReq)
            }
        }
        is(sWriteBackReq) { // 阶段1: 向总线挤出写回请求
            io.bus.a.valid         := true.B
            io.bus.a.bits.opcode   := TLOpcode.PutFullData
            io.bus.a.bits.param    := 0.U
            io.bus.a.bits.size     := offsetBits.U
            io.bus.a.bits.source   := 0.U
            io.bus.a.bits.address  := Cat(victimTagReg, reqIdx, 0.U(offsetBits.W))
            io.bus.a.bits.data     := victimDataReg
            io.bus.a.bits.mask     := ((1 << (params.dataWidth/8)) - 1).U
            io.bus.a.bits.corrupt  := false.B

            when(io.bus.a.fire) {
                state := sWriteBackWait
            }
        }
        is(sWriteBackWait) { // 阶段2: 纯等待内存接收完毕吐出 D 响应
            io.bus.d.ready := true.B
            when(io.bus.d.fire) {
                dirtyArray(reqIdx) := false.B
                state := sRefillReq
            }
        }
        is(sRefillReq) {   // 阶段3: 向总线扔出读新数片请求
            io.bus.a.valid         := true.B
            io.bus.a.bits.opcode   := TLOpcode.Get
            io.bus.a.bits.param    := 0.U
            io.bus.a.bits.size     := offsetBits.U
            io.bus.a.bits.source   := 0.U
            io.bus.a.bits.address  := Cat(reqTag, reqIdx, 0.U(offsetBits.W))
            io.bus.a.bits.mask     := ((1 << (params.dataWidth/8)) - 1).U // TileLink 读请求掩码需为全1
            io.bus.a.bits.data     := 0.U
            io.bus.a.bits.corrupt  := false.B

            when(io.bus.a.fire) {
                state := sRefillWait
            }
        }
        is(sRefillWait) { // 阶段4: 阻塞挂起拿取真实数据返回
            io.bus.d.ready := true.B
            when(io.bus.d.fire) {
                val isWrite = (reqReg.cmd === CacheCmd.Write)
                val fetchedData = io.bus.d.bits.data
                
                // 如果恰好这是个 Write 请求引起的 Miss，则直接在进缓存前覆盖它
                val maskedData = (reqReg.wdata & FillInterleaved(8, reqReg.mask)) | (fetchedData & ~FillInterleaved(8, reqReg.mask))
                val writeToSram = Mux(isWrite, maskedData, fetchedData)
                
                validArray(reqIdx) := true.B
                dirtyArray(reqIdx) := isWrite
                tagArray.write(reqIdx, reqTag)
                dataArray.write(reqIdx, writeToSram)
                
                // 拦截写入寄存器以支持 Cache Bypass 当拍给流水线返回
                refillReg := writeToSram
                state := sResp
            }
        }
        is(sResp) { // 阶段5: Miss补偿响应期
            io.cpu.resp.valid := true.B
            // rdata 在顶层被上面 Mux 接走了 refillReg
            when(io.cpu.resp.ready) {
                state := sIdle
            }
        }
    }
}
