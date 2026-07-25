import java.util.ArrayList;

/**
 * Normalizer - ทำหน้าที่เดิม 2 อย่างเหมือน compiler เก่า:
 *   1) clean_line()  ตัด comment (";") ออก โดยไม่ยุ่งกับ ; ที่อยู่ใน string/char literal
 *   2) lexer()        แปลงบรรทัดที่ clean แล้วให้เป็น Token[]
 *
 * ตัด logic เกี่ยวกับ 0x.. -> BYTE ออก (ของเดิมแยก byte กับ int) เพราะ
 * CPU16 ใช้ 16-bit word ล้วน ไม่มี concept byte literal แยกต่างหาก
 * 0x.. ทุกตัวจึงถูก parse เป็น INT ตามปกติ
 */
class Normalizer {
    public ArrayList<Token[]> program = new ArrayList<>();
    // เก็บบรรทัด source ดิบ (หลัง clean comment + resolve import แล้ว) คู่ขนาน
    // กับ program ไว้ให้ Parser เอาไปแปะเป็น comment ใน output แบบ hex/manual
    public ArrayList<String> sourceLines = new ArrayList<>();
    public int pc;

    public static String clean_line(String line) {
        StringBuilder sb = new StringBuilder();
        boolean inString = false;
        boolean inChar = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (c == ';' && !inString && !inChar) {
                break;
            }

            if (c == '"' && !inChar) {
                inString = !inString;
            } else if (c == '\'' && !inString) {
                inChar = !inChar;
            }

            sb.append(c);
        }
        return sb.toString();
    }

    public Token[] lexer(String line) {
        ArrayList<Token> tokens = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        int state = 0; // 0=idle 1=in-string 2=in-char 3=in-general-token

        char[] chars = line.toCharArray();
        for (char c : chars) {
            if (state == 0) {
                if (c == ' ' || c == '\t' || c == '\r' || c == ',') {
                    // หมายเหตุ: comma ใช้เป็น separator ได้ด้วย (เช่น "ADD r0,r1")
                    // เพื่อให้ syntax ใกล้เคียง assembly ทั่วไปตามตารางที่ให้มา
                    continue;
                } else if (c == '"') {
                    state = 1;
                    buffer.setLength(0);
                } else if (c == '\'') {
                    state = 2;
                    buffer.setLength(0);
                } else {
                    state = 3;
                    buffer.setLength(0);
                    buffer.append(c);
                }
            } else if (state == 1) {
                if (c == '"') {
                    Token t = new Token();
                    t.type = Token.STRING;
                    t.StrValue = buffer.toString();
                    tokens.add(t);
                    state = 0;
                } else {
                    buffer.append(c);
                }
            } else if (state == 2) {
                if (c == '\'') {
                    Token t = new Token();
                    t.type = Token.CHAR;
                    t.CharValue = decodeCharLiteral(buffer.toString());
                    tokens.add(t);
                    state = 0;
                } else {
                    buffer.append(c);
                }
            } else { // state == 3, general token (id/int/register/label ref)
                if (c == ' ' || c == '\t' || c == '\r' || c == ',') {
                    tokens.add(parseGeneralToken(buffer.toString()));
                    state = 0;
                } else if (c == '"' || c == '\'') {
                    tokens.add(parseGeneralToken(buffer.toString()));
                    state = (c == '"') ? 1 : 2;
                    buffer.setLength(0);
                } else {
                    buffer.append(c);
                }
            }
        }

        if (state == 3) {
            tokens.add(parseGeneralToken(buffer.toString()));
        } else if (state == 1 || state == 2) {
            System.err.println("[Error] Lexer: Unclosed string or char literal.");
            System.exit(1);
        }

        return tokens.toArray(new Token[0]);
    }

    private char decodeCharLiteral(String raw) {
        if (raw.isEmpty()) {
            System.err.println("[Error] Lexer: Empty char literal ''.");
            System.exit(1);
        }
        if (raw.length() == 1) {
            return raw.charAt(0);
        }
        if (raw.length() == 2 && raw.charAt(0) == '\\') {
            switch (raw.charAt(1)) {
                case 'n': return '\n';
                case 't': return '\t';
                case 'r': return '\r';
                case '0': return '\0';
                case '\\': return '\\';
                case '\'': return '\'';
                case '"': return '"';
                default:
                    System.err.println("[Error] Lexer: Unknown escape sequence '\\" + raw.charAt(1) + "'.");
                    System.exit(1);
            }
        }
        System.err.println("[Error] Lexer: Invalid char literal '" + raw + "' (too many characters).");
        System.exit(1);
        return '\0';
    }

    private Token parseGeneralToken(String str) {
        Token t = new Token();

        if (str.equals("true") || str.equals("false")) {
            t.type = Token.BOOL;
            t.BoolValue = Boolean.parseBoolean(str);
        } else if (str.matches("-?\\d+")) {
            t.type = Token.INT;
            t.IntValue = Integer.parseInt(str);
        } else if (str.startsWith("0x") && str.length() > 2) {
            try {
                t.type = Token.INT;
                t.IntValue = (int) Long.parseLong(str.substring(2), 16);
            } catch (NumberFormatException e) {
                t.type = Token.ID;
                t.StrValue = str;
            }
        } else {
            t.type = Token.ID;
            t.StrValue = str;
        }

        return t;
    }

    public void showToken() {
        int lineNo = 1;
        for (Token[] lineTokens : this.program) {
            System.out.print(lineNo + " | ");
            for (Token token : lineTokens) {
                switch (token.type) {
                    case Token.ID:     System.out.print("[ID: " + token.StrValue + "] "); break;
                    case Token.INT:    System.out.print("[INT: " + token.IntValue + "] "); break;
                    case Token.STRING: System.out.print("[STR: \"" + token.StrValue + "\"] "); break;
                    case Token.BOOL:   System.out.print("[BOOL: " + token.BoolValue + "] "); break;
                    case Token.CHAR:   System.out.print("[CHAR: '" + token.CharValue + "'] "); break;
                    default:           System.out.print("[UNKNOWN] "); break;
                }
            }
            System.out.println();
            lineNo++;
        }
    }

    public void run(String path) {
        pc = 0;
        try {
            ModuleLoader loader = new ModuleLoader();
            java.util.List<String> lines = loader.load(path);
            if (Cpu16Asm.debug) {
                loader.printImportLog();
            }
            while (pc < lines.size()) {
                String cleaned = lines.get(pc);
                this.program.add(lexer(cleaned));
                this.sourceLines.add(cleaned);
                pc++;
            }
        } catch (Exception e) {
            System.err.println(e);
            System.exit(1);
        }
    }
}
