package soc.core

import chisel3._

class Register(XLEN: Int) extends Module {
    val io = IO(new Bundle {
        val write_en   = Input(Bool())
        val write_addr = Input(UInt(5.W))
        val write_data = Input(UInt(XLEN.W))

        val rs1_addr = Input(UInt(5.W))
        val rs2_addr = Input(UInt(5.W))

        val rs1_data = Output(UInt(XLEN.W))
        val rs2_data = Output(UInt(XLEN.W))
    })

    val regFile = Mem(32, UInt(XLEN.W))

    when(io.write_en && (io.write_addr =/= 0.U)) {
        regFile.write(io.write_addr, io.write_data)
    }

    io.rs1_data := Mux(
        io.rs1_addr === 0.U,
        0.U,
        Mux(io.rs1_addr === io.write_addr && io.write_en, io.write_data, regFile.read(io.rs1_addr))
    )

    io.rs2_data := Mux(
		io.rs2_addr === 0.U,
		0.U,
		Mux(io.rs2_addr === io.write_addr && io.write_en, io.write_data, regFile.read(io.rs2_addr))
	)
}

