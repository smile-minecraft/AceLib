// AceLib — Folia-first base library plugin for Smile Minecraft plugins.
//
// 此 Gradle 腳本使用 Kotlin DSL；依賴與 toolchain 設定集中於此。
// Library plugin 不需 fat jar，因此不引入 shadow。

plugins {
    java
}

group = "com.smile"
version = "0.5.0-SNAPSHOT"

// Java 25 是 Paper 26.1+ 的最低需求；保留 toolchain 確保跨開發者一致。
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    withSourcesJar()
}

repositories {
    // Paper 與 Folia 官方 artifact 倉庫
    maven("https://repo.papermc.io/repository/maven-public/")
    mavenCentral()
}

dependencies {
    // 編譯期需要 paper-api；運行期由伺服器提供（provided scope）
    // 版本固定為 26.1.2.build.72-stable 以對齊 MockBukkit 4.113.1 的 paper-api 版本，
    // 避免 binary incompatible 問題。如需升級 paper-api 須同步升級 MockBukkit。
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.72-stable")

    // JetBrains 註解 (org.jetbrains:annotations) — 標記 @NotNull 等
    compileOnly("org.jetbrains:annotations:24.1.0")

    // 測試框架
    testImplementation(platform("org.junit:junit-bom:5.11.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // 測試亦需 Bukkit 類別以 mock Server / PluginManager / PluginDescriptionFile
    // 注意：因 AceLibPlugin extends JavaPlugin，測試需在 runtime 載入 JavaPlugin，
    // 因此這裡使用 testImplementation（讓 class 進入 runtime classpath）而非 testCompileOnly。
    testImplementation("io.papermc.paper:paper-api:26.1.2.build.72-stable")

    // Mockito 用於 mock JavaPlugin / Server
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.11.0")

    // MockBukkit：在測試環境模擬 Bukkit/Paper server，解決 JavaPlugin 建構子
    // 呼叫 Bukkit.getUnsafe() 而導致 NPE 的問題。
    // MockBukkit 4.x 起改用新 groupId `org.mockbukkit.mockbukkit`，並依 paper-api
    // 版本區分子 artifact（mockbukkit-v26.1.2 內含 paper-api 26.1.2）。
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v26.1.2:4.113.1")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
        showExceptions = true
        showCauses = true
        showStackTraces = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// plugin.yml 版本欄位採硬編碼（與 build.gradle.kts 的 version 同步），
// 避免 Gradle 8+ configuration cache 與 Ant filter 的相容性問題。
// Phase 1+ 可改用自訂 task 或 expand() 動態注入。