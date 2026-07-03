WORD_LEN = 64
# COMPILER = riscv64-linux-gnu-
COMPILER = riscv64-unknown-elf-
CC = $(COMPILER)gcc
AS = $(COMPILER)as
LD = $(COMPILER)ld
OBJCOPY = $(COMPILER)objcopy
VERILATOR = verilator
OPENOCD ?= /opt/openocd/bin/openocd
NPROC ?= $(shell nproc 2>/dev/null || getconf _NPROCESSORS_ONLN 2>/dev/null || echo 1)
# Verilator trace instrumentation materially slows compile and bloats generated
# C++, so normal smoke/perf runs build without it. Use `TRACE=1` together with
# `ION_TRACE_WAVE=1` when a VCD is actually needed.
TRACE ?= 0
ifeq ($(TRACE),1)
VERILATOR_TRACE_FLAGS = --trace
VERILATOR_OBJ_SUFFIX = -trace
else
VERILATOR_TRACE_FLAGS =
VERILATOR_OBJ_SUFFIX =
endif
# The simulator harness still reads selected rootp internals for ROM loading
# and bring-up diagnostics. Keep those symbols public independently of VCD
# tracing, so default builds avoid trace code but retain harness visibility.
VERILATOR_PUBLIC_FLAGS = --public-flat-rw
PAYLOAD_MARCH = rv$(WORD_LEN)imazicsr
PAYLOAD_MABI = lp$(WORD_LEN)

SIMULATOR_DIR = simulator
BUILD_DIR = $(SIMULATOR_DIR)/build
SYSTEM_VERILOG_DIR = $(BUILD_DIR)/../../build/rtl
MCU_SYSTEM_VERILOG_DIR = $(BUILD_DIR)/../../build/rtl-mcu
ICACHE_SYSTEM_VERILOG_DIR = $(BUILD_DIR)/../../build/rtl-icache
FIRMWARE_SYSTEM_VERILOG_DIR = $(BUILD_DIR)/../../build/rtl-firmware
DIFFTEST_SYSTEM_VERILOG_DIR = $(BUILD_DIR)/../../build/rtl-difftest
PAYLOAD_BUILD_DIR = $(BUILD_DIR)/payload
VERILATOR_OBJ_DIR = $(BUILD_DIR)/obj$(VERILATOR_OBJ_SUFFIX)
MCU_VERILATOR_OBJ_DIR = $(BUILD_DIR)/obj-mcu$(VERILATOR_OBJ_SUFFIX)
ICACHE_VERILATOR_OBJ_DIR = $(BUILD_DIR)/obj-icache$(VERILATOR_OBJ_SUFFIX)
FIRMWARE_VERILATOR_OBJ_DIR = $(BUILD_DIR)/obj-firmware$(VERILATOR_OBJ_SUFFIX)
SIM_HARNESS_DIR = $(SIMULATOR_DIR)/harness
SIM_RTL_DIR = $(SIMULATOR_DIR)/rtl
PAYLOAD_SRC_DIR = $(SIMULATOR_DIR)/payloads
FIRMWARE_DIR = $(SIMULATOR_DIR)/firmware
RUSTSBI_DIR = $(FIRMWARE_DIR)/rustsbi
OPENSBI_DIR ?= $(FIRMWARE_DIR)/opensbi
FILE_LIST = $(SYSTEM_VERILOG_DIR)/filelist.f
MCU_FILE_LIST = $(MCU_SYSTEM_VERILOG_DIR)/filelist.f
ICACHE_FILE_LIST = $(ICACHE_SYSTEM_VERILOG_DIR)/filelist.f
FIRMWARE_FILE_LIST = $(FIRMWARE_SYSTEM_VERILOG_DIR)/filelist.f
DIFFTEST_FILE_LIST = $(DIFFTEST_SYSTEM_VERILOG_DIR)/filelist.f
RTL_STAMP = $(SYSTEM_VERILOG_DIR)/.generated.stamp
MCU_RTL_STAMP = $(MCU_SYSTEM_VERILOG_DIR)/.generated.stamp
ICACHE_RTL_STAMP = $(ICACHE_SYSTEM_VERILOG_DIR)/.generated.stamp
FIRMWARE_RTL_STAMP = $(FIRMWARE_SYSTEM_VERILOG_DIR)/.generated.stamp
DIFFTEST_RTL_STAMP = $(DIFFTEST_SYSTEM_VERILOG_DIR)/.generated.stamp
TB = $(SIM_HARNESS_DIR)/verilator_main.cpp
PAYLOAD_SRC ?= $(PAYLOAD_SRC_DIR)/timer.S
PAYLOAD_LDS = $(PAYLOAD_SRC_DIR)/payload.ld
FIRMWARE_LDS = $(PAYLOAD_SRC_DIR)/firmware.ld
FIRMWARE_BSWAP_LDS = $(PAYLOAD_SRC_DIR)/firmware_bswap.ld
SBI_PAYLOAD_LDS = $(PAYLOAD_SRC_DIR)/sbi_payload.ld
PAYLOAD = $(PAYLOAD_BUILD_DIR)/payload
PAYLOAD_ELF = $(PAYLOAD_BUILD_DIR)/payload.elf
PAYLOAD_ROM_LO_HEX = $(PAYLOAD_BUILD_DIR)/payload_rom_lo.hex
PAYLOAD_ROM_HI_HEX = $(PAYLOAD_BUILD_DIR)/payload_rom_hi.hex
PAYLOAD_SRAM_BIN = $(PAYLOAD_BUILD_DIR)/payload_sram.bin
PAYLOAD_SRAM_HEX = $(PAYLOAD_BUILD_DIR)/payload_sram.hex
FIRMWARE_TRAMPOLINE_ELF = $(PAYLOAD_BUILD_DIR)/firmware_trampoline.elf
TIMER_ELF = $(PAYLOAD_BUILD_DIR)/timer.elf
BASIC_ELF = $(PAYLOAD_BUILD_DIR)/basic.elf
CLINT32_ELF = $(PAYLOAD_BUILD_DIR)/clint32.elf
TLERROR_ELF = $(PAYLOAD_BUILD_DIR)/tlerror.elf
AMO_ELF = $(PAYLOAD_BUILD_DIR)/amo.elf
HAZARD_ELF = $(PAYLOAD_BUILD_DIR)/hazard.elf
PIPELINE_REISSUE_ELF = $(PAYLOAD_BUILD_DIR)/pipeline_reissue.elf
LOAD_STALL_BYPASS_ELF = $(PAYLOAD_BUILD_DIR)/load_stall_bypass.elf
BSWAP_ELF = $(PAYLOAD_BUILD_DIR)/bswap.elf
FIRMWARE_BSWAP_ELF = $(PAYLOAD_BUILD_DIR)/firmware_bswap.elf
LDADDR_ELF = $(PAYLOAD_BUILD_DIR)/ldaddr.elf
MISALIGN_LD_ELF = $(PAYLOAD_BUILD_DIR)/misalign_ld.elf
PERF_ELF = $(PAYLOAD_BUILD_DIR)/perf.elf
BITMANIP_ELF = $(PAYLOAD_BUILD_DIR)/bitmanip.elf
PLIC_ELF = $(PAYLOAD_BUILD_DIR)/plic.elf
PLIC_S_ELF = $(PAYLOAD_BUILD_DIR)/plic_s.elf
UART_IRQ_ELF = $(PAYLOAD_BUILD_DIR)/uart_irq.elf
SBI_SMOKE_ELF = $(PAYLOAD_BUILD_DIR)/sbi_smoke.elf
FIRMWARE_PROBE_ELF = $(PAYLOAD_BUILD_DIR)/firmware_probe.elf
VSOC_BIN = $(VERILATOR_OBJ_DIR)/VSoc
MCU_VSOC_BIN = $(MCU_VERILATOR_OBJ_DIR)/VSoc
ICACHE_VSOC_BIN = $(ICACHE_VERILATOR_OBJ_DIR)/VSoc
FIRMWARE_VSOC_BIN = $(FIRMWARE_VERILATOR_OBJ_DIR)/VSoc
IONSOC_DTB = $(BUILD_DIR)/ionsoc.dtb
RUSTSBI_TARGET_DIR = $(RUSTSBI_DIR)/target/riscv64gc-unknown-none-elf/release
RUSTSBI_FW_ELF = $(RUSTSBI_TARGET_DIR)/rustsbi-prototyper-jump.elf
RUSTSBI_CONFIG = prototyper/prototyper/config/ionsoc.toml
OPENSBI_FW_JUMP_ELF ?= $(OPENSBI_DIR)/build/platform/generic/firmware/fw_jump.elf
OPENSBI_PLATFORM ?= generic
OPENSBI_CROSS_COMPILE ?= $(COMPILER)
# Keep OpenSBI within the hardware ISA profile. Generic OpenSBI defaults to
# rv64gc on many toolchains, which pulls in F/D instructions that this MCU
# profile does not implement.
OPENSBI_PLATFORM_RISCV_ISA ?= rv64imac_zicsr_zifencei_zba_zbb_zbs
RTL_SCALA_SOURCES = $(shell find src/main/scala -name '*.scala') src/test/scala/sim.scala

RUN_ARGS := $(filter-out verilator verilator-jtag,$(MAKECMDGOALS))

NEMU_HOME = ${HOME}/NEMU
NOOP_HOME ?= $(CURDIR)
NEMU_SO ?= $(NEMU_HOME)/build/riscv64-nemu-interpreter-so
NEMU_DEFCONFIG ?= riscv64-ionsoc-ref_defconfig
NEMU_LOCAL_DEFCONFIG ?= $(SIMULATOR_DIR)/difftest/$(NEMU_DEFCONFIG)
DIFFTEST_MAX_CYCLES ?= 1000000
DIFFTEST_MAX_INSTR ?= 100000
DIFFTEST_BUILD_JOBS ?= $(NPROC)
DIFFTEST_SIM_VFLAGS ?= +define+DIFFTEST +define+ENABLE_INITIAL_MEM_
DIFFTEST_MAKE_ARGS = WITH_CHISELDB=0 WITH_CONSTANTIN=0 NO_ZSTD_COMPRESSION=1 \
	NEMU_HOME=$(NEMU_HOME) NOOP_HOME=$(NOOP_HOME) RTL_DIR=$(abspath $(DIFFTEST_SYSTEM_VERILOG_DIR)) \
	VERILATOR_BUILD_JOBS=$(DIFFTEST_BUILD_JOBS) SIM_VFLAGS="$(DIFFTEST_SIM_VFLAGS) $(SIM_VFLAGS)"
SIM_TOP = sim.TopMain

# Scala test groups are deliberately split so edit loops can target the block
# that changed instead of paying for every Chisel simulator suite.
SCALA_PROFILE_TESTS = config.ConfigSpec
SCALA_BUS_TESTS = bus.TLXbarSpec bus.TLSystemXbarSpec bus.TLCoherenceHubSpec
SCALA_CLINT_TESTS = device.CLINTSpec
SCALA_UART_TESTS = device.UartSpec
SCALA_PLIC_TESTS = device.PLICSpec
SCALA_DEBUG_TESTS = debug.DebugModuleSpec debug.JtagTapSpec
SCALA_DEVICE_TESTS = $(SCALA_CLINT_TESTS) device.TLDeviceSpec $(SCALA_UART_TESTS) $(SCALA_PLIC_TESTS)
SCALA_CACHE_TESTS = memory.L1CacheSpec
SCALA_CORE_FAST_TESTS = core.CSRFileSpec core.BranchPredictorSpec core.InstrFetchSpec core.InstrDecodeSpec core.ALUSpec core.StoreBufferSpec
SCALA_CORE_MEM_TESTS = core.LSUSpec
SCALA_FAST_TESTS = $(SCALA_PROFILE_TESTS) $(SCALA_BUS_TESTS) $(SCALA_DEVICE_TESTS) $(SCALA_CACHE_TESTS) core.CSRFileSpec core.InstrFetchSpec
SCALA_SLOW_TESTS = system.IonSoCSpec debug.JtagTapSpec

all: clean emu

emu: payload sim-verilog-difftest
	@$(MAKE) -C difftest emu $(DIFFTEST_MAKE_ARGS)

difftest-emu: sim-verilog-difftest
	@$(MAKE) -C difftest emu $(DIFFTEST_MAKE_ARGS)

# Build the NEMU shared-object reference used by OpenXiangShan DiffTest. The
# default config matches the expected output name in $(NEMU_SO).
nemu-so: $(NEMU_SO)

$(NEMU_SO): $(NEMU_LOCAL_DEFCONFIG)
	cp $(NEMU_LOCAL_DEFCONFIG) $(NEMU_HOME)/configs/$(NEMU_DEFCONFIG)
	NEMU_HOME=$(NEMU_HOME) $(MAKE) -C $(NEMU_HOME) $(NEMU_DEFCONFIG)
	NEMU_HOME=$(NEMU_HOME) $(MAKE) -C $(NEMU_HOME) -j$(NPROC)

difftest-run-payload: difftest-emu payload-rom-hex payload-sram-hex nemu-so
	$(NOOP_HOME)/build/emu --diff=$(NEMU_SO) --image=$(PAYLOAD_ELF) --max-instr=$(DIFFTEST_MAX_INSTR) --max-cycles=$(DIFFTEST_MAX_CYCLES) -- +ion_rom_lo=$(PAYLOAD_ROM_LO_HEX) +ion_rom_hi=$(PAYLOAD_ROM_HI_HEX)

$(RTL_STAMP): $(RTL_SCALA_SOURCES) build.mill
	mill -i IonSoC.test.runMain $(SIM_TOP)
	@touch $(RTL_STAMP)

sim-verilog: $(RTL_STAMP)

# The MCU RTL is the default platform contract made explicit: RV64IMAC(+small B
# subset), no MMU, CLINT+PLIC+UART, and default 64 KiB SRAM.
$(MCU_RTL_STAMP): $(RTL_SCALA_SOURCES) build.mill
	mill -i IonSoC.test.runMain sim.McuTopMain
	@touch $(MCU_RTL_STAMP)

sim-verilog-mcu: $(MCU_RTL_STAMP)

$(ICACHE_RTL_STAMP): $(RTL_SCALA_SOURCES) build.mill
	mill -i IonSoC.test.runMain sim.ICacheTopMain
	@touch $(ICACHE_RTL_STAMP)

sim-verilog-icache: $(ICACHE_RTL_STAMP)

$(FIRMWARE_RTL_STAMP): $(RTL_SCALA_SOURCES) build.mill
	mill -i IonSoC.test.runMain sim.FirmwareTopMain
	@touch $(FIRMWARE_RTL_STAMP)

sim-verilog-firmware: $(FIRMWARE_RTL_STAMP)

# DiffTest RTL generation emits the official OpenXiangShan probe wrappers and
# generated C++ state headers under build/generated-src. Keep it separate from
# normal Verilator flows so ordinary bring-up does not require NEMU/libdifftest.
$(DIFFTEST_RTL_STAMP): $(RTL_SCALA_SOURCES) build.mill
	NOOP_HOME=$(NOOP_HOME) mill -i IonSoC.test.runMain sim.DifftestTopMain
	@touch $(DIFFTEST_RTL_STAMP)

sim-verilog-difftest: $(DIFFTEST_RTL_STAMP)

test-fast:
	mill -i IonSoC.test.testOnly $(SCALA_FAST_TESTS)

test-profile:
	mill -i IonSoC.test.testOnly $(SCALA_PROFILE_TESTS)

test-bus:
	mill -i IonSoC.test.testOnly $(SCALA_BUS_TESTS)

test-devices:
	mill -i IonSoC.test.testOnly $(SCALA_DEVICE_TESTS)

test-clint:
	mill -i IonSoC.test.testOnly $(SCALA_CLINT_TESTS)

test-uart:
	mill -i IonSoC.test.testOnly $(SCALA_UART_TESTS)

test-plic:
	mill -i IonSoC.test.testOnly $(SCALA_PLIC_TESTS)

test-debug:
	mill -i IonSoC.test.testOnly $(SCALA_DEBUG_TESTS)

test-cache:
	mill -i IonSoC.test.testOnly $(SCALA_CACHE_TESTS)

test-core-fast:
	mill -i IonSoC.test.testOnly $(SCALA_CORE_FAST_TESTS)

test-core-mem:
	mill -i IonSoC.test.testOnly $(SCALA_CORE_MEM_TESTS)

test-slow:
	mill -i IonSoC.test.testOnly $(SCALA_SLOW_TESTS)

# help:
# 	mill -i IonSoC.test.runMain $(SIM_TOP) --help

payload:
	@mkdir -p $(PAYLOAD_BUILD_DIR)
	$(CC) -march=$(PAYLOAD_MARCH) -mabi=$(PAYLOAD_MABI) -nostdlib -nostartfiles -T$(PAYLOAD_LDS) -o $(PAYLOAD_ELF) $(PAYLOAD_SRC)
	$(OBJCOPY) -O binary --only-section=.text $(PAYLOAD_ELF) $(PAYLOAD)

payload-rom-hex: payload
	@mkdir -p $(PAYLOAD_BUILD_DIR)
	@od -An -v -tx4 -w4 $(PAYLOAD) | awk '{ print $$1 }' > $(PAYLOAD_ROM_LO_HEX)
	@cp $(PAYLOAD_ROM_LO_HEX) $(PAYLOAD_ROM_HI_HEX)

payload-sram-hex: payload
	@mkdir -p $(PAYLOAD_BUILD_DIR)
	$(OBJCOPY) -O binary --only-section=.data $(PAYLOAD_ELF) $(PAYLOAD_SRAM_BIN)
	@od -An -v -tx1 $(PAYLOAD_SRAM_BIN) | awk ' \
	    { for (i = 1; i <= NF; i++) bytes[n++] = $$i } \
	    END { \
	        for (w = 0; w < 8192; w++) { \
	            line = ""; \
	            for (b = 7; b >= 0; b--) { \
	                idx = w * 8 + b; \
	                line = line (idx in bytes ? bytes[idx] : "00"); \
	            } \
	            print line; \
	        } \
	    }' > $(PAYLOAD_SRAM_HEX)

$(TIMER_ELF): $(PAYLOAD_SRC_DIR)/timer.S $(PAYLOAD_LDS)
	@mkdir -p $(PAYLOAD_BUILD_DIR)
	$(CC) -march=$(PAYLOAD_MARCH) -mabi=$(PAYLOAD_MABI) -nostdlib -nostartfiles -T$(PAYLOAD_LDS) -o $@ $<

$(BASIC_ELF): $(PAYLOAD_SRC_DIR)/basic.S $(PAYLOAD_LDS)
	@mkdir -p $(PAYLOAD_BUILD_DIR)
	$(CC) -march=$(PAYLOAD_MARCH) -mabi=$(PAYLOAD_MABI) -nostdlib -nostartfiles -T$(PAYLOAD_LDS) -o $@ $<

$(CLINT32_ELF): $(PAYLOAD_SRC_DIR)/clint32.S $(PAYLOAD_LDS)
	@mkdir -p $(PAYLOAD_BUILD_DIR)
	$(CC) -march=$(PAYLOAD_MARCH) -mabi=$(PAYLOAD_MABI) -nostdlib -nostartfiles -T$(PAYLOAD_LDS) -o $@ $<

$(TLERROR_ELF): $(PAYLOAD_SRC_DIR)/tlerror.S $(PAYLOAD_LDS)
	@mkdir -p $(PAYLOAD_BUILD_DIR)
	$(CC) -march=$(PAYLOAD_MARCH) -mabi=$(PAYLOAD_MABI) -nostdlib -nostartfiles -T$(PAYLOAD_LDS) -o $@ $<

$(AMO_ELF): $(PAYLOAD_SRC_DIR)/amo.S $(PAYLOAD_LDS)
	@mkdir -p $(PAYLOAD_BUILD_DIR)
	$(CC) -march=$(PAYLOAD_MARCH) -mabi=$(PAYLOAD_MABI) -nostdlib -nostartfiles -T$(PAYLOAD_LDS) -o $@ $<

$(HAZARD_ELF): $(PAYLOAD_SRC_DIR)/hazard.S $(PAYLOAD_LDS)
	@mkdir -p $(PAYLOAD_BUILD_DIR)
	$(CC) -march=$(PAYLOAD_MARCH) -mabi=$(PAYLOAD_MABI) -nostdlib -nostartfiles -T$(PAYLOAD_LDS) -o $@ $<

$(PIPELINE_REISSUE_ELF): $(PAYLOAD_SRC_DIR)/pipeline_reissue.S $(PAYLOAD_LDS)
	@mkdir -p $(PAYLOAD_BUILD_DIR)
	$(CC) -march=rv$(WORD_LEN)imac_zicsr -mabi=$(PAYLOAD_MABI) -nostdlib -nostartfiles -T$(PAYLOAD_LDS) -o $@ $<

$(LOAD_STALL_BYPASS_ELF): $(PAYLOAD_SRC_DIR)/load_stall_bypass.S $(PAYLOAD_LDS)
	@mkdir -p $(PAYLOAD_BUILD_DIR)
	$(CC) -march=rv$(WORD_LEN)imac_zicsr -mabi=$(PAYLOAD_MABI) -nostdlib -nostartfiles -T$(PAYLOAD_LDS) -o $@ $<

$(BSWAP_ELF): $(PAYLOAD_SRC_DIR)/bswap.S $(PAYLOAD_LDS)
	@mkdir -p $(PAYLOAD_BUILD_DIR)
	$(CC) -march=rv$(WORD_LEN)imac_zicsr -mabi=$(PAYLOAD_MABI) -nostdlib -nostartfiles -T$(PAYLOAD_LDS) -o $@ $<

$(FIRMWARE_BSWAP_ELF): $(PAYLOAD_SRC_DIR)/bswap.S $(FIRMWARE_BSWAP_LDS)
	@mkdir -p $(PAYLOAD_BUILD_DIR)
	$(CC) -march=rv$(WORD_LEN)imac_zicsr -mabi=$(PAYLOAD_MABI) -nostdlib -nostartfiles -T$(FIRMWARE_BSWAP_LDS) -o $@ $<

$(LDADDR_ELF): $(PAYLOAD_SRC_DIR)/ldaddr.S $(PAYLOAD_LDS)
	@mkdir -p $(PAYLOAD_BUILD_DIR)
	$(CC) -march=$(PAYLOAD_MARCH) -mabi=$(PAYLOAD_MABI) -nostdlib -nostartfiles -T$(PAYLOAD_LDS) -o $@ $<

$(MISALIGN_LD_ELF): $(PAYLOAD_SRC_DIR)/misalign_ld.S $(PAYLOAD_LDS)
	@mkdir -p $(PAYLOAD_BUILD_DIR)
	$(CC) -march=$(PAYLOAD_MARCH) -mabi=$(PAYLOAD_MABI) -nostdlib -nostartfiles -T$(PAYLOAD_LDS) -o $@ $<

$(PERF_ELF): $(PAYLOAD_SRC_DIR)/perf.S $(PAYLOAD_LDS)
	@mkdir -p $(PAYLOAD_BUILD_DIR)
	$(CC) -march=$(PAYLOAD_MARCH) -mabi=$(PAYLOAD_MABI) -nostdlib -nostartfiles -T$(PAYLOAD_LDS) -o $@ $<

$(BITMANIP_ELF): $(PAYLOAD_SRC_DIR)/bitmanip.S $(PAYLOAD_LDS)
	@mkdir -p $(PAYLOAD_BUILD_DIR)
	$(CC) -march=rv$(WORD_LEN)imac_zba_zbb_zbs_zicsr -mabi=$(PAYLOAD_MABI) -nostdlib -nostartfiles -T$(PAYLOAD_LDS) -o $@ $<

$(PLIC_ELF): $(PAYLOAD_SRC_DIR)/plic.S $(PAYLOAD_LDS)
	@mkdir -p $(PAYLOAD_BUILD_DIR)
	$(CC) -march=$(PAYLOAD_MARCH) -mabi=$(PAYLOAD_MABI) -nostdlib -nostartfiles -T$(PAYLOAD_LDS) -o $@ $<

$(PLIC_S_ELF): $(PAYLOAD_SRC_DIR)/plic_s.S $(PAYLOAD_LDS)
	@mkdir -p $(PAYLOAD_BUILD_DIR)
	$(CC) -march=$(PAYLOAD_MARCH) -mabi=$(PAYLOAD_MABI) -nostdlib -nostartfiles -T$(PAYLOAD_LDS) -o $@ $<

$(UART_IRQ_ELF): $(PAYLOAD_SRC_DIR)/uart_irq.S $(PAYLOAD_LDS)
	@mkdir -p $(PAYLOAD_BUILD_DIR)
	$(CC) -march=$(PAYLOAD_MARCH) -mabi=$(PAYLOAD_MABI) -nostdlib -nostartfiles -T$(PAYLOAD_LDS) -o $@ $<

$(SBI_SMOKE_ELF): $(PAYLOAD_SRC_DIR)/sbi_smoke.S $(SBI_PAYLOAD_LDS)
	@mkdir -p $(PAYLOAD_BUILD_DIR)
	$(CC) -march=$(PAYLOAD_MARCH) -mabi=$(PAYLOAD_MABI) -nostdlib -nostartfiles -T$(SBI_PAYLOAD_LDS) -o $@ $<

$(FIRMWARE_PROBE_ELF): $(PAYLOAD_SRC_DIR)/firmware_probe.S $(FIRMWARE_LDS)
	@mkdir -p $(PAYLOAD_BUILD_DIR)
	$(CC) -march=$(PAYLOAD_MARCH) -mabi=$(PAYLOAD_MABI) -nostdlib -nostartfiles -T$(FIRMWARE_LDS) -o $@ $<

$(FIRMWARE_TRAMPOLINE_ELF): $(PAYLOAD_SRC_DIR)/firmware_trampoline.S
	@mkdir -p $(PAYLOAD_BUILD_DIR)
	$(CC) -march=$(PAYLOAD_MARCH) -mabi=$(PAYLOAD_MABI) -nostdlib -nostartfiles -T$(PAYLOAD_LDS) -o $@ $<

$(IONSOC_DTB): $(FIRMWARE_DIR)/ionsoc.dts
	@mkdir -p $(BUILD_DIR)
	dtc -I dts -O dtb -o $@ $<

$(RUSTSBI_FW_ELF): $(IONSOC_DTB) $(RUSTSBI_DIR)/$(RUSTSBI_CONFIG)
	cd $(RUSTSBI_DIR) && PROTOTYPER_LINK_START_ADDRESS=0x40000000 PROTOTYPER_PAYLOAD_START_ADDRESS=0x40100000 cargo prototyper --jump -c $(RUSTSBI_CONFIG) --fdt ../../build/ionsoc.dtb

$(OPENSBI_FW_JUMP_ELF):
	@if [ ! -d "$(OPENSBI_DIR)" ]; then \
		echo "OpenSBI source not found at $(OPENSBI_DIR)."; \
		echo "Clone or copy OpenSBI there, or run with OPENSBI_FW_JUMP_ELF=/path/to/fw_jump.elf."; \
		exit 1; \
	fi
	$(MAKE) -C $(OPENSBI_DIR) PLATFORM=$(OPENSBI_PLATFORM) CROSS_COMPILE=$(OPENSBI_CROSS_COMPILE) PLATFORM_RISCV_ISA=$(OPENSBI_PLATFORM_RISCV_ISA) FW_TEXT_START=0x40000000 FW_JUMP_ADDR=0x40100000 FW_OPTIONS=0

$(VSOC_BIN): $(RTL_STAMP) $(TB) $(FILE_LIST) $(SIM_RTL_DIR)/filelist.f Makefile
	$(VERILATOR) --cc -I$(SIM_RTL_DIR) -I$(SYSTEM_VERILOG_DIR) -f $(FILE_LIST) -f $(SIM_RTL_DIR)/filelist.f --exe $(TB) $(VERILATOR_PUBLIC_FLAGS) $(VERILATOR_TRACE_FLAGS) --Mdir $(VERILATOR_OBJ_DIR) --top-module SimTop --prefix VSoc
	@$(MAKE) -C $(VERILATOR_OBJ_DIR) -f VSoc.mk VSoc -j $(NPROC)

$(MCU_VSOC_BIN): $(MCU_RTL_STAMP) $(TB) $(MCU_FILE_LIST) $(SIM_RTL_DIR)/filelist.f Makefile
	$(VERILATOR) --cc -I$(SIM_RTL_DIR) -I$(MCU_SYSTEM_VERILOG_DIR) -f $(MCU_FILE_LIST) -f $(SIM_RTL_DIR)/filelist.f --exe $(TB) $(VERILATOR_PUBLIC_FLAGS) $(VERILATOR_TRACE_FLAGS) --Mdir $(MCU_VERILATOR_OBJ_DIR) --top-module SimTop --prefix VSoc
	@$(MAKE) -C $(MCU_VERILATOR_OBJ_DIR) -f VSoc.mk VSoc -j $(NPROC)

$(ICACHE_VSOC_BIN): $(ICACHE_RTL_STAMP) $(TB) $(ICACHE_FILE_LIST) $(SIM_RTL_DIR)/filelist.f Makefile
	$(VERILATOR) --cc -I$(SIM_RTL_DIR) -I$(ICACHE_SYSTEM_VERILOG_DIR) -f $(ICACHE_FILE_LIST) -f $(SIM_RTL_DIR)/filelist.f --exe $(TB) $(VERILATOR_PUBLIC_FLAGS) $(VERILATOR_TRACE_FLAGS) --Mdir $(ICACHE_VERILATOR_OBJ_DIR) --top-module SimTop --prefix VSoc
	@$(MAKE) -C $(ICACHE_VERILATOR_OBJ_DIR) -f VSoc.mk VSoc -j $(NPROC)

$(FIRMWARE_VSOC_BIN): $(FIRMWARE_RTL_STAMP) $(TB) $(FIRMWARE_FILE_LIST) $(SIM_RTL_DIR)/filelist.f Makefile
	$(VERILATOR) --cc -I$(SIM_RTL_DIR) -I$(FIRMWARE_SYSTEM_VERILOG_DIR) -f $(FIRMWARE_FILE_LIST) -f $(SIM_RTL_DIR)/filelist.f --exe $(TB) $(VERILATOR_PUBLIC_FLAGS) $(VERILATOR_TRACE_FLAGS) --Mdir $(FIRMWARE_VERILATOR_OBJ_DIR) --top-module SimTop --prefix VSoc
	@$(MAKE) -C $(FIRMWARE_VERILATOR_OBJ_DIR) -f VSoc.mk VSoc -j $(NPROC)

verilator-build: $(VSOC_BIN)

verilator-build-mcu: $(MCU_VSOC_BIN)

verilator-build-icache: $(ICACHE_VSOC_BIN)

verilator-build-firmware: $(FIRMWARE_VSOC_BIN)

verilator: payload $(VSOC_BIN)
	./$(VSOC_BIN) $(RUN_ARGS)

verilator-jtag: payload $(VSOC_BIN)
	env ION_JTAG_ONLY=1 ION_JTAG_RBB_PORT=$${ION_JTAG_RBB_PORT:-9824} ./$(VSOC_BIN) $(RUN_ARGS)

openocd-smoke: payload $(VSOC_BIN)
	@mkdir -p $(BUILD_DIR)
	@set -e; \
	port=$${ION_JTAG_RBB_PORT:-9824}; \
	log="$(BUILD_DIR)/openocd-smoke.log"; \
	simlog="$(BUILD_DIR)/verilator-jtag-smoke.log"; \
	env ION_JTAG_ONLY=1 ION_JTAG_RBB_PORT=$$port ION_MAX_CYCLES=0 ./$(VSOC_BIN) > $$simlog 2>&1 & \
	sim_pid=$$!; \
	trap 'kill $$sim_pid >/dev/null 2>&1 || true' EXIT; \
	for i in $$(seq 1 50); do \
		if grep -q "remote-bitbang listening" $$simlog; then break; fi; \
		sleep 0.1; \
	done; \
	$(OPENOCD) -s . -f openocd/ionsoc-rbb-smoke.cfg > $$log 2>&1; \
	kill $$sim_pid >/dev/null 2>&1 || true; \
	trap - EXIT; \
	grep -q "ION_OPENOCD_SBA_OK" $$log; \
	! grep -q "Unexpected error during fence" $$log; \
	echo "OpenOCD smoke passed. Logs: $$log $$simlog"

verilator-run-timer: $(TIMER_ELF) $(VSOC_BIN)
	./$(VSOC_BIN) --payload timer S!!P $(TIMER_ELF)

verilator-run-clint32: $(CLINT32_ELF) $(VSOC_BIN)
	./$(VSOC_BIN) --payload clint32 CP $(CLINT32_ELF)

verilator-run-tlerror: $(TLERROR_ELF) $(VSOC_BIN)
	./$(VSOC_BIN) --payload tlerror EP $(TLERROR_ELF)

verilator-run-amo: $(AMO_ELF) $(VSOC_BIN)
	./$(VSOC_BIN) --payload amo AP $(AMO_ELF)

verilator-run-hazard: $(HAZARD_ELF) $(VSOC_BIN)
	./$(VSOC_BIN) --payload hazard HP $(HAZARD_ELF)

verilator-run-pipeline-reissue: $(PIPELINE_REISSUE_ELF) $(VSOC_BIN)
	./$(VSOC_BIN) --payload pipeline_reissue RP $(PIPELINE_REISSUE_ELF)

verilator-run-load-stall-bypass: $(LOAD_STALL_BYPASS_ELF) $(VSOC_BIN)
	./$(VSOC_BIN) --payload load_stall_bypass BP $(LOAD_STALL_BYPASS_ELF)

verilator-run-bswap: $(BSWAP_ELF) $(VSOC_BIN)
	./$(VSOC_BIN) --payload bswap BP $(BSWAP_ELF)

verilator-run-firmware-bswap: $(FIRMWARE_BSWAP_ELF) $(FIRMWARE_VSOC_BIN)
	ION_SRAM_BASE=0x40000000 ION_SRAM_SIZE=0x01000000 ION_MAX_CYCLES=20000 ./$(FIRMWARE_VSOC_BIN) --payload-firmware bswap BP $(FIRMWARE_BSWAP_ELF)

verilator-run-ldaddr: $(LDADDR_ELF) $(VSOC_BIN)
	./$(VSOC_BIN) --payload ldaddr LP $(LDADDR_ELF)

verilator-run-misalign-ld: $(MISALIGN_LD_ELF) $(VSOC_BIN)
	./$(VSOC_BIN) --payload misalign_ld MP $(MISALIGN_LD_ELF)

verilator-run-perf: $(PERF_ELF) $(VSOC_BIN)
	ION_PERF=1 ION_MAX_CYCLES=2000000 ./$(VSOC_BIN) --payload perf P $(PERF_ELF)

verilator-run-bitmanip: $(BITMANIP_ELF) $(VSOC_BIN)
	./$(VSOC_BIN) --payload bitmanip BP $(BITMANIP_ELF)

verilator-run-plic: $(PLIC_ELF) $(VSOC_BIN)
	./$(VSOC_BIN) --payload plic XP $(PLIC_ELF)

verilator-run-plic-s: $(PLIC_S_ELF) $(VSOC_BIN)
	./$(VSOC_BIN) --payload plic_s SIP $(PLIC_S_ELF)

verilator-run-uart-irq: $(UART_IRQ_ELF) $(VSOC_BIN)
	ION_UART_RX_CYCLE=160 ION_UART_RX_BYTE=0x5a ./$(VSOC_BIN) --payload uart_irq UP $(UART_IRQ_ELF)

# Firmware profile smoke: ROM trampoline -> M-mode SBI firmware in SRAM -> S-mode payload.
# The harness checks UART output, exit sentinel, and that execution reached both
# firmware SRAM and the S-mode payload window.
verilator-run-rustsbi: $(RUSTSBI_FW_ELF) $(SBI_SMOKE_ELF) $(FIRMWARE_TRAMPOLINE_ELF) $(IONSOC_DTB) $(FIRMWARE_VSOC_BIN)
	ION_SRAM_BASE=0x40000000 ION_SRAM_SIZE=0x01000000 ION_DTB_ADDR=0x40f00000 ION_BOOT_A1=0x40f00000 ION_BOOT_A2=0x40100000 ION_MAX_CYCLES=8000000 ION_EXPECT_UART="IonSoC SBI smoke" ./$(FIRMWARE_VSOC_BIN) --rustsbi $(FIRMWARE_TRAMPOLINE_ELF) $(RUSTSBI_FW_ELF) $(SBI_SMOKE_ELF) $(IONSOC_DTB)

rustsbi-smoke: verilator-run-rustsbi

verilator-run-opensbi: $(OPENSBI_FW_JUMP_ELF) $(SBI_SMOKE_ELF) $(FIRMWARE_TRAMPOLINE_ELF) $(IONSOC_DTB) $(FIRMWARE_VSOC_BIN)
	ION_SRAM_BASE=0x40000000 ION_SRAM_SIZE=0x01000000 ION_DTB_ADDR=0x40f00000 ION_BOOT_A1=0x40f00000 ION_BOOT_A2=0x40100000 ION_MAX_CYCLES=12000000 ION_EXPECT_UART="IonSoC SBI smoke" ./$(FIRMWARE_VSOC_BIN) --sbi-firmware $(FIRMWARE_TRAMPOLINE_ELF) $(OPENSBI_FW_JUMP_ELF) $(SBI_SMOKE_ELF) $(IONSOC_DTB)

opensbi-smoke: verilator-run-opensbi

verilator-run-firmware-probe: $(FIRMWARE_PROBE_ELF) $(SBI_SMOKE_ELF) $(FIRMWARE_TRAMPOLINE_ELF) $(IONSOC_DTB) $(FIRMWARE_VSOC_BIN)
	ION_EXPECT_UART="IonSoC firmware probe" ION_TRACE_BOOT=1 ION_SRAM_BASE=0x40000000 ION_SRAM_SIZE=0x01000000 ION_DTB_ADDR=0x40f00000 ION_BOOT_A1=0x40f00000 ION_BOOT_A2=0x40100000 ION_MAX_CYCLES=200000 ./$(FIRMWARE_VSOC_BIN) --rustsbi $(FIRMWARE_TRAMPOLINE_ELF) $(FIRMWARE_PROBE_ELF) $(SBI_SMOKE_ELF) $(IONSOC_DTB)

verilator-clint32: verilator-run-clint32

verilator-tlerror: verilator-run-tlerror

regress: $(VSOC_BIN) $(TIMER_ELF) $(CLINT32_ELF) $(TLERROR_ELF) $(AMO_ELF) $(HAZARD_ELF) $(PIPELINE_REISSUE_ELF) $(BITMANIP_ELF) $(PLIC_ELF) $(PLIC_S_ELF) $(UART_IRQ_ELF)
	./$(VSOC_BIN) --payload timer S!!P $(TIMER_ELF)
	./$(VSOC_BIN) --payload clint32 CP $(CLINT32_ELF)
	./$(VSOC_BIN) --payload tlerror EP $(TLERROR_ELF)
	./$(VSOC_BIN) --payload amo AP $(AMO_ELF)
	./$(VSOC_BIN) --payload hazard HP $(HAZARD_ELF)
	./$(VSOC_BIN) --payload pipeline_reissue RP $(PIPELINE_REISSUE_ELF)
	./$(VSOC_BIN) --payload bitmanip BP $(BITMANIP_ELF)
	./$(VSOC_BIN) --payload plic XP $(PLIC_ELF)
	./$(VSOC_BIN) --payload plic_s SIP $(PLIC_S_ELF)
	ION_UART_RX_CYCLE=160 ION_UART_RX_BYTE=0x5a ./$(VSOC_BIN) --payload uart_irq UP $(UART_IRQ_ELF)

regress-mcu: $(MCU_VSOC_BIN) $(TIMER_ELF) $(CLINT32_ELF) $(TLERROR_ELF) $(AMO_ELF) $(HAZARD_ELF) $(PIPELINE_REISSUE_ELF) $(BITMANIP_ELF) $(PLIC_ELF) $(PLIC_S_ELF) $(UART_IRQ_ELF)
	./$(MCU_VSOC_BIN) --payload timer S!!P $(TIMER_ELF)
	./$(MCU_VSOC_BIN) --payload clint32 CP $(CLINT32_ELF)
	./$(MCU_VSOC_BIN) --payload tlerror EP $(TLERROR_ELF)
	./$(MCU_VSOC_BIN) --payload amo AP $(AMO_ELF)
	./$(MCU_VSOC_BIN) --payload hazard HP $(HAZARD_ELF)
	./$(MCU_VSOC_BIN) --payload pipeline_reissue RP $(PIPELINE_REISSUE_ELF)
	./$(MCU_VSOC_BIN) --payload bitmanip BP $(BITMANIP_ELF)
	./$(MCU_VSOC_BIN) --payload plic XP $(PLIC_ELF)
	./$(MCU_VSOC_BIN) --payload plic_s SIP $(PLIC_S_ELF)
	ION_UART_RX_CYCLE=160 ION_UART_RX_BYTE=0x5a ./$(MCU_VSOC_BIN) --payload uart_irq UP $(UART_IRQ_ELF)

regress-icache: $(ICACHE_VSOC_BIN) $(BASIC_ELF) $(TIMER_ELF) $(HAZARD_ELF)
	./$(ICACHE_VSOC_BIN) --payload basic "Hello, World!" $(BASIC_ELF)
	./$(ICACHE_VSOC_BIN) --payload timer S!!P $(TIMER_ELF)
	./$(ICACHE_VSOC_BIN) --payload hazard HP $(HAZARD_ELF)

regress-icache-basic: $(ICACHE_VSOC_BIN) $(BASIC_ELF)
	./$(ICACHE_VSOC_BIN) --payload basic "Hello, World!" $(BASIC_ELF)

regress-icache-hazard: $(ICACHE_VSOC_BIN) $(HAZARD_ELF)
	./$(ICACHE_VSOC_BIN) --payload hazard HP $(HAZARD_ELF)

gtkwave:
	gtkwave $(BUILD_DIR)/wave.vcd

clean:
	@$(MAKE) -C difftest clean NEMU_HOME=$(NEMU_HOME) NOOP_HOME=$(NOOP_HOME)
	mill -i clean
	rm -rf $(PAYLOAD_BUILD_DIR)/*
# 	rm -rf $(SYSTEM_VERILOG_DIR)/*

ifneq ($(filter verilator verilator-jtag,$(MAKECMDGOALS)),)
$(RUN_ARGS):
	@:
endif
