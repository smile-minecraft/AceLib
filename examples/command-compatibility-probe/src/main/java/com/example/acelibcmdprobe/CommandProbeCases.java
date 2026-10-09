package com.example.acelibcmdprobe;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 探針案例目錄（固定、可重跑）。
 *
 * <p>三組根指令對應三種註冊路徑，覆蓋每個型別化引數的解析、錯誤與補全，
 * 以及生命週期殘留檢查：</p>
 * <ul>
 *   <li>{@code /cprobe parse <...>} — 成功解析，記錄執行緒與型別值</li>
 *   <li>{@code /cprobe-args <...>} — 錯誤路徑（非法值／溢位／未知目標）與
 *       補全觀察；根指令無權限限制，任何人可試</li>
 *   <li>{@code /cprobe lifecycle <...>} — reload／重複註冊／disable 殘留</li>
 * </ul>
 *
 * <p>案例不引用 Bukkit API（純字串），因此可在無伺服器環境做完整性測試。
 * 以 {@code trade} 開頭的輸入是相對於 {@code /cprobe-args} 根指令，
 * 其餘輸入相對於 {@code /cprobe}。</p>
 */
public final class CommandProbeCases {

    private CommandProbeCases() {
    }

    /** 案例分組：解析成功路徑。 */
    public static final String GROUP_PARSE = "parse";
    /** 案例分組：錯誤路徑。 */
    public static final String GROUP_ERROR = "error";
    /** 案例分組：補全觀察。 */
    public static final String GROUP_COMPLETE = "complete";
    /** 案例分組：生命週期殘留。 */
    public static final String GROUP_LIFECYCLE = "lifecycle";

    private static final Map<String, ProbeCase> CASES = buildCases();

    /** 全部案例（依宣告順序，不可變）。 */
    public static List<ProbeCase> all() {
        return List.copyOf(CASES.values());
    }

    /**
     * 依 id 取案例。
     *
     * @param id 案例識別字；不可為 null
     * @return 對應案例；不存在時為 null
     */
    public static ProbeCase byId(String id) {
        return id == null ? null : CASES.get(id);
    }

    private static Map<String, ProbeCase> buildCases() {
        Map<String, ProbeCase> cases = new LinkedHashMap<>();

        // 解析成功路徑：每種型別化引數各一。
        add(cases, "parse-player", GROUP_PARSE, "parse Steve",
            "在線玩家解析為 PlayerHandle，記錄執行緒名稱與是否 region 執行緒");
        add(cases, "parse-player-case", GROUP_PARSE, "parse STEVE",
            "大小寫不敏感：應與 parse-player 同結果");
        add(cases, "parse-offline", GROUP_PARSE, "parse-offline Steve",
            "離線玩家解析為 OfflinePlayer（可在線亦可離線）");
        add(cases, "parse-int-min", GROUP_PARSE, "parse-int 1",
            "範圍下限 1 inclusive：解析成功");
        add(cases, "parse-int-max", GROUP_PARSE, "parse-int 64",
            "範圍上限 64 inclusive：解析成功");
        add(cases, "parse-double", GROUP_PARSE, "parse-double 2.5",
            "小數範圍 0-10：解析成功");
        add(cases, "parse-duration-ticks", GROUP_PARSE, "parse-duration 100",
            "無單位：100 ticks");
        add(cases, "parse-duration-suffix", GROUP_PARSE, "parse-duration 1s",
            "帶 s 單位：20 ticks");
        add(cases, "parse-duration-day", GROUP_PARSE, "parse-duration 1d",
            "帶 d 單位：24000 ticks");
        add(cases, "parse-duration-fraction", GROUP_PARSE, "parse-duration 1.5s",
            "小數秒：30 ticks（向下取整）");
        add(cases, "parse-world", GROUP_PARSE, "parse-world overworld",
            "已載入世界解析為 World（預設維度為 overworld；其他世界名稱請覆寫）");
        add(cases, "parse-enum", GROUP_PARSE, "parse-mode buy",
            "列舉固定選項解析為常數");
        add(cases, "parse-fixed", GROUP_PARSE, "parse-fixed sell",
            "固定字串選項解析為 canonical 字串");
        add(cases, "parse-material", GROUP_PARSE, "parse-material stone",
            "材質解析為 Material");

        // 第三階段：省略引數＋重複引數（同一 give 子指令：
        // [player] [amount 預設1] [extra:material...]）。
        add(cases, "give-default", GROUP_PARSE, "give Steve",
            "省略 amount → 預設 1（source=default），重複引數零個 → notes=[]；"
                + "回 ok player=Steve amount=1 source=default notes=[]");
        add(cases, "give-provided", GROUP_PARSE, "give Steve 5",
            "提供 amount=5（source=provided），重複引數零個 → notes=[]");
        add(cases, "give-repeat-one", GROUP_PARSE, "give Steve 5 stone",
            "單一重複值：amount=5，notes=[STONE]");
        add(cases, "give-repeat-many", GROUP_PARSE, "give Steve 5 stone dirt",
            "多個重複值：amount=5，notes=[STONE, DIRT]（順序即輸入順序）");

        // 第三階段：精確數值（parse-bigdecimal，範圍 0-1000 含端點，小數位上限 2）。
        add(cases, "parse-bigdecimal-ok", GROUP_PARSE, "parse-bigdecimal 0.10",
            "精確值 0.10：回 ok value=0.10 scale=2（scale 保留，不經 double 中轉）");
        add(cases, "err-bigdecimal-scale", GROUP_ERROR, "parse-bigdecimal 1.234",
            "超小數位（上限 2 位）→ ACELIB-CMD-015（不四捨五入）");
        add(cases, "err-bigdecimal-scientific", GROUP_ERROR, "parse-bigdecimal 1E3",
            "科學記號 → ACELIB-CMD-015（須改寫為一般十進位）");
        add(cases, "err-bigdecimal-range", GROUP_ERROR, "parse-bigdecimal 1000.01",
            "超範圍（上限 1000 含）→ ACELIB-CMD-015");

        // 第三階段：動態選項（初始集合 alpha／beta；dyn-add／dyn-remove
        // 執行期增刪，供應函式每次解析與補全重新取值）。
        add(cases, "dyn-parse-initial", GROUP_PARSE, "parse-dyn alpha",
            "初始集合含 alpha：回 ok value=alpha（canonical 宣告形式）");
        add(cases, "dyn-add-gamma", GROUP_PARSE, "dyn-add gamma",
            "回報已加入 gamma；之後 parse-dyn gamma 可解析，"
                + "parse-dyn 補全出現 gamma");
        add(cases, "dyn-parse-added", GROUP_PARSE, "parse-dyn gamma",
            "需先執行 dyn-add gamma：回 ok（大小寫不敏感，回宣告形式 gamma）");
        add(cases, "dyn-remove-beta", GROUP_PARSE, "dyn-remove beta",
            "回報已移除 beta；之後 parse-dyn beta 走 ACELIB-CMD-015");
        add(cases, "dyn-parse-removed", GROUP_ERROR, "parse-dyn beta",
            "需先執行 dyn-remove beta：集合內已無 beta → ACELIB-CMD-015");

        // 第三階段：自訂引數（parse-percent：0-100 裸數字 → 0.0-1.0，
        // 字元集內單 token，兩條路徑皆可解析）。
        add(cases, "parse-percent-ok", GROUP_PARSE, "parse-percent 75",
            "裸數字 75 → 0.75（0-100 含端點）");
        add(cases, "err-percent-range", GROUP_ERROR, "parse-percent 150",
            "超出 0-100 → ACELIB-CMD-015");
        add(cases, "err-percent-nonnumeric", GROUP_ERROR, "parse-percent abc",
            "非數字 → ACELIB-CMD-015");

        // 第三階段：子指令別名（pi ≡ parse-int）。
        add(cases, "alias-pi", GROUP_PARSE, "pi 5",
            "別名執行與主名等價：與 parse-int 5 同回覆 ok value=5");

        // 第三階段：固定選項在地化（/cprobe-args 根；成功基線＋集合外值）。
        add(cases, "args-trade-ok", GROUP_PARSE, "trade buy 5",
            "/cprobe-args 根：回 ok mode=buy amount=5（錯誤案例的對照基線）");
        add(cases, "err-args-trade-loud", GROUP_ERROR, "trade loud",
            "/cprobe-args 根：集合外值走錯誤後備節點，"
                + "回在地化 ACELIB-CMD-015（非 Brigadier 通用錯誤）");

        // 錯誤路徑：ACELIB-CMD-015 的每一類。
        add(cases, "err-int-nonnumeric", GROUP_ERROR, "parse-int abc",
            "非數字 → ACELIB-CMD-015，在地化錯誤提示");
        add(cases, "err-int-overflow", GROUP_ERROR, "parse-int 99999999999999999999",
            "int 溢位 → ACELIB-CMD-015（不 wrap、不截斷）");
        add(cases, "err-int-below-min", GROUP_ERROR, "parse-int 0",
            "低於下限 → ACELIB-CMD-015");
        add(cases, "err-int-above-max", GROUP_ERROR, "parse-int 65",
            "高於上限 → ACELIB-CMD-015");
        add(cases, "err-double-nan", GROUP_ERROR, "parse-double NaN",
            "NaN → ACELIB-CMD-015");
        add(cases, "err-double-infinite", GROUP_ERROR, "parse-double Infinity",
            "無限大 → ACELIB-CMD-015");
        add(cases, "err-double-range", GROUP_ERROR, "parse-double 99",
            "超出小數範圍 → ACELIB-CMD-015");
        add(cases, "err-duration-syntax", GROUP_ERROR, "parse-duration 5h",
            "h 單位兩端皆拒絕 → ACELIB-CMD-015（與 vanilla time 一致）");
        add(cases, "err-duration-overflow", GROUP_ERROR,
            "parse-duration 99999999999999999999d",
            "時間長度溢位 → ACELIB-CMD-015（long 精確運算）");
        add(cases, "err-duration-fraction-overflow", GROUP_ERROR,
            "parse-duration 1.00000000000000000000000000000001s",
            "超長小數位溢位 → ACELIB-CMD-015");
        add(cases, "err-world-unknown", GROUP_ERROR, "parse-world nosuchworld",
            "未載入世界 → ACELIB-CMD-015（注意：world 不是合法維度名，"
                + "預設維度是 overworld）");
        add(cases, "err-material-unknown", GROUP_ERROR, "parse-material nosuchmaterial",
            "未知材質 → ACELIB-CMD-015");
        add(cases, "err-enum-unknown", GROUP_ERROR, "parse-mode unknownmode",
            "非列舉常數 → ACELIB-CMD-015");
        add(cases, "err-fixed-unknown", GROUP_ERROR, "parse-fixed unknownmode",
            "非固定選項 → ACELIB-CMD-015");
        add(cases, "err-offline-unknown", GROUP_ERROR, "parse-offline NeverPlayedPlayer",
            "從未上線玩家 → ACELIB-CMD-015（不是 ACELIB-CMD-007 離線語意）");
        add(cases, "err-player-offline", GROUP_ERROR, "parse NobodyOnline",
            "不在線玩家 → ACELIB-CMD-007（沿用既有離線語意）");
        add(cases, "err-whitespace", GROUP_ERROR, "parse-int 1 2",
            "額外 token → ACELIB-CMD-015（參數數量不符）");

        // 補全觀察：Java 版由伺服器建議；基岩端建議列實測未顯示（literal 分支
        // 與玩家引數皆然，Geyser Current Limitations，Unfixable），見
        // docs/modules/command.md 補全支援矩陣。
        add(cases, "complete-enum", GROUP_COMPLETE, "parse-mode ",
            "列舉選項（buy/sell）以 literal 分支結構呈現 — 基岩版：實測建議列不顯示（Geyser 平台限制）");
        add(cases, "complete-fixed", GROUP_COMPLETE, "parse-fixed ",
            "固定選項以 literal 分支呈現 — 基岩版：實測建議列不顯示（Geyser 平台限制）");
        add(cases, "complete-player", GROUP_COMPLETE, "parse ",
            "在線玩家名（伺服器建議；基岩版：實測建議列不顯示，Geyser 平台限制）");
        add(cases, "complete-world", GROUP_COMPLETE, "parse-world ",
            "已載入世界名（伺服器建議；基岩版：推論無建議列，同 Geyser 平台限制，未逐項實測）");
        add(cases, "complete-material", GROUP_COMPLETE, "parse-material ",
            "材質名（伺服器建議；基岩版：推論無建議列，同 Geyser 平台限制，未逐項實測）");
        add(cases, "complete-duration", GROUP_COMPLETE, "parse-duration ",
            "時間語法範例（伺服器建議；基岩版：推論無建議列，同 Geyser 平台限制，未逐項實測）");
        add(cases, "complete-offline", GROUP_COMPLETE, "parse-offline ",
            "best-effort 只列在線玩家（離線名單無法低成本枚舉；基岩版：推論無建議列，同 Geyser 平台限制，未逐項實測）");
        add(cases, "complete-int-none", GROUP_COMPLETE, "parse-int ",
            "整數刻意不給建議；範圍由客戶端驗證");
        add(cases, "complete-bigdecimal-none", GROUP_COMPLETE, "parse-bigdecimal ",
            "精確數值刻意不給建議；範圍由客戶端驗證（同整數）");
        add(cases, "complete-dyn", GROUP_COMPLETE, "parse-dyn ",
            "動態選項（伺服器建議；Java 版列出當前集合，增刪後即變；"
                + "基岩版未實測，不斷言可見性）");
        add(cases, "complete-percent", GROUP_COMPLETE, "parse-percent ",
            "自訂引數候選 25／50／75／100（伺服器建議，前綴過濾；"
                + "基岩版未實測，不斷言可見性）");

        // 生命週期：殘留檢查（以 RCON 或 console 執行，需 acelibcmdprobe.admin）。
        add(cases, "life-reregister", GROUP_LIFECYCLE, "lifecycle re-register",
            "重複註冊同名 → IllegalArgumentException，平台側不掛第二個節點");
        add(cases, "life-shutdown", GROUP_LIFECYCLE, "lifecycle shutdown",
            "shutdown 後殘留 dispatch 一律 ACELIB-CMD-009，不靜默執行");
        add(cases, "life-status", GROUP_LIFECYCLE, "lifecycle status",
            "回報已註冊根指令數與平台側節點狀態");

        return cases;
    }

    private static void add(Map<String, ProbeCase> cases, String id, String group,
                            String input, String expectation) {
        cases.put(id, new ProbeCase(id, group, input, expectation));
    }
}