label wait_loop
dec r2
cmp r2, r0
jnz wait_loop
ret
