package com.smile.acelib;

import com.smile.acelib.platform.Platform;
import java.util.Map;
import java.util.Set;

/**
 * 內部相容性決策 gate（package-private；不屬於 v1 對外契約）。
 *
 * <p>依 {@link RuntimeFingerprint} 與 {@link CapabilityProbe} 結果分類為
 * SUPPORTED / UNVERIFIED / INCOMPATIBLE：</p>
 * <ol>
 *   <li>任一 required capability 缺失 → {@link CompatibilityStatus.State#INCOMPATIBLE}
 *       （fail-closed，理由列出缺失項與其錯誤代碼）</li>
 *   <li>否則若 {@code platform:version} 落在內建已驗證矩陣
 *       （{@code PAPER:26.1.2} / {@code FOLIA:26.1.2}）→ SUPPORTED</li>
 *   <li>否則 → UNVERIFIED（best-effort，輸出 warning）</li>
 * </ol>
 *
 * <p>已驗證矩陣刻意只內建 26.1.2 Paper/Folia：AceLib 的 Folia-safe 路徑與
 * Adventure 整合僅在該版本組合經過驗證；其他版本不假裝 verified，避免
 * 「看起來 ready 但其實沒驗證過」的假象。</p>
 *
 * <p>本型別刻意為 package-private，避免被 api-surface scanner 視為對外契約。</p>
 *
 * @see CompatibilityStatus
 * @see RuntimeFingerprint
 * @since 1.1.2
 */
final class CompatibilityGate {

    /** 內建已驗證 runtime 矩陣（platform:version）。 */
    private static final Set<String> VERIFIED = Set.of("PAPER:26.1.2", "FOLIA:26.1.2");

    private CompatibilityGate() {
        // utility class
    }

    /**
     * 決策相容性狀態。
     *
     * @param fingerprint  runtime fingerprint；不可為 null
     * @param outcomes     capability 探測結果；不可為 null
     * @return 對應的 {@link CompatibilityStatus}（永遠不為 null）
     */
    static CompatibilityStatus decide(RuntimeFingerprint fingerprint,
                                      Map<CapabilityProbe.CapabilityKey, CapabilityProbe.ProbeOutcome> outcomes) {
        if (fingerprint == null) {
            return CompatibilityStatus.incompatible("runtime fingerprint is null", "null");
        }
        Set<CapabilityProbe.CapabilityKey> required = CapabilityProbe.requiredKeys(fingerprint.platform);
        StringBuilder missing = new StringBuilder();
        for (CapabilityProbe.CapabilityKey key : required) {
            CapabilityProbe.ProbeOutcome o = outcomes.get(key);
            if (o == null || !o.isPresent()) {
                if (missing.length() > 0) {
                    missing.append("; ");
                }
                missing.append(key.name()).append('=')
                    .append(o == null ? "null" : (o.errorCode + " " + o.detail));
            }
        }
        if (missing.length() > 0) {
            return CompatibilityStatus.incompatible("required capability missing: " + missing,
                fingerprint.summary());
        }
        String matrixKey = fingerprint.platform.name() + ":" + fingerprint.minecraftVersion;
        if (VERIFIED.contains(matrixKey)) {
            return CompatibilityStatus.supported(fingerprint.summary());
        }
        return CompatibilityStatus.unverified(
            "runtime not in verified matrix (verified=" + VERIFIED + ")", fingerprint.summary());
    }
}
