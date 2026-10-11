package com.smile.acelib.message;

import com.smile.acelib.AceLibApi;
import com.smile.acelib.AceLibPlugin;
import com.smile.acelib.bedrock.BedrockPlayerInfo;
import com.smile.acelib.bedrock.BedrockService;
import com.smile.acelib.config.LangManager;
import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import com.smile.acelib.platform.PlatformDetector;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.translation.GlobalTranslator;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
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
 *   <li>{@link #formatFormText(String, Map, Locale)} /
 *       {@link #formatFormText(String, Map, FormTextOptions)} — 表單文字格式化
 *       （基岩表單可安全顯示字串，不套 prefix）</li>
 *   <li>{@link #render(String, Map)} / {@link #render(String, Map, Locale)} —
 *       單次共用渲染（{@link RenderedMessage}：component／text／formText 三視圖；
 *       聊天、ActionBar、GUI 與表單共用同一份結果）</li>
 *   <li>{@link #renderDetailed(String, Map)} /
 *       {@link #renderDetailed(String, Map, Locale)} — 帶狀態的單次渲染
 *       （{@link DetailedRender}：成功／語系未載入／缺 key／渲染失敗可分辨，
 *       缺 key 診斷帶完整 key 與可用語系）</li>
 *   <li>{@link #format(String, Map)} — 純文字格式化（含 {@code message.prefix}）</li>
 *   <li>{@link #formatPlain(String, Map)} /
 *       {@link #formatPlain(String, Map, boolean)} — 純文字格式化
 *       （去除全部 MiniMessage 標記只留可讀文字，可選是否帶 prefix）</li>
 *   <li>{@link #sendChat(Player, String, Map)} — 玩家 chat</li>
 *   <li>{@link #sendActionBar(Player, String, Map)} — 玩家 action bar</li>
 *   <li>{@link #sendTitle(Player, String, Map)} — 玩家 title</li>
 *   <li>{@link #sendTitle(Player, String, Map, String, Map)} —
 *       title + 可選 subtitle（subtitleKey 為 null 時不送出）</li>
 *   <li>{@link #broadcast(String, Map)} — 全服廣播</li>
 *   <li>{@link #sendConsole(String, Map)} — console（logger.info）</li>
 *   <li>{@link #sendChat(Player, RenderedMessage)} /
 *       {@link #sendActionBar(Player, RenderedMessage)} /
 *       {@link #sendTitle(Player, RenderedMessage, RenderedMessage)} /
 *       {@link #broadcast(RenderedMessage)} — 已渲染訊息發送（Component 視圖，
 *       不重讀語言檔；缺 key 結果不發送）</li>
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
 *   <li>{@code ACELIB-MSG-004} — 基岩玩家查詢失敗（lookup / getPlayerInfo 拋例外或
 *       無法判定）；視為非基岩玩家並沿用原始 Component，但留下可追蹤 warning</li>
 *   <li>{@code ACELIB-MSG-005} — 表單文字渲染失敗；回傳純文字版本，不中斷執行</li>
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

    /**
     * 基岩玩家查詢失敗（lookup / getPlayerInfo 拋例外或無法判定）的錯誤代碼。
     *
     * <p>查詢失敗時不中斷：視為非基岩玩家，沿用原始 {@link Component} 發送，
     * 但必須留下可追蹤的 {@code ACELIB-MSG-004} warning，避免被 {@code warnSilently}
     * 靜默吞掉而無法診斷。</p>
     */
    static final String ERR_BEDROCK_LOOKUP = "ACELIB-MSG-004";

    private static final Logger LOGGER = Logger.getLogger("AceLib");

    private final JavaPlugin plugin;
    private final LangManager lang;
    private final Platform platform;
    private final PlatformCapability capability;
    private final BedrockService bedrock;

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
        this(plugin, lang, resolveBedrockService(plugin));
    }

    /**
     * 含 Bedrock 偵測 seam 的建構子（Supported API，自 1.1.2 起由 package-private
     * 提升為公開）。
     *
     * <p>下游插件若已持有 {@link BedrockService} facade，可透過此建構子讓
     * {@code *WithFallback} 方法具備基岩玩家 click 降級能力。典型用法：從
     * {@code AceLibApi#getBedrockService()} 取得 facade 後注入；若取得的是
     * {@code forUnavailable} facade，{@link MessageService} 會安全捕捉其拋出的
     * {@link IllegalStateException} 並退回原始 {@link net.kyori.adventure.text.Component}，
     * 不降級、不中斷。</p>
     *
     * <p>未提供 bedrock 時（2 參數建構子）會自動解析 canonical bedrock facade
     * （已 ready 的 {@link AceLibPlugin} 取 {@code AceLibApi#getBedrockService()}，
     * 否則 {@code forUnavailable}），此時 {@code isBedrockPlayer} 一律安全回 false，
     * 所有訊息沿用原始 Component 發送，不受基岩 seam 影響。</p>
     *
     * <p>此建構子自 1.0.0 即存在，但直到 1.1.2 才由 package-private 注入 seam
     * 提升為公開 Supported API；在此之前下游無法直接注入 {@link BedrockService}，
     * 導致四個 {@code *WithFallback} 方法對下游永遠不降級。</p>
     *
     * @param plugin     對外 owner plugin；不可為 null
     * @param lang       語言檔管理器；不可為 null
     * @param bedrock    基岩玩家查詢 facade；不可為 null
     * @throws NullPointerException 任一參數為 null
     * @since 1.1.2
     */
    public MessageService(JavaPlugin plugin, LangManager lang, BedrockService bedrock) {
        this(plugin, lang, resolvePlatformContext(plugin, lang), bedrock);
    }

    private MessageService(JavaPlugin plugin,
                           LangManager lang,
                           PlatformContext context,
                           BedrockService bedrock) {
        this(plugin, lang, context.platform(), context.capability(), bedrock);
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
        this(plugin, lang, platform, capability, BedrockService.forUnavailable(BedrockService.NOT_READY));
    }

    /**
     * 內部 / 測試注入用建構子：顯式提供 {@link Platform}、{@link PlatformCapability}
     * 與 {@link BedrockService}。
     */
    MessageService(JavaPlugin plugin,
                   LangManager lang,
                   Platform platform,
                   PlatformCapability capability,
                   BedrockService bedrock) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.platform = Objects.requireNonNull(platform, "platform");
        this.capability = Objects.requireNonNull(capability, "capability");
        this.bedrock = Objects.requireNonNull(bedrock, "bedrock");
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

    /**
     * 解析對外契約（2 參數建構子）應使用的 {@link BedrockService}。
     *
     * <p>已就緒的 {@link AceLibPlugin} 直接重用 canonical bedrock facade
     * （{@code AceLibApi#getBedrockService()}），使正式 ready plugin 透過 2 參數
     * 建構子也能取得基岩玩家 click 降級能力，不會永遠失去 fallback；其他
     * {@link JavaPlugin} 或尚未 ready 的 plugin 則以 {@code forUnavailable} facade
     * 取代（{@code isBedrockPlayer} 一律安全回 false）。</p>
     *
     * <p>任何例外（含 {@code getApi()} 尚未綁定）都視為不可用並安全退回
     * unavailable facade，不中斷建構。</p>
     */
    private static BedrockService resolveBedrockService(JavaPlugin plugin) {
        if (plugin instanceof AceLibPlugin aceLibPlugin && aceLibPlugin.isReady()) {
            try {
                BedrockService svc = aceLibPlugin.getApi().getBedrockService();
                if (svc != null) {
                    return svc;
                }
            } catch (Throwable t) {
                // 退回 unavailable；不中斷建構。
            }
        }
        return BedrockService.forUnavailable(BedrockService.NOT_READY);
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
        return renderInternal(key, vars, null, false, 0).text();
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

    /**
     * 純文字格式化：給 console 與外部頻道使用，去除全部 MiniMessage 標記只留可讀文字。
     *
     * <p>預設不帶 {@code message.prefix}（與 {@link #formatConsole} 一致）；
     * 要帶前綴時用 {@link #formatPlain(String, Map, boolean)}。</p>
     *
     * <p>實作：模板經安全變數替換（使用者值先跳脫，與 {@link #format} 同規則）後
     * 解析為 Component，再走 plain 序列化；解析失敗時盡力去除標記
     * （見 {@link #stripTagsBestEffort}），仍回傳可讀文字，不中斷執行。</p>
     *
     * <p>錯誤處理：key 為 null 拋 {@link NullPointerException}；
     * key 缺失回傳空字串 + {@code ACELIB-MSG-001}；
     * 讀取拋錯回傳空字串 + {@code ACELIB-MSG-003}。</p>
     *
     * @param key 訊息 key；不可為 null
     * @param vars 變數替換表；可為 null
     * @return 去除標記的純文字；key 缺失或讀取失敗時為空字串
     * @since 1.5.0
     */
    public String formatPlain(String key, Map<String, Object> vars) {
        return formatPlain(key, vars, false);
    }

    /**
     * 純文字格式化（可選前綴）：同 {@link #formatPlain(String, Map)}，
     * 另依 {@code withPrefix} 決定是否在正文前加上 {@code message.prefix}。
     *
     * <p>前綴與正文合併後一起解析：前綴本身若含 MiniMessage 標記，
     * 會被當成格式解析而非原文透出，保證輸出不殘留標記。</p>
     *
     * @param key 訊息 key；不可為 null
     * @param vars 變數替換表；可為 null
     * @param withPrefix 是否加上 {@code message.prefix}
     * @return 去除標記的純文字；key 缺失或讀取失敗時為空字串
     * @since 1.5.0
     */
    public String formatPlain(String key, Map<String, Object> vars, boolean withPrefix) {
        Objects.requireNonNull(key, "key");
        Locale effective = lang.getCurrentLocale();
        if (effective == null) {
            effective = lang.getDefaultLocale();
        }
        if (effective == null) {
            effective = Locale.ROOT;
        }
        Optional<String> opt;
        try {
            opt = lang.get(key, null);
        } catch (Throwable t) {
            safeLog(Level.WARNING,
                "[" + ERR_FORMAT_ERROR + "] lang.get threw for key=" + key + ": " + t.getMessage(),
                t);
            return "";
        }
        if (opt == null || opt.isEmpty()) {
            safeLog(Level.WARNING,
                "[" + ERR_KEY_MISSING + "] message key missing: {0}", key);
            return "";
        }
        String template = opt.get();
        if (template == null) {
            safeLog(Level.WARNING,
                "[" + ERR_FORMAT_ERROR + "] lang.get returned Optional with null body for key="
                    + key);
            return "";
        }
        String substituted = safeSubstitute(template, vars);
        String source = withPrefix ? prefixOf(effective) + substituted : substituted;
        Component parsed = deserializeOrNull(source, null, ERR_FORMAT_ERROR,
            "formatPlain parse failed for key=" + key);
        if (parsed != null) {
            return PlainTextComponentSerializer.plainText().serialize(parsed);
        }
        return stripTagsBestEffort(source);
    }

    /**
     * 失敗路徑的盡力去標記（package-private；可單測）。
     *
     * <p>依序去除：完整 MiniMessage 標記（{@code <...>}）、legacy 色彩碼
     * （{@code §X}）、結尾未閉合的 {@code <...}。原文中的 {@code <}
     * （例如 {@code a < b}，無配對 {@code >} 且非行尾標記開頭）會保留，
     * 避免誤傷可讀文字。</p>
     *
     * @param source 原始字串；可為 null（→ 空字串）
     * @return 去除標記後的字串；never null
     */
    static String stripTagsBestEffort(String source) {
        if (source == null) {
            return "";
        }
        String out = source.replaceAll("<[^<>]*>", "");
        out = out.replaceAll("§.", "");
        return out.replaceAll("<[^<>\\s]*$", "");
    }

    /**
     * 表單文字格式化（基岩表單可安全顯示字串）：讀取 rich template
     * （MiniMessage 字串），經安全變數替換後以 {@link FormText} 規則轉換。
     *
     * <p>與 {@link #format(String, Map)} 不同，本方法<strong>不</strong>套用
     * {@code message.prefix}，且 click 提示使用插件語系
     * （{@code message.bedrock.fallback.*}，沿用既有安全預設文字行為）。
     * 本 overload 不附加 click 提示、不截斷。</p>
     *
     * <p><strong>多載歧義提醒</strong>：另有
     * {@link #formatFormText(String, Map, FormTextOptions)} 多載；
     * 第三個參數若要傳 {@code null}，字面 {@code null} 會因多載歧義而無法編譯，
     * 請先指派給具明確型別的變數（{@code Locale} 或 {@code FormTextOptions}）再傳入。</p>
     *
     * <p>錯誤處理（與 {@link #format} 一致）：key 為 null 拋
     * {@link NullPointerException}；key 缺失回傳空字串 +
     * {@code ACELIB-MSG-001}；解析失敗回傳純文字退回 +
     * {@code ACELIB-MSG-003}；渲染失敗回傳純文字退回 +
     * {@code ACELIB-MSG-005}。純格式化方法不受服務啟用狀態 guard 影響。</p>
     *
     * @param key    訊息 key；不可為 null
     * @param vars   變數替換表；可為 null
     * @param locale 翻譯 locale；可為 null（→ 預設語系）
     * @return 表單可安全顯示的字串；key 缺失時為空字串
     * @since 1.3.0
     */
    public String formatFormText(String key, Map<String, Object> vars, Locale locale) {
        Objects.requireNonNull(key, "key");
        return renderInternal(key, vars, locale, false, 0).formText();
    }

    /**
     * 表單文字格式化（完整選項）：同
     * {@link #formatFormText(String, Map, Locale)}，另依
     * {@link FormTextOptions} 決定是否附加插件語系 click 提示、
     * 可見字元上限與 locale（null locale → 預設語系）。
     * 多載歧義提醒見 {@link #formatFormText(String, Map, Locale)}。
     *
     * @param key     訊息 key；不可為 null
     * @param vars    變數替換表；可為 null
     * @param options 渲染選項；不可為 null
     * @return 表單可安全顯示的字串；key 缺失時為空字串
     * @throws IllegalArgumentException 當 {@code options} 為 null
     * @since 1.3.0
     */
    public String formatFormText(String key, Map<String, Object> vars, FormTextOptions options) {
        Objects.requireNonNull(key, "key");
        if (options == null) {
            throw new IllegalArgumentException("options must not be null");
        }
        return renderInternal(key, vars, options.locale(),
            options.clickHints(), options.maxLength()).formText();
    }

    // -----------------------------------------------------------------
    // 共用渲染結果（聊天／ActionBar／GUI／表單同一份輸出）
    // -----------------------------------------------------------------

    /**
     * 以目前全域 locale 渲染一次，聊天、ActionBar、GUI 與表單共用此結果。
     *
     * <p>同一個模板只讀取、替換、解析一次：{@code component}（富文字，含 prefix）、
     * {@code text}（純文字，含 prefix，與 {@link #format} 輸出一致）、
     * {@code formText}（表單安全字串，不帶 prefix，與
     * {@link #formatFormText(String, Map, Locale)} 輸出一致）。</p>
     *
     * @param key 訊息 key；不可為 null
     * @param vars 變數替換表；可為 null
     * @return 單次渲染結果；never null
     * @since 1.4.0
     */
    public RenderedMessage render(String key, Map<String, Object> vars) {
        Objects.requireNonNull(key, "key");
        return renderInternal(key, vars, null, false, 0);
    }

    /**
     * 以指定語系渲染一次（模板按該語系分層讀取：磁碟請求 locale →
     * 磁碟預設 locale → 內建請求 locale → 內建預設 locale）。
     *
     * @param key 訊息 key；不可為 null
     * @param vars 變數替換表；可為 null
     * @param locale 指定語系；可為 null（→ 全域目前語系，與 {@link #render(String, Map)}
     *     一致）
     * @return 單次渲染結果；never null
     * @since 1.4.0
     */
    public RenderedMessage render(String key, Map<String, Object> vars, Locale locale) {
        Objects.requireNonNull(key, "key");
        return renderInternal(key, vars, locale, false, 0);
    }

    /**
     * 以目前全域 locale 渲染一次，並回傳帶狀態的結果。
     *
     * <p>與 {@link #render(String, Map)} 讀取同一份模板；模板找得到時，
     * 兩者的 {@link RenderedMessage}（內容、診斷字串與記錄）完全一致，外加
     * {@link RenderStatus} 分類與缺 key／未載入時的 enriched 診斷
     * （完整 key 與可用語系）。模板找不到且語言檔損壞時，兩者語意不同：
     * 一般路徑維持 1.4.0 語意（缺 key，{@code ACELIB-MSG-001}），
     * 診斷路徑回報渲染失敗（{@code ACELIB-MSG-003}）；診斷語意只由本方法提供。</p>
     *
     * @param key 訊息 key；不可為 null
     * @param vars 變數替換表；可為 null
     * @return 帶狀態的單次渲染結果；never null
     * @since 1.5.0
     */
    public DetailedRender renderDetailed(String key, Map<String, Object> vars) {
        Objects.requireNonNull(key, "key");
        return renderDetailedPipeline(key, vars, null, false, 0, true);
    }

    /**
     * 以指定語系渲染一次，並回傳帶狀態的結果。
     *
     * <p>模板按該語系分層讀取（磁碟請求 locale → 磁碟預設 locale →
     * 內建請求 locale → 內建預設 locale），與
     * {@link #render(String, Map, Locale)} 同規則。「語系未載入」指整條查找鏈
     * （請求語系與預設語系的磁碟檔與內建資源）皆無內容；任一層有內容但 key
     * 不存在時為缺 key。</p>
     *
     * @param key 訊息 key；不可為 null
     * @param vars 變數替換表；可為 null
     * @param locale 指定語系；可為 null（→ 全域目前語系）
     * @return 帶狀態的單次渲染結果；never null
     * @since 1.5.0
     */
    public DetailedRender renderDetailed(String key, Map<String, Object> vars, Locale locale) {
        Objects.requireNonNull(key, "key");
        return renderDetailedPipeline(key, vars, locale, false, 0, true);
    }

    /**
     * 單一渲染管線：模板只讀一次，三種呈現皆由該次結果衍生。
     *
     * @param clickHints 表單視圖是否附加 click 可讀提示
     * @param maxLength 表單視圖可見字元上限（0 不截斷）
     */
    private RenderedMessage renderInternal(String key, Map<String, Object> vars, Locale locale,
                                           boolean clickHints, int maxLength) {
        return renderDetailedPipeline(key, vars, locale, clickHints, maxLength, false).rendered();
    }

    /**
     * 帶狀態的單一渲染管線：模板找得到時，本體邏輯與 {@link #renderInternal} 共用，
     * 兩者的 {@link RenderedMessage} 內容、診斷字串與記錄完全一致，
     * 外加 {@link RenderStatus} 分類與 enriched 診斷。
     *
     * <p>模板找不到時依 {@code diagnostic} 分流：一般路徑（false）維持 1.4.0
     * 輕量語意（缺 key，不做任何磁碟驗證）；診斷路徑（true）先驗證載入失敗，
     * 再區分缺 key／語系未載入並附可用語系。</p>
     *
     * @param diagnostic 是否為診斷路徑；一般渲染傳 false，renderDetailed 傳 true
     */
    private DetailedRender renderDetailedPipeline(String key, Map<String, Object> vars,
                                                  Locale locale,
                                                  boolean clickHints, int maxLength,
                                                  boolean diagnostic) {
        // mock 情境下 getCurrentLocale()/getDefaultLocale() 可能回傳 null；
        // 真實 LangManager 建構時即設預設語系，不受影響。此處逐層退回，永不 NPE。
        Locale effective = locale != null ? locale : lang.getCurrentLocale();
        if (effective == null) {
            effective = lang.getDefaultLocale();
        }
        if (effective == null) {
            effective = Locale.ROOT;
        }
        Locale formLocale = locale != null ? locale : lang.getDefaultLocale();
        if (formLocale == null) {
            formLocale = Locale.ROOT;
        }
        Optional<String> opt;
        try {
            opt = locale == null ? lang.get(key, null) : lang.get(locale, key);
        } catch (Throwable t) {
            safeLog(Level.WARNING,
                "[" + ERR_FORMAT_ERROR + "] lang.get threw for key=" + key + ": " + t.getMessage(),
                t);
            RenderedMessage failed = new RenderedMessage(key, effective, Component.empty(), "", "",
                false, "[" + ERR_FORMAT_ERROR + "] lang.get threw for key=" + key);
            return new DetailedRender(failed, RenderStatus.RENDER_FAILED, List.of(),
                failed.diagnosis());
        }
        if (opt == null || opt.isEmpty()) {
            RenderedMessage missing = new RenderedMessage(key, effective, Component.empty(), "", "",
                true, "[" + ERR_KEY_MISSING + "] message key missing: " + key
                    + " (locale=" + effective + ")");
            if (!diagnostic) {
                // 一般渲染路徑：維持 1.4.0 輕量語意。缺 key 直接回報，
                // 不做驗證性讀取、不列目錄、不探測內建資源，避免在呼叫端
                // 執行緒（例如 Folia region 執行緒）上觸發磁碟 I/O。
                // 語言檔損壞的診斷語意只由 renderDetailed 提供。
                safeLog(Level.WARNING,
                    "[" + ERR_KEY_MISSING + "] message key missing: {0}", key);
                return new DetailedRender(missing, RenderStatus.KEY_MISSING, List.of(),
                    missing.diagnosis());
            }
            Locale requested = locale != null ? locale : lang.getCurrentLocale();
            Locale def = lang.getDefaultLocale();
            // LangManager 的 per-locale 讀取會把「檔案損壞」吞成空（負向快取），
            // 檔案存在檢查分不出「載入失敗」與「真的沒內容」。
            // 此處以驗證性讀取拿實際載入結果：任一層載入失敗即為渲染失敗，
            // 只有各層都「缺席或載入成功但無該 key」才繼續區分缺 key／未載入。
            Optional<LoadFailure> failure = firstLoadFailure(requested, def);
            if (failure.isPresent()) {
                LoadFailure loadFailure = failure.get();
                safeLog(Level.WARNING,
                    "[" + ERR_FORMAT_ERROR + "] language file load failed for key=" + key
                        + ": " + loadFailure.detail(), loadFailure.cause());
                RenderedMessage failed = new RenderedMessage(key, effective, Component.empty(),
                    "", "", false, "[" + ERR_FORMAT_ERROR + "] language file load failed for key="
                        + key + ": " + loadFailure.detail());
                return new DetailedRender(failed, RenderStatus.RENDER_FAILED, List.of(),
                    failed.diagnosis());
            }
            List<Locale> available = availableLocales();
            String availableText = formatLocales(available);
            if (chainHasContent(requested, def)) {
                safeLog(Level.WARNING,
                    "[" + ERR_KEY_MISSING + "] message key missing: {0}", key);
                return new DetailedRender(missing, RenderStatus.KEY_MISSING, available,
                    missing.diagnosis() + "; availableLocales=" + availableText);
            }
            safeLog(Level.WARNING,
                "[" + ERR_KEY_MISSING + "] locale not loaded for key=" + key
                    + " (requested=" + requested
                    + ", default=" + lang.getDefaultLocale() + ")");
            return new DetailedRender(missing, RenderStatus.LOCALE_NOT_LOADED, available,
                "[" + ERR_KEY_MISSING + "] locale not loaded for key=" + key
                    + " (requested=" + requested
                    + ", default=" + lang.getDefaultLocale() + ")"
                    + "; availableLocales=" + availableText);
        }
        String template = opt.get();
        if (template == null) {
            safeLog(Level.WARNING,
                "[" + ERR_FORMAT_ERROR + "] lang.get returned Optional with null body for key="
                    + key);
            RenderedMessage failed = new RenderedMessage(key, effective, Component.empty(), "", "",
                false, "[" + ERR_FORMAT_ERROR + "] null body for key=" + key);
            return new DetailedRender(failed, RenderStatus.RENDER_FAILED, List.of(),
                failed.diagnosis());
        }
        // 純文字視圖：與富文字視圖共用同一份安全替換結果（使用者值已跳脫），
        // 保證兩者輸出一致；模板本身的 MiniMessage 標記保留不解析。
        String substituted = safeSubstitute(template, vars);
        String text = prefixOf(effective) + substituted;
        // 富文字視圖：沿用同一份替換結果再解析（防 MiniMessage 注入），與 formatComponent 同規則。
        Component parsed = deserializeOrNull(substituted, null, ERR_FORMAT_ERROR,
            "render parse failed for key=" + key);
        String diagnosis = "";
        Component content;
        if (parsed == null) {
            diagnosis = "[" + ERR_FORMAT_ERROR + "] MiniMessage parse failed for key=" + key
                + "; using plain-text fallback";
            content = Component.text(substituted);
        } else {
            content = parsed;
        }
        Component component = applyPrefixIfNeeded(content, effective);
        String formText = FormText.renderInternal(content, formLocale,
            clickHints, maxLength, this::buildBedrockHint);
        RenderedMessage rendered =
            new RenderedMessage(key, effective, component, text, formText, false, diagnosis);
        if (parsed == null) {
            return new DetailedRender(rendered, RenderStatus.RENDER_FAILED, List.of(), diagnosis);
        }
        return new DetailedRender(rendered, RenderStatus.OK, List.of(), "");
    }

    /**
     * 整條查找鏈是否有任何內容（任一磁碟檔存在，或任一內建資源存在）。
     *
     * <p>查找鏈指請求語系與預設語系的磁碟檔與內建資源（與
     * {@code LangManager.get(Locale, String)} 的四層順序對應）。
     * 任一層有內容代表語系已載入，此時查無 key 為缺 key；四層皆無才算語系未載入。</p>
     */
    private boolean chainHasContent(Locale requested, Locale def) {
        if (requested != null
            && (diskLangFileExists(requested) || builtinLangResourceExists(requested))) {
            return true;
        }
        return def != null && (diskLangFileExists(def) || builtinLangResourceExists(def));
    }

    /**
     * 磁碟語言檔是否存在（不讀內容，只判斷檔案）。
     */
    private boolean diskLangFileExists(Locale locale) {
        try {
            File dir = new File(plugin.getDataFolder(), LangManager.LANG_DIR);
            return new File(dir, fileNameFor(locale)).isFile();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 內建語言資源是否存在（不讀內容，只判斷資源；串流立即關閉）。
     */
    private boolean builtinLangResourceExists(Locale locale) {
        try (InputStream in =
                 plugin.getResource(LangManager.LANG_DIR + "/" + fileNameFor(locale))) {
            return in != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 載入失敗的描述（哪一層壞、原因是什麼）。
     */
    private record LoadFailure(String detail, Throwable cause) {
    }

    /**
     * 查找鏈第一個載入失敗的層（磁碟檔或內建資源無法解析）。
     *
     * <p>手段選擇理由：{@code LangManager} 的 per-locale 讀取把解析例外吞成
     * {@code Optional.empty()}（config 模組的既有設計，不可改），且其內部快取
     * 無法從外部觀測；「檔案存在」分不出損壞與缺內容。訊息模組只能以自己的一次
     * 驗證性讀取（與 {@code LangManager} 相同的 {@code YamlConfiguration}
     * 解析呼叫，只讀不寫）拿實際載入結果。只在「查無 key」的失敗路徑執行，
     * 成功渲染不受影響。</p>
     *
     * @param requested 請求語系；可為 null（→ 跳過該層）
     * @param def 預設語系；可為 null（→ 跳過該層）
     * @return 第一個載入失敗；各層皆缺席或載入成功時為空
     */
    private Optional<LoadFailure> firstLoadFailure(Locale requested, Locale def) {
        Set<Locale> chain = new LinkedHashSet<>();
        if (requested != null) {
            chain.add(requested);
        }
        if (def != null) {
            chain.add(def);
        }
        for (Locale locale : chain) {
            Optional<LoadFailure> disk = checkDiskLoad(locale);
            if (disk.isPresent()) {
                return disk;
            }
        }
        for (Locale locale : chain) {
            Optional<LoadFailure> builtin = checkBuiltinLoad(locale);
            if (builtin.isPresent()) {
                return builtin;
            }
        }
        return Optional.empty();
    }

    /**
     * 驗證單一磁碟語言檔能否解析（與 {@code LangManager} 同解析呼叫）。
     *
     * <p>檔案不存在（含中途被刪的競態）視為缺席，不算失敗，避免誤判。</p>
     */
    private Optional<LoadFailure> checkDiskLoad(Locale locale) {
        File file;
        try {
            File dir = new File(plugin.getDataFolder(), LangManager.LANG_DIR);
            file = new File(dir, fileNameFor(locale));
        } catch (Throwable t) {
            return Optional.empty();
        }
        if (!file.isFile()) {
            return Optional.empty();
        }
        try {
            YamlConfiguration cfg = new YamlConfiguration();
            cfg.load(file);
            return Optional.empty();
        } catch (java.io.FileNotFoundException ex) {
            return Optional.empty();
        } catch (java.io.IOException | InvalidConfigurationException ex) {
            return Optional.of(new LoadFailure("disk file " + file.getAbsolutePath()
                + " cannot be parsed（" + ex.getMessage() + "）", ex));
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    /**
     * 驗證單一內建語言資源能否解析（與 {@code LangManager} 同解析呼叫）。
     *
     * <p>資源不存在（{@code getResource} 回 null，拋錯亦視為查不到）時視為缺席，
     * 語意與 {@code LangManager} 及 {@link #builtinLangResourceExists} 一致；
     * 取得串流後讀取或關閉失敗時為載入失敗。</p>
     */
    private Optional<LoadFailure> checkBuiltinLoad(Locale locale) {
        String path = LangManager.LANG_DIR + "/" + fileNameFor(locale);
        InputStream in;
        try {
            in = plugin.getResource(path);
        } catch (Throwable t) {
            return Optional.empty();
        }
        if (in == null) {
            return Optional.empty();
        }
        byte[] bytes;
        try (InputStream autoClose = in) {
            bytes = autoClose.readAllBytes();
        } catch (Throwable t) {
            return Optional.of(new LoadFailure("builtin resource " + path
                + " cannot be read（" + t.getMessage() + "）", t));
        }
        try {
            YamlConfiguration cfg = new YamlConfiguration();
            cfg.loadFromString(new String(bytes, StandardCharsets.UTF_8));
            return Optional.empty();
        } catch (Throwable t) {
            return Optional.of(new LoadFailure("builtin resource " + path
                + " cannot be parsed（" + t.getMessage() + "）", t));
        }
    }

    /**
     * 磁碟 {@code lang/} 目錄下實際存在的語系（依檔名排序）。
     *
     * <p>內建資源（plugin JAR 內）無法枚舉，不列入；呼叫端若只有內建資源而無磁碟檔，
     * 此清單為空，但渲染仍可能成功（內建資源補缺口）。</p>
     */
    private List<Locale> availableLocales() {
        List<Locale> out = new ArrayList<>();
        try {
            File dir = new File(plugin.getDataFolder(), LangManager.LANG_DIR);
            File[] files = dir.listFiles((parent, name) -> name.endsWith(".yml"));
            if (files == null) {
                return List.of();
            }
            for (File file : files) {
                Locale parsed = parseLangFileName(file.getName());
                if (parsed != null) {
                    out.add(parsed);
                }
            }
        } catch (Throwable t) {
            return List.of();
        }
        out.sort(Comparator.comparing(Locale::toString));
        return List.copyOf(out);
    }

    /**
     * 與 {@code LangManager} 相同的 locale→檔名規則（該方法為 package-private，
     * 此處為跨套件重述；規則變更時需同步）。
     */
    private static String fileNameFor(Locale locale) {
        String language = locale.getLanguage();
        String country = locale.getCountry();
        if (country == null || country.isEmpty()) {
            return language + ".yml";
        }
        return language + "_" + country + ".yml";
    }

    /**
     * 語言檔檔名→locale；格式不符回傳 null（略過該檔）。
     */
    private static Locale parseLangFileName(String name) {
        if (name == null || !name.endsWith(".yml")) {
            return null;
        }
        String base = name.substring(0, name.length() - ".yml".length());
        if (base.isEmpty()) {
            return null;
        }
        int idx = base.indexOf('_');
        if (idx < 0) {
            return base.matches("[a-zA-Z]{2,8}") ? new Locale(base) : null;
        }
        String language = base.substring(0, idx);
        String country = base.substring(idx + 1);
        if (!language.matches("[a-zA-Z]{2,8}") || !country.matches("[a-zA-Z]{2,8}")) {
            return null;
        }
        return new Locale(language, country);
    }

    /**
     * 可用語系清單→診斷字串（locale 字串形式，例如 {@code [en_US, zh_TW]}）。
     */
    private static String formatLocales(List<Locale> locales) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < locales.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(locales.get(i).toString());
        }
        return sb.append(']').toString();
    }

    /**
     * 指定語系的 prefix 字串（無 prefix、查詢失敗或 mock 回傳 null 時為空字串）。
     *
     * <p>按渲染語系分層讀取（與本體模板同規則），不固定讀全域目前語系。</p>
     */
    private String prefixOf(Locale locale) {
        Optional<String> prefix;
        try {
            prefix = lang.get(locale, PREFIX_KEY);
        } catch (Throwable t) {
            return "";
        }
        if (prefix == null || prefix.isEmpty() || prefix.get().isEmpty()) {
            return "";
        }
        return prefix.get();
    }

    /**
     * 對單一玩家發送已渲染訊息（chat，Component 視圖）。
     *
     * <p>直接送出該次渲染的 Component，不重讀語言檔；缺 key 的渲染結果
     * （{@link RenderedMessage#missing()}）不發送（診斷已在渲染時記錄）。</p>
     *
     * @param player 目標玩家；可為 null（→ silent no-op）
     * @param rendered 已渲染訊息；可為 null（→ silent no-op）
     * @since 1.4.0
     */
    public void sendChat(Player player, RenderedMessage rendered) {
        if (!isServiceActive()) {
            return;
        }
        if (player == null) {
            warnSilently("sendChat(Player, RenderedMessage) called with null player");
            return;
        }
        if (rendered == null) {
            warnSilently("sendChat(Player, RenderedMessage) called with null rendered message");
            return;
        }
        if (!player.isOnline()) {
            warnSilently("sendChat to offline player=" + safeName(player));
            return;
        }
        if (rendered.missing()) {
            return;
        }
        Component message = rendered.component();
        synchronized (plugin) {
            if (!isServiceActive()) {
                return;
            }
            try {
                player.sendMessage(message);
            } catch (IllegalStateException ex) {
                logPlayerOperationFailure(player, "sendChat", ex);
            } catch (Throwable t) {
                safeLog(Level.WARNING,
                    "[" + ERR_FORMAT_ERROR
                        + "] sendChat(Player, RenderedMessage) failed for player="
                        + safeName(player) + ": " + t.getMessage(), t);
            }
        }
    }

    /**
     * 對單一玩家發送已渲染訊息（action bar，Component 視圖）。
     *
     * @param player 目標玩家；可為 null（→ silent no-op）
     * @param rendered 已渲染訊息；可為 null（→ silent no-op）
     * @since 1.4.0
     */
    public void sendActionBar(Player player, RenderedMessage rendered) {
        if (!isServiceActive()) {
            return;
        }
        if (player == null) {
            warnSilently("sendActionBar(Player, RenderedMessage) called with null player");
            return;
        }
        if (rendered == null) {
            warnSilently(
                "sendActionBar(Player, RenderedMessage) called with null rendered message");
            return;
        }
        if (!player.isOnline()) {
            warnSilently("sendActionBar to offline player=" + safeName(player));
            return;
        }
        if (rendered.missing()) {
            return;
        }
        Component message = rendered.component();
        synchronized (plugin) {
            if (!isServiceActive()) {
                return;
            }
            try {
                player.sendActionBar(message);
            } catch (IllegalStateException ex) {
                logPlayerOperationFailure(player, "sendActionBar", ex);
            } catch (Throwable t) {
                safeLog(Level.WARNING,
                    "[" + ERR_FORMAT_ERROR
                        + "] sendActionBar(Player, RenderedMessage) failed for player="
                        + safeName(player) + ": " + t.getMessage(), t);
            }
        }
    }

    /**
     * 對單一玩家發送已渲染 title／subtitle（Component 視圖）。
     *
     * @param player 目標玩家；可為 null（→ silent no-op）
     * @param title 已渲染 title；可為 null（→ silent no-op）
     * @param subtitle 已渲染 subtitle；可為 null（→ 視為空）
     * @since 1.4.0
     */
    public void sendTitle(Player player, RenderedMessage title, RenderedMessage subtitle) {
        if (!isServiceActive()) {
            return;
        }
        if (player == null) {
            warnSilently("sendTitle(Player, RenderedMessage, RenderedMessage) called "
                + "with null player");
            return;
        }
        if (title == null) {
            warnSilently("sendTitle(Player, RenderedMessage, RenderedMessage) called "
                + "with null title");
            return;
        }
        if (!player.isOnline()) {
            warnSilently("sendTitle to offline player=" + safeName(player));
            return;
        }
        if (title.missing()) {
            return;
        }
        Component titleComponent = title.component();
        Component subtitleComponent =
            subtitle == null || subtitle.missing() ? Component.empty() : subtitle.component();
        synchronized (plugin) {
            if (!isServiceActive()) {
                return;
            }
            try {
                Title adventureTitle = Title.title(
                    titleComponent, subtitleComponent, 10, 70, 20);
                player.showTitle(adventureTitle);
            } catch (IllegalStateException ex) {
                logPlayerOperationFailure(player, "sendTitle", ex);
            } catch (Throwable t) {
                safeLog(Level.WARNING,
                    "[" + ERR_FORMAT_ERROR
                        + "] sendTitle(Player, RenderedMessage, RenderedMessage) failed "
                        + "for player=" + safeName(player) + ": " + t.getMessage(), t);
            }
        }
    }

    /**
     * 對所有線上玩家廣播已渲染訊息（Component 視圖，不重讀語言檔）。
     *
     * @param rendered 已渲染訊息；可為 null（→ silent no-op）
     * @since 1.4.0
     */
    public void broadcast(RenderedMessage rendered) {
        if (!isServiceActive()) {
            return;
        }
        if (rendered == null) {
            warnSilently("broadcast(RenderedMessage) called with null rendered message");
            return;
        }
        if (rendered.missing()) {
            return;
        }
        Component message = rendered.component();
        synchronized (plugin) {
            if (!isServiceActive()) {
                return;
            }
            Server srv = safeServer();
            if (srv == null) {
                warnSilently("broadcast(RenderedMessage) called but server is unavailable");
                return;
            }
            for (Player p : srv.getOnlinePlayers()) {
                if (p == null || !p.isOnline()) {
                    continue;
                }
                try {
                    p.sendMessage(message);
                } catch (IllegalStateException ex) {
                    logPlayerOperationFailure(p, "broadcast", ex);
                } catch (Throwable t) {
                    safeLog(Level.WARNING,
                        "[" + ERR_FORMAT_ERROR + "] broadcast(RenderedMessage) failed for player="
                            + safeName(p) + ": " + t.getMessage(), t);
                }
            }
        }
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
        if (!isServiceActive()) {
            return;
        }
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
        synchronized (plugin) {
            if (!isServiceActive()) {
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
    }

    /**
     * 對單一玩家發送 action bar（無 prefix；action bar 是短暫提示）。
     *
     * @param player 目標玩家；可為 null（→ silent no-op）
     * @param key    訊息 key；不可為 null
     * @param vars   變數替換表；可為 null
     */
    public void sendActionBar(Player player, String key, Map<String, Object> vars) {
        if (!isServiceActive()) {
            return;
        }
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
        synchronized (plugin) {
            if (!isServiceActive()) {
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
        if (!isServiceActive()) {
            return;
        }
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
        synchronized (plugin) {
            if (!isServiceActive()) {
                return;
            }
            try {
                // Paper / Bukkit: title(title, subtitle, fadeIn, stay, fadeOut)
                player.sendTitle(title, subtitle, 10, 70, 20);
            } catch (IllegalStateException ex) {
                // Folia 在 non-owned region 操作玩家時會丟 IllegalStateException；
                // 其他平台同樣的例外型別不應誤標為 Folia 區域不安全，應走訊息層
                // 降級路徑（見 logPlayerOperationFailure）。
                logPlayerOperationFailure(player, "sendTitle", ex);
            } catch (Throwable t) {
                safeLog(Level.WARNING,
                    "[" + ERR_FORMAT_ERROR + "] sendTitle failed for player=" + safeName(player)
                        + " key=" + key + ": " + t.getMessage(),
                    t);
            }
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
        if (!isServiceActive()) {
            return;
        }
        String body = format(key, vars);
        if (body.isEmpty()) {
            return;
        }
        synchronized (plugin) {
            if (!isServiceActive()) {
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

    // -----------------------------------------------------------------
    // Adventure Component 管線（additive；不影響既有 String API）
    // -----------------------------------------------------------------

    /**
     * 以目前全域 locale 讀取 rich template（MiniMessage 字串）並轉為
     * {@link Component}；適用於玩家導向訊息，會自動套用
     * {@link #PREFIX_KEY message.prefix}（與 {@link #format(String, Map)} 的 prefix
     * 規則一致）。
     *
     * <p>rich template 中的 {@code {var}} 由本方法以「安全替換」處理：使用者變數值會先經
     * {@link MiniMessage#escapeTags(String)} 跳脫，避免值中的 {@code <tag>} 被當成
     * MiniMessage 標籤注入互動；template 本身的 MiniMessage 標籤（如 {@code <click>}、
     * {@code <hover>}、顏色）則保留為 Component 結構，不會被攤平成純文字。</p>
     *
     * <p>錯誤處理：</p>
     * <ul>
     *   <li>key 為 null → 拋 {@link NullPointerException}（契約，與 {@link #format} 一致）</li>
     *   <li>key 缺失 → 回傳 {@link Component#empty()} + 記錄 {@link #ERR_KEY_MISSING}</li>
     *   <li>vars 為 null → 視為空 map</li>
     *   <li>MiniMessage 解析失敗 → 回傳原始（已替換）字串的純文字 Component
     *       + 記錄 {@link #ERR_FORMAT_ERROR}</li>
     * </ul>
     *
     * @param key  訊息 key；不可為 null
     * @param vars 變數替換表；可為 null
     * @return 渲染後的 Component；key 缺失或解析失敗時為 {@link Component#empty()} 或純文字 Component
     */
    public Component formatComponent(String key, Map<String, Object> vars) {
        Objects.requireNonNull(key, "key");
        return renderInternal(key, vars, null, false, 0).component();
    }

    /**
     * 解析明確的 MiniMessage 字串為 {@link Component}（便利入口）。
     *
     * <p>與 {@link #formatComponent(String, Map)} 不同，本方法不讀取語言檔、不套 prefix，
     * 也不做 {@code {var}} 替換；呼叫方直接提供 MiniMessage 字串。</p>
     *
     * <p>變數以 {@code <key>} placeholder 形式注入，且一律使用
     * {@link Placeholder#unparsed(String, String)}（unparsed）機制：使用者值中的
     * {@code <tag>} 會被視為純文字，不會被解析成 MiniMessage 標籤或 click/hover 互動。
     * 這避免了「先把未信任值做 raw string interpolation 再 parse」的注入風險。</p>
     *
     * <p>錯誤處理：</p>
     * <ul>
     *   <li>input 為 null → 回傳 {@link Component#empty()} + 記錄 {@link #ERR_FORMAT_ERROR}</li>
     *   <li>vars 為 null/空 → 直接解析 input（無 placeholder）</li>
     *   <li>未知 tag / 解析失敗 → 回傳原始 input 的純文字 Component
     *       + 記錄 {@link #ERR_FORMAT_ERROR}</li>
     * </ul>
     *
     * <p>本方法不依賴 {@code BedrockService}，也不執行任何 Bedrock fallback；
     * Bedrock 玩家的 click 互動在 beta 環境無效果（見
     * {@code docs/reference/bedrock-message-compatibility-matrix.md}），本方法只負責
     * 送出原始 Component。</p>
     *
     * @param input 明確的 MiniMessage 字串；可為 null
     * @param vars   placeholder 變數表（key 對應 {@code <key>}）；可為 null
     * @return 解析後的 Component；失敗時為 {@link Component#empty()} 或純文字 Component
     */
    public Component parseMiniMessage(String input, Map<String, Object> vars) {
        if (input == null) {
            safeLog(Level.WARNING,
                "[" + ERR_FORMAT_ERROR + "] parseMiniMessage called with null input");
            return Component.empty();
        }
        TagResolver resolver = buildUnparsedResolver(vars);
        Component parsed = deserializeOrNull(input, resolver, ERR_FORMAT_ERROR,
            "parseMiniMessage failed");
        return parsed == null ? Component.text(input) : parsed;
    }

    /**
     * 對單一玩家發送原始 {@link Component}（chat）。
     *
     * <p>直接送出原始 Component，<strong>不</strong>套用 {@code message.prefix}，
     * 也<strong>不</strong>執行任何 Bedrock fallback；prefix／key 模板責任請使用
     * {@link #formatComponent(String, Map)}。Component 的 hover/click/style 結構原樣送出。</p>
     *
     * @param player  目標玩家；可為 null（→ silent no-op）
     * @param message 原始 Component；可為 null（→ silent no-op）
     */
    public void sendChat(Player player, Component message) {
        if (!isServiceActive()) {
            return;
        }
        if (player == null) {
            warnSilently("sendChat(Player, Component) called with null player");
            return;
        }
        if (message == null) {
            warnSilently("sendChat(Player, Component) called with null message");
            return;
        }
        if (!player.isOnline()) {
            warnSilently("sendChat to offline player=" + safeName(player));
            return;
        }
        synchronized (plugin) {
            if (!isServiceActive()) {
                return;
            }
            try {
                player.sendMessage(message);
            } catch (IllegalStateException ex) {
                logPlayerOperationFailure(player, "sendChat", ex);
            } catch (Throwable t) {
                safeLog(Level.WARNING,
                    "[" + ERR_FORMAT_ERROR + "] sendChat(Player, Component) failed for player="
                        + safeName(player) + ": " + t.getMessage(), t);
            }
        }
    }

    /**
     * 對單一玩家發送原始 {@link Component}（action bar）。
     *
     * <p>直接送出原始 Component，不套 prefix、不執行 Bedrock fallback；
     * Component 的 hover/click/style 結構原樣送出。</p>
     *
     * @param player  目標玩家；可為 null（→ silent no-op）
     * @param message 原始 Component；可為 null（→ silent no-op）
     */
    public void sendActionBar(Player player, Component message) {
        if (!isServiceActive()) {
            return;
        }
        if (player == null) {
            warnSilently("sendActionBar(Player, Component) called with null player");
            return;
        }
        if (message == null) {
            warnSilently("sendActionBar(Player, Component) called with null message");
            return;
        }
        if (!player.isOnline()) {
            warnSilently("sendActionBar to offline player=" + safeName(player));
            return;
        }
        synchronized (plugin) {
            if (!isServiceActive()) {
                return;
            }
            try {
                player.sendActionBar(message);
            } catch (IllegalStateException ex) {
                logPlayerOperationFailure(player, "sendActionBar", ex);
            } catch (Throwable t) {
                safeLog(Level.WARNING,
                    "[" + ERR_FORMAT_ERROR + "] sendActionBar(Player, Component) failed for player="
                        + safeName(player) + ": " + t.getMessage(), t);
            }
        }
    }

    /**
     * 對單一玩家發送 title + subtitle（原始 {@link Component}）。
     *
     * <p>直接送出原始 Component，不套 prefix、不執行 Bedrock fallback。
     * 使用預設的 {@code 10 / 70 / 20} ticks（與 {@link #sendTitle(Player, String, Map)}
     * 一致）。</p>
     *
     * @param player    目標玩家；可為 null（→ silent no-op）
     * @param title     原始 title Component；可為 null（→ silent no-op）
     * @param subtitle  原始 subtitle Component；可為 null（→ 不發送 subtitle）
     */
    public void sendTitle(Player player, Component title, Component subtitle) {
        if (!isServiceActive()) {
            return;
        }
        if (player == null) {
            warnSilently("sendTitle(Player, Component, Component) called with null player");
            return;
        }
        if (title == null) {
            warnSilently("sendTitle(Player, Component, Component) called with null title");
            return;
        }
        if (!player.isOnline()) {
            warnSilently("sendTitle to offline player=" + safeName(player));
            return;
        }
        synchronized (plugin) {
            if (!isServiceActive()) {
                return;
            }
            try {
                Title adventureTitle = Title.title(
                    title,
                    subtitle == null ? Component.empty() : subtitle,
                    10, 70, 20);
                player.showTitle(adventureTitle);
            } catch (IllegalStateException ex) {
                logPlayerOperationFailure(player, "sendTitle", ex);
            } catch (Throwable t) {
                safeLog(Level.WARNING,
                    "[" + ERR_FORMAT_ERROR + "] sendTitle(Player, Component, Component) failed for player="
                        + safeName(player) + ": " + t.getMessage(), t);
            }
        }
    }

    /**
     * 對所有線上玩家廣播原始 {@link Component}。
     *
     * <p>直接送出原始 Component，不套 prefix、不執行 Bedrock fallback。
     * 透過 {@link Server#getOnlinePlayers()} 逐一 {@code sendMessage(Component)} 廣播，
     * 與 {@link #broadcast(String, Map)} 的 server 級廣播語意一致；此處不使用
     * {@link Server} 的 {@code sendMessage(Component)}（ForwardingAudience）是因為
     * 部分測試/舊環境未實作 {@code audiences()}，逐一發送可同時保證生產與測試行為一致。</p>
     *
     * @param message 原始 Component；可為 null（→ silent no-op）
     */
    public void broadcast(Component message) {
        if (!isServiceActive()) {
            return;
        }
        if (message == null) {
            warnSilently("broadcast(Component) called with null message");
            return;
        }
        synchronized (plugin) {
            if (!isServiceActive()) {
                return;
            }
            Server srv = safeServer();
            if (srv == null) {
                warnSilently("broadcast(Component) called but server is unavailable");
                return;
            }
            for (Player p : srv.getOnlinePlayers()) {
                if (p == null || !p.isOnline()) {
                    continue;
                }
                try {
                    p.sendMessage(message);
                } catch (IllegalStateException ex) {
                    // Folia unsafe context：個別玩家 region 例外，記 MSG-002 並繼續其他玩家
                    logPlayerOperationFailure(p, "broadcast", ex);
                } catch (Throwable t) {
                    safeLog(Level.WARNING,
                        "[" + ERR_FORMAT_ERROR + "] broadcast(Component) failed for player="
                            + safeName(p) + ": " + t.getMessage(), t);
                }
            }
        }
    }

    // -----------------------------------------------------------------
    // Bedrock fallback（明確 API；不影響既有 Component 管線）
    // -----------------------------------------------------------------

    /**
     * 對單一玩家發送原始 {@link Component}（chat），並對明確的基岩版玩家執行
     * click 互動降級。
     *
     * <p>只有 {@code BedrockService.isBedrockPlayer(UUID)} 明確回 true 時，才將
     * Component 中的 {@code ClickEvent} 替換為可見且可讀的 action 提示（由
     * {@link LangManager} 依 locale 提供）；Java 玩家、Floodgate 缺席或無法判定
     * 時，直接送出原始 Component，不改變既有行為。</p>
     *
     * <p>本方法委派給
     * {@link #sendChatWithFallbackResult(Player, Component, Locale)} 並使用
     * {@link BedrockFallbackStyle#HINTS}，行為與 AceLib 1.4.0 逐字一致；
     * 要拿發送結果時改用 {@code *WithFallbackResult} 入口。</p>
     *
     * @param player         目標玩家；可為 null（→ silent no-op）
     * @param message        原始 Component；可為 null（→ silent no-op）
     * @param localeOverride 每次呼叫的 locale 覆寫；可為 null（→ 依 Player.locale /
     *                      Floodgate languageCode / default 解析）
     */
    public void sendChatWithFallback(Player player, Component message, Locale localeOverride) {
        sendChatWithFallbackCore(player, message, localeOverride, BedrockFallbackStyle.HINTS);
    }

    /**
     * 對單一玩家發送原始 {@link Component}（chat），並回傳發送結果。
     *
     * <p>降級風格為 {@link BedrockFallbackStyle#HINTS}（預設）；要攤平成純文字時用
     * {@link #sendChatWithFallbackResult(Player, Component, Locale, BedrockFallbackStyle)}。</p>
     *
     * @param player         目標玩家；可為 null（→ silent no-op，回傳未送達）
     * @param message        原始 Component；可為 null（→ silent no-op，回傳未送達）
     * @param localeOverride 每次呼叫的 locale 覆寫；可為 null
     * @return 發送結果（含是否確實套用基岩降級）；never null
     * @since 1.5.0
     */
    public SendResult sendChatWithFallbackResult(Player player, Component message,
                                                 Locale localeOverride) {
        return sendChatWithFallbackCore(player, message, localeOverride,
            BedrockFallbackStyle.HINTS);
    }

    /**
     * 對單一玩家發送原始 {@link Component}（chat），並回傳發送結果。
     *
     * @param player         目標玩家；可為 null（→ silent no-op，回傳未送達）
     * @param message        原始 Component；可為 null（→ silent no-op，回傳未送達）
     * @param localeOverride 每次呼叫的 locale 覆寫；可為 null
     * @param style          基岩降級風格；可為 null（→ {@link BedrockFallbackStyle#HINTS}）
     * @return 發送結果（含是否確實套用基岩降級）；never null
     * @since 1.5.0
     */
    public SendResult sendChatWithFallbackResult(Player player, Component message,
                                                 Locale localeOverride,
                                                 BedrockFallbackStyle style) {
        return sendChatWithFallbackCore(player, message, localeOverride, style);
    }

    /**
     * {@link #sendChatWithFallback} 與 {@code sendChatWithFallbackResult} 共用的核心：
     * 守衛、降級與發送流程完全一致，只有回傳差別。
     */
    private SendResult sendChatWithFallbackCore(Player player, Component message,
                                                 Locale localeOverride,
                                                 BedrockFallbackStyle style) {
        if (!isServiceActive()) {
            return new SendResult(false, false);
        }
        if (player == null) {
            warnSilently("sendChatWithFallback called with null player");
            return new SendResult(false, false);
        }
        if (message == null) {
            warnSilently("sendChatWithFallback called with null message");
            return new SendResult(false, false);
        }
        if (!player.isOnline()) {
            warnSilently("sendChatWithFallback to offline player=" + safeName(player));
            return new SendResult(false, false);
        }
        FallbackOutcome outcome = applyFallbackWithOutcome(player, message, localeOverride, style);
        synchronized (plugin) {
            if (!isServiceActive()) {
                return new SendResult(false, false);
            }
            try {
                player.sendMessage(outcome.component());
                return new SendResult(true, outcome.applied());
            } catch (IllegalStateException ex) {
                logPlayerOperationFailure(player, "sendChatWithFallback", ex);
                return new SendResult(false, outcome.applied());
            } catch (Throwable t) {
                safeLog(Level.WARNING,
                    "[" + ERR_FORMAT_ERROR + "] sendChatWithFallback failed for player="
                        + safeName(player) + ": " + t.getMessage(), t);
                return new SendResult(false, outcome.applied());
            }
        }
    }

    /**
     * 對單一玩家發送原始 {@link Component}（action bar），並對明確的基岩版玩家
     * 執行 click 互動降級。語意同 {@link #sendChatWithFallback}。
     *
     * <p>本方法委派共用核心並使用 {@link BedrockFallbackStyle#HINTS}，
     * 行為與 AceLib 1.4.0 逐字一致；要拿發送結果時改用
     * {@code sendActionBarWithFallbackResult} 入口。</p>
     *
     * @param player         目標玩家；可為 null（→ silent no-op）
     * @param message        原始 Component；可為 null（→ silent no-op）
     * @param localeOverride 每次呼叫的 locale 覆寫；可為 null
     */
    public void sendActionBarWithFallback(Player player, Component message, Locale localeOverride) {
        sendActionBarWithFallbackCore(player, message, localeOverride,
            BedrockFallbackStyle.HINTS);
    }

    /**
     * 對單一玩家發送原始 {@link Component}（action bar），並回傳發送結果。
     *
     * <p>降級風格為 {@link BedrockFallbackStyle#HINTS}（預設）；要攤平成純文字時用
     * {@link #sendActionBarWithFallbackResult(Player, Component, Locale,
     * BedrockFallbackStyle)}。</p>
     *
     * @param player         目標玩家；可為 null（→ silent no-op，回傳未送達）
     * @param message        原始 Component；可為 null（→ silent no-op，回傳未送達）
     * @param localeOverride 每次呼叫的 locale 覆寫；可為 null
     * @return 發送結果（含是否確實套用基岩降級）；never null
     * @since 1.5.0
     */
    public SendResult sendActionBarWithFallbackResult(Player player, Component message,
                                                      Locale localeOverride) {
        return sendActionBarWithFallbackCore(player, message, localeOverride,
            BedrockFallbackStyle.HINTS);
    }

    /**
     * 對單一玩家發送原始 {@link Component}（action bar），並回傳發送結果。
     *
     * @param player         目標玩家；可為 null（→ silent no-op，回傳未送達）
     * @param message        原始 Component；可為 null（→ silent no-op，回傳未送達）
     * @param localeOverride 每次呼叫的 locale 覆寫；可為 null
     * @param style          基岩降級風格；可為 null（→ {@link BedrockFallbackStyle#HINTS}）
     * @return 發送結果（含是否確實套用基岩降級）；never null
     * @since 1.5.0
     */
    public SendResult sendActionBarWithFallbackResult(Player player, Component message,
                                                      Locale localeOverride,
                                                      BedrockFallbackStyle style) {
        return sendActionBarWithFallbackCore(player, message, localeOverride, style);
    }

    /**
     * {@link #sendActionBarWithFallback} 與
     * {@code sendActionBarWithFallbackResult} 共用的核心。
     */
    private SendResult sendActionBarWithFallbackCore(Player player, Component message,
                                                      Locale localeOverride,
                                                      BedrockFallbackStyle style) {
        if (!isServiceActive()) {
            return new SendResult(false, false);
        }
        if (player == null) {
            warnSilently("sendActionBarWithFallback called with null player");
            return new SendResult(false, false);
        }
        if (message == null) {
            warnSilently("sendActionBarWithFallback called with null message");
            return new SendResult(false, false);
        }
        if (!player.isOnline()) {
            warnSilently("sendActionBarWithFallback to offline player=" + safeName(player));
            return new SendResult(false, false);
        }
        FallbackOutcome outcome = applyFallbackWithOutcome(player, message, localeOverride, style);
        synchronized (plugin) {
            if (!isServiceActive()) {
                return new SendResult(false, false);
            }
            try {
                player.sendActionBar(outcome.component());
                return new SendResult(true, outcome.applied());
            } catch (IllegalStateException ex) {
                logPlayerOperationFailure(player, "sendActionBarWithFallback", ex);
                return new SendResult(false, outcome.applied());
            } catch (Throwable t) {
                safeLog(Level.WARNING,
                    "[" + ERR_FORMAT_ERROR + "] sendActionBarWithFallback failed for player="
                        + safeName(player) + ": " + t.getMessage(), t);
                return new SendResult(false, outcome.applied());
            }
        }
    }

    /**
     * 對單一玩家顯示 title / subtitle（原始 {@link Component}），並對明確的基岩版
     * 玩家執行 click 互動降級（title 與 subtitle 分別處理）。
     *
     * @param player         目標玩家；可為 null（→ silent no-op）
     * @param title          原始 title Component；可為 null（→ silent no-op）
     * @param subtitle       原始 subtitle Component；可為 null（視為空）
     * @param localeOverride 每次呼叫的 locale 覆寫；可為 null
     */
    public void sendTitleWithFallback(Player player, Component title, Component subtitle,
                                     Locale localeOverride) {
        sendTitleWithFallbackCore(player, title, subtitle, localeOverride,
            BedrockFallbackStyle.HINTS);
    }

    /**
     * 對單一玩家顯示 title / subtitle（原始 {@link Component}），並回傳發送結果。
     *
     * <p>降級風格為 {@link BedrockFallbackStyle#HINTS}（預設）；title 與 subtitle
     * 分別降級，任一實際套用即標記。要攤平成純文字時用
     * {@link #sendTitleWithFallbackResult(Player, Component, Component, Locale,
     * BedrockFallbackStyle)}。</p>
     *
     * @param player 目標玩家；可為 null（→ silent no-op，回傳未送達）
     * @param title 原始 title Component；可為 null（→ silent no-op，回傳未送達）
     * @param subtitle 原始 subtitle Component；可為 null（視為空）
     * @param localeOverride 每次呼叫的 locale 覆寫；可為 null
     * @return 發送結果（含是否確實套用基岩降級）；never null
     * @since 1.5.0
     */
    public SendResult sendTitleWithFallbackResult(Player player, Component title,
                                                  Component subtitle,
                                                  Locale localeOverride) {
        return sendTitleWithFallbackCore(player, title, subtitle, localeOverride,
            BedrockFallbackStyle.HINTS);
    }

    /**
     * 對單一玩家顯示 title / subtitle（原始 {@link Component}），並回傳發送結果。
     *
     * @param player 目標玩家；可為 null（→ silent no-op，回傳未送達）
     * @param title 原始 title Component；可為 null（→ silent no-op，回傳未送達）
     * @param subtitle 原始 subtitle Component；可為 null（視為空）
     * @param localeOverride 每次呼叫的 locale 覆寫；可為 null
     * @param style 基岩降級風格；可為 null（→ {@link BedrockFallbackStyle#HINTS}）
     * @return 發送結果（含是否確實套用基岩降級）；never null
     * @since 1.5.0
     */
    public SendResult sendTitleWithFallbackResult(Player player, Component title,
                                                  Component subtitle,
                                                  Locale localeOverride,
                                                  BedrockFallbackStyle style) {
        return sendTitleWithFallbackCore(player, title, subtitle, localeOverride, style);
    }

    /**
     * 共用核心：守衛、降級與發送流程與舊 void 入口一致，只有回傳差別。
     */
    private SendResult sendTitleWithFallbackCore(Player player, Component title,
                                                  Component subtitle,
                                                  Locale localeOverride,
                                                  BedrockFallbackStyle style) {
        if (!isServiceActive()) {
            return new SendResult(false, false);
        }
        if (player == null) {
            warnSilently("sendTitleWithFallback called with null player");
            return new SendResult(false, false);
        }
        if (title == null) {
            warnSilently("sendTitleWithFallback called with null title");
            return new SendResult(false, false);
        }
        if (!player.isOnline()) {
            warnSilently("sendTitleWithFallback to offline player=" + safeName(player));
            return new SendResult(false, false);
        }
        FallbackOutcome titleOut = applyFallbackWithOutcome(player, title, localeOverride, style);
        FallbackOutcome subtitleOut = subtitle == null
            ? null
            : applyFallbackWithOutcome(player, subtitle, localeOverride, style);
        synchronized (plugin) {
            if (!isServiceActive()) {
                return new SendResult(false, false);
            }
            try {
                Title adventureTitle = Title.title(
                    titleOut.component(),
                    subtitleOut == null ? Component.empty() : subtitleOut.component(),
                    10, 70, 20);
                player.showTitle(adventureTitle);
                boolean applied = titleOut.applied()
                    || (subtitleOut != null && subtitleOut.applied());
                return new SendResult(true, applied);
            } catch (IllegalStateException ex) {
                logPlayerOperationFailure(player, "sendTitleWithFallback", ex);
                return new SendResult(false, titleOut.applied());
            } catch (Throwable t) {
                safeLog(Level.WARNING,
                    "[" + ERR_FORMAT_ERROR + "] sendTitleWithFallback failed for player="
                        + safeName(player) + ": " + t.getMessage(), t);
                return new SendResult(false, titleOut.applied());
            }
        }
    }

    /**
     * 對所有線上玩家廣播原始 {@link Component}，並對每位明確的基岩版玩家執行
     * click 互動降級（依各玩家 locale 解析）。單一玩家失敗不影響其他玩家。
     *
     * @param message        原始 Component；可為 null（→ silent no-op）
     * @param localeOverride 每次呼叫的 locale 覆寫；可為 null（→ 依各玩家解析）
     */
    public void broadcastWithFallback(Component message, Locale localeOverride) {
        broadcastWithFallbackCore(message, localeOverride, BedrockFallbackStyle.HINTS);
    }

    /**
     * 對所有線上玩家廣播原始 {@link Component}，並回傳發送結果。
     *
     * <p>每位明確的基岩版玩家各自執行 click 互動降級（依各玩家 locale 解析）；
     * 任一玩家送達即 {@code delivered=true}，任一玩家實際套用降級即
     * {@code fallbackApplied=true}。單一玩家失敗不影響其他玩家。</p>
     *
     * <p>降級風格為 {@link BedrockFallbackStyle#HINTS}（預設）；要攤平成純文字時用
     * {@link #broadcastWithFallbackResult(Component, Locale, BedrockFallbackStyle)}。</p>
     *
     * @param message 原始 Component；可為 null（→ silent no-op，回傳未送達）
     * @param localeOverride 每次呼叫的 locale 覆寫；可為 null（→ 依各玩家解析）
     * @return 發送結果；never null
     * @since 1.5.0
     */
    public SendResult broadcastWithFallbackResult(Component message, Locale localeOverride) {
        return broadcastWithFallbackCore(message, localeOverride, BedrockFallbackStyle.HINTS);
    }

    /**
     * 對所有線上玩家廣播原始 {@link Component}，並回傳發送結果。
     *
     * @param message 原始 Component；可為 null（→ silent no-op，回傳未送達）
     * @param localeOverride 每次呼叫的 locale 覆寫；可為 null（→ 依各玩家解析）
     * @param style 基岩降級風格；可為 null（→ {@link BedrockFallbackStyle#HINTS}）
     * @return 發送結果；never null
     * @since 1.5.0
     */
    public SendResult broadcastWithFallbackResult(Component message, Locale localeOverride,
                                                  BedrockFallbackStyle style) {
        return broadcastWithFallbackCore(message, localeOverride, style);
    }

    /**
     * 共用核心：守衛、逐玩家降級與發送流程與舊 void 入口一致，只有回傳差別。
     */
    private SendResult broadcastWithFallbackCore(Component message, Locale localeOverride,
                                                  BedrockFallbackStyle style) {
        if (!isServiceActive()) {
            return new SendResult(false, false);
        }
        if (message == null) {
            warnSilently("broadcastWithFallback called with null message");
            return new SendResult(false, false);
        }
        synchronized (plugin) {
            if (!isServiceActive()) {
                return new SendResult(false, false);
            }
            Server srv = safeServer();
            if (srv == null) {
                warnSilently("broadcastWithFallback called but server is unavailable");
                return new SendResult(false, false);
            }
            boolean delivered = false;
            boolean fallbackApplied = false;
            for (Player p : srv.getOnlinePlayers()) {
                if (p == null || !p.isOnline()) {
                    continue;
                }
                try {
                    FallbackOutcome outcome =
                        applyFallbackWithOutcome(p, message, localeOverride, style);
                    // 降級完成即累積：後續發送失敗不抹掉已套用的事實
                    // （與單人入口失敗時仍回 outcome.applied() 一致）。
                    // delivered 只在發送成功後更新。
                    fallbackApplied = fallbackApplied || outcome.applied();
                    p.sendMessage(outcome.component());
                    delivered = true;
                } catch (IllegalStateException ex) {
                    logPlayerOperationFailure(p, "broadcastWithFallback", ex);
                } catch (Throwable t) {
                    safeLog(Level.WARNING,
                        "[" + ERR_FORMAT_ERROR + "] broadcastWithFallback failed for player="
                            + safeName(p) + ": " + t.getMessage(), t);
                }
            }
            return new SendResult(delivered, fallbackApplied);
        }
    }

    /**
     * 降級結果：送出的 Component 與本次是否確實套用降級。
     */
    private record FallbackOutcome(Component component, boolean applied) {
    }

    /**
     * 若玩家為明確基岩版，則依風格套用降級；否則原樣回傳原始 Component。
     *
     * <p>若 Floodgate locale lookup 拋例外（已記 ACELIB-MSG-004），則放棄降級、
     * 保留原始 Component，避免在無法判定 locale 時仍套用 fallback。
     * HINTS 只有降級器實際改寫輸出（click 被剝離並插入提示）才標記；
     * 無 click 的訊息走此路徑輸出與輸入為同一實例，不標記。
     * PLAIN_TEXT 只要走進基岩分支（lookup 成功）就標記。</p>
     */
    private FallbackOutcome applyFallbackWithOutcome(Player player, Component message,
                                                      Locale localeOverride,
                                                      BedrockFallbackStyle style) {
        BedrockFallbackStyle effective = style != null ? style : BedrockFallbackStyle.HINTS;
        if (!isBedrockPlayer(player)) {
            return new FallbackOutcome(message, false);
        }
        Locale locale;
        try {
            locale = resolveFallbackLocale(player, localeOverride);
        } catch (BedrockLookupFailed ex) {
            return new FallbackOutcome(message, false);
        }
        if (effective == BedrockFallbackStyle.PLAIN_TEXT) {
            return new FallbackOutcome(flattenToPlain(message, locale), true);
        }
        Component downgraded = applyBedrockFallback(message, locale);
        return new FallbackOutcome(downgraded, downgraded != message);
    }

    /**
     * 整棵 Component 攤成純文字（translatable 先依 locale 解析，保證可讀）。
     *
     * <p>永不拋錯：解析或序列化失敗時退回盡力而為的純文字，不中斷發送。</p>
     */
    private Component flattenToPlain(Component message, Locale locale) {
        try {
            Component resolved =
                locale == null ? message : GlobalTranslator.render(message, locale);
            return Component.text(PlainTextComponentSerializer.plainText().serialize(resolved));
        } catch (Throwable t) {
            try {
                return Component.text(PlainTextComponentSerializer.plainText().serialize(message));
            } catch (Throwable inner) {
                return Component.text("");
            }
        }
    }

    /**
     * Floodgate lookup 無法判定時的內部中斷信號；由 {@link #safeFloodgateLanguageCode(Player)} 拋出，
     * 僅在 {@link #applyFallbackWithOutcome} 邊界被攔截以保留原始 Component。
     */
    private static final class BedrockLookupFailed extends RuntimeException {
        BedrockLookupFailed(Throwable cause) {
            super(cause);
        }
    }

    private Component applyBedrockFallback(Component message, Locale locale) {
        return BedrockFallbackRenderer.render(message, locale, this::buildBedrockHint);
    }

    private boolean isBedrockPlayer(Player player) {
        if (bedrock == null) {
            return false;
        }
        try {
            return bedrock.isBedrockPlayer(player.getUniqueId());
        } catch (Throwable t) {
            // ACELIB-BED-001/002 或無法判定：沿用原始 Component，不降級，但留下可追蹤 warning。
            safeLog(Level.WARNING,
                "[" + ERR_BEDROCK_LOOKUP + "] bedrock player lookup failed for "
                    + safeName(player) + ": " + t.getMessage(), t);
            return false;
        }
    }

    private Locale resolveFallbackLocale(Player player, Locale override) {
        if (override != null) {
            return override;
        }
        Locale playerLocale = safePlayerLocale(player);
        if (playerLocale != null && !Locale.ROOT.equals(playerLocale)) {
            return playerLocale;
        }
        String code = safeFloodgateLanguageCode(player);
        Locale fromCode = parseLanguageCode(code);
        if (fromCode != null) {
            return fromCode;
        }
        return lang.getDefaultLocale();
    }

    private Locale safePlayerLocale(Player player) {
        try {
            return player.locale();
        } catch (Throwable t) {
            warnSilently("player.locale() failed for " + safeName(player)
                + ": " + t.getMessage());
            return null;
        }
    }

    private String safeFloodgateLanguageCode(Player player) {
        if (bedrock == null) {
            return "";
        }
        try {
            return bedrock.getPlayerInfo(player.getUniqueId())
                .map(BedrockPlayerInfo::languageCode)
                .orElse("");
        } catch (Throwable t) {
            safeLog(Level.WARNING,
                "[" + ERR_BEDROCK_LOOKUP + "] bedrock getPlayerInfo failed for "
                    + safeName(player) + ": " + t.getMessage(), t);
            throw new BedrockLookupFailed(t);
        }
    }

    private static Locale parseLanguageCode(String code) {
        if (code == null) {
            return null;
        }
        String s = code.trim();
        if (s.isEmpty()) {
            return null;
        }
        s = s.replace('-', '_');
        if (!s.matches("[a-zA-Z]{2,8}(_[a-zA-Z]{2,8})?")) {
            return null;
        }
        int idx = s.indexOf('_');
        if (idx < 0) {
            return new Locale(s);
        }
        return new Locale(s.substring(0, idx), s.substring(idx + 1));
    }

    private Component buildBedrockHint(ClickEvent click, Locale locale) {
        ClickEventCompat.Descriptor d = ClickEventCompat.describe(click);
        // 預設為 UNKNOWN fail-safe；已知 action 會在下方 switch 覆寫。
        String key = "message.bedrock.fallback.unknown";
        String defaultPrefix = "[Action: ";
        switch (d.kind) {
            case RUN_COMMAND -> {
                key = "message.bedrock.fallback.run_command";
                defaultPrefix = "[Run command: ";
            }
            case SUGGEST_COMMAND -> {
                key = "message.bedrock.fallback.suggest_command";
                defaultPrefix = "[Suggest command: ";
            }
            case OPEN_URL -> {
                key = "message.bedrock.fallback.open_url";
                defaultPrefix = "[Open URL: ";
            }
            case COPY_TO_CLIPBOARD -> {
                key = "message.bedrock.fallback.copy_to_clipboard";
                defaultPrefix = "[Copy to clipboard: ";
            }
            // UNKNOWN 與未來新增種類：沿用預設（fail-safe，不拋 linkage error）。
            default -> { }
        }
        String payload = d.payload == null ? "" : d.payload;
        String template;
        try {
            Optional<String> opt = lang.get(locale, key);
            template = (opt.isPresent() && !opt.get().isEmpty()) ? opt.get() : null;
        } catch (Throwable t) {
            safeLog(Level.WARNING,
                "[" + ERR_BEDROCK_LOOKUP + "] bedrock fallback prompt lookup failed key=" + key
                    + " locale=" + locale + ": " + t.getMessage(), t);
            template = null;
        }
        if (template != null) {
            try {
                // payload 以 unparsed placeholder 注入，避免被當成 MiniMessage 解析（防注入）。
                return MiniMessage.miniMessage().deserialize(template,
                    Placeholder.unparsed("payload", payload));
            } catch (Throwable t) {
                safeLog(Level.WARNING,
                    "[" + ERR_BEDROCK_LOOKUP + "] bedrock fallback prompt parse failed key=" + key
                        + " locale=" + locale + ": " + t.getMessage(), t);
            }
        } else {
            // 缺 prompt key / locale file：安全可讀 default text + ACELIB-MSG-* warning。
            safeLog(Level.WARNING,
                "[" + ERR_BEDROCK_LOOKUP + "] bedrock fallback prompt missing key=" + key
                    + " locale=" + locale + "; using safe default text");
        }
        return Component.text(defaultPrefix + payload + "]");
    }

    // -----------------------------------------------------------------
    // Component 管線內部 helper
    // -----------------------------------------------------------------

    private Component applyPrefixIfNeeded(Component component, Locale locale) {
        Optional<String> prefix = lang.get(locale, PREFIX_KEY);
        if (prefix.isEmpty() || prefix.get().isEmpty()) {
            return component;
        }
        return Component.text(prefix.get()).append(component);
    }

    private String safeSubstitute(String template, Map<String, Object> vars) {
        if (vars == null || vars.isEmpty()) {
            return template;
        }
        StringBuilder sb = new StringBuilder(template.length() + 32);
        int i = 0;
        int len = template.length();
        while (i < len) {
            char c = template.charAt(i);
            if (c == '{') {
                int end = template.indexOf('}', i + 1);
                if (end > 0) {
                    String varKey = template.substring(i + 1, end);
                    if (vars.containsKey(varKey)) {
                        // 使用者變數值先跳脫，避免 <tag> 被當成 MiniMessage 標籤注入。
                        sb.append(MiniMessage.miniMessage().escapeTags(String.valueOf(vars.get(varKey))));
                        i = end + 1;
                        continue;
                    }
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

    private static TagResolver buildUnparsedResolver(Map<String, Object> vars) {
        if (vars == null || vars.isEmpty()) {
            return TagResolver.empty();
        }
        TagResolver[] resolvers = new TagResolver[vars.size()];
        int idx = 0;
        for (Map.Entry<String, Object> e : vars.entrySet()) {
            resolvers[idx++] = Placeholder.unparsed(e.getKey(), String.valueOf(e.getValue()));
        }
        return TagResolver.resolver(resolvers);
    }

    private Component deserializeOrNull(String input, TagResolver resolver, String errCode, String context) {
        try {
            return resolver == null
                ? MiniMessage.miniMessage().deserialize(input)
                : MiniMessage.miniMessage().deserialize(input, resolver);
        } catch (Throwable t) {
            safeLog(Level.WARNING,
                "[" + errCode + "] " + context + ": " + t.getMessage(), t);
            return null;
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
     * 判斷 owner plugin 是否仍處於可安全送出的生命週期狀態。
     *
     * <p>canonical {@link AceLibPlugin} 以 {@link AceLibPlugin#isReady()} 為準：
     * {@code onDisable()} 後 {@code isReady()} 回傳 false，服務應停止送出任何玩家訊息。
     * 一般 {@link JavaPlugin} 沒有 {@code isReady()} 概念，改以
     * {@link JavaPlugin#isEnabled()} 判斷；任何例外都視為不可用，避免 Mockito
     * generic plugin 的 getter 在測試環境拋出時被誤判為可用。</p>
     *
     * <p>本方法只影響「送出／broadcast」類 API；純格式化（{@link #format}、
     * {@link #formatComponent}、{@link #parseMiniMessage}）不受此 guard 影響。</p>
     */
    private boolean isServiceActive() {
        try {
            if (plugin instanceof AceLibPlugin aceLibPlugin) {
                return aceLibPlugin.isReady();
            }
            return plugin.isEnabled();
        } catch (Throwable t) {
            safeLog(Level.WARNING,
                "[" + ERR_FORMAT_ERROR + "] lifecycle probe failed; treating message service "
                    + "as inactive: " + t.getMessage(), t);
            return false;
        }
    }

    /**
     * 取得 plugin 持有的 {@link Server}；plugin 未 onEnable / 停用後回傳 null。
     *
     * <p>停用判斷以 {@link #isServiceActive()} 為準：canonical {@link AceLibPlugin}
     * 在 {@code onDisable()} 後 {@code isReady()} 為 false，此時即使
     * {@link JavaPlugin#getServer()} 仍回傳非 null（MockBukkit 等環境），也必須回傳
     * null，避免對已停用 plugin 的玩家送出訊息。</p>
     */
    private Server safeServer() {
        if (!isServiceActive()) {
            return null;
        }
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
