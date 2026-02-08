package soc.bus

import chisel3._

class AXI4Master(AW: Int, DW: Int) extends Bundle {
    val aresetn = Input(Bool())
    // Write address channel signals
    val awid    = Output(UInt(4.W))
    val awaddr  = Output(UInt(AW.W))
    val awlen   = Output(UInt(4.W))
    val awsize  = Output(UInt(3.W))
    val awburst = Output(UInt(2.W))
    val awvalid = Output(Bool())
    val awready = Input(Bool())
    // Write data channel signals
    val wid    = Output(UInt(4.W))
    val wdata  = Output(UInt(DW.W))
    val wstrb  = Output(UInt((DW / 8).W))
    val wlast  = Output(Bool())
    val wvalid = Output(Bool())
    val wready = Input(Bool())
    // Write response channel signals
    val bid    = Input(UInt(4.W))
    val bresp  = Input(UInt(2.W))
    val bvalid = Input(Bool())
    val bready = Output(Bool())
    // Read address channel signals
    val arid    = Output(UInt(4.W))
    val araddr  = Output(UInt(AW.W))
    val arlen   = Output(UInt(4.W))
    val arsize  = Output(UInt(3.W))
    val arburst = Output(UInt(2.W))
    val arvalid = Output(Bool())
    val arready = Input(Bool())
    // Read data channel signals
    val rid    = Input(UInt(4.W))
    val rdata  = Input(UInt(DW.W))
    val rresp  = Input(UInt(2.W))
    val rlast  = Input(Bool())
    val rvalid = Input(Bool())
    val rready = Output(Bool())
}

class AXI4Slave(AW: Int, DW: Int) extends Bundle {
    val aresetn = Input(Bool())
    // Write address channel signals
    val awid    = Input(UInt(4.W))
    val awaddr  = Input(UInt(AW.W))
    val awlen   = Input(UInt(4.W))
    val awsize  = Input(UInt(3.W))
    val awburst = Input(UInt(2.W))
    val awvalid = Input(Bool())
    val awready = Output(Bool())
    // Write data channel signals
    val wid    = Input(UInt(4.W))
    val wdata  = Input(UInt(DW.W))
    val wstrb  = Input(UInt((DW / 8).W))
    val wlast  = Input(Bool())
    val wvalid = Input(Bool())
    val wready = Output(Bool())
    // Write response channel signals
    val bid    = Output(UInt(4.W))
    val bresp  = Output(UInt(2.W))
    val bvalid = Output(Bool())
    val bready = Input(Bool())
    // Read address channel signals
    val arid    = Input(UInt(4.W))
    val araddr  = Input(UInt(AW.W))
    val arlen   = Input(UInt(4.W))
    val arsize  = Input(UInt(3.W))
    val arburst = Input(UInt(2.W))
    val arvalid = Input(Bool())
    val arready = Output(Bool())
    // Read data channel signals
    val rid    = Output(UInt(4.W))
    val rdata  = Output(UInt(DW.W))
    val rresp  = Output(UInt(2.W))
    val rlast  = Output(Bool())
    val rvalid = Output(Bool())
    val rready = Input(Bool())
}

class AXI4LiteSlave(AW: Int, DW: Int) extends Bundle {
	val aresetn = Input(Bool())
	// Write address channel signals
	val awaddr  = Input(UInt(AW.W))
	val awvalid = Input(Bool())
	val awready = Output(Bool())
	// Write data channel signals
	val wdata  = Input(UInt(DW.W))
	val wstrb  = Input(UInt((DW / 8).W))
	val wvalid = Input(Bool())
	val wready = Output(Bool())
	// Write response channel signals
	val bresp  = Output(UInt(2.W))
	val bvalid = Output(Bool())
	val bready = Input(Bool())
	// Read address channel signals
	val araddr  = Input(UInt(AW.W))
	val arvalid = Input(Bool())
	val arready = Output(Bool())
	// Read data channel signals
	val rdata  = Output(UInt(DW.W))
	val rresp  = Output(UInt(2.W))
	val rvalid = Output(Bool())
	val rready = Input(Bool())
}
