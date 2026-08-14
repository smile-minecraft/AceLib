package com.smile.acelib.config;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 語言檔管理器（多 locale 支援）。
 *
 * <p>從 {@code <dataFolder>/lang/<locale>.yml} 讀取多語字串，
 * 支援 {@code {var}} 變數替換與 fallback（請求 locale 缺失時退回 default）。</p>
 *
 * <h2>錯誤代碼</h2>
 * <ul>
 *   <li>{@code ACELIB-LANG-001}：訊息 key 缺失（記錄 warning，不中斷）</li>
 *   <li>{@code ACELIB-LANG-002}：語言檔格式錯誤</li>
 * </ul>
 *
 * <h2>設計原則</h2>
 * <ul>
 *   <li>缺失 {@code key} 不拋例外，而是回傳 {@link Optional#empty()}，
 *       讓呼叫端可以選擇 fallback 或忽略</li>
 *   <li>支援變數替換（{@code {player}} → {@code "smile"}）；
 *       變數缺失時保留原 {@code {var}} 字串，不中斷運行</li>
 *   <li>首次啟動無對應 locale 檔案時自動生成空檔，方便管理員填入翻譯</li>
 * </ul>
 *
 * @since 1.0.0
 */
public final class LangManager {

    /** 語言檔目錄名稱（位於 plugin dataFolder 內）。 */
    public static final String LANG_DIR = "lang";

    private final JavaPlugin plugin;
    private final Locale defaultLocale;
    private volatile YamlConfiguration current;
    private volatile Locale currentLocale;
    private volatile boolean ready = false;

    /**
     * 主要建構子。
     *
     * @param plugin        擁有此 manager 的 plugin；不可為 null
     * @param defaultLocale 預設 locale（fallback 目標）；不可為 null
     */
    public LangManager(JavaPlugin plugin, Locale defaultLocale) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.defaultLocale = Objects.requireNonNull(defaultLocale, "defaultLocale");
        this.currentLocale = defaultLocale;
    }

    // -----------------------------------------------------------------
    // 狀態查詢
    // -----------------------------------------------------------------

    /**
     * 是否已通過 {@link #load()} 成功載入。
     */
    public boolean isReady() {
        return ready;
    }

    /**
     * 取得建構時設定的預設 locale。
     */
    public Locale getDefaultLocale() {
        return defaultLocale;
    }

    /**
     * 取得當前生效的 locale（load / reload 後可能改變）。
     */
    public Locale getCurrentLocale() {
        return currentLocale;
    }

    // -----------------------------------------------------------------
    // 載入流程
    // -----------------------------------------------------------------

    /**
     * 載入當前 locale 的語言檔。
     *
     * <p>若當前 locale 檔案不存在，fallback 到 {@link #defaultLocale}。</p>
     */
    public void load() {
        load(this.defaultLocale);
    }

    /**
     * 載入指定 locale 的語言檔。
     *
     * <p>若請求 locale 檔案不存在，fallback 到 {@link #defaultLocale} 並更新
     * {@link #getCurrentLocale()}。</p>
     *
     * @param locale 欲載入的 locale；不可為 null
     */
    public void load(Locale locale) {
        Objects.requireNonNull(locale, "locale");
        Locale target = locale;
        File file = resolveFile(target);

        if (!file.exists()) {
            // Fallback 到 default
            logFallbackWarning(locale);
            target = this.defaultLocale;
            file = resolveFile(target);
        }

        // 確保目錄存在
        ensureLangDirectory(file);

        if (!file.exists()) {
            // default locale 也沒有 → 建立空檔
            writeEmptyLanguageFile(file, target);
        }

        this.current = loadFromDisk(file);
        this.currentLocale = target;
        this.ready = true;
    }

    /**
     * 重新載入當前 locale 的語言檔。
     */
    public void reload() {
        reload(this.currentLocale);
    }

    /**
     * 重新載入指定 locale 的語言檔。
     *
     * @param locale 欲重新載入的 locale；不可為 null
     */
    public void reload(Locale locale) {
        load(locale);
    }

    // -----------------------------------------------------------------
    // 訊息讀取
    // -----------------------------------------------------------------

    /**
     * 取得訊息（無變數替換）。
     *
     * @param key 訊息 key（YAML 路徑）；不可為 null
     * @return 訊息內容；若 key 缺失則回傳 {@link Optional#empty()}
     */
    public Optional<String> get(String key) {
        return get(key, null);
    }

    /**
     * 取得訊息並套用變數替換。
     *
     * <p>替換規則：將訊息內所有 {@code {var}} 替換為 {@code vars.get("var")}；
     * 若 {@code vars} 為 null 或不包含某個 var，保留原 {@code {var}} 字串。</p>
     *
     * @param key  訊息 key；不可為 null
     * @param vars 變數對應表；可為 null（視為空 map）
     * @return 替換後的訊息；若 key 缺失則回傳 {@link Optional#empty()}
     */
    public Optional<String> get(String key, Map<String, Object> vars) {
        Objects.requireNonNull(key, "key");
        if (current == null) {
            logMissingKey(key);
            return Optional.empty();
        }
        Object raw = current.get(key);
        if (raw == null) {
            logMissingKey(key);
            return Optional.empty();
        }
        String template = raw.toString();
        if (vars == null || vars.isEmpty()) {
            return Optional.of(template);
        }
        return Optional.of(substitute(template, vars));
    }

    // -----------------------------------------------------------------
    // 內部輔助
    // -----------------------------------------------------------------

    /**
     * 解析 locale 對應的語言檔路徑。
     */
    private File resolveFile(Locale locale) {
        return new File(new File(plugin.getDataFolder(), LANG_DIR), localeToFileName(locale));
    }

    /**
     * 將 {@link Locale} 轉為檔名，例如 {@code zh_TW} 或 {@code en_US}。
     *
     * <p>使用 {@code Locale.toString()} 規則（{@code language + "_" + country}），
     * 與 Java 標準 ResourceBundle 慣例一致。</p>
     */
    static String localeToFileName(Locale locale) {
        String lang = locale.getLanguage();
        String country = locale.getCountry();
        if (country == null || country.isEmpty()) {
            return lang + ".yml";
        }
        return lang + "_" + country + ".yml";
    }

    /**
     * 確保 lang/ 目錄存在。
     */
    private static void ensureLangDirectory(File file) {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new ConfigException(
                "ACELIB-LANG-002",
                "無法建立語言檔目錄：" + parent.getAbsolutePath()
            );
        }
    }

    /**
     * 從磁碟載入語言檔。
     *
     * @throws ConfigException 當格式錯誤（ACELIB-LANG-002）
     */
    private static YamlConfiguration loadFromDisk(File file) {
        YamlConfiguration cfg = new YamlConfiguration();
        try {
            cfg.load(file);
            return cfg;
        } catch (InvalidConfigurationException | IOException ex) {
            throw new ConfigException(
                "ACELIB-LANG-002",
                "語言檔格式錯誤：" + file.getAbsolutePath() + "（" + ex.getMessage() + "）",
                ex
            );
        }
    }

    /**
     * 寫入空的語言檔（含版本註解）。
     */
    private static void writeEmptyLanguageFile(File file, Locale locale) {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("language.name", locale.getDisplayName(locale));
        cfg.set("language.code", localeToFileName(locale).replace(".yml", ""));
        try {
            cfg.save(file);
        } catch (IOException ex) {
            throw new ConfigException(
                "ACELIB-LANG-002",
                "無法寫入語言檔：" + file.getAbsolutePath() + "（" + ex.getMessage() + "）",
                ex
            );
        }
    }

    /**
     * 套用 {@code {var}} 替換。
     */
    private static String substitute(String template, Map<String, Object> vars) {
        StringBuilder sb = new StringBuilder(template.length() + 32);
        int i = 0;
        int len = template.length();
        while (i < len) {
            char c = template.charAt(i);
            if (c == '{') {
                int end = template.indexOf('}', i + 1);
                if (end > 0) {
                    String key = template.substring(i + 1, end);
                    if (vars.containsKey(key)) {
                        sb.append(vars.get(key));
                        i = end + 1;
                        continue;
                    }
                    // 變數缺失 → 保留原 {var} 字串
                    sb.append(template, i, end + 1);
                    i = end + 1;
                    continue;
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    /**
     * 記錄訊息 key 缺失的警告（ACELIB-LANG-001）。
     */
    private void logMissingKey(String key) {
        safeLogger().log(Level.WARNING,
            "[ACELIB-LANG-001] message key missing: {0} (locale={1})",
            new Object[]{key, currentLocale});
    }

    /**
     * 記錄 locale fallback 警告。
     */
    private void logFallbackWarning(Locale requested) {
        safeLogger().log(Level.WARNING,
            "[ACELIB-LANG-002] language file for locale {0} not found, falling back to {1}",
            new Object[]{requested, defaultLocale});
    }

    /**
     * 取得 plugin logger（測試環境下安全退避）。
     */
    private Logger safeLogger() {
        try {
            Logger l = plugin.getLogger();
            return l != null ? l : Logger.getLogger("AceLib");
        } catch (Throwable t) {
            return Logger.getLogger("AceLib");
        }
    }
}