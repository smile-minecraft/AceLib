package com.smile.acelib.config;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 測試用 log 擷取器。
 *
 * <p>把 {@code plugin.getLogger()} 與 {@code safeLogger()} 的退避名稱 {@code "AceLib"}
 * 的記錄收進記憶體，讓「失敗必須可由 logger 查到原因」這條規則可以被直接斷言，
 * 不必靠人工翻 console。</p>
 *
 * <p>{@code build.gradle.kts} 未設定 {@code maxParallelForks}，測試類別在同一 JVM
 * 內循序執行，因此掛在共用 logger 上的 handler 不會互相干擾。</p>
 */
final class LogCapture implements AutoCloseable {

    private final List<Logger> attached = new ArrayList<>();
    private final List<LogRecord> records = new ArrayList<>();

    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
            // 測試不需要 flush
        }

        @Override
        public void close() {
            // 測試不需要 close
        }
    };

    /**
     * 掛上 handler，開始擷取。
     *
     * @param plugin 被擷取的 plugin；不可為 null
     * @return 擷取器；用 try-with-resources 或手動 close 解除掛載
     */
    static LogCapture attachTo(JavaPlugin plugin) {
        LogCapture capture = new LogCapture();
        capture.attach(plugin.getLogger());
        capture.attach(Logger.getLogger("AceLib"));
        return capture;
    }

    private void attach(Logger logger) {
        if (logger == null || attached.contains(logger)) {
            return;
        }
        attached.add(logger);
        logger.addHandler(handler);
    }

    /**
     * 指定等級的記錄中，render 後包含 {@code needle} 的筆數。
     */
    long countAt(Level level, String needle) {
        return records.stream()
            .filter(r -> level.equals(r.getLevel()))
            .map(LogCapture::render)
            .filter(msg -> msg.contains(needle))
            .count();
    }

    /**
     * 是否有任何等級的記錄，render 後包含 {@code needle}。
     */
    boolean hasMessageContaining(String needle) {
        return records.stream()
            .map(LogCapture::render)
            .anyMatch(msg -> msg.contains(needle));
    }

    /**
     * render 後的所有記錄訊息（診斷失敗原因時附在斷言訊息後）。
     */
    List<String> messages() {
        return records.stream().map(LogCapture::render).toList();
    }

    /**
     * 把 {@code java.util.logging} 的 {@code {0}} 佔位符代換為實際參數，
     * 讓斷言可以檢查參數內容而不只是模板。
     */
    private static String render(LogRecord record) {
        String message = record.getMessage();
        if (message == null) {
            return "";
        }
        Object[] parameters = record.getParameters();
        if (parameters == null) {
            return message;
        }
        String rendered = message;
        for (int i = 0; i < parameters.length; i++) {
            rendered = rendered.replace("{" + i + "}", String.valueOf(parameters[i]));
        }
        return rendered;
    }

    @Override
    public void close() {
        for (Logger logger : attached) {
            logger.removeHandler(handler);
        }
        attached.clear();
    }
}