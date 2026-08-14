package com.smile.acelib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 發布座標與版本一致性守護測試。
 *
 * <p>確保 build.gradle.kts、plugin.yml 與 AceLibVersion.java 三處的版本字串一致，
 * 且 publication 座標（groupId=com.smile、artifactId=acelib）可由專案推導。
 * 此測試不依賴任何外部 repository 或 secret。
 */
class PublicationConsistencyTest {

    private static final Pattern BUILD_VERSION =
            Pattern.compile("version\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern PLUGIN_VERSION =
            Pattern.compile("^version:\\s*([^\\s#]+)", Pattern.MULTILINE);
    private static final Pattern VERSION_CONST =
            Pattern.compile("VERSION\\s*=\\s*\"([^\"]+)\"");

    private String readProjectFile(String relativePath) throws IOException {
        Path path = Path.of(relativePath);
        assertTrue(Files.exists(path), "找不到專案檔案：" + relativePath);
        return Files.readString(path);
    }

    private String firstGroup(Pattern pattern, String text, String source) {
        Matcher matcher = pattern.matcher(text);
        assertTrue(matcher.find(), "無法從 " + source + " 解析版本");
        return matcher.group(1);
    }

    @Test
    void buildPluginAndVersionClassVersionsAreConsistent() throws IOException {
        String build = readProjectFile("build.gradle.kts");
        String pluginYml = readProjectFile("src/main/resources/plugin.yml");
        String versionJava = readProjectFile("src/main/java/com/smile/acelib/AceLibVersion.java");

        String buildVersion = firstGroup(BUILD_VERSION, build, "build.gradle.kts");
        String pluginVersion = firstGroup(PLUGIN_VERSION, pluginYml, "plugin.yml");
        String classVersion = firstGroup(VERSION_CONST, versionJava, "AceLibVersion.java");

        assertEquals(buildVersion, pluginVersion, "plugin.yml 版本應與 build.gradle.kts 一致");
        assertEquals(buildVersion, classVersion, "AceLibVersion.java 版本應與 build.gradle.kts 一致");
        assertFalse(buildVersion.isBlank(), "版本不得為空");
    }

    @Test
    void publicationCoordinatesAreDerivable() throws IOException {
        String build = readProjectFile("build.gradle.kts");
        assertTrue(build.contains("`maven-publish`") || build.contains("maven-publish"),
                "build.gradle.kts 應套用 maven-publish plugin");
        assertTrue(build.contains("groupId = \"com.smile\""),
                "publication groupId 應為 com.smile");
        assertTrue(build.contains("artifactId = \"acelib\""),
                "publication artifactId 應為 acelib");
        assertTrue(build.contains("withJavadocJar()"),
                "應啟用 javadoc jar 以產出 Javadoc artifact");
        assertTrue(build.contains("withSourcesJar()"),
                "應啟用 sources jar 以產出 sources artifact");
    }

    @Test
    void doclintMustNotBeDisabled() throws IOException {
        String build = readProjectFile("build.gradle.kts");
        assertFalse(build.contains("Xdoclint:none"),
                "build.gradle.kts 不得以 Xdoclint:none 關閉 doclint");
        assertFalse(build.contains("Xdoclint"),
                "build.gradle.kts 不得存在任何 Xdoclint bypass 設定");
    }

    @Test
    void pomUrlAndScmMustPointToActualRepository() throws IOException {
        String build = readProjectFile("build.gradle.kts");
        assertTrue(build.contains("https://github.com/smile-minecraft/AceLib"),
                "POM URL/SCM 應指向實際 repository smile-minecraft/AceLib");
        // 只驗證 GitHub URL 設定值（license URL 為 opensource.org，不在此範圍）；
        // verifyPublication 的舊 URL 反向守護本身會包含舊字串。
        Pattern urlValue = Pattern.compile("(?:url|connection|developerConnection)\\.set\\(\"(https://github\\.com/[^\"]+)\"\\)");
        Matcher matcher = urlValue.matcher(build);
        assertTrue(matcher.find(), "build.gradle.kts 應宣告 POM url/scm 設定");
        do {
            String value = matcher.group(1);
            assertTrue(value.startsWith("https://github.com/smile-minecraft/AceLib"),
                    "POM URL/SCM 設定值應指向實際 repository（實際: " + value + "）");
        } while (matcher.find());
    }
}
