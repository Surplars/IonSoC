.section .text.init
.globl _start

_start:
	addi t0, x0, 1

# _start:
#     la t0, payload_data
#     addi x9, t0, 14
#     li t2, 0x30000000
#     li x10, 0
# put:
#     lb t1, 0(t0)
#     sb t1, 0(t2)
#     addi t0, t0, 1
#     bne t0, x9, put

# pass:
#     li s10, 1
#     li s11, 1
#     j pass

# fail:
#     li s10, 1
#     li s11, 0
#     j fail

# .section .data
# .align 2
# payload_data:
#     .ascii "Hello, World!\n"
