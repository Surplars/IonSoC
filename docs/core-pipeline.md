# Core Pipeline

## 概览

`src/main/scala/core/Core.scala` 集成当前单发射、顺序提交的 RV64 pipeline。主要模块为：

1. `PC`
2. `InstrFetch`
3. `InstrDecode`
4. `ALU`
5. `LSU`
6. `WirteBack`
7. `RegisterFile`
8. `CSRFile`

流水线不是严格教科书五级实现。为了 bring-up 外设、cache、S-mode 和 RustSBI，当前设计把多周期内存、CSR trap、fence.i、debug halt、I-cache backpressure 统一纳入全局 stall/flush 控制。

核心全局控制信号：

- `pipe_stall`: 来自 LSU 的内存等待。
- `decodeUsesPending`: load-like hazard 检测。
- `ifetch.io.fetch_stall`: I-cache 取指等待。
- `fenceIHold`: `fence.i` 正在刷新 I-cache。
- `debugHalted`: debug module 发起 halt 后暂停流水线。
- `global_stall`: 上述条件的总和，驱动 PC。

## PC 和分支预测

`src/main/scala/core/PC.scala` 保存 `ProgramCounter`，默认 reset 到 `Config.resetVector`，即 `0x80000000`。

输入来源优先级：

1. reset：输出 reset vector，取指暂时关闭。
2. trap/interrupt：跳转到 `trap_pc`。
3. trap return：跳转到 `trap_epc`。
4. stall 或 `redirectHold`：保持 PC。
5. 正常路径：使用 BPU 预测目标或 `PC + stepBytes`。

`instr_len` 编码：

- `0`: 32-bit 指令，PC 加 4。
- `2`: 16-bit compressed 指令，PC 加 2。

`redirectHold` 是为了避免 redirect 后下一拍 PC 被旧的取指/预测状态推进。这个点在 C 扩展和 cache path bring-up 时很关键。

## Instruction Fetch

`src/main/scala/core/pipeline/InstrFetch.scala` 同时支持 direct ROM path 和 cache path。

### Direct ROM path

`BROM` 输出 64-bit 指令窗口，由 low/high 两个 32-bit ROM 组成。`InstrFetch` 根据 PC 低位选择：

- 低半字或高半字的 16-bit compressed 指令。
- 从高 halfword 开始的 32-bit 指令，通过 64-bit window 拼接。

如果启用 C 扩展，`Compressed.expand` 会把 16-bit 指令展开为标准 32-bit 指令；非法 compressed 编码输出 `Common.instrIllegal`。

### I-cache path

I-cache path 使用 6 状态状态机：

- `sIdle`
- `sFirstReq`
- `sFirstWait`
- `sSecondReq`
- `sSecondWait`
- `sRelease`

关键行为：

- 第一拍请求当前 PC 所在 64-bit beat。
- 如果 compressed enabled 且 PC 指向 beat 最后一个 halfword，同时低 2 位显示这是 32-bit 指令，则发第二次请求取下一个 beat。
- `assembledCrossBeat = Cat(nextBeat(15,0), crossBeatLowHalf)` 拼接跨 beat 的 32-bit 指令。
- `pc_step_len` 在 cache response 被接受的同拍使用刚解出的 `acceptedLen`，避免“上一条 32-bit 后接 16-bit 时 PC 仍加 4”的 bug。
- flush 后仍保持 response channel 可 drain，避免 late response 把 I-cache 卡在 response 状态，进而阻塞 `fence.i`。

## Decode

`src/main/scala/core/pipeline/InstrDecode.scala` 使用 `InstrTable.getTable(enabledExt)` 获取当前配置允许的指令表。输出 `DecodedInstr`，包含：

- `rs1/rs2/rd`
- `op1/op2`
- `ctrl`: ALU/mem/CSR/branch 控制信号
- `funct3`
- `atomic/aq/rl`
- `instr_len`
- `br_imm/mem_imm`

异常处理：

- 未命中 decode table 或 ECALL 会生成 `trap_info.valid`。
- ECALL cause 按当前 privilege level 区分 U/S/M。
- MRET/SRET/MNRET 通过 `trap_info.is_ret` 和 `ret_type` 向后传递。

## Execute / ALU

`src/main/scala/core/pipeline/ALU.scala` 负责：

- RV64I/RV64M 运算。
- Zba/Zbb/Zbs bitmanip 子集。
- 分支目标和 redirect 决策。
- CSR read-modify-write 的数据准备。
- load/store/atomic/fence/fence.i 的 `MemoryAccessInfo` 生成。

ALU 支持多级 forward：

- 当前 LSU 返回的 load data。
- ALU result forward。
- 上一拍 ALU result forward。
- writeback forward。
- LSU mem_out forward。

分支重定向规则：

- JAL/JALR 总是视为 taken，需要 redirect。
- 条件分支 taken 时 redirect 到 target。
- 预测 taken 但实际 not taken 时 redirect 到 fallthrough。
- fallthrough 使用 `instr_len`，compressed 指令返回地址是 `PC + 2`。

## LSU

`src/main/scala/core/pipeline/LSU.scala` 是当前 pipeline 最复杂的模块，负责 cache/MMIO/load-store/atomic 统一调度。

主要子结构：

- `MemoryAccessStage`: 地址属性、PMP、未来 MMU 入口。
- `StoreBuffer(4)`: 非设备 store 入队后异步 drain 到 D-cache。
- cache load pending FSM。
- MMIO pending FSM。
- atomic pending FSM。
- LR/SC reservation。

访问分类：

- SRAM/cacheable: 经 D-cache 或 uncached bridge。
- device/MMIO: 经 `TLTransTracker` 发 TileLink。
- atomic: 当前在 LSU 内序列化为 cache read + optional cache write，不使用 TileLink Atomic opcode。

异常：

- misaligned load/store/atomic 产生 address misaligned。
- atomic to device 产生 access fault。
- PMP/access stage fault 产生 load/store access fault。
- TileLink denied response 转换为 load/store access fault。

当前 atomic 设计为单 hart 语义：

- LR 读取并设置 reservation。
- SC reservation 命中则写回并返回 0；未命中返回 1。
- AMO 读取旧值，计算新值，写回新值，返回旧值。
- store drain 和 atomic write 会清除 reservation。

## Writeback

`src/main/scala/core/pipeline/WirteBack.scala` 将 LSU 输出写回寄存器堆。写回条件：

- `rd != x0`
- `mem_in.reg_write`
- `valid_in`
- 当前没有 trap

文件名当前拼写为 `WirteBack.scala`，这是历史命名问题；重命名会影响引用，可以作为后续结构整理任务单独做。

## Hazard 和 Stall

当前核心主要处理以下 hazard：

- load-like pending：Load/LR/SC/AMO 目标寄存器尚未可用，而 decode 指令使用该 rd，则 stall。
- LSU 多周期访问：cache miss、MMIO、atomic、store buffer full 会拉住 `pipe_stall`。
- I-cache miss 或跨 beat fetch：`fetch_stall` 阻止 PC/前级推进。
- fence.i：等待 I-cache flush 完成后释放。
- debug halt：halted 时冻结 pipeline，并允许 debug module 访问 GPR/CSR。

旁路优先级主要在 `Core.decodeBypass` 和 `ALU` 输入选择中实现。需要注意 LSU 和 ALU 内部仍存在若干 RegNext 风格的时序补偿，后续性能优化可以考虑统一 forward network。

## Trap、Interrupt、Return

pipeline trap 来自 LSU 的 `trap_info_out`。外部中断由 CSRFile 仲裁后，在 Core 中 latch：

- `interrupt_detect`: CSR 看到 pending interrupt，且 pipeline 没有 stall/trap/ret。
- `interruptPending`: latch PC/cause/target，等待可注入周期。
- `interrupt_fire`: 生成一次 combined trap。

trap 和 return 冲刷前端：

- `redirect_flush = combined_trap || ret_redirect`
- `frontend_flush = redirect_flush || fenceIActive`
- IF/ID/ALU 使用这些信号丢弃旧指令。

return 使用 `retConsumed` 防止同一个 `MRET/SRET` 被重复消费。

## fence.i

`fence.i` 在 ALU 阶段识别为 `MemOpType.FenceI`。Core 会：

1. 计算 fence 指令 fallthrough PC。
2. 向 PC 注入一次 redirect。
3. 如果有 I-cache，发起 whole-cache invalidate。
4. 等待 I-cache 返回维护完成响应。
5. 释放 pipeline。

如果没有 I-cache，`fence.i` 退化为短暂 flush。

## Debug Halt

Debug Module 通过 `debug_haltreq/debug_resumereq` 控制 `debugHalted`。halt 生效要求当前没有 LSU stall，避免半个内存事务中间暂停。halted 后：

- GPR debug write 才被允许。
- CSR debug write 才被允许。
- pipeline 全局冻结。

当前 debug 侧适合 bring-up/OpenOCD examine，尚不是完整生产级 debug subsystem。

