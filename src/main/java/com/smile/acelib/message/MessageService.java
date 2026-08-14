package com.smile.acelib.message;

import com.smile.acelib.AceLibApi;
import com.smile.acelib.AceLibPlugin;
import com.smile.acelib.config.LangManager;
import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import com.smile.acelib.platform.PlatformDetector;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 統一訊息服務。
 *
 * <p>封裝「讀 LangManager 模板 → 套用 prefix / 變數替換 → 送到目標媒介
 * （chat / action bar / title-subtitle / 廣播 / console）」的完整流程，
 * 讓後續插件不需要各自重複書寫 i18n + 顯示形式分流邏輯。</p>
 *
 * <h2>對外方法</h2>
 * <ul>
 *   <li>{@link #MessageService(JavaPlugin, LangManager)} — 主要建構子（對外契約）：
 *       優先重用 AceLib canonical platform cache，一般 plugin 才透過
 *       {@link PlatformDetector} 偵測；呼叫端不需自行注入 {@link Platform}
 *       與 {@link PlatformCapability}</li>
 *   <li>{@link #format(String, Map)} — 純文字格式化（含 {@code message.prefix}）</li>
 *   <li>{@link #formatConsole(String, Map)} — console 專用格式（不套 prefix）</li>
 *   <li>{@link #sendChat(Player, String, Map)} — 玩家 chat</li>
 *   <li>{@link #sendActionBar(Player, String, Map)} — 玩家 action bar</li>
 *   <li>{@link #sendTitle(Player, String, Map)} — 玩家 title</li>
 *   <li>{@link #sendTitle(Player, String, Map, String, Map)} —
 *       title + 可選 subtitle（subtitleKey 為 null 時不送出）</li>
 *   <li>{@link #broadcast(String, Map)} — 全服廣播</li>
 *   <li>{@link #sendConsole(String, Map)} — console（logger.info）</li>
 * </ul>
 *
 * <h2>設計原則</h2>
 * <ol>
 *   <li>訊息 key 缺失 → 回傳空字串 + {@code ACELIB-MSG-001} warning，<strong>不</strong>中斷執行</li>
 *   <li>玩家 null 或離線 → noop + 適當警告，<strong>不</strong>中斷執行</li>
 *   <li>Folia 環境下操作玩家訊息走 native API，
 *       並 try-catch {@link IllegalStateException} 模式（Folia 在 non-owned region 拋的標準例外）：
 *       若捕獲則記錄 {@code ACELIB-MSG-002} warning，降級為 silent no-op</li>
 *   <li>LangManager 內部已實作 {@code {var}} 變數替換；
 *       本服務只在前置/後置處理顯示形式，不重新發明替換邏輯</li>
 *   <li>格式錯誤（例如 LangManager 連物件都抓不到）→ 回傳空字串 + {@code ACELIB-MSG-003}</li>
 *   <li>Paper / UNKNOWN 平台下 player API 拋 {@link IllegalStateException}（非 Folia
 *       region context 語意）→ 視為一般訊息層級的格式/輸出降級，輸出
 *       {@code ACELIB-MSG-003} warning；不誤標為 {@code ACELIB-MSG-002}</li>
 * </ol>
 *
 * <h2>錯誤代碼</h2>
 * <ul>
 *   <li>{@code ACELIB-MSG-001} — 訊息 key 缺失</li>
 *   <li>{@code ACELIB-MSG-002} — 在不安全上下文操作玩家訊息（Folia）</li>
 *   <li>{@code ACELIB-MSG-003} — 訊息格式錯誤，或 Paper / UNKNOWN 平台下 player
 *       API 拋 IllegalStateException 的安全降級</li>
 * </ul>
 *
 * <h2>執行緒安全</h2>
 * <p>本類別為不可變狀態：所有欄位在建構時設定且後續不變。
 * 內部呼叫 player / server API 不修改自身狀態，符合多 region 並行安全。</p>
 *
 * @see LangManager
 * @see Platform
 * @see PlatformCapability
 * @since 1.0.0
 */
public final class MessageService {

    /** 玩家導向訊息通用的 prefix key（在 {@code <locale>.yml} 內）。 */
    public static final String PREFIX_KEY = "message.prefix";

    /** 訊息 key 缺失的錯誤代碼。 */
    static final String ERR_KEY_MISSING = "ACELIB-MSG-001";

    /** Folia 不安全上下文操作玩家訊息的錯誤代碼。 */
    static final String ERR_FOLIA_UNSAFE = "ACELIB-MSG-002";

    /**
     * 訊息格式錯誤，或 Paper / UNKNOWN 平台下 player API 拋
     * {@link IllegalStateException} 的安全降級錯誤代碼。
     *
     * <p>此代碼涵蓋兩種語意：</p>
     * <ol>
     *   <li>訊息格式錯誤：{@link LangManager} 內部拋例外，或回傳
     *       {@link Optional} 帶 {@code null} body</li>
     *   <li>非 Folia 平台下，player API 拋 {@link IllegalStateException}（非
     *       region context 不安全語意）— 視為訊息層級的輸出降級，避免誤標
     *       {@link #ERR_FOLIA_UNSAFE}</li>
     * </ol>
     */
    static final String ERR_FORMAT_ERROR = "ACELIB-MSG-003";

    private static final Logger LOGGER = Logger.getLogger("AceLib");

    private final JavaPlugin plugin;
    private final LangManager lang;
    private final Platform platform;
    private final PlatformCapability capability;

    /**
     * 主要建構子（對外契約）。
     *
     * <p>已就緒的 {@link AceLibPlugin} 直接重用 canonical platform cache；
     * 其他 {@link JavaPlugin} 才透過 {@link PlatformDetector} 偵測目前執行平台，
     * 並由同一次偵測結果推導 {@link PlatformCapability}。</p>
     *
     * @param plugin 對外 owner plugin；不可為 null
     * @param lang   語言檔管理器；不可為 null
     * @throws NullPointerException 任一參數為 null
     */
    public MessageService(JavaPlugin plugin, LangManager lang) {
        this(plugin, lang, resolvePlatformContext(plugin, lang));
    }

    private MessageService(JavaPlugin plugin,
                           LangManager lang,
                           PlatformContext context) {
        this(plugin, lang, context.platform(), context.capability());
    }

    /**
     * 內部 / 測試注入用建構子：顯式提供 {@link Platform} 與 {@link PlatformCapability}。
     *
     * <p>保留此建構子僅供 <strong>同 package 測試</strong>（例如
     * {@code MessageServiceFoliaTest}）用於注入 FOLIA 環境；對外契約
     * 為 2 參數 {@link #MessageService(JavaPlugin, LangManager)}，自動解析平台。</p>
     *
     * @param plugin     對外 owner plugin；不可為 null
     * @param lang       語言檔管理器；不可為 null
     * @param platform   目前執行平台；不可為 null
     * @param capability 對應 capability profile；不可為 null
     * @throws NullPointerException 任一參數為 null
     */
    MessageService(JavaPlugin plugin,
                   LangManager lang,
                   Platform platform,
                   PlatformCapability capability) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.platform = Objects.requireNonNull(platform, "platform");
        this.capability = Objects.requireNonNull(capability, "capability");
    }

    /**
     * 驗證主要建構子參數並解析單一、相互一致的平台快照。
     */
    private static PlatformContext resolvePlatformContext(JavaPlugin plugin,
                                                          LangManager lang) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(lang, "lang");

        if (plugin instanceof AceLibPlugin aceLibPlugin && aceLibPlugin.isReady()) {
            AceLibApi api = aceLibPlugin.getApi();
            return new PlatformContext(
                api.getPlatform(), api.getPlatformCapability());
        }

        PlatformDetector detector = new PlatformDetector(
            plugin.getClass().getClassLoader());
        Platform detected = detector.detect();
        return new PlatformContext(
            detected, detector.detectCapability(detected));
    }

    private record PlatformContext(Platform platform,
                                   PlatformCapability capability) {
    }

    // -----------------------------------------------------------------
    // 平台 accessor
    // -----------------------------------------------------------------

    /** 對外暴露目前平台（診斷 / 測試用）。 */
    public Platform getPlatform() {
        return platform;
    }

    /** 對外暴露 capability profile。 */
    public PlatformCapability getCapability() {
        return capability;
    }

    // -----------------------------------------------------------------
    // 純文字格式化（含 / 不含 prefix）
    // -----------------------------------------------------------------

    /**
     * 純文字格式化：適用於玩家導向訊息，會自動套用 {@link #PREFIX_KEY message.prefix}。
     *
     * <p>訊息 key 缺失或 vars 為 null 時：</p>
     * <ul>
     *   <li>key 為 null → 拋 {@link NullPointerException}（契約）</li>
     *   <li>key 缺失 → 回傳 {@code ""} + 記錄 {@link #ERR_KEY_MISSING}</li>
     *   <li>vars 為 null → 由 {@link LangManager} 視為空 map</li>
     * </ul>
     *
     * @param key  訊息 key；不可為 null
     * @param vars 變數替換表；可為 null
     * @return 格式化後字串；key 缺失時為空字串
     */
    public String format(String key, Map<String, Object> vars) {
        Objects.requireNonNull(key, "key");
        String body = renderBody(key, vars, true);
        return body == null ? "" : body;
    }

    /**
     * Console 專用格式化：不套用 {@code message.prefix}，
     * 適合 server 主控台本身不需 prefix 的情境。
     *
     * @param key  訊息 key；不可為 null
     * @param vars 變數替換表；可為 null
     * @return 格式化後字串；key 缺失時為空字串
     */
    public String formatConsole(String key, Map<String, Object> vars) {
        Objects.requireNonNull(key, "key");
        String body = renderBody(key, vars, false);
        return body == null ? "" : body;
    }

    // -----------------------------------------------------------------
    // 玩家導向發送
    // -----------------------------------------------------------------

    /**
     * 對單一玩家發送 chat 訊息（含 {@code message.prefix}）。
     *
     * @param player 目標玩家；可為 null（→ silent no-op）
     * @param key    訊息 key；不可為 null
     * @param vars   變數替換表；可為 null
     */
    public void sendChat(Player player, String key, Map<String, Object> vars) {
        if (player == null) {
            warnSilently("sendChat called with null player (key=" + key + ")");
            return;
        }
        if (!player.isOnline()) {
            warnSilently("sendChat to offline player=" + safeName(player)
                + " (key=" + key + ")");
            return;
        }
        String body = format(key, vars);
        if (body.isEmpty()) {
            // format 內已記錄 MSG-001
            return;
        }
        try {
            player.sendMessage(body);
        } catch (IllegalStateException ex) {
            // Folia 在 non-owned region 操作玩家時會丟 IllegalStateException；
            // 其他平台同樣的例外型別不應誤標為 Folia 區域不安全，應走訊息層
            // 降級路徑（見 logPlayerOperationFailure）。
            logPlayerOperationFailure(player, "sendChat", ex);
        } catch (Throwable t) {
            safeLog(Level.WARNING,
                "[" + ERR_FORMAT_ERROR + "] sendChat failed for player=" + safeName(player)
                    + " key=" + key + ": " + t.getMessage(),
                t);
        }
    }

    /**
     * 對單一玩家發送 action bar（無 prefix；action bar 是短暫提示）。
     *
     * @param player 目標玩家；可為 null（→ silent no-op）
     * @param key    訊息 key；不可為 null
     * @param vars   變數替換表；可為 null
     */
    public void sendActionBar(Player player, String key, Map<String, Object> vars) {
        if (player == null) {
            warnSilently("sendActionBar called with null player (key=" + key + ")");
            return;
        }
        if (!player.isOnline()) {
            warnSilently("sendActionBar to offline player=" + safeName(player)
                + " (key=" + key + ")");
            return;
        }
        String body = format(key, vars);
        if (body.isEmpty()) {
            return;
        }
        try {
            player.sendActionBar(body);
        } catch (IllegalStateException ex) {
            logPlayerOperationFailure(player, "sendActionBar", ex);
        } catch (Throwable t) {
            safeLog(Level.WARNING,
                "[" + ERR_FORMAT_ERROR + "] sendActionBar failed for player=" + safeName(player)
                    + " key=" + key + ": " + t.getMessage(),
                t);
        }
    }

    /**
     * 對單一玩家發送 title（無 subtitle）。
     *
     * <p>使用預設的 {@code 10 / 70 / 20} ticks
     * （1.5s 在、2 ticks out、與標準 Bukkit 行為一致）。</p>
     */
    public void sendTitle(Player player, String key, Map<String, Object> vars) {
        sendTitle(player, key, vars, null, null);
    }

    /**
     * 對單一玩家發送 title + 可選 subtitle。
     *
     * @param player        目標玩家；可為 null（→ silent no-op）
     * @param key           title 訊息 key；不可為 null
     * @param vars          title 變數；可為 null
     * @param subtitleKey   subtitle 訊息 key；可為 null（→ 不發送 subtitle）
     * @param subtitleVars  subtitle 變數；可為 null
     */
    public void sendTitle(Player player,
                          String key,
                          Map<String, Object> vars,
                          String subtitleKey,
                          Map<String, Object> subtitleVars) {
        if (player == null) {
            warnSilently("sendTitle called with null player (key=" + key + ")");
            return;
        }
        if (!player.isOnline()) {
            warnSilently("sendTitle to offline player=" + safeName(player)
                + " (key=" + key + ")");
            return;
        }
        String title = format(key, vars);
        if (title.isEmpty()) {
            return;
        }
        String subtitle = "";
        if (subtitleKey != null) {
            String sub = format(subtitleKey, subtitleVars);
            subtitle = sub == null ? "" : sub;
        }
        try {
            // Paper / Bukkit: title(title, subtitle, fadeIn, stay, fadeOut)
            player.sendTitle(title, subtitle, 10, 70, 20);
        } catch (IllegalStateException ex) {
            logPlayerOperationFailure(player, "sendTitle", ex);
        } catch (Throwable t) {
            safeLog(Level.WARNING,
                "[" + ERR_FORMAT_ERROR + "] sendTitle failed for player=" + safeName(player)
                    + " key=" + key + ": " + t.getMessage(),
                t);
        }
    }

    // -----------------------------------------------------------------
    // 全服廣播 / console
    // -----------------------------------------------------------------

    /**
     * 對所有線上玩家廣播訊息（含 {@code message.prefix}）。
     *
     * <p>線上玩家數為 0 時不中斷；key 缺失時記錄 {@link #ERR_KEY_MISSING} 並 silent。</p>
     */
    public void broadcast(String key, Map<String, Object> vars) {
        String body = format(key, vars);
        if (body.isEmpty()) {
            return;
        }
        Server srv = safeServer();
        if (srv == null) {
            warnSilently("broadcast called but server is unavailable (key=" + key + ")");
            return;
        }
        // 廣播不走 player.sendMessage by-iteration（避免 client-server frame 處理差異）；
        // 採 Server.broadcastMessage 直接送聊天頻道，與 Bukkit 慣例一致。
        try {
            srv.broadcastMessage(body);
        } catch (Throwable t) {
            safeLog(Level.WARNING,
                "[" + ERR_FORMAT_ERROR + "] broadcast failed for key=" + key
                    + ": " + t.getMessage(),
                t);
        }
    }

    /**
     * 對 console 輸出訊息（不套 {@code message.prefix}）。
     *
     * <p>輸出策略：同時寫到 {@link JavaPlugin#getLogger() plugin logger}
     * （INFO 級別）與 Bukkit 的 {@link Server#getConsoleSender() console sender}
     * （若有）。Plugin logger 為主路徑，保證測試環境
     * （MockBukkit 的 console sender 為內部 Queue，不會冒到 JUL）也能驗證；
     * console sender 是後續插件可繼續串接 mini-message / 色彩的延伸點。</p>
     */
    public void sendConsole(String key, Map<String, Object> vars) {
        String body = formatConsole(key, vars);
        if (body.isEmpty()) {
            return;
        }
        // 主路徑：plugin logger（JUL）。直接組字串而非使用 JUL 的
        // MessageFormat ({0} substitution)，因為 Bukkit plugin logger 預設
        // 不會對單參數 pattern 做替換；為了保證輸出內容就是模板本身，
        // 採直接拼接。
        safeLogPlain(Level.INFO, "[AceLib] " + body);
        // 延伸：console sender（不阻塞，失敗只記 debug）
        Server srv = safeServer();
        if (srv != null) {
            ConsoleCommandSender console = safeGetConsoleSender(srv);
            if (console != null) {
                try {
                    console.sendMessage(body);
                } catch (Throwable t) {
                    safeLog(Level.FINE,
                        "console.sendMessage failed (non-fatal): {0}", t.getMessage());
                }
            }
        }
    }

    private static ConsoleCommandSender safeGetConsoleSender(Server srv) {
        try {
            return srv.getConsoleSender();
        } catch (Throwable t) {
            return null;
        }
    }

    // -----------------------------------------------------------------
    // 內部輔助
    // -----------------------------------------------------------------

    /**
     * 渲染訊息 body（含 / 不含 prefix）。
     *
     * @return 渲染結果；key 缺失或渲染失敗時回傳 {@code null}
     */
    private String renderBody(String key, Map<String, Object> vars, boolean applyPrefix) {
        Optional<String> opt;
        try {
            opt = lang.get(key, vars);
        } catch (Throwable t) {
            safeLog(Level.WARNING,
                "[" + ERR_FORMAT_ERROR + "] lang.get threw for key=" + key + ": " + t.getMessage(),
                t);
            return null;
        }
        if (opt.isEmpty()) {
            // LangManager 內部已輸出 ACELIB-LANG-001；此處再加一行 MSG-001 標示
            // 訊息層也感知到了，讓管理者能跨層追蹤。
            safeLog(Level.WARNING,
                "[" + ERR_KEY_MISSING + "] message key missing: {0}", key);
            return null;
        }
        String template = opt.get();
        if (template == null) {
            safeLog(Level.WARNING,
                "[" + ERR_FORMAT_ERROR + "] lang.get returned Optional with null body for key="
                    + key);
            return null;
        }
        if (!applyPrefix) {
            return template;
        }
        Optional<String> prefix = lang.get(PREFIX_KEY);
        if (prefix.isEmpty() || prefix.get().isEmpty()) {
            return template;
        }
        return prefix.get() + template;
    }

    /**
     * 統一處理 player API 拋 {@link IllegalStateException} 的降級記錄。
     *
     * <p>語意分流（依 {@link #platform}）：</p>
     * <ul>
     *   <li>{@code FOLIA}：視為 region context 不安全，輸出
     *       {@link #ERR_FOLIA_UNSAFE}（{@code ACELIB-MSG-002}）</li>
     *   <li>{@code PAPER} / {@code UNKNOWN}：同型例外在這些平台不具 region 語意，
     *       視為訊息層級的輸出降級，輸出 {@link #ERR_FORMAT_ERROR}
     *       （{@code ACELIB-MSG-003}），避免誤標為 Folia 區域不安全</li>
     * </ul>
     *
     * <p>方法名採用「player 操作失敗」中性語意，因其並非「總是 Folia 觸發」；
     * 分流責任由內部 {@code if (platform == FOLIA)} 承擔。</p>
     */
    private void logPlayerOperationFailure(Player player, String op, Throwable ex) {
        if (platform == Platform.FOLIA) {
            safeLog(Level.WARNING,
                "[" + ERR_FOLIA_UNSAFE + "] " + op + " blocked by Folia region context: "
                    + "player=" + safeName(player) + " platform=" + platform
                    + " : " + ex.getMessage(),
                ex);
            return;
        }

        // Paper / UNKNOWN 平台下 IllegalStateException 不具 Folia region 語意；
        // 維持訊息層級的格式/輸出降級語意（ACELIB-MSG-003），避免誤標 Folia。
        safeLog(Level.WARNING,
            "[" + ERR_FORMAT_ERROR + "] " + op
                + " failed with IllegalStateException: player=" + safeName(player)
                + " platform=" + platform + " : " + ex.getMessage(),
            ex);
    }

    /**
     * 取得 plugin 持有的 {@link Server}；plugin 未 onEnable / 停用後回傳 null。
     */
    private Server safeServer() {
        try {
            return plugin.getServer();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 取得玩家名字；玩家無效時回傳 {@code "<unknown>"}。
     */
    private static String safeName(Player p) {
        try {
            String n = p.getName();
            return n != null ? n : "<unknown>";
        } catch (Throwable t) {
            return "<unknown>";
        }
    }

    /**
     * 統一 logger 入口：先嘗試 {@link JavaPlugin#getLogger()}（MockBukkit 環境可為 null），
     * 退避到 {@code "AceLib"} JUL logger。
     */
    private void safeLog(Level level, String msg, Object... args) {
        try {
            Logger l = plugin.getLogger();
            if (l == null) {
                LOGGER.log(level, msg, args);
            } else {
                l.log(level, msg, args);
            }
        } catch (Throwable t) {
            LOGGER.log(level, msg, args);
        }
    }

    private void safeLog(Level level, String msg, Throwable thrown) {
        try {
            Logger l = plugin.getLogger();
            if (l == null) {
                LOGGER.log(level, msg, thrown);
            } else {
                l.log(level, msg, thrown);
            }
        } catch (Throwable t) {
            LOGGER.log(level, msg, thrown);
        }
    }

    /**
     * silent no-op 的 debug-level 警告（用於 null player / 離線 player 等已預期情境）。
     */
    private void warnSilently(String msg) {
        safeLog(Level.FINE, msg);
    }

    /**
     * 直接寫入已格式化字串（不做 JUL MessageFormat 參數替換）。
     *
     * <p>適用於已含完整內容的訊息，例如 {@code "[AceLib] " + body}；
     * 用 {@link Logger#log(Level, String)} 而非 {@code log(Level, msg, args)}，
     * 以避免 {@code {0}} 被當作 MessageFormat 佔位符卻未替換。</p>
     */
    private void safeLogPlain(Level level, String msg) {
        try {
            Logger l = plugin.getLogger();
            if (l == null) {
                LOGGER.log(level, msg);
            } else {
                l.log(level, msg);
            }
        } catch (Throwable t) {
            LOGGER.log(level, msg);
        }
    }
}
