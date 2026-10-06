package com.example.acelibguiprobe;

/**
 * 單一 GUI 相容性探針案例：固定、可重複執行的實機觀察樣本。
 *
 * <p>本類別只描述「要觀察什麼」，不負責執行；執行由
 * {@link GuiCompatibilityProbePlugin} 經由玩家指令、透過 AceLib
 * {@code GuiScope}（插件隔離 handle）完成。案例內容固定，
 * 確保實機觀察可重現。</p>
 *
 * <p>每個案例都有穩定的 {@link #id()}；新增案例時必須同步更新
 * {@code GuiProbeCasesTest} 的完整性斷言與 {@code docs/modules/gui.md}
 * 的探針小節。</p>
 */
public final class GuiProbeCase {

    private final String id;
    private final String description;

    /**
     * @param id 穩定識別碼（實機操作清單鍵）
     * @param description 人類可讀說明，描述此案例要觀察的 GUI 行為與斷言方式
     */
    public GuiProbeCase(String id, String description) {
        this.id = id;
        this.description = description;
    }

    /** 穩定識別碼；操作清單與測試都依此比對。 */
    public String id() {
        return id;
    }

    /** 人類可讀說明。 */
    public String description() {
        return description;
    }
}
