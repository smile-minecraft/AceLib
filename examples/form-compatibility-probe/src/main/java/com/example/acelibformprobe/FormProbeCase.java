package com.example.acelibformprobe;

import com.smile.acelib.form.FormSpec;
import java.util.function.Supplier;

/**
 * 單一表單相容性探針案例：固定、可重複建構的 {@link FormSpec} 測試樣本。
 *
 * <p>本類別只描述「要發送什麼」，不負責發送；發送由
 * {@link FormCompatibilityProbePlugin} 經由玩家指令、透過 AceLib
 * {@code FormService} 執行。案例內容固定，不依賴任何線上狀態或隨機值，
 * 確保 Bedrock 客戶端觀察可重現。</p>
 *
 * <p>每個案例都有穩定的 {@link #id()}，對應
 * docs/reference/bedrock-form-compatibility-matrix.md 矩陣的縱軸；
 * 新增案例時必須同步更新矩陣文件與 {@code FormProbeCasesTest} 的完整性斷言。</p>
 *
 * <p>規格以 factory（{@link Supplier}）承載而非預建實例：每次呼叫
 * {@link #buildSpec()} 都重新走一次建構路徑（含 {@code FormText.render}），
 * 因此單元測試能驗證「案例可重複建構」而不只是「某次建構成功」。</p>
 */
public final class FormProbeCase {

    private final String id;
    private final String description;
    private final Supplier<FormSpec> specFactory;

    /**
     * @param id          穩定識別碼（矩陣縱軸鍵）
     * @param description 人類可讀說明，描述此案例要觀察的表單特性
     * @param specFactory 表單規格工廠；每次呼叫回傳一個合法、可發送的 {@link FormSpec}
     */
    public FormProbeCase(String id, String description, Supplier<FormSpec> specFactory) {
        this.id = id;
        this.description = description;
        this.specFactory = specFactory;
    }

    /** 穩定識別碼；矩陣報告與測試都依此比對。 */
    public String id() {
        return id;
    }

    /** 人類可讀說明。 */
    public String description() {
        return description;
    }

    /**
     * 建構本次要發送的表單規格。
     *
     * @return 合法的 {@link FormSpec}；never null
     */
    public FormSpec buildSpec() {
        return specFactory.get();
    }
}
