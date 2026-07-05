# IonSoC

IonSoC 是一个 Scala 2.13 / Chisel 实现的 RV64 SoC，用 Mill 构建，Verilator 做本地仿真。当前重点是从裸机 smoke、RustSBI/OpenSBI firmware flow 继续推进到 Linux bring-up。

## 当前状态

- RV64IMAC 为主，带 M/S mode、CSR、CLINT、PLIC、UART、I/D cache 和 TileLink-UH 子集互连。
- 裸机 payload、PLIC/UART interrupt、RustSBI jump flow 已有 Verilator smoke。
- Linux profile 已能通过 ROM trampoline -> OpenSBI -> S-mode Linux `Image`，并进入 early kernel memory init。
- Sv39 页表 walk 已接入 IF/LSU，项目内 `sv39` payload 和高半区 fixmap 定向 payload 已验证通过。
- Linux 完整启动尚未完成，当前卡在 text patch/fixmap 路径的 store page fault，详见 [Bring-up Bug Record](docs/bringup-bug-record.md)。

## 快速命令

```bash
mill -i IonSoC.compile
mill -i IonSoC.test
make test-fast
make regress
make verilator-run-rustsbi
make verilator-run-linux
```

Linux 目标默认以 UART 输出 `Initmem setup node 0` 作为 early boot 里程碑：

```bash
LINUX_EXPECT_UART='Initmem setup node 0' make verilator-run-linux
```

继续追完整启动时使用更深的预期点：

```bash
env LINUX_EXPECT_UART='Memory:' LINUX_MAX_CYCLES=120000000 make verilator-run-linux
```

截至本文档更新，该命令仍会在 `patch_insn_write` / `FIX_TEXT_POKE0` 路径触发 `0xfffffffffebfa030` store page fault。

## 文档

- [docs/README.md](docs/README.md): 文档总入口和状态索引。
- [docs/architecture.md](docs/architecture.md): 顶层结构、profile、地址空间。
- [docs/core-pipeline.md](docs/core-pipeline.md): 核心流水线和 hazard/trap/fence。
- [docs/memory-cache-tilelink.md](docs/memory-cache-tilelink.md): cache、TileLink、内存路径。
- [docs/simulation-firmware-debug.md](docs/simulation-firmware-debug.md): Verilator、payload、firmware、Linux/OpenSBI、JTAG/OpenOCD。
- [docs/bringup-bug-record.md](docs/bringup-bug-record.md): bring-up 问题记录、根因和验证命令。

## 目录

- `src/main/scala`: Chisel/Scala RTL。
- `src/test/scala`: ScalaTest / ChiselSim 测试。
- `simulator/payloads`: 裸机和诊断 payload。
- `simulator/harness`: Verilator C++ harness。
- `simulator/firmware`: RustSBI/OpenSBI/DTS 相关文件。
- `docs`: 架构、仿真和 bring-up 文档。
