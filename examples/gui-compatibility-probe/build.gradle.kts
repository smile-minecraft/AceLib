// GUI 相容性探針 plugin：部署到 Folia 測試服，用 /gprobe 指令把固定、可重複的
// GUI 探針案例經 AceLib GuiScope（插件隔離 handle）開啟給玩家，供真人用
// Java 客戶端觀察導航／票券／輸入／冷卻，並用 Bedrock（經 Geyser）客戶端
// 觀察表單派送。
//
// 本探針只使用 AceLib 公開 Supported API（GuiScopes / GuiScope / GuiView /
// GuiButton / GuiInputPrompt / FormSpec / AceLibApi），不碰 internal package。
//
// 建置需要兩步（AceLib 以 mavenLocal 解析，與 consumer-plugin 相同慣例）：
//   1. 在 AceLib 根目錄執行 `./gradlew publishToMavenLocal`
//   2. `./gradlew -p examples/gui-compatibility-probe build`
// 產出 jar 位於 examples/gui-compatibility-probe/build/libs/。
//
// 測試（案例目錄完整性與選擇語意）同樣獨立執行：
//   ./gradlew -p examples/gui-compatibility-probe test
//
// 本探針刻意不接進根專案的 docsCheck / compatibilityCheck（與
// form-compatibility-probe 相同慣例：探針是部署到測試服的人工作業，不屬於
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
    // AceLib 以 mavenLocal 解析本地 publish 產物（com.smile:acelib:1.4.0，
    // GUI 隔離作用域等新 API 只存在於 1.4.0；先在根目錄執行
    // `./gradlew publishToMavenLocal` 再建置本探針）。
    // compileOnly：運行期由伺服器上的 AceLib plugin 提供（plugin.yml 另以
    // depend: [AceLib] 保證載入順序）。
    compileOnly("com.smile:acelib:1.4.0")
    // Paper/Folia API 由伺服器 runtime 提供，編譯期只需要 API 面。
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.72-stable")

    // 測試需要 GuiScope 相關型別進入 runtime classpath。
    testImplementation("com.smile:acelib:1.4.0")
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
