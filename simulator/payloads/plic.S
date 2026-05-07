.section .text.init
.globl _start

.equ UART_BASE,       0x10010000
.equ PLIC_BASE,       0x0c000000
.equ PLIC_PRIORITY1,  0x0c000004
.equ PLIC_PENDING,    0x0c001000
.equ PLIC_ENABLE_M,   0x0c002000
.equ PLIC_THRESHOLD,  0x0c200000
.equ PLIC_CLAIM,      0x0c200004

_start:
    la   t0, trap_handler
    csrw mtvec, t0

    # Source 1 is driven by the Verilator harness for this integration test.
    li   t0, PLIC_PRIORITY1
    li   t1, 3
    sw   t1, 0(t0)

    li   t0, PLIC_ENABLE_M
    li   t1, 0x2
    sw   t1, 0(t0)

    li   t0, PLIC_THRESHOLD
    sw   zero, 0(t0)

    li   t0, 0x800        # MEIE
    csrw mie, t0
    li   t0, 0x8          # MIE
    csrs mstatus, t0

wait_external:
    j    wait_external

trap_handler:
    csrr t0, mcause
    # Build interrupt mcause explicitly. Some assemblers lower the large
    # immediate through 32-bit sign-extended steps, which would compare
    # against 0xffffffff8000000b instead of 0x800000000000000b.
    li   t1, 1
    slli t1, t1, 63
    ori  t1, t1, 11
    bne  t0, t1, fail

    li   t0, PLIC_CLAIM
    lw   t1, 0(t0)
    li   t2, 1
    bne  t1, t2, fail

    li   t3, UART_BASE
    li   t4, 'X'
    sb   t4, 0(t3)

    # Complete by writing the claimed source ID back.
    sw   t1, 0(t0)

    li   t4, 'P'
    sb   t4, 0(t3)
    li   a7, 93
    li   a0, 0
pass_spin:
    j    pass_spin

fail:
    li   t0, UART_BASE
    li   t1, 'F'
    sb   t1, 0(t0)
    li   a7, 93
    li   a0, 1
fail_spin:
    j    fail_spin
