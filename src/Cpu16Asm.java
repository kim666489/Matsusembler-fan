/**
 * Cpu16Asm - Program entry point for the assembler
 *
 * Usage:
 *   java Cpu16Asm <source.asm> <output> [--format=bin|hex|manual] [--debug]
 *
 *   --format=bin     (default) raw binary, big-endian, 2 bytes/word, no header
 *   --format=hex     text file, 1 word per line as 4-digit hex, with comments
 *   --format=manual  text file, 1 word per line as full 16-bit binary (0/1),
 *                     with address/comment annotations, for manual program loading
 */
public class Cpu16Asm {
    public static boolean debug = false;

    public static void main(String[] args) {
        if (args.length < 2) {
            printUsage();
            System.exit(1);
            return;
        }

        String inputPath = args[0];
        String outputPath = args[1];
        OutputWriter.Format format = OutputWriter.Format.BIN;

        for (int i = 2; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--debug")) {
                debug = true;
            } else if (arg.startsWith("--format=")) {
                format = OutputWriter.Format.fromString(arg.substring("--format=".length()));
            } else if (arg.equals("--format")) {
                if (i + 1 >= args.length) {
                    System.err.println("[Error] --format must be followed by a value: bin | hex | manual");
                    System.exit(1);
                }
                format = OutputWriter.Format.fromString(args[++i]);
            } else {
                System.err.println("[Error] Unknown argument: " + arg);
                printUsage();
                System.exit(1);
            }
        }

        Normalizer norm = new Normalizer();
        norm.run(inputPath);
        if (debug) {
            System.out.println("=== Tokens ===");
            norm.showToken();
        }

        Parser parser = new Parser();
        parser.run(norm.program, norm.sourceLines);
        if (debug) {
            parser.showLabels();
        }

        OutputWriter.write(format, outputPath, parser);

        int wordCount = parser.words.size();
        System.out.println("Assembled OK: " + wordCount + " word(s) -> " + outputPath
                + "  [format=" + format.toString().toLowerCase() + "]");
    }

    private static void printUsage() {
        System.err.println("Usage: java Cpu16Asm <source.asm> <output> [--format=bin|hex|manual] [--debug]");
        System.err.println("  bin     raw binary, big-endian, 2 bytes/word (default)");
        System.err.println("  hex     text, 1 word/line as 4-digit hex + comment");
        System.err.println("  manual  text, 1 word/line as 16-bit binary (0/1) + comment, for manual loading");
    }
}