package sim

import chisel3._
import _root_.circt.stage.ChiselStage
import difftest.DifftestModule
import soc._
import soc.config.ISAProfiles
import soc.config.SoCFeatures
import soc.config.SoCProfiles

class SimTop extends IonSoC

// Explicit MCU emission target. Keeping this separate from SimTop lets tests
// and Makefile targets depend on a stable platform contract.
class SimTopMCU extends IonSoC(SoCProfiles.BareMetalMCU, ISAProfiles.RV64IMACB) {
    override def desiredName: String = "SimTop"
}
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

object McuTopMain extends App {
    EmitHelper.emit(new SimTopMCU, "build/rtl-mcu")
}

object ICacheTopMain extends App {
    EmitHelper.emit(new SimTopICache, "build/rtl-icache")
}

object FirmwareTopMain extends App {
    EmitHelper.emit(new SimTopFirmware, "build/rtl-firmware")
}

object DifftestTopMain extends App {
    EmitHelper.emit(
        DifftestModule.top(new IonSoCDifftest(SoCProfiles.LinuxCapablePLIC.copy(mmu = false), ISAProfiles.RV64IMACB)),
        "build/rtl-difftest"
    )
}
