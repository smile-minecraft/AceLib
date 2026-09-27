// 表單相容性探針 plugin：部署到 Folia 測試服，用 /fprobe 指令把固定、可重複的
// FormSpec 測試案例經 AceLib FormService 發送給玩家，供真人用 Bedrock（經 Geyser）
// 客戶端觀察轉換結果。
//
// 本探針只使用 AceLib 公開 Supported API（FormSpec / FormImage / FormText /
// FormTextOptions / AceLibApi），不碰 internal package；Cumulus / Floodgate 型別
// 不出現在探針原始碼，轉換由 AceLib 內部翻譯層負責。
//
// 建置需要兩步（AceLib 以 mavenLocal 解析，與 consumer-plugin 相同慣例）：
//   1. 在 AceLib 根目錄執行 `./gradlew publishToMavenLocal`
//   2. `./gradlew -p examples/form-compatibility-probe build`
// 產出 jar 位於 examples/form-compatibility-probe/build/libs/。
//
// 測試（案例目錄完整性）同樣獨立執行：
//   ./gradlew -p examples/form-compatibility-probe test
//
// 本探針刻意不接進根專案的 docsCheck / compatibilityCheck（與
// message-compatibility-probe 相同慣例：探針是部署到測試服的人工作業，不屬於
// 每次建置都要跑的門禁）。
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
    // AceLib 以 mavenLocal 解析本地 publish 產物（com.smile:acelib:1.3.0，
    // 僅供貢獻者本地開發；公開安裝座標為 JitPack，見 consumer-plugin 註解）。
    // compileOnly：運行期由伺服器上的 AceLib plugin 提供（plugin.yml 另以
    // depend: [AceLib] 保證載入順序）。
    compileOnly("com.smile:acelib:1.3.0")
    // Paper/Folia API 由伺服器 runtime 提供，編譯期只需要 API 面。
    // paper-api 的 POM 會把 adventure-api / adventure-text-minimessage 以
    // compileOnly 形式帶入，因此本探針可直接使用 net.kyori.adventure.text.* 建構案例。
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.72-stable")

    // 測試需要 FormSpec / FormText 型別與 Component 型別進入 runtime classpath。
    testImplementation("com.smile:acelib:1.3.0")
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
