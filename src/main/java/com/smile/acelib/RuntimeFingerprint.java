package com.smile.acelib;

import com.smile.acelib.platform.Platform;
import java.util.Map;
import java.util.Objects;

/**
 * 內部 runtime fingerprint（package-private；不屬於 v1 對外契約）。
 *
 * <p>快照啟用當下的關鍵 runtime 事實：平台、Minecraft 版本、Java 版本，以及
 * 由 {@link CapabilityProbe} 結果推導的 capability shape 布林值。供
 * {@link CompatibilityGate} 對照已驗證矩陣，也供 diagnostics 輸出人類可讀摘要。</p>
 *
 * <p>本型別刻意為 package-private，避免被 api-surface scanner 視為對外契約。</p>
 *
 * @see CompatibilityGate
 * @see CapabilityProbe
 * @since 1.1.2
 */
final class RuntimeFingerprint {

    final Platform platform;
    final String minecraftVersion;
    final String javaVersion;
    final boolean adventureActionShapePresent;
    final boolean adventurePayloadShapePresent;
    final boolean requiredSchedulerMethodShapePresent;
    final boolean regionSchedulerShapePresent;

    private RuntimeFingerprint(Platform platform,
                               String minecraftVersion,
                               String javaVersion,
                               boolean adventureActionShapePresent,
                               boolean adventurePayloadShapePresent,
                               boolean requiredSchedulerMethodShapePresent,
                               boolean regionSchedulerShapePresent) {
        this.platform = platform;
        this.minecraftVersion = minecraftVersion;
        this.javaVersion = javaVersion;
        this.adventureActionShapePresent = adventureActionShapePresent;
        this.adventurePayloadShapePresent = adventurePayloadShapePresent;
        this.requiredSchedulerMethodShapePresent = requiredSchedulerMethodShapePresent;
        this.regionSchedulerShapePresent = regionSchedulerShapePresent;
    }

    /**
     * 從平台與 capability 探測結果建立 fingerprint。
     *
     * @param platform          偵測平台；不可為 null
     * @param minecraftVersion  Minecraft 版本字串；可為 null（內部補 "unknown"）
     * @param javaVersion       Java 版本字串；可為 null（內部補 "unknown"）
     * @param outcomes          capability 探測結果；不可為 null
     */
    static RuntimeFingerprint capture(Platform platform,
                                      String minecraftVersion,
                                      String javaVersion,
                                      Map<CapabilityProbe.CapabilityKey, CapabilityProbe.ProbeOutcome> outcomes) {
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(outcomes, "outcomes");
        return new RuntimeFingerprint(
            platform,
            minecraftVersion == null ? "unknown" : minecraftVersion,
            javaVersion == null ? "unknown" : javaVersion,
            CapabilityProbe.isPresent(outcomes, CapabilityProbe.CapabilityKey.ADVENTURE_ACTION),
            CapabilityProbe.isPresent(outcomes, CapabilityProbe.CapabilityKey.ADVENTURE_PAYLOAD),
            CapabilityProbe.isPresent(outcomes, CapabilityProbe.CapabilityKey.GLOBAL_SCHEDULER),
            CapabilityProbe.isPresent(outcomes, CapabilityProbe.CapabilityKey.REGION_SCHEDULER)
        );
    }

    /** 人類可讀摘要（供 diagnostics / log 使用）。 */
    String summary() {
        return "platform=" + platform.name()
            + ";mc=" + minecraftVersion
            + ";java=" + javaVersion
            + ";adventureAction=" + adventureActionShapePresent
            + ";adventurePayload=" + adventurePayloadShapePresent
            + ";globalScheduler=" + requiredSchedulerMethodShapePresent
            + ";regionScheduler=" + regionSchedulerShapePresent;
    }
}
