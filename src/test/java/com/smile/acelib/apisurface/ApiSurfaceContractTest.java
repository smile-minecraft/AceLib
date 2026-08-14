package com.smile.acelib.apisurface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 驗證 v1 公開 API surface 契約：機器可讀 allowlist 與人類可讀文件一致，
 * 且與 src/main/java 中實際的 public 頂層型別完全對應。
 *
 * <p>本測試不依賴任何 Bukkit / MockBukkit 環境，純靜態掃描與 JSON / Markdown 解析；
 * 型別數量由來源動態計算，不硬編任何數字。</p>
 */
class ApiSurfaceContractTest {

    private static final Set<String> VALID = Set.of("Supported", "SPI", "Internal");
    private static final Pattern WORKFLOW_ID = Pattern.compile(
        "(acelib-docs-|acelib-developer|acelib-bootstrap|20260814|Plan §|task-|plan-|milestone-)");

    private Path projectRoot() {
        Path dir = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath();
        for (int i = 0; i < 12; i++) {
            if (Files.exists(dir.resolve("docs/reference/api-surface.json"))
                    || Files.exists(dir.resolve("build.gradle.kts"))) {
                return dir;
            }
            Path parent = dir.getParent();
            if (parent == null) {
                break;
            }
            dir = parent;
        }
        return Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath();
    }

    @Test
    void allowlistMatchesActualPublicTopLevelTypes() throws IOException {
        Path root = projectRoot();
        Path jsonPath = root.resolve("docs/reference/api-surface.json");
        assertTrue(Files.exists(jsonPath), "allowlist 不存在：" + jsonPath);

        String json = Files.readString(jsonPath);
        List<Map<String, String>> types = parseTypes(json);
        assertFalse(types.isEmpty(), "allowlist 未包含任何型別");

        Set<String> seen = new HashSet<>();
        for (Map<String, String> t : types) {
            String fqcn = t.get("fqcn");
            assertTrue(seen.add(fqcn), "allowlist 出現重複型別：" + fqcn);
        }

        for (Map<String, String> t : types) {
            String cls = t.get("classification");
            assertTrue(VALID.contains(cls), "型別 " + t.get("fqcn") + " 分類非法：" + cls);
            String reason = t.get("reason");
            assertFalse(reason == null || reason.isBlank(), "型別 " + t.get("fqcn") + " 缺少理由");
        }

        Set<String> actual = scanPublicTopLevelTypes(root.resolve("src/main/java"));
        Set<String> declared = new HashSet<>();
        for (Map<String, String> t : types) {
            declared.add(t.get("fqcn"));
        }

        Set<String> missing = new HashSet<>(actual);
        missing.removeAll(declared);
        Set<String> extra = new HashSet<>(declared);
        extra.removeAll(actual);

        assertTrue(missing.isEmpty(),
            "allowlist 遺漏實際 public 頂層型別（需加入）：" + missing);
        assertTrue(extra.isEmpty(),
            "allowlist 包含不存在的型別（需移除）：" + extra);
        assertEquals(actual.size(), declared.size(),
            "allowlist 數量與實際 public 頂層型別數量不一致");
    }

    @Test
    void markdownConsistentWithAllowlist() throws IOException {
        Path root = projectRoot();
        Path jsonPath = root.resolve("docs/reference/api-surface.json");
        Path mdPath = root.resolve("docs/reference/api-surface.md");
        assertTrue(Files.exists(jsonPath), "allowlist 不存在");
        assertTrue(Files.exists(mdPath), "人類可讀 API surface 文件不存在：" + mdPath);

        List<Map<String, String>> types = parseTypes(Files.readString(jsonPath));
        Map<String, String> expected = new LinkedHashMap<>();
        for (Map<String, String> t : types) {
            expected.put(t.get("fqcn"), t.get("classification"));
        }

        String md = Files.readString(mdPath);
        Map<String, String> actual = parseMarkdown(md);

        for (Map.Entry<String, String> e : expected.entrySet()) {
            assertTrue(actual.containsKey(e.getKey()),
                "API surface 文件缺少型別：" + e.getKey());
            assertEquals(e.getValue(), actual.get(e.getKey()),
                "API surface 文件對 " + e.getKey() + " 的分類與 allowlist 不一致");
        }
        for (String fqcn : actual.keySet()) {
            assertTrue(expected.containsKey(fqcn),
                "API surface 文件包含 allowlist 沒有的型別：" + fqcn);
        }
    }

    @Test
    void artifactsContainNoWorkflowIds() throws IOException {
        Path root = projectRoot();
        String json = Files.readString(root.resolve("docs/reference/api-surface.json"));
        String md = Files.readString(root.resolve("docs/reference/api-surface.md"));
        assertFalse(WORKFLOW_ID.matcher(json).find(), "allowlist 含有 workflow ID");
        assertFalse(WORKFLOW_ID.matcher(md).find(), "API surface 文件含有 workflow ID");
    }

    @Test
    void scannerDetectsPublicTypeWhoseJavadocContainsModifierWords() {
        // 回歸：javadoc 內含 protected/private 字樣（例如「protectedSlots」）不得讓
        // public 頂層型別被誤判為 non-public；type keyword 前的立即宣告文字才是判準。
        String source = """
            package com.smile.acelib.gui;

            /**
             * 對外不可變的 GUI session 物件。
             * <p>callers 不可修改 generation 或 protectedSlots；{@code final}。</p>
             * @see GuiSessionRegistry
             */
            public final class GuiSession {
                private final int size;
            }
            """;
        assertTrue(isPublicTopLevelType(source, "GuiSession"),
            "javadoc 含 protected/private 字樣時，public 型別仍應被偵測");
    }

    @Test
    void scannerRejectsNonPublicAndNestedTypes() {
        String source = """
            package com.smile.acelib.internal;

            /**
             * 內部 helper；private field 不應影響判斷。
             */
            final class PackagePrivateHelper {
                private int value;
            }

            class Outer {
                public class Nested {
                }
            }
            """;
        assertFalse(isPublicTopLevelType(source, "PackagePrivateHelper"),
            "package-private 型別不應被視為 public 頂層");
        assertFalse(isPublicTopLevelType(source, "Nested"),
            "巢狀型別不應被視為頂層 public");
        assertFalse(isPublicTopLevelType(source, "Outer"),
            "無 public modifier 的頂層型別不應被視為 public");
    }

    private Set<String> scanPublicTopLevelTypes(Path srcRoot) throws IOException {
        Set<String> result = new HashSet<>();
        if (!Files.exists(srcRoot)) {
            return result;
        }
        try (Stream<Path> stream = Files.walk(srcRoot)) {
            List<Path> files = stream.filter(p -> p.toString().endsWith(".java")).toList();
            for (Path file : files) {
                String base = file.getFileName().toString().replace(".java", "");
                String pkg = packageOf(srcRoot, file);
                String text = Files.readString(file);
                if (isPublicTopLevelType(text, base)) {
                    result.add(pkg + "." + base);
                }
            }
        }
        return result;
    }

    private String packageOf(Path srcRoot, Path file) {
        Path rel = srcRoot.relativize(file).getParent();
        return rel == null ? "" : rel.toString().replace(File.separatorChar, '.');
    }

    private boolean isPublicTopLevelType(String text, String base) {
        Pattern p = Pattern.compile(
            "\\b(class|interface|enum|record|@interface)\\s+" + Pattern.quote(base) + "\\b");
        Matcher m = p.matcher(text);
        while (m.find()) {
            if (braceDepth(text, m.start()) != 0) {
                continue;
            }
            int declStart = declarationStart(text, m.start());
            String seg = text.substring(declStart, m.start());
            // 只檢查 type keyword 前的「立即宣告文字」：最後一個 block comment 結束
            // （`*/`）之後的 modifiers；否則 javadoc 內的 protected/private 字樣
            // （例如「protectedSlots」）會造成誤判。
            String modifierText = seg;
            int lastBlockClose = modifierText.lastIndexOf("*/");
            if (lastBlockClose >= 0) {
                modifierText = modifierText.substring(lastBlockClose + 2);
            }
            modifierText = modifierText.trim();
            if (modifierText.contains("public")
                    && !modifierText.contains("private")
                    && !modifierText.contains("protected")) {
                return true;
            }
        }
        return false;
    }

    // 從關鍵字位置向前掃描，跳過註解與字串，找到宣告起點（前一個 { ; 或檔首）。
    // 向後掃描時先遇到 closing `*/`（c='/'、prev='*'）進入註解，
    // 再遇到 opening `/*`（c='*'、prev='/'）離開註解；兩個條件不可互換。
    private int declarationStart(String text, int keywordPos) {
        boolean lineComment = false;
        boolean blockComment = false;
        boolean inString = false;
        boolean inChar = false;
        for (int i = keywordPos - 1; i >= 0; i--) {
            char c = text.charAt(i);
            char prev = (i - 1 >= 0) ? text.charAt(i - 1) : '\0';
            if (lineComment) {
                if (c == '\n') {
                    lineComment = false;
                }
                continue;
            }
            if (blockComment) {
                if (c == '*' && prev == '/') {
                    blockComment = false;
                    i--;
                }
                continue;
            }
            if (inString) {
                if (c == '\\') {
                    i--;
                    continue;
                }
                if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (inChar) {
                if (c == '\\') {
                    i--;
                    continue;
                }
                if (c == '\'') {
                    inChar = false;
                }
                continue;
            }
            if (c == '/' && prev == '*') {
                blockComment = true;
                i--;
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '\'') {
                inChar = true;
                continue;
            }
            if (c == '{' || c == ';') {
                return i + 1;
            }
        }
        return 0;
    }

    // 計算位置前的 brace 深度，忽略註解與字串字面量（避免 javadoc 的 {@link} 干擾）。
    private int braceDepth(String text, int pos) {
        int depth = 0;
        boolean lineComment = false;
        boolean blockComment = false;
        boolean inString = false;
        boolean inChar = false;
        for (int i = 0; i < pos; i++) {
            char c = text.charAt(i);
            char next = (i + 1 < text.length()) ? text.charAt(i + 1) : '\0';
            if (lineComment) {
                if (c == '\n') {
                    lineComment = false;
                }
                continue;
            }
            if (blockComment) {
                if (c == '*' && next == '/') {
                    blockComment = false;
                    i++;
                }
                continue;
            }
            if (inString) {
                if (c == '\\') {
                    i++;
                    continue;
                }
                if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (inChar) {
                if (c == '\\') {
                    i++;
                    continue;
                }
                if (c == '\'') {
                    inChar = false;
                }
                continue;
            }
            if (c == '/' && next == '/') {
                lineComment = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                blockComment = true;
                i++;
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '\'') {
                inChar = true;
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            }
        }
        return depth;
    }

    private List<Map<String, String>> parseTypes(String json) {
        List<Map<String, String>> out = new ArrayList<>();
        int idx = json.indexOf("\"types\"");
        if (idx < 0) {
            return out;
        }
        int arr = json.indexOf('[', idx);
        int end = json.indexOf(']', arr);
        if (arr < 0 || end < 0) {
            return out;
        }
        String body = json.substring(arr + 1, end);
        int i = 0;
        while (true) {
            int objStart = body.indexOf('{', i);
            if (objStart < 0) {
                break;
            }
            int objEnd = matchingBrace(body, objStart);
            String obj = body.substring(objStart, objEnd + 1);
            Map<String, String> map = new LinkedHashMap<>();
            for (String key : new String[] {"fqcn", "package", "simpleName", "kind",
                "classification", "reason", "mainCallers"}) {
                map.put(key, stringField(obj, key));
            }
            out.add(map);
            i = objEnd + 1;
        }
        return out;
    }

    private int matchingBrace(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return s.length() - 1;
    }

    private String stringField(String obj, String key) {
        Pattern p = Pattern.compile(
            "\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        Matcher m = p.matcher(obj);
        return m.find() ? unescape(m.group(1)) : null;
    }

    private String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "/")
            .replace("\\n", " ").replace("\\t", " ");
    }

    private Map<String, String> parseMarkdown(String md) {
        Map<String, String> out = new LinkedHashMap<>();
        Pattern row = Pattern.compile(
            "\\|\\s*`?([\\w.]+)`?\\s*\\|\\s*(\\w+)\\s*\\|\\s*(Supported|SPI|Internal)\\s*\\|");
        for (String line : md.split("\n")) {
            Matcher m = row.matcher(line);
            if (m.find()) {
                out.put(m.group(1), m.group(3));
            }
        }
        return out;
    }
}
