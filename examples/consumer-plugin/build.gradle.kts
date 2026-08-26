// 範例消費者（consumer）plugin fixture：驗證下游開發者依 README / Quick Start
// 就能用正式 AceLibApi.AceLibProvider contract 編譯出乾淨的 plugin。
//
// 注意：本 fixture 是「編譯驗證」用途，不發布、不宣稱外部可用。
// AceLib 1.0.0 的 GitHub repository 已公開、GitHub Release 已建立，
// JitPack `v1.0.0` artifact endpoint 已驗證可解析（HTTP 200，無 transitive dependencies）。
// 本 fixture 仍使用「本地 mavenLocal artifact」解析（com.smile:acelib:1.1.0），
// 因為它是貢獻者本地開發用途；公開安裝請使用 JitPack 座標 com.github.smile-minecraft:AceLib:v1.0.0。
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
    // AceLib 1.1.0 以 mavenLocal 解析本地 publish 產物（com.smile:acelib:1.1.0，僅供貢獻者本地開發，
    // 不代表 Maven Central）；公開安裝座標為 JitPack com.github.smile-minecraft:AceLib:v1.0.0（已驗證可解析）。
    compileOnly("com.smile:acelib:1.1.0")
    // consumer plugin 依賴 Paper/Folia API（runtime 由伺服器提供，compileOnly）。
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.72-stable")
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
        val readme = File(repoRoot, "README.md")
        require(readme.exists()) { "找不到根 README.md：$readme" }

        // 掃描範圍：根 README + docs/**/*.md + examples/**/*.md（fixture 文件納入，避免 checker 漏掃）。
        val mdFiles = (listOf(readme)
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

        // 3) 版本文字：26.1.2 是「已驗證基線」，26.2 尚未驗證——
        //    不得以 broad range（'26.1+'、'26.1.2+' 等）宣稱支援範圍，
        //    避免讀者誤解 26.2 / 26.1.x 全系列可用。
        val broadPaperVersion = Regex("""26\.1(\.\d+)?\s*\+""")
        val versionMisleading = mdFiles.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val hit = broadPaperVersion.find(line)?.value
                if (hit != null) {
                    "${file.relativeTo(repoRoot)}:${index + 1}: 不得以 broad range '$hit' 宣稱支援基線（26.1.2 已驗證、26.2 尚未驗證）"
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

        // 4) 版本一致：README 必須以實際 build.gradle.kts 的 version 為準（1.0.0）
        val buildScript = File(repoRoot, "build.gradle.kts").readText()
        val versionMatch = Regex("""version\s*=\s*"([^"]+)"""").find(buildScript)
        val expectedVersion = versionMatch?.groupValues?.get(1)
            ?: throw IllegalStateException("build.gradle.kts 找不到 version 欄位")
        val readmeText = readme.readText()
        require(readmeText.contains(expectedVersion)) {
            "README.md 必須提到目前版本 $expectedVersion"
        }
        // 發布狀態：GitHub repository 已公開且 GitHub v1.0.0 Release 已建立。
        // 文件必須明確描述此 public/release 狀態；JitPack v1.0.0 endpoint 已驗證可解析，
        // 本機 mavenLocal() 座標（com.smile:acelib:1.1.0）僅供貢獻者本地開發，不代表 Maven Central。
        require(readmeText.contains("GitHub Release") && readmeText.contains("repository 已公開")) {
            "README.md 必須明確描述 GitHub Release 與 repository 已公開狀態"
        }
        // 不得把 current-state 寫成未發布／Release Candidate（歷史段落如 0.5.0 封存、RC 同步說明不含「未發布」，不誤判）。
        val unpublishedCurrentState = Regex("""v?1\.0\.0[^\n]*(未發布|Release Candidate[^\n]*尚未發布)""")
        require(!unpublishedCurrentState.containsMatchIn(readmeText)) {
            "README.md 不得把 1.0.0 現況宣稱為未發布／Release Candidate"
        }
        // 正向 current-state：README 必須描述公開 JitPack 安裝方式（repository + 當前版本座標）。
        // 座標以根專案 version 為準（expectedVersion），避免版本前進時門禁本身寫死舊版號。
        require(readmeText.contains("jitpack.io", ignoreCase = true)) {
            "README.md 必須包含 JitPack repository（maven(\"https://jitpack.io\")）"
        }
        val jitpackCoordinate = "com.github.smile-minecraft:AceLib:v$expectedVersion"
        require(readmeText.contains(jitpackCoordinate)) {
            "README.md 必須包含公開 JitPack 座標 $jitpackCoordinate"
        }
        // 負向 current-state：不得宣稱 com.smile:acelib:1.1.0 已發布至 Maven Central。
        // 本機 mavenLocal() 座標僅供貢獻者本地開發，不代表 Maven Central 已發布。
        // 以明確 forbidden marker 判定（同時出現 Maven Central 與「已發布/已成功/published」），
        // 並排除否定語境（「不」「不代表」「不得」「未」「不宣稱」），避免脆弱的單一否定判斷。
        val mavenCentralPublishedClaim = readme.readLines().any { line ->
            val mentionsCentral = line.contains("Maven Central", ignoreCase = true)
            val claimsPublished = line.contains("已發布") || line.contains("已成功")
                || line.contains("published", ignoreCase = true)
            val negation = listOf("不", "不代表", "不得", "未", "不宣稱").any { line.contains(it) }
            mentionsCentral && claimsPublished && !negation
        }
        require(!mavenCentralPublishedClaim) {
            "README.md 不得宣稱 com.smile:acelib:1.1.0 已發布至 Maven Central（本機 mavenLocal 僅供貢獻者）"
        }

        // 4b) CHANGELOG 目前 release section 檢查：避免只檢查 README 而漏掉 CHANGELOG 的 stale RC 描述。
        // 僅擷取目前版本 section（從 `## [<version>]` 到下一個同層 `## ` heading），不掃描歷史版本段落。
        val changelog = File(repoRoot, "CHANGELOG.md")
        require(changelog.exists()) { "找不到 CHANGELOG.md：$changelog" }
        val changelogLines = changelog.readLines()
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

        // 5) docs 導航：consumer quickstart 必須存在（IA 預留給本任務的頁面）
        require(File(repoRoot, "docs/consumer/quickstart.md").exists()) {
            "docs/consumer/quickstart.md 不存在"
        }

        logger.lifecycle("verifyConsumerDocs: stale-symbol / relative-link / anchor / version 檢查通過")
    }
}

tasks.build {
    dependsOn(verifyConsumerDocs)
}
