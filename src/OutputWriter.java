import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * OutputWriter - writes the assembled output in 3 formats:
 *
 *   BIN    - the original raw binary: 2 bytes per word, big-endian, no header
 *            can be loaded directly into instruction memory / ROM
 *
 *   HEX    - text file, 1 word per line as a 4-digit hex number (16-bit)
 *            with a trailing comment (// ...) noting the address + source instruction
 *            compatible with Verilog's $readmemh (lines containing only a //
 *            comment don't interfere with other tools' parsing)
 *
 *   MANUAL - text file, 1 word per line as full 16-bit binary (0/1),
 *            annotated with the address and source instruction; used when
 *            a program needs to be loaded into ROM/RAM by hand (e.g. toggling
 *            switches, or typing it in line by line)
 *            also compatible with Verilog's $readmemb for the same reason as HEX
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
                            + "' (valid options: bin | hex | manual)");
                    System.exit(1);
                    return BIN; // unreachable, but javac requires a return
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
            out.println("// Format: <4-digit hex>  // addr <hex> (<dec>)  <source line if any>");
            out.println("// Compatible with Verilog $readmemh (// lines don't interfere with parsing)");
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
            out.println("// Load the following 16-bit values (0/1) into ROM/RAM one line at a time");
            out.println("// Starting from address 0, in the order listed below");
            out.println("// Format: <16-bit 0/1>  // addr <hex> (<dec>)  <source line if any>");
            out.println();
            for (int i = 0; i < words.size(); i++) {
                String bin = toBinaryString16(words.get(i));
                String note = comments.get(i);
                out.printf("%s  // addr 0x%04X (%d)%s%n",
                        bin, i, i, note.isEmpty() ? "" : "  " + note);
            }
            out.println();
            out.println("// End of file - " + words.size() + " word(s) total");
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