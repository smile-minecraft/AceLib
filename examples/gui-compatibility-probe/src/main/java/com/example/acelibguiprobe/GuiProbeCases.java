package com.example.acelibguiprobe;

import java.util.List;

/**
 * GUI 探針案例目錄：固定、可重複的實機觀察清單（順序即操作順序）。
 *
 * <p>五個案例對應五項驗收面向；每個案例的「怎麼斷言」寫在
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
                    + "每步與完成 log 記 [gprobe-flow]")); 
    }
}
