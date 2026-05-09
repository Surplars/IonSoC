package sim

import chisel3._
import _root_.circt.stage.ChiselStage
import soc._
import soc.config.SoCFeatures
import soc.config.SoCProfiles

class SimTop extends IonSoC
class SimTopICache extends IonSoC(SoCFeatures(iCache = true)) {
    override def desiredName: String = "SimTop"
}
class SimTopFirmware extends IonSoC(SoCProfiles.LinuxCapablePLIC.copy(mmu = false)) {
    override def desiredName: String = "SimTop"
}

object EmitHelper {
    def emit(top: => RawModule, targetDir: String = "build/rtl"): Unit = {
        ChiselStage.emitSystemVerilogFile(
            top,
            args = Array("--target-dir", targetDir),
            firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info", "-default-layer-specialization=enable")
        )
    }
}

object TopMain extends App {
    EmitHelper.emit(new SimTop, "build/rtl")
}

object ICacheTopMain extends App {
    EmitHelper.emit(new SimTopICache, "build/rtl-icache")
}

object FirmwareTopMain extends App {
    EmitHelper.emit(new SimTopFirmware, "build/rtl-firmware")
}
