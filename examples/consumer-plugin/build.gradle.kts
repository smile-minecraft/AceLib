// 範例消費者（consumer）plugin fixture：驗證下游開發者依 README / Quick Start
// 就能用正式 AceLibApi.AceLibProvider contract 編譯出乾淨的 plugin。
//
// 注意：本 fixture 是「編譯驗證」用途，不發布、不宣稱外部可用。
// AceLib 的 GitHub repository 已公開。本 fixture 使用「本地 mavenLocal artifact」解析
// （com.smile:acelib:1.4.0），因為它是貢獻者本地開發用途；公開安裝座標為
// JitPack com.github.smile-minecraft:AceLib:v1.4.0（對應 v1.4.0 tag）。
// 說明：本 fixture 是編譯驗證用途，不發布、不宣稱外部可用；JitPack 是否提供編譯用 API
// v1.4.0 tag 於 GitHub Release 建立時同步建立，由 JitPack 建置提供。
//   1. 先在 AceLib 根目錄執行 `./gradlew publishToMavenLocal`
//   2. 再執行 `./gradlew -p examples/consumer-plugin build`
plugins {
    java
}

group = "com.smile.consumer"
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
    // AceLib 1.4.0 以 mavenLocal 解析本地 publish 產物（com.smile:acelib:1.4.0，僅供貢獻者本地開發，
    // 不代表 Maven Central）；公開安裝座標為 JitPack com.github.smile-minecraft:AceLib:v1.4.0（對應 v1.4.0 tag）。
    compileOnly("com.smile:acelib:1.4.0")
    // AceLib 測試輔助（test-fixtures jar）：classifier 座標只提供該單一檔案，
    // main API 仍由上面的 compileOnly 提供；兩者缺一不可。compileOnly 不進測試
    // 路徑，測試以 testImplementation 同時取得 main（編譯＋運行）與 fixtures。
    testImplementation("com.smile:acelib:1.4.0")
    testImplementation("com.smile:acelib:1.4.0:test-fixtures")
    testImplementation(platform("org.junit:junit-bom:5.11.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // 逐玩家 sqlite 後端的 consumer 實測（PlayerStoreV150Example.sqliteStore）：
    // 只取 driver jar 本體（isTransitive=false），與主專案同版本。
    testImplementation("org.xerial:sqlite-jdbc:3.50.3.0") { isTransitive = false }
    // consumer plugin 依賴 Paper/Folia API（runtime 由伺服器提供，compileOnly）。
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.72-stable")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.compileJava {
    options.encoding = "UTF-8"
}

// ---------------------------------------------------------------------------
// 文件驗證（可重現）：檢查 repo 根 README / docs / examples fixture markdown
// 沒有 stale symbol、相對連結與 anchor 有效、且版本資訊與實際 source/config 一致。
// ---------------------------------------------------------------------------
val verifyConsumerDocs by tasks.registering {
    group = "verification"
    description = "檢查 AceLib 根 README / consumer docs / examples 的 stale symbol、相對連結、anchor 與版本一致性"

    doLast {
        val repoRoot = project.projectDir.parentFile.parentFile
        val readmeEn = File(repoRoot, "README.md")
        val readmeZhTw = File(repoRoot, "README.zh-TW.md")
        require(readmeEn.exists()) { "找不到根 README.md：$readmeEn" }
        require(readmeZhTw.exists()) { "找不到根 README.zh-TW.md：$readmeZhTw" }
        val readmes = listOf(readmeEn, readmeZhTw)

        // 掃描範圍：根 README 雙語 + docs/**/*.md + examples/**/*.md（fixture 文件納入，避免 checker 漏掃）。
        val mdFiles = (readmes
            + File(repoRoot, "docs").walkTopDown().filter { it.isFile && it.name.endsWith(".md") }
            + File(repoRoot, "examples").walkTopDown().filter { it.isFile && it.name.endsWith(".md") })
            .toList()
        val anchorRegex = Regex("""^#{1,6}\s+(.*)$""")

        // GitHub 相容 anchor slug：lowercase、保留 unicode letter/digit/'-'/'_'、
        // 移除其他 punctuation、空白轉 '-'。
        fun slugify(heading: String): String {
            val sb = StringBuilder()
            for (ch in heading.lowercase()) {
                when {
                    ch.isLetterOrDigit() || ch == '-' || ch == '_' -> sb.append(ch)
                    ch.isWhitespace() -> sb.append('-')
                    else -> Unit // strip punctuation（如 . ( ) ： 等）
                }
            }
            return sb.toString()
        }

        // 每個 md 檔的 heading slugs（供 anchor 驗證）。
        val headingsByFile = mdFiles.associateWith { file ->
            file.readLines()
                .mapNotNull { line -> anchorRegex.find(line.trim())?.groupValues?.get(1)?.trim() }
                .map { slugify(it) }
                .toSet()
        }

        // 1) stale symbol：不得「教使用者使用」不存在的 com.smile.acelib.AceLib / AceLib.getApi()。
        //    說明性引用（同一行帶「不存在 / 不得 / 不要 / 禁止 / stale」等禁止語意）允許，
        //    文件必須能告訴讀者「不要這樣做」。
        val stalePatterns = listOf(
            "AceLib.getApi()",
            "import com.smile.acelib.AceLib;",
            "com.smile.acelib.AceLib."
        )
        val forbiddenContextMarkers = listOf(
            "不存在", "不得", "不要", "禁止", "stale", "不建議", "不能", "不可",
            "無法", "失敗"
        )
        val staleHits = mutableListOf<String>()
        for (file in mdFiles) {
            // ` ```text ` / ` ```output ` / ` ```none ` block 是編譯器輸出 / 錯誤訊息
            // （negative example 的一部分），跳過 stale symbol 檢查；` ```java ` 等
            // 程式碼示範 block 仍嚴格檢查，確保真正 Quick Start stale API 會 fail。
            var inTextOutputBlock = false
            file.readLines().forEachIndexed { index, line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("```")) {
                    val lang = trimmed.removePrefix("```").trim().lowercase()
                    inTextOutputBlock = lang == "text" || lang == "output" || lang == "none"
                    return@forEachIndexed
                }
                if (inTextOutputBlock) {
                    return@forEachIndexed
                }
                val hit = stalePatterns.firstOrNull { line.contains(it) }
                if (hit != null && !forbiddenContextMarkers.any { line.contains(it) }) {
                    staleHits.add("${file.relativeTo(repoRoot)}:${index + 1}: stale symbol '$hit'")
                }
            }
        }
        require(staleHits.isEmpty()) {
            "發現 stale symbol（AceLib.getApi / com.smile.acelib.AceLib）：\n" + staleHits.joinToString("\n")
        }

        // 2) 相對連結 + anchor：目標檔案必須存在；帶 fragment 時對應 heading 必須存在
        //    （GitHub slug 比對，忽略大小寫；同頁 #anchor 也驗證）。
        val linkRegex = Regex("""\]\(([^)]+)\)""")
        val linkFailures = mutableListOf<String>()
        for (file in mdFiles) {
            val baseDir = file.parentFile
            val ownSlugs = headingsByFile[file].orEmpty()
            file.readLines().forEachIndexed { index, line ->
                for (match in linkRegex.findAll(line)) {
                    val target = match.groupValues[1].trim()
                    if (target.isEmpty()
                        || target.startsWith("http://") || target.startsWith("https://")
                        || target.startsWith("mailto:")
                    ) {
                        continue
                    }
                    val pathPart = target.substringBefore("#").trim()
                    val fragPart = target.substringAfter("#", "").trim()
                    val targetFile = if (pathPart.isEmpty()) file else File(baseDir, pathPart).normalize()
                    if (!targetFile.exists()) {
                        linkFailures.add(
                            "${file.relativeTo(repoRoot)}:${index + 1}: broken link '$target'（目標檔案不存在）"
                        )
                        continue
                    }
                    if (fragPart.isNotEmpty()) {
                        val headings = if (targetFile == file) ownSlugs
                        else headingsByFile[targetFile].orEmpty()
                        val anchorOk = headings.any { fragPart.equals(it, ignoreCase = true) }
                        if (!anchorOk) {
                            linkFailures.add(
                                "${file.relativeTo(repoRoot)}:${index + 1}: broken anchor '$target'（heading 不存在）"
                            )
                        }
                    }
                }
            }
        }
        require(linkFailures.isEmpty()) {
            "發現 broken relative links / anchors：\n" + linkFailures.joinToString("\n")
        }

        // 3) 版本文字：26.1.2 與 26.2 皆為「已驗證基線」——
        //    不得以 broad range（'26.1+'、'26.1.2+' 等）宣稱支援範圍，
        //    避免讀者誤解 26.2 / 26.1.x 全系列可用。
        val broadPaperVersion = Regex("""26\.1(\.\d+)?\s*\+""")
        val versionMisleading = mdFiles.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val hit = broadPaperVersion.find(line)?.value
                if (hit != null) {
                    "${file.relativeTo(repoRoot)}:${index + 1}: 不得以 broad range '$hit' 宣稱支援基線（26.1.2 與 26.2 已驗證）"
                } else {
                    null
                }
            }
        }
        require(versionMisleading.isEmpty()) {
            "發現誤導性的支援版本文字：\n" + versionMisleading.joinToString("\n")
        }

        // 3b) task-history：consumer 導航/文件不得暴露 workflow / task 狀態，
        //     只保留穩定技術資訊（不含具體 Plan/Task/session history）。
        val taskHistoryPatterns = listOf("文件任務", "本 task", "規劃中（", "由資訊架構文件任務")
        val consumerMds = mdFiles.filter {
            it.relativeTo(repoRoot).invariantSeparatorsPath.startsWith("docs/consumer/")
        }
        val taskHistoryHits = consumerMds.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val hit = taskHistoryPatterns.firstOrNull { line.contains(it) }
                if (hit != null) {
                    "${file.relativeTo(repoRoot)}:${index + 1}: task-history '$hit'（導航應為穩定文字）"
                } else {
                    null
                }
            }
        }
        require(taskHistoryHits.isEmpty()) {
            "發現 task-history 文字：\n" + taskHistoryHits.joinToString("\n")
        }

        // 4) 版本一致：以實際 build.gradle.kts 的 version 為準。
        //    `-PacelibDocsVersion=<version>` 可覆寫本次檢查的受測版本，僅供驗證
        //    門禁自身的雙路徑（預覽版／正式版本）嚴格度，不得作為日常放寬手段。
        val buildScript = File(repoRoot, "build.gradle.kts").readText()
        val versionMatch = Regex("""version\s*=\s*"([^"]+)"""").find(buildScript)
        val configuredVersion = versionMatch?.groupValues?.get(1)
            ?: throw IllegalStateException("build.gradle.kts 找不到 version 欄位")
        val overriddenVersion = (findProperty("acelibDocsVersion") as String?)?.trim().orEmpty()
        val expectedVersion = overriddenVersion.ifEmpty { configuredVersion }
        val isPreview = expectedVersion.endsWith("-SNAPSHOT")
        val expectedBase = expectedVersion.substringBefore("-")
        // 最新已發布版本：CHANGELOG 第一個不帶 -SNAPSHOT 的 `## [x.y.z]` section。
        // 預覽版期間文件必須保留該版本的安裝座標，不得改成預覽版座標。
        val changelogFile = File(repoRoot, "CHANGELOG.md")
        require(changelogFile.exists()) { "找不到 CHANGELOG.md：$changelogFile" }
        val changelogText = changelogFile.readText()
        val latestRelease = Regex("""^##\s+\[(\d+\.\d+\.\d+)\]""", RegexOption.MULTILINE)
            .findAll(changelogText).map { it.groupValues[1] }.firstOrNull()
            ?: throw IllegalStateException("CHANGELOG.md 找不到已發布版本 section '## [x.y.z]'")
        val releasedJitpackCoordinate = "com.github.smile-minecraft:AceLib:v$latestRelease"
        val jitpackCoordinate = "com.github.smile-minecraft:AceLib:v$expectedVersion"
        // 否定語境：含這些字樣的行視為「澄清未發布」，不算發布宣稱。
        fun hasNegation(line: String): Boolean {
            return listOf("未", "不", "非", "沒有", "尚未", "not", "n't", "no ")
                .any { line.contains(it, ignoreCase = true) }
        }
        for (readmeFile in readmes) {
            val readmeText = readmeFile.readText()
            val readmeRel = readmeFile.relativeTo(repoRoot).invariantSeparatorsPath
            require(readmeText.contains(expectedVersion)) {
                "$readmeRel 必須提到目前版本 $expectedVersion"
            }
            // 發布狀態：GitHub repository 已公開且目前版本已作為 GitHub Release 建立。
            // 雙語 README 各自檢查：兩檔皆須含 GitHub Release；語言特定標記分開驗證
            //（zh-TW 要求「repository 已公開」，英文要求 "public repository" 或 "publicly available"）。
            require(readmeText.contains("GitHub Release")) {
                "$readmeRel 必須明確描述 GitHub Release 狀態"
            }
            val isZhTw = readmeFile.name == "README.zh-TW.md"
            if (isZhTw) {
                require(readmeText.contains("repository 已公開")) {
                    "$readmeRel 必須包含「repository 已公開」"
                }
            } else {
                val lower = readmeText.lowercase()
                require(lower.contains("public repository") || lower.contains("publicly available")) {
                    "$readmeRel must describe public repository status (\"public repository\" or \"publicly available\")"
                }
            }
            // 不得把 current-state 寫成未發布／Release Candidate（歷史段落如 0.5.0 封存、RC 同步說明不含「未發布」，不誤判）。
            // 正式版本路徑：維持現行全部檢查，一行都不放寬。
            if (!isPreview) {
                val unpublishedCurrentState = Regex("""v?""" + Regex.escape(expectedVersion) + """[^\n]*(未發布|Release Candidate[^\n]*尚未發布)""")
                require(!unpublishedCurrentState.containsMatchIn(readmeText)) {
                    "$readmeRel 不得把 $expectedVersion 現況宣稱為未發布／Release Candidate"
                }
                // 正向 current-state：README 必須描述公開 JitPack 安裝方式（repository + 當前版本座標）。
                // 座標以根專案 version 為準（expectedVersion），避免版本前進時門禁本身寫死舊版號。
                require(readmeText.contains("jitpack.io", ignoreCase = true)) {
                    "$readmeRel 必須包含 JitPack repository（maven(\"https://jitpack.io\")）"
                }
                require(readmeText.contains(jitpackCoordinate)) {
                    "$readmeRel 必須包含公開 JitPack 座標 $jitpackCoordinate"
                }
            } else {
                // 預覽版路徑：安裝說明必須仍然真實——保留已發布版本的 JitPack 座標，
                // 且不得把預覽版寫成公開座標或已發布狀態。
                require(readmeText.contains("jitpack.io", ignoreCase = true)) {
                    "$readmeRel 必須包含 JitPack repository（maven(\"https://jitpack.io\")）"
                }
                require(readmeText.contains(releasedJitpackCoordinate)) {
                    "$readmeRel 在預覽版期間仍須包含已發布版本 $latestRelease 的公開 JitPack 座標 $releasedJitpackCoordinate（安裝說明必須真實）"
                }
                require(!readmeText.contains(jitpackCoordinate)) {
                    "$readmeRel 不得把預覽版 $expectedVersion 寫成公開 JitPack 座標（預覽版尚未發布）"
                }
                val previewPublishedClaim = readmeFile.readLines().any { line ->
                    line.contains(expectedVersion)
                        && (line.contains("已發布") || line.contains("提供可下載")
                            || line.contains("published", ignoreCase = true)
                            || line.contains("released", ignoreCase = true))
                        && !hasNegation(line)
                }
                require(!previewPublishedClaim) {
                    "$readmeRel 不得把預覽版 $expectedVersion 描述成已發布（須以開發中／尚未發布措辭）"
                }
            }
            // 負向 current-state：不得宣稱已發布至 Maven Central。
            // 本機 mavenLocal() 座標僅供貢獻者本地開發，不代表 Maven Central 已發布。
            // 以明確 forbidden marker 判定（同時出現 Maven Central 與「已發布/已成功/published」），
            // 並排除否定語境（「不」「不代表」「不得」「未」「不宣稱」），避免脆弱的單一否定判斷。
            val mavenCentralPublishedClaim = readmeFile.readLines().any { line ->
                val mentionsCentral = line.contains("Maven Central", ignoreCase = true)
                val claimsPublished = line.contains("已發布") || line.contains("已成功")
                    || line.contains("published", ignoreCase = true)
                val negation = listOf("不", "不代表", "不得", "未", "不宣稱").any { line.contains(it) }
                mentionsCentral && claimsPublished && !negation
            }
            require(!mavenCentralPublishedClaim) {
                "$readmeRel 不得宣稱 com.smile:acelib:$expectedVersion 已發布至 Maven Central（本機 mavenLocal 僅供貢獻者）"
            }
        }

        // 4b) CHANGELOG 目前 release section 檢查：避免只檢查 README 而漏掉 CHANGELOG 的 stale RC 描述。
        // 僅擷取目前版本 section（從 `## [<version>]` 到下一個同層 `## ` heading），不掃描歷史版本段落。
        // 正式版本路徑維持現行檢查；預覽版路徑要求預覽 section 誠實標示開發中／尚未發布，
        // 且最新已發布版本的 section 必須保留。
        val changelogLines = changelogText.lines()
        if (!isPreview) {
            val currentSectionStart = changelogLines.indexOfFirst {
                it.matches(Regex("##\\s+\\[" + Regex.escape(expectedVersion) + "].*"))
            }
            require(currentSectionStart >= 0) {
                "CHANGELOG.md 找不到目前版本 section '## [$expectedVersion]'"
            }
            val currentSectionEnd = changelogLines.subList(currentSectionStart + 1, changelogLines.size)
                .indexOfFirst { it.startsWith("## ") }
                .let { if (it < 0) changelogLines.size else currentSectionStart + 1 + it }
            val currentSection = changelogLines.subList(currentSectionStart, currentSectionEnd).joinToString("\n")
            require(currentSection.contains(expectedVersion)) {
                "CHANGELOG.md 目前 $expectedVersion section 必須提到版本 $expectedVersion"
            }
            require(currentSection.contains("GitHub Release")) {
                "CHANGELOG.md 目前 $expectedVersion section 必須描述 GitHub Release 狀態"
            }
            // 拒絕目前 release section 的 current-state RC 表述（歷史 section 不在此範圍，不誤判）。
            // 以明確 marker「本 RC」／「Release Candidate」判定，不用廣泛的 !contains("RC") 破壞歷史版本。
            val changelogCurrentRc = Regex("""本\s*RC|Release Candidate""")
            require(!changelogCurrentRc.containsMatchIn(currentSection)) {
                "CHANGELOG.md 目前 $expectedVersion section 不得把現況宣稱為本 RC／Release Candidate"
            }
        } else {
            val previewSectionStart = changelogLines.indexOfFirst {
                it.matches(Regex("##\\s+\\[" + Regex.escape(expectedVersion) + "].*"))
            }
            require(previewSectionStart >= 0) {
                "CHANGELOG.md 找不到預覽版 section '## [$expectedVersion]'"
            }
            val previewSectionEnd = changelogLines.subList(previewSectionStart + 1, changelogLines.size)
                .indexOfFirst { it.startsWith("## ") }
                .let { if (it < 0) changelogLines.size else previewSectionStart + 1 + it }
            val previewSection = changelogLines.subList(previewSectionStart, previewSectionEnd).joinToString("\n")
            require(previewSection.contains(expectedVersion)) {
                "CHANGELOG.md 預覽版 $expectedVersion section 必須提到版本 $expectedVersion"
            }
            require(previewSection.contains("GitHub Release")) {
                "CHANGELOG.md 預覽版 $expectedVersion section 必須誠實描述 GitHub Release 狀態（尚未建立）"
            }
            require(previewSection.contains("尚未發布") || previewSection.contains("開發中")
                || previewSection.contains("未發布")) {
                "CHANGELOG.md 預覽版 $expectedVersion section 必須明確標示為開發中／尚未發布"
            }
            val previewRc = Regex("""本\s*RC|Release Candidate""")
            require(!previewRc.containsMatchIn(previewSection)) {
                "CHANGELOG.md 預覽版 $expectedVersion section 不得把現況宣稱為本 RC／Release Candidate"
            }
            require(changelogLines.any {
                it.matches(Regex("##\\s+\\[" + Regex.escape(latestRelease) + "].*"))
            }) {
                "CHANGELOG.md 必須保留最新已發布版本 section '## [$latestRelease]'"
            }
        }

        // 4c) 版本受檔頁面一致：quickstart / compatibility / operator / release-artifacts /
        //     contributor README 中，
        //     所有 AceLib 自身版本的座標、artifact 名稱、checkout 指令與 status 輸出範例
        //     必須等於根 build.gradle.kts 的 version。其他版本號（Paper API、Floodgate、Gradle 等）
        //     有各自語境，不在本檢查範圍。只有同時含 <!-- 版本歷史 --> 且以 "Git tag" 開頭的行才豁免，
        //     限歷史事實（如舊 tag 的 commit hash）；其餘含標記但非 Git tag 開頭的行仍正常比對。
        val versionedPages = listOf(
            "docs/consumer/quickstart.md",
            "docs/consumer/compatibility.md",
            "docs/operator/README.md",
            "docs/reference/release-artifacts.md",
            "docs/contributor/README.md",
            "examples/consumer-plugin/README.md",
            "CONTRIBUTING.md"
        ).map { File(repoRoot, it) }
        versionedPages.forEach { page ->
            require(page.exists()) { "版本受檔文件不存在：${page.relativeTo(repoRoot)}" }
        }
        val versionHistoryMarker = "<!-- 版本歷史 -->"
        // 只比對「AceLib 自身版本」語境；泛用 X.Y.Z 比對會誤判 Paper/Floodgate/Gradle 版本。
        val aceLibVersionPatterns = listOf(
            Regex("""com\.github\.smile-minecraft:AceLib:v(\d+\.\d+\.\d+)"""),
            Regex("""AceLib-v(\d+\.\d+\.\d+)(?:-sources|-javadoc)?\.jar"""),
            Regex("""git checkout v(\d+\.\d+\.\d+)"""),
            Regex("""com\.smile:acelib:v?(\d+\.\d+\.\d+)"""),
            Regex("""AceLib-(\d+\.\d+\.\d+)\.jar"""),
            Regex("""AceLib\s+v?(\d+\.\d+\.\d+)\b"""),
            Regex("""`v(\d+\.\d+\.\d+)`"""),
            Regex("""^\s*Version:\s*v?(\d+\.\d+\.\d+)\s*$""")
        )
        val docVersionDrift = mutableListOf<String>()
        // 允許的版本基線：正式版本路徑維持嚴格相等；預覽版路徑允許已發布版本
        //（頁面保留真實的安裝座標）與預覽版基線（正規化後比對，-SNAPSHOT 後綴不造成漂移）。
        val allowedBases = if (isPreview) setOf(expectedBase, latestRelease) else setOf(expectedVersion)
        for (page in versionedPages) {
            page.readLines().forEachIndexed { index, line ->
                if (line.contains(versionHistoryMarker) && line.trimStart().startsWith("Git tag")) {
                    return@forEachIndexed
                }
                for (pattern in aceLibVersionPatterns) {
                    for (match in pattern.findAll(line)) {
                        val found = match.groupValues[1]
                        if (found !in allowedBases) {
                            docVersionDrift.add(
                                "${page.relativeTo(repoRoot)}:${index + 1}: " +
                                    "'${match.value}' 的版本 $found 與目前版本 $expectedVersion 不一致"
                            )
                        }
                    }
                }
            }
        }
        require(docVersionDrift.isEmpty()) {
            "文件頁面存在與目前版本 $expectedVersion 不一致的 AceLib 版本語境：\n" +
                docVersionDrift.joinToString("\n")
        }
        // 預覽版期間：版本受檔頁面不得出現預覽版的公開 JitPack 座標
        //（`v<previewBase>` 尚未發布；本機驗證請用 mavenLocal 座標）。
        // consumer fixture README 以佔位 `v<release>` 描述該座標，不含版本數字，不在此限。
        if (isPreview) {
            val previewJitpackCoord = "com.github.smile-minecraft:AceLib:v$expectedBase"
            val previewCoordPages = versionedPages.filter {
                it.name != "README.md" || it.parentFile.name != "consumer-plugin"
            }
            val previewCoordHits = previewCoordPages.flatMap { page ->
                page.readLines().mapIndexedNotNull { index, line ->
                    if (line.contains(previewJitpackCoord)) {
                        "${page.relativeTo(repoRoot)}:${index + 1}: 不得在預覽版期間使用尚未發布的 JitPack 座標 '$previewJitpackCoord'"
                    } else {
                        null
                    }
                }
            }
            require(previewCoordHits.isEmpty()) {
                "文件頁面含有預覽版的公開 JitPack 座標：\n" + previewCoordHits.joinToString("\n")
            }
        }

        // 5) docs 導航：consumer quickstart 必須存在（IA 預留給本任務的頁面）
        require(File(repoRoot, "docs/consumer/quickstart.md").exists()) {
            "docs/consumer/quickstart.md 不存在"
        }

        // 6) llms.txt AI 檢索入口：repo 內路徑必須存在且不得逃逸出 repoRoot（fail-closed，外部 URL 不誤判）
        val llmsTxt = File(repoRoot, "llms.txt")
        require(llmsTxt.exists()) { "找不到 llms.txt：$llmsTxt（AI 檢索入口必須存在）" }
        val repoUrlPrefix = "https://github.com/smile-minecraft/AceLib/blob/main/"
        val repoRootCanonical = repoRoot.canonicalFile
        val repoRootCanonicalPath = repoRootCanonical.canonicalPath + File.separator
        val llmsLines = llmsTxt.readLines()
        val repoUrlRegex = Regex("""\(("?)(https?://[^)\s"]+)\1\)""")
        val llmsPathFailures = mutableListOf<String>()
        llmsLines.forEachIndexed { index, line ->
            for (match in repoUrlRegex.findAll(line)) {
                val url = match.groupValues[2].trim()
                if (!url.startsWith(repoUrlPrefix)) {
                    continue
                }
                val afterPrefix = url.substringAfter(repoUrlPrefix)
                val pathPart = afterPrefix.substringBefore("#").substringBefore("?").trim()
                if (pathPart.isEmpty()) {
                    continue
                }
                val targetFile = File(repoRoot, pathPart).normalize()
                val targetCanonical = try {
                    targetFile.canonicalFile
                } catch (_: Exception) {
                    null
                }
                val insideRepo = targetCanonical?.let { tc ->
                    tc.canonicalPath == repoRootCanonical.canonicalPath ||
                        tc.canonicalPath.startsWith(repoRootCanonicalPath)
                } ?: false
                if (!insideRepo || targetCanonical?.isFile != true) {
                    val reason = when {
                        !insideRepo -> "路徑逃逸出 repoRoot"
                        targetCanonical?.exists() != true -> "repo 路徑不存在"
                        targetCanonical?.isFile != true -> "路徑不是檔案"
                        else -> "repo 路徑無效"
                    }
                    llmsPathFailures.add(
                        "llms.txt:${index + 1}: $reason '$pathPart'（來自 $url）"
                    )
                }
            }
        }
        require(llmsPathFailures.isEmpty()) {
            "llms.txt 內指向本 repo 的路徑必須存在且不得逃逸：\n" + llmsPathFailures.joinToString("\n")
        }

        logger.lifecycle("verifyConsumerDocs: stale-symbol / relative-link / anchor / version 檢查通過")
    }
}

tasks.build {
    dependsOn(verifyConsumerDocs)
}
