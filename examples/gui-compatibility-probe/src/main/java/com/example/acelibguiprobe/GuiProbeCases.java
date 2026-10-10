package com.example.acelibguiprobe;

import java.util.List;

/**
 * GUI 探針案例目錄：固定、可重複的實機觀察清單（順序即操作順序）。
 *
 * <p>九個案例對應九項驗收面向；每個案例的「怎麼斷言」寫在
 * {@link GuiCompatibilityProbePlugin} 對應分支的 log 與玩家提示裡，
 * 以 {@code [gprobe-...]} 為前綴寫入 server log。</p>
 */
public final class GuiProbeCases {

    private GuiProbeCases() {
    }

    /**
     * 回傳固定、可重複的探針案例清單（順序即操作清單順序）。
     *
     * @return 案例清單；never null、never empty
     */
    public static List<GuiProbeCase> buildCatalog() {
        return List.of(
            new GuiProbeCase("nav",
                "導航：open 主畫面 → push 第二層 → replace 第三層 → back 回第二層 → "
                    + "close 關閉；每步 log 記 [gprobe-nav] result=<state>，"
                    + "玩家端以實際開關箱型介面確認"),
            new GuiProbeCase("ticket",
                "一次性票券：開啟確認畫面並簽發票券 → 玩家點確認鈕（或 /gprobe confirm）"
                    + "消耗一次 → 重複確認須回 ACTION_ALREADY_RESOLVED；"
                    + "log 記 [gprobe-ticket] callback=<FIRED|NOT_FIRED>"),
            new GuiProbeCase("input",
                "聊天輸入：開啟輸入畫面並提示玩家在聊天欄輸入 → 玩家輸入被消耗"
                    + "（不外流到公開聊天）→ log 記 [gprobe-input]；"
                    + "未命中提示的正常聊天不受影響"),
            new GuiProbeCase("cooldown",
                "按鈕冷卻：開啟含 5 秒冷卻按鈕的畫面 → 快速連點 → 第二次須回 "
                    + "COOLDOWN_ACTIVE 且回呼只執行一次；log 記 [gprobe-cooldown]"),
            new GuiProbeCase("form",
                "基岩表單派送：經共用流程發送固定 simple 表單給目標玩家 → "
                    + "Java 端不受影響，Bedrock（經 Geyser）客戶端觀察彈窗；"
                    + "log 記 [gprobe-form] result=<SENT|REJECTED>"),
            new GuiProbeCase("flow",
                "共用流程：同一份 GuiFlow 經 openFlow 開啟 → Java 玩家看到箱型步驟、"
                    + "按鈕以 goTo 推進；基岩玩家看到原生表單、按鈕回應以 transitions 推進；"
                    + "每步與完成 log 記 [gprobe-flow]"),
            new GuiProbeCase("mask-icons",
                "遮罩物品位置：以固定字元遮罩開 27 格箱型畫面（A 區 9 欄放鑽石按鈕、B 區 "
                    + "9 欄放金蘋果按鈕、C 區 9 欄放終界珍珠按鈕，預期欄位見 log）→ "
                    + "操作步驟＝/gprobe send <player> mask-icons 後逐欄點擊；"
                    + "斷言方式＝server log 記 [gprobe-mask-icons] 每符號預期欄位與點擊回呼"
                    + "（已執行）；物品確在預期欄位、點該欄位觸發同一按鈕屬真人觀察"
                    + "（客戶端才看得到，不以伺服器日誌宣稱）"),
            new GuiProbeCase("pager",
                "分頁翻頁：開啟固定 23 筆資料的分頁畫面（項目區 10 欄、上一頁欄 18、"
                    + "下一頁欄 26，標題前綴「探針分頁」）→ 操作步驟＝"
                    + "/gprobe send <player> pager 後點下一頁到第 2 頁再到第 3 頁、"
                    + "再逐頁返回；斷言方式＝server log 記 [gprobe-pager] 每頁標題"
                    + "（探針分頁 — 頁 i/3）與上一頁／下一頁按鈕存在性（已執行）；"
                    + "翻頁後標題是否切換、首頁無上一頁／末頁無下一頁／中頁兩者皆有"
                    + "屬真人觀察（客戶端才看得到，不以伺服器日誌宣稱）"),
            new GuiProbeCase("label-form",
                "標籤表單：開啟帶標籤的共用流程（第一步三顆標籤按鈕：欄 10 蘋果／"
                    + "欄 13 香蕉／欄 16 橘子，依欄升序對應表單索引 0/1/2，全轉往第二步；"
                    + "第二步單顆完成鈕）→ 操作步驟＝/gprobe send <player> label-form 後 "
                    + "Java 端點箱型按鈕推進、基岩端在原生表單點按鈕；斷言方式＝server log 記 "
                    + "[gprobe-label-form] Java 開啟結果與基岩派送結果（已送出／已執行）"
                    + "及轉移索引；基岩端表單按鈕順序（蘋果／香蕉／橘子）與索引對應"
                    + "屬真人觀察（客戶端才看得到，探針只記錄派送結果，"
                    + "不以伺服器日誌宣稱）")); 
    }
}
