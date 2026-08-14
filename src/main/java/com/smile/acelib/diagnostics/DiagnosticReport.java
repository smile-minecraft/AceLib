package com.smile.acelib.diagnostics;

import com.smile.acelib.platform.Platform;
import java.util.Map;
import java.util.Objects;

/**
 * 診斷報告（immutable）。
 *
 * <p>將 {@link DiagnosticSnapshot} 格式化為人類可讀字串；
 * debug 模式增加「throttle 統計」、「capability 細節」等額外區塊。</p>
 *
 * <h2>輸出區塊（非 debug）</h2>
 * <ol>
 *   <li>標題列與版本／平台／capability／ready／debug</li>
 *   <li>Modules（scheduler / config / lang / integration / data 預設 5 項）</li>
 *   <li>Errors（依 code 合併的 summary）</li>
 * </ol>
 *
 * <h2>debug 額外區塊</h2>
 * <ul>
 *   <li>Capability 細節（4 個 boolean）</li>
 *   <li>Throttle 統計（每個 code 的 allowed / suppressed / windowMs）</li>
 * </ul>
 *
 * <h2>不可變性</h2>
 * <ul>
 *   <li>{@link #snapshot()} 永遠回傳同一物件</li>
 *   <li>同一 {@link DiagnosticReport} 多次呼叫 {@link #format(boolean)} 結果一致</li>
 * </ul>
 *
 * @see DiagnosticSnapshot
 * @since 1.0.0
 */
public final class DiagnosticReport {

    private final DiagnosticSnapshot snapshot;

    private DiagnosticReport(DiagnosticSnapshot snapshot) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    /**
     * 由快照建立不可變報告。
     *
     * @param snapshot 來源快照；不可為 null
     * @return 新的 {@link DiagnosticReport}
     */
    public static DiagnosticReport from(DiagnosticSnapshot snapshot) {
        return new DiagnosticReport(snapshot);
    }

    /**
     * 取得來源快照。
     *
     * @return 當初傳入的快照；永遠不為 null
     */
    public DiagnosticSnapshot snapshot() {
        return snapshot;
    }

    /**
     * 格式化為人類可讀字串。
     *
     * @param debug true 則附加 throttle／capability 細節
     * @return 多行報告字串
     */
    public String format(boolean debug) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("=== AceLib Diagnostics Report ===\n");
        sb.append("Version: ").append(snapshot.version()).append('\n');
        sb.append("Platform: ").append(snapshot.platform().getDisplayName()).append('\n');
        sb.append("Capability: ")
            .append("globalScheduler=").append(snapshot.capability().globalScheduler())
            .append(", bukkitApi=").append(snapshot.capability().bukkitApi())
            .append('\n');
        sb.append("Ready: ").append(snapshot.ready() ? "true" : "false").append('\n');
        sb.append("debug: ").append(debug ? "on" : "off").append('\n');
        sb.append("Timestamp: ").append(snapshot.timestampMillis()).append('\n');

        sb.append('\n').append("Modules:\n");
        Map<String, ModuleState> modules = snapshot.modules();
        if (modules.isEmpty()) {
            sb.append("  (no modules registered)\n");
        } else {
            for (Map.Entry<String, ModuleState> entry : modules.entrySet()) {
                ModuleState ms = entry.getValue();
                sb.append("  ").append(ms.name()).append(": ")
                    .append(ms.status().name())
                    .append(" - ").append(ms.detail());
                ms.errorCode().ifPresent(code -> sb.append(" [").append(code).append(']'));
                sb.append('\n');
            }
        }

        sb.append('\n').append("Errors:\n");
        if (snapshot.recentErrors().isEmpty()) {
            sb.append("  (no errors)\n");
        } else {
            for (ErrorSummaryLine line : snapshot.recentErrors()) {
                sb.append("  - ").append(line.code())
                    .append(" [").append(line.category().name()).append("] x")
                    .append(line.count()).append(' ')
                    .append(line.detail()).append('\n');
            }
        }

        if (debug) {
            sb.append('\n').append("Capability details:\n");
            sb.append("  regionScheduling=").append(snapshot.capability().regionScheduling())
                .append(", globalScheduler=").append(snapshot.capability().globalScheduler())
                .append(", bukkitApi=").append(snapshot.capability().bukkitApi())
                .append(", foliaThreadedRegionsApi=").append(snapshot.capability().foliaThreadedRegionsApi())
                .append('\n');

            sb.append('\n').append("throttle stats:\n");
            Map<String, ThrottleStats> stats = snapshot.throttleSnapshot();
            if (stats.isEmpty()) {
                sb.append("  (no throttle stats)\n");
            } else {
                for (Map.Entry<String, ThrottleStats> entry : stats.entrySet()) {
                    ThrottleStats s = entry.getValue();
                    sb.append("  ").append(entry.getKey())
                        .append(": allowed=").append(s.allowed())
                        .append(", suppressed=").append(s.suppressed())
                        .append(", windowMs=").append(s.windowMs())
                        .append('\n');
                }
            }
        }

        return sb.toString();
    }

    /**
     * 預設使用 {@link #format(boolean) format(false)}。
     */
    @Override
    public String toString() {
        return format(false);
    }
}
