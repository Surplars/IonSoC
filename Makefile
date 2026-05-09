WORD_LEN = 64
# COMPILER = riscv64-linux-gnu-
COMPILER = riscv64-unknown-elf-
CC = $(COMPILER)gcc
AS = $(COMPILER)as
LD = $(COMPILER)ld
OBJCOPY = $(COMPILER)objcopy
VERILATOR = verilator
PAYLOAD_MARCH = rv$(WORD_LEN)imazicsr
PAYLOAD_MABI = lp$(WORD_LEN)

SIMULATOR_DIR = simulator
BUILD_DIR = $(SIMULATOR_DIR)/build
SYSTEM_VERILOG_DIR = $(BUILD_DIR)/../../build/rtl
ICACHE_SYSTEM_VERILOG_DIR = $(BUILD_DIR)/../../build/rtl-icache
FIRMWARE_SYSTEM_VERILOG_DIR = $(BUILD_DIR)/../../build/rtl-firmware
PAYLOAD_BUILD_DIR = $(BUILD_DIR)/payload
VERILATOR_OBJ_DIR = $(BUILD_DIR)/obj
ICACHE_VERILATOR_OBJ_DIR = $(BUILD_DIR)/obj-icache
FIRMWARE_VERILATOR_OBJ_DIR = $(BUILD_DIR)/obj-firmware
SIM_HARNESS_DIR = $(SIMULATOR_DIR)/harness
SIM_RTL_DIR = $(SIMULATOR_DIR)/rtl
PAYLOAD_SRC_DIR = $(SIMULATOR_DIR)/payloads
FIRMWARE_DIR = $(SIMULATOR_DIR)/firmware
RUSTSBI_DIR = $(FIRMWARE_DIR)/rustsbi
FILE_LIST = $(SYSTEM_VERILOG_DIR)/filelist.f
ICACHE_FILE_LIST = $(ICACHE_SYSTEM_VERILOG_DIR)/filelist.f
FIRMWARE_FILE_LIST = $(FIRMWARE_SYSTEM_VERILOG_DIR)/filelist.f
RTL_STAMP = $(SYSTEM_VERILOG_DIR)/.generated.stamp
ICACHE_RTL_STAMP = $(ICACHE_SYSTEM_VERILOG_DIR)/.generated.stamp
FIRMWARE_RTL_STAMP = $(FIRMWARE_SYSTEM_VERILOG_DIR)/.generated.stamp
TB = $(SIM_HARNESS_DIR)/verilator_main.cpp
PAYLOAD_SRC ?= $(PAYLOAD_SRC_DIR)/timer.S
PAYLOAD_LDS = $(PAYLOAD_SRC_DIR)/payload.ld
FIRMWARE_LDS = $(PAYLOAD_SRC_DIR)/firmware.ld
SBI_PAYLOAD_LDS = $(PAYLOAD_SRC_DIR)/sbi_payload.ld
PAYLOAD = $(PAYLOAD_BUILD_DIR)/payload
FIRMWARE_TRAMPOLINE_ELF = $(PAYLOAD_BUILD_DIR)/firmware_trampoline.elf
TIMER_ELF = $(PAYLOAD_BUILD_DIR)/timer.elf
BASIC_ELF = $(PAYLOAD_BUILD_DIR)/basic.elf
CLINT32_ELF = $(PAYLOAD_BUILD_DIR)/clint32.elf
TLERROR_ELF = $(PAYLOAD_BUILD_DIR)/tlerror.elf
AMO_ELF = $(PAYLOAD_BUILD_DIR)/amo.elf
HAZARD_ELF = $(PAYLOAD_BUILD_DIR)/hazard.elf
PLIC_ELF = $(PAYLOAD_BUILD_DIR)/plic.elf
PLIC_S_ELF = $(PAYLOAD_BUILD_DIR)/plic_s.elf
SBI_SMOKE_ELF = $(PAYLOAD_BUILD_DIR)/sbi_smoke.elf
FIRMWARE_PROBE_ELF = $(PAYLOAD_BUILD_DIR)/firmware_probe.elf
VSOC_BIN = $(VERILATOR_OBJ_DIR)/VSoc
ICACHE_VSOC_BIN = $(ICACHE_VERILATOR_OBJ_DIR)/VSoc
FIRMWARE_VSOC_BIN = $(FIRMWARE_VERILATOR_OBJ_DIR)/VSoc
IONSOC_DTB = $(BUILD_DIR)/ionsoc.dtb
RUSTSBI_TARGET_DIR = $(RUSTSBI_DIR)/target/riscv64gc-unknown-none-elf/release
RUSTSBI_FW_ELF = $(RUSTSBI_TARGET_DIR)/rustsbi-prototyper-jump.elf
RUSTSBI_CONFIG = prototyper/prototyper/config/ionsoc.toml
RTL_SCALA_SOURCES = $(shell find src/main/scala -name '*.scala') src/test/scala/sim.scala

RUN_ARGS := $(filter-out verilator verilator-jtag,$(MAKECMDGOALS))

NEMU_HOME = ${HOME}/NEMU
NOOP_HOME = ${HOME}/IonSoC
SIM_TOP = sim.TopMain

all: clean emu

emu: payload sim-verilog
	@$(MAKE) -C difftest emu WITH_CHISELDB=0 WITH_CONSTANTIN=0 NEMU_HOME=$(NEMU_HOME) NOOP_HOME=$(NOOP_HOME)

$(RTL_STAMP): $(RTL_SCALA_SOURCES) build.mill
	mill -i IonSoC.test.runMain $(SIM_TOP)
	@touch $(RTL_STAMP)

sim-verilog: $(RTL_STAMP)

$(ICACHE_RTL_STAMP): $(RTL_SCALA_SOURCES) build.mill
	mill -i IonSoC.test.runMain sim.ICacheTopMain
	@touch $(ICACHE_RTL_STAMP)

sim-verilog-icache: $(ICACHE_RTL_STAMP)

$(FIRMWARE_RTL_STAMP): $(RTL_SCALA_SOURCES) build.mill
	mill -i IonSoC.test.runMain sim.FirmwareTopMain
	@touch $(FIRMWARE_RTL_STAMP)

sim-verilog-firmware: $(FIRMWARE_RTL_STAMP)

# help:
# 	mill -i IonSoC.test.runMain $(SIM_TOP) --help

payload:
	@mkdir -p $(PAYLOAD_BUILD_DIR)
	$(CC) -march=$(PAYLOAD_MARCH) -mabi=$(PAYLOAD_MABI) -nostdlib -nostartfiles -T$(PAYLOAD_LDS) -o $(PAYLOAD_BUILD_DIR)/payload.elf $(PAYLOAD_SRC)
	$(OBJCOPY) -O binary $(PAYLOAD_BUILD_DIR)/payload.elf $(PAYLOAD)

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

$(PLIC_ELF): $(PAYLOAD_SRC_DIR)/plic.S $(PAYLOAD_LDS)
	@mkdir -p $(PAYLOAD_BUILD_DIR)
	$(CC) -march=$(PAYLOAD_MARCH) -mabi=$(PAYLOAD_MABI) -nostdlib -nostartfiles -T$(PAYLOAD_LDS) -o $@ $<

$(PLIC_S_ELF): $(PAYLOAD_SRC_DIR)/plic_s.S $(PAYLOAD_LDS)
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

$(VSOC_BIN): $(RTL_STAMP) $(TB) $(FILE_LIST) $(SIM_RTL_DIR)/filelist.f
	$(VERILATOR) --cc -I$(SIM_RTL_DIR) -I$(SYSTEM_VERILOG_DIR) -f $(FILE_LIST) -f $(SIM_RTL_DIR)/filelist.f --exe $(TB) --trace --Mdir $(VERILATOR_OBJ_DIR) --top-module SimTop --prefix VSoc
	@$(MAKE) -C $(VERILATOR_OBJ_DIR) -f VSoc.mk VSoc -j 15

$(ICACHE_VSOC_BIN): $(ICACHE_RTL_STAMP) $(TB) $(ICACHE_FILE_LIST) $(SIM_RTL_DIR)/filelist.f
	$(VERILATOR) --cc -I$(SIM_RTL_DIR) -I$(ICACHE_SYSTEM_VERILOG_DIR) -f $(ICACHE_FILE_LIST) -f $(SIM_RTL_DIR)/filelist.f --exe $(TB) --trace --Mdir $(ICACHE_VERILATOR_OBJ_DIR) --top-module SimTop --prefix VSoc
	@$(MAKE) -C $(ICACHE_VERILATOR_OBJ_DIR) -f VSoc.mk VSoc -j 15

$(FIRMWARE_VSOC_BIN): $(FIRMWARE_RTL_STAMP) $(TB) $(FIRMWARE_FILE_LIST) $(SIM_RTL_DIR)/filelist.f
	$(VERILATOR) --cc -I$(SIM_RTL_DIR) -I$(FIRMWARE_SYSTEM_VERILOG_DIR) -f $(FIRMWARE_FILE_LIST) -f $(SIM_RTL_DIR)/filelist.f --exe $(TB) --trace --Mdir $(FIRMWARE_VERILATOR_OBJ_DIR) --top-module SimTop --prefix VSoc
	@$(MAKE) -C $(FIRMWARE_VERILATOR_OBJ_DIR) -f VSoc.mk VSoc -j 15

verilator-build: $(VSOC_BIN)

verilator-build-icache: $(ICACHE_VSOC_BIN)

verilator-build-firmware: $(FIRMWARE_VSOC_BIN)

verilator: payload $(VSOC_BIN)
	./$(VSOC_BIN) $(RUN_ARGS)

verilator-jtag: payload $(VSOC_BIN)
	env ION_JTAG_ONLY=1 ION_JTAG_RBB_PORT=$${ION_JTAG_RBB_PORT:-9824} ./$(VSOC_BIN) $(RUN_ARGS)

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

verilator-run-plic: $(PLIC_ELF) $(VSOC_BIN)
	./$(VSOC_BIN) --payload plic XP $(PLIC_ELF)

verilator-run-plic-s: $(PLIC_S_ELF) $(VSOC_BIN)
	./$(VSOC_BIN) --payload plic_s SIP $(PLIC_S_ELF)

verilator-run-rustsbi: $(RUSTSBI_FW_ELF) $(SBI_SMOKE_ELF) $(FIRMWARE_TRAMPOLINE_ELF) $(IONSOC_DTB) $(FIRMWARE_VSOC_BIN)
	ION_SRAM_BASE=0x40000000 ION_SRAM_SIZE=0x01000000 ION_DTB_ADDR=0x40f00000 ION_BOOT_A1=0x40f00000 ION_BOOT_A2=0x40100000 ION_MAX_CYCLES=8000000 ./$(FIRMWARE_VSOC_BIN) --rustsbi $(FIRMWARE_TRAMPOLINE_ELF) $(RUSTSBI_FW_ELF) $(SBI_SMOKE_ELF) $(IONSOC_DTB)

verilator-run-firmware-probe: $(FIRMWARE_PROBE_ELF) $(SBI_SMOKE_ELF) $(FIRMWARE_TRAMPOLINE_ELF) $(IONSOC_DTB) $(FIRMWARE_VSOC_BIN)
	ION_EXPECT_UART="IonSoC firmware probe" ION_TRACE_BOOT=1 ION_SRAM_BASE=0x40000000 ION_SRAM_SIZE=0x01000000 ION_DTB_ADDR=0x40f00000 ION_BOOT_A1=0x40f00000 ION_BOOT_A2=0x40100000 ION_MAX_CYCLES=200000 ./$(FIRMWARE_VSOC_BIN) --rustsbi $(FIRMWARE_TRAMPOLINE_ELF) $(FIRMWARE_PROBE_ELF) $(SBI_SMOKE_ELF) $(IONSOC_DTB)

verilator-clint32: verilator-run-clint32

verilator-tlerror: verilator-run-tlerror

regress: $(VSOC_BIN) $(TIMER_ELF) $(CLINT32_ELF) $(TLERROR_ELF) $(AMO_ELF) $(HAZARD_ELF) $(PLIC_ELF) $(PLIC_S_ELF)
	./$(VSOC_BIN) --payload timer S!!P $(TIMER_ELF)
	./$(VSOC_BIN) --payload clint32 CP $(CLINT32_ELF)
	./$(VSOC_BIN) --payload tlerror EP $(TLERROR_ELF)
	./$(VSOC_BIN) --payload amo AP $(AMO_ELF)
	./$(VSOC_BIN) --payload hazard HP $(HAZARD_ELF)
	./$(VSOC_BIN) --payload plic XP $(PLIC_ELF)
	./$(VSOC_BIN) --payload plic_s SIP $(PLIC_S_ELF)

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
