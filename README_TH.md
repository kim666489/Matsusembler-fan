#Matsusembler FanMade (CPU16 Assembler)

Assembler (เขียนด้วย Java) สำหรับ CPU 16-bit ตัวใหม่ ดัดแปลงมาจาก compiler เดิม
(`fgcompiler` ของ VM แบบ stack-based) โดยยึด opcode/encoding ตามสเปกที่ให้มา
(`L1IsSequences`-style opcode sheet) ทั้งหมด — bit pattern ของทุกคำสั่งถูก
ตรวจสอบเทียบกับตารางต้นฉบับเรียบร้อยแล้ว (ดูหัวข้อ [Verification](#verification))

## สิ่งที่คงไว้จาก compiler เดิม กับสิ่งที่เขียนขึ้นใหม่

| ส่วน | สถานะ | เหตุผล |
|---|---|---|
| `ModuleLoader` (ระบบ `import "..." as alias`, ป้องกัน circular import, label aliasing) | **คงไว้เกือบทั้งหมด** | เป็น logic ระดับ source-text ล้วน ๆ ไม่ผูกติดกับ ISA |
| `Normalizer` (comment stripping, lexer) | **ปรับเล็กน้อย** | ตัด token type "BYTE" ออก เพราะ CPU16 ไม่มี byte literal แยกจาก int (`0x..` ทุกตัวคือ 16-bit int) และเพิ่มการรองรับ comma เป็นตัวคั่น operand (`ADD r0, r1`) |
| `Parser` (ตัวแปลง token → bytecode) | **เขียนใหม่ทั้งหมด** | ชุดคำสั่ง รีจิสเตอร์ และการเข้ารหัส bit ไม่เหมือนของเดิมเลย |
| `storage "name" size`, syscall table, register แบบ `r/a/s` (33 registers), header `FGV2` | **ตัดออก** | ไม่มีอยู่ใน ISA ใหม่ (CPU16 ใช้ RAM ร่วมกับ register file ธรรมดา และสื่อสาร I/O ผ่าน `IN`/`OUT` port แทน syscall) |
| `config.txt` / `ConfigReader` | **ตัดออก** | ไฟล์ config เดิมไม่ได้แนบมาด้วยและไม่เกี่ยวข้องกับ ISA เปลี่ยนไปรับ path ผ่าน CLI argument โดยตรงแทน (สามารถเพิ่มกลับได้ในอนาคตหากต้องการ) |

## CPU16 ISA

CPU มีรีจิสเตอร์ 16 ตัว (`R0`-`R15`) กว้าง 16 บิตทุกตัว โดย `R15` ทำหน้าที่
เป็น Stack Pointer (SP) รีจิสเตอร์ทั้งหมดถูก map ไว้ใน RAM ที่ address 0-15
(คือ `R0` อยู่ที่ RAM address 0 ไล่ไปจนถึง `R15` ที่ address 15) ทุกคำสั่ง
กว้าง 16 บิต และใช้พื้นที่ 1 หรือ 2 words (คำสั่งที่มี address/immediate
แยกต่างหากจะกิน 2 words เสมอ)

### กลุ่มคำสั่งกระโดด (Jump family) — 2 words: opcode + address

| Mnemonic | ความหมาย |
|---|---|
| `JMP addr` | กระโดดแบบไม่มีเงื่อนไข |
| `JS addr` / `JNS addr` | กระโดดถ้า Sign flag ติด / ไม่ติด |
| `JV addr` / `JNV addr` | กระโดดถ้า Overflow flag ติด / ไม่ติด |
| `JC addr` / `JNC addr` | กระโดดถ้า Carry flag ติด / ไม่ติด |
| `JN addr` / `JNN addr` | กระโดดถ้า bit15 ของผลลัพธ์ติด / ไม่ติด |
| `JZ addr` / `JNZ addr` | กระโดดถ้าผลลัพธ์เป็น 0 / ไม่เป็น 0 |

`addr` สามารถใส่เป็นเลขตรง ๆ หรือชื่อ label ก็ได้ (assembler จะ resolve ให้
เป็น word-address ของ label นั้นโดยอัตโนมัติ)

### ALU / คำสั่งเกี่ยวกับรีจิสเตอร์ — 1 word

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
| `CMP Ra, Rb` | ตั้งค่า flag จาก `Ra - Rb` โดยไม่เก็บผลลัพธ์ไว้ |

### คำสั่ง Immediate / memory (LIM/STORE/LOAD กิน 2 words ที่เหลือ 1 word)

| Mnemonic | ความหมาย |
|---|---|
| `LIM Ra, value` | `Ra = value` (โหลดค่าคงที่ 16 บิตเข้ารีจิสเตอร์โดยตรง) |
| `STORE Ra, addr` | `RAM[addr] = Ra` |
| `LOAD Ra, addr` | `Ra = RAM[addr]` |

### Stack / call — 1 word

| Mnemonic | ความหมาย |
|---|---|
| `PUSH Ra` | `RAM[SP] = Ra` แล้วตามด้วย `SP++` |
| `POP Ra` | `SP--` แล้วตามด้วย `Ra = RAM[SP]` |
| `CALL Ra` | เรียกฟังก์ชันที่ address ซึ่งเก็บอยู่ใน `Ra` (indirect call ผ่านรีจิสเตอร์) |
| `RET` | กลับจากฟังก์ชัน |

### I/O และอื่น ๆ — 1 word

| Mnemonic | ความหมาย |
|---|---|
| `OUT Ra, Rb` | `Port[Ra] = Rb` |
| `IN Ra, Rb` | `Rb = Port[Ra]` |
| `NOP` | ไม่ทำอะไร |

> **หมายเหตุ:** `CALL` ในสเปกนี้เป็นแบบ *register-indirect* (รับ address
> จากรีจิสเตอร์ ไม่ใช่จาก label ตรง ๆ) ต่างจาก `CALL label` ของ compiler
> เดิม หากต้องการเรียกไปยัง label ให้ใช้ `LIM` โหลดตำแหน่งของ label นั้น
> เข้ารีจิสเตอร์ก่อน แล้วจึงค่อย `CALL` รีจิสเตอร์นั้น (ดูตัวอย่างใน
> `examples/import_test.asm`)

## รูปแบบไฟล์ source (.asm)

```
; comment เขียนแบบนี้
LIM r0, 10        ; ใช้ comma คั่น operand ได้ (หรือจะเว้นวรรคเฉย ๆ ก็ได้)
LIM r1 20

loop:             ; นิยาม label แบบสั้น
  DEC r0
  CMP r0, r1
  JNZ loop        ; อ้างอิง label ได้ทั้งแบบสั้นและแบบ "ชื่อ label"

label done        ; นิยาม label แบบยาว (เทียบเท่ากับ "done:")
  NOP

import "lib/util.asm" as util   ; import ไฟล์อื่น label ข้างในจะถูกเติม
                                  ; prefix เป็น util::labelname ให้อัตโนมัติ
JMP util::wait_loop
```

รีจิสเตอร์ที่ใช้ได้: `r0`-`r15` และ `sp` (alias ของ `r15`)
เลขจำนวนเต็มรองรับทั้งฐาน 10 (`1234`) และฐาน 16 (`0x04D2`)

## วิธี build และรัน

ต้องมี JDK (`javac`/`java`) ติดตั้งไว้ — โปรเจกต์นี้ไม่มี dependency ภายนอกเลย

### ใช้ Makefile (แนะนำ)

```bash
make            # compile src/*.java -> out/
make run ASM=examples/allops.asm OUT=out/allops.bin
make debug ASM=examples/allops.asm OUT=out/allops.bin   # เปิด --debug ด้วย
make test       # assemble ตัวอย่างทุกไฟล์ใน examples/ เพื่อตรวจ regression
make clean      # ลบโฟลเดอร์ out/ ทิ้ง
make help       # แสดงรายการ target ทั้งหมด
```

หากไม่ระบุ `ASM=`/`OUT=` คำสั่ง `make run`/`make debug` จะ assemble
`examples/allops.asm` เป็นค่า default ให้เอง

### ไม่ใช้ Makefile (เรียก javac/java ตรง ๆ)

```bash
cd src
javac -d ../out *.java
cd ..
java -cp out Cpu16Asm examples/allops.asm out/allops.bin
```

ใส่ `--debug` ต่อท้ายเพื่อดู token stream, import log และตาราง label:

```bash
java -cp out Cpu16Asm examples/allops.asm out/allops.bin --debug
```

## รูปแบบ output

เลือกได้ 3 แบบผ่านตัวเลือก `--format=bin|hex|manual` (ค่า default คือ `bin`)

```bash
java -cp out Cpu16Asm program.asm program.bin  --format=bin      # (default)
java -cp out Cpu16Asm program.asm program.hex  --format=hex
java -cp out Cpu16Asm program.asm program.txt  --format=manual
```

| Format | ไฟล์ที่ได้ | เหมาะกับงานแบบไหน |
|---|---|---|
| `bin` (default) | raw binary, big-endian, 2 byte ต่อ word, ไม่มี header | โหลดเข้า instruction memory / ROM ของ simulator หรือฮาร์ดแวร์ได้โดยตรง |
| `hex` | text file, 1 word ต่อบรรทัด เป็น hex 4 หลัก พร้อม comment บอก address/คำสั่งต้นทาง | ใช้อ่านตรวจสอบด้วยตา และเข้ากันได้กับคำสั่ง `$readmemh` ของ Verilog |
| `manual` | text file, 1 word ต่อบรรทัด เป็นเลขฐาน 2 เต็ม 16 บิต (`0`/`1`) พร้อม comment | ไว้ใช้โหลดโปรแกรมเข้าเครื่องด้วยมือทีละบรรทัด (เช่น toggle switch บน breadboard CPU) และเข้ากันได้กับ `$readmemb` ด้วย |

รูปแบบ `bin` ยังคงเหมือนเดิมทุกประการกับก่อนที่จะมีระบบเลือก format
(เป็น raw binary ของ instruction words เรียงต่อกันตามลำดับ ไม่มี header
ใด ๆ) ซึ่งต่างจาก `fgcompiler` เดิมที่มี header `FGV2` พร้อมตาราง storage
file แนบไว้ด้วย เพราะ ISA ตัวนี้ไม่มี concept ของ "storage file" แล้ว

ตัวอย่างไฟล์แบบ `--format=hex`:

```
0000  // addr 0x0000 (0)  jmp target
002D  // addr 0x0001 (1)
0021  // addr 0x0002 (2)  js target
002D  // addr 0x0003 (3)
```

ตัวอย่างไฟล์แบบ `--format=manual`:

```
0000000000000000  // addr 0x0000 (0)  jmp target
0000000000101101  // addr 0x0001 (1)
0000000000100001  // addr 0x0002 (2)  js target
0000000000101101  // addr 0x0003 (3)
```

ทั้ง `hex` และ `manual` จะแปะ comment (source line ต้นทาง) ไว้เฉพาะ word
แรกของแต่ละคำสั่งเท่านั้น ส่วน word ที่สอง (ค่า immediate ของ `LIM` หรือ
address ของ `STORE`/`LOAD`/กลุ่มคำสั่งกระโดด) จะไม่มี comment กำกับ
เพื่อไม่ให้เข้าใจผิดว่าเป็นคำสั่งใหม่ ทั้งที่จริงเป็นแค่ operand

## Verification

ไฟล์ `examples/allops.asm` มี mnemonic ครบทุกตัวตามสเปก ผลลัพธ์ที่ได้ถูก
แปลงเป็น hex แล้วนำไปเทียบ bit pattern กับตาราง opcode ต้นฉบับ (ครอบคลุม
mask/value ของคำสั่งกระโดดทุกตัว, mode ของ ALU ops, bit11 ของกลุ่ม
CPY/LIM, INC/DEC/POP, PUSH/CALL, RET/STORE, LOAD/NOP, OUT/IN, CMP) ผลคือ
**ตรงกันทั้งหมด**

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
│   ├── Token.java          - โครงสร้าง token ที่ได้จาก lexer
│   ├── Normalizer.java     - comment stripping + lexer
│   ├── ModuleLoader.java   - ระบบ import/linker + label aliasing
│   ├── LabelRef.java       - เก็บ word-address ของ label
│   ├── Parser.java         - แปลง token → CPU16 bytecode (encoder หลัก)
│   ├── OutputWriter.java   - เขียนผลลัพธ์เป็น bin/hex/manual
│   └── Cpu16Asm.java       - CLI entry point
└── examples/
    ├── allops.asm          - ตัวอย่างที่มี mnemonic ครบทุกตัว (ไว้ตรวจ encoding)
    ├── import_test.asm     - ตัวอย่างการใช้ import + label alias
    └── lib/util.asm
```

## ข้อจำกัด / สิ่งที่ยังไม่ได้ทำ

- ยังไม่มี directive สำหรับฝังข้อมูลดิบ (data segment) เช่น `.word`/`.string`
  สามารถเพิ่มได้ในอนาคตหากต้องการฝังตารางค่าคงที่ตรง ๆ ลงใน instruction memory
- การ `CALL`/jump ข้ามไฟล์ที่ import เข้ามาต้องอ้างผ่าน `alias::label`
  เท่านั้น ยังไม่รองรับการ export/import แบบเลือกเฉพาะบาง label (wildcard)
- ยังไม่มี disassembler (โหมด `d` ของ `Matsusembler` เดิมก็ยังเป็นแค่ TODO
  เช่นกัน)

## Credit
นี่คือ Matsusembly Compiler ของผม โดยผู้คิดค้นและสร้างภาษา Matsusembly
คือคุณ Pong B (YouTube: https://www.youtube.com/@pongsapatboon) ผมได้ขอ
ตัวอย่าง Matsusembly จากคุณ Pong B ผ่านทาง TikTok
(TikTok: https://www.tiktok.com/@pongsapatboonpong)

- ขอสงวนสิทธิ์ไม่เปิดเผยเอกสารต้นฉบับดังกล่าว
- หากต้องการเอกสารตัวอย่าง Matsusembly กรุณาติดต่อขอจากคุณ Pong B โดยตรง
  ผ่านช่องทางใดก็ได้
- โปรเจกต์นี้ทำขึ้นเพื่อฝึกฝน เปิดเป็น open source สามารถนำไปปรับใช้หรือ
  ศึกษา code ได้ตามต้องการ
- นี่ไม่ใช่ compiler จากทาง official อาจมี bug หลงเหลืออยู่ และอาจไม่
  อัปเดตตาม opcode จริงในเอกสารแบบ real-time
- สรุปคือ นี่เป็น Matsusembler Compiler แบบ Fan-made ซึ่งไม่ใช่ของทาง
  official และไม่ได้อัปเดตตามเอกสาร real-time เขียนด้วย Java เป็นหลัก
  จึงสามารถใช้งานได้บนหลายระบบปฏิบัติการ เช่น Windows 10/11 และ Linux
  บางดิสโทร