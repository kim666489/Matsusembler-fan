import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ModuleLoader — ระบบ import / linker แบบ source-text เหมือนตัวเดิมทุกประการ
 * (deduping, circular-import detection, label aliasing เวลา import ... as X)
 *
 * ส่วนที่ต้องแก้จาก compiler เดิม: LABEL_ARG_INDEX
 *   ของเดิม (VM แบบ stack-based) มี jmp/jmpr/jmpf/jmpl/call ที่อ้าง label ได้
 *   CPU16 ตัวใหม่มีแค่กลุ่ม jump ที่อ้าง label ตรง ๆ ในบรรทัด (operand ตัวที่ 1)
 *   ส่วน CALL ของ CPU16 เป็น "CALL Ra" (เรียกผ่าน register, indirect) ไม่ใช่
 *   label โดยตรง จึงไม่ต้องอยู่ในตารางนี้อีกต่อไป
 */
class ModuleLoader {

    private final Set<String> completed = new HashSet<>();
    private final Set<String> loading = new HashSet<>();
    private final List<String> importLog = new ArrayList<>();

    private static final Pattern IMPORT_PATTERN =
        Pattern.compile("^import\\s+\"([^\"]+)\"(?:\\s+as\\s+([A-Za-z_][A-Za-z0-9_]*))?\\s*$");

    private static final Pattern LABEL_SHORT =
        Pattern.compile("^([A-Za-z_][A-Za-z0-9_:]*):$");

    private static final Pattern LABEL_LONG =
        Pattern.compile("^label\\s+([A-Za-z_][A-Za-z0-9_:]*)\\s*$");

    // operand index (นับ mnemonic เป็น index 0) ที่เป็นชื่อ label สำหรับแต่ละคำสั่ง jump
    private static final Map<String, Integer> LABEL_ARG_INDEX = new HashMap<>();
    static {
        for (String m : new String[]{
                "jmp", "js", "jns", "jv", "jnv", "jc", "jnc", "jn", "jnn", "jz", "jnz"}) {
            LABEL_ARG_INDEX.put(m, 1);
        }
    }

    public List<String> load(String path) throws Exception {
        Path abs = Paths.get(path).toAbsolutePath().normalize();
        return loadRecursive(abs, null, path);
    }

    private String dedupKey(String absKey, String alias) {
        return absKey + "#" + (alias == null ? "" : alias);
    }

    private List<String> loadRecursive(Path absPath, String alias, String displayPath) throws Exception {
        List<String> out = new ArrayList<>();
        String key = absPath.toString();
        String dkey = dedupKey(key, alias);

        if (completed.contains(dkey)) {
            importLog.add("[skip-duplicate] " + displayPath
                    + (alias != null ? (" as " + alias) : "")
                    + "  (เคย import ไปแล้ว ไม่ทำซ้ำ)");
            return out;
        }

        if (loading.contains(key)) {
            throw new Exception("[Error] Import: circular import detected -> " + displayPath);
        }
        if (!Files.exists(absPath)) {
            throw new Exception("[Error] Import: file not found -> " + absPath);
        }

        loading.add(key);
        importLog.add("[import] " + displayPath + (alias != null ? (" as " + alias) : ""));

        List<String> rawLines = Files.readAllLines(absPath);
        Path dir = absPath.getParent();

        for (String raw : rawLines) {
            String cleaned = Normalizer.clean_line(raw).trim();
            if (cleaned.isEmpty()) { out.add(""); continue; }

            Matcher im = IMPORT_PATTERN.matcher(cleaned);
            if (im.matches()) {
                String importPath = im.group(1);
                String childAlias = im.group(2);
                Path childAbs = dir.resolve(importPath).normalize();

                List<String> childLines = loadRecursive(childAbs, childAlias, importPath);
                if (childAlias != null && !childLines.isEmpty()) {
                    childLines = applyAlias(childLines, childAlias);
                }
                out.addAll(childLines);
                continue;
            }
            out.add(cleaned);
        }

        loading.remove(key);
        completed.add(dkey);
        return out;
    }

    public List<String> getImportLog() { return importLog; }

    public void printImportLog() {
        System.out.println("=== Import Log ===");
        for (String line : importLog) System.out.println("  " + line);
    }

    private List<String> applyAlias(List<String> lines, String alias) {
        Set<String> localLabels = new HashSet<>();

        for (String line : lines) {
            Matcher m1 = LABEL_SHORT.matcher(line);
            if (m1.matches()) {
                localLabels.add(m1.group(1));
                continue;
            }
            Matcher m2 = LABEL_LONG.matcher(line);
            if (m2.matches()) {
                localLabels.add(m2.group(1));
            }
        }

        List<String> result = new ArrayList<>(lines.size());
        for (String line : lines) {
            result.add(rewriteLine(line, alias, localLabels));
        }
        return result;
    }

    private String rewriteLine(String line, String alias, Set<String> localLabels) {
        if (line.isEmpty()) return line;

        Matcher m1 = LABEL_SHORT.matcher(line);
        if (m1.matches()) {
            return alias + "::" + m1.group(1) + ":";
        }
        Matcher m2 = LABEL_LONG.matcher(line);
        if (m2.matches()) {
            return "label " + alias + "::" + m2.group(1);
        }

        String[] parts = line.split("[\\s,]+");
        if (parts.length >= 2) {
            Integer argIdx = LABEL_ARG_INDEX.get(parts[0].toLowerCase());
            if (argIdx != null && parts.length > argIdx) {
                String argTok = parts[argIdx];
                if (localLabels.contains(argTok)) {
                    parts[argIdx] = alias + "::" + argTok;
                    return String.join(" ", parts);
                }
            }
        }

        return line;
    }
}
