// 基岩功能驗收工具 plugin：部署到 Folia 測試服，用 /btest 指令診斷
// floodgate 整合狀態並讓真人以基岩客戶端操作三種表單。
//
// 建置方式（與 consumer fixture 相同的兩步）：
//   1. 先在 AceLib 根目錄執行 `./gradlew publishToMavenLocal`
//   2. 再執行 `./gradlew -p examples/bedrock-test-plugin build`
//
// 本專案是測試服驗收工具，不發布；AceLib 以 mavenLocal 座標
// com.smile:acelib:1.0.0 解析本地 publish 產物（僅供貢獻者本地開發，
// 公開安裝請使用 JitPack com.github.smile-minecraft:AceLib:v1.0.0）。
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
    compileOnly("com.smile:acelib:1.0.0")
    // Paper/Folia API 由伺服器 runtime 提供，編譯期只需要 API 面。
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.72-stable")
}

tasks.compileJava {
    options.encoding = "UTF-8"
}
