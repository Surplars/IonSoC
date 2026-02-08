WORD_LEN = 64
COMPILER = riscv64-unknown-elf-
CC = $(COMPILER)gcc
AS = $(COMPILER)as
LD = $(COMPILER)ld
OBJCOPY = $(COMPILER)objcopy
VERILATOR = verilator

SIMULATOR_DIR = simulator
BUILD_DIR = $(SIMULATOR_DIR)/build
SYSTEM_VERILOG_DIR = $(BUILD_DIR)/sv
PAYLOAD_BUILD_DIR = $(BUILD_DIR)/payload
VERILATOR_OBJ_DIR = $(BUILD_DIR)/obj
FILE_LIST = $(SYSTEM_VERILOG_DIR)/filelist.f
TB = $(SIMULATOR_DIR)/test.cpp
PAYLOAD_SRC = $(SIMULATOR_DIR)/payload.S
PAYLOAD_LDS = $(SIMULATOR_DIR)/payload.ld
PAYLOAD = $(PAYLOAD_BUILD_DIR)/payload

RUN_ARGS := $(filter-out verilator,$(MAKECMDGOALS))

NEMU_HOME = ${HOME}/NEMU
NOOP_HOME = ${HOME}/IonSoC
SIM_TOP = sim.TopMain

all: clean emu

emu: payload sim-verilog
	@$(MAKE) -C difftest emu WITH_CHISELDB=0 WITH_CONSTANTIN=0 NEMU_HOME=$(NEMU_HOME) NOOP_HOME=$(NOOP_HOME)

sim-verilog:
	mill -i IonSoC.test.runMain $(SIM_TOP)

# help:
# 	mill -i IonSoC.test.runMain $(SIM_TOP) --help

payload:
	$(CC) -march=rv$(WORD_LEN)imd -mabi=lp$(WORD_LEN) -nostdlib -nostartfiles -T$(PAYLOAD_LDS) -o $(PAYLOAD_BUILD_DIR)/payload.elf $(PAYLOAD_SRC)
	$(OBJCOPY) -O binary $(PAYLOAD_BUILD_DIR)/payload.elf $(PAYLOAD)

verilator: payload
	$(VERILATOR) --cc -I$(SIMULATOR_DIR)/sv_module -I$(SYSTEM_VERILOG_DIR) -f $(FILE_LIST) -f $(SIMULATOR_DIR)/sv_module/filelist.f --exe $(TB) --trace --Mdir $(VERILATOR_OBJ_DIR) --top-module SoC --prefix VSoc
	@$(MAKE) -C $(VERILATOR_OBJ_DIR) -f VSoc.mk VSoc
	./$(VERILATOR_OBJ_DIR)/VSoc $(RUN_ARGS)

gtkwave:
	gtkwave $(BUILD_DIR)/wave.vcd

clean:
	@$(MAKE) -C difftest clean NEMU_HOME=$(NEMU_HOME) NOOP_HOME=$(NOOP_HOME)
	mill -i clean
	rm -rf $(PAYLOAD_BUILD_DIR)/*
	rm -rf $(SYSTEM_VERILOG_DIR)/*

ifeq ($(filter verilator,$(MAKECMDGOALS)),verilator)
$(RUN_ARGS):
	@:
endif

