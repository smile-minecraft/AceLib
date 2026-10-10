package com.smile.acelib.gui;

import com.smile.acelib.form.FormSpec;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 共用流程的一個步驟（Supported API）。
 *
 * <p>同一個步驟同時描述 Java GUI 呈現（{@link GuiView}）與基岩表單呈現
 * （{@link FormSpec}，可為 null 表示該步驟只有 Java 呈現、基岩玩家退回
 * 開啟 Java inventory）：基岩客戶端看到原生表單，Java 客戶端看到 inventory，
 * 兩者走同一份步驟順序與返回歷史。</p>
 *
 * <p>基岩表單的按鈕→下一步對應由 {@link #transitions()} 描述
 * （按鈕索引 → 步驟識別字）；沒有對應的按鈕走線性下一步。
 * Java 側由下游按鈕回呼呼叫 {@link GuiScope#goTo} 前進
 * （基岩客戶端無法執行程式碼，故由服務端代為推進）。</p>
 *
 * @param id 步驟識別字（流程內唯一；不可為空白）
 * @param view Java GUI 視圖；不可為 null
 * @param form 基岩表單規格；可為 null（該步驟無基岩呈現）
 * @param transitions 按鈕索引 → 下一步驟識別字（不可變；null 視為空表＝全線性）
 * @see GuiFlow
 * @since 1.4.0
 */
public record GuiFlowStep(String id, GuiView view, FormSpec form,
                          Map<Integer, String> transitions) {

    /**
     * 正規化建構子。
     *
     * @throws NullPointerException 當 {@code id}／{@code view} 為 null
     * @throws IllegalArgumentException 當 {@code id} 空白、轉移鍵為負或目標空白
     */
    public GuiFlowStep {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(view, "view");
        if (id.isBlank()) {
            throw new IllegalArgumentException(
                "[" + GuiErrorCode.INVALID_INPUT + "] 流程步驟識別字不可為空白");
        }
        if (transitions == null) {
            transitions = Map.of();
        } else {
            for (Map.Entry<Integer, String> entry : transitions.entrySet()) {
                if (entry.getKey() == null || entry.getKey() < 0) {
                    throw new IllegalArgumentException(
                        "[" + GuiErrorCode.INVALID_INPUT + "] 轉移按鈕索引不可為 null 或負數");
                }
                if (entry.getValue() == null || entry.getValue().isBlank()) {
                    throw new IllegalArgumentException(
                        "[" + GuiErrorCode.INVALID_INPUT + "] 轉移目標步驟不可為 null 或空白");
                }
            }
            transitions = Map.copyOf(transitions);
        }
    }

    /**
     * 建立無轉移表（全線性下一步）的步驟。
     *
     * @param id 步驟識別字；不可為 null／空白
     * @param view Java GUI 視圖；不可為 null
     * @param form 基岩表單規格；可為 null
     */
    public GuiFlowStep(String id, GuiView view, FormSpec form) {
        this(id, view, form, Map.of());
    }

    /**
     * 建立純 Java 呈現的步驟（基岩玩家退回開啟 Java inventory）。
     *
     * @param id 步驟識別字；不可為 null／空白
     * @param view Java GUI 視圖；不可為 null
     */
    public GuiFlowStep(String id, GuiView view) {
        this(id, view, null, Map.of());
    }

    /**
     * 以同一份按鈕宣告產生基岩表單的步驟。
     *
     * <p>有標籤的按鈕依欄位升序排成簡單表單的按鈕（與宣告順序無關）；
     * {@code transitions} 的按鈕索引對應該順序（第 0 顆是欄位最小的有標籤按鈕，
     * 依此類推）。無標籤（null）的按鈕不進表單。表單標題與說明皆取自視圖標題。</p>
     *
     * <p>視圖完全無標籤按鈕時退回純 Java 步驟（{@code form} 為 null）；
     * 此時 {@code transitions} 必須為空，否則索引無對應按鈕而被拒。</p>
     *
     * @param id 步驟識別字；不可為 null／空白
     * @param view Java GUI 視圖；不可為 null（其標籤按鈕即表單按鈕來源）
     * @param transitions 按鈕索引 → 下一步驟識別字（索引對應欄位升序的標籤順序；
     *     不可變；null 視為空表＝全線性）
     * @return 新的步驟；有標籤時攜帶產生的表單，無標籤時 {@code form} 為 null
     * @throws NullPointerException 當 {@code id} 或 {@code view} 為 null
     * @throws IllegalArgumentException 當 {@code id} 空白、視圖標題空白（無法作為
     *     表單標題與說明）、轉移鍵超出標籤按鈕範圍，或轉移目標空白
     */
    public static GuiFlowStep labeled(String id, GuiView view,
            Map<Integer, String> transitions) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(view, "view");
        Map<Integer, String> moves = transitions == null ? Map.of() : transitions;
        List<String> labels = view.buttonLabels().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(Map.Entry::getValue)
            .toList();
        if (labels.isEmpty()) {
            if (!moves.isEmpty()) {
                throw new IllegalArgumentException(
                    "[" + GuiErrorCode.INVALID_INPUT + "] 轉移按鈕索引無對應標籤按鈕："
                        + "視圖沒有標籤按鈕，轉移表必須為空");
            }
            return new GuiFlowStep(id, view);
        }
        if (view.title().isBlank()) {
            throw new IllegalArgumentException(
                "[" + GuiErrorCode.INVALID_INPUT + "] 標籤表單需要非空白視圖標題"
                    + "（表單標題與說明皆取自視圖標題）");
        }
        for (Map.Entry<Integer, String> entry : moves.entrySet()) {
            if (entry.getKey() == null || entry.getKey() < 0
                    || entry.getKey() >= labels.size()) {
                throw new IllegalArgumentException(
                    "[" + GuiErrorCode.INVALID_INPUT + "] 轉移按鈕索引超出標籤按鈕範圍: "
                        + entry.getKey() + "（標籤按鈕數=" + labels.size() + "）");
            }
        }
        FormSpec.Simple.Builder builder =
            FormSpec.simple(view.title()).content(view.title());
        for (String label : labels) {
            builder.button(label);
        }
        return new GuiFlowStep(id, view, builder.build(), moves);
    }
}
