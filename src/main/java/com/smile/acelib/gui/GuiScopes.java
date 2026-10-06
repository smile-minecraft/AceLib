package com.smile.acelib.gui;

import com.smile.acelib.diagnostics.Clock;
import com.smile.acelib.form.FormService;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 插件隔離 GUI 作用域的統一工廠（Supported API）。
 *
 * <p>每個 plugin 在 {@code onEnable} 時 {@link #create} 自己的
 * {@link GuiScope}，在 {@code onDisable} 時 {@link #close}（AceLib 也會在
 * {@code PluginDisableEvent} 自動關閉）。作用域以 plugin 實例隔離：
 * 只能操作自己開的 GUI，{@code close} 只結束自己的 GUI、只移除自己的登記，
 * 不碰其他 plugin 的資源。底層 session 登記由各作用域共用；
 * 新 GUI 取代其他 plugin 的 GUI 時，原擁有者會收到取代通知。</p>
 *
 * <p>同一 plugin 重複 {@code create} 拋 {@link IllegalStateException}
 * （{@code ACELIB-GUI-020}），避免生命週期錯誤被靜默掩蓋。例外是
 * 「已停用但從未 {@code close}」的殘留登記：靜態登記強持有 plugin 會阻礙
 * classloader 釋放，{@code create} 時會自動驅逐該殘留並重建；
 * 仍請在 {@code onDisable} 確實 {@code close}，不要依賴驅逐。</p>
 *
 * @since 1.4.0
 */
public final class GuiScopes {

    private static final Logger LOGGER = Logger.getLogger("AceLib");

    private static final ConcurrentHashMap<JavaPlugin, GuiScope> ACTIVE =
        new ConcurrentHashMap<>();

    private GuiScopes() {
        // 靜態工廠，不提供實例
    }

    /**
     * 建立指定 plugin 的 GUI 作用域（Java GUI 呈現）。
     *
     * @param plugin 擁有者 plugin；不可為 null
     * @param service 共用 GUI 服務；不可為 null
     * @return 新建的作用域；never null
     * @throws IllegalStateException 該 plugin 已有作用域尚未關閉
     */
    public static GuiScope create(JavaPlugin plugin, GuiService service) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(service, "service");
        return createInternal(plugin, () -> service, Clock.system(), null, null);
    }

    /**
     * 建立指定 plugin 的 GUI 作用域（含可注入時鐘，按鈕冷卻測試用）。
     *
     * @param plugin 擁有者 plugin；不可為 null
     * @param service 共用 GUI 服務；不可為 null
     * @param clock 時間來源；不可為 null
     * @return 新建的作用域；never null
     * @throws IllegalStateException 該 plugin 已有作用域尚未關閉
     */
    public static GuiScope create(JavaPlugin plugin, GuiService service, Clock clock) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(clock, "clock");
        return createInternal(plugin, () -> service, clock, null, null);
    }

    /**
     * 建立指定 plugin 的 GUI 作用域（含基岩表單呈現與 reload 彈性）。
     *
     * <p>服務以 supplier 包裝：每次操作才讀取，AceLib reload 提交新服務後
     * 自動取到新實例（舊 session 已隨舊服務停用而失效，需以
     * {@link GuiScope#reopen} 重開）。基岩判定成立且步驟帶表單時走原生表單
     * 呈現，否則退回開啟 Java inventory（Geyser 會轉譯顯示）。</p>
     *
     * @param plugin 擁有者 plugin；不可為 null
     * @param services 共用 GUI 服務供應器；不可為 null
     * @param clock 時間來源；不可為 null
     * @param forms 表單服務；與 {@code bedrockProbe} 同時為 null（純 Java）
     *     或同時非 null（啟用基岩呈現），混用拋例外
     * @param bedrockProbe 基岩玩家判定；與 {@code forms} 同進退
     * @return 新建的作用域；never null
     * @throws IllegalStateException 該 plugin 已有作用域尚未關閉
     */
    public static GuiScope create(JavaPlugin plugin, Supplier<GuiService> services,
            Clock clock, FormService forms, Predicate<UUID> bedrockProbe) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(services, "services");
        Objects.requireNonNull(clock, "clock");
        if ((forms == null) != (bedrockProbe == null)) {
            throw new IllegalArgumentException(
                "[" + GuiErrorCode.INVALID_INPUT + "] forms 與 bedrockProbe "
                    + "必須同時為 null 或同時非 null");
        }
        return createInternal(plugin, services, clock, forms, bedrockProbe);
    }

    private static GuiScope createInternal(JavaPlugin plugin,
            Supplier<GuiService> services, Clock clock, FormService forms,
            Predicate<UUID> bedrockProbe) {
        String ownerName = ownerNameOf(plugin);
        GuiScope current = ACTIVE.get(plugin);
        if (current != null && !current.isClosed()) {
            if (isEnabled(plugin)) {
                throw new IllegalStateException("[" + GuiErrorCode.SCOPE_CLOSED
                    + "] gui scope already exists for plugin " + ownerName
                    + "; close it before creating a new one");
            }
            // 已停用但從未關閉的殘留登記：靜態 map 強持有 plugin 會阻礙 classloader
            // 釋放，且同一實例重啟時 create 會永久失敗。驅逐舊登記後重建；
            // 啟用中的重複建立仍以上方分支拒絕，不放寬。
            if (ACTIVE.remove(plugin, current)) {
                current.close();
            }
        }
        // 擁有者標記以 plugin 名區分：同名不同實例的兩個 live 作用域會互相覆寫
        // session 歸屬，先拒絕，避免通知與清理錯亂。
        for (GuiScope other : ACTIVE.values()) {
            if (!other.isClosed() && other.ownerName().equals(ownerName)
                    && other.plugin() != plugin) {
                throw new IllegalStateException("[" + GuiErrorCode.SCOPE_CLOSED
                    + "] gui owner name already registered by another plugin: "
                    + ownerName);
            }
        }
        GuiScope scope = new GuiScope(plugin, ownerName, services, clock, forms,
            bedrockProbe);
        GuiScope raced = ACTIVE.putIfAbsent(plugin, scope);
        if (raced != null && !raced.isClosed()) {
            // 並行競爭敗方退場：只關閉自己（不移除勝方的登記）。
            scope.close();
            throw new IllegalStateException("[" + GuiErrorCode.SCOPE_CLOSED
                + "] concurrent gui scope creation for plugin " + ownerName);
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
    public static Optional<GuiScope> get(JavaPlugin plugin) {
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
        GuiScope scope = ACTIVE.remove(plugin);
        if (scope == null) {
            return false;
        }
        scope.close();
        return true;
    }

    /**
     * 任一 plugin 停用時的接線入口（供 AceLib 內部 disable listener 與測試）。
     *
     * <p>永不拋例外：null 或非 {@link JavaPlugin} 直接返回。
     * 下游仍應在 {@code onDisable} 主動 {@link #close}，
     * 不要依賴此自動關閉。</p>
     *
     * @param plugin 被停用的 plugin；可為 null
     */
    public static void handlePluginDisable(Plugin plugin) {
        try {
            if (plugin instanceof JavaPlugin javaPlugin) {
                close(javaPlugin);
            }
        } catch (Throwable t) {
            LOGGER.fine("gui scope disable dispatch failed (ignored): " + t);
        }
    }

    /**
     * 目前登記的作用域數量（測試與診斷用）。
     */
    static int activeCount() {
        return ACTIVE.size();
    }

    /**
     * 移除登記（由 {@link GuiScope#close()} 回呼；只移除「仍指向該 scope」
     * 的登記，避免關閉舊 handle 時誤刪新 handle）。
     */
    static void unregister(JavaPlugin plugin, GuiScope scope) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(scope, "scope");
        ACTIVE.remove(plugin, scope);
    }

    /**
     * 依擁有者標記查詢作用域（服務 listener 分派用）。
     *
     * @return 對應作用域；無登記時為 null
     */
    static GuiScope findByOwner(String ownerName) {
        if (ownerName == null) {
            return null;
        }
        for (GuiScope scope : ACTIVE.values()) {
            if (!scope.isClosed() && scope.ownerName().equals(ownerName)) {
                return scope;
            }
        }
        return null;
    }

    /**
     * 通知原擁有者其 GUI 被取代（取代接線用）。
     */
    static void notifyReplaced(String oldOwner, UUID playerUuid,
            GuiSession oldSession, GuiSession newSession) {
        GuiScope scope = findByOwner(oldOwner);
        if (scope != null) {
            scope.onReplacedInternal(playerUuid, oldSession, newSession);
        }
    }

    /**
     * 丟棄各作用域中該玩家的狀態（退服接線用）。
     */
    static void dropPlayer(UUID playerUuid) {
        for (GuiScope scope : ACTIVE.values()) {
            try {
                scope.dropPlayerState(playerUuid);
            } catch (Throwable t) {
                LOGGER.fine("gui scope drop player failed (ignored): " + t);
            }
        }
    }

    /**
     * 通知各作用域關閉事件（視窗關閉接線用）。
     *
     * <p>各作用域只丟棄 generation 相符的狀態 — 取代導航產生的舊視窗關閉事件
     * 會被忽略，避免清掉新視圖。</p>
     */
    static void notifyInventoryClosed(UUID playerUuid, Long closingGeneration) {
        for (GuiScope scope : ACTIVE.values()) {
            try {
                scope.dropViewState(playerUuid, closingGeneration);
            } catch (Throwable t) {
                LOGGER.fine("gui scope drop view failed (ignored): " + t);
            }
        }
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

    private static String ownerNameOf(JavaPlugin plugin) {
        try {
            String name = plugin.getName();
            if (name != null) {
                return name;
            }
        } catch (Throwable ignored) {
            // fall through to fallback
        }
        return "unknown-" + Integer.toHexString(System.identityHashCode(plugin));
    }
}
