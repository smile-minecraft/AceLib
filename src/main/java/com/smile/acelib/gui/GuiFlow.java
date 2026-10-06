package com.smile.acelib.gui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Java GUI／基岩表單共用流程（Supported API）。
 *
 * <p>下游以有序步驟描述一份跨呈現流程（見 {@link GuiFlowStep}），交給
 * {@link GuiScope#openFlow} 開啟：Java 玩家看到 inventory 視圖並以
 * {@link GuiScope#goTo} 前進，基岩玩家收到原生表單、其回應由服務端按轉移表
 * 自動推進。兩種呈現共用同一份返回歷史（{@code back} 回到上一步）。</p>
 *
 * <ul>
 *   <li>表單 {@code VALID} → 轉移表命中的步驟，否則線性下一步；
 *       已是線性末端 → 結束流程並觸發 {@code onComplete}（可為 null＝只關閉）</li>
 *   <li>表單 {@code CLOSED} → 結束流程（正常關閉，不是錯誤）</li>
 *   <li>表單 {@code INVALID} → 停留（忽略，不推進也不關閉）</li>
 *   <li>過時回應（推進後才回來的舊回應）→ 忽略，不重送</li>
 * </ul>
 *
 * <p>需要讀取 custom 表單元件答案的場景請直接使用
 * {@link com.smile.acelib.form.FormService}；流程只負責導航，
 * 不做資料綁定。</p>
 *
 * @see GuiFlowStep
 * @see GuiScope
 * @since 1.4.0
 */
public final class GuiFlow {

    private final List<GuiFlowStep> steps;
    private final Map<String, GuiFlowStep> byId;
    private final String startStepId;
    private final Consumer<UUID> onComplete;

    private GuiFlow(List<GuiFlowStep> steps, String startStepId,
            Consumer<UUID> onComplete) {
        Objects.requireNonNull(steps, "steps");
        Objects.requireNonNull(startStepId, "startStepId");
        if (steps.isEmpty()) {
            throw new IllegalArgumentException(
                "[" + GuiErrorCode.INVALID_INPUT + "] 流程至少需要一個步驟");
        }
        Map<String, GuiFlowStep> index = new LinkedHashMap<>();
        for (GuiFlowStep step : steps) {
            Objects.requireNonNull(step, "step");
            if (index.containsKey(step.id())) {
                throw new IllegalArgumentException(
                    "[" + GuiErrorCode.INVALID_INPUT + "] 流程步驟識別字重複: "
                        + step.id());
            }
            index.put(step.id(), step);
        }
        if (!index.containsKey(startStepId)) {
            throw new IllegalArgumentException(
                "[" + GuiErrorCode.INVALID_INPUT + "] 起始步驟不存在: " + startStepId);
        }
        for (GuiFlowStep step : steps) {
            for (String target : step.transitions().values()) {
                if (!index.containsKey(target)) {
                    throw new IllegalArgumentException(
                        "[" + GuiErrorCode.INVALID_INPUT + "] 步驟 " + step.id()
                            + " 的轉移目標不存在: " + target);
                }
            }
        }
        this.steps = List.copyOf(steps);
        this.byId = Map.copyOf(index);
        this.startStepId = startStepId;
        this.onComplete = onComplete;
    }

    /**
     * 建立流程（結束時只關閉，不另行通知）。
     *
     * @param steps 有序步驟；不可為 null／空，識別字唯一，轉移目標必須存在
     * @param startStepId 起始步驟識別字；必須存在於 {@code steps}
     * @return 新的 {@link GuiFlow}
     */
    public static GuiFlow of(List<GuiFlowStep> steps, String startStepId) {
        return new GuiFlow(steps, startStepId, null);
    }

    /**
     * 建立流程（含結束回呼）。
     *
     * @param steps 有序步驟；不可為 null／空，識別字唯一，轉移目標必須存在
     * @param startStepId 起始步驟識別字；必須存在於 {@code steps}
     * @param onComplete 流程走完線性末端時的回呼；不可為 null
     *     （執行於表單回應派送執行緒／呼叫端執行緒，不得做長時間工作）
     * @return 新的 {@link GuiFlow}
     */
    public static GuiFlow of(List<GuiFlowStep> steps, String startStepId,
            Consumer<UUID> onComplete) {
        Objects.requireNonNull(onComplete, "onComplete");
        return new GuiFlow(steps, startStepId, onComplete);
    }

    /** @return 有序步驟（不可變） */
    public List<GuiFlowStep> steps() {
        return steps;
    }

    /** @return 起始步驟識別字；永不為 null */
    public String startStepId() {
        return startStepId;
    }

    /**
     * @return 結束回呼；可為 null（只關閉）
     */
    public Consumer<UUID> onComplete() {
        return onComplete;
    }

    /**
     * 依識別字查詢步驟。
     *
     * @param stepId 步驟識別字；不可為 null
     * @return 對應步驟；不存在時為 null
     */
    public GuiFlowStep step(String stepId) {
        Objects.requireNonNull(stepId, "stepId");
        return byId.get(stepId);
    }

    /**
     * 線性下一步（依 {@link #steps()} 順序）。
     *
     * @param stepId 目前步驟；不可為 null
     * @return 下一步；已是末端時為 null
     */
    public GuiFlowStep linearNext(String stepId) {
        Objects.requireNonNull(stepId, "stepId");
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).id().equals(stepId)) {
                return i + 1 < steps.size() ? steps.get(i + 1) : null;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "GuiFlow{startStepId=" + startStepId + ", steps=" + steps + "}";
    }
}
