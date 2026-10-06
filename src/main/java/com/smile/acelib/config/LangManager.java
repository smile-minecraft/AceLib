package com.smile.acelib.config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 語言檔管理器（多 locale 支援）。
 *
 * <p>從 {@code <dataFolder>/lang/<locale>.yml} 讀取多語字串，
 * 支援 {@code {var}} 變數替換與 fallback（請求 locale 缺失時退回 default）。
 * 磁碟缺 key 時退回 plugin JAR 內建資源（{@code lang/<locale>.yml}）：
 * 磁碟自訂文案永遠優先，內建資源只補缺口。</p>
 *
 * <h2>錯誤代碼</h2>
 * <ul>
 *   <li>{@code ACELIB-LANG-001}：訊息 key 缺失（記錄 warning，不中斷；同一載入週期只記一次）</li>
 *   <li>{@code ACELIB-LANG-002}：語言檔格式錯誤或無法寫入（含內建資源解析損壞）</li>
 *   <li>{@code ACELIB-LANG-003}：語言檔不存在（記錄 warning 並負向快取）</li>
 * </ul>
 *
 * <h2>設計原則</h2>
 * <ul>
 *   <li>缺失 {@code key} 不拋例外，而是回傳 {@link Optional#empty()}，
 *       讓呼叫端可以選擇 fallback 或忽略</li>
 *   <li>支援變數替換（{@code {player}} → {@code "smile"}）；
 *       變數缺失時保留原 {@code {var}} 字串，不中斷運行</li>
 *   <li>首次啟動無對應 locale 檔案時自動生成空檔，方便管理員填入翻譯</li>
 *   <li>「檔案不存在」與「檔案解析損壞」都可查：前者記 LANG-003，後者記 LANG-002 並附錯誤</li>
 *   <li>兩者都會負向快取，避免熱路徑反覆 stat 或重複解析壞檔；
 *       {@link #reload()} 會清空快取，讓修好的檔案立刻生效</li>
 *   <li>缺鍵 warning 在同一次載入週期內去重；{@code reload} 後重新記錄</li>
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
     * per-locale 檔案快取（與全域 current state 隔離）。
     * 供 {@link #get(Locale, String)} 依特定 locale 讀取，不切換全域 current。
     *
     * <p>{@link Optional#empty()} 同時充當負向快取：檔案不存在或解析損壞時記住這個結果，
     * 不在每次查詢時重複 stat 或重複解析壞檔。</p>
     */
    private final Map<Locale, Optional<YamlConfiguration>> localeCache = new ConcurrentHashMap<>();

    /**
     * per-locale 內建資源快取（plugin JAR 內 {@code lang/<locale>.yml}）。
     *
     * <p>內建資源在執行期不變，快取純為避免每次查詢重複解析；
     * {@link #load(Locale)}／{@link #reload()} 會一併清空，
     * 讓升級後的新 JAR 資源立刻生效。</p>
     */
    private final Map<Locale, Optional<YamlConfiguration>> builtinCache = new ConcurrentHashMap<>();

    /**
     * 本次載入週期內已記錄過的缺鍵（{@code locale + "|" + key}）。
     *
     * <p>訊息查詢常在熱路徑上重複呼叫同一個 key；不去重的話每查一次就噴一行 warning，
     * 真正的問題反而被洗掉。由 {@link #load(Locale)} 在成功載入時清空，
     * 因此管理者換檔、reload 之後仍會再看到一次。</p>
     */
    private final Set<String> reportedMissingKeys = ConcurrentHashMap.newKeySet();

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
     * <p>全部步驟成功後才提交狀態：{@code current}、{@code currentLocale}、per-locale
     * 快取與缺鍵去重記錄都維持原樣直到讀檔完成。因此 {@link #reload()} 失敗時，
     * 還能用的舊訊息與快取不會因為「太早清快取」而跟著消失。</p>
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

        YamlConfiguration loaded = loadFromDisk(file);

        // 讀檔成功才提交：失敗時保持既有 current / currentLocale / 快取 / 去重記錄，
        // 避免失敗的 reload 順手把還能用的狀態清掉。
        this.current = loaded;
        this.currentLocale = target;
        this.localeCache.clear();
        this.builtinCache.clear();
        this.reportedMissingKeys.clear();
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

    /**
     * 升級補 key：把 plugin 內建資源（JAR 內 {@code lang/<locale>.yml}）的新 key
     * 補進磁碟語言檔，不覆寫管理員已修改的文案。
     *
     * <p>合併規則（重用保註解寫回）：既有 key 逐位元保留（含註解與排版），
     * 只附加缺的 key；原檔不存在時以內建資源建立可編輯副本。
     * 同步的作用域為目前 locale 與預設 locale（去重）。</p>
     *
     * <p>補進 key 後，當前生效內容與缺鍵去重記錄一併更新，
     * 因此後續查詢立刻可見新 key，不必再 {@link #reload()}。</p>
     *
     * @return 補進的 key 數量
     * @throws ConfigException 磁碟檔損壞無法解析，或寫回失敗（ACELIB-LANG-002）
     * @since 1.4.0
     */
    public int syncMissingBuiltinKeys() {
        Set<Locale> locales = new LinkedHashSet<>();
        locales.add(currentLocale != null ? currentLocale : defaultLocale);
        locales.add(defaultLocale);
        int added = 0;
        for (Locale locale : locales) {
            added += syncBuiltinForLocale(locale);
        }
        if (added > 0) {
            localeCache.clear();
            reportedMissingKeys.clear();
        }
        return added;
    }

    /**
     * 對單一 locale 執行升級補 key。
     *
     * @return 該 locale 補進的 key 數量
     */
    private int syncBuiltinForLocale(Locale locale) {
        Optional<YamlConfiguration> builtinOpt =
            builtinCache.computeIfAbsent(locale, this::loadBuiltinFile);
        if (builtinOpt.isEmpty()) {
            return 0;
        }
        Map<String, Object> builtinFlat = CommentPreservingWriter.flatten(
            builtinOpt.get().getValues(false));
        if (builtinFlat.isEmpty()) {
            return 0;
        }
        File file = resolveFile(locale);
        String originalText = null;
        Map<String, Object> diskFlat = new LinkedHashMap<>();
        if (file.exists()) {
            try {
                originalText = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                YamlConfiguration disk = new YamlConfiguration();
                disk.load(file);
                diskFlat = CommentPreservingWriter.flatten(disk.getValues(false));
            } catch (IOException | InvalidConfigurationException ex) {
                throw new ConfigException(
                    "ACELIB-LANG-002",
                    "語言檔格式錯誤：" + file.getAbsolutePath() + "（" + ex.getMessage() + "）",
                    ex
                );
            }
        } else {
            ensureLangDirectory(file);
        }
        Map<String, Object> missing = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : builtinFlat.entrySet()) {
            if (!diskFlat.containsKey(entry.getKey())) {
                missing.put(entry.getKey(), entry.getValue());
            }
        }
        if (missing.isEmpty()) {
            return 0;
        }
        // 只補不覆寫：removed 為空，且既有 key 值相同時合併器會逐行跳過。
        Map<String, Object> merged = new LinkedHashMap<>(diskFlat);
        merged.putAll(missing);
        String text = CommentPreservingWriter.merge(originalText, merged, Map.of(), Set.of());
        YamlFileWriter.writeTextAtomically(text, file.toPath(), "ACELIB-LANG-002", "語言檔");
        refreshCurrentIfSynced(locale, file);
        return missing.size();
    }

    /**
     * 同步的 locale 正好是全域目前 locale 時，刷新生效內容。
     *
     * <p>寫入剛成功，解析失敗只可能是極端競態（外部同時改檔）；
     * 此時保留舊生效內容，不把可用狀態換成不可用。</p>
     */
    private void refreshCurrentIfSynced(Locale locale, File file) {
        if (!ready || !locale.equals(currentLocale)) {
            return;
        }
        try {
            this.current = loadFromDisk(file);
        } catch (ConfigException ex) {
            safeLogger().log(Level.WARNING,
                "[ACELIB-LANG-002] synced language file cannot be re-read, "
                    + "keeping previous content: {0}",
                file.getAbsolutePath());
        }
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
            // 磁碟缺 key：退回內建資源（請求 locale → 預設 locale），仍缺才記 LANG-001。
            Optional<String> builtin = readBuiltinValue(currentLocale, key);
            if (builtin.isEmpty() && !currentLocale.equals(defaultLocale)) {
                builtin = readBuiltinValue(defaultLocale, key);
            }
            if (builtin.isEmpty()) {
                logMissingKey(key);
                return Optional.empty();
            }
            String template = builtin.get();
            if (vars == null || vars.isEmpty()) {
                return Optional.of(template);
            }
            return Optional.of(substitute(template, vars));
        }
        String template = raw.toString();
        if (vars == null || vars.isEmpty()) {
            return Optional.of(template);
        }
        return Optional.of(substitute(template, vars));
    }

    /**
     * 讀取指定 locale 的訊息（無變數替換），不變更全域 current / currentLocale 狀態。
     *
     * <p>此方法供 Bedrock fallback prompt 等「依特定 locale 讀取」場景使用；
     * 它從獨立的 per-locale 快取載入該 locale 檔案，不會切換全域 current state，
     * 因此不存在 race。若該 locale 檔案不存在或 key 缺失，回傳 {@link Optional#empty()}。</p>
     *
     * @param locale 欲讀取的 locale；不可為 null
     * @param key    訊息 key；不可為 null
     * @return 訊息內容；若 locale 檔案缺失或 key 缺失則回傳 {@link Optional#empty()}
     */
    public Optional<String> get(Locale locale, String key) {
        Objects.requireNonNull(locale, "locale");
        Objects.requireNonNull(key, "key");
        Optional<String> requested = readFromLocale(locale, key);
        if (requested.isPresent()) {
            return requested;
        }
        // 請求 locale 檔缺失或 key 缺失：退回 default locale 檔（不寫入全域 current state）。
        Locale def = getDefaultLocale();
        if (def != null && !def.equals(locale)) {
            Optional<String> fromDefault = readFromLocale(def, key);
            if (fromDefault.isPresent()) {
                return fromDefault;
            }
        }
        // 磁碟各層皆缺：退回內建資源（請求 locale → 預設 locale），磁碟永遠優先於內建。
        Optional<String> builtin = readBuiltinValue(locale, key);
        if (builtin.isPresent()) {
            return builtin;
        }
        if (def != null && !def.equals(locale)) {
            return readBuiltinValue(def, key);
        }
        return Optional.empty();
    }

    private Optional<String> readFromLocale(Locale locale, String key) {
        Optional<YamlConfiguration> state = localeCache.computeIfAbsent(locale, this::loadLocaleFile);
        if (state.isEmpty()) {
            return Optional.empty();
        }
        Object raw = state.get().get(key);
        if (raw == null) {
            return Optional.empty();
        }
        return Optional.of(raw.toString());
    }

    /**
     * 從磁碟載入指定 locale 的語言檔（不寫入全域 current state）。
     *
     * <p>回傳值就是負向快取的內容：{@link Optional#empty()} 代表「這個 locale 目前讀不到」，
     * 包含兩種可區分的原因，都在此處記錄一次：</p>
     * <ul>
     *   <li>檔案不存在 → {@code ACELIB-LANG-003}</li>
     *   <li>檔案存在但解析損壞 → {@code ACELIB-LANG-002}，附帶解析錯誤</li>
     * </ul>
     *
     * <p>兩者都進快取，避免每次查詢都重新 stat 或重新解析壞檔；只有
     * {@link #load(Locale)}／{@link #reload()} 會清空快取，讓管理者修好檔案後
     * 不必重開伺服器就能讀到。</p>
     *
     * @return 載入成功的內容；檔案不存在或格式錯誤時回傳 {@link Optional#empty()}
     */
    private Optional<YamlConfiguration> loadLocaleFile(Locale locale) {
        File file = resolveFile(locale);
        if (!file.exists()) {
            safeLogger().log(Level.WARNING,
                "[ACELIB-LANG-003] language file for locale {0} not found: {1}",
                new Object[]{locale, file.getAbsolutePath()});
            return Optional.empty();
        }
        try {
            return Optional.of(loadFromDisk(file));
        } catch (ConfigException ex) {
            safeLogger().log(Level.WARNING,
                "[{0}] language file cannot be parsed: {1}（{2}）",
                new Object[]{ex.getCode(), file.getAbsolutePath(), ex.getMessage()});
            return Optional.empty();
        }
    }

    /**
     * 從 plugin JAR 內建資源載入指定 locale 的語言檔（不寫入全域 current state）。
     *
     * <p>資源路徑為 {@code lang/<locale>.yml}（Bukkit {@code getResource} 慣例）。
     * 資源不存在 → {@link Optional#empty()}（靜默：並非每個 locale 都要內建）；{@code getResource}
     * 本身拋例外 → 同樣視為無內建資源，不中斷磁碟查詢。資源存在但解析損壞 →
     * {@code ACELIB-LANG-002} 警告並負向快取。</p>
     *
     * @return 載入成功的內容；無資源或格式錯誤時回傳 {@link Optional#empty()}
     */
    private Optional<YamlConfiguration> loadBuiltinFile(Locale locale) {
        InputStream in;
        try {
            in = plugin.getResource(LANG_DIR + "/" + localeToFileName(locale));
        } catch (Throwable t) {
            safeLogger().log(Level.FINE,
                "builtin language resource lookup failed for locale {0}: {1}",
                new Object[]{locale, t.getMessage()});
            return Optional.empty();
        }
        if (in == null) {
            return Optional.empty();
        }
        try (InputStream autoClose = in) {
            String text = new String(autoClose.readAllBytes(), StandardCharsets.UTF_8);
            YamlConfiguration cfg = new YamlConfiguration();
            cfg.loadFromString(text);
            return Optional.of(cfg);
        } catch (Exception ex) {
            safeLogger().log(Level.WARNING,
                "[ACELIB-LANG-002] builtin language resource cannot be parsed: lang/{0}（{1}）",
                new Object[]{localeToFileName(locale), ex.getMessage()});
            return Optional.empty();
        }
    }

    /**
     * 讀取內建資源的單一 key（含 per-locale 快取與負向快取）。
     */
    private Optional<String> readBuiltinValue(Locale locale, String key) {
        Optional<YamlConfiguration> state =
            builtinCache.computeIfAbsent(locale, this::loadBuiltinFile);
        if (state.isEmpty()) {
            return Optional.empty();
        }
        Object raw = state.get().get(key);
        if (raw == null) {
            return Optional.empty();
        }
        return Optional.of(raw.toString());
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
     * 寫入空的語言檔（含版本註解）；temp + atomic move，失敗保留原檔。
     */
    private static void writeEmptyLanguageFile(File file, Locale locale) {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("language.name", locale.getDisplayName(locale));
        cfg.set("language.code", localeToFileName(locale).replace(".yml", ""));
        YamlFileWriter.writeAtomically(cfg, file.toPath(), "ACELIB-LANG-002", "語言檔");
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
     *
     * <p>同一個載入週期內，同一個 {@code locale + key} 只記一次；
     * {@link #load(Locale)} 成功時清空去重記錄，所以 reload 後仍會再記一次。</p>
     */
    private void logMissingKey(String key) {
        // 先取一次 locale：去重鍵與訊息內容必須用同一個 locale，
        // 否則 reload 併發時可能記到「A locale 的去重、B locale 的訊息」。
        Locale locale = currentLocale;
        if (reportedMissingKeys.add(locale + "|" + key)) {
            safeLogger().log(Level.WARNING,
                "[ACELIB-LANG-001] message key missing: {0} (locale={1})",
                new Object[]{key, locale});
        }
    }

    /**
     * 記錄 locale fallback 警告。
     *
     * <p>用 {@code ACELIB-LANG-003}（語言檔不存在）而不是 {@code ACELIB-LANG-002}
     * （格式錯誤），讓「找不到檔」和「檔案壞掉」在 log 上可以區分。</p>
     */
    private void logFallbackWarning(Locale requested) {
        safeLogger().log(Level.WARNING,
            "[ACELIB-LANG-003] language file for locale {0} not found, falling back to {1}",
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