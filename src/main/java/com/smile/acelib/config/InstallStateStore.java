package com.smile.acelib.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 安裝狀態 sidecar 與最後成功副本（套件私有）。
 *
 * <p>首次安裝 vs 使用後缺檔不能只看設定檔是否存在——檔案不存在有兩種意思，
 * 必須有獨立於設定檔的狀態紀錄。本類別維護兩個 sidecar：</p>
 * <ul>
 *   <li>{@code <fileName>.acelib-state}：只在一次完整驗證成功後寫入
 *       {@code installed=true}；有它代表「曾經成功載入過」</li>
 *   <li>{@code <fileName>.last-good}：每次驗證成功後原子寫入的完整內容；
 *       損壞啟動時據此恢復快照，原檔逐位元不動</li>
 * </ul>
 *
 * <p>狀態檔本身損壞（讀不到／格式不合）時視為「從未安裝」——寧可回報
 * 首次安裝重建，也不憑殘缺狀態誤判為使用後缺檔。</p>
 */
final class InstallStateStore {

    /** 狀態檔後綴。 */
    private static final String STATE_SUFFIX = ".acelib-state";

    /** 最後成功副本後綴。 */
    private static final String LAST_GOOD_SUFFIX = ".last-good";

    private InstallStateStore() {
        // 工具類別，不提供實例
    }

    /**
     * 是否曾經成功載入過（狀態檔存在且內容為 {@code installed=true}）。
     *
     * @param configFile 設定檔路徑；不可為 null
     * @return 曾成功載入回傳 true
     */
    static boolean wasEverInstalled(Path configFile) {
        Path stateFile = stateFileFor(configFile);
        if (!Files.isRegularFile(stateFile)) {
            return false;
        }
        try {
            String text = Files.readString(stateFile, StandardCharsets.UTF_8);
            for (String line : text.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.equals("installed=true")) {
                    return true;
                }
            }
            return false;
        } catch (IOException | RuntimeException ex) {
            // 狀態檔讀不到或解碼失敗：視為從未安裝，不憑殘缺狀態做判斷
            return false;
        }
    }

    /**
     * 標記安裝成功（驗證通過後呼叫）。
     *
     * <p>寫入失敗不拋例外（狀態遺失只影響下次分類精度，不影響本次生效的設定）；
     * 呼叫端應已記錄主流程成功，本方法靜默退避。</p>
     *
     * @param configFile 設定檔路徑；不可為 null
     */
    static void markInstalled(Path configFile) {
        Path stateFile = stateFileFor(configFile);
        try {
            Path parent = stateFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(stateFile, "installed=true\n", StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ignored) {
            // 狀態 sidecar 寫不進去不影響設定本身生效；下次啟動退回較保守的分類
        }
    }

    /**
     * 保存最後驗證成功的完整內容（驗證通過、發布快照後呼叫）。
     *
     * @param configFile 設定檔路徑；不可為 null
     * @param content    已驗證的完整檔案內容；不可為 null
     */
    static void saveLastGood(Path configFile, String content) {
        Path lastGood = lastGoodFileFor(configFile);
        try {
            YamlFileWriter.writeTextAtomically(content, lastGood, "ACELIB-CFG-001", "設定檔最後成功副本");
        } catch (RuntimeException ignored) {
            // 副本寫不進去不影響本次生效；下次損壞時退回無副本路徑（CFG-003）
        }
    }

    /**
     * 讀取最後成功副本。
     *
     * @param configFile 設定檔路徑；不可為 null
     * @return 副本內容；不存在或讀不到回傳 null
     */
    static String readLastGood(Path configFile) {
        Path lastGood = lastGoodFileFor(configFile);
        if (!Files.isRegularFile(lastGood)) {
            return null;
        }
        try {
            return Files.readString(lastGood, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    /**
     * 計算位元組內容的 SHA-256（監看器比對「內容真的變了」用，
     * 不以 mtime 判斷，避免編輯器暫存與寫回自觸發）。
     *
     * @param content 內容；null 視為空
     * @return 十六進位雜湊
     */
    static String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content == null ? new byte[0] : content);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 在所有標準 JDK 必備；走到這裡代表執行環境異常
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private static Path stateFileFor(Path configFile) {
        return configFile.resolveSibling(configFile.getFileName() + STATE_SUFFIX);
    }

    private static Path lastGoodFileFor(Path configFile) {
        return configFile.resolveSibling(configFile.getFileName() + LAST_GOOD_SUFFIX);
    }
}
