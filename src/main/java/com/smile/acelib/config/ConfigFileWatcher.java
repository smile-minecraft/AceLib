package com.smile.acelib.config;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 設定檔監看器（套件私有）。
 *
 * <p>以 JDK {@link WatchService} 監看設定檔所在目錄，收到事件後做兩層過濾
 * 才觸發重載，避免寫回自觸發與編輯器暫存噪音：</p>
 * <ol>
 *   <li>內容雜湊比對：檔案位元組的 SHA-256 與上次一致就略過
 *      （自己的寫回已先 {@link #markClean()}，連事件都不會走到重載）</li>
 *   <li>去抖動：首次觀測到變化先記錄時間，靜默滿 {@code debounceMillis}
 *       才真正重載；連續寫入只觸發一次</li>
 * </ol>
 *
 * <p>執行緒保證：監看執行緒為 daemon（不擋 JVM 退出），
 * {@link #stop()} 後一定結束，不殘留。重載動作本身只呼叫呼叫端給的
 * {@code reloadAction}（即 {@code ConfigManager} 的驗證管線），
 * 不直接碰任何遊戲物件。</p>
 */
final class ConfigFileWatcher {

    private final Path directory;
    private final String fileName;
    private final Supplier<Boolean> reloadAction;
    private final long debounceMillis;
    private final Logger logger;

    private volatile String lastHash;
    private volatile long firstSeenChangeMillis = -1;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread thread;
    private volatile WatchService watchService;

    /**
     * @param configFile     被監看的設定檔；不可為 null
     * @param reloadAction   內容變化且去抖動通過時執行的重載；不可為 null
     * @param debounceMillis 連續事件的靜默窗（毫秒）；測試可傳 0 讓單次輪詢即重載
     * @param logger         診斷記錄；不可為 null
     */
    ConfigFileWatcher(Path configFile, Supplier<Boolean> reloadAction,
                       long debounceMillis, Logger logger) {
        Objects.requireNonNull(configFile, "configFile");
        Path parent = configFile.toAbsolutePath().getParent();
        this.directory = parent == null ? Path.of(".") : parent;
        Path name = configFile.getFileName();
        this.fileName = name == null ? configFile.toString() : name.toString();
        this.reloadAction = Objects.requireNonNull(reloadAction, "reloadAction");
        this.debounceMillis = Math.max(0, debounceMillis);
        this.logger = Objects.requireNonNull(logger, "logger");
        this.lastHash = hashOf(configFile);
    }

    /**
     * 啟動監看執行緒（冪等：已在跑時直接回傳）。
     *
     * @param threadName 執行緒名（測試以此前綴查殘留）
     */
    synchronized void start(String threadName) {
        if (running.get() && thread != null && thread.isAlive()) {
            return;
        }
        stop();
        running.set(true);
        Thread worker = new Thread(this::loop, threadName);
        worker.setDaemon(true);
        this.thread = worker;
        worker.start();
    }

    /**
     * 停止監看並等執行緒收尾（冪等，可重複呼叫）。
     */
    synchronized void stop() {
        running.set(false);
        WatchService service = watchService;
        if (service != null) {
            try {
                service.close();
            } catch (IOException ignored) {
                // 關閉中的錯誤不影響「已停止」的事實
            }
        }
        Thread worker = thread;
        thread = null;
        watchService = null;
        if (worker != null && worker != Thread.currentThread()) {
            try {
                worker.join(5000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 是否正在監看。
     */
    boolean isRunning() {
        Thread worker = thread;
        return running.get() && worker != null && worker.isAlive();
    }

    /**
     * 標記「目前磁碟內容已是最新」——呼叫端自己寫回後呼叫，
     * 讓後續事件比對直接略過，不觸發重載迴圈。
     *
     * @param configFile 設定檔；不可為 null
     */
    void markClean(Path configFile) {
        lastHash = hashOf(configFile);
        firstSeenChangeMillis = -1;
    }

    /**
     * 受控輪詢一次：檢查內容是否變化並按去抖動決定是否重載。
     *
     * <p>監看執行緒收到事件後走同一入口；測試直接呼叫本方法即可決定性驅動，
     * 不依賴真實時間（{@code debounceMillis} 為 0 時單次呼叫即重載）。</p>
     *
     * @param configFile 設定檔；不可為 null
     * @return 有執行重載回傳 true；無變化或還在去抖動窗內回傳 false
     */
    boolean checkNow(Path configFile) {
        return checkNow(configFile, false);
    }

    /**
     * 同上，但 {@code force} 為 true 時略過去抖動、內容一變就重載。
     *
     * <p>受控輪詢（測試／除錯）走強制路徑，決定性且不依賴真實時間；
     * 監看執行緒走非強制路徑，連續寫入只觸發一次。</p>
     */
    boolean checkNow(Path configFile, boolean force) {
        String current = hashOf(configFile);
        if (current.equals(lastHash)) {
            firstSeenChangeMillis = -1;
            return false;
        }
        long now = System.currentTimeMillis();
        if (!force) {
            if (firstSeenChangeMillis < 0) {
                firstSeenChangeMillis = now;
                if (debounceMillis > 0) {
                    return false;
                }
            } else if (now - firstSeenChangeMillis < debounceMillis) {
                return false;
            }
        }
        firstSeenChangeMillis = -1;
        lastHash = current;
        try {
            reloadAction.get();
        } catch (RuntimeException ex) {
            logger.log(Level.WARNING, "設定檔監看重載發生未預期例外：" + ex.getMessage());
        }
        return true;
    }

    private void loop() {
        WatchService service = null;
        try {
            service = FileSystems.getDefault().newWatchService();
            watchService = service;
            try {
                directory.register(service,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            } catch (IOException ex) {
                logger.log(Level.WARNING,
                    "設定檔監看啟動失敗（目錄無法註冊）：" + directory + "（" + ex.getMessage() + "）");
                running.set(false);
                return;
            }
            while (running.get()) {
                WatchKey key;
                try {
                    key = service.poll(250, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (ClosedWatchServiceException ex) {
                    break;
                }
                if (key == null) {
                    continue;
                }
                boolean relevant = false;
                for (WatchEvent<?> event : key.pollEvents()) {
                    Object context = event.context();
                    if (context instanceof Path changed
                        && changed.getFileName().toString().equals(fileName)) {
                        relevant = true;
                        break;
                    }
                }
                key.reset();
                if (relevant) {
                    checkNow(directory.resolve(fileName));
                }
            }
        } catch (IOException ex) {
            logger.log(Level.WARNING, "設定檔監看器異常結束：" + ex.getMessage());
        } finally {
            // 任何退出路徑（中斷、服務被關、註冊失敗）都要關掉自己建的服務：
            // start 後立刻 stop 的競態下，stop 可能關不到還沒建好的服務，
            // 這裡是最後一道防線，不留 FD 洩漏。
            running.set(false);
            if (service != null) {
                try {
                    service.close();
                } catch (IOException ignored) {
                    // 收尾階段的關閉錯誤不影響「已停止」的事實
                }
                if (watchService == service) {
                    watchService = null;
                }
            }
        }
    }

    private static String hashOf(Path configFile) {
        try {
            if (!Files.isRegularFile(configFile)) {
                return "missing";
            }
            return InstallStateStore.sha256(Files.readAllBytes(configFile));
        } catch (IOException | RuntimeException ex) {
            // 讀不到就固定回「不可讀」：讓重載管線去診斷真正原因，
            // 且雜湊穩定（連續輪詢只觸發一次，不洗版）
            return "unreadable";
        }
    }
}
