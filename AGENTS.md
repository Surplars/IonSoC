# Repository Guidelines

## Project Structure & Module Organization

IonSoC is a Scala 2.13 Chisel SoC project built primarily with Mill. Hardware sources live in `src/main/scala`, grouped by subsystem: `core`, `core/pipeline`, `bus`, `bus/tilelink`, `memory`, `device`, `isa`, `debug`, `config`, and `system`. Scala tests live in `src/test/scala`; `src/test/scala/sim.scala` emits SystemVerilog into `build/rtl`. Simulator sources are split under `simulator/`: `payloads/` for bare-metal assembly and linker scripts, `harness/` for Verilator C++ drivers, `rtl/` for supplemental SystemVerilog, and `build/` for generated artifacts.

## Build, Test, and Development Commands

- `mill -i IonSoC.compile`: compile the Scala/Chisel sources.
- `mill -i IonSoC.test`: run ScalaTest tests configured by the Mill test module.
- `mill -i IonSoC.test.runMain sim.TopMain`: generate RTL into `build/rtl`.
- `make sim-verilog`: wrapper for RTL generation.
- `make payload`: build the RISC-V bare-metal payload using `riscv64-unknown-elf-*`.
- `make verilator`: generate RTL, compile the Verilator model, and run `simulator/harness/verilator_main.cpp`.
- `make clean`: clean Mill, difftest, and payload build artifacts.

The Makefile expects `riscv64-unknown-elf-gcc`, `verilator`, and, for difftest targets, `NEMU_HOME` and `NOOP_HOME` to be valid.

## Coding Style & Naming Conventions

Use Scalafmt-compatible formatting before submitting changes. `.scalafmt.conf` sets the Scala 2.13 dialect, 4-space indentation, 120-column limit, and aligned declarations. Use package names that match the existing subsystem directories. Name hardware modules and Bundles in `PascalCase` (`IonSoC`, `UartTx`, `CSRFile`), values and methods in `camelCase`, and constants in the local style already present in the file.

## Testing Guidelines

Place Scala tests under `src/test/scala`, preferably near the package or subsystem under test. Use ScalaTest via Mill/SBT. For hardware behavior, add focused elaboration or simulation tests before broad integration tests. Run `mill -i IonSoC.test` after Scala changes and `make verilator` when RTL generation or simulator behavior changes. Keep generated VCDs and compiled simulator artifacts out of commits.

## Commit & Pull Request Guidelines

The current history uses short imperative or topic-style subjects, for example `TileLink Dev`, `pipeline decode test`, and `Template cleanup`. Keep commit subjects concise and name the changed subsystem. Pull requests should include the motivation, affected modules, commands run, and simulator or waveform observations. Link related issues when available.

## Agent-Specific Instructions

Preserve user changes unless explicitly asked to clean them. Prefer Mill commands for Scala/Chisel work and Makefile targets for simulator flows. Do not edit vendored or generated difftest/NEMU outputs unless the task specifically targets them.
