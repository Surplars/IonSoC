package soc.device.interrupt

import chisel3._
import chisel3.util._
import soc.bus.tilelink._

object PLICMap {
    val PriorityBase: BigInt = 0x000000
    val PendingBase: BigInt = 0x001000
    val EnableBase: BigInt = 0x002000
    val ContextBase: BigInt = 0x200000
    val ContextStride: BigInt = 0x001000
    val ThresholdOffset: BigInt = 0x0
    val ClaimCompleteOffset: BigInt = 0x4
}

class PLIC(params: TLParams, nSources: Int = 31, priorityWidth: Int = 3) extends Module {
    require(nSources > 0, "PLIC must expose at least one interrupt source")
    require(nSources <= 31, "this PLIC register subset packs sources into one 32-bit word")
    require(priorityWidth > 0, "priorityWidth must be positive")

    val io = IO(new Bundle {
        val tl      = Flipped(new TLBundle(params))
        val sources = Input(Vec(nSources + 1, Bool()))
        val meip    = Output(Bool())
    })

    private val beatBytes = params.dataWidth / 8
    private val beatOffsetBits = log2Ceil(beatBytes)
    private val sourceIdWidth = log2Ceil(nSources + 1).max(1)
    private val maxPriority = ((BigInt(1) << priorityWidth) - 1).U(priorityWidth.W)
    private val priorityAddrBits = log2Ceil((nSources + 1) * 4)

    val priority = RegInit(VecInit(Seq.fill(nSources + 1)(0.U(priorityWidth.W))))
    val pending  = RegInit(VecInit(Seq.fill(nSources + 1)(false.B)))
    val enable   = RegInit(0.U((nSources + 1).W))
    val threshold = RegInit(0.U(priorityWidth.W))

    priority(0) := 0.U
    pending(0) := false.B

    // Source 0 is reserved by the PLIC spec. External gateways feed source IDs
    // starting at 1; a high level latches pending until claim/complete clears it.
    for (id <- 1 to nSources) {
        when(io.sources(id)) {
            pending(id) := true.B
        }
    }

    private def maskBits: UInt = {
        Cat((0 until beatBytes).reverse.map(i => Fill(8, io.tl.a.bits.mask(i))))
    }

    private def laneShift: UInt = {
        Cat(io.tl.a.bits.address(beatOffsetBits - 1, 0), 0.U(3.W))
    }

    private def placeRead(value: UInt): UInt = {
        (value << laneShift)(params.dataWidth - 1, 0)
    }

    private def mergeLaneMasked(oldValue: UInt): UInt = {
        val oldBeat = (oldValue << laneShift)(params.dataWidth - 1, 0)
        ((oldBeat & ~maskBits) | (io.tl.a.bits.data & maskBits)) >> laneShift
    }

    val notifyEligible = Wire(Vec(nSources + 1, Bool()))
    val claimEligible = Wire(Vec(nSources + 1, Bool()))
    for (id <- 0 to nSources) {
        notifyEligible(id) := pending(id) && enable(id) && priority(id) > threshold
        claimEligible(id) := pending(id) && enable(id) && priority(id) =/= 0.U
    }

    private def selectBest(eligible: Vec[Bool]): (UInt, UInt) = {
        val selected = (1 to nSources).foldLeft((0.U(sourceIdWidth.W), 0.U(priorityWidth.W))) {
            case ((curId, curPriority), id) =>
                val take = eligible(id) && (priority(id) > curPriority || (priority(id) === curPriority && (curId === 0.U || id.U < curId)))
                (Mux(take, id.U(sourceIdWidth.W), curId), Mux(take, priority(id), curPriority))
        }
        selected
    }

    val (notifyId, _) = selectBest(notifyEligible)
    val (claimId, _) = selectBest(claimEligible)

    io.meip := notifyId =/= 0.U

    val respValid  = RegInit(false.B)
    val respData   = RegInit(0.U(params.dataWidth.W))
    val respOpcode = RegInit(TLOpcode.AccessAck)
    val respSize   = RegInit(0.U(params.sizeBits.W))
    val respSource = RegInit(0.U(params.sourceBits.W))

    io.tl.a.ready := !respValid

    val offset = io.tl.a.bits.address(25, 0)
    when(io.tl.a.fire) {
        val isRead = io.tl.a.bits.opcode === TLOpcode.Get
        val isWrite = io.tl.a.bits.opcode === TLOpcode.PutFullData ||
            io.tl.a.bits.opcode === TLOpcode.PutPartialData
        val wordOffset = offset(25, 2) << 2
        val readData = Wire(UInt(params.dataWidth.W))
        readData := 0.U

        when(wordOffset >= (PLICMap.PriorityBase + 4).U && wordOffset <= (PLICMap.PriorityBase + nSources * 4).U) {
            val id = wordOffset(priorityAddrBits - 1, 2)
            readData := placeRead(priority(id))
        }.elsewhen(wordOffset === PLICMap.PendingBase.U) {
            readData := placeRead(pending.asUInt)
        }.elsewhen(wordOffset === PLICMap.EnableBase.U) {
            readData := placeRead(enable)
        }.elsewhen(wordOffset === (PLICMap.ContextBase + PLICMap.ThresholdOffset).U) {
            readData := placeRead(threshold)
        }.elsewhen(wordOffset === (PLICMap.ContextBase + PLICMap.ClaimCompleteOffset).U) {
            readData := placeRead(claimId)
        }

        when(isWrite) {
            when(wordOffset >= (PLICMap.PriorityBase + 4).U && wordOffset <= (PLICMap.PriorityBase + nSources * 4).U) {
                val id = wordOffset(priorityAddrBits - 1, 2)
                val merged = mergeLaneMasked(priority(id))
                priority(id) := Mux(merged(priorityWidth - 1, 0) > maxPriority, maxPriority, merged(priorityWidth - 1, 0))
            }.elsewhen(wordOffset === PLICMap.EnableBase.U) {
                enable := mergeLaneMasked(enable)(nSources, 0) & ~1.U((nSources + 1).W)
            }.elsewhen(wordOffset === (PLICMap.ContextBase + PLICMap.ThresholdOffset).U) {
                val merged = mergeLaneMasked(threshold)
                threshold := Mux(merged(priorityWidth - 1, 0) > maxPriority, maxPriority, merged(priorityWidth - 1, 0))
            }.elsewhen(wordOffset === (PLICMap.ContextBase + PLICMap.ClaimCompleteOffset).U) {
                val completeId = mergeLaneMasked(0.U)(sourceIdWidth - 1, 0)
                when(completeId =/= 0.U && completeId <= nSources.U) {
                    pending(completeId) := false.B
                }
            }
        }.elsewhen(isRead && wordOffset === (PLICMap.ContextBase + PLICMap.ClaimCompleteOffset).U && claimId =/= 0.U) {
            // Claim is a side-effecting read: it returns the selected source and
            // clears that pending bit so software can service it exactly once.
            pending(claimId) := false.B
        }

        respValid  := true.B
        respOpcode := Mux(isRead, TLOpcode.AccessAckData, TLOpcode.AccessAck)
        respSize   := io.tl.a.bits.size
        respSource := io.tl.a.bits.source
        respData   := readData
    }

    io.tl.d.valid        := respValid
    io.tl.d.bits.opcode  := respOpcode
    io.tl.d.bits.param   := 0.U
    io.tl.d.bits.size    := respSize
    io.tl.d.bits.source  := respSource
    io.tl.d.bits.sink    := 0.U
    io.tl.d.bits.denied  := false.B
    io.tl.d.bits.data    := respData
    io.tl.d.bits.corrupt := false.B

    when(io.tl.d.fire) {
        respValid := false.B
    }
}
