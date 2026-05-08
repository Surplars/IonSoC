package soc.debug

import chisel3._
import chisel3.util._
import soc.bus.tilelink._

object DebugModuleMap {
    val DMControl  = 0x10
    val DMStatus   = 0x11
    val AbstractCS = 0x16
    val Data0      = 0x04
}

class DebugModule(params: TLParams) extends Module {
    val io = IO(new Bundle {
        val tl = Flipped(new TLBundle(params))
        val dmi_valid = Input(Bool())
        val dmi_addr  = Input(UInt(7.W))
        val dmi_wdata = Input(UInt(32.W))
        val dmi_write = Input(Bool())
        val dmi_rdata = Output(UInt(32.W))

        val haltreq = Output(Bool())
        val resumereq = Output(Bool())
        val dmactive = Output(Bool())
        val hart_halted = Input(Bool())
    })

    private val beatBytes = params.dataWidth / 8
    val dmcontrol = RegInit(0.U(32.W))
    val data0 = RegInit(0.U(32.W))

    private val abstractcs = "h01000000".U(32.W) // one data register, no abstract commands yet

    private val haltedBits = Cat(!io.hart_halted, !io.hart_halted, io.hart_halted, io.hart_halted)
    private val dmstatus = Cat(0.U(14.W), true.B, 0.U(5.W), haltedBits, 0.U(4.W), 2.U(4.W))

    private def readReg(addr: UInt): UInt = {
        MuxLookup(addr, 0.U(32.W))(
            Seq(
                DebugModuleMap.DMControl.U  -> dmcontrol,
                DebugModuleMap.DMStatus.U   -> dmstatus,
                DebugModuleMap.AbstractCS.U -> abstractcs,
                DebugModuleMap.Data0.U      -> data0
            )
        )
    }

    private def writeReg(addr: UInt, data: UInt): Unit = {
        when(addr === DebugModuleMap.DMControl.U) {
            dmcontrol := Mux(data(30), data & ~(1.U(32.W) << 31), data)
        }.elsewhen(addr === DebugModuleMap.Data0.U) {
            data0 := data
        }
    }

    when(io.dmi_valid) {
        when(io.dmi_write) {
            writeReg(io.dmi_addr, io.dmi_wdata)
        }
    }
    io.dmi_rdata := readReg(io.dmi_addr)

    val respValid = RegInit(false.B)
    val respOpcode = RegInit(TLOpcode.AccessAck)
    val respSize = RegInit(0.U(params.sizeBits.W))
    val respSource = RegInit(0.U(params.sourceBits.W))
    val respData = RegInit(0.U(params.dataWidth.W))
    val respDenied = RegInit(false.B)

    io.tl.a.ready := !respValid

    when(io.tl.a.fire) {
        val isRead = io.tl.a.bits.opcode === TLOpcode.Get
        val isWrite = io.tl.a.bits.opcode === TLOpcode.PutFullData || io.tl.a.bits.opcode === TLOpcode.PutPartialData
        val wordAddr = io.tl.a.bits.address(9, 2)
        val lane = io.tl.a.bits.address(log2Ceil(beatBytes) - 1, 0)
        val writeData = (io.tl.a.bits.data >> Cat(lane, 0.U(3.W)))(31, 0)
        val readData32 = readReg(wordAddr)

        respValid := true.B
        respOpcode := Mux(isRead, TLOpcode.AccessAckData, TLOpcode.AccessAck)
        respSize := io.tl.a.bits.size
        respSource := io.tl.a.bits.source
        respDenied := !(isRead || isWrite)
        respData := Mux(isRead, readData32 << Cat(lane, 0.U(3.W)), 0.U)

        when(isWrite) {
            writeReg(wordAddr, writeData)
        }
    }

    io.tl.d.valid := respValid
    io.tl.d.bits.opcode := respOpcode
    io.tl.d.bits.param := 0.U
    io.tl.d.bits.size := respSize
    io.tl.d.bits.source := respSource
    io.tl.d.bits.sink := 0.U
    io.tl.d.bits.denied := respDenied
    io.tl.d.bits.data := respData
    io.tl.d.bits.corrupt := false.B

    when(io.tl.d.fire) {
        respValid := false.B
    }

    io.dmactive := dmcontrol(0)
    io.haltreq := dmcontrol(31) && dmcontrol(0)
    io.resumereq := dmcontrol(30) && dmcontrol(0)
}
