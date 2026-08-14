package com.smile.acelib.diagnostics;

import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 不可變診斷快照（immutable record）。
 *
 * <p>由 {@link DiagnosticsService#buildSnapshot()} 建立，承載當下的
 * 版本、平台、capability、ready／debug、模組狀態、錯誤摘要、節流統計等資訊。</p>
 *
 * <h2>不可變性</h2>
 * <ul>
 *   <li>所有 list 與 map 欄位於建構時 wrap 為不可變視圖</li>
 *   <li>{@link #timestamp()} 為 {@link Instant} 推導值（不可寫入）</li>
 *   <li>{@link #modules()} 嘗試修改會拋 {@link UnsupportedOperationException}</li>
 * </ul>
 *
 * @param timestampMillis  建立快照的 epoch millis
 * @param version          對外版本字串；不可為 null
 * @param platform         對應平台；不可為 null
 * @param capability       對應 capability；不可為 null
 * @param ready            plugin 自身生命週期旗標
 * @param debugEnabled     是否啟用 debug 模式
 * @param modules          模組狀態映射（不可變視圖；不可為 null）
 * @param recentErrors     錯誤摘要清單（不可變；不可為 null）
 * @param throttleSnapshot 節流統計映射（不可變視圖；不可為 null）
 * @see DiagnosticsService
 * @see DiagnosticReport
 * @since 1.0.0
 */
public record DiagnosticSnapshot(
    long timestampMillis,
    String version,
    Platform platform,
    PlatformCapability capability,
    boolean ready,
    boolean debugEnabled,
    Map<String, ModuleState> modules,
    List<ErrorSummaryLine> recentErrors,
    Map<String, ThrottleStats> throttleSnapshot
) {

    /**
     * Compact constructor：對不可空欄位做 null 檢查、集合防禦性 wrap。
     *
     * @throws NullPointerException 當必要欄位為 null
     */
    public DiagnosticSnapshot {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(modules, "modules");
        Objects.requireNonNull(recentErrors, "recentErrors");
        Objects.requireNonNull(throttleSnapshot, "throttleSnapshot");
        // 防禦性 copy + 不可變 wrap
        modules = Collections.unmodifiableMap(new LinkedHashMap<>(modules));
        recentErrors = List.copyOf(recentErrors);
        throttleSnapshot = Collections.unmodifiableMap(new LinkedHashMap<>(throttleSnapshot));
    }

    /**
     * 將 {@link #timestampMillis} 轉為 {@link Instant}。
     *
     * @return 對應的 {@link Instant}
     */
    public Instant timestamp() {
        return Instant.ofEpochMilli(timestampMillis);
    }

    /**
     * 是否已 ready（plugin 完成的標記）。
     *
     * <p>語意同 {@link #ready()}；提供 {@code isReady()} 為對外讀取的
     * 慣用 getter 名稱（與 {@link java.util.function.BooleanSupplier} 等
     * 命名風格一致）。</p>
     *
     * @return ready 旗標
     */
    public boolean isReady() {
        return ready;
    }

    /**
     * 是否啟用 debug 模式（建立快照當下的狀態）。
     *
     * @return debug 旗標
     */
    public boolean isDebugEnabled() {
        return debugEnabled;
    }

    /**
     * 建立 {@link Builder}，供流式組裝。
     *
     * @return 新的 {@link Builder} 實例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 診斷快照的流式建構器。
     *
     * <p>必要欄位：version；其他欄位有合理預設。</p>
     */
    public static final class Builder {
        private long timestampMillis;
        private String version;
        private Platform platform;
        private PlatformCapability capability;
        private boolean ready;
        private boolean debugEnabled;
        private Map<String, ModuleState> modules = new LinkedHashMap<>();
        private List<ErrorSummaryLine> recentErrors = List.of();
        private Map<String, ThrottleStats> throttleSnapshot = new LinkedHashMap<>();

        private Builder() {
            // package-private constructor
        }

        public Builder timestampMillis(long millis) {
            this.timestampMillis = millis;
            return this;
        }

        public Builder version(String version) {
            this.version = version;
            return this;
        }

        public Builder platform(Platform platform) {
            this.platform = platform;
            return this;
        }

        public Builder capability(PlatformCapability capability) {
            this.capability = capability;
            return this;
        }

        public Builder ready(boolean ready) {
            this.ready = ready;
            return this;
        }

        public Builder debugEnabled(boolean debug) {
            this.debugEnabled = debug;
            return this;
        }

        public Builder modules(Map<String, ModuleState> modules) {
            this.modules = modules != null ? new LinkedHashMap<>(modules) : new LinkedHashMap<>();
            return this;
        }

        public Builder recentErrors(List<ErrorSummaryLine> errors) {
            this.recentErrors = errors != null ? List.copyOf(errors) : List.of();
            return this;
        }

        public Builder throttleSnapshot(Map<String, ThrottleStats> stats) {
            this.throttleSnapshot = stats != null ? new LinkedHashMap<>(stats) : new LinkedHashMap<>();
            return this;
        }

        /**
         * 將 {@link #throttleSnapshot} 與既有值合併（既有優先）。
         */
        public Builder putThrottleStat(String code, ThrottleStats stat) {
            if (code != null && stat != null) {
                this.throttleSnapshot.put(code, stat);
            }
            return this;
        }

        /**
         * 註冊單一模組。
         */
        public Builder putModule(String name, ModuleState state) {
            if (name != null && state != null) {
                this.modules.put(name, state);
            }
            return this;
        }

        /**
         * 組裝為 {@link DiagnosticSnapshot}。
         *
         * @return 不可變的快照
         * @throws NullPointerException 必要欄位為 null 時
         */
        public DiagnosticSnapshot build() {
            if (capability == null && platform != null) {
                capability = PlatformCapability.forPlatform(platform);
            }
            if (platform == null) {
                platform = Platform.UNKNOWN;
            }
            if (capability == null) {
                capability = PlatformCapability.forPlatform(Platform.UNKNOWN);
            }
            return new DiagnosticSnapshot(
                timestampMillis,
                version,
                platform,
                capability,
                ready,
                debugEnabled,
                modules,
                recentErrors,
                throttleSnapshot
            );
        }
    }
}
