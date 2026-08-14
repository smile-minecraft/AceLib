package com.smile.acelib.config;

import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 設定檔 + 語言檔 facade（綁定到 plugin）。
 *
 * <p>消費者取得設定與語言檔管理服務的統一入口：</p>
 * <ul>
 *   <li>{@link #bind(JavaPlugin)} — 取得（或建立）綁定到 plugin 的 facade</li>
 *   <li>{@link #withConfigSchema(ConfigSchema, ConfigVersion)} — 設定 config schema</li>
 *   <li>{@link #withLang(Locale)} — 設定 default locale</li>
 *   <li>{@link #getConfig()} / {@link #getLang()} — 取得底層 manager</li>
 *   <li>{@link #reload()} — 同時 reload config + lang</li>
 * </ul>
 *
 * <h2>使用範例</h2>
 * <pre>{@code
 * AceLibConfig facade = AceLibConfig.bind(plugin)
 *     .withConfigSchema(schema, new ConfigVersion(1, 0))
 *     .withLang(Locale.TRADITIONAL_CHINESE);
 * facade.getConfig().load();
 * facade.getLang().load();
 *
 * String greeting = facade.getLang().get("greeting",
 *     Map.of("player", player.getName())).orElse("Hi!");
 * }</pre>
 *
 * <h2>綁定語意</h2>
 * <p>使用 {@link IdentityHashMap} 維護 {@code plugin → facade} 對應，
 * 同一個 plugin 重複 {@link #bind} 回傳同一 instance。
 * 跨 plugin 各自獨立（不會碰撞）。</p>
 *
 * @since 1.0.0
 */
public final class AceLibConfig {

    private static final Map<JavaPlugin, AceLibConfig> BINDINGS = new IdentityHashMap<>();

    private final JavaPlugin plugin;
    private volatile ConfigSchema schema;
    private volatile ConfigVersion configVersion;
    private volatile Locale defaultLocale;
    private volatile ConfigManager configManager;
    private volatile LangManager langManager;

    private AceLibConfig(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    // -----------------------------------------------------------------
    // 綁定（process-local）
    // -----------------------------------------------------------------

    /**
     * 取得或建立綁定到 {@code plugin} 的 {@link AceLibConfig} facade。
     *
     * <p>同一 plugin 重複呼叫回傳同一 instance。</p>
     *
     * @param plugin 目標 plugin；不可為 null
     * @return 綁定後的 facade
     * @throws NullPointerException 當 {@code plugin} 為 null
     */
    public static AceLibConfig bind(JavaPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        synchronized (BINDINGS) {
            return BINDINGS.computeIfAbsent(plugin, AceLibConfig::new);
        }
    }

    /**
     * 取得綁定到 {@code plugin} 的 facade；若未綁定則回傳 null。
     *
     * @param plugin 目標 plugin；不可為 null
     * @return 已綁定的 facade；未綁定回傳 null
     */
    public static AceLibConfig get(JavaPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        synchronized (BINDINGS) {
            return BINDINGS.get(plugin);
        }
    }

    /**
     * 解除綁定（測試清理用）。
     *
     * <p>呼叫後，後續 {@link #bind} 將建立新 instance。</p>
     *
     * @param plugin 欲解除綁定的 plugin
     */
    public static void unbind(JavaPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        synchronized (BINDINGS) {
            BINDINGS.remove(plugin);
        }
    }

    // -----------------------------------------------------------------
    // 設定 schema / locale
    // -----------------------------------------------------------------

    /**
     * 設定 config schema 與當前版本。
     *
     * <p>呼叫此方法後，{@link #getConfig()} 才會回傳非 null 的 {@link ConfigManager}。</p>
     *
     * @param schema         schema 物件；不可為 null
     * @param currentVersion 當前版本；不可為 null
     * @return this（鏈式 API）
     */
    public AceLibConfig withConfigSchema(ConfigSchema schema, ConfigVersion currentVersion) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(currentVersion, "currentVersion");
        this.schema = schema;
        this.configVersion = currentVersion;
        this.configManager = new ConfigManager(plugin, "config.yml", schema, currentVersion);
        return this;
    }

    /**
     * 設定 default locale 並建立 {@link LangManager}。
     *
     * <p>呼叫此方法後，{@link #getLang()} 才會回傳非 null 的 {@link LangManager}。</p>
     *
     * @param defaultLocale 預設 locale；不可為 null
     * @return this（鏈式 API）
     */
    public AceLibConfig withLang(Locale defaultLocale) {
        Objects.requireNonNull(defaultLocale, "defaultLocale");
        this.defaultLocale = defaultLocale;
        this.langManager = new LangManager(plugin, defaultLocale);
        return this;
    }

    // -----------------------------------------------------------------
    // 取得底層 manager
    // -----------------------------------------------------------------

    /**
     * 取得 {@link ConfigManager}（已透過 {@link #withConfigSchema} 建立）。
     *
     * @return manager；若未呼叫 {@link #withConfigSchema} 則回傳 null
     */
    public ConfigManager getConfig() {
        return configManager;
    }

    /**
     * 取得 {@link LangManager}（已透過 {@link #withLang} 建立）。
     *
     * @return manager；若未呼叫 {@link #withLang} 則回傳 null
     */
    public LangManager getLang() {
        return langManager;
    }

    // -----------------------------------------------------------------
    // 統一 reload
    // -----------------------------------------------------------------

    /**
     * 同時 reload config 與 lang。
     *
     * <p>任何 manager 尚未建立時，該 manager 的 reload 會被跳過。</p>
     */
    public void reload() {
        if (configManager != null) {
            configManager.reload();
        }
        if (langManager != null) {
            langManager.reload();
        }
    }
}