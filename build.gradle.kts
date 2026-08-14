// AceLib — Folia-first base library plugin for Smile Minecraft plugins.
//
// 此 Gradle 腳本使用 Kotlin DSL；依賴與 toolchain 設定集中於此。
// Library plugin 不需 fat jar，因此不引入 shadow。

plugins {
    java
    `maven-publish`
}

group = "com.smile"
version = "1.0.0"

// Java 25 是 Paper 26.1+ 的最低需求；保留 toolchain 確保跨開發者一致。
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    withSourcesJar()
    withJavadocJar()
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
    // 把 CLI 的 -Dacelib.genSignatureBaseline 明確轉傳給 test worker，
    // 讓 signature baseline generation guard（fail-closed）在 system-property
    // 路徑同樣生效（否則 Gradle CLI -D 預設不會進 test JVM）。
    // getOrElse("") 在組態期解析一次；此 property 是 developer 維護用開關，
    // 變更會觸發 configuration-cache 重新評估，不影響 publishing 行為。
    systemProperty(
        "acelib.genSignatureBaseline",
        providers.systemProperty("acelib.genSignatureBaseline").getOrElse("")
    )
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
        showExceptions = true
        showCauses = true
        showStackTraces = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// javadoc artifact 產出：doclint 保持啟用（預設），確保公開 Javadoc 品質。
// source 註解若有 doclint 不相容標記，必須在 source 中修正，不得關閉檢查。
tasks.withType<Javadoc>().configureEach {
    val javadocOptions = options as org.gradle.external.javadoc.StandardJavadocDocletOptions
    javadocOptions.encoding = "UTF-8"
    // @implSpec 是 JDK 標準 tag（JEP 224），doclet 需註冊才能辨識；
    // 註冊不等於關閉 doclint，僅讓合法 tag 通過檢查。
    javadocOptions.tags("implSpec:a:Implementation Specification:")
}

// plugin.yml 版本欄位採硬編碼（與 build.gradle.kts 的 version 同步），
// 避免 Gradle 8+ configuration cache 與 Ant filter 的相容性問題。
// Phase 1+ 可改用自訂 task 或 expand() 動態注入。

// ---------------------------------------------------------------------------
// 發布基礎（Maven Local / JitPack / Maven Central 預備）
// ---------------------------------------------------------------------------
// 座標：groupId=com.smile、artifactId=acelib、version=project.version。
// 顯式設定 artifactId 以避免 rootProject.name（AceLib）的大小寫差異影響座標。
// 不引入 signing plugin，因此無需任何 secret / credential 即可發布到 mavenLocal。
publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            groupId = "com.smile"
            artifactId = "acelib"
            version = project.version.toString()
            from(components["java"])
            pom {
                name.set("AceLib")
                description.set("Folia-first base library for Smile Minecraft plugins")
                url.set("https://github.com/smile-minecraft/AceLib")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("smile")
                        name.set("Smile")
                    }
                }
                scm {
                    url.set("https://github.com/smile-minecraft/AceLib")
                    connection.set("scm:git:https://github.com/smile-minecraft/AceLib.git")
                    developerConnection.set("scm:git:https://github.com/smile-minecraft/AceLib.git")
                }
            }
        }
    }
}

// verifyPublication：在 publishToMavenLocal 之後檢查四類 artifact 是否產出，
// 並驗證 POM 座標與版本在三處來源（build.gradle.kts / plugin.yml / AceLibVersion.java）
// 的一致性。此 task 不依賴任何外部 repository 或 secret。
// 注意：組態期先將 repo 路徑 / 版本 / projectDir 擷取為可序列化區域變數，
// 避免 doLast 直接捕獲 project（與 gradle.properties 的 configuration-cache 相容）。
val verifyPublication by tasks.registering {
    dependsOn("publishToMavenLocal")
    val repoLocal = System.getProperty("maven.repo.local")
        ?: (System.getProperty("user.home") + "/.m2/repository")
    val artifactVersion = project.version.toString()
    val projectDirFile = project.projectDir
    doLast {
        val base = File(repoLocal, "com/smile/acelib/$artifactVersion")
        val expected = listOf(
            "acelib-$artifactVersion.jar",
            "acelib-$artifactVersion.pom",
            "acelib-$artifactVersion-sources.jar",
            "acelib-$artifactVersion-javadoc.jar"
        )
        val missing = expected.filter { !File(base, it).exists() }
        require(missing.isEmpty()) {
            "publishToMavenLocal 未產出預期 artifact（缺少：$missing），目錄：$base"
        }

        val pom = File(base, "acelib-$artifactVersion.pom").readText()
        require(pom.contains("<groupId>com.smile</groupId>")) { "POM groupId 不一致" }
        require(pom.contains("<artifactId>acelib</artifactId>")) { "POM artifactId 不一致" }
        require(pom.contains("<version>$artifactVersion</version>")) { "POM version 不一致" }
        require(pom.contains("https://github.com/smile-minecraft/AceLib")) {
            "POM URL/SCM 應指向實際 repository smile-minecraft/AceLib"
        }
        require(!pom.contains("https://github.com/smile/acelib")) {
            "POM 不得包含舊 repository URL（smile/acelib）"
        }

        val pluginYml = File(projectDirFile, "src/main/resources/plugin.yml").readText()
        require(pluginYml.contains("version: $artifactVersion")) { "plugin.yml 版本與 build 不一致" }
        val versionJava = File(projectDirFile, "src/main/java/com/smile/acelib/AceLibVersion.java").readText()
        require(versionJava.contains("VERSION = \"$artifactVersion\"")) { "AceLibVersion.java 版本與 build 不一致" }

        logger.lifecycle(
            "verifyPublication: 座標 com.smile:acelib:$artifactVersion 四類 artifact 與版本一致性檢查通過"
        )
    }
}

// ---------------------------------------------------------------------------
// docsCheck：文件品質門禁（fail-closed 聚合）
// ---------------------------------------------------------------------------
// 聚合 Javadoc（doclint 保持啟用）、publication verification、consumer fixture
// gate（compile + verifyConsumerDocs）與 root tests（API surface / signature /
// docs coverage）。任何一環失敗都會讓 docsCheck 失敗，確保本機與 PR/push CI
// 使用同一套文件品質契約。
//
// 競態處理：consumerFixtureCheck 依賴 publishToMavenLocal，確保 fixture 在
// mavenLocal 已有 com.smile:acelib artifact 之後才開始；verifyPublication 也
// 依賴同一個 publishToMavenLocal，Gradle 會把該 task 在 task graph 中只執行
// 一次並置於兩個消費者之前。fixture 使用獨立 Gradle invocation（-p 切換
// projectDir），其 build/ 輸出與 root build/ 分離，不共用目錄。
//
// 注意：docsCheck 依賴 root `test`，因此會連帶執行全部既有測試（含
// ApiSurfaceContractTest / ApiBoundaryRegressionTest / ApiSurfaceSignatureContractTest /
// PublicationConsistencyTest），不需要單獨再列這些測試類。
val consumerFixtureCheck by tasks.registering(Exec::class) {
    group = "verification"
    description = "編譯 consumer fixture 並執行 verifyConsumerDocs（stale symbol / link / anchor / version）"
    dependsOn("publishToMavenLocal")
    workingDir = project.projectDir
    // 以獨立 Gradle invocation 執行 fixture build；--no-daemon 避免與 root
    // build 共用 daemon/build 輸出（fixture 是獨立 project，見
    // examples/consumer-plugin/settings.gradle.kts）。
    commandLine("./gradlew", "-p", "examples/consumer-plugin", "build", "--no-daemon", "--console=plain")
}

val docsCheck by tasks.registering {
    group = "verification"
    description = "文件品質門禁：Javadoc/doclint、publication、consumer docs、API surface/signature/docs coverage"
    dependsOn("javadoc", "test", "verifyPublication", consumerFixtureCheck)
}