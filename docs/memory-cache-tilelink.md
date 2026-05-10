# Memory, Cache, and TileLink

## TileLink 子集

TileLink 定义在 `src/main/scala/bus/tilelink/TileLink.scala`。当前实际使用 A/D 通道：

- A channel: `Get`、`PutFullData`、`PutPartialData`
- D channel: `AccessAck`、`AccessAckData`
- `denied` 用于错误响应

B/C/E 通道 Bundle 已定义但未接入一致性协议。

`TLParams` 默认：

- `addrWidth = 32`
- `dataWidth = 64`
- `sourceBits = 4`
- `sinkBits = 1`
- `sizeBits = 3`

目前 SoC 地址是 64-bit 概念，但 TileLink 地址宽度默认 32-bit。当前映射均落在 32-bit 范围内。若后续扩展到高物理地址，需要先统一 TL address width。

## TLXbar

`src/main/scala/bus/tilelink/TLXbar.scala` 实现多 master、多 slave crossbar。

行为：

- A 通道按 slave 方向使用 `RRArbiter` 仲裁。
- source ID 扩展 master tag，D 通道根据 source 高位回到原 master。
- 每个 master 地址命中最多一个 slave；若重叠会 assert。
- 未命中地址会在 xbar 内生成本地 denied response，避免 master 永久等待。

`TLSystemXbar` 是 n-master 到单 slave 的聚合器，用在 Core 内部 DBus 汇聚。

## TLTransTracker

`src/main/scala/bus/tilelink/TLTransTracker.scala` 把 LSU 的简化 `MmioMaster` 接口转换为 TileLink。

功能：

- 分配 source ID。
- 记录 inflight entry。
- D response 返回后释放 source。
- response 打一拍再送回 LSU，避免 response valid/data 组合竞争。

当前 Core 实例化 tracker 时 `maxInFlight = 1 << tlParams.sourceBits`，但 LSU 的 MMIO pending 逻辑当前一次只发一个 MMIO request。

## TLRAM

`src/main/scala/bus/tilelink/TLRAM.scala` 是 SRAM 的 TileLink slave。

实现细节：

- `SyncReadMem(depth, Vec(beatBytes, UInt(8.W)))`
- A channel 先经过深度 2 queue。
- write 使用 byte mask。
- read response 返回完整 aligned beat，不做 lane shift；LSU/cache 负责按原始地址提取。
- unsupported opcode 返回 denied。

Firmware profile 下 SRAM 大小为 16 MiB；默认裸机 profile 为 64 KiB。

## ROM/TLROM/BROM

`src/main/scala/device/rom.scala` 包含：

- `ROM`: inline SystemVerilog blackbox，32-bit word ROM。
- `BROM`: 两个 `ROM` 组成 64-bit 取指窗口，供 core reset 后直接取指。
- `TLROM`: TileLink read-only ROM slave，用于 data-side 访问 ROM。

BROM 同时读取 `addr` 和 `addr + 4`，这是 C 扩展支持从高 halfword 开始拼接 32-bit 指令的基础。

## L1 Cache

`src/main/scala/memory/cache/L1Cache.scala` 实现直接映射 L1 cache。

默认参数：

- nSets = 512，Core 当前实例化时使用 256。
- line size = 1 TileLink beat，即 8 bytes。
- tag/index/offset 从地址切分。
- valid/dirty 使用 Reg Vec。
- tag/data 使用 `SyncReadMem`。

状态机：

- `sIdle`: 等 CPU request 或 invalidate。
- `sCompare`: hit/miss 判断。
- `sWriteBackReq/sWriteBackWait`: dirty victim 写回。
- `sRefillReq/sRefillWait`: miss refill。
- `sResp`: 向 CPU 返回响应。
- `sFlushRead/sFlushInvalidate/sFlushWriteBackReq/sFlushWriteBackWait`: whole-cache flush/invalidate。

Hit 行为：

- read hit 直接返回 cache line。
- write hit 根据 byte mask 合并并置 dirty。

Miss 行为：

- dirty victim 先 writeback。
- refill 新 beat。
- 如果 miss 是 write 引起，refill 数据会立即 merge write data 后写入 cache。

维护行为：

- `invalidate` 是 whole-cache maintenance。
- CPU request 上的 `fence/fence.i` 也会进入同一套 whole-cache maintenance FSM。
- dirty line 会先写回，再清 valid/dirty。
- 完成后通过 `cpu.resp.valid` 回 ack。
- 这个机制同时用于 I-cache `fence.i` 和 D-cache `fence`。

限制：

- direct-mapped，line size 只有 8 bytes，性能不高。
- 没有 burst refill。
- 没有 non-blocking miss。
- 没有 coherence。
- cache assert 要求 device/uncached request 不进入 cache。

## UncachedTileLinkBridge

`src/main/scala/memory/cache/UncachedTileLinkBridge.scala` 在 D-cache 关闭时使用。

行为：

- CPU cache-like request 转成单个 TL Get/PutPartialData。
- 保存 D response 后再通过 cache response 返回。
- invalidate 永远 ready。

这个 bridge 让 Core 上层不需要关心是否存在 D-cache。

## Store Buffer

`src/main/scala/core/pipeline/StoreBuffer.scala` 是 LSU 内部 4 entry FIFO。

用途：

- 非设备 store 可以先入队，后续 drain 到 cache。
- load 可以搜索 store buffer，若地址和 mask 完全覆盖则 forward 最新 store data。

搜索策略：

- 从 newest 到 oldest 查找。
- 命中条件：地址相同，且 buffer mask 覆盖 load mask。
- 使用 `PriorityEncoderOH` 选最新命中。

限制：

- 仅支持同 beat/同地址完全覆盖的 forwarding。
- 没有 store merging。
- device store 不入 store buffer。

## MemoryAccessStage

`src/main/scala/core/pipeline/MemoryAccessStage.scala` 是未来 MMU/PMP/attribute 统一入口。

当前已实现：

- ROM/SRAM/device 地址属性判断。
- PMP 8 entry 支持。
- TOR/NA4/NAPOT 匹配。
- M-mode locked PMP 权限限制。
- S/U-mode 未命中或权限不足 fault。

当前未实现：

- 页表 walk。
- TLB。
- `mxr/sum/mprv` 精细语义。
- instruction fetch side PMP/MMU 检查。

输出 `MemorySystemConfig` 来自 CSRFile：

- privilege level
- `mmu_en`
- `satp`
- `pmpcfg0`
- `pmpaddr[0..7]`
- `mxr/sum/mprv`

## Cache 与 fence/fence.i

`fence.i` 被 ALU 标记为 `MemOpType.FenceI`，Core 中转成 frontend barrier：

1. redirect 到 fence 后继 PC。
2. drain stale fetch。
3. 对 I-cache 发 whole-cache invalidate。
4. 等待 I-cache 维护完成 response。

之前曾出现 late cache response 阻塞 invalidation 的问题。当前 IF cache path 会在 flush 后继续让 response channel 可 drain，避免 I-cache 卡死。

普通 `fence` 被 ALU 标记为 `MemOpType.Fence`，LSU 会先等待 store buffer、MMIO、atomic 和 cache load 路径清空，再向 D-cache 发 whole-cache clean+invalidate。D-cache 关闭时，`UncachedTileLinkBridge` 对 maintenance request 直接返回 no-op ack。

当前 TileLink 仍是 TL-UL-like non-coherent 子集。SBA、后续 DMA/QSPI 等非 CPU master 写入 SRAM 后，软件或调试器需要让 hart 执行 `fence`/`fence.i`，或通过 Debug Module 的 IonSoC 私有 `IonCacheCtl` register 触发显式 cache maintenance，来保证 D-cache/I-cache 可见性。

`IonCacheCtl` 位于 Debug Module DMI 地址 `0x70`，不是 RISC-V Debug Spec 标准 register。写 bit 0 请求 D-cache whole-cache clean+invalidate，写 bit 1 请求 I-cache whole-cache invalidate；读 bit 8/9 获取 done sticky，bit 16/17 获取 error sticky，done/error sticky 均写 1 清除。Core 内部会在 debug 维护期间冻结流水线；I-cache 维护会等待已有 `fence.i` 或 fetch response drain 完成后再占用 cache CPU port。

## 后续优化方向

- 将 I-cache/D-cache line size 提升到多 beat，并支持 burst refill。
- 引入 set-associative cache 或 victim buffer。
- 将 debug cache maintenance 扩展成可按地址范围维护的接口，减少 whole-cache flush 成本。
- MMU 前先统一物理地址宽度和 TileLink address width。
- 为 LR/SC/AMO 加入 coherence-aware reservation invalidation。
- 对 firmware profile 增加 SRAM latency/cache miss 性能统计。
