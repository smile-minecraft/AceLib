package com.smile.acelib.message;

import com.smile.acelib.config.LangManager;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 單一 plugin 的訊息作用域 handle。
 *
 * <p>持有該 plugin 專屬的 {@link LangManager} 與 {@link MessageService}：
 * 同一個 key 在不同 plugin 讀到各自的文案，不跨 plugin 讀取或清理。
 * 實例由 {@link MessageScopes} 統一建立與清理；下游只操作自己持有的 handle。</p>
 *
 * <p>生命週期：{@code onEnable} 時 {@code create}，{@code onDisable} 時
 * {@link #close()}。{@code close} 後任何操作拋
 * {@link IllegalStateException}（{@code ACELIB-MSG-006}），具冪等性。</p>
 *
 * @since 1.4.0
 */
public final class MessageScope {

    /**
     * 訊息作用域生命週期違規的錯誤代碼（重複建立／已關閉後使用）。
     */
    static final String ERR_SCOPE_LIFECYCLE = "ACELIB-MSG-006";

    private static final Logger LOGGER = Logger.getLogger("AceLib");

    private final JavaPlugin plugin;
    private final LangManager lang;
    private final MessageService messages;
    private volatile PlayerLocaleResolver resolver;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * 主要建構子：使用預設玩家語系解析器（跟隨 {@link Player#locale()}，
     * 失敗時退回語言檔預設語系）。
     *
     * @param plugin 擁有此作用域的 plugin；不可為 null
     * @param lang 該 plugin 專屬的語言檔管理器；不可為 null
     */
    public MessageScope(JavaPlugin plugin, LangManager lang) {
        this(plugin, lang, PlayerLocaleResolver.playerLocale(
            Objects.requireNonNull(lang, "lang").getDefaultLocale()));
    }

    /**
     * 含自訂解析器的建構子。
     *
     * @param plugin 擁有此作用域的 plugin；不可為 null
     * @param lang 該 plugin 專屬的語言檔管理器；不可為 null
     * @param resolver 玩家語系解析器；不可為 null
     */
    public MessageScope(JavaPlugin plugin, LangManager lang, PlayerLocaleResolver resolver) {
        this(plugin, lang, new MessageService(plugin, lang), resolver);
    }

    /**
     * 內部／測試注入用建構子：顯式提供 {@link MessageService}。
     */
    MessageScope(JavaPlugin plugin, LangManager lang, MessageService messages,
                 PlayerLocaleResolver resolver) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    /** 擁有此作用域的 plugin。 */
    public JavaPlugin plugin() {
        return plugin;
    }

    /** 此作用域專屬的語言檔管理器。 */
    public LangManager lang() {
        return lang;
    }

    /** 此作用域專屬的訊息服務。 */
    public MessageService messages() {
        return messages;
    }

    /** 目前的玩家語系解析器。 */
    public PlayerLocaleResolver resolver() {
        return resolver;
    }

    /**
     * 替換玩家語系解析器（不強制偏好儲存方式）。
     *
     * @param resolver 新解析器；不可為 null
     */
    public void setResolver(PlayerLocaleResolver resolver) {
        requireOpen();
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    /**
     * 以全域目前語系渲染一次，聊天／ActionBar／GUI／表單共用此結果。
     *
     * @param key 訊息 key；不可為 null
     * @param vars 變數替換表；可為 null
     * @return 單次渲染結果；never null
     */
    public RenderedMessage render(String key, Map<String, Object> vars) {
        requireOpen();
        return messages.render(key, vars);
    }

    /**
     * 以指定語系渲染一次。
     *
     * @param key 訊息 key；不可為 null
     * @param vars 變數替換表；可為 null
     * @param locale 指定語系；可為 null（→ 全域目前語系，與 {@link #render(String, Map)}
     *     一致）
     * @return 單次渲染結果；never null
     */
    public RenderedMessage render(String key, Map<String, Object> vars, Locale locale) {
        requireOpen();
        return messages.render(key, vars, locale);
    }

    /**
     * 建立顯示標籤：程式識別字與顯示文案分離。
     *
     * <p>顯示文字取該次渲染的表單安全字串視圖（與表單／GUI 共用：不含聊天
     * {@code prefix}，MiniMessage 已解析為可見文字），因此管理員改文案或
     * 調整顏色只會改變顯示，不會動到程式分支用的 {@code id}。
     * 缺 key 時文字退回 {@code id} 本身（診斷已在渲染時以
     * {@code ACELIB-MSG-001} 記錄），程式分支仍可用 {@code id} 繼續運作。</p>
     *
     * @param id 穩定的程式識別字；不可為 null
     * @param labelKey 顯示文案的訊息 key；不可為 null
     * @param vars 變數替換表；可為 null
     * @return 顯示標籤；never null
     */
    public MessageLabel label(String id, String labelKey, Map<String, Object> vars) {
        requireOpen();
        Objects.requireNonNull(id, "id");
        RenderedMessage rendered = render(labelKey, vars);
        if (rendered.missing()) {
            return new MessageLabel(id, id);
        }
        return new MessageLabel(id, rendered.formText());
    }

    /**
     * 以指定語系建立顯示標籤。
     *
     * @param id 穩定的程式識別字；不可為 null
     * @param labelKey 顯示文案的訊息 key；不可為 null
     * @param vars 變數替換表；可為 null
     * @param locale 指定語系；可為 null（→ 全域目前語系）
     * @return 顯示標籤；never null
     */
    public MessageLabel label(String id, String labelKey, Map<String, Object> vars,
                              Locale locale) {
        requireOpen();
        Objects.requireNonNull(id, "id");
        RenderedMessage rendered = render(labelKey, vars, locale);
        if (rendered.missing()) {
            return new MessageLabel(id, id);
        }
        return new MessageLabel(id, rendered.formText());
    }

    /**
     * 對玩家發送聊天訊息（語系由解析器決定，解析失敗退回預設語系）。
     *
     * @param player 目標玩家；可為 null（→ silent no-op）
     * @param key 訊息 key；不可為 null
     * @param vars 變數替換表；可為 null
     */
    public void sendChat(Player player, String key, Map<String, Object> vars) {
        requireOpen();
        Objects.requireNonNull(key, "key");
        messages.sendChat(player, messages.render(key, vars, resolveLocale(player)));
    }

    /**
     * 對玩家發送 ActionBar（語系由解析器決定）。
     *
     * @param player 目標玩家；可為 null（→ silent no-op）
     * @param key 訊息 key；不可為 null
     * @param vars 變數替換表；可為 null
     */
    public void sendActionBar(Player player, String key, Map<String, Object> vars) {
        requireOpen();
        Objects.requireNonNull(key, "key");
        messages.sendActionBar(player, messages.render(key, vars, resolveLocale(player)));
    }

    /**
     * 對玩家發送 title＋可選 subtitle（語系由解析器決定）。
     *
     * @param player 目標玩家；可為 null（→ silent no-op）
     * @param key title 訊息 key；不可為 null
     * @param vars title 變數；可為 null
     * @param subtitleKey subtitle 訊息 key；可為 null（→ 不發送 subtitle）
     * @param subtitleVars subtitle 變數；可為 null
     */
    public void sendTitle(Player player, String key, Map<String, Object> vars,
                          String subtitleKey, Map<String, Object> subtitleVars) {
        requireOpen();
        Objects.requireNonNull(key, "key");
        Locale locale = resolveLocale(player);
        RenderedMessage title = messages.render(key, vars, locale);
        RenderedMessage subtitle =
            subtitleKey == null ? null : messages.render(subtitleKey, subtitleVars, locale);
        messages.sendTitle(player, title, subtitle);
    }

    /**
     * 對全服廣播（使用全域目前語系，與 {@link MessageService#broadcast(String, Map)}
     * 一致）。
     *
     * @param key 訊息 key；不可為 null
     * @param vars 變數替換表；可為 null
     */
    public void broadcast(String key, Map<String, Object> vars) {
        requireOpen();
        Objects.requireNonNull(key, "key");
        messages.broadcast(messages.render(key, vars));
    }

    /**
     * 重新載入此作用域的語言檔（新文案即時生效，缺鍵去重記錄重置）。
     */
    public void reload() {
        requireOpen();
        lang.reload();
    }

    /**
     * 升級補 key：把內建資源的新 key 補進磁碟檔，不覆寫管理員修改。
     *
     * @return 補進的 key 數量
     */
    public int syncBuiltinDefaults() {
        requireOpen();
        return lang.syncMissingBuiltinKeys();
    }

    /**
     * 關閉此作用域並從工廠登記移除（具冪等性；只移除自己的登記）。
     */
    public void close() {
        if (closed.compareAndSet(false, true)) {
            MessageScopes.unregister(plugin, this);
        }
    }

    /** 是否已關閉。 */
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * 以目前解析器決定語系；解析器拋例外或回傳 null 時退回預設語系
     * 並記錄 {@code ACELIB-MSG-003}（輸出降級），不中斷發送。
     */
    private Locale resolveLocale(Player player) {
        try {
            Locale locale = resolver.resolve(player);
            if (locale != null) {
                return locale;
            }
            safeLog(Level.WARNING,
                "[ACELIB-MSG-003] locale resolver returned null; falling back to {0}",
                lang.getDefaultLocale());
        } catch (Throwable t) {
            safeLog(Level.WARNING,
                "[ACELIB-MSG-003] locale resolver failed; falling back to {0}: {1}",
                lang.getDefaultLocale(), t.getMessage());
        }
        return lang.getDefaultLocale();
    }

    /**
     * 已關閉後的使用一律拒絕（可診斷的生命週期違規，不靜默吞掉）。
     */
    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("[" + ERR_SCOPE_LIFECYCLE + "] message scope for plugin "
                + safePluginName() + " is closed");
        }
    }

    private String safePluginName() {
        try {
            String name = plugin.getName();
            return name != null ? name : "<unknown>";
        } catch (Throwable t) {
            return "<unknown>";
        }
    }

    private void safeLog(Level level, String msg, Object... args) {
        try {
            Logger logger = plugin.getLogger();
            if (logger == null) {
                LOGGER.log(level, msg, args);
            } else {
                logger.log(level, msg, args);
            }
        } catch (Throwable t) {
            LOGGER.log(level, msg, args);
        }
    }
}
