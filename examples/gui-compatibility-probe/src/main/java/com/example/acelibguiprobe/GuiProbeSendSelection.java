package com.example.acelibguiprobe;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * {@code /gprobe send} 的案例選擇（純函式，可單測、不需伺服器）。
 *
 * <p>選擇規則與 {@code /fprobe} 對齊：</p>
 * <ul>
 *   <li>未給識別碼（null 或空白）→ 回傳整份目錄（順序不變）。</li>
 *   <li>有給識別碼 → 去除前後空白、不分大小寫比對；命中回傳單一案例，
 *       未命中拋 {@link IllegalArgumentException}（訊息含使用者輸入、
 *       可用識別碼清單與 {@code /gprobe list} 提示），呼叫端據此回報且不執行。</li>
 * </ul>
 */
public final class GuiProbeSendSelection {

    private GuiProbeSendSelection() {
    }

    /**
     * 依使用者給的識別碼挑選要執行的案例。
     *
     * @param catalog 目錄；不可為 null
     * @param rawCaseId 使用者輸入的識別碼；null 或空白表示全部
     * @return 要執行的案例清單；never null、never empty（目錄本身為空除外）
     * @throws IllegalArgumentException 識別碼未知
     */
    public static List<GuiProbeCase> selectCases(List<GuiProbeCase> catalog, String rawCaseId) {
        Objects.requireNonNull(catalog, "catalog must not be null");
        if (rawCaseId == null || rawCaseId.isBlank()) {
            return List.copyOf(catalog);
        }
        String normalized = rawCaseId.trim().toLowerCase(Locale.ROOT);
        for (GuiProbeCase c : catalog) {
            if (c.id().equals(normalized)) {
                return List.of(c);
            }
        }
        String available = catalog.stream()
            .map(GuiProbeCase::id)
            .collect(Collectors.joining(", "));
        throw new IllegalArgumentException(
            "未知的案例識別碼：" + rawCaseId.trim()
                + "；可用識別碼：" + available + "（或用 /gprobe list 查看）");
    }
}
