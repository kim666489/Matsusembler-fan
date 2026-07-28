# Matsusembler FanMade (CPU16 Assembler)

A Java assembler for a new 16-bit CPU, adapted from the original `fgcompiler`
(compiler for a stack-based VM). It follows the opcode/encoding spec provided
(`L1IsSequences`-style opcode sheet) 100% — every instruction's bit pattern
has been checked against the original table (see [Verification](#verification)).

## What was kept from the original compiler vs. rewritten

| Part | Status | Reason |
|---|---|---|
| `ModuleLoader` (the `import "..." as alias` system, circular-import guarding, label aliasing) | **Kept almost entirely** | Pure source-text-level logic, not tied to the ISA |
| `Normalizer` (comment stripping, lexer) | **Lightly modified** | Removed the "BYTE" token type, since CPU16 has no byte literal separate from int — every `0x..` is a 16-bit int; added support for comma as a separator (`ADD r0, r1`) |
| `Parser` (token → bytecode converter) | **Rewritten entirely** | The instruction set, registers, and bit encoding are completely different from before |
| `storage "name" size`, syscall table, `r/a/s`-style registers (33 registers), `FGV2` header | **Removed** | Not present in the new ISA (CPU16 shares RAM with a plain register file; I/O goes through `IN`/`OUT` ports instead of syscalls) |
| `config.txt` / `ConfigReader` | **Removed** | The original config file wasn't included and isn't related to the ISA; paths are now taken directly from CLI arguments instead (can be added back later if needed) |

## CPU16 ISA

The CPU has 16 registers (`R0`-`R15`), all 16 bits wide, with `R15` as the
Stack Pointer (SP). All registers are mapped into RAM at addresses 0-15
(i.e. `R0` sits at RAM address 0, through `R15` at address 15). Every
instruction is 16 bits wide and takes either 1 or 2 words (instructions with
a separate address/immediate always take 2 words).

### Jump family (2 words: opcode + address)

| Mnemonic | Meaning |
|---|---|
| `JMP addr` | Unconditional jump |
| `JS addr` / `JNS addr` | Jump if Sign flag set / not set |
| `JV addr` / `JNV addr` | Jump if Overflow flag set / not set |
| `JC addr` / `JNC addr` | Jump if Carry flag set / not set |
| `JN addr` / `JNN addr` | Jump if bit15 of the result is set / not set |
| `JZ addr` / `JNZ addr` | Jump if the result is 0 / not 0 |

`addr` can be given as a literal number or a label name (the assembler
automatically resolves it to that label's word address).

### ALU / register ops (1 word)

| Mnemonic | Meaning |
|---|---|
| `AND Ra, Rb` | `Ra = Ra AND Rb` |
| `OR Ra, Rb` | `Ra = Ra OR Rb` |
| `ADD Ra, Rb` | `Ra = Ra + Rb` |
| `SUB Ra, Rb` | `Ra = Ra - Rb` |
| `XOR Ra, Rb` | `Ra = Ra XOR Rb` |
| `NOT Ra` | `Ra = NOT Ra` |
| `CPY Ra, Rb` | `Ra = Rb` |
| `INC Ra` | `Ra++` |
| `DEC Ra` | `Ra--` |
| `CMP Ra, Rb` | Sets flags from `Ra - Rb` without storing the result |

### Immediate / memory (LIM/STORE/LOAD take 2 words, the rest take 1 word)

| Mnemonic | Meaning |
|---|---|
| `LIM Ra, value` | `Ra = value` (loads a 16-bit constant directly) |
| `STORE Ra, addr` | `RAM[addr] = Ra` |
| `LOAD Ra, addr` | `Ra = RAM[addr]` |

### Stack / call (1 word)

| Mnemonic | Meaning |
|---|---|
| `PUSH Ra` | `RAM[SP] = Ra`, then `SP++` |
| `POP Ra` | `SP--`, then `Ra = RAM[SP]` |
| `CALL Ra` | Calls the function at the address held in `Ra` (indirect call through a register) |
| `RET` | Returns from a function |

### I/O and misc (1 word)

| Mnemonic | Meaning |
|---|---|
| `OUT Ra, Rb` | `Port[Ra] = Rb` |
| `IN Ra, Rb` | `Rb = Port[Ra]` |
| `NOP` | Does nothing |

> **Note:** In this spec, `CALL` is *register-indirect* (it takes the
> address from a register, not directly from a label), unlike `CALL label`
> in the original compiler. To call a label, first `LIM` that label's
> address into a register, then `CALL` that register (see
> `examples/import_test.asm`).

## Source file syntax (.asm)

```
; a comment like this
LIM r0, 10        ; operands can be comma-separated (or just space-separated)
LIM r1 20

loop:             ; short-form label definition
  DEC r0
  CMP r0, r1
  JNZ loop        ; labels can be referenced in short or "label name" form

label done        ; long-form label definition (equivalent to "done:")
  NOP

import "lib/util.asm" as util   ; import another file; its internal labels
                                  ; are automatically prefixed as util::labelname
JMP util::wait_loop
```

Valid registers: `r0`-`r15` and `sp` (alias for `r15`).
Integers support both decimal (`1234`) and hex (`0x04D2`).

## Build and run

Requires a JDK (`javac`/`java`) — this project has no external dependencies.

### Using the Makefile (recommended)

```bash
make            # compile src/*.java -> out/
make run ASM=examples/allops.asm OUT=out/allops.bin
make debug ASM=examples/allops.asm OUT=out/allops.bin   # also enables --debug
make test       # assembles every example file in examples/ to check for regressions
make clean      # removes out/
make help       # shows all available targets
```

If `ASM=`/`OUT=` aren't specified, `make run`/`make debug` will default to
assembling `examples/allops.asm`.

### Without the Makefile (calling javac/java directly)

```bash
cd src
javac -d ../out *.java
cd ..
java -cp out Cpu16Asm examples/allops.asm out/allops.bin
```

Append `--debug` to see the token stream, import log, and label table:

```bash
java -cp out Cpu16Asm examples/allops.asm out/allops.bin --debug
```

## Output format

Choose one of 3 formats via `--format=bin|hex|manual` (default = `bin`):

```bash
java -cp out Cpu16Asm program.asm program.bin  --format=bin      # (default)
java -cp out Cpu16Asm program.asm program.hex  --format=hex
java -cp out Cpu16Asm program.asm program.txt  --format=manual
```

| Format | Output file | When to use |
|---|---|---|
| `bin` (default) | raw binary, big-endian, 2 bytes/word, no header | Loading directly into a simulator's or hardware's instruction memory / ROM |
| `hex` | text, 1 word/line as 4-digit hex + a comment noting the address/source instruction | Visual inspection; compatible with Verilog's `$readmemh` |
| `manual` | text, 1 word/line as full 16-bit binary (`0`/`1`) + comment | For manually loading a program line-by-line (e.g. toggling switches on a breadboard CPU); also compatible with `$readmemb` |

`bin` behaves exactly the same as before the format-selection system was
added (raw binary instruction words in sequence, no header at all) — unlike
the original `fgcompiler`, which included an `FGV2` header plus an attached
storage-file table, since this ISA has no "storage file" concept.

Example of a `--format=hex` file:

```
0000  // addr 0x0000 (0)  jmp target
002D  // addr 0x0001 (1)
0021  // addr 0x0002 (2)  js target
002D  // addr 0x0003 (3)
```

Example of a `--format=manual` file:

```
0000000000000000  // addr 0x0000 (0)  jmp target
0000000000101101  // addr 0x0001 (1)
0000000000100001  // addr 0x0002 (2)  js target
0000000000101101  // addr 0x0003 (3)
```

Both `hex` and `manual` attach a comment (the originating source line) only
to the first word of each instruction. The second word (the immediate value
for `LIM`, or the address for `STORE`/`LOAD`/the jump family) has no comment,
to avoid any confusion with it being mistaken for a new instruction rather
than an operand.

## Verification

The `examples/allops.asm` file contains every mnemonic in the spec. Its
output was converted to hex and the bit patterns checked against the
original opcode table (the mask/value of every jump variant, ALU op modes,
bit11 of the CPY/LIM group, INC/DEC/POP, PUSH/CALL, RET/STORE, LOAD/NOP,
OUT/IN, CMP) — **all of which matched**.

Some sample results (word, hex):

| Source | Word(s) | Matches table |
|---|---|---|
| `js target` | `0021 00XX` | mask=bit5, value=bit0 ✓ |
| `jz target` | `0210 00XX` | mask=bit9, value=bit4 ✓ |
| `not r0` | `1A00` | `0001 1010 0000 xxxx` ✓ |
| `lim r0, 1234` | `2800 04D2` | `0010 1xxx 0000 xxxx` + immediate ✓ |
| `pop r0` | `3C0F` | `0011 1100 0000 1111` ✓ |
| `call r0` | `4B0F` | `0100 1011 0000 1111` ✓ |
| `ret` | `540F` | `0101 0100 xxxx 1111` ✓ |
| `cmp r0, r1` | `8401` | `1000 0100 0000 0001` ✓ |

## Project structure

```
cpu16asm/
├── README.md
├── Makefile
├── src/
│   ├── Token.java          - token structure produced by the lexer
│   ├── Normalizer.java     - comment stripping + lexer
│   ├── ModuleLoader.java   - import/linker system + label aliasing
│   ├── LabelRef.java       - stores a label's word address
│   ├── Parser.java         - converts tokens → CPU16 bytecode (main encoder)
│   ├── OutputWriter.java   - writes output as bin/hex/manual
│   └── Cpu16Asm.java       - CLI entry point
└── examples/
    ├── allops.asm          - example covering every mnemonic (for checking encoding)
    ├── import_test.asm     - example of using import + label aliasing
    └── lib/util.asm
```

## Limitations / not yet implemented

- No directive yet for embedding raw data (a data segment), e.g.
  `.word`/`.string`. Could be added later if embedding constant tables
  directly into instruction memory is needed.
- Remote `CALL`/jump across an imported file must be referenced via
  `alias::label` only; selective wildcard export/import of specific labels
  isn't supported yet.
- No disassembler (the `d` mode of the original `Matsusembler` was also
  still just a TODO).

## Credit
This is my Matsusembly Compiler, and the creator of the Matsusembly language
is Pong B (YouTube: https://www.youtube.com/@pongsapatboon). I obtained a
sample of Matsusembly from Pong B via TikTok
(TikTok: https://www.tiktok.com/@pongsapatboonpong).
- I do not have permission to share that original document publicly.
- If you'd like a sample Matsusembly document, please request it from Pong B
  directly through any of his channels.
- This project was built for practice and is released as open source — feel
  free to adapt it or study the code as you like.
- This is **not** the official compiler, may contain bugs, and may not
  reflect real-time changes to the official opcode spec.
- In short: this is a fan-made Matsusembler compiler, not the official one,
  and it is not guaranteed to track the official spec in real time. It's
  written in Java, so it should run on most OSes, including Windows 10/11
  and various Linux distributions.