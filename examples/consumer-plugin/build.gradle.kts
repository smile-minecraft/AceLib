// 範例消費者（consumer）plugin fixture：驗證下游開發者依 README / Quick Start
// 就能用正式 AceLibApi.AceLibProvider contract 編譯出乾淨的 plugin。
//
// 注意：本 fixture 是「編譯驗證」用途，不發布、不宣稱外部可用。
// AceLib 目前（1.0.0 Release Candidate）尚未發布到外部 repository，
// 因此 fixture 依賴「本地 mavenLocal artifact」：
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
    // AceLib 尚未發布外部 artifact：以 mavenLocal 解析本地 publish 產物。
    compileOnly("com.smile:acelib:1.0.0")
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
            "README.md 必須提到目前版本 $expectedVersion（未發布狀態）"
        }
        require(!readmeText.contains("v1.0.0") || readmeText.contains("未發布")) {
            "README.md 不得宣稱 v1.0.0 已發布"
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
