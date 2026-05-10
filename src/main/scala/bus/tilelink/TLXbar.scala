package soc.bus.tilelink

import chisel3._
import chisel3.util._

// Rocket风格多主多从Xbar，A通道按slave仲裁，D通道按master仲裁。
class TLXbar(params: TLParams, nMasters: Int, nSlaves: Int, addrMap: Seq[UInt => Bool]) extends Module {
    require(nMasters > 0, "nMasters must be positive")
    require(nSlaves > 0, "nSlaves must be positive")
    require(addrMap.length == nSlaves, "addrMap size must equal nSlaves")

    private val masterTagBits = if (nMasters <= 1) 0 else log2Ceil(nMasters)
    private val slaveParams = params.copy(sourceBits = params.sourceBits + masterTagBits)

    val io = IO(new Bundle {
        val masters = Vec(nMasters, Flipped(new TLBundle(params)))
        val slaves  = Vec(nSlaves, new TLBundle(slaveParams))
    })

    private def expandSource(master: Int, source: UInt): UInt = {
        if (masterTagBits == 0) source else Cat(master.U(masterTagBits.W), source)
    }

    private def shrinkSource(source: UInt): UInt = {
        if (masterTagBits == 0) source else source(params.sourceBits - 1, 0)
    }

    private def decodeMaster(source: UInt): UInt = {
        if (masterTagBits == 0) 0.U else source(slaveParams.sourceBits - 1, params.sourceBits)
    }

    val routeHits = Seq.tabulate(nMasters, nSlaves) { (m, s) =>
        addrMap(s)(io.masters(m).a.bits.address)
    }
    val routeHitVecs = Seq.tabulate(nMasters) { m => VecInit(routeHits(m)) }
    val routeHasHit = routeHitVecs.map(_.asUInt.orR)

    val localErrValid  = RegInit(VecInit(Seq.fill(nMasters)(false.B)))
    val localErrOpcode = RegInit(VecInit(Seq.fill(nMasters)(TLOpcode.AccessAck)))
    val localErrSize   = RegInit(VecInit(Seq.fill(nMasters)(0.U(params.sizeBits.W))))
    val localErrSource = RegInit(VecInit(Seq.fill(nMasters)(0.U(params.sourceBits.W))))

    val aArbs = Seq.fill(nSlaves)(Module(new RRArbiter(new TLBundleA(slaveParams), nMasters)))
    for (s <- 0 until nSlaves) {
        for (m <- 0 until nMasters) {
            aArbs(s).io.in(m).valid := io.masters(m).a.valid && routeHits(m)(s)
            aArbs(s).io.in(m).bits.opcode := io.masters(m).a.bits.opcode
            aArbs(s).io.in(m).bits.param := io.masters(m).a.bits.param
            aArbs(s).io.in(m).bits.size := io.masters(m).a.bits.size
            aArbs(s).io.in(m).bits.source := expandSource(m, io.masters(m).a.bits.source)
            aArbs(s).io.in(m).bits.address := io.masters(m).a.bits.address
            aArbs(s).io.in(m).bits.mask := io.masters(m).a.bits.mask
            aArbs(s).io.in(m).bits.data := io.masters(m).a.bits.data
            aArbs(s).io.in(m).bits.corrupt := io.masters(m).a.bits.corrupt
        }
        io.slaves(s).a <> aArbs(s).io.out
    }

    for (m <- 0 until nMasters) {
        val readyVec = (0 until nSlaves).map(s => routeHits(m)(s) && aArbs(s).io.in(m).ready)
        when(io.masters(m).a.valid) {
            assert(PopCount(routeHitVecs(m)) <= 1.U, "TLXbar: address overlaps multiple slaves")
        }
        val routedReady = readyVec.reduceOption(_ || _).getOrElse(false.B)
        val localErrReady = !routeHasHit(m) && !localErrValid(m)
        io.masters(m).a.ready := routedReady || localErrReady

        when(io.masters(m).a.valid && io.masters(m).a.ready && !routeHasHit(m)) {
            localErrValid(m)  := true.B
            localErrOpcode(m) := Mux(io.masters(m).a.bits.opcode === TLOpcode.Get, TLOpcode.AccessAckData, TLOpcode.AccessAck)
            localErrSize(m)   := io.masters(m).a.bits.size
            localErrSource(m) := io.masters(m).a.bits.source
        }
    }

    val dArbs = Seq.fill(nMasters)(Module(new RRArbiter(new TLBundleD(params), nSlaves + 1)))
    for (m <- 0 until nMasters) {
        for (s <- 0 until nSlaves) {
            val respToMaster = decodeMaster(io.slaves(s).d.bits.source) === m.U
            dArbs(m).io.in(s).valid := io.slaves(s).d.valid && respToMaster
            dArbs(m).io.in(s).bits.opcode := io.slaves(s).d.bits.opcode
            dArbs(m).io.in(s).bits.param := io.slaves(s).d.bits.param
            dArbs(m).io.in(s).bits.size := io.slaves(s).d.bits.size
            dArbs(m).io.in(s).bits.source := shrinkSource(io.slaves(s).d.bits.source)
            dArbs(m).io.in(s).bits.sink := io.slaves(s).d.bits.sink
            dArbs(m).io.in(s).bits.denied := io.slaves(s).d.bits.denied
            dArbs(m).io.in(s).bits.data := io.slaves(s).d.bits.data
            dArbs(m).io.in(s).bits.corrupt := io.slaves(s).d.bits.corrupt
        }
        dArbs(m).io.in(nSlaves).valid := localErrValid(m)
        dArbs(m).io.in(nSlaves).bits.opcode := localErrOpcode(m)
        dArbs(m).io.in(nSlaves).bits.param := 0.U
        dArbs(m).io.in(nSlaves).bits.size := localErrSize(m)
        dArbs(m).io.in(nSlaves).bits.source := localErrSource(m)
        dArbs(m).io.in(nSlaves).bits.sink := 0.U
        dArbs(m).io.in(nSlaves).bits.denied := true.B
        dArbs(m).io.in(nSlaves).bits.data := 0.U
        dArbs(m).io.in(nSlaves).bits.corrupt := false.B
        when(dArbs(m).io.in(nSlaves).fire) {
            localErrValid(m) := false.B
        }
        io.masters(m).d <> dArbs(m).io.out
    }

    for (s <- 0 until nSlaves) {
        if (nMasters == 1) {
            io.slaves(s).d.ready := dArbs.head.io.in(s).ready
        } else {
            val readyCases = (0 until nMasters).map { m =>
                (decodeMaster(io.slaves(s).d.bits.source) === m.U) -> dArbs(m).io.in(s).ready
            }
            io.slaves(s).d.ready := MuxCase(false.B, readyCases)
        }
    }

    // First TL-C migration step: keep this crossbar functionally TL-UL while
    // exposing all five channels. Coherent managers/clients can replace these
    // tie-offs with Probe/Release/GrantAck routing in a later step.
    for (m <- 0 until nMasters) {
        TLBundle.tieoffSlaveCoherence(io.masters(m))
    }
    for (s <- 0 until nSlaves) {
        TLBundle.tieoffMasterCoherence(io.slaves(s))
    }
}

class TLSystemXbar(params: TLParams, nMasters: Int = 2) extends Module {
    private val tagBits = log2Ceil(nMasters)

    val io = IO(new Bundle {
        val masters = Vec(nMasters, Flipped(new TLBundle(params)))
        val slave   = new TLBundle(params.copy(sourceBits = params.sourceBits + tagBits))
    })

    private val xbar = Module(new TLXbar(params, nMasters = nMasters, nSlaves = 1, addrMap = Seq((_: UInt) => true.B)))

    xbar.io.masters <> io.masters
    io.slave <> xbar.io.slaves(0)
}
