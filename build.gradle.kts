// AceLib — Folia-first base library plugin for Smile Minecraft plugins.
//
// 此 Gradle 腳本使用 Kotlin DSL；依賴與 toolchain 設定集中於此。
// Library plugin 不需 fat jar，因此不引入 shadow。

plugins {
    java
    `maven-publish`
}

group = "com.smile"
version = "1.2.0"

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

// ---------------------------------------------------------------------------
// Adventure 5.2 isolated runtime 驗證（Adventure 相容邊界 task）
// ---------------------------------------------------------------------------
// 獨立 configuration，不進入 compile / test classpath，避免與 paper-api 攜帶的
// Adventure 4.26.1 衝突（同一座標兩版本會造成 classpath 歧義，導致 v4 測試
// 行為漂移）。僅解析 adventure-api jar 本身（isTransitive=false）；其
// key / nbt / examination 傳遞依賴由 testRuntimeClasspath 的 v4 版本提供
// （API 相容，見 Adventure5ClickCompatTest 前提驗證）。此 jar 僅供 isolated
// URLClassLoader 在測試中載入 v5 runtime，驗證同一份 production JAR 的
// click descriptor helper 在 Adventure 5.2.0 下不發生 linkage error。
val adventure5ApiConfig by configurations.creating {
    isTransitive = false
}

dependencies {
    // Adventure 5.2.0 isolated runtime 驗證用（見上方 configuration 說明）。
    adventure5ApiConfig("net.kyori:adventure-api:5.2.0")

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
    // 將 Adventure 5.2.0 isolated runtime jar 路徑傳給測試，供 isolated
    // URLClassLoader 載入 v5（不進入 test classpath，避免與 v4 衝突）。
    systemProperty(
        "acelib.adventure5ApiJar",
        adventure5ApiConfig.files.first().absolutePath
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

// ---------------------------------------------------------------------------
// 雙版本相容矩陣聚合（Adventure 4/5 isolated tests + API gates + publication + build + 版本矩陣）
// ---------------------------------------------------------------------------
// 把 Adventure 4/5 isolated tests、API gates、publication 與 build 聚合成單一
// 文件化入口 compatibilityCheck；並由 compatibilityMatrix 輸出各 lane 的
// exact resolved versions。PR CI 以此作為 binary / unit / artifact gate；
// server runtime matrix（Paper/Folia 26.1.2/26.2）由 compatibility-nightly.yml
// 承擔，本 task 僅建立該 workflow，未實際執行（見該檔案頂端註解）。
//
// fail-closed：compatibilityMatrix 在關鍵 lane 解析不到具體版本時拋出，
// 確保「零測試 / 錯誤 classpath / 未解析」都會讓 gate 失敗。
// 解析某 configuration 中指定 group/module 的 exact resolved version。
// 純函式，不 capture script 物件；於 configuration 階段呼叫（見 compatibilityMatrix）。
fun resolveVersion(conf: Configuration, group: String, module: String): String {
    return conf.resolvedConfiguration.resolvedArtifacts
        .firstOrNull { art ->
            val cid = art.id.componentIdentifier
            cid is org.gradle.api.artifacts.component.ModuleComponentIdentifier
                && cid.group == group && cid.module == module
        }
        ?.id?.componentIdentifier
        ?.let { (it as org.gradle.api.artifacts.component.ModuleComponentIdentifier).version }
        ?: "n/a"
}

val compatibilityMatrix by tasks.registering {
    group = "verification"
    description = "輸出各相容 lane 的 exact resolved versions（Adventure 4/5、paper-api、MockBukkit、Java toolchain）"
    // configuration cache 禁止在 execution 存取 project；故於 configuration 階段把版本解析成
    // plain String 存入 val，doLast 只讀這些字串。僅在 compatibilityCheck / compatibilityMatrix
    // 被請求時才強制解析 dependency graph，避免每次 build 都解析（影響 ./gradlew build 等）。
    val shouldResolve = project.gradle.startParameter.taskNames.any {
        it == "compatibilityCheck" || it == "compatibilityMatrix"
            || it.endsWith(":compatibilityCheck") || it.endsWith(":compatibilityMatrix")
    }
    val adventure4: String
    val adventure5: String
    val paperApi: String
    val mockbukkit: String
    if (shouldResolve) {
        adventure4 = resolveVersion(configurations.testRuntimeClasspath.get(), "net.kyori", "adventure-api")
        adventure5 = resolveVersion(configurations.getByName("adventure5ApiConfig"), "net.kyori", "adventure-api")
        paperApi = resolveVersion(configurations.compileClasspath.get(), "io.papermc.paper", "paper-api")
        mockbukkit = resolveVersion(configurations.testRuntimeClasspath.get(), "org.mockbukkit.mockbukkit", "mockbukkit-v26.1.2")
    } else {
        adventure4 = "n/a"; adventure5 = "n/a"; paperApi = "n/a"; mockbukkit = "n/a"
    }
    doLast {
        logger.lifecycle("[compatibility-matrix] Adventure 4 (testRuntimeClasspath) : $adventure4")
        logger.lifecycle("[compatibility-matrix] Adventure 5 (adventure5ApiConfig)  : $adventure5")
        logger.lifecycle("[compatibility-matrix] paper-api   (compileClasspath)     : $paperApi")
        logger.lifecycle("[compatibility-matrix] MockBukkit  (testRuntimeClasspath) : $mockbukkit")
        logger.lifecycle("[compatibility-matrix] Java toolchain                  : ${JavaLanguageVersion.of(25)}")

        require(adventure4 != "n/a") {
            "無法解析 Adventure 4 (net.kyori:adventure-api) 版本；testRuntimeClasspath 可能損壞"
        }
        require(adventure5 != "n/a") {
            "無法解析 Adventure 5 (net.kyori:adventure-api) 版本；adventure5ApiConfig 未正確解析（v5 lane 缺失）"
        }
        require(paperApi != "n/a") {
            "無法解析 paper-api (io.papermc.paper:paper-api) 版本；compileClasspath 可能損壞"
        }
        require(mockbukkit != "n/a") {
            "無法解析 MockBukkit (org.mockbukkit.mockbukkit:mockbukkit-v26.1.2) 版本；testRuntimeClasspath 可能損壞"
        }
    }
}

// artifact gate 需要實際 jar（build/libs/AceLib-*.jar）；確保 test 在 jar 之後執行，
// 避免 gate 因 jar 尚未產出而誤判。此為 build wiring，不影響 production code。
tasks.test { dependsOn(tasks.jar) }

val compatibilityCheck by tasks.registering {
    group = "verification"
    description = "雙版本相容矩陣聚合 gate：Adventure 4/5 tests + API gates + publication + build + 版本矩陣"
    dependsOn("test", "verifyPublication", "jar", compatibilityMatrix)
    // 配置期解析 test 結果目錄路徑（與 verifyPublication 同模式，避免 doLast 直接 capture project）
    val testResultsDir = layout.buildDirectory.dir("test-results/test").get().asFile
    doLast {
        // 零測試 fail-closed：compatibilityCheck 依賴 test，但若 test 實際執行 0 個
        // 測試（例如 filter 排除全部、或沒有編譯任何測試），gate 仍會成功，造成
        // 「靜默通過」假象。此處讀取 test 結果 XML，統計實際執行的測試數量，
        // 低於門檻即 fail，確保 compatibility 相關測試確實被執行（test task 含
        // com.smile.acelib.compatibility.* 與 Adventure gate/v5 tests）。
        require(testResultsDir.isDirectory) {
            "找不到 test 結果目錄：$testResultsDir（test task 應已產出 XML）"
        }
        val xmls = testResultsDir.listFiles { f ->
            f.name.startsWith("TEST-") && f.name.endsWith(".xml")
        } ?: emptyArray()
        val testsAttr = Regex("""<testsuite[^>]*\btests="(\d+)"""")
        var totalTests = 0
        for (xml in xmls) {
            val text = xml.readText()
            for (m in testsAttr.findAll(text)) {
                totalTests += m.groupValues[1].toIntOrNull() ?: 0
            }
        }
        require(totalTests > 0) {
            "compatibilityCheck 零測試門禁：test 實際執行 $totalTests 個測試，低於門檻（要求 > 0，" +
                "XML 數=${xmls.size}）；compatibility 相關測試未執行；gate 不允許靜默通過。"
        }
        logger.lifecycle("[compatibility-check] 實際執行測試數量 = $totalTests（零測試門禁通過）")
    }
}