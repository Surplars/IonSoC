package sim

import chisel3._
import _root_.circt.stage.ChiselStage
import soc._
import soc.config.SoCFeatures

class SimTop extends IonSoC
class SimTopICache extends IonSoC(SoCFeatures(iCache = true)) {
    override def desiredName: String = "SimTop"
}

object EmitHelper {
    def emit(top: => RawModule): Unit = {
        ChiselStage.emitSystemVerilogFile(
            top,
            args = Array("--target-dir", "build/rtl"),
            firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info", "-default-layer-specialization=enable")
        )
    }
}

object TopMain extends App {
    EmitHelper.emit(new SimTop)
}

object ICacheTopMain extends App {
    EmitHelper.emit(new SimTopICache)
}
