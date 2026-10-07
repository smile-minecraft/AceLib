// 指令相容性探針 plugin：部署到 Paper / Folia 測試服，實測 v1.4.0 型別化
// 指令框架的執行緒、錯誤提示與補全，並驗證 reload／重複註冊／disable
// 無殘留。註冊本身走 AceLib 的 BrigadierRegistrar（因此本 plugin 的
// plugin.yml 不宣告 commands 區塊 — 這正是本探針要驗收的行為之一）。
//
// 本探針只使用 AceLib 公開 Supported API（BrigadierRegistrar / TypedCommand /
// TypedSubCommand / Arguments / CommandArgument / ReplySink），不碰 internal package。
//
// 建置需要兩步（AceLib 以 mavenLocal 解析，與其他探針相同慣例）：
//   1. 在 AceLib 根目錄執行 `./gradlew publishToMavenLocal`
//   2. `./gradlew -p examples/command-compatibility-probe build`
// 產出 jar 位於 examples/command-compatibility-probe/build/libs/。
//
// 測試（探針案例目錄完整性）獨立執行：
//   ./gradlew -p examples/command-compatibility-probe test
//
// 本探針刻意不接進根專案的 docsCheck / compatibilityCheck（與其他探針相同
// 慣例：探針是部署到測試服的人工作業，不屬於每次建置都要跑的門禁）。
plugins {
    java
}

group = "com.smile.test"
version = "1.0.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenLocal()
    maven("https://repo.papermc.io/repository/maven-public/")
    mavenCentral()
}

dependencies {
    // AceLib 以 mavenLocal 解析本地 publish 產物（com.smile:acelib:1.4.0-SNAPSHOT，
    // 型別化指令框架只存在於 1.4.0；先在根目錄執行
    // `./gradlew publishToMavenLocal` 再建置本探針）。
    compileOnly("com.smile:acelib:1.4.0-SNAPSHOT")
    // Paper/Folia API 由伺服器 runtime 提供，編譯期只需要 API 面。
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.72-stable")

    testImplementation("com.smile:acelib:1.4.0-SNAPSHOT")
    testImplementation("io.papermc.paper:paper-api:26.1.2.build.72-stable")
    testImplementation(platform("org.junit:junit-bom:5.11.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.compileJava {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}