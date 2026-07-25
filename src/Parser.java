import java.io.ByteArrayOutputStream;
import java.util.*;

/**
 * Parser - แปลง Token[] (ต่อบรรทัด) ให้เป็น bytecode 16-bit words ของ CPU16
 *
 * อ้างอิง encoding ทั้งหมดจากตาราง opcode ที่ให้มา (L1IsSequences-style sheet):
 *   16 registers R0..R15 (R15 = SP), RAM แชร์กับ register file (R0 อยู่ที่ RAM
 *   address 0 ... R15 อยู่ที่ address 15), ทุกคำสั่งกว้าง 16 บิต 1 หรือ 2 words
 *
 * สรุป opcode class ตาม bits15-12 (ไล่จาก Seq No ในตาราง):
 *   0000  jump family (bit นอกนั้นคือ flag mask/value โดยตรง)               2 words (opcode + address)
 *   0001  ALU 2-op (AND/OR/ADD/SUB/XOR) ที่ bit11=0, mode ที่ bit10-8        1 word
 *         NOT (bit11=1, mode=010 ที่ bit10-8)                               1 word
 *   0010  CPY (bit11=0) / LIM (bit11=1, มี immediate เป็น word ที่ 2)       1 หรือ 2 words
 *   0011  INC (mode=ADD) / DEC (mode=SUB) / POP (bit11=1, mode=SUB)         1 word
 *   0100  PUSH (mode=ADD) / CALL (bit11=1, mode=ADD)                       1 word
 *   0101  RET (mode=SUB) / STORE (bit11=1, + address word)                 1 หรือ 2 words
 *   0110  LOAD (bit11=0, + address word) / NOP (bit11=1)                   1 หรือ 2 words
 *   0111  OUT (bit11=0) / IN (bit11=1)                                     1 word
 *   1000  CMP (mode=SUB, ไม่เขียนผลลัพธ์ ตั้งแต่ flag อย่างเดียว)           1 word
 */
class Parser {
    public int pc = 0; // เลขบรรทัด source ปัจจุบัน (ไว้ใช้ error message)
    public ByteArrayOutputStream program = new ByteArrayOutputStream();

    // เก็บผลลัพธ์แบบ "รายคำ" คู่ขนานไปกับ program (raw bytes) ข้างบน
    // ไว้ให้ OutputWriter เอาไปพิมพ์เป็น hex/manual ได้ โดยไม่ต้องแกะ byte กลับ
    //   words.get(i)    -> ค่า word ที่ i (0-based, ตาม word address จริง)
    //   comments.get(i) -> ข้อความกำกับ (source line ต้นทาง) ใส่เฉพาะ word แรก
    //                       ของแต่ละคำสั่ง ส่วน word ที่สอง (immediate/address
    //                       ของคำสั่ง 2 words) จะเป็นสตริงว่าง
    public final List<Integer> words = new ArrayList<>();
    public final List<String> comments = new ArrayList<>();

    private final Map<String, LabelRef> labelTable = new HashMap<>();

    // ---- register name -> id (0-15) ------------------------------------
    private final Map<String, Integer> regMap = new HashMap<>();

    // ---- jump family: mnemonic -> word1 base (flags mask|value ใส่แล้ว) --
    private static final Map<String, Integer> JUMP_OPS = new HashMap<>();
    static {
        JUMP_OPS.put("jmp", 0x0000);
        JUMP_OPS.put("js",  0x0021); // mask S(bit5) | value S(bit0)
        JUMP_OPS.put("jns", 0x0020);
        JUMP_OPS.put("jv",  0x0042); // mask V(bit6) | value V(bit1)
        JUMP_OPS.put("jnv", 0x0040);
        JUMP_OPS.put("jc",  0x0084); // mask C(bit7) | value C(bit2)
        JUMP_OPS.put("jnc", 0x0080);
        JUMP_OPS.put("jn",  0x0108); // mask N(bit8) | value N(bit3)
        JUMP_OPS.put("jnn", 0x0100);
        JUMP_OPS.put("jz",  0x0210); // mask Z(bit9) | value Z(bit4)
        JUMP_OPS.put("jnz", 0x0200);
    }

    // ---- ALU 2-operand: mnemonic -> 3-bit ALU mode ----------------------
    private static final Map<String, Integer> ALU2_MODE = new HashMap<>();
    static {
        ALU2_MODE.put("and", 0b000);
        ALU2_MODE.put("or",  0b001);
        ALU2_MODE.put("add", 0b011);
        ALU2_MODE.put("sub", 0b100);
        ALU2_MODE.put("xor", 0b101);
    }

    Parser() {
        for (int i = 0; i <= 15; i++) {
            regMap.put("r" + i, i);
        }
        regMap.put("sp", 15); // R15 คือ Stack Pointer ตามสเปก
    }

    private void error(String msg) {
        System.err.println("[Error] Parser: " + msg + " (source line " + this.pc + ")");
        System.exit(1);
    }

    private void requireArgs(Token[] line, int count, String op) {
        if (line.length - 1 < count) {
            error(op + " expects " + count + " argument(s), got " + (line.length - 1));
        }
    }

    private void emitWord(int word) {
        program.write((word >> 8) & 0xFF);
        program.write(word & 0xFF);
        words.add(word & 0xFFFF);
        comments.add(""); // ใส่ comment จริงทีหลังใน emitInstructionWithComment()
    }

    private boolean isRegisterToken(Token t) {
        return t.type == Token.ID && regMap.containsKey(t.StrValue.toLowerCase());
    }

    private int regId(Token t) {
        if (t.type != Token.ID || !regMap.containsKey(t.StrValue.toLowerCase())) {
            error("Expected register name (r0-r15 or sp), got '"
                    + (t.type == Token.ID ? t.StrValue : t.IntValue) + "'");
            return 0;
        }
        return regMap.get(t.StrValue.toLowerCase());
    }

    private int intVal(Token t) {
        if (t.type == Token.INT) return t.IntValue;
        error("Expected an integer literal, got '" + t.StrValue + "'");
        return 0;
    }

    /** ใช้กับ operand ที่รับได้ทั้ง label name หรือเลขตรง ๆ (JMP family, STORE/LOAD address) */
    private int resolveAddrOrLiteral(Token t) {
        if (t.type == Token.ID) {
            LabelRef ref = labelTable.get(t.StrValue);
            if (ref == null) {
                error("Unknown label: " + t.StrValue);
                return 0;
            }
            return ref.addr;
        }
        if (t.type == Token.INT) return t.IntValue;
        error("Expected an address or a label name");
        return 0;
    }

    // ------------------------------------------------------------------
    //  label definition:  "name:"  หรือ  "label name"
    // ------------------------------------------------------------------
    private String labelDefName(Token[] line) {
        if (line.length == 1 && line[0].type == Token.ID) {
            String s = line[0].StrValue;
            if (s.length() > 1 && s.endsWith(":")) {
                return s.substring(0, s.length() - 1);
            }
        } else if (line.length == 2 && line[0].type == Token.ID && line[0].StrValue.equals("label")) {
            if (line[1].type == Token.ID) return line[1].StrValue;
            error("label name must be an identifier");
        }
        return null;
    }

    /** จำนวน "word" ที่คำสั่งบรรทัดนี้จะกินไปในหน่วยความจำโปรแกรม (สำหรับคำนวณตำแหน่ง label) */
    private int wordLengthOf(Token[] line) {
        if (line.length == 0) return 0;
        if (line[0].type != Token.ID) return 0;
        String op = line[0].StrValue.toLowerCase();
        if (JUMP_OPS.containsKey(op)) return 2;
        switch (op) {
            case "lim":
            case "store":
            case "load":
                return 2;
            default:
                return 1; // และ/or/add/sub/xor/not/cpy/inc/dec/pop/push/call/ret/nop/out/in/cmp
        }
    }

    // ------------------------------------------------------------------
    //  pass 1: scanLabels - เดิน address ทีละ word (ไม่ใช่ทีละบรรทัด!)
    //  เพราะบางคำสั่งกิน 2 words ตำแหน่ง label ต้องนับตาม word จริง
    // ------------------------------------------------------------------
    private void scanLabels(ArrayList<Token[]> codeToken) {
        labelTable.clear();
        int wordAddr = 0;

        for (Token[] line : codeToken) {
            if (line == null || line.length == 0) continue;

            String labelName = labelDefName(line);
            if (labelName != null) {
                if (labelTable.containsKey(labelName)) {
                    error("Duplicate label: " + labelName);
                } else {
                    labelTable.put(labelName, new LabelRef(wordAddr));
                }
                continue; // label definition เองไม่กิน word ใด ๆ
            }
            wordAddr += wordLengthOf(line);
        }
    }

    public void showLabels() {
        System.out.println("=== Label Table (word address) ===");
        for (Map.Entry<String, LabelRef> e : labelTable.entrySet()) {
            System.out.println("  " + e.getKey() + "  ->  0x" + Integer.toHexString(e.getValue().addr)
                    + "  (" + e.getValue().addr + ")");
        }
    }

    // ------------------------------------------------------------------
    //  pass 2: emit
    //  sourceLines: บรรทัด source (หลัง clean comment/resolve import แล้ว)
    //  เรียงตาม index เดียวกับ codeToken ใช้เป็น comment กำกับ output
    //  แบบ hex/manual เท่านั้น ไม่มีผลต่อ bytecode ที่ได้เลย
    // ------------------------------------------------------------------
    public void run(ArrayList<Token[]> codeToken, List<String> sourceLines) {
        scanLabels(codeToken);

        int lineNo = 0;
        for (Token[] line : codeToken) {
            this.pc = lineNo;
            String comment = (sourceLines != null && lineNo < sourceLines.size())
                    ? sourceLines.get(lineNo) : "";
            lineNo++;
            if (line == null || line.length == 0) continue;
            if (labelDefName(line) != null) continue; // ไม่ emit อะไรสำหรับ label definition

            emitInstructionWithComment(line, comment);
        }
    }

    // เรียก emitInstruction() ตามปกติ แล้วแปะ comment ลงบน word "แรก" เท่านั้น
    // ของคำสั่งนี้ (word ที่สองถ้ามี เช่น immediate/address จะเป็นค่าว่าง)
    private void emitInstructionWithComment(Token[] line, String comment) {
        int start = words.size();
        emitInstruction(line);
        if (words.size() > start) {
            comments.set(start, comment);
        }
    }

    private void emitInstruction(Token[] line) {
        if (line[0].type != Token.ID) {
            error("First token of the line must be an instruction mnemonic");
            return;
        }
        String op = line[0].StrValue.toLowerCase();

        // ---- jump family: <mnemonic> <address|label> ---------------------
        if (JUMP_OPS.containsKey(op)) {
            requireArgs(line, 1, op);
            emitWord(JUMP_OPS.get(op));
            emitWord(resolveAddrOrLiteral(line[1]));
            return;
        }

        // ---- ALU 2-operand: <mnemonic> Ra, Rb -----------------------------
        if (ALU2_MODE.containsKey(op)) {
            requireArgs(line, 2, op);
            int mode = ALU2_MODE.get(op);
            int ra = regId(line[1]);
            int rb = regId(line[2]);
            emitWord(0x1000 | (mode << 8) | (ra << 4) | rb);
            return;
        }

        switch (op) {
            case "not": {
                // NOT Ra
                requireArgs(line, 1, op);
                int ra = regId(line[1]);
                emitWord(0x1A00 | (ra << 4));
                break;
            }
            case "cpy": {
                // CPY Ra, Rb  (Ra = Rb)
                requireArgs(line, 2, op);
                int ra = regId(line[1]);
                int rb = regId(line[2]);
                emitWord(0x2000 | (ra << 4) | rb);
                break;
            }
            case "lim": {
                // LIM Ra, value  (Ra = value, 2 words)
                requireArgs(line, 2, op);
                int ra = regId(line[1]);
                int value = intVal(line[2]);
                emitWord(0x2800 | (ra << 4));
                emitWord(value & 0xFFFF);
                break;
            }
            case "inc": {
                requireArgs(line, 1, op);
                int ra = regId(line[1]);
                emitWord(0x3300 | (ra << 4));
                break;
            }
            case "dec": {
                requireArgs(line, 1, op);
                int ra = regId(line[1]);
                emitWord(0x3400 | (ra << 4));
                break;
            }
            case "pop": {
                // POP Ra  (SP-- ; Ra = RAM[SP])
                requireArgs(line, 1, op);
                int ra = regId(line[1]);
                emitWord(0x3C00 | (ra << 4) | 0xF);
                break;
            }
            case "push": {
                // PUSH Ra  (RAM[SP] = Ra ; SP++)
                requireArgs(line, 1, op);
                int ra = regId(line[1]);
                emitWord(0x4300 | (ra << 4) | 0xF);
                break;
            }
            case "call": {
                // CALL Ra  (เรียกฟังก์ชันที่ address อยู่ใน Ra, indirect)
                requireArgs(line, 1, op);
                int ra = regId(line[1]);
                emitWord(0x4B00 | (ra << 4) | 0xF);
                break;
            }
            case "ret": {
                emitWord(0x540F);
                break;
            }
            case "store": {
                // STORE Ra, Address  (RAM[Address] = Ra)
                requireArgs(line, 2, op);
                int ra = regId(line[1]);
                int addr = resolveAddrOrLiteral(line[2]);
                emitWord(0x5800 | (ra << 4));
                emitWord(addr & 0xFFFF);
                break;
            }
            case "load": {
                // LOAD Ra, Address  (Ra = RAM[Address])
                requireArgs(line, 2, op);
                int ra = regId(line[1]);
                int addr = resolveAddrOrLiteral(line[2]);
                emitWord(0x6000 | (ra << 4));
                emitWord(addr & 0xFFFF);
                break;
            }
            case "nop": {
                emitWord(0x6800);
                break;
            }
            case "out": {
                // OUT Ra, Rb  (Port[Ra] = Rb)
                requireArgs(line, 2, op);
                int ra = regId(line[1]);
                int rb = regId(line[2]);
                emitWord(0x7000 | (ra << 4) | rb);
                break;
            }
            case "in": {
                // IN Ra, Rb  (Rb = Port[Ra])
                requireArgs(line, 2, op);
                int ra = regId(line[1]);
                int rb = regId(line[2]);
                emitWord(0x7800 | (ra << 4) | rb);
                break;
            }
            case "cmp": {
                // CMP Ra, Rb  (ตั้ง flags จาก Ra-Rb, ไม่เก็บผลลัพธ์)
                requireArgs(line, 2, op);
                int ra = regId(line[1]);
                int rb = regId(line[2]);
                emitWord(0x8400 | (ra << 4) | rb);
                break;
            }
            default:
                error("Unknown instruction: " + op);
        }
    }
}
