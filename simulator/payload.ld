OUTPUT_ARCH("riscv")
OUTPUT_FORMAT("elf64-littleriscv")
ENTRY(_start)

MEMORY {
    ROM (rx)   : ORIGIN = 0x00000000, LENGTH = 0x1000
    SRAM (rwx) : ORIGIN = 0x1000, LENGTH = 0x4000
    /* SRAM (rwx) : ORIGIN = 0x20000000, LENGTH = 16K */
}

SECTIONS {
    .text : {
        *(.text*)
        *(.rodata*)
    } > ROM

    .data : {
        *(.data*)
    } > SRAM

    .bss : {
        *(.bss*)
        *(COMMON)
    } > SRAM
    
}


