# Simulation, Firmware, and Debug

## 构建工具

项目主要使用 Mill 构建 Chisel/Scala：

```bash
mill -i IonSoC.compile
mill -i IonSoC.test
mill -i IonSoC.test.runMain sim.TopMain
```

Makefile 封装了常用 RTL 生成、payload 编译和 Verilator 运行：

```bash
make sim-verilog
make sim-verilog-icache
make sim-verilog-firmware
make payload
make verilator
make regress
make regress-icache
make verilator-run-rustsbi
```

依赖：

- `riscv64-unknown-elf-gcc`
- `riscv64-unknown-elf-objcopy`
- `verilator`
- `dtc`
- Rust nightly / RustSBI 需要的 target
- `llvm-objcopy` 或 `rust-objcopy` 或 `riscv64-unknown-elf-objcopy`

## RTL 生成 Profile

`src/test/scala/sim.scala` 定义三个 emission entry：

- `sim.TopMain` -> `build/rtl`
- `sim.McuTopMain` -> `build/rtl-mcu`
- `sim.ICacheTopMain` -> `build/rtl-icache`
- `sim.FirmwareTopMain` -> `build/rtl-firmware`

对应 Make target：

```bash
make sim-verilog
make sim-verilog-mcu
make sim-verilog-icache
make sim-verilog-firmware
```

Firmware profile 使用 `SoCProfiles.LinuxCapablePLIC.copy(mmu = false)`，即 16 MiB SRAM、I/D cache、PLIC、CLINT、UART，但 MMU 暂时关闭。

常规本地测试优先使用：

```bash
make test-fast
```

`test-fast` 覆盖配置、bus、设备、PLIC、L1 cache、CSR 和 IF。更细的本地目标如下：

```bash
make test-profile
make test-bus
make test-devices
make test-clint
make test-uart
make test-plic
make test-debug
make test-cache
make test-core-fast
make test-core-mem
```

设备测试已经拆成独立 spec：`CLINTSpec`、`TLDeviceSpec`、`UartSpec` 和 `PLICSpec`。Debug 相关测试在 `src/test/scala/debug`。改单个外设时优先跑对应 Make target，或直接运行单个 spec：

```bash
mill -i IonSoC.test.testOnly device.UartSpec
mill -i IonSoC.test.testOnly debug.DebugModuleSpec
```

顶层 SoC/JTAG/debug halt 属于慢测，放在：

```bash
make test-slow
```

## Payload

裸机 payload 位于 `simulator/payloads`。

常用 payload：

- `timer.S`: timer/CLINT smoke。
- `clint32.S`: 32-bit CLINT high/low word 访问。
- `tlerror.S`: unmapped/denied response smoke。
- `amo.S`: A 扩展 smoke。
- `hazard.S`: pipeline hazard smoke。
- `plic.S`: M-mode PLIC smoke。
- `plic_s.S`: S-mode PLIC/delegation smoke。
- `uart_irq.S`: harness 注入 UART RX byte，验证 UART RDI -> PLIC source 1 -> M-mode external interrupt -> claim/complete。
- `firmware_trampoline.S`: ROM 到 firmware SRAM 的 trampoline。
- `firmware_probe.S`: firmware bring-up probe。
- `sbi_smoke.S`: RustSBI 跳入 S-mode 后的 SBI console smoke。

Linker scripts：

- `payload.ld`: ROM `0x80000000` + default SRAM `0x10000000`。
- `firmware.ld`: firmware probe 放在 `0x40000000`。
- `sbi_payload.ld`: S-mode payload 放在 `0x40100000`。

## Verilator Harness

主 harness 是 `simulator/harness/verilator_main.cpp`。

能力：

- ELF program header loader。
- 支持 ROM/SRAM 按地址加载。
- 支持 firmware profile 的多 ELF 加载：trampoline、RustSBI、S-mode payload。
- 支持 DTB blob 加载。
- 可以注入 `a0/a1/a2`。
- UART stdout 捕获和可选 stdin。
- CLINT/PLIC/外部中断仿真入口。
- JTAG remote-bitbang server。
- boot trace / CPU trace / IRQ trace / DMI trace。
- 可选 VCD trace，默认不生成波形文件。

常用环境变量：

| 变量 | 用途 |
| --- | --- |
| `ION_MAX_CYCLES` | 最大仿真 cycle |
| `ION_TRACE_BOOT=1` | 打印 ROM/SRAM/payload/trap/cache boot trace |
| `ION_TRACE_CPU=1` | 打印 CPU trace |
| `ION_TRACE_PC_START/END` | 限制 CPU trace PC 范围 |
| `ION_TRACE_IRQ=1` | 打印中断状态 |
| `ION_TRACE_DMI=1` | 打印 DMI/JTAG debug 状态 |
| `ION_TRACE_WAVE=1` | 生成 `simulator/build/wave.vcd`；默认关闭，长仿真不要打开 |
| `ION_EXPECT_UART` | UART 预期字符串 |
| `ION_SRAM_BASE/SIZE` | 覆盖 SRAM 地址/大小 |
| `ION_DTB_ADDR` | DTB 加载地址 |
| `ION_BOOT_A0/A1/A2` | 注入启动寄存器 |
| `ION_UART_STDIN=1` | 将 stdin 接入模拟 UART RX |
| `ION_JTAG_RBB_PORT` | remote-bitbang 端口 |
| `ION_JTAG_ONLY=1` | JTAG-only 运行模式 |

## RustSBI Jump Flow

当前 RustSBI 路径由以下文件协同：

- `simulator/firmware/rustsbi`: RustSBI source/vendor tree。
- `simulator/firmware/rustsbi/prototyper/prototyper/config/ionsoc.toml`: IonSoC prototyper 配置。
- `simulator/firmware/ionsoc.dts`: 传给 RustSBI/OS 的设备树。
- `simulator/payloads/firmware_trampoline.S`: ROM trampoline。
- `simulator/payloads/sbi_smoke.S`: S-mode smoke payload。
- `Makefile` 的 `verilator-run-rustsbi`。

启动过程：

1. Verilator loader 将 trampoline ELF 写入 ROM。
2. RustSBI ELF 写入 SRAM `0x40000000`。
3. `sbi_smoke.elf` 写入 SRAM `0x40100000`。
4. DTB 写入 SRAM `0x40f00000`。
5. harness 在 reset 后注入：
   - `a0 = hartid = 0`
   - `a1 = dtb address = 0x40f00000`
   - `a2 = payload address = 0x40100000`
6. ROM trampoline 打印 `ROM->SRAM` 并跳到 `0x40000000`。
7. RustSBI 初始化平台，patch DTB，配置 PMP，然后跳到 S-mode payload。
8. S-mode payload 用 legacy SBI console_putchar 打印 `IonSoC SBI smoke`。
9. payload 设置 `a7=93/a0=0` 作为 harness exit sentinel。

验证：

```bash
make verilator-run-rustsbi
```

预期输出包含：

```text
ROM->SRAM
[RustSBI] INFO - Hello RustSBI!
Redirecting hart 0 to 0x00000040100000 in Supervisor mode.
IonSoC SBI smoke
[rustsbi]: gp=0, a7=93, a0=0, test passed
```

## UART 模拟

硬件 `UartTx` 是 16550-like register subset，DTS compatible 为 `ns16550a`。

支持：

- THR/RBR/DLL
- IER/DLM
- IIR/FCR
- LCR/MCR/LSR/MSR/SCR
- RX ready、overrun error
- THR empty interrupt
- RX data available interrupt

Verilator harness 在 `io_uart_tx` 有效时捕获 `io_uart_byte` 并打印到 stdout。`ION_UART_STDIN=1` 可把 host stdin 注入 RX。

## JTAG/OpenOCD

JTAG 配置：

```bash
make verilator-jtag
openocd -f openocd/ionsoc-rbb.cfg
```

`openocd/ionsoc-rbb.cfg` 使用：

- adapter: `remote_bitbang`
- host: `127.0.0.1`
- port: `9824`
- IR length: 5
- expected IDCODE: `0x10e31913`
- target: `riscv`
- memory access: abstract

非交互 smoke：

```bash
make openocd-smoke
```

该目标启动 Verilator remote-bitbang，验证 TAP IDCODE、OpenOCD examine、halt/resume，以及通过直接 DMI 操作 `sbcs/sbaddress/sbdata` 做一次 SBA SRAM 写读。smoke 同时确认 OpenOCD 能看到 `progbufsize=2`，并覆盖其 fence/postexec 探测路径。

当前 JTAG TAP 是同步到 SoC clock 的第一阶段实现，通过 TCK edge detect 驱动 TAP FSM。生产级设计应替换为真实 TCK clock domain + CDC。

Debug Module 当前支持：

- `dmcontrol`
- `dmstatus`
- `hartinfo`
- `abstractcs`
- `abstractauto`，支持 `autoexecdata0/1` 与 `autoexecprogbuf0/1`
- `command`
- `data0/data1`
- `progbuf0/progbuf1` backing register
- 安全 postexec 子集解释：`nop`、`fence`、`fence.i`、`ebreak`
- `haltsum0`
- `sbcs/sbaddress0/sbaddress1/sbdata0/sbdata1`
- halt/resume request
- GPR abstract read/write
- CSR abstract read/write
- debug CSR `dcsr/dpc/dscratch0` 子集
- hart halted 时捕获 `dpc`
- SBA 通过 TileLink 作为低优先级 master 访问系统总线，当前支持单 outstanding、8/16/32/64-bit 访问、跨 beat split、`sbreadonaddr/sbreadondata`、`sbautoincrement` 和基础 error/busy 状态
- DMI 在 SBA 忙时可返回 busy op，便于 OpenOCD 重试

限制：

- program buffer 当前是 DM 内部安全解释子集，不是真实 hart 指令执行入口。`abstractcs.progbufsize=2` 仅承诺 `nop/fence/fence.i/ebreak` postexec 探测可用；load/store 等 helper 序列会返回 `cmderr`，避免误改 architectural state。
- SBA 尚未实现硬件 cache 一致性；调试器直接改内存后，hart 侧需执行 `fence`/`fence.i`，或后续接入显式 debug cache maintenance hook。
- OpenOCD 高级功能可能仍会触发 unsupported command。
- 若 simulator 被 kill，OpenOCD 可能卡在 remote_bitbang socket 状态，需要单独终止 OpenOCD 进程。

## 推荐测试命令

快速硬件定向：

```bash
mill -i IonSoC.test.testOnly core.CSRFileSpec core.InstrFetchSpec
```

裸机回归：

```bash
make regress
```

I-cache 回归：

```bash
make regress-icache
```

RustSBI flow：

```bash
make verilator-run-rustsbi
```

带 boot trace：

```bash
ION_TRACE_BOOT=1 make verilator-run-rustsbi
```
