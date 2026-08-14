package com.smile.acelib.apisurface;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
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
 * v1 API boundary regression：Internal 分類型別必須「已收斂為非 public」或
 * 「在 allowlist 留下具體 retention 理由」；Supported/SPI 型別不得標記 retention。
 *
 * <p>本測試與 {@link ApiSurfaceContractTest} 互補：後者驗證 source↔JSON↔MD 的
 * 型別集合一致性；本測試驗證 Internal 的可見性契約——不允許「默默保持 public
 * 卻沒有持久理由」的內部實作型別漂移進 v1 對外 surface。</p>
 *
 * <p>不依賴 Bukkit / MockBukkit 環境，純靜態掃描與 JSON 解析。</p>
 */
class ApiBoundaryRegressionTest {

    private static final Pattern TYPE_DECL = Pattern.compile(
        "\\b(class|interface|enum|record)\\s+(\\w+)\\b");

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
    void canonicalTopLevelInventoryIs132() throws IOException {
        Path root = projectRoot();
        List<Map<String, String>> types = ApiSurfaceContractTestHelpers.parseTypes(
            Files.readString(root.resolve("docs/reference/api-surface.json")));
        int supported = 0;
        int spi = 0;
        int internal = 0;
        for (Map<String, String> t : types) {
            switch (t.get("classification")) {
                case "Supported" -> supported++;
                case "SPI" -> spi++;
                case "Internal" -> internal++;
                default -> throw new IllegalStateException("非法分類：" + t.get("fqcn"));
            }
        }
        // v1 canonical inventory：101 Supported + 12 SPI + 19 Internal = 132。
        // 此為公開契約（provider 維持 nested 正是為了不讓此數變成 133）；
        // 收斂 Internal 為非 public 會靜默縮減 inventory，屬於未授權 breaking change。
        assertTrue(supported == 101,
            "Supported 數量偏離 canonical 101，實際=" + supported);
        assertTrue(spi == 12,
            "SPI 數量偏離 canonical 12，實際=" + spi);
        assertTrue(internal == 19,
            "Internal 數量偏離 canonical 19，實際=" + internal
                + "（Internal 收斂為非 public 前必須先經 review 並同步 canonical 契約）");
        assertTrue(types.size() == 132,
            "top-level inventory 偏離 canonical 132，實際=" + types.size());
    }

    @Test
    void internalTypesAreConvergedOrCarryRetentionRationale() throws IOException {
        Path root = projectRoot();
        List<Map<String, String>> types = ApiSurfaceContractTestHelpers.parseTypes(
            Files.readString(root.resolve("docs/reference/api-surface.json")));
        Map<String, Boolean> sourcePublic = scanPublicTopLevelTypes(root.resolve("src/main/java"));

        List<String> missingRetention = new ArrayList<>();
        List<String> staleEntries = new ArrayList<>();
        for (Map<String, String> t : types) {
            if (!"Internal".equals(t.get("classification"))) {
                continue;
            }
            String fqcn = t.get("fqcn");
            String retention = t.get("retention");
            boolean isPublic = sourcePublic.containsKey(fqcn);
            if (isPublic && (retention == null || retention.isBlank())) {
                missingRetention.add(fqcn + "（source 仍 public，但 allowlist 無 retention 理由）");
            }
            if (!isPublic && retention != null && !retention.isBlank()) {
                staleEntries.add(fqcn + "（allowlist 標記 retention 但 source 已收斂為非 public）");
            }
        }
        assertTrue(missingRetention.isEmpty(),
            "Internal 型別仍為 public 卻無 retention 理由（需收斂或補理由）：" + missingRetention);
        assertTrue(staleEntries.isEmpty(),
            "allowlist 標記 retention 的型別已收斂但仍留在清單：" + staleEntries);
    }

    @Test
    void supportedAndSpiTypesNeverCarryRetentionField() throws IOException {
        Path root = projectRoot();
        List<Map<String, String>> types = ApiSurfaceContractTestHelpers.parseTypes(
            Files.readString(root.resolve("docs/reference/api-surface.json")));
        List<String> violations = new ArrayList<>();
        for (Map<String, String> t : types) {
            String cls = t.get("classification");
            if (("Supported".equals(cls) || "SPI".equals(cls))
                    && t.get("retention") != null && !t.get("retention").isBlank()) {
                violations.add(t.get("fqcn"));
            }
        }
        assertTrue(violations.isEmpty(),
            "Supported/SPI 型別不應帶 retention 欄位（該欄位僅供 Internal）：" + violations);
    }

    @Test
    void convergedTypesMustNotBePublicInSource() throws IOException {
        Path root = projectRoot();
        String json = Files.readString(root.resolve("docs/reference/api-surface.json"));
        List<Map<String, String>> converged = ApiSurfaceContractTestHelpers.parseConvergedTypes(json);
        Map<String, Boolean> sourcePublic = scanPublicTopLevelTypes(root.resolve("src/main/java"));
        List<String> reExposed = new ArrayList<>();
        for (Map<String, String> c : converged) {
            String fqcn = c.get("fqcn");
            String reason = c.get("reason");
            if (reason == null || reason.isBlank()) {
                throw new IllegalStateException("convergedTypes 缺少 reason：" + fqcn);
            }
            if (sourcePublic.containsKey(fqcn)) {
                reExposed.add(fqcn);
            }
        }
        assertTrue(reExposed.isEmpty(),
            "convergedTypes 中的型別不得重新變成 public top-level：" + reExposed);
    }

    @Test
    void everySourcePublicTypeIsListed() throws IOException {
        Path root = projectRoot();
        List<Map<String, String>> types = ApiSurfaceContractTestHelpers.parseTypes(
            Files.readString(root.resolve("docs/reference/api-surface.json")));
        Set<String> declared = new HashSet<>();
        for (Map<String, String> t : types) {
            declared.add(t.get("fqcn"));
        }
        Map<String, Boolean> sourcePublic = scanPublicTopLevelTypes(root.resolve("src/main/java"));
        Set<String> unlisted = new HashSet<>(sourcePublic.keySet());
        unlisted.removeAll(declared);
        assertTrue(unlisted.isEmpty(),
            "source 存在 public 頂層型別但 allowlist 未列出：" + unlisted);
    }

    private Map<String, Boolean> scanPublicTopLevelTypes(Path srcRoot) throws IOException {
        Map<String, Boolean> result = new LinkedHashMap<>();
        if (!Files.exists(srcRoot)) {
            return result;
        }
        try (Stream<Path> stream = Files.walk(srcRoot)) {
            List<Path> files = stream.filter(p -> p.toString().endsWith(".java")).toList();
            for (Path file : files) {
                String base = file.getFileName().toString().replace(".java", "");
                Path rel = srcRoot.relativize(file).getParent();
                String pkg = rel == null ? "" : rel.toString().replace('/', '.');
                String text = Files.readString(file);
                if (isPublicTopLevel(text, base)) {
                    result.put(pkg + "." + base, true);
                }
            }
        }
        return result;
    }

    private boolean isPublicTopLevel(String text, String base) {
        Matcher m = TYPE_DECL.matcher(text);
        while (m.find()) {
            if (!m.group(2).equals(base)) {
                continue;
            }
            if (braceDepth(text, m.start()) != 0) {
                continue;
            }
            String before = text.substring(declarationStart(text, m.start()), m.start());
            String modifiers = before;
            int lastBlockClose = modifiers.lastIndexOf("*/");
            if (lastBlockClose >= 0) {
                modifiers = modifiers.substring(lastBlockClose + 2);
            }
            modifiers = modifiers.trim();
            if (modifiers.contains("public")
                    && !modifiers.contains("private")
                    && !modifiers.contains("protected")) {
                return true;
            }
        }
        return false;
    }

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

    /**
     * 供同 package 測試共用的 JSON 解析 helper（避免每個測試複製一份 parser）。
     */
    static final class ApiSurfaceContractTestHelpers {
        private ApiSurfaceContractTestHelpers() {
        }

        static List<Map<String, String>> parseTypes(String json) {
            return parseSection(json, "types");
        }

        static List<Map<String, String>> parseConvergedTypes(String json) {
            return parseSection(json, "convergedTypes");
        }

        private static List<Map<String, String>> parseSection(String json, String sectionName) {
            List<Map<String, String>> out = new ArrayList<>();
            int idx = json.indexOf("\"" + sectionName + "\"");
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
                Map<String, String> map = new HashMap<>();
                for (String key : new String[] {"fqcn", "package", "simpleName", "kind",
                    "classification", "reason", "mainCallers", "retention", "convergedTo"}) {
                    map.put(key, stringField(obj, key));
                }
                out.add(map);
                i = objEnd + 1;
            }
            return out;
        }

        private static int matchingBrace(String s, int open) {
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

        private static String stringField(String obj, String key) {
            Pattern p = Pattern.compile(
                "\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
            Matcher m = p.matcher(obj);
            return m.find() ? m.group(1).replace("\\\"", "\"").replace("\\\\", "/")
                .replace("\\n", " ").replace("\\t", " ") : null;
        }
    }
}
