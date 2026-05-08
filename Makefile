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
PAYLOAD_BUILD_DIR = $(BUILD_DIR)/payload
VERILATOR_OBJ_DIR = $(BUILD_DIR)/obj
ICACHE_VERILATOR_OBJ_DIR = $(BUILD_DIR)/obj-icache
SIM_HARNESS_DIR = $(SIMULATOR_DIR)/harness
SIM_RTL_DIR = $(SIMULATOR_DIR)/rtl
PAYLOAD_SRC_DIR = $(SIMULATOR_DIR)/payloads
FILE_LIST = $(SYSTEM_VERILOG_DIR)/filelist.f
RTL_STAMP = $(SYSTEM_VERILOG_DIR)/.generated.stamp
ICACHE_RTL_STAMP = $(SYSTEM_VERILOG_DIR)/.generated-icache.stamp
TB = $(SIM_HARNESS_DIR)/verilator_main.cpp
PAYLOAD_SRC ?= $(PAYLOAD_SRC_DIR)/timer.S
PAYLOAD_LDS = $(PAYLOAD_SRC_DIR)/payload.ld
PAYLOAD = $(PAYLOAD_BUILD_DIR)/payload
TIMER_ELF = $(PAYLOAD_BUILD_DIR)/timer.elf
BASIC_ELF = $(PAYLOAD_BUILD_DIR)/basic.elf
CLINT32_ELF = $(PAYLOAD_BUILD_DIR)/clint32.elf
TLERROR_ELF = $(PAYLOAD_BUILD_DIR)/tlerror.elf
AMO_ELF = $(PAYLOAD_BUILD_DIR)/amo.elf
HAZARD_ELF = $(PAYLOAD_BUILD_DIR)/hazard.elf
PLIC_ELF = $(PAYLOAD_BUILD_DIR)/plic.elf
PLIC_S_ELF = $(PAYLOAD_BUILD_DIR)/plic_s.elf
VSOC_BIN = $(VERILATOR_OBJ_DIR)/VSoc
ICACHE_VSOC_BIN = $(ICACHE_VERILATOR_OBJ_DIR)/VSoc
RTL_SCALA_SOURCES = $(shell find src/main/scala -name '*.scala') src/test/scala/sim.scala

RUN_ARGS := $(filter-out verilator,$(MAKECMDGOALS))

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

$(VSOC_BIN): $(RTL_STAMP) $(TB) $(FILE_LIST) $(SIM_RTL_DIR)/filelist.f
	$(VERILATOR) --cc -I$(SIM_RTL_DIR) -I$(SYSTEM_VERILOG_DIR) -f $(FILE_LIST) -f $(SIM_RTL_DIR)/filelist.f --exe $(TB) --trace --Mdir $(VERILATOR_OBJ_DIR) --top-module SimTop --prefix VSoc
	@$(MAKE) -C $(VERILATOR_OBJ_DIR) -f VSoc.mk VSoc -j 15

$(ICACHE_VSOC_BIN): $(ICACHE_RTL_STAMP) $(TB) $(FILE_LIST) $(SIM_RTL_DIR)/filelist.f
	$(VERILATOR) --cc -I$(SIM_RTL_DIR) -I$(SYSTEM_VERILOG_DIR) -f $(FILE_LIST) -f $(SIM_RTL_DIR)/filelist.f --exe $(TB) --trace --Mdir $(ICACHE_VERILATOR_OBJ_DIR) --top-module SimTop --prefix VSoc
	@$(MAKE) -C $(ICACHE_VERILATOR_OBJ_DIR) -f VSoc.mk VSoc -j 15

verilator-build: $(VSOC_BIN)

verilator-build-icache: $(ICACHE_VSOC_BIN)

verilator: payload $(VSOC_BIN)
	./$(VSOC_BIN) $(RUN_ARGS)

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

regress-icache: $(ICACHE_VSOC_BIN) $(TIMER_ELF)
	./$(ICACHE_VSOC_BIN) --payload timer S!!P $(TIMER_ELF)

regress-icache-basic: $(ICACHE_VSOC_BIN) $(BASIC_ELF)
	./$(ICACHE_VSOC_BIN) --payload basic "Hello, World!" $(BASIC_ELF)

gtkwave:
	gtkwave $(BUILD_DIR)/wave.vcd

clean:
	@$(MAKE) -C difftest clean NEMU_HOME=$(NEMU_HOME) NOOP_HOME=$(NOOP_HOME)
	mill -i clean
	rm -rf $(PAYLOAD_BUILD_DIR)/*
# 	rm -rf $(SYSTEM_VERILOG_DIR)/*

ifeq ($(filter verilator,$(MAKECMDGOALS)),verilator)
$(RUN_ARGS):
	@:
endif
