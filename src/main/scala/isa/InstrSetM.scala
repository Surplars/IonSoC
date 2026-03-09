package soc.isa

import chisel3._

import soc.core.pipeline.ALUOps
import soc.core.pipeline.OpSel
import soc.core.pipeline.BranchType

object InstrSetM extends InstrProvider {
	def instructions: Array[InstrEntry] = Array()
}
