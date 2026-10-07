package com.smile.acelib.milestone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * v0.1.0 milestone：驗證 Gradle 產出的 AceLib jar 內含正確的 {@code plugin.yml}
 * 描述子。這是「build artifact 契約」測試，避免 {@code plugin.yml} 在重構過程中
 * 意外掉欄位、掉 {@code folia-supported} 或掉 {@code acelib.admin} 權限。
 *
 * <p>測試從 {@code build/libs/} 找出實際的 AceLib jar（單一檔案），打開後讀
 * {@code plugin.yml} 並比對必要欄位。所有測試只看 build artifact，
 * <strong>不</strong>直接讀源碼樹的 {@code plugin.yml}，確保「source 與 artifact
 * 同步」的契約。</p>
 */
@DisplayName("Milestone v0.1.0 build jar plugin descriptor")
class PluginDescriptorJarTest {

    private static final Path LIBS_DIR = Path.of("build", "libs").toAbsolutePath();
    private static final String PLUGIN_YML_ENTRY = "plugin.yml";
    private static final String PLUGIN_NAME = "AceLib";
        // Gradle `withSourcesJar()` / `withJavadocJar()` 會額外產生 classifier 為
        // `sources` / `javadoc` 的 jar，與 runtime plugin jar 共存於 build/libs/。
        // filter 必須排除這兩個 classifier，否則會誤把 auxiliary jar 當成
        // runtime plugin jar。
        // `java-test-fixtures` 另產出 classifier 為 `test-fixtures` 的 jar
        // （下游單元測試用），同樣排除在 runtime 候選外（與 release workflow
        // 及 ArtifactCompatibilityGateTest 的選取規則一致）。
        private static final String SOURCES_CLASSIFIER_SUFFIX = "-sources.jar";
        private static final String JAVADOC_CLASSIFIER_SUFFIX = "-javadoc.jar";
        private static final String TEST_FIXTURES_CLASSIFIER_SUFFIX = "-test-fixtures.jar";

    private static Path jarPath;
    private static String pluginYmlContent;
    private static Object parsedRoot;

    @BeforeAll
    static void locateJarAndReadDescriptor() throws IOException {
        if (!Files.isDirectory(LIBS_DIR)) {
            throw new IllegalStateException(
                "找不到 build/libs 目錄：請先跑 `./gradlew jar` 再執行此測試。"
                    + "路徑: " + LIBS_DIR);
        }
        try (Stream<Path> stream = Files.list(LIBS_DIR)) {
            List<Path> jars = stream
                .filter(p -> {
                    String name = p.getFileName().toString();
                    return name.startsWith(PLUGIN_NAME)
                        && name.endsWith(".jar")
                        && !name.endsWith(SOURCES_CLASSIFIER_SUFFIX)
                        && !name.endsWith(JAVADOC_CLASSIFIER_SUFFIX)
                        && !name.endsWith(TEST_FIXTURES_CLASSIFIER_SUFFIX);
                })
                .toList();
            assertEquals(1, jars.size(),
                "build/libs 必須恰好有一個 AceLib runtime jar（已排除 classifier artifact）；實際: " + jars);
            jarPath = jars.get(0);
        }
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry entry = jar.getJarEntry(PLUGIN_YML_ENTRY);
            assertNotNull(entry,
                "AceLib jar 內必須含 " + PLUGIN_YML_ENTRY + "；實際 jar: " + jarPath);
            try (InputStream in = jar.getInputStream(entry)) {
                pluginYmlContent = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        // YAML 解析（snakeyaml 已在 MockBukkit test scope 內 transitive）
        parsedRoot = new Yaml().load(pluginYmlContent);
        assertNotNull(parsedRoot, "plugin.yml 解析後不可為 null；內容:\n" + pluginYmlContent);
    }

    @Test
    @DisplayName("plugin.yml.name 必須為 AceLib")
    void nameIsAceLib() {
        assertEquals(PLUGIN_NAME, stringOf(parsedRoot, "name"),
            "plugin.yml.name 必須為 '" + PLUGIN_NAME + "'；實際: "
                + pluginYmlContent);
    }

    @Test
    @DisplayName("plugin.yml.main 必須指向 com.smile.acelib.AceLibPlugin")
    void mainPointsToAceLibPlugin() {
        assertEquals("com.smile.acelib.AceLibPlugin", stringOf(parsedRoot, "main"),
            "plugin.yml.main 必須為 'com.smile.acelib.AceLibPlugin'；實際: "
                + pluginYmlContent);
    }

    @Test
    @DisplayName("plugin.yml.folia-supported 必須為 true（Folia-first 契約）")
    void foliaSupportedIsTrue() {
        Object value = objectOf(parsedRoot, "folia-supported");
        assertEquals(Boolean.TRUE, value,
            "plugin.yml.folia-supported 必須為 true（Folia-first 契約）；實際: "
                + (value == null ? "<missing>" : value));
    }

    @Test
    @DisplayName("plugin.yml 不再宣告 commands 區塊（v1.4.0 Brigadier 遷移）")
    void commandsBlockAbsentAfterBrigadierMigration() {
        // v1.4.0 型別化指令框架：/acelib 改經 Paper Brigadier 生命週期
        // （LifecycleEvents.COMMANDS）註冊，不再需要 plugin.yml 的
        // commands 宣告。本測試由「必須宣告」改為「必須缺席」，
        // 避免雙註冊（Bukkit PluginCommand 與 Brigadier literal 同名覆蓋
        // 語意隨版本而定）；權限節點仍由 permissions 區塊宣告（見下一個測試）。
        Object commands = objectOf(parsedRoot, "commands");
        assertTrue(commands == null,
            "plugin.yml 不可再有 commands 區塊（Brigadier 註冊取代宣告需求）；實際: "
                + pluginYmlContent);
    }

    @Test
    @DisplayName("plugin.yml.permissions 必須宣告 acelib.admin 節點")
    void permissionsDeclaresAcelibAdmin() {
        Object permissions = objectOf(parsedRoot, "permissions");
        assertNotNull(permissions,
            "plugin.yml 缺少 permissions 區塊；實際: " + pluginYmlContent);
        assertTrue(permissions instanceof java.util.Map,
            "permissions 區塊必須是 map；實際: " + permissions.getClass());
        assertTrue(((java.util.Map<?, ?>) permissions).containsKey("acelib.admin"),
            "permissions 必須宣告 'acelib.admin' 節點；實際 keys: "
                + ((java.util.Map<?, ?>) permissions).keySet());
    }

    // --- helpers ---

    private static String stringOf(Object root, String key) {
        if (!(root instanceof java.util.Map<?, ?> map)) {
            throw new IllegalStateException("plugin.yml root 不是 map");
        }
        Object v = map.get(key);
        return v == null ? null : v.toString();
    }

    private static Object objectOf(Object root, String key) {
        if (!(root instanceof java.util.Map<?, ?> map)) {
            return null;
        }
        return map.get(key);
    }
}
