package com.example.acelibmsgprobe;

import net.kyori.adventure.text.Component;

/**
 * 單一相容性探針案例：固定、可重複發送的 Adventure Component 測試樣本。
 *
 * <p>本類別只描述「要發送什麼」，不負責發送；發送由
 * {@link MessageCompatibilityProbePlugin} 經由玩家指令執行。案例內容固定，
 * 不依賴任何線上狀態或隨機值，確保 Java 與 Bedrock 客戶端觀察可重現。</p>
 *
 * <p>每個案例都有穩定的 {@link #id()}，對應
 * docs/reference/bedrock-message-compatibility-matrix.md 矩陣的縱軸；
 * 新增案例時必須同步更新矩陣文件與 {@code CompatibilityCasesTest} 的完整性斷言。</p>
 */
public final class CompatibilityCase {

    private final String id;
    private final String description;
    private final Component component;

    /**
     * @param id          穩定識別碼（矩陣縱軸鍵）
     * @param description 人類可讀說明，描述此案例要觀察的 Component 特性
     * @param component   固定、可重複建構的 Adventure Component
     */
    public CompatibilityCase(String id, String description, Component component) {
        this.id = id;
        this.description = description;
        this.component = component;
    }

    /** 穩定識別碼；矩陣報告與測試都依此比對。 */
    public String id() {
        return id;
    }

    /** 人類可讀說明。 */
    public String description() {
        return description;
    }

    /** 要發送的 Component。 */
    public Component component() {
        return component;
    }
}
