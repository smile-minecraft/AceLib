// 訊息相容性探針 plugin：部署到 Folia 測試服，用 /mprobe 指令把固定、可重複的
// Adventure Component 測試案例發送給玩家，供真人用 Java 與 Bedrock（經 Geyser）
// 客戶端觀察轉換結果。
//
// 本 plugin 不依賴 AceLib（直接走 Bukkit/Paper 原生的 Component 送出入口），
// 因此建置只需一步：
//   ./gradlew -p examples/message-compatibility-probe build
// 產出 jar 位於 examples/message-compatibility-probe/build/libs/。
//
// 測試（案例目錄完整性）同樣一步執行：
//   ./gradlew -p examples/message-compatibility-probe test
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
    maven("https://repo.papermc.io/repository/maven-public/")
    mavenCentral()
}

dependencies {
    // Paper/Folia API 由伺服器 runtime 提供，編譯期只需要 API 面。
    // paper-api 的 POM 會把 adventure-api / adventure-text-minimessage /
    // adventure-text-serializer-plain 以 compileOnly 形式帶入，因此本探針
    // 可直接使用 net.kyori.adventure.text.* 與 MiniMessage 建構案例。
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.72-stable")

    // 測試需要 Component 型別進入 runtime classpath，故用 testImplementation。
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
