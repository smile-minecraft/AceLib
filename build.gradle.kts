// AceLib — Folia-first base library plugin for Smile Minecraft plugins.
//
// 此 Gradle 腳本使用 Kotlin DSL；依賴與 toolchain 設定集中於此。
// Library plugin 不需 fat jar，因此不引入 shadow。

plugins {
    java
    `maven-publish`
}

group = "com.smile"
version = "1.1.0"

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
    // GeyserMC / Floodgate 官方 artifact 倉庫（OpenCollab）。
    // 僅直接取得 artifact（jar），不解析任何 descriptor：本倉庫只供下方鎖定
    // unique snapshot 完整版本號的 floodgate/geyser/cumulus/events 依賴使用，
    // isTransitive=false 下不需要 POM/module metadata；略去 descriptor 可避免
    // 同一模組在 compile / test classpath 以不同 variant 解析時，
    // dependency verification 產生重複 entry（Gradle 9.x 已知情境）。
    maven("https://repo.opencollab.dev/main/") {
        metadataSources {
            artifact()
        }
    }
    mavenCentral()
}

dependencies {
    // 編譯期需要 paper-api；運行期由伺服器提供（provided scope）
    // 版本固定為 26.1.2.build.72-stable 以對齊 MockBukkit 4.113.1 的 paper-api 版本，
    // 避免 binary incompatible 問題。如需升級 paper-api 須同步升級 MockBukkit。
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.72-stable")

    // Floodgate API（基岩玩家偵測）。compileOnly：運行期由伺服器上的 floodgate
    // plugin 提供；缺席時 AceLib 以 reflection-only 探測安全降級。
    // 版本鎖定 unique snapshot（2.2.5-SNAPSHOT 於 2026-08-09 解析為 build 20），
    // 不使用浮動 -SNAPSHOT，避免上游重複發布造成建置漂移。
    // isTransitive=false：只取 api jar 本身；其 POM 的 compile transitives 含
    // 浮動 SNAPSHOT（geyser common / events），改為下方顯式鎖定需要的 jar。
    val floodgateApiVersion = "2.2.5-20260809.110940-20"
    compileOnly("org.geysermc.floodgate:api:$floodgateApiVersion") { isTransitive = false }

    // DeviceOs / InputMode / LinkedPlayer 位於 geyser common（floodgate api 的
    // compile transitive）；以 isTransitive=false 鎖單一 jar，避免上游 SNAPSHOT 漂移。
    // 實際驗證版本組合：floodgate api 2.2.5-20260809.110940-20 + geyser common
    // 2.2.1-20240128.225244-3（OpenCollab repo，2026-08-26 解析並記錄）。
    val geyserCommonVersion = "2.2.1-20240128.225244-3"
    compileOnly("org.geysermc.geyser:common:$geyserCommonVersion") { isTransitive = false }

    // Cumulus（表單模型）：內部翻譯層（external 套件 package-private 類別）把 AceLib
    // FormSpec 翻成 Cumulus form 後交給 FloodgateApi.sendForm；Cumulus 型別不出現在
    // 任何公開簽章。運行期由 floodgate plugin 提供，故僅 compileOnly；
    // testImplementation 同版本雙掛（比照 geyser common 模式）。
    compileOnly("org.geysermc.cumulus:cumulus:1.1.2") { isTransitive = false }

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

    // typed provider seam 測試需要真實 Floodgate 型別（mock FloodgateApi /
    // FloodgatePlayer、列舉映射）；與 compileOnly 同一鎖定版本，受 dependency
    // verification checksum 管控。
    testImplementation("org.geysermc.floodgate:api:$floodgateApiVersion") { isTransitive = false }
    testImplementation("org.geysermc.geyser:common:$geyserCommonVersion") { isTransitive = false }
    // JVM 載入 FloodgateApi / FloodgatePlayer 介面時需解析全部方法簽章引用的型別：
    // sendForm 簽章引用 cumulus Form、getEventBus 簽章引用 geyser events EventBus。
    // 兩者皆為 floodgate api POM 宣告的 compile transitives；cumulus 為 release 版本，
    // events 鎖 unique snapshot（1.1-SNAPSHOT 於 2023-08-15 解析為 build 4），
    // 不使用浮動 -SNAPSHOT。
    testImplementation("org.geysermc.cumulus:cumulus:1.1.2") { isTransitive = false }
    testImplementation("org.geysermc.event:events:1.1-20230815.153219-4") { isTransitive = false }

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
    // geyser common 是 fat jar（內嵌舊版 guava / gson）；將其移到 test classpath
    // 尾端，讓 paper-api / MockBukkit 的正式 guava / gson 先被類別載入器解析，
    // 避免 NoSuchMethodError；DeviceOs / InputMode / LinkedPlayer 僅存在於該 jar，
    // 從尾端仍可正常載入。
    val geyserCommonJar = configurations.testRuntimeClasspath.get().filter {
        it.absolutePath.replace('\\', '/').contains("/org.geysermc.geyser/")
    }
    classpath = (classpath - geyserCommonJar) + geyserCommonJar
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