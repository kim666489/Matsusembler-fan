# CPU16 Assembler

Assembler (Java) สำหรับ CPU 16-bit ตัวใหม่ ที่ดัดแปลงมาจาก compiler เดิม
(`fgcompiler` ของ VM แบบ stack-based) โดยยึด opcode/encoding ตามสเปกที่ให้มา
(`L1IsSequences`-style opcode sheet) 100% — bit pattern ทุกคำสั่งถูกตรวจสอบ
กับตารางต้นฉบับแล้ว (ดูหัวข้อ [Verification](#verification))

## สิ่งที่เอามาจาก compiler เดิม กับสิ่งที่เขียนใหม่

| ส่วน | สถานะ | เหตุผล |
|---|---|---|
| `ModuleLoader` (ระบบ `import "..." as alias`, กัน circular import, label aliasing) | **คงไว้เกือบทั้งหมด** | เป็น logic ระดับ source-text ล้วน ไม่ผูกกับ ISA |
| `Normalizer` (comment stripping, lexer) | **ปรับเล็กน้อย** | ตัด token type "BYTE" ทิ้ง เพราะ CPU16 ไม่มี byte literal แยกจาก int, `0x..` ทุกตัวคือ 16-bit int; เพิ่มรองรับ comma เป็น separator (`ADD r0, r1`) |
| `Parser` (ตัวแปลง token → bytecode) | **เขียนใหม่ทั้งหมด** | ชุดคำสั่ง/รีจิสเตอร์/การเข้ารหัส bit ไม่เหมือนเดิมเลย |
| `storage "name" size`, syscall table, register แบบ `r/a/s` (33 registers), header `FGV2` | **เอาออก** | ไม่มีอยู่ใน ISA ใหม่ (CPU16 ใช้ RAM แชร์กับ register file ธรรมดา, I/O ผ่าน `IN`/`OUT` port แทน syscall) |
| `config.txt` / `ConfigReader` | **เอาออก** | ไฟล์ config เดิมไม่ได้แนบมาด้วยและไม่เกี่ยวกับ ISA เปลี่ยนเป็นรับ path จาก CLI argument ตรง ๆ แทน (เพิ่มกลับได้ทีหลังถ้าต้องการ) |

## CPU16 ISA

CPU มี 16 registers (`R0`-`R15`) กว้าง 16 บิตทุกตัว, `R15` = Stack Pointer (SP)
Register ทั้งหมดถูก map ไว้ใน RAM ที่ address 0-15 (คือ `R0` อยู่ที่ RAM
address 0 ไปจนถึง `R15` ที่ address 15) ทุกคำสั่งกว้าง 16 บิต และใช้ 1 หรือ 2
words (คำสั่งที่มี address/immediate แยก จะกิน 2 words เสมอ)

### Jump family (2 words: opcode + address)

| Mnemonic | ความหมาย |
|---|---|
| `JMP addr` | กระโดดแบบไม่มีเงื่อนไข |
| `JS addr` / `JNS addr` | กระโดดถ้า Sign flag ติด / ไม่ติด |
| `JV addr` / `JNV addr` | กระโดดถ้า Overflow flag ติด / ไม่ติด |
| `JC addr` / `JNC addr` | กระโดดถ้า Carry flag ติด / ไม่ติด |
| `JN addr` / `JNN addr` | กระโดดถ้า bit15 ของผลลัพธ์ติด / ไม่ติด |
| `JZ addr` / `JNZ addr` | กระโดดถ้าผลลัพธ์เป็น 0 / ไม่เป็น 0 |

`addr` ใส่เป็นเลขตรง ๆ หรือชื่อ label ก็ได้ (assembler จะ resolve ให้เป็น
word-address ของ label นั้นอัตโนมัติ)

### ALU / register ops (1 word)

| Mnemonic | ความหมาย |
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
| `CMP Ra, Rb` | ตั้งค่า flag จาก `Ra - Rb` โดยไม่เก็บผลลัพธ์ |

### Immediate / memory (LIM/STORE/LOAD กิน 2 words, ที่เหลือ 1 word)

| Mnemonic | ความหมาย |
|---|---|
| `LIM Ra, value` | `Ra = value` (โหลดค่าคงที่ 16 บิตตรง ๆ) |
| `STORE Ra, addr` | `RAM[addr] = Ra` |
| `LOAD Ra, addr` | `Ra = RAM[addr]` |

### Stack / call (1 word)

| Mnemonic | ความหมาย |
|---|---|
| `PUSH Ra` | `RAM[SP] = Ra` แล้ว `SP++` |
| `POP Ra` | `SP--` แล้ว `Ra = RAM[SP]` |
| `CALL Ra` | เรียกฟังก์ชันที่ address อยู่ใน `Ra` (indirect call ผ่าน register) |
| `RET` | กลับจากฟังก์ชัน |

### I/O และอื่น ๆ (1 word)

| Mnemonic | ความหมาย |
|---|---|
| `OUT Ra, Rb` | `Port[Ra] = Rb` |
| `IN Ra, Rb` | `Rb = Port[Ra]` |
| `NOP` | ไม่ทำอะไร |

> **หมายเหตุ:** `CALL` ในสเปกนี้เป็น *register-indirect* (รับ address จาก
> register ไม่ใช่ label ตรง ๆ) ต่างจาก `CALL label` ของ compiler เดิม
> ถ้าจะเรียกไปที่ label ให้ `LIM` ที่อยู่ label เข้า register ก่อน แล้วค่อย
> `CALL` register นั้น (ดู `examples/import_test.asm`)

## Syntax ของไฟล์ source (.asm)

```
; comment แบบนี้
LIM r0, 10        ; comma คั่น operand ได้ (หรือจะเว้นวรรคเฉย ๆ ก็ได้)
LIM r1 20

loop:             ; นิยาม label แบบสั้น
  DEC r0
  CMP r0, r1
  JNZ loop        ; อ้าง label ได้ทั้งแบบสั้นและแบบ "label name"

label done        ; นิยาม label แบบยาว (เทียบเท่ากับ "done:")
  NOP

import "lib/util.asm" as util   ; import ไฟล์อื่น, label ข้างในจะถูกเติม
                                  ; prefix เป็น util::labelname อัตโนมัติ
JMP util::wait_loop
```

Register ที่ใช้ได้: `r0`-`r15` และ `sp` (alias ของ `r15`)
เลขจำนวนเต็มรองรับทั้ง decimal (`1234`) และ hex (`0x04D2`)

## วิธี build และรัน

ต้องมี JDK (`javac`/`java`) — โปรเจกต์นี้ไม่มี dependency ภายนอกเลย

### ใช้ Makefile (แนะนำ)

```bash
make            # compile src/*.java -> out/
make run ASM=examples/allops.asm OUT=out/allops.bin
make debug ASM=examples/allops.asm OUT=out/allops.bin   # เปิด --debug ด้วย
make test       # assemble ตัวอย่างทุกไฟล์ใน examples/ เพื่อเช็ค regression
make clean      # ลบ out/ ทิ้ง
make help       # แสดงรายการ target ทั้งหมด
```

ถ้าไม่ระบุ `ASM=`/`OUT=` เลย `make run`/`make debug` จะ assemble
`examples/allops.asm` เป็นค่า default

### ไม่ใช้ Makefile (เรียก javac/java ตรง ๆ)

```bash
cd src
javac -d ../out *.java
cd ..
java -cp out Cpu16Asm examples/allops.asm out/allops.bin
```

ใส่ `--debug` ต่อท้ายเพื่อดู token stream, import log และ label table:

```bash
java -cp out Cpu16Asm examples/allops.asm out/allops.bin --debug
```

## Output format

เลือกได้ 3 แบบผ่าน `--format=bin|hex|manual` (default = `bin`):

```bash
java -cp out Cpu16Asm program.asm program.bin  --format=bin      # (default)
java -cp out Cpu16Asm program.asm program.hex  --format=hex
java -cp out Cpu16Asm program.asm program.txt  --format=manual
```

| Format | ไฟล์ที่ได้ | ใช้ตอนไหน |
|---|---|---|
| `bin` (default) | raw binary, big-endian, 2 byte/word, ไม่มี header | โหลดเข้า instruction memory / ROM ของ simulator หรือฮาร์ดแวร์ตรง ๆ |
| `hex` | text, 1 word/บรรทัด เป็น hex 4 หลัก + comment บอก address/คำสั่งต้นทาง | อ่านตรวจสอบด้วยตา, เข้ากันได้กับ Verilog `$readmemh` |
| `manual` | text, 1 word/บรรทัด เป็นเลขฐาน 2 เต็ม 16 บิต (`0`/`1`) + comment | ไว้โหลดโปรแกรมเข้าเครื่องด้วยมือทีละบรรทัด (เช่น toggle switch บน breadboard CPU) เข้ากันได้กับ `$readmemb` ด้วย |

`bin` เหมือนเดิมทุกประการกับตอนที่ยังไม่มีระบบเลือก format (raw binary
instruction words เรียงกันตามลำดับ ไม่มี header ใด ๆ — ต่างจาก `fgcompiler`
เดิมที่มี header `FGV2` + ตาราง storage file แนบไว้ด้วย เพราะ ISA นี้ไม่มี
concept "storage file")

ตัวอย่างไฟล์ `--format=hex`:

```
0000  // addr 0x0000 (0)  jmp target
002D  // addr 0x0001 (1)
0021  // addr 0x0002 (2)  js target
002D  // addr 0x0003 (3)
```

ตัวอย่างไฟล์ `--format=manual`:

```
0000000000000000  // addr 0x0000 (0)  jmp target
0000000000101101  // addr 0x0001 (1)
0000000000100001  // addr 0x0002 (2)  js target
0000000000101101  // addr 0x0003 (3)
```

ทั้ง `hex` และ `manual` แปะ comment (source line ต้นทาง) ไว้เฉพาะ word แรก
ของแต่ละคำสั่งเท่านั้น ส่วน word ที่สอง (immediate ของ `LIM` หรือ address
ของ `STORE`/`LOAD`/jump family) จะไม่มี comment เพื่อไม่ให้สับสนว่าเป็นค่า
operand ไม่ใช่คำสั่งใหม่

## Verification

ไฟล์ `examples/allops.asm` มีครบทุก mnemonic ในสเปก แปลผลลัพธ์เป็น hex แล้ว
เทียบ bit pattern กับตาราง opcode ต้นฉบับไปแล้ว (mask/value ของ jump ทุกตัว,
mode ของ ALU ops, bit11 ของกลุ่ม CPY/LIM, INC/DEC/POP, PUSH/CALL,
RET/STORE, LOAD/NOP, OUT/IN, CMP) **ตรงกันทั้งหมด**

ตัวอย่างผลลัพธ์บางส่วน (word, hex):

| Source | Word(s) | ตรงกับตาราง |
|---|---|---|
| `js target` | `0021 00XX` | mask=bit5, value=bit0 ✓ |
| `jz target` | `0210 00XX` | mask=bit9, value=bit4 ✓ |
| `not r0` | `1A00` | `0001 1010 0000 xxxx` ✓ |
| `lim r0, 1234` | `2800 04D2` | `0010 1xxx 0000 xxxx` + immediate ✓ |
| `pop r0` | `3C0F` | `0011 1100 0000 1111` ✓ |
| `call r0` | `4B0F` | `0100 1011 0000 1111` ✓ |
| `ret` | `540F` | `0101 0100 xxxx 1111` ✓ |
| `cmp r0, r1` | `8401` | `1000 0100 0000 0001` ✓ |

## โครงสร้างโปรเจกต์

```
cpu16asm/
├── README.md
├── Makefile
├── src/
│   ├── Token.java          - โครงสร้าง token จาก lexer
│   ├── Normalizer.java     - comment stripping + lexer
│   ├── ModuleLoader.java   - ระบบ import/linker + label aliasing
│   ├── LabelRef.java       - เก็บ word-address ของ label
│   ├── Parser.java         - แปลง token → CPU16 bytecode (encoder หลัก)
│   ├── OutputWriter.java   - เขียนผลลัพธ์เป็น bin/hex/manual
│   └── Cpu16Asm.java       - CLI entry point
└── examples/
    ├── allops.asm          - ตัวอย่างครบทุก mnemonic (ไว้ตรวจ encoding)
    ├── import_test.asm     - ตัวอย่างการใช้ import + label alias
    └── lib/util.asm
```

## ข้อจำกัด / สิ่งที่ยังไม่ทำ

- ยังไม่มี directive สำหรับฝังข้อมูลดิบ (data segment) เช่น `.word`/`.string`
  เพิ่มได้ในอนาคตถ้าต้องการฝังตารางค่าคงที่ตรง ๆ ใน instruction memory
- `CALL`/jump ระยะไกลข้ามไฟล์ที่ import ต้องอ้างผ่าน `alias::label` เท่านั้น
  ยังไม่รองรับ wildcard export/import แบบเลือกเฉพาะบาง label
- ไม่มี disassembler (โหมด `d` ของ `Matsusembler` เดิมก็ยังเป็นแค่ TODO เหมือนกัน)

## Credit
นี้คือ Matsusembly Compiler จากผม และ ผู้คิดและสร้างภาษา Matsusembly คือคุณ Pong B (Youtube: https://www.youtube.com/@pongsapatboon)
และ ผมนั้นได้ไปขอ ตัวอย่าง Matsusembly จาก Pong B ผ่าน Tiktok (Tiktok: https://www.tiktok.com/@pongsapatboonpong)
- ขอไม่เปิดเผยเอกสารฉบับนั้น 
- ถ้าหากต้องการ เอกสารตัวอย่าง Matsusembly ให้ไปขอทาง Pong B ในช่องทางใดก็ตาม
- และ project นี้ทำเพื่อฝึก เป็น open source สามารถนำไปปรับใช้ได้ตามต้องการหรือศึกษา Code
- นี้ไม่ใช้ Compiler จาก official และอาจมี bug ระบบ และ อาจไม่เปลี่ยนแปลงตาม opcode จริงในเอกสาร RealTime
สรุปคือ นี้เป็น Matsusembler Compiler FanMade ซึ่งไม่ใช้ของทาง official และ update อ่านไม่ตาม เอกสาร RealTime และ อาจใช้งานได้บนหลายๆ os เช่น Windows 11/10 linux บ้างตัว เพราะเขียนด้วย java เป็นหลัก#   M a t s u s e m b l e r - f a n  
 