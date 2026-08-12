package com.smile.acelib.milestone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * v0.1.0 milestone：驗證 {@code scripts/smoke-server.sh} 的 syntax 與錯誤路徑契約。
 *
 * <p>本測試只覆蓋「可離線驗證」的路徑（語法檢查、{@code --help}、無效平台、
 * 缺 {@code SERVER_JAR}），不啟動真實 Minecraft 伺服器；真實 runtime smoke
 * 需另由 CI 或開發者手動執行（README 已說明限制）。</p>
 *
 * <p>測試透過 {@link ProcessBuilder} 直接 spawn {@code bash}；當腳本不存在
 * 或語法錯誤時，本測試會先 fail，作為 Red 階段證據。</p>
 */
@DisplayName("Milestone v0.1.0 smoke script harness")
class SmokeScriptTest {

    private static final Path SCRIPT_PATH =
        Path.of("scripts", "smoke-server.sh").toAbsolutePath();

    /**
     * 跑 {@code bash <args>} 並回傳 exit code + stdout + stderr；timeout 強制
     * 30 秒，避免腳本卡住時整個 test suite 跟著卡住。
     */
    private ScriptResult run(String... args) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("bash")
            .redirectErrorStream(false)
            .directory(new File("."));
        for (String a : args) {
            pb.command().add(a);
        }
        Process p = pb.start();
        boolean finished = p.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new IOException("bash 子行程超時（30s）: " + String.join(" ", args));
        }
        String out = drain(p.getInputStream());
        String err = drain(p.getErrorStream());
        return new ScriptResult(p.exitValue(), out, err);
    }

    private static String drain(InputStream in) throws IOException {
        byte[] bytes = in.readAllBytes();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private record ScriptResult(int exitCode, String stdout, String stderr) { }

    @Test
    @DisplayName("scripts/smoke-server.sh 必須存在")
    void scriptFileExists() {
        assertTrue(Files.isRegularFile(SCRIPT_PATH),
            "缺少 scripts/smoke-server.sh：v0.1.0 milestone 需要 smoke harness。"
                + "路徑: " + SCRIPT_PATH);
    }

    @Test
    @DisplayName("bash -n 必須通過（腳本語法正確）")
    void bashSyntaxCheckPasses() throws IOException, InterruptedException {
        // 先確保檔案存在，否則此測試的 fail 訊息不明確
        assertTrue(Files.isRegularFile(SCRIPT_PATH),
            "scripts/smoke-server.sh 不存在，無法跑 bash -n");
        ScriptResult r = run("-n", SCRIPT_PATH.toString());
        assertEquals(0, r.exitCode,
            "bash -n 應 exit 0；實際 " + r.exitCode + "\nstdout: " + r.stdout
                + "\nstderr: " + r.stderr);
    }

    @Test
    @DisplayName("--help 必須印 usage 並 exit 0")
    void helpFlagPrintsUsageAndExitsZero() throws IOException, InterruptedException {
        assertTrue(Files.isRegularFile(SCRIPT_PATH),
            "scripts/smoke-server.sh 不存在，無法跑 --help");
        ScriptResult r = run(SCRIPT_PATH.toString(), "--help");
        assertEquals(0, r.exitCode,
            "--help 應 exit 0；實際 " + r.exitCode + "\nstdout: " + r.stdout
                + "\nstderr: " + r.stderr);
        String combined = (r.stdout + "\n" + r.stderr).toLowerCase();
        assertTrue(combined.contains("usage") || combined.contains("usage:"),
            "--help 輸出須含 usage；實際: " + r.stdout + r.stderr);
    }

    @Test
    @DisplayName("無效平台參數必須被拒絕（exit 非零 + 明確錯誤訊息）")
    void invalidPlatformIsRejected() throws IOException, InterruptedException {
        assertTrue(Files.isRegularFile(SCRIPT_PATH),
            "scripts/smoke-server.sh 不存在，無法測無效平台");
        ScriptResult r = run(SCRIPT_PATH.toString(), "spigot");
        assertTrue(r.exitCode != 0,
            "無效平台 'spigot' 應被拒絕；實際 exit " + r.exitCode
                + "\nstdout: " + r.stdout + "\nstderr: " + r.stderr);
        String combined = r.stdout + r.stderr;
        // 必須出現「paper|folia」或「無效」之類的明確提示
        assertTrue(
            combined.contains("paper") && combined.contains("folia")
                || combined.toLowerCase().contains("invalid")
                || combined.contains("無效"),
            "無效平台錯誤訊息須提示合法值；實際: " + combined);
    }

    @Test
    @DisplayName("未提供 SERVER_JAR 且未 --download 必須被拒絕")
    void missingServerJarIsRejected() throws IOException, InterruptedException {
        assertTrue(Files.isRegularFile(SCRIPT_PATH),
            "scripts/smoke-server.sh 不存在，無法測缺 jar 場景");
        // 確保環境變數清乾淨，避免 CI 環境汙染
        ScriptResult r = runWithClearedServerJar(SCRIPT_PATH.toString(), "paper");
        assertTrue(r.exitCode != 0,
            "缺 SERVER_JAR 且未 --download 應被拒絕；實際 exit " + r.exitCode
                + "\nstdout: " + r.stdout + "\nstderr: " + r.stderr);
        String combined = r.stdout + r.stderr;
        assertTrue(
            combined.contains("SERVER_JAR")
                || combined.toLowerCase().contains("server jar")
                || combined.contains("jar"),
            "錯誤訊息須提及 SERVER_JAR；實際: " + combined);
    }

    /**
     * 確保 {@code SERVER_JAR} 環境變數在子行程內為空（即便外部 CI 已設置，
     * 也要明確拒絕該路徑覆寫帶來的偽綠）。
     */
    private ScriptResult runWithClearedServerJar(String... args)
        throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("bash")
            .redirectErrorStream(false)
            .directory(new File("."));
        for (String a : args) {
            pb.command().add(a);
        }
        pb.environment().put("SERVER_JAR", "");
        Process p = pb.start();
        boolean finished = p.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new IOException("bash 子行程超時（30s）");
        }
        return new ScriptResult(p.exitValue(),
            drain(p.getInputStream()), drain(p.getErrorStream()));
    }

    @Test
    @DisplayName("腳本頂端須含 Bash strict mode 設定（set -euo pipefail）")
    void scriptDeclaresBashStrictMode() throws IOException {
        assertTrue(Files.isRegularFile(SCRIPT_PATH),
            "scripts/smoke-server.sh 不存在，無法檢查 strict mode");
        String body = Files.readString(SCRIPT_PATH, StandardCharsets.UTF_8);
        assertTrue(body.contains("set -euo pipefail"),
            "smoke-server.sh 頂端必須宣告 set -euo pipefail；實際內容:\n" + body);
        assertNotNull(body, "腳本內容不可為空");
    }

    /**
     * 抓 bug：先前腳本 watcher subshell 用 {@code return}，在
     * {@code set -euo pipefail} 下會拋 {@code return: can only 'return' from
     * a function or sourced script}，使 watcher 異常終止。修正契約：
     * subshell control flow 一律用 {@code exit}，並在 subshell 結束前明確
     * 設定 exit code。
     */
    @Test
    @DisplayName("watcher subshell 須使用 exit 而非 return（strict mode 相容）")
    void watcherSubshellUsesExitNotReturn() throws IOException {
        assertTrue(Files.isRegularFile(SCRIPT_PATH),
            "scripts/smoke-server.sh 不存在");
        String body = Files.readString(SCRIPT_PATH, StandardCharsets.UTF_8);
        // 任何在 subshell (...) 區塊內的 `return <code>` 都違反 strict mode 契約。
        // 排除 docstring / 註解行：以「return」後接數字字面值判斷，且其前
        // 200 字元內有 `( ` subshell 開頭。
        assertFalse(body.matches("(?s).*\\(\\s*[^)]*\\n[^)]*return\\s+\\d+[^)]*\\).*"),
            "smoke-server.sh 的 subshell 內不得用 `return <num>` 控制離開；"
                + "必須改用 `exit <num>` 以符合 set -euo pipefail 契約。");
        // 同時確認腳本仍以 exit 為唯一離開 subshell 的手段（粗略正/反向契約）
        assertTrue(body.contains("exit "),
            "smoke-server.sh 必須使用 `exit` 結束 subshell；實際內容不含 exit");
    }

    /**
     * 抓 bug：Paper/Folia 26.1 CLI 不接受 {@code --plugins-dir} 與
     * {@code --config-dir} 兩個 flag（會直接拒絕啟動）。修正契約：腳本
     * 對 server 進程的 CLI 只允許 Paper/Folia 接受的參數（如 {@code --nojline}），
     * 不允許 {@code --plugins-dir} / {@code --config-dir}。
     */
    @Test
    @DisplayName("server 進程 CLI 不得使用 --plugins-dir 或 --config-dir（Paper/Folia 不接受）")
    void serverCliDoesNotUseUnsupportedFlags() throws IOException {
        assertTrue(Files.isRegularFile(SCRIPT_PATH),
            "scripts/smoke-server.sh 不存在");
        String body = Files.readString(SCRIPT_PATH, StandardCharsets.UTF_8);
        assertFalse(body.contains("--plugins-dir"),
            "smoke-server.sh 不得把 --plugins-dir 傳給 server；"
                + "Paper/Folia 26.1 僅接受 --plugins / -P。");
        assertFalse(body.contains("--config-dir"),
            "smoke-server.sh 不得把 --config-dir 傳給 server；"
                + "Paper/Folia CLI 無此 flag（應改用 cd 切 CWD）。");
    }

    /**
     * 抓 bug：先前 {@code api.papermc.io/v2/...} 下載 URL 已回 410 Gone，
     * 導致 --download 永遠失敗。修正契約：腳本下載 URL 必須指向
     * {@code fill-data.papermc.io} 內容分發端點，並以 evidence pack 提供的
     * SHA256 物件鍵定位。
     */
    @Test
    @DisplayName("下載 URL 必須指向 fill-data.papermc.io（api.papermc.io 已停用）")
    void downloadUrlPointsToFillDataEndpoint() throws IOException {
        assertTrue(Files.isRegularFile(SCRIPT_PATH),
            "scripts/smoke-server.sh 不存在");
        String body = Files.readString(SCRIPT_PATH, StandardCharsets.UTF_8);
        assertFalse(body.contains("api.papermc.io"),
            "smoke-server.sh 不得使用 api.papermc.io/v2/...；該端點已回 410。");
        assertTrue(body.contains("fill-data.papermc.io"),
            "smoke-server.sh 必須以 fill-data.papermc.io 內容分發端點下載"
                + "Paper/Folia 26.1.2 artifact。");
        // SHA256 物件鍵必須在 URL 中出現（hex 64 chars）
        assertTrue(body.contains("0555a0b0468a5198d8fb1a16e1f9e95c81a917a2dc8f2e09867b4044742f6401"),
            "Paper SHA256 物件鍵必須出現在 download URL。");
        assertTrue(body.contains("607afd1c3320008e1ffd2eaee6780ace4419d5f8c527b75e79f259be79ebf57b"),
            "Folia SHA256 物件鍵必須出現在 download URL。");
    }

    /**
     * 抓 bug：先前 glob {@code build/libs/AceLib-*.jar} 排序時
     * {@code -sources.jar} 字典序小於 {@code .jar}，會誤載入 sources jar
     * 導致 Paper/Folia 拒絕啟動 plugin。修正契約：腳本必須排除
     * {@code -sources.jar} 與 {@code -javadoc.jar}。
     */
    @Test
    @DisplayName("AceLib jar glob 須排除 -sources / -javadoc jar")
    void acelibJarGlobExcludesAuxiliaryJars() throws IOException {
        assertTrue(Files.isRegularFile(SCRIPT_PATH),
            "scripts/smoke-server.sh 不存在");
        String body = Files.readString(SCRIPT_PATH, StandardCharsets.UTF_8);
        assertTrue(body.contains("-sources.jar") || body.contains("sources"),
            "smoke-server.sh 必須顯式排除 -sources.jar 以免誤載入 sources jar。");
        assertTrue(body.contains("-javadoc.jar") || body.contains("javadoc"),
            "smoke-server.sh 必須顯式排除 -javadoc.jar 以免誤載入 javadoc jar。");
    }

    /**
     * 抓 bug：先前 {@code --timeout} parse 但 server 進程從未被它控制；
     * 整個 lifecycle 沒 watchdog。修正契約：腳本必須顯式建立 watchdog
     * （以 background 進程 sleep + kill），確保 timeout 到期能終止 server。
     * macOS 沒有 GNU {@code timeout}；必須以 bash/perl 內建手段實作。
     */
    @Test
    @DisplayName("--timeout 須實作為可中止 server/watcher 的 watchdog")
    void timeoutOptionWiredToWatchdog() throws IOException {
        assertTrue(Files.isRegularFile(SCRIPT_PATH),
            "scripts/smoke-server.sh 不存在");
        String body = Files.readString(SCRIPT_PATH, StandardCharsets.UTF_8);
        // 必須對 server_pid 進行 kill，否則 timeout 形同虛設
        assertTrue(body.contains("kill") && body.contains("server_pid"),
            "smoke-server.sh 必須對 server_pid 呼叫 kill 來實作 timeout watchdog。");
        // 必須以 background subshell 跑 sleep watchdog
        assertTrue(body.contains("sleep") && body.contains("&"),
            "smoke-server.sh 必須以 background sleep 實作 timeout watchdog。");
        // 不能依賴 GNU-only timeout
        assertFalse(body.matches("(?s)\\btimeout\\s+\\d+\\s+java\\b.*"),
            "smoke-server.sh 不得依賴 GNU `timeout`（macOS 無此指令）。");
    }

    /**
     * 抓 bug：先前 disable 驗證寬鬆到「任何 AceLib 字樣」就過；實際
     * disable lifecycle 會印 {@code "AceLib disabled"} log line。修正契約：
     * 必須有獨立 grep 斷言確實出現該行，而非只看 log 內含 AceLib 字樣。
     */
    @Test
    @DisplayName("disable 驗證須獨立 grep 'AceLib disabled' log line")
    void disableLifecycleGrepMatchesActualLifecycleLog() throws IOException {
        assertTrue(Files.isRegularFile(SCRIPT_PATH),
            "scripts/smoke-server.sh 不存在");
        String body = Files.readString(SCRIPT_PATH, StandardCharsets.UTF_8);
        // 必須包含獨立的 disabled log grep，不可只在最後一行寬鬆斷言
        assertTrue(body.matches("(?s).*grep.*[Dd]isabled.*"),
            "smoke-server.sh 必須對 plugin log 內 disabled lifecycle 做獨立 grep 斷言。");
    }

    /**
     * 抓 bug（Momus G4 blocking finding）：先前 {@code --timeout N} 只能殺掉
     * {@code server_pid}；watcher 仍會繼續以 1 秒間隔輪詢 plugin log，
     * 最長輪滿 {@code SERVER_READY_TIMEOUT_SECONDS=120} 秒才會自行結束。
     * 因此「{@code --timeout < 120}」並非真正整個 smoke lifecycle 的 timeout。
     *
     * <p>修正契約：watchdog 必須能在 timeout 到期時「明確令 watcher 結束」
     * （sentinel flag + server liveness 檢查），parent 必須在合理時間內
     * bounded exit；不能只 kill server。</p>
     *
     * <p>本測試用 fake java（永遠 hang，維持 stdin 開著直到 TERM）
     * 取代真實 Paper/Folia server，並把 fake java 路徑 prepend 到 PATH，
     * 讓腳本誤以為找到 java。由於 fake java 不會印 ready log，
     * watcher 必須靠 timeout flag 或 server 死亡信號結束，
     * 不能等到自己的 120 秒輪詢上限。</p>
     */
    @Test
    @DisplayName("--timeout 必須在合理時間內 bounded exit（不能等滿 watcher 120 秒輪詢）")
    void timeoutBoundsEntireLifecycle(@TempDir Path tempDir) throws IOException, InterruptedException {
        assertTrue(Files.isRegularFile(SCRIPT_PATH),
            "scripts/smoke-server.sh 不存在，無法驗證 --timeout 邊界");
        // 確保 build/libs/AceLib-*.jar 存在，否則腳本會在 acelib jar 解析階段 exit 3，
        // 不是 timeout 邊界問題
        assertTrue(Files.isDirectory(Path.of("build", "libs")),
            "build/libs 不存在；請先 ./gradlew jar 才能跑此 timeout regression");

        // fake java：模擬 hung server（不會印 log，TERM 會殺掉）
        Path fakeJava = tempDir.resolve("java");
        Files.writeString(fakeJava,
            "#!/usr/bin/env bash\n" +
                "# Fake java for SmokeScriptTest: 模擬 hung server。\n" +
                "# 不讀 stdin、不印 log；TERM 會殺掉 sleep，整個 process 在 ~<timeout>s 內收屍。\n" +
                "exec sleep 999\n",
            StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(fakeJava,
            EnumSet.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));

        // dummy server jar / dummy acelib jar：滿足腳本內 [[ -f ]] 檢查
        Path dummyServerJar = tempDir.resolve("dummy-server.jar");
        Files.writeString(dummyServerJar, "dummy");
        Path dummyAceLibJar = tempDir.resolve("dummy-acelib.jar");
        Files.writeString(dummyAceLibJar, "dummy");

        ProcessBuilder pb = new ProcessBuilder("bash")
            .redirectErrorStream(false)
            .directory(new File("."));
        pb.command().add(SCRIPT_PATH.toString());
        pb.command().add("paper");
        pb.command().add("--timeout");
        pb.command().add("5");
        pb.command().add("--acelib-jar");
        pb.command().add(dummyAceLibJar.toAbsolutePath().toString());

        // 把 fake java 路徑 prepend 到 PATH，使 require_cmd java 找到 fake
        String origPath = pb.environment().getOrDefault("PATH", "");
        pb.environment().put("PATH",
            tempDir.toAbsolutePath() + File.pathSeparator + origPath);
        // SERVER_JAR 必須存在（腳本 [[ -f server_jar ]] 檢查）
        pb.environment().put("SERVER_JAR",
            dummyServerJar.toAbsolutePath().toString());

        long startNanos = System.nanoTime();
        Process p = pb.start();
        // 故意給超過 120 秒的等待預算，讓舊 bug（watcher 120 秒輪詢）有
        // 機會被偵測到
        boolean finished = p.waitFor(150, TimeUnit.SECONDS);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

        String stdout = drain(p.getInputStream());
        String stderr = drain(p.getErrorStream());

        if (!finished) {
            p.destroyForcibly();
            // 強制 destroy 後再讀，避免 race
            stdout = stdout + drain(p.getInputStream());
            stderr = stderr + drain(p.getErrorStream());
            fail("bash 子行程未在 150s 內結束；--timeout 5 預期 bounded exit。"
                + "實際耗時 " + elapsedMs + "ms（>150s 表示 watcher 仍輪詢 120 秒）。"
                + "\nstdout: " + stdout + "\nstderr: " + stderr);
        }

        // 核心契約：--timeout 5 必須在遠低於 120 秒的時間內 bounded exit
        // 預期修正後耗時 ~6s（watchdog fires @5s, watcher 1 個輪詢週期內 exit）
        assertTrue(elapsedMs < 30_000L,
            "--timeout 5 必須在 < 30s 內 bounded exit；實際耗時 " + elapsedMs
                + "ms（>30s 表示 watcher 沒收到 timeout 信號，仍在輪詢 120 秒）");

        // timeout 觸發 + server 沒變 ready → exit code 非零
        assertTrue(p.exitValue() != 0,
            "--timeout 5 期間 fake java 不會印 ready log，預期 exit 非零；"
                + "實際 exit " + p.exitValue()
                + "\nstdout: " + stdout + "\nstderr: " + stderr);
    }

    /**
     * 抓 bug（Momus G4 nonblocking finding）：先前 {@code --download} 建立的
     * 暫存 server jar 路徑（{@code downloaded_jar=$(mktemp -t ...)}）沒被
     * cleanup 函式追蹤並刪除，導致該檔案留在 /tmp 區直到手動清理。
     *
     * <p>修正契約：腳本必須宣告 {@code downloaded_jar} 變數、在下載路徑指派
     * 它，並在 {@code cleanup()} 內對該變數呼叫 {@code rm -f}。</p>
     *
     * <p>本測試採靜態契約檢查（字串掃描），不啟動真實下載；靜態契約已足以
     * 保證：只要 cleanup 內出現 {@code rm -f "$downloaded_jar"}（或同義寫法），
     * --download 建立的暫存檔一定會在 script 結束（不論成功失敗）後被移除。</p>
     */
    @Test
    @DisplayName("--download 建立的暫存 server jar 須於 cleanup 內被 rm -f 清理")
    void downloadedJarCleanedUpByExitTrap() throws IOException {
        assertTrue(Files.isRegularFile(SCRIPT_PATH),
            "scripts/smoke-server.sh 不存在，無法檢查 --download 清理");
        String body = Files.readString(SCRIPT_PATH, StandardCharsets.UTF_8);

        // 必須宣告並在下載路徑指派 downloaded_jar 變數
        assertTrue(body.contains("downloaded_jar="),
            "smoke-server.sh 必須指派 downloaded_jar 變數（路�）以追蹤 --download 暫存 jar；"
                + "目前找不到 `downloaded_jar=` 賦值");

        // 切出 cleanup() { ... } 函式 body（用 indexOf 比 regex 更穩，避免 { } escape 雷）
        int cleanupHeader = body.indexOf("cleanup()");
        assertTrue(cleanupHeader >= 0,
            "smoke-server.sh 必須有 cleanup() 函式宣告；目前找不到。");
        int cleanupBodyStart = body.indexOf('{', cleanupHeader);
        assertTrue(cleanupBodyStart > cleanupHeader,
            "cleanup() 函式宣告後須有 '{'；找不到 body 開頭。");
        // 從 '{' 開始掃到對應的 '\n}' 作為 function 結尾
        int cleanupBodyEnd = body.indexOf("\n}", cleanupBodyStart);
        assertTrue(cleanupBodyEnd > cleanupBodyStart,
            "cleanup() 函式必須以 '\\n}' 收尾；找不到結尾。");
        String cleanupContent = body.substring(cleanupBodyStart, cleanupBodyEnd + 2);

        // 須有 `rm -f ... downloaded_jar` 出現在 cleanup 區塊內
        assertTrue(cleanupContent.contains("rm -f") && cleanupContent.contains("downloaded_jar"),
            "smoke-server.sh 的 cleanup() 內必須對 $downloaded_jar 呼叫 `rm -f`，"
                + "以確保 --download 暫存 server jar 在 script exit 後被清理。"
                + "目前 cleanup() body 不含 `rm -f ... downloaded_jar`。"
                + "cleanup() body 內容:\n" + cleanupContent);
    }

    /**
     * 抓 bug（Momus G4 blocking finding #2）：先前 {@code --download} 建立的
     * 暫存 server jar（{@code mktemp -t "smoke-server-XXXXXX.jar"}）在以下
     * 路徑都不會被清理：
     * <ul>
     *   <li>下載失敗（curl 或 SHA 驗證失敗）：trap 安裝於 runtime 建立後，
     *       下載失敗在 runtime 之前就 exit，trap 不觸發。</li>
     *   <li>下載成功：{@code downloaded_jar} 在 {@code cleanup()} 定義之後
     *       被重新設為空字串（line 285），cleanup 內的 {@code rm -f} 失效。</li>
     * </ul>
     *
     * <p>修正契約：腳本必須在 {@code mktemp} 之前先初始化
     * {@code downloaded_jar=""}、安裝 cleanup trap，且 cleanup 對未建立的
     * runtime 安全；不可在 mktemp 之後又把 {@code downloaded_jar} 清空。</p>
     *
     * <p>本測試以動態 fake downloader / 校驗器取代靜態字串掃描（既有
     * {@code downloadedJarCleanedUpByExitTrap} 只看 cleanup 內是否有
     * {@code rm -f ... downloaded_jar}，對 {@code downloaded_jar=""} 重設無感）。
     * 測試覆蓋三條路徑：
     * <ol>
     *   <li>下載成功 → 後續 --timeout 觸發 → script 結束。</li>
     *   <li>curl 失敗（HTTP 22）→ script 直接 exit，runtime 尚未建立。</li>
     *   <li>SHA 驗證失敗 → script 直接 exit，runtime 尚未建立。</li>
     * </ol>
     * </p>
     *
     * <p>不依賴網路、不下載 50MB：用 PATH fake {@code curl}、
     * {@code sha256sum} / {@code shasum} 模擬下載成功 / 失敗。</p>
     */
    @Test
    @DisplayName("--download 成功路徑：script 自建 temp jar 在 script 結束後被清理")
    void downloadSuccessCleansUpTempJar(@TempDir Path tempDir) throws Exception {
        Path scriptTmp = tempDir.resolve("script-tmp");
        Files.createDirectories(scriptTmp);
        Path fakeBin = tempDir.resolve("bin");
        Files.createDirectories(fakeBin);
        Path systemTmp = Path.of(System.getProperty("java.io.tmpdir"));

        // Fake curl：寫 dummy 內容到 --output 指定的檔案
        writeExecutable(fakeBin.resolve("curl"),
            "#!/usr/bin/env bash\n" +
            "# Fake curl for SmokeScriptTest：模擬 Paper/Folia artifact 下載。\n" +
            "# 從 $1..$N 找 -o/--output 後的檔名路徑，寫入 dummy 內容。\n" +
            "output=\"\"\n" +
            "while [[ $# -gt 0 ]]; do\n" +
            "  case \"$1\" in\n" +
            "    -o|--output) output=\"$2\"; shift 2 ;;\n" +
            "    *) shift ;;\n" +
            "  esac\n" +
            "done\n" +
            "[[ -n \"$output\" ]] && printf 'dummy server jar content' > \"$output\"\n" +
            "exit 0\n");

        // Fake sha256sum + shasum：永遠回傳 PAPER_SHA256（不管檔案內容）
        String paperSha = "0555a0b0468a5198d8fb1a16e1f9e95c81a917a2dc8f2e09867b4044742f6401";
        writeExecutable(fakeBin.resolve("sha256sum"),
            "#!/usr/bin/env bash\n" +
            "# Fake sha256sum：永遠回傳 PAPER_SHA256。\n" +
            "echo '" + paperSha + "  ' \"$1\"\n" +
            "exit 0\n");
        writeExecutable(fakeBin.resolve("shasum"),
            "#!/usr/bin/env bash\n" +
            "# Fake shasum：永遠回傳 PAPER_SHA256。\n" +
            "echo '" + paperSha + "  ' \"$1\"\n" +
            "exit 0\n");

        // Fake java：模擬 hung server（--timeout 5 會終止）
        writeExecutable(fakeBin.resolve("java"),
            "#!/usr/bin/env bash\n" +
            "# Fake java for SmokeScriptTest：模擬 hung Paper/Folia server。\n" +
            "exec sleep 999\n");

        // Dummy jar（滿足 [[ -f server_jar ]] 檢查；實際內容由 fake curl 寫入）
        Path dummyServerJar = tempDir.resolve("dummy-server.jar");
        Files.writeString(dummyServerJar, "dummy");
        Path dummyAceLibJar = tempDir.resolve("dummy-acelib.jar");
        Files.writeString(dummyAceLibJar, "dummy");

        Set<String> before = snapshotSmokeServerJars(scriptTmp, systemTmp);

        ProcessBuilder pb = baseScriptEnv(tempDir, scriptTmp, fakeBin, dummyAceLibJar);
        pb.command().add("--download");
        pb.command().add("--timeout");
        pb.command().add("5");
        // 不帶 SERVER_JAR：走 --download 路徑

        Process p = pb.start();
        boolean finished = p.waitFor(150, TimeUnit.SECONDS);
        String stdout = drain(p.getInputStream());
        String stderr = drain(p.getErrorStream());

        if (!finished) {
            p.destroyForcibly();
            stdout = stdout + drain(p.getInputStream());
            stderr = stderr + drain(p.getErrorStream());
            fail("download 成功路徑 script 在 150s 內未結束\nstdout: " + stdout
                + "\nstderr: " + stderr);
        }

        Set<String> after = snapshotSmokeServerJars(scriptTmp, systemTmp);
        Set<String> leaked = new HashSet<>(after);
        leaked.removeAll(before);
        assertTrue(leaked.isEmpty(),
            "download 成功路徑結束後，TMPDIR 內不得新增任何 smoke-server-* 殘留；"
                + "實際新增: " + leaked
                + "\nscript exit code: " + p.exitValue()
                + "\nscript stdout: " + stdout
                + "\nscript stderr: " + stderr);
    }

    /**
     * 對應任務：curl 失敗路徑。fake curl exit 22 → script 在 runtime 尚未建立
     * 之前就 die 3。如果 cleanup trap 未在 mktemp 之前安裝、或 trap 內的
     * downloaded_jar 被重設為空，{@code mktemp -t "smoke-server-XXXXXX.jar"}
     * 建立的空檔會留在系統暫存區直到手動清理。
     *
     * <p>本測試是 {@code --download} 路徑下「runtime 尚未建立也安全清理」
     * 契約的關鍵證據：trap 必須在 mktemp 之前安裝，cleanup 必須容忍
     * unset runtime 與空 {@code downloaded_jar}。</p>
     */
    @Test
    @DisplayName("--download + curl 失敗：script 自建 temp jar 在 script 結束後被清理（runtime 尚未建立）")
    void downloadCurlFailureCleansUpTempJar(@TempDir Path tempDir) throws Exception {
        Path scriptTmp = tempDir.resolve("script-tmp");
        Files.createDirectories(scriptTmp);
        Path fakeBin = tempDir.resolve("bin");
        Files.createDirectories(fakeBin);
        Path systemTmp = Path.of(System.getProperty("java.io.tmpdir"));

        // Fake curl：永遠 exit 22（模擬 HTTP 失敗）
        writeExecutable(fakeBin.resolve("curl"),
            "#!/usr/bin/env bash\n" +
            "# Fake curl for SmokeScriptTest：永遠 exit 22（HTTP 失敗）。\n" +
            "exit 22\n");

        // Fake sha256sum / shasum：合理 SHA（curl 失敗路徑不會跑到這裡，但仍備好以防順序變動）
        String paperSha = "0555a0b0468a5198d8fb1a16e1f9e95c81a917a2dc8f2e09867b4044742f6401";
        writeExecutable(fakeBin.resolve("sha256sum"),
            "#!/usr/bin/env bash\n" +
            "echo '" + paperSha + "  ' \"$1\"\n");
        writeExecutable(fakeBin.resolve("shasum"),
            "#!/usr/bin/env bash\n" +
            "echo '" + paperSha + "  ' \"$1\"\n");

        Path dummyAceLibJar = tempDir.resolve("dummy-acelib.jar");
        Files.writeString(dummyAceLibJar, "dummy");

        Set<String> before = snapshotSmokeServerJars(scriptTmp, systemTmp);

        ProcessBuilder pb = baseScriptEnv(tempDir, scriptTmp, fakeBin, dummyAceLibJar);
        pb.command().add("--download");
        // 不帶 SERVER_JAR：走 --download 路徑
        // 不帶 --timeout：避免 fake java / 任何 java 被呼叫；curl 失敗路徑下
        // script 在 runtime 建立之前就 die 3。

        Process p = pb.start();
        boolean finished = p.waitFor(30, TimeUnit.SECONDS);
        String stdout = drain(p.getInputStream());
        String stderr = drain(p.getErrorStream());

        if (!finished) {
            p.destroyForcibly();
            stdout = stdout + drain(p.getInputStream());
            stderr = stderr + drain(p.getErrorStream());
            fail("curl 失敗路徑 script 在 30s 內未結束\nstdout: " + stdout
                + "\nstderr: " + stderr);
        }

        // curl 失敗路徑必定 exit 3
        assertEquals(3, p.exitValue(),
            "curl 失敗路徑應 exit 3（download 失敗 = script 環境錯誤）；"
                + "實際 exit " + p.exitValue()
                + "\nstdout: " + stdout + "\nstderr: " + stderr);

        Set<String> after = snapshotSmokeServerJars(scriptTmp, systemTmp);
        Set<String> leaked = new HashSet<>(after);
        leaked.removeAll(before);
        assertTrue(leaked.isEmpty(),
            "curl 失敗路徑結束後，TMPDIR 內不得新增任何 smoke-server-* 殘留"
                + "（這代表 trap 未在 mktemp 之前安裝，或 trap 內變數被重設）；"
                + "實際新增: " + leaked
                + "\nscript exit code: " + p.exitValue()
                + "\nscript stdout: " + stdout
                + "\nscript stderr: " + stderr);
    }

    /**
     * 對應任務：SHA 驗證失敗路徑。fake curl 寫入 dummy 內容，
     * fake sha256sum 回傳錯誤 digest → script 走
     * {@code verify_sha256} → {@code die 3} → exit；cleanup trap 必須在
     * {@code mktemp} 之前安裝，才能在 runtime 尚未建立時把 {@code downloaded_jar}
     * （mktemp 已建立的空檔或含壞內容的檔）移除。
     */
    @Test
    @DisplayName("--download + SHA 驗證失敗：script 自建 temp jar 在 script 結束後被清理（runtime 尚未建立）")
    void downloadShaFailureCleansUpTempJar(@TempDir Path tempDir) throws Exception {
        Path scriptTmp = tempDir.resolve("script-tmp");
        Files.createDirectories(scriptTmp);
        Path fakeBin = tempDir.resolve("bin");
        Files.createDirectories(fakeBin);
        Path systemTmp = Path.of(System.getProperty("java.io.tmpdir"));

        // Fake curl：寫 dummy 內容到 --output（成功路徑）
        writeExecutable(fakeBin.resolve("curl"),
            "#!/usr/bin/env bash\n" +
            "# Fake curl for SHA-failure test：寫 dummy 內容到 --output。\n" +
            "output=\"\"\n" +
            "while [[ $# -gt 0 ]]; do\n" +
            "  case \"$1\" in\n" +
            "    -o|--output) output=\"$2\"; shift 2 ;;\n" +
            "    *) shift ;;\n" +
            "  esac\n" +
            "done\n" +
            "[[ -n \"$output\" ]] && printf 'wrong content for sha test' > \"$output\"\n" +
            "exit 0\n");

        // Fake sha256sum：回傳錯誤 digest（全 0）
        writeExecutable(fakeBin.resolve("sha256sum"),
            "#!/usr/bin/env bash\n" +
            "# Fake sha256sum for SHA-failure test：永遠回傳全 0 digest。\n" +
            "echo '0000000000000000000000000000000000000000000000000000000000000000  ' \"$1\"\n");
        writeExecutable(fakeBin.resolve("shasum"),
            "#!/usr/bin/env bash\n" +
            "# Fake shasum for SHA-failure test：永遠回傳全 0 digest。\n" +
            "echo '0000000000000000000000000000000000000000000000000000000000000000  ' \"$1\"\n");

        Path dummyAceLibJar = tempDir.resolve("dummy-acelib.jar");
        Files.writeString(dummyAceLibJar, "dummy");

        Set<String> before = snapshotSmokeServerJars(scriptTmp, systemTmp);

        ProcessBuilder pb = baseScriptEnv(tempDir, scriptTmp, fakeBin, dummyAceLibJar);
        pb.command().add("--download");

        Process p = pb.start();
        boolean finished = p.waitFor(30, TimeUnit.SECONDS);
        String stdout = drain(p.getInputStream());
        String stderr = drain(p.getErrorStream());

        if (!finished) {
            p.destroyForcibly();
            stdout = stdout + drain(p.getInputStream());
            stderr = stderr + drain(p.getErrorStream());
            fail("SHA 失敗路徑 script 在 30s 內未結束\nstdout: " + stdout
                + "\nstderr: " + stderr);
        }

        // SHA 失敗走 die 3 → exit 3
        assertEquals(3, p.exitValue(),
            "SHA 驗證失敗路徑應 exit 3（download 校驗失敗 = script 環境錯誤）；"
                + "實際 exit " + p.exitValue()
                + "\nstdout: " + stdout + "\nstderr: " + stderr);

        Set<String> after = snapshotSmokeServerJars(scriptTmp, systemTmp);
        Set<String> leaked = new HashSet<>(after);
        leaked.removeAll(before);
        assertTrue(leaked.isEmpty(),
            "SHA 失敗路徑結束後，TMPDIR 內不得新增任何 smoke-server-* 殘留"
                + "（這代表 trap 未在 mktemp 之前安裝，或 trap 內變數被重設）；"
                + "實際新增: " + leaked
                + "\nscript exit code: " + p.exitValue()
                + "\nscript stdout: " + stdout
                + "\nscript stderr: " + stderr);
    }

    /**
     * 抓 bug：{@code download_artifact()} 內部 {@code tmp=$(mktemp)} 是 function-local
     * 變數，cleanup trap 透過 {@code downloaded_jar} 變數追蹤的是另一條路徑（line 294
     * 的 {@code mktemp -t "smoke-server-XXXXXX.jar"}）。在 SHA mismatch 路徑下，
     * {@code verify_sha256} 內部 {@code die} 不會清掉 tmp；trap 也讀不到這個
     * function-local 變數，導致 {@code tmp.XXXXXXXX} 檔案殘留在 per-user temp
     * 目錄（macOS 為 {@code /var/folders/.../T/}、Linux 為 {@code $TMPDIR}）
     * 直到手動清理。
     *
     * <p>既有 {@code downloadShaFailureCleansUpTempJar} 只 snapshot
     * {@code smoke-server-*} 模式（對應 {@code downloaded_jar} 變數），無法
     * 偵測此 {@code tmp.XXX} 殘留。本測試新增獨立的 {@code tmp.*} 快照 helper，
     * 以 dynamic 方式鎖定 {@code download_artifact} 內部 tmp 清理契約。</p>
     *
     * <p>驗證契約：SHA mismatch 路徑下，script 結束後 script-controlled TMPDIR
     * 與系統 {@code java.io.tmpdir} 內不得新增任何 {@code tmp.XXX} 檔案。</p>
     */
    @Test
    @DisplayName("--download + SHA 驗證失敗：download_artifact 內部 tmp.XXX 也須被清理")
    void downloadShaFailureCleansUpInternalTmpFile(@TempDir Path tempDir) throws Exception {
        Path scriptTmp = tempDir.resolve("script-tmp");
        Files.createDirectories(scriptTmp);
        Path fakeBin = tempDir.resolve("bin");
        Files.createDirectories(fakeBin);
        Path systemTmp = Path.of(System.getProperty("java.io.tmpdir"));

        // Fake curl：寫入 dummy 內容到 --output（curl 成功路徑）
        writeExecutable(fakeBin.resolve("curl"),
            "#!/usr/bin/env bash\n" +
            "# Fake curl for internal-tmp-leak test：寫 dummy 內容到 --output。\n" +
            "output=\"\"\n" +
            "while [[ $# -gt 0 ]]; do\n" +
            "  case \"$1\" in\n" +
            "    -o|--output) output=\"$2\"; shift 2 ;;\n" +
            "    *) shift ;;\n" +
            "  esac\n" +
            "done\n" +
            "[[ -n \"$output\" ]] && printf 'wrong content for internal tmp test' > \"$output\"\n" +
            "exit 0\n");

        // Fake sha256sum / shasum：永遠回傳全 0 digest → mismatch
        writeExecutable(fakeBin.resolve("sha256sum"),
            "#!/usr/bin/env bash\n" +
            "# Fake sha256sum for internal-tmp-leak test：永遠回傳全 0 digest。\n" +
            "echo '0000000000000000000000000000000000000000000000000000000000000000  ' \"$1\"\n");
        writeExecutable(fakeBin.resolve("shasum"),
            "#!/usr/bin/env bash\n" +
            "# Fake shasum for internal-tmp-leak test：永遠回傳全 0 digest。\n" +
            "echo '0000000000000000000000000000000000000000000000000000000000000000  ' \"$1\"\n");

        Path dummyAceLibJar = tempDir.resolve("dummy-acelib.jar");
        Files.writeString(dummyAceLibJar, "dummy");

        // 只 snapshot tmp.XXX 模式（download_artifact 內 mktemp 預設名），
        // 不混入 smoke-server-*（既有測試已覆蓋 downloaded_jar 清理）。
        Set<String> before = snapshotTmpFiles(scriptTmp, systemTmp);

        ProcessBuilder pb = baseScriptEnv(tempDir, scriptTmp, fakeBin, dummyAceLibJar);
        pb.command().add("--download");

        Process p = pb.start();
        boolean finished = p.waitFor(30, TimeUnit.SECONDS);
        String stdout = drain(p.getInputStream());
        String stderr = drain(p.getErrorStream());

        if (!finished) {
            p.destroyForcibly();
            stdout = stdout + drain(p.getInputStream());
            stderr = stderr + drain(p.getErrorStream());
            fail("SHA 失敗路徑 script 在 30s 內未結束\nstdout: " + stdout
                + "\nstderr: " + stderr);
        }

        // SHA 失敗走 die 3 → exit 3（既有契約）
        assertEquals(3, p.exitValue(),
            "SHA 驗證失敗路徑應 exit 3；實際 " + p.exitValue()
                + "\nstdout: " + stdout + "\nstderr: " + stderr);

        Set<String> after = snapshotTmpFiles(scriptTmp, systemTmp);
        Set<String> leaked = new HashSet<>(after);
        leaked.removeAll(before);
        assertTrue(leaked.isEmpty(),
            "SHA 失敗路徑結束後不得新增任何 tmp.XXX 殘留（這代表 download_artifact"
                + " 內部 mktemp tmp 在 SHA mismatch 時未先清除，trap 看不到"
                + " function-local 變數）；實際新增: " + leaked
                + "\nscript exit code: " + p.exitValue()
                + "\nscript stdout: " + stdout + "\nscript stderr: " + stderr);
    }

    /**
     * Snapshot 名稱以 {@code tmp.} 開頭的檔案（macOS BSD {@code mktemp} 與
     * Linux GNU {@code mktemp} 無 template 時的預設檔名）。同時掃描
     * 由案例 @TempDir 提供的隔離 TMPDIR 與系統 {@code java.io.tmpdir}，
     * 覆蓋 GNU mktemp（讀 TMPDIR）與 BSD mktemp（忽略 TMPDIR 走 per-user dir）
     * 兩種行為。與 {@link #snapshotSmokeServerJars} 互補：前者負責
     * downloaded_jar，本方法負責 download_artifact 內部 tmp。
     */
    private Set<String> snapshotTmpFiles(Path... candidateDirs) throws IOException {
        Set<String> snapshot = new HashSet<>();
        for (Path dir : candidateDirs) {
            if (dir == null || !Files.isDirectory(dir)) {
                continue;
            }
            try (var stream = Files.list(dir)) {
                for (Path p : (Iterable<Path>) stream::iterator) {
                    String name = p.getFileName().toString();
                    if (name.startsWith("tmp.")) {
                        snapshot.add(p.toString());
                    }
                }
            }
        }
        return snapshot;
    }

    /**
     * 共用 helper：建立執行腳本的 {@link ProcessBuilder} 環境。包含：
     * <ul>
     *   <li>PATH prepend {@code fakeBin}（讓 fake curl / sha256sum / java 先被找到）</li>
     *   <li>TMPDIR 指向 {@code scriptTmp}（讓 GNU {@code mktemp -t} 在 Linux 上
     *       使用此目錄；macOS BSD {@code mktemp -t} 會忽略 TMPDIR，仍走
     *       {@code java.io.tmpdir}，故測試 snapshot 同時掃兩個目錄）</li>
     *   <li>SERVER_JAR 顯式清空（避免 CI 環境污染）</li>
     *   <li>--acelib-jar 指向 dummy jar</li>
     * </ul>
     * 不在這裡加 {@code --download} / {@code --timeout} / {@code paper|folia}，
     * 由各測試自行指定，以維持共用 helper 簡潔。
     */
    private ProcessBuilder baseScriptEnv(Path tempDir, Path scriptTmp, Path fakeBin,
        Path dummyAceLibJar) throws IOException {
        assertTrue(Files.isRegularFile(SCRIPT_PATH),
            "scripts/smoke-server.sh 不存在，無法跑下載 cleanup regression");
        assertTrue(Files.isDirectory(Path.of("build", "libs")),
            "build/libs 不存在；請先 ./gradlew jar 才能跑下載 cleanup regression");

        ProcessBuilder pb = new ProcessBuilder("bash")
            .redirectErrorStream(false)
            .directory(new File("."));
        pb.command().add(SCRIPT_PATH.toString());
        pb.command().add("paper");
        pb.command().add("--acelib-jar");
        pb.command().add(dummyAceLibJar.toAbsolutePath().toString());

        String origPath = pb.environment().getOrDefault("PATH", "");
        pb.environment().put("PATH",
            fakeBin.toAbsolutePath() + File.pathSeparator + origPath);
        pb.environment().put("TMPDIR",
            scriptTmp.toAbsolutePath().toString());
        // 確保 SERVER_JAR 沒被外部 CI 汙染；本測試要走 --download 路徑
        pb.environment().put("SERVER_JAR", "");

        return pb;
    }

    /**
     * Snapshot 在 {@code candidateDirs} 內任何名稱以 {@code smoke-server-}
     * 開頭的檔案路徑集合。同時掃描 test-controlled TMPDIR 與系統預設
     * {@code java.io.tmpdir}，覆蓋 GNU mktemp（Linux, 走 TMPDIR）與
     * BSD mktemp（macOS, 忽略 TMPDIR 走 per-user dir）兩種行為。
     */
    private Set<String> snapshotSmokeServerJars(Path... candidateDirs) throws IOException {
        Set<String> snapshot = new HashSet<>();
        for (Path dir : candidateDirs) {
            if (dir == null || !Files.isDirectory(dir)) {
                continue;
            }
            try (var stream = Files.list(dir)) {
                for (Path p : (Iterable<Path>) stream::iterator) {
                    String name = p.getFileName().toString();
                    if (name.startsWith("smoke-server-")) {
                        snapshot.add(p.toString());
                    }
                }
            }
        }
        return snapshot;
    }

    /**
     * 把 {@code body} 寫到 {@code file} 並設為 owner rwx。
     */
    private void writeExecutable(Path file, String body) throws IOException {
        Files.writeString(file, body, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file,
            EnumSet.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
    }
}
