package soc.device

import chisel3._
import chisel3.util._
import soc.bus.AXI4Slave
import soc.config.Config._
import soc.bus.AXI4LiteSlave

class AXI4LiteSram(depth: Int, dataBytes: Int = 4) extends Module {
    require(isPow2(depth), "depth must be power of two")
    val dataWidth = dataBytes * 8
    val addrBits  = log2Ceil(depth)

    val io = IO(new Bundle {
        val axi = new AXI4LiteSlave(AXI_ADDR_WIDTH, AXI_DATA_WIDTH)
    })

    val axi     = io.axi

    val mem = SyncReadMem(depth, Vec(dataBytes, UInt(8.W)))

    // Write path (AXI4-Lite single beat)
    val awAddrReg  = RegInit(0.U(32.W))
    val awValidReg = RegInit(false.B)

    axi.awready := !awValidReg
    when(axi.awvalid && axi.awready) {
        awAddrReg  := axi.awaddr
        awValidReg := true.B
    }

    // accept W only after AW accepted
    axi.wready := awValidReg
    val bvalid = RegInit(false.B)
    val bresp  = RegInit(0.U(2.W))
    axi.bvalid := bvalid
    axi.bresp  := bresp

    when(axi.wvalid && axi.wready) {
        // write with mask
        val wordAddr = awAddrReg >> log2Ceil(dataBytes)
        val bytes    = Wire(Vec(dataBytes, UInt(8.W)))
        for (i <- 0 until dataBytes) {
            val hi = 8 * (i + 1) - 1
            val lo = 8 * i
            bytes(i) := axi.wdata(hi, lo)
        }
        val mask = Wire(Vec(dataBytes, Bool()))
        for (i <- 0 until dataBytes) mask(i) := axi.wstrb(i)
        mem.write(wordAddr(addrBits - 1, 0), bytes, mask)
        // respond OKAY
        bvalid     := true.B
        bresp      := 0.U
        awValidReg := false.B
    }

    when(axi.bready && axi.bvalid) {
        bvalid := false.B
    }

    // Read path (single beat)
    val arAddrReg  = RegInit(0.U(32.W))
    val arValidReg = RegInit(false.B)

    axi.arready := !arValidReg
    when(axi.arvalid && axi.arready) {
        arAddrReg  := axi.araddr
        arValidReg := true.B
    }

    val readDataReg = Reg(UInt(dataWidth.W))
    val rvalid      = RegInit(false.B)
    val rresp       = RegInit(0.U(2.W))
    val rlastReg    = RegInit(true.B)

    // issue read when arValid set and rslot free
    when(arValidReg && !rvalid) {
        val wordAddr = arAddrReg >> log2Ceil(dataBytes)
        val dout     = mem.read(wordAddr(addrBits - 1, 0))
        readDataReg := dout.asUInt
        rvalid      := true.B
        rresp       := 0.U
        rlastReg    := true.B
        arValidReg  := false.B
    }

    axi.rvalid := rvalid
    axi.rdata  := readDataReg
    axi.rresp  := rresp

    when(axi.rvalid && axi.rready) {
        rvalid := false.B
    }
}
