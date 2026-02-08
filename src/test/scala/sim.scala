package sim

import chisel3._
import _root_.circt.stage.ChiselStage
import soc._

class SimTop extends IonSoC

object TopMain extends App {
    ChiselStage.emitSystemVerilogFile(
		new SimTop,
		// args = Array("--target-dir", "simulator/build/sv"),
		args = Array("--target-dir", "build/rtl"),
        firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info", "-default-layer-specialization=enable")
    )
}


