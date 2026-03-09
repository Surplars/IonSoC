package soc.core.pipeline

import chisel3._
import chisel3.util._

class StoreBufferEntry(val AW: Int, val DW: Int) extends Bundle {
    val valid = Bool()
    val addr  = UInt(AW.W)
    val data  = UInt(DW.W)
    val mask  = UInt((DW / 8).W)
}

class StoreBuffer(val entries: Int, val AW: Int, val DW: Int) extends Module {
    val io = IO(new Bundle {
        // Enq (来自 LSU Core 逻辑)
        val enq_valid = Input(Bool())
        val enq_addr  = Input(UInt(AW.W))
        val enq_data  = Input(UInt(DW.W))
        val enq_mask  = Input(UInt((DW / 8).W))
        val enq_ready = Output(Bool())
        // Search (来自 Load 指令 Forwarding)
        val search_addr = Input(UInt(AW.W))
        val search_mask = Input(UInt((DW / 8).W))
        val search_hit  = Output(Bool())
        val search_data = Output(UInt(DW.W))
        // Deq (发往 Memory/Bus)
        val deq_valid = Output(Bool())
        val deq_addr  = Output(UInt(AW.W))
        val deq_data  = Output(UInt(DW.W))
        val deq_mask  = Output(UInt((DW / 8).W))
        val deq_ready = Input(Bool())
    })

    val buffer = RegInit(VecInit(Seq.fill(entries) {
        0.U.asTypeOf(new StoreBufferEntry(AW, DW))
    }))

    val head  = RegInit(0.U(log2Ceil(entries).W)) // 读指针（出队）
    val tail  = RegInit(0.U(log2Ceil(entries).W)) // 写指针（入队）
    val count = RegInit(0.U(log2Ceil(entries + 1).W))

    def ptrNext(ptr: UInt): UInt = Mux(ptr === (entries - 1).U, 0.U, ptr + 1.U)

    io.enq_ready := count < entries.U
    io.deq_valid := count > 0.U

    val enq_fire = io.enq_valid && io.enq_ready
    val deq_fire = io.deq_valid && io.deq_ready

    when(enq_fire) {
        buffer(tail).valid := true.B
        buffer(tail).addr  := io.enq_addr
        buffer(tail).data  := io.enq_data
        buffer(tail).mask  := io.enq_mask
        tail               := ptrNext(tail)
    }

    io.deq_addr  := buffer(head).addr
    io.deq_data  := buffer(head).data
    io.deq_mask  := buffer(head).mask

    when(deq_fire) {
        buffer(head).valid := false.B
        head               := ptrNext(head)
    }

    switch(Cat(enq_fire, deq_fire)) {
        is("b10".U) {
            count := count + 1.U
        }
        is("b01".U) {
            count := count - 1.U
        }
    }

	val hit_vec = Wire(Vec(entries, Bool()))
	val data_vec = Wire(Vec(entries, UInt(DW.W)))

	for (i <- 0 until entries) {
        hit_vec(i) := buffer(i).valid && (buffer(i).addr === io.search_addr) && ((buffer(i).mask & io.search_mask) === io.search_mask)
		data_vec(i) := buffer(i).data
	}
	io.search_hit  := hit_vec.asUInt.orR
	io.search_data := Mux1H(hit_vec, data_vec)
}
