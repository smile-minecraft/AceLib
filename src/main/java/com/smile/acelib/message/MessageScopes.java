package com.smile.acelib.message;

import com.smile.acelib.config.LangManager;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 插件作用域訊息服務的統一工廠。
 *
 * <p>每個 plugin 在 {@code onEnable} 時 {@link #create} 自己的
 * {@link MessageScope}，在 {@code onDisable} 時 {@link #close}。
 * 作用域以 plugin 實例隔離：不跨 plugin 讀文案，{@code close} 只移除
 * 指定 plugin 的登記，不碰其他 plugin 的資源。</p>
 *
 * <p>同一 plugin 重複 {@code create} 拋 {@link IllegalStateException}
 * （{@code ACELIB-MSG-006}），避免生命週期錯誤被靜默掩蓋。例外是
 * 「已停用但從未 {@code close}」的殘留登記：靜態登記強持有 plugin 會阻礙
 * classloader 釋放，{@code create} 時會自動驅逐該殘留並重建；
 * 仍請在 {@code onDisable} 確實 {@code close}，不要依賴驅逐。</p>
 *
 * @since 1.4.0
 */
public final class MessageScopes {

    private static final ConcurrentHashMap<JavaPlugin, MessageScope> ACTIVE =
        new ConcurrentHashMap<>();

    private MessageScopes() {
        // 靜態工廠，不提供實例
    }

    /**
     * 建立指定 plugin 的訊息作用域（含語言檔載入與預設語系解析器）。
     *
     * @param plugin 擁有者 plugin；不可為 null
     * @param defaultLocale 預設 locale（fallback 目標）；不可為 null
     * @return 新建的作用域；never null
     * @throws IllegalStateException 該 plugin 已有作用域尚未關閉
     */
    public static MessageScope create(JavaPlugin plugin, Locale defaultLocale) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(defaultLocale, "defaultLocale");
        return createInternal(plugin, defaultLocale, null);
    }

    /**
     * 建立指定 plugin 的訊息作用域（含自訂語系解析器）。
     *
     * @param plugin 擁有者 plugin；不可為 null
     * @param defaultLocale 預設 locale；不可為 null
     * @param resolver 玩家語系解析器；不可為 null
     * @return 新建的作用域；never null
     * @throws IllegalStateException 該 plugin 已有作用域尚未關閉
     */
    public static MessageScope create(JavaPlugin plugin, Locale defaultLocale,
                                      PlayerLocaleResolver resolver) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(defaultLocale, "defaultLocale");
        Objects.requireNonNull(resolver, "resolver");
        return createInternal(plugin, defaultLocale, resolver);
    }

    private static MessageScope createInternal(JavaPlugin plugin, Locale defaultLocale,
                                               PlayerLocaleResolver resolver) {
        MessageScope current = ACTIVE.get(plugin);
        if (current != null && !current.isClosed()) {
            if (isEnabled(plugin)) {
                throw new IllegalStateException("[" + MessageScope.ERR_SCOPE_LIFECYCLE
                    + "] message scope already exists for plugin " + safeName(plugin)
                    + "; close it before creating a new one");
            }
            // 已停用但從未關閉的殘留登記：靜態 map 強持有 plugin 會阻礙 classloader
            // 釋放，且同一實例重啟時 create 會永久失敗。驅逐舊登記後重建；
            // 啟用中的重複建立仍以上方分支拒絕，不放寬。
            if (ACTIVE.remove(plugin, current)) {
                current.close();
            }
        }
        LangManager lang = new LangManager(plugin, defaultLocale);
        lang.load();
        MessageScope scope = resolver == null
            ? new MessageScope(plugin, lang)
            : new MessageScope(plugin, lang, resolver);
        MessageScope raced = ACTIVE.putIfAbsent(plugin, scope);
        if (raced != null && !raced.isClosed()) {
            // 並行競爭敗方退場：只關閉自己（不移除勝方的登記）。
            scope.close();
            throw new IllegalStateException("[" + MessageScope.ERR_SCOPE_LIFECYCLE
                + "] concurrent message scope creation for plugin " + safeName(plugin));
        }
        if (raced != null) {
            ACTIVE.put(plugin, scope);
        }
        return scope;
    }

    /**
     * 查詢指定 plugin 的作用域。
     *
     * @param plugin 擁有者 plugin；不可為 null
     * @return 作用域；不存在時為 empty
     */
    public static Optional<MessageScope> get(JavaPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        return Optional.ofNullable(ACTIVE.get(plugin));
    }

    /**
     * 關閉並移除指定 plugin 的作用域（具冪等性，不碰其他 plugin）。
     *
     * @param plugin 擁有者 plugin；不可為 null
     * @return 有登記並已關閉時為 true；無登記時為 false
     */
    public static boolean close(JavaPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        MessageScope scope = ACTIVE.remove(plugin);
        if (scope == null) {
            return false;
        }
        scope.close();
        return true;
    }

    /**
     * 目前登記的作用域數量（測試與診斷用）。
     */
    static int activeCount() {
        return ACTIVE.size();
    }

    /**
     * 移除登記（由 {@link MessageScope#close()} 回呼；只移除「仍指向該 scope」
     * 的登記，避免關閉舊 handle 時誤刪新 handle）。
     */
    static void unregister(JavaPlugin plugin, MessageScope scope) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(scope, "scope");
        ACTIVE.remove(plugin, scope);
    }

    /**
     * 探測 plugin 是否仍啟用；探測本身失敗視為已停用（殘留登記可驅逐）。
     */
    private static boolean isEnabled(JavaPlugin plugin) {
        try {
            return plugin.isEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    private static String safeName(JavaPlugin plugin) {
        try {
            String name = plugin.getName();
            return name != null ? name : "<unknown>";
        } catch (Throwable t) {
            return "<unknown>";
        }
    }
}
