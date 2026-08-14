package com.smile.acelib.apisurface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Supported/SPI public signature baseline regression。
 *
 * <p>v1 對外契約（Supported + SPI）的 public methods / fields / constructors
 * signature 一旦被未授權變更（改名、改參數、改回傳、刪除、新增公開成員），
 * 本測試會與 {@code docs/reference/api-surface-signatures.json} 的 baseline 比對並失敗。</p>
 *
 * <p>Baseline 產生/更新方式（僅供刻意改動 v1 契約時使用，必須在非 CI 的本地執行）：</p>
 * <pre>
 *   ./gradlew test --tests "com.smile.acelib.apisurface.ApiSurfaceSignatureContractTest" \
 *     -Dacelib.genSignatureBaseline=true
 * </pre>
 * 或：
 * <pre>
 *   ACELIB_GEN_SIGNATURE_BASELINE=true ./gradlew test --tests \
 *     "com.smile.acelib.apisurface.ApiSurfaceSignatureContractTest"
 * </pre>
 * 產生後檢視 diff 並 commit。一般測試執行不會寫入檔案，只做純比對。
 * <strong>CI 環境（CI 非 false 或 GITHUB_ACTIONS 非空）下，任何 generation 要求
 * （env 或 system property）都會 fail closed 且不會覆寫 baseline。</strong>
 * 系統屬性路徑需由 build.gradle.kts 的 tasks.test systemProperty wiring 轉傳
 * 至 test worker（Gradle CLI -D 預設不進 test JVM）。</p>
 *
 * <p>本測試用 reflection 讀取已編譯 class 的 declared public API；classpath 由
 * Gradle test runtime 提供（main classes + paper-api testImplementation），
 * {@code Class.forName(..., false, loader)} 只 link 不初始化，避免 static init。</p>
 */
class ApiSurfaceSignatureContractTest {

    private static final String BASELINE_REL = "docs/reference/api-surface-signatures.json";
    private static final String GEN_FLAG = "acelib.genSignatureBaseline";
    private static final String GEN_ENV = "ACELIB_GEN_SIGNATURE_BASELINE";

    /** 產生/更新模式：系統屬性 acelib.genSignatureBaseline 或環境變數 ACELIB_GEN_SIGNATURE_BASELINE 設為 true。 */
    private static boolean generationRequested() {
        return Boolean.getBoolean(GEN_FLAG)
            || "true".equalsIgnoreCase(System.getenv(GEN_ENV));
    }

    /**
     * CI 偵測：GitHub Actions 與主流 CI 都會設定 CI=true；GITHUB_ACTIONS 為
     * GitHub Actions 專用標記。此處保守判斷：CI 環境下 baseline generation
     * 一律 fail closed，避免 CI 無意間覆寫契約 baseline。
     */
    static boolean isCiEnvironment() {
        String ci = System.getenv("CI");
        if (ci != null && !ci.isBlank() && !"false".equalsIgnoreCase(ci)) {
            return true;
        }
        return "true".equalsIgnoreCase(System.getenv("GITHUB_ACTIONS"));
    }

    /**
     * baseline generation 是否允許：只有「明確要求產生」且「非 CI 環境」才允許。
     * 測試可直接驗證此決策函數（fail-closed 契約）。
     */
    static boolean generationAllowed(boolean requested, boolean ci) {
        return requested && !ci;
    }

    private Path projectRoot() {
        Path dir = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath();
        for (int i = 0; i < 12; i++) {
            if (Files.exists(dir.resolve(BASELINE_REL))
                    || Files.exists(dir.resolve("docs/reference/api-surface.json"))
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
    void supportedSpiSignaturesMatchBaseline() throws IOException {
        Path root = projectRoot();
        List<Map<String, String>> types = ApiBoundaryRegressionTest.ApiSurfaceContractTestHelpers
            .parseTypes(Files.readString(root.resolve("docs/reference/api-surface.json")));

        Map<String, List<String>> current = new TreeMap<>();
        for (Map<String, String> t : types) {
            String cls = t.get("classification");
            if (!"Supported".equals(cls) && !"SPI".equals(cls)) {
                continue;
            }
            current.put(t.get("fqcn"), signatureOf(t.get("fqcn")));
        }
        assertFalse(current.isEmpty(), "Supported/SPI baseline 不應為空");

        Path baselinePath = root.resolve(BASELINE_REL);
        boolean requested = generationRequested();
        boolean ci = isCiEnvironment();
        if (requested) {
            if (!generationAllowed(requested, ci)) {
                throw new IllegalStateException(
                    "CI 環境禁止產生 signature baseline（fail closed）：" + baselinePath
                        + "；請於本地非 CI 環境執行產生，並審查 diff 後再 commit。"
                        + "（CI=" + System.getenv("CI")
                        + ", GITHUB_ACTIONS=" + System.getenv("GITHUB_ACTIONS") + "）");
            }
            Files.writeString(baselinePath, toJson(current));
            return; // 產生/更新模式：不在此次執行比對
        }

        assertTrue(Files.exists(baselinePath),
            "signature baseline 不存在：" + baselinePath
                + "（若首次建立，請先以 -Dacelib.genSignatureBaseline=true 產生並審查）");

        String expectedRaw = Files.readString(baselinePath);
        Map<String, List<String>> expected = parseBaseline(expectedRaw);

        List<String> missingTypes = new ArrayList<>();
        List<String> extraTypes = new ArrayList<>();
        for (String fqcn : current.keySet()) {
            if (!expected.containsKey(fqcn)) {
                missingTypes.add(fqcn);
            }
        }
        for (String fqcn : expected.keySet()) {
            if (!current.containsKey(fqcn)) {
                extraTypes.add(fqcn);
            }
        }
        assertTrue(missingTypes.isEmpty(),
            "Supported/SPI 型別新增但 baseline 未記錄（未授權的 surface 擴張）：" + missingTypes);
        assertTrue(extraTypes.isEmpty(),
            "baseline 記錄型別已不存在（未授權的 surface 縮減）：" + extraTypes);

        List<String> diffs = new ArrayList<>();
        for (String fqcn : current.keySet()) {
            List<String> exp = expected.getOrDefault(fqcn, List.of());
            if (!exp.equals(current.get(fqcn))) {
                diffs.add("=== " + fqcn + " ===\n  baseline: " + exp
                    + "\n  current : " + current.get(fqcn));
            }
        }
        assertEquals(Collections.emptyList(), diffs,
            "Supported/SPI public signature drift（未授權 breaking change）：\n"
                + String.join("\n", diffs));
    }

    @Test
    void generationAllowedFailsClosedInCi() {
        assertFalse(generationAllowed(true, true),
            "CI 環境下不得允許 baseline generation（fail closed）");
        assertFalse(generationAllowed(false, true),
            "未要求產生時，即使非 CI 也不得產生");
        assertFalse(generationAllowed(false, false),
            "未要求產生時不得產生");
        assertTrue(generationAllowed(true, false),
            "本地非 CI 環境的明確產生要求應被允許（供維護者更新 baseline）");
    }

    /**
     * 計算型別的 declared public API canonical signature（不含 synthetic/bridge）。
     */
    static List<String> signatureOf(String fqcn) {
        try {
            Class<?> c = Class.forName(fqcn, false,
                ApiSurfaceSignatureContractTest.class.getClassLoader());
            List<String> out = new ArrayList<>();
            for (Constructor<?> ctor : c.getDeclaredConstructors()) {
                if (!Modifier.isPublic(ctor.getModifiers()) || ctor.isSynthetic()) {
                    continue;
                }
                out.add("ctor(" + joinTypes(ctor.getGenericParameterTypes()) + ")");
            }
            for (Method m : c.getDeclaredMethods()) {
                if (!Modifier.isPublic(m.getModifiers()) || m.isSynthetic() || m.isBridge()) {
                    continue;
                }
                out.add("method " + m.getName() + "(" + joinTypes(m.getGenericParameterTypes())
                    + ") : " + m.getGenericReturnType().getTypeName());
            }
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isPublic(f.getModifiers()) || f.isSynthetic()) {
                    continue;
                }
                out.add("field " + f.getName() + " : " + f.getGenericType().getTypeName());
            }
            Collections.sort(out);
            return out;
        } catch (LinkageError | ClassNotFoundException e) {
            throw new IllegalStateException("無法載入型別做 signature baseline：" + fqcn, e);
        }
    }

    private static String joinTypes(Type[] types) {
        List<String> parts = new ArrayList<>();
        for (Type t : types) {
            parts.add(t.getTypeName());
        }
        return String.join(",", parts);
    }

    private static String toJson(Map<String, List<String>> current) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"schemaVersion\": 1,\n");
        sb.append("  \"description\": \"AceLib v1 Supported/SPI public signature baseline; "
            + "由 ApiSurfaceSignatureContractTest 驗證與產生。\",\n");
        sb.append("  \"types\": {\n");
        List<String> fqcns = new ArrayList<>(current.keySet());
        Collections.sort(fqcns);
        for (int i = 0; i < fqcns.size(); i++) {
            String fqcn = fqcns.get(i);
            sb.append("    \"").append(fqcn).append("\": [\n");
            List<String> sigs = current.get(fqcn);
            for (int j = 0; j < sigs.size(); j++) {
                sb.append("      \"").append(escape(sigs.get(j))).append("\"");
                sb.append(j < sigs.size() - 1 ? "," : "");
                sb.append("\n");
            }
            sb.append("    ]");
            sb.append(i < fqcns.size() - 1 ? "," : "");
            sb.append("\n");
        }
        sb.append("  }\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static Map<String, List<String>> parseBaseline(String raw) {
        Map<String, List<String>> out = new TreeMap<>();
        int typesIdx = raw.indexOf("\"types\"");
        if (typesIdx < 0) {
            return out;
        }
        int arr = raw.indexOf('{', typesIdx);
        if (arr < 0) {
            return out;
        }
        String typesBody = raw.substring(arr);
        int i = 0;
        while (true) {
            // 找下一個 entry 的 fqcn（被引號包圍，後面接 ': ['）
            int fqcnStart = typesBody.indexOf('"', i);
            if (fqcnStart < 0) {
                break;
            }
            int fqcnEnd = typesBody.indexOf('"', fqcnStart + 1);
            if (fqcnEnd < 0) {
                break;
            }
            String fqcn = typesBody.substring(fqcnStart + 1, fqcnEnd);
            int colon = typesBody.indexOf(": [", fqcnEnd);
            if (colon < 0) {
                break;
            }
            int arrayStart = colon + 3;
            // 以 quote-aware 掃描找出陣列結尾（signature 內可能含 ']'，例如 String[]）
            int arrEnd = findArrayEnd(typesBody, arrayStart);
            if (arrEnd < 0) {
                break;
            }
            String arrBody = typesBody.substring(arrayStart, arrEnd);
            List<String> sigs = new ArrayList<>();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(arrBody);
            while (m.find()) {
                sigs.add(unescape(m.group(1)));
            }
            Collections.sort(sigs);
            out.put(fqcn, sigs);
            i = arrEnd + 1;
        }
        return out;
    }

    /** 從陣列起始位置找配對的 ']'；跳過字串字面量（含 \" 跳脫）。 */
    private static int findArrayEnd(String s, int start) {
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                i++;
                while (i < s.length()) {
                    char inner = s.charAt(i);
                    if (inner == '\\') {
                        i++;
                    } else if (inner == '"') {
                        break;
                    }
                    i++;
                }
            } else if (c == ']') {
                return i;
            }
        }
        return -1;
    }

    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
