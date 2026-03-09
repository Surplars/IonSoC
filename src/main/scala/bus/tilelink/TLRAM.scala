package soc.bus.tilelink

import chisel3._
import chisel3.util._

class TLRAM(params: TLParams, sizeBytes: Int) extends Module {
    val io = IO(new Bundle {
        val tl = Flipped(new TLBundle(params))
    })

    require(params.dataWidth % 8 == 0, "dataWidth must be byte-aligned")

    private val beatBytes  = params.dataWidth / 8
    private val offsetBits = log2Ceil(beatBytes)

    require(sizeBytes % beatBytes == 0, s"sizeBytes must be multiple of $beatBytes bytes")

    val depth = sizeBytes / beatBytes
    val mem   = SyncReadMem(depth, Vec(beatBytes, UInt(8.W)))

    val req_queue = Queue(io.tl.a, 2)

    val addr_idx   = req_queue.bits.address >> offsetBits.U
    val byteOffset = req_queue.bits.address(offsetBits - 1, 0)
    val wdata_vec  = VecInit(Seq.tabulate(beatBytes)(i => req_queue.bits.data(8 * i + 7, 8 * i)))
    val mask_vec   = VecInit(Seq.tabulate(beatBytes)(i => req_queue.bits.mask(i)))
    val is_write   = req_queue.bits.opcode === TLOpcode.PutFullData || req_queue.bits.opcode === TLOpcode.PutPartialData
    val is_read    = req_queue.bits.opcode === TLOpcode.Get
    val is_legal   = is_read || is_write

    val req_valid      = RegInit(false.B)
    val req_is_read    = RegInit(false.B)
    val req_size       = RegInit(0.U(params.sizeBits.W))
    val req_source     = RegInit(0.U(params.sourceBits.W))
    val req_byteOffset = RegInit(0.U(offsetBits.W))
    val req_denied     = RegInit(false.B)

    val resp_valid   = RegInit(false.B)
    val resp_opcode  = RegInit(TLOpcode.AccessAck)
    val resp_size    = RegInit(0.U(params.sizeBits.W))
    val resp_source  = RegInit(0.U(params.sourceBits.W))
    val resp_denied  = RegInit(false.B)
    val resp_data    = RegInit(0.U(params.dataWidth.W))

    val can_accept = !req_valid && !resp_valid
    req_queue.ready := can_accept

    val req_fire = req_queue.valid && can_accept
    val mem_rdata = mem.read(addr_idx, req_fire && is_read)

    when(req_fire && is_write) {
        mem.write(addr_idx, wdata_vec, mask_vec)
    }

    when(req_fire) {
        req_valid      := true.B
        req_is_read    := is_read
        req_size       := req_queue.bits.size
        req_source     := req_queue.bits.source
        req_byteOffset := byteOffset
        req_denied     := !is_legal
    }

    val read_shift = req_byteOffset << 3
    val shifted_rdata = (mem_rdata.asUInt >> read_shift)(params.dataWidth - 1, 0)
    val resp_byte_count = (1.U((params.sizeBits + 1).W) << req_size)
    val resp_bit_count  = resp_byte_count << 3
    val full_mask       = Fill(params.dataWidth, 1.U(1.W))
    val read_mask       = full_mask >> (params.dataWidth.U - resp_bit_count)

    when(req_valid && !resp_valid) {
        resp_valid  := true.B
        resp_opcode := Mux(req_is_read, TLOpcode.AccessAckData, TLOpcode.AccessAck)
        resp_size   := req_size
        resp_source := req_source
        resp_denied := req_denied
        resp_data   := Mux(req_is_read && !req_denied, shifted_rdata & read_mask, 0.U)
        req_valid   := false.B
    }

    io.tl.d.valid        := resp_valid
    io.tl.d.bits.opcode  := resp_opcode
    io.tl.d.bits.param   := 0.U
    io.tl.d.bits.size    := resp_size
    io.tl.d.bits.source  := resp_source
    io.tl.d.bits.sink    := 0.U
    io.tl.d.bits.denied  := resp_denied
    io.tl.d.bits.data    := resp_data
    io.tl.d.bits.corrupt := false.B

    when(io.tl.d.fire) {
        resp_valid := false.B
    }
}
