package com.example.acelibcmdprobe;

/**
 * 探針案例型別：固定、可重複的單一實測步驟。
 *
 * <p>案例只描述「要做什麼觀察」，不自行執行；執行與回報由
 * {@link CommandCompatibilityProbePlugin} 依案例 id 分派，確保同一組案例
 * 在 Paper 與 Folia 上跑出可比較的結果。</p>
 *
 * @param id 案例識別字（穩定；主代理與維護者以此回報觀察結果）
 * @param group 案例分組（解析／錯誤／補全／生命週期）
 * @param input 要輸入的指令字串（相對於根指令）
 * @param expectation 預期觀察（供人工對照，不是斷言）
 */
public record ProbeCase(String id, String group, String input, String expectation) {
}