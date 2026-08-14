package com.smile.acelib.qualitygate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 文件品質門禁（docsCheck）wiring 契約守護。
 *
 * <p>驗證 root Gradle 聚合 docsCheck 的宣告與依賴，以及 CI workflow 是否顯式執行
 * docsCheck 並上傳 HTML Javadoc artifact。本測試以靜態檔案掃描為主（與
 * {@code SmokeScriptTest} / {@code PublicationConsistencyTest} 同風格），
 * 不啟動 Gradle；真正的執行行為由 CI 與 docsCheck 的 task graph 驗證。</p>
 *
 * <p>本測試不依賴 Bukkit / MockBukkit，純讀取 repo 檔案。</p>
 */
class DocsQualityGateWiringTest {

    private Path projectRoot() {
        Path dir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath();
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
        return Path.of(System.getProperty("user.dir", ".")).toAbsolutePath();
    }

    private String read(String relativePath) throws IOException {
        Path path = projectRoot().resolve(relativePath);
        assertTrue(Files.exists(path), "缺少檔案：" + relativePath);
        return Files.readString(path);
    }

    @Test
    void rootDocsCheckAggregatesQualityGates() throws IOException {
        String build = read("build.gradle.kts");
        // docsCheck 必須存在，且聚合 javadoc（doclint）、publication verification、
        // consumer fixture gate 與 root tests（API surface/signature/docs coverage）。
        assertTrue(build.contains("docsCheck"),
            "build.gradle.kts 應宣告 docsCheck 聚合 task");
        assertTrue(build.contains("javadoc"),
            "docsCheck 應聚合 javadoc（doclint 保持啟用）");
        assertTrue(build.contains("verifyPublication"),
            "docsCheck 應聚合 verifyPublication（publication coordinates/version/artifacts）");
        assertTrue(build.contains("consumerFixtureCheck"),
            "docsCheck 應聚合 consumer fixture gate（compile + verifyConsumerDocs）");
        assertTrue(build.contains("dependsOn"),
            "docsCheck 應以 dependsOn 表達聚合依賴，避免並行 build race");
    }

    @Test
    void ciRunsDocsCheckAndUploadsJavadocArtifact() throws IOException {
        String ci = read(".github/workflows/ci.yml");
        assertTrue(ci.contains("docsCheck"),
            "CI 應顯式執行 docsCheck");
        assertTrue(ci.contains("actions/upload-artifact"),
            "CI 應上傳 HTML Javadoc artifact");
        assertTrue(ci.contains("build/docs/javadoc"),
            "CI 上傳路徑應為 root javadoc HTML 輸出 build/docs/javadoc");
        assertTrue(ci.contains("contents: read"),
            "CI 應維持最小 contents: read 權限");
        assertFalse(ci.contains("${{ secrets"),
            "CI 不得引用 secrets（不應出現 ${{ secrets 片段）");
        assertFalse(ci.contains("Xdoclint:none"),
            "CI 不得以 Xdoclint:none 關閉 doclint");
    }

    @Test
    void fixtureNotPartOfFormalPublication() throws IOException {
        String fixture = read("examples/consumer-plugin/build.gradle.kts");
        // consumer fixture 是編譯驗證用途，不得套用 maven-publish 或宣告 publication。
        assertFalse(fixture.contains("maven-publish"),
            "consumer fixture 不得套用 maven-publish（不進正式 publication）");
        assertFalse(fixture.contains("MavenPublication"),
            "consumer fixture 不得宣告 MavenPublication");
        assertTrue(fixture.contains("verifyConsumerDocs"),
            "consumer fixture 應保留 verifyConsumerDocs（stale/link/anchor/version policy 不刪）");
    }

    @Test
    void doclintPolicyRemainsEnforced() throws IOException {
        String build = read("build.gradle.kts");
        assertFalse(build.contains("Xdoclint"),
            "build.gradle.kts 不得存在任何 Xdoclint bypass 設定");
    }
}
