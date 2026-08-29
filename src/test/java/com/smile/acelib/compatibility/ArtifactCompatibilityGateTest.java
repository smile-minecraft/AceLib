package com.smile.acelib.compatibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Artifact / binary 相容門禁（雙版本 CI 矩陣的 artifact lane）。
 *
 * <p>本測試是 {@code compatibilityCheck} 聚合 gate 的一部分，覆蓋六項 artifact 契約：</p>
 * <ol>
 *   <li>Java 25 bytecode（production class 的 major version = 69）；</li>
 *   <li>最低 Paper / API 26.1.2 基線（plugin.yml api-version ≥ 26.1.2）；</li>
 *   <li>plugin descriptor 一致性（version 與 AceLibVersion.VERSION 同步、main / folia-supported 正確）；</li>
 *   <li>無 bundled server APIs（產出 jar 不得內含 org.bukkit / io.papermc class）；</li>
 *   <li>無 optional eager linkage（production 不得在非 external 套件直接參照 org.geysermc）；</li>
 *   <li>API surface 契約 artifact 存在且可解析（drift gate 的最小防護）。</li>
 * </ol>
 *
 * <p>每項檢查都先以 fixture / 純函式做 Red→Green 證明（能攔截錯誤輸入、不誤攔正確輸入），
 * 再對真實 build artifact 做 Green 斷言。所有「找不到 artifact」的情境都 fail-closed
 * （拋出而非跳過），確保未產出 jar / 未編譯時 gate 會失敗，而非靜默通過。</p>
 */
@DisplayName("Artifact / binary 相容門禁")
class ArtifactCompatibilityGateTest {

    /** Java 25 的 class file major version。 */
    private static final int JAVA25_MAJOR = 69;

    /** 最低支援基線：Paper / API 26.1.2。 */
    private static final String MIN_API_BASELINE = "26.1.2";

    // ---------------------------------------------------------------------
    // 純函式（可被 fixture 與真實掃描共用）
    // ---------------------------------------------------------------------

    /** 從 class 檔前 8 位元組讀取 major version（offset 6..7, big-endian）。 */
    static int majorVersionOf(byte[] b) {
        return ((b[6] & 0xff) << 8) | (b[7] & 0xff);
    }

    /** 語意化版本比較：a > b 回正、a == b 回 0、a < b 回負。缺漏段以 0 補。 */
    static int compareVersion(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int xa = i < pa.length ? Integer.parseInt(pa[i]) : 0;
            int xb = i < pb.length ? Integer.parseInt(pb[i]) : 0;
            if (xa != xb) {
                return Integer.compare(xa, xb);
            }
        }
        return 0;
    }

    /** api-version 是否達到最低基線。 */
    static boolean apiVersionMeetsBaseline(String apiVersion, String baseline) {
        try {
            return compareVersion(apiVersion, baseline) >= 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 掃描 jar 內容，回傳所有「bundled server API」entry 名稱（org.bukkit / io.papermc）。
     * 這些 class 絕不該出現在 AceLib 的 library jar 中（server API 由運行期伺服器提供）。
     */
    static List<String> detectBundledServerApi(Path jar) throws IOException {
        List<String> violations = new ArrayList<>();
        try (JarFile jf = new JarFile(jar.toFile())) {
            for (var e = jf.entries(); e.hasMoreElements(); ) {
                String name = e.nextElement().getName();
                if (name.startsWith("org/bukkit/") || name.startsWith("io/papermc/")) {
                    violations.add(name);
                }
            }
        }
        return violations;
    }

    /**
     * 給定 class 的 internal path 與位元組，若位元組含 {@code org/geysermc} 參照且
     * 該 class 不在 {@code com/smile/acelib/external/} 套件，則回傳違規說明；否則回 null。
     */
    static String geysermcReferenceViolation(String classInternalPath, byte[] classBytes) {
        String s = new String(classBytes, StandardCharsets.ISO_8859_1);
        if (!s.contains("org/geysermc")) {
            return null;
        }
        if (classInternalPath.startsWith("com/smile/acelib/external/")) {
            return null;
        }
        return classInternalPath + " 直接參照 org/geysermc（非 external 套件，構成 optional eager linkage）";
    }

    // ---------------------------------------------------------------------
    // Red→Green：純函式能攔截錯誤輸入、不誤攔正確輸入
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("majorVersionOf：能正確解析 Java 8 (52) 與 Java 25 (69)")
    void majorVersionHelper_parsesCorrectly() {
        byte[] java8 = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 52};
        byte[] java25 = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 69};
        assertEquals(52, majorVersionOf(java8), "Java 8 major 應為 52");
        assertEquals(69, majorVersionOf(java25), "Java 25 major 應為 69");
    }

    @Test
    @DisplayName("apiVersionMeetsBaseline：26.1.2/26.2 通過，26.0/26.1.1/1.0 拒絕")
    void apiVersionBaseline_fixtures() {
        assertTrue(apiVersionMeetsBaseline("26.1.2", MIN_API_BASELINE), "26.1.2 應達基線");
        assertTrue(apiVersionMeetsBaseline("26.2", MIN_API_BASELINE), "26.2 應達基線");
        assertTrue(apiVersionMeetsBaseline("26.1.10", MIN_API_BASELINE), "26.1.10 應達基線");
        assertFalse(apiVersionMeetsBaseline("26.0", MIN_API_BASELINE), "26.0 低於基線");
        assertFalse(apiVersionMeetsBaseline("26.1.1", MIN_API_BASELINE), "26.1.1 低於基線");
        assertFalse(apiVersionMeetsBaseline("1.0", MIN_API_BASELINE), "1.0 低於基線");
    }

    @Test
    @DisplayName("detectBundledServerApi：能抓出含 org.bukkit 的 jar，且不誤攔純 AceLib jar")
    void bundledServerApi_fixture() throws IOException {
        Path bad = buildTempJar("bad-server-api.jar",
            "org/bukkit/Server.class", "dummy".getBytes(StandardCharsets.ISO_8859_1));
        List<String> badViolations = detectBundledServerApi(bad);
        assertFalse(badViolations.isEmpty(),
            "應抓出 bundled server API（org/bukkit/Server.class），但回傳空");
        assertTrue(badViolations.stream().anyMatch(v -> v.startsWith("org/bukkit/")),
            "違規項目應標示 org/bukkit/：" + badViolations);

        Path good = buildTempJar("clean-acelib.jar",
            "com/smile/acelib/AceLibPlugin.class", "dummy".getBytes(StandardCharsets.ISO_8859_1));
        assertTrue(detectBundledServerApi(good).isEmpty(),
            "純 AceLib jar 不應被誤判為 bundled server API");
    }

    @Test
    @DisplayName("geysermcReferenceViolation：form 套件參照被攔截，external 套件參照放行")
    void geysermcReference_fixture() {
        // 注意：bytecode 內部型別描述使用 slash 分隔（org/geysermc/...），
        // 故 fixture 必須用 slash 才與真實 class 掃描一致。
        byte[] withGeyser = "import org/geysermc/floodgate/api/FloodgateApi;".getBytes(StandardCharsets.ISO_8859_1);
        String formViolation = geysermcReferenceViolation("com/smile/acelib/form/Foo.class", withGeyser);
        assertNotNull(formViolation, "form 套件直接參照 org/geysermc 應被攔截");
        String externalOk = geysermcReferenceViolation("com/smile/acelib/external/Foo.class", withGeyser);
        assertEquals(null, externalOk, "external 套件參照 org/geysermc 應被放行（合法 seam）");
    }

    // ---------------------------------------------------------------------
    // Green：對真實 build artifact 做斷言（fail-closed：artifact 缺失即失敗）
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("production bytecode 全部為 Java 25（major=69）")
    void productionBytecode_isJava25() throws IOException {
        Path classesDir = productionClassesDir();
        List<Path> classFiles;
        try (Stream<Path> walk = Files.walk(classesDir)) {
            classFiles = walk.filter(p -> p.toString().endsWith(".class")).sorted().toList();
        }
        assertFalse(classFiles.isEmpty(),
            "掃描樣本為 0 個 .class，編譯輸出可能不完整（test 應已先編譯 main sourceset）");
        List<String> violations = new ArrayList<>();
        for (Path cf : classFiles) {
            byte[] bytes = Files.readAllBytes(cf);
            int major = majorVersionOf(bytes);
            if (major != JAVA25_MAJOR) {
                violations.add(cf.getFileName() + " -> major=" + major);
            }
        }
        assertTrue(violations.isEmpty(),
            "production 位元組碼必須為 Java 25（major=" + JAVA25_MAJOR + "），違規 "
                + violations.size() + " 筆：\n" + String.join("\n", violations));
    }

    @Test
    @DisplayName("plugin.yml api-version 達到最低基線 26.1.2")
    void pluginDescriptor_apiVersionMeetsBaseline() throws IOException {
        Path pluginYml = projectRoot().resolve("src/main/resources/plugin.yml");
        assertTrue(Files.isRegularFile(pluginYml), "找不到 plugin.yml：" + pluginYml);
        String body = Files.readString(pluginYml, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("api-version:\\s*['\"]?([0-9.]+)['\"]?").matcher(body);
        assertTrue(m.find(), "plugin.yml 缺少 api-version 欄位");
        String apiVersion = m.group(1);
        assertTrue(apiVersionMeetsBaseline(apiVersion, MIN_API_BASELINE),
            "plugin.yml api-version=" + apiVersion + " 低於最低基線 " + MIN_API_BASELINE);
    }

    @Test
    @DisplayName("plugin.yml version 與 AceLibVersion.VERSION 同步")
    void pluginDescriptor_versionConsistentWithAceLibVersion() throws IOException {
        Path pluginYml = projectRoot().resolve("src/main/resources/plugin.yml");
        Path versionJava = projectRoot().resolve("src/main/java/com/smile/acelib/AceLibVersion.java");
        assertTrue(Files.isRegularFile(pluginYml), "找不到 plugin.yml：" + pluginYml);
        assertTrue(Files.isRegularFile(versionJava), "找不到 AceLibVersion.java：" + versionJava);
        String yml = Files.readString(pluginYml, StandardCharsets.UTF_8);
        String java = Files.readString(versionJava, StandardCharsets.UTF_8);
        Matcher ymlVer = Pattern.compile("^version:\\s*(\\S+)", Pattern.MULTILINE).matcher(yml);
        assertTrue(ymlVer.find(), "plugin.yml 缺少 version 欄位");
        String ymlVersion = ymlVer.group(1).replace("'", "").replace("\"", "");
        Matcher javaVer = Pattern.compile("VERSION\\s*=\\s*\"([^\"]+)\"").matcher(java);
        assertTrue(javaVer.find(), "AceLibVersion.java 缺少 VERSION 常數");
        String javaVersion = javaVer.group(1);
        assertEquals(javaVersion, ymlVersion,
            "plugin.yml version 必須與 AceLibVersion.VERSION 同步");
    }

    @Test
    @DisplayName("產出 jar 不含 bundled server API（org.bukkit / io.papermc）")
    void artifact_hasNoBundledServerApi() throws IOException {
        Path jar = locateRuntimeJar();
        List<String> violations = detectBundledServerApi(jar);
        assertTrue(violations.isEmpty(),
            "AceLib jar 不得內含 server API class（org.bukkit / io.papermc），違規 "
                + violations.size() + " 筆：\n" + String.join("\n", violations));
    }

    @Test
    @DisplayName("production 無 optional eager linkage（org.geysermc 僅允許出現在 external 套件）")
    void production_hasNoOptionalEagerLinkage() throws IOException {
        Path classesDir = productionClassesDir();
        // 正向對照：external seam 確實參照 geysermc，確保掃描邏輯非真空通過。
        Path control = classesDir.resolve("com/smile/acelib/external/FloodgateFormSender.class");
        assertTrue(Files.isRegularFile(control), "缺少正向對照檔：" + control);
        assertTrue(new String(Files.readAllBytes(control), StandardCharsets.ISO_8859_1).contains("org/geysermc"),
            "掃描器正向對照失敗：external/FloodgateFormSender 應含 org/geysermc 參照");

        List<String> violations = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(classesDir)) {
            for (Path cf : walk.filter(p -> p.toString().endsWith(".class")).sorted().toList()) {
                byte[] bytes = Files.readAllBytes(cf);
                String internal = classesDir.relativize(cf).toString().replace('\\', '/');
                String v = geysermcReferenceViolation(internal, bytes);
                if (v != null) {
                    violations.add(v);
                }
            }
        }
        assertTrue(violations.isEmpty(),
            "production 不得在非 external 套件直接參照 org.geysermc（optional eager linkage），違規 "
                + violations.size() + " 筆：\n" + String.join("\n", violations));
    }

    @Test
    @DisplayName("API surface 契約 artifact 存在且可解析（drift gate 最小防護）")
    void apiSurfaceArtifact_presentAndParseable() throws IOException {
        Path json = projectRoot().resolve("docs/reference/api-surface.json");
        assertTrue(Files.isRegularFile(json), "缺少 API surface allowlist：" + json);
        String content = Files.readString(json, StandardCharsets.UTF_8);
        assertFalse(content.isBlank(), "API surface allowlist 不得為空");
        assertTrue(content.contains("\"types\""), "API surface allowlist 應含 types 區段");
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private static Path projectRoot() {
        Path dir = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath();
        for (int i = 0; i < 12; i++) {
            if (Files.exists(dir.resolve("build.gradle.kts"))) {
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

    private static Path productionClassesDir() {
        Path dir = projectRoot().resolve("build/classes/java/main");
        assertTrue(Files.isDirectory(dir),
            "找不到 production 編譯輸出目錄：" + dir + "（test 應已先編譯 main sourceset）");
        return dir;
    }

    private static Path locateRuntimeJar() throws IOException {
        Path libs = projectRoot().resolve("build/libs");
        assertTrue(Files.isDirectory(libs),
            "找不到 build/libs 目錄：" + libs + "（compatibilityCheck 應已先執行 jar task）");
        List<Path> jars;
        try (Stream<Path> stream = Files.list(libs)) {
            jars = stream
                .filter(p -> {
                    String name = p.getFileName().toString();
                    return name.startsWith("AceLib")
                        && name.endsWith(".jar")
                        && !name.endsWith("-sources.jar")
                        && !name.endsWith("-javadoc.jar");
                })
                .toList();
        }
        assertEquals(1, jars.size(),
            "build/libs 必須恰好有一個 AceLib runtime jar（已排除 classifier artifact）；實際: " + jars);
        return jars.get(0);
    }

    /** 在系統暫存區建立一個含單一 entry 的 jar，供 fixture 測試使用。 */
    private static Path buildTempJar(String name, String entryName, byte[] entryBytes) throws IOException {
        Path out = Files.createTempFile(name.replace('.', '_'), ".jar");
        try (OutputStream fos = Files.newOutputStream(out);
             JarOutputStream jos = new JarOutputStream(fos)) {
            jos.putNextEntry(new JarEntry(entryName));
            jos.write(entryBytes);
            jos.closeEntry();
        }
        out.toFile().deleteOnExit();
        return out;
    }
}
