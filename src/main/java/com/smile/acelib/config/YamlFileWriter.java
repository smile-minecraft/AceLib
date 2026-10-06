package com.smile.acelib.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * 設定檔與語言檔的原子寫入工具（套件私有）。
 *
 * <p>流程：</p>
 * <ol>
 *   <li>用 {@link YamlConfiguration#saveToString()} 序列化（不碰磁碟）</li>
 *   <li>寫入與目標檔同目錄的 {@code .tmp} 檔</li>
 *   <li>{@code Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING)}；
 *       底層檔案系統不支援時降級為 {@code REPLACE_EXISTING}</li>
 *   <li>任一步驟失敗 → 刪除 temp 孤兒檔並拋出 {@link ConfigException}；
 *       清理自己失敗時以 {@code suppressed} 留痕，不掩蓋原始寫入錯誤</li>
 * </ol>
 *
 * <h2>為什麼不直接呼叫 {@code config.save(file)}</h2>
 * <p>直接寫入會先截斷目標檔，寫到一半失敗（磁碟滿、權限變更、檔案系統錯誤）就會留下
 * 半份設定檔——管理員回頭只會看到設定損壞，卻找不到原本能用的值。temp + move 讓目標檔
 * 在完整寫好之前維持原內容，失敗時舊檔完好。
 *
 * <p>取捨：原子替換需要<strong>父目錄</strong>可寫（temp 要建在同目錄、move 要改目錄項目），
 * 但不需要目標檔本身可寫。這與資料層 {@code JsonFileDataStore} 的寫入語意一致。
 *
 * <p>序列化字串一律以 UTF-8 落地，不隨平台預設 charset 變動。
 */
final class YamlFileWriter {

    /** temp 檔名前綴；沿用資料層命名，方便兩邊的孤兒檔一起巡檢。 */
    private static final String TEMP_PREFIX = "acelib-";

    /** temp 檔副檔名。 */
    private static final String TEMP_SUFFIX = ".tmp";

    private YamlFileWriter() {
        // 工具類別，不提供實例
    }

    /**
     * 檔案操作接縫（測試注入用，不對外暴露）。
     *
     * <p>正式環境一律走 {@link #defaultOps()}（直接委派 {@code Files}）。
     * 測試需要讓「temp 建出來之後的寫入」失敗時，才經由多載傳入假實作；
     * 不可用「建不出 temp」來冒充「寫 temp 失敗」，兩者的清理路徑不同。</p>
     */
    interface FileOps {

        Path createTempFile(Path dir, String prefix, String suffix) throws IOException;

        void writeString(Path path, String content) throws IOException;

        void move(Path source, Path target) throws IOException;

        boolean deleteIfExists(Path path) throws IOException;

        static FileOps defaultOps() {
            return DefaultFileOps.INSTANCE;
        }
    }

    private static final class DefaultFileOps implements FileOps {

        private static final DefaultFileOps INSTANCE = new DefaultFileOps();

        @Override
        public Path createTempFile(Path dir, String prefix, String suffix) throws IOException {
            return Files.createTempFile(dir, prefix, suffix);
        }

        @Override
        public void writeString(Path path, String content) throws IOException {
            Files.writeString(path, content, StandardCharsets.UTF_8);
        }

        @Override
        public void move(Path source, Path target) throws IOException {
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        @Override
        public boolean deleteIfExists(Path path) throws IOException {
            return Files.deleteIfExists(path);
        }
    }

    /**
     * 把設定內容原子寫入目標檔。
     *
     * @param config    要寫出的內容；不可為 null
     * @param target    目標檔案；不可為 null
     * @param errorCode 失敗時使用的 {@code ACELIB-<AREA>-<CODE>}；
     *                  由呼叫端決定沿用自己領域的既有代碼
     * @param label     失敗訊息中的檔案種類描述（例如「設定檔」「語言檔」）
     * @throws ConfigException 當序列化、建立 temp 或 move 失敗
     */
    static void writeAtomically(YamlConfiguration config, Path target, String errorCode, String label) {
        writeAtomically(config, target, errorCode, label, FileOps.defaultOps());
    }

    /**
     * 把已渲染好的文字內容原子寫入目標檔（保註解合併後的落盤走這條，
     * 不再經 {@code YamlConfiguration} 重新序列化）。
     *
     * @param text      完整檔案文字；不可為 null
     * @param target    目標檔案；不可為 null
     * @param errorCode 失敗時使用的 {@code ACELIB-<AREA>-<CODE>}
     * @param label     失敗訊息中的檔案種類描述
     * @throws ConfigException 當建立 temp、寫入或 move 失敗
     */
    static void writeTextAtomically(String text, Path target, String errorCode, String label) {
        writeTextAtomically(text, target, errorCode, label, FileOps.defaultOps());
    }

    /**
     * 同上，但檔案操作可注入（測試接縫）。
     *
     * @param ops 檔案操作實作；正式環境傳 {@link FileOps#defaultOps()}
     */
    static void writeAtomically(YamlConfiguration config, Path target, String errorCode, String label,
                                FileOps ops) {
        if (target == null) {
            throw new ConfigException(errorCode, "寫入目標路徑不可為 null");
        }
        String text;
        try {
            // saveToString 只寫入記憶體中的 writer，不會碰磁碟，因此不會是部分寫入
            text = config.saveToString();
        } catch (RuntimeException ex) {
            throw writeFailure(target, errorCode, label, "序列化失敗", ex);
        }
        writeTextAtomically(text, target, errorCode, label, ops);
    }

    /**
     * 同上，但寫入已渲染好的文字（測試接縫）。
     *
     * @param ops 檔案操作實作；正式環境傳 {@link FileOps#defaultOps()}
     */
    static void writeTextAtomically(String text, Path target, String errorCode, String label,
                                    FileOps ops) {
        if (text == null) {
            throw new ConfigException(errorCode, "寫入內容不可為 null：" + target);
        }

        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException ex) {
                throw writeFailure(target, errorCode, label, "無法建立父目錄 " + parent, ex);
            }
        }

        // temp 建出來之後的每一步都可能留下孤兒檔：寫入與搬移各自負責清理，
        // 不共用同一個 catch，否則會把「建不出 temp」和「寫壞 temp」混成同一條路。
        final Path tmp;
        try {
            tmp = ops.createTempFile(parent, TEMP_PREFIX, TEMP_SUFFIX);
        } catch (IOException ex) {
            throw writeFailure(target, errorCode, label, "無法寫入 temp 檔", ex);
        }

        try {
            ops.writeString(tmp, text);
        } catch (IOException ex) {
            ConfigException failure = writeFailure(target, errorCode, label, "無法寫入 temp 檔", ex);
            deleteQuietly(tmp, failure, ops);
            throw failure;
        }

        // 替換前先記下目標檔的權限：temp 搬過去後 inode 換新，權限會變成
        // temp 建立時的預設值；成功後盡力還原（最佳努力，失敗不影響寫入成功）。
        java.util.Set<java.nio.file.attribute.PosixFilePermission> previousPermissions =
            readPosixPermissions(target);

        try {
            ops.move(tmp, target);
        } catch (IOException ex) {
            // move 失敗時清掉 temp，否則 data/ 與 lang/ 會累積孤兒檔
            ConfigException failure = writeFailure(target, errorCode, label, "無法把 temp 檔移到目標位置", ex);
            deleteQuietly(tmp, failure, ops);
            throw failure;
        }

        restorePosixPermissions(target, previousPermissions);
    }

    /**
     * 讀取目標檔既有 POSIX 權限；檔案不存在或檔案系統不支援時回傳 null
     *（呼叫端據此跳過還原，不視為錯誤）。
     */
    private static java.util.Set<java.nio.file.attribute.PosixFilePermission> readPosixPermissions(
            Path target) {
        try {
            return Files.getPosixFilePermissions(target);
        } catch (UnsupportedOperationException | IOException | SecurityException ex) {
            return null;
        }
    }

    /**
     * 盡力把目標檔權限還原為替換前的值；還原失敗靜默略過
     *（寫入本身已成功，不因權限還原把成功翻成失敗）。
     */
    private static void restorePosixPermissions(
            Path target,
            java.util.Set<java.nio.file.attribute.PosixFilePermission> previousPermissions) {
        if (previousPermissions == null) {
            return;
        }
        try {
            Files.setPosixFilePermissions(target, previousPermissions);
        } catch (UnsupportedOperationException | IOException | SecurityException ignored) {
            // 最佳努力：還原不了就維持 temp 預設權限，文件據實說明
        }
    }

    /**
     * 盡力清掉 temp 孤兒檔；清不掉時把清理失敗掛到原始錯誤的 {@code suppressed}，
     * 讓維運仍能從例外鏈查到兩邊的原因，而不是靜靜吞掉其中一邊。
     */
    private static void deleteQuietly(Path tmp, ConfigException failure, FileOps ops) {
        try {
            ops.deleteIfExists(tmp);
        } catch (IOException cleanup) {
            failure.addSuppressed(cleanup);
        }
    }

    private static ConfigException writeFailure(Path target, String errorCode, String label,
                                                String reason, Throwable cause) {
        return new ConfigException(errorCode,
            "無法寫入" + label + " " + target + "（" + reason + "：" + cause.getMessage() + "）",
            cause);
    }
}
