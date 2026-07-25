import "lib/util.asm" as util
lim r0, 0
lim r2, 5
call r0
jmp util::wait_loop
