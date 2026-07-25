import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * OutputWriter - เขียนผลลัพธ์ที่ assemble แล้ว ออกเป็น 3 รูปแบบ:
 *
 *   BIN    - raw binary เดิม: 2 byte ต่อ word, big-endian, ไม่มี header
 *            เอาไปโหลดเข้า instruction memory / ROM ได้ตรง ๆ
 *
 *   HEX    - text file, 1 word ต่อบรรทัด เป็นเลข hex 4 หลัก (16-bit)
 *            มี comment (// ...) ต่อท้ายบอก address + คำสั่งต้นทาง
 *            เข้ากันได้กับ Verilog $readmemh (บรรทัดที่มีแต่ // comment
 *            ไม่กระทบการ parse ของเครื่องมืออื่น)
 *
 *   MANUAL - text file, 1 word ต่อบรรทัด เป็นเลขฐาน 2 เต็ม 16 บิต (0/1)
 *            พร้อม address และคำสั่งต้นทางกำกับไว้ให้ ใช้เวลาต้องโหลดโปรแกรม
 *            เข้า ROM/RAM ด้วยมือ (เช่น toggle switch หรือพิมพ์ทีละบรรทัด)
 *            เข้ากันได้กับ Verilog $readmemb ด้วยเหตุผลเดียวกับ HEX
 */
class OutputWriter {

    enum Format {
        BIN, HEX, MANUAL;

        static Format fromString(String s) {
            switch (s.toLowerCase()) {
                case "bin": case "binary": return BIN;
                case "hex": case "hexadecimal": return HEX;
                case "manual": case "bin-text": case "bintext": return MANUAL;
                default:
                    System.err.println("[Error] Unknown output format: '" + s
                            + "' (ใช้ได้แค่ bin | hex | manual)");
                    System.exit(1);
                    return BIN; // ไม่ถึงตรงนี้ แต่ javac ต้องการ return
            }
        }
    }

    static void write(Format format, String path, Parser parser) {
        switch (format) {
            case BIN:    writeBin(path, parser);    break;
            case HEX:    writeHex(path, parser);    break;
            case MANUAL: writeManual(path, parser); break;
        }
    }

    // ------------------------------------------------------------------
    private static void writeBin(String path, Parser parser) {
        byte[] bytes = parser.program.toByteArray();
        try (FileOutputStream out = new FileOutputStream(path)) {
            out.write(bytes);
        } catch (IOException e) {
            fail(e);
        }
    }

    // ------------------------------------------------------------------
    private static void writeHex(String path, Parser parser) {
        List<Integer> words = parser.words;
        List<String> comments = parser.comments;

        try (PrintWriter out = newTextWriter(path)) {
            out.println("// CPU16 program image - hex format, 1 word (16-bit) per line");
            out.println("// รูปแบบ: <hex 4 หลัก>  // addr <hex> (<dec>)  <source line ถ้ามี>");
            out.println("// เข้ากันได้กับ Verilog $readmemh (บรรทัด // ไม่รบกวนการ parse)");
            out.println();
            for (int i = 0; i < words.size(); i++) {
                String hex = String.format("%04X", words.get(i));
                String note = comments.get(i);
                out.printf("%s  // addr 0x%04X (%d)%s%n",
                        hex, i, i, note.isEmpty() ? "" : "  " + note);
            }
        } catch (IOException e) {
            fail(e);
        }
    }

    // ------------------------------------------------------------------
    private static void writeManual(String path, Parser parser) {
        List<Integer> words = parser.words;
        List<String> comments = parser.comments;

        try (PrintWriter out = newTextWriter(path)) {
            out.println("// CPU16 program image - MANUAL LOAD format");
            out.println("// โหลดค่า 16 บิต (0/1) ต่อไปนี้เข้า ROM/RAM ทีละบรรทัด");
            out.println("// เริ่มจาก address 0 ไล่ขึ้นไปตามลำดับที่เขียนไว้ด้านล่าง");
            out.println("// รูปแบบ: <16 bit 0/1>  // addr <hex> (<dec>)  <source line ถ้ามี>");
            out.println();
            for (int i = 0; i < words.size(); i++) {
                String bin = toBinaryString16(words.get(i));
                String note = comments.get(i);
                out.printf("%s  // addr 0x%04X (%d)%s%n",
                        bin, i, i, note.isEmpty() ? "" : "  " + note);
            }
            out.println();
            out.println("// จบไฟล์ - รวม " + words.size() + " word(s)");
        } catch (IOException e) {
            fail(e);
        }
    }

    private static String toBinaryString16(int word) {
        StringBuilder sb = new StringBuilder(16);
        for (int bit = 15; bit >= 0; bit--) {
            sb.append(((word >> bit) & 1) == 1 ? '1' : '0');
        }
        return sb.toString();
    }

    private static PrintWriter newTextWriter(String path) throws IOException {
        return new PrintWriter(new java.io.OutputStreamWriter(
                new FileOutputStream(path), StandardCharsets.UTF_8));
    }

    private static void fail(IOException e) {
        System.err.println("[Error] Writing output: " + e);
        System.exit(1);
    }
}
