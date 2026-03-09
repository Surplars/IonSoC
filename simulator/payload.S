.section .text.init
.globl _start

_start:
#     li      gp, 7

# 	la t0, trap_vector
# 	csrw   mtvec, t0

#     csrrw   x0, mscratch, x0    # write 0, discard old

#     addi    t0, t0, 0x12
#     csrrw   t1, mscratch, t0    # t1 = old (0), mscratch = 0x12
# 	csrrw   t2, mscratch, x0    # t2 = old (0x12), mscratch = 0
# 	sw gp, 0(t0)
#     bne     t2, t0, fail


	li gp, 11
	li t0, 0x10000000
	li t1, 0x10001000
	li t3, 0x1234
	li t4, 0x56

	sb t3, 0(t0)
	lb t2, 0(t0)
	andi t6, t3, 0xff
	bne t2, t6, fail

	sb t4, 0(t1)
	lb t5, 0(t1)
	bne t5, t4, fail


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

pass:
    li a7, 93
    li a0, 0
    j pass

fail:
	slli gp, gp, 1
	ori gp, gp, 1
    li a7, 93
    mv a0, gp
    j fail

trap_vector:
	j pass

# .section .data
# .align 2
# payload_data:
#     .ascii "Hello, World!\n"
