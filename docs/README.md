# IonSoC Documentation

本目录记录 IonSoC 当前实现状态、模块边界、流水线设计、总线/缓存/外设、仿真启动链路，以及 bring-up 过程中已经定位并修复的关键问题。

文档基于当前仓库代码编写，重点覆盖以下路径：

- `src/main/scala/core`: RV64 核心、PC、寄存器堆、CSR、流水线。
- `src/main/scala/isa`: 指令集表、扩展选择、压缩指令展开。
- `src/main/scala/bus/tilelink`: TileLink Bundle、crossbar、事务 tracker、TLRAM。
- `src/main/scala/memory`: cache 请求/响应、L1 cache、uncached bridge。
- `src/main/scala/device`: ROM/SRAM、UART、CLINT、PLIC、错误设备。
- `src/main/scala/debug`: JTAG TAP、Debug Module、OpenOCD 接入基础。
- `src/main/scala/system`: `IonSoC` 顶层集成。
- `simulator`: Verilator harness、payload、RustSBI 接入、DTS、OpenOCD 配置。

## 文档索引

- [SoC Architecture](architecture.md): 顶层结构、可配置 profile、地址空间、模块连接关系。
- [Core Pipeline](core-pipeline.md): PC/IF/ID/EX/LSU/WB、旁路、stall、trap、fence.i、debug halt。
- [ISA, CSR, and Interrupts](isa-csr-interrupts.md): 指令集扩展、CSR/S-mode、CLINT、PLIC、中断仲裁。
- [Memory, Cache, and TileLink](memory-cache-tilelink.md): TileLink-UH 子集、crossbar、L1 cache、store buffer、PMP/MMU 现状。
- [Simulation, Firmware, and Debug](simulation-firmware-debug.md): Mill/Make/Verilator、RustSBI 启动流、JTAG/OpenOCD、常用环境变量。
- [Bring-up Bug Record](bringup-bug-record.md): 已解决 bug 的症状、根因、修复方式、验证命令。

## 当前总体状态

IonSoC 当前已经能够运行无 MMU 的 RustSBI jump flow：ROM trampoline 跳入 SRAM 中的 RustSBI，RustSBI 初始化 M-mode 平台后跳转到 S-mode smoke payload，并通过 legacy SBI console 输出 `IonSoC SBI smoke`。对应验证命令：

```bash
make verilator-run-rustsbi
```

当前默认配置已固化为 `SoCProfiles.BareMetalMCU`：RV64IMAC(+小部分 B 扩展)、M/S mode、无 MMU、I/D cache、UART、CLINT、PLIC 和默认 64 KiB SRAM。MMU 接口已预留但页表转换未实现；AIA 作为配置枚举预留；JTAG/OpenOCD 已能通过 remote bitbang 连接 TAP/DM 的基础链路，但距离完整 RISC-V Debug Spec 仍有工作量。
