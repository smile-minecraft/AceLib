package com.smile.acelib;

import com.smile.acelib.platform.Platform;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 內部 runtime capability probe（package-private；不屬於 v1 對外契約）。
 *
 * <p>透過 classpath reflection 探測關鍵 API 的 class / method shape，產生
 * {@link ProbeOutcome}；結果交由 {@link CompatibilityGate} 對照已驗證矩陣
 * 分類為 SUPPORTED / UNVERIFIED / INCOMPATIBLE。本類別刻意為 package-private，
 * 避免被 api-surface scanner 視為對外契約（Supported/SPI 簽章 baseline 不會因此變動）。</p>
 *
 * <p>探測策略：使用 {@code Class.forName(fqcn, false, loader)}（只 link 不初始化，
 * 避免 static init 副作用），必要時再 {@code clazz.getMethod(...)} 確認 method shape。
 * 任何探測例外都保守分類為「缺失 / 失敗」，使 gate 能 fail-closed。</p>
 *
 * @see CompatibilityGate
 * @see CompatibilityStatus
 * @since 1.1.2
 */
final class CapabilityProbe {

    /** 受探測的 capability 鍵。 */
    enum CapabilityKey {
        BUKKIT_API,
        GLOBAL_SCHEDULER,
        ADVENTURE_ACTION,
        ADVENTURE_PAYLOAD,
        REGION_SCHEDULER
    }

    /** 單一 capability 的探測結果分類。 */
    enum OutcomeKind {
        PRESENT,
        CLASS_ABSENT,
        METHOD_ABSENT,
        LINKAGE_FAILURE,
        SECURITY_FAILURE
    }

    /** 單一 capability 的探測結果（immutable）。 */
    static final class ProbeOutcome {
        final OutcomeKind kind;
        final String errorCode;
        final String detail;

        private ProbeOutcome(OutcomeKind kind, String errorCode, String detail) {
            this.kind = kind;
            this.errorCode = errorCode;
            this.detail = detail;
        }

        boolean isPresent() {
            return kind == OutcomeKind.PRESENT;
        }

        static ProbeOutcome present() {
            return new ProbeOutcome(OutcomeKind.PRESENT, null, "present");
        }

        static ProbeOutcome classAbsent(String fqcn) {
            return new ProbeOutcome(OutcomeKind.CLASS_ABSENT, "ACELIB-PLAT-005",
                "class absent: " + fqcn);
        }

        static ProbeOutcome methodAbsent(String fqcn, String method) {
            return new ProbeOutcome(OutcomeKind.METHOD_ABSENT, "ACELIB-PLAT-006",
                "method absent: " + fqcn + "#" + method);
        }

        static ProbeOutcome linkageFailure(String fqcn, Throwable t) {
            return new ProbeOutcome(OutcomeKind.LINKAGE_FAILURE, "ACELIB-PLAT-007",
                "linkage failure: " + fqcn + " (" + t + ")");
        }

        static ProbeOutcome securityFailure(String fqcn, Throwable t) {
            return new ProbeOutcome(OutcomeKind.SECURITY_FAILURE, "ACELIB-PLAT-008",
                "security failure: " + fqcn + " (" + t + ")");
        }
    }

    /** 單一探測目標（class + 選用 method）。 */
    private static final class Target {
        final CapabilityKey key;
        final String className;
        final String methodName; // null = 只探測 class 存在
        final String[] paramTypes; // null / 空 = no-arg method

        Target(CapabilityKey key, String className, String methodName, String[] paramTypes) {
            this.key = key;
            this.className = className;
            this.methodName = methodName;
            this.paramTypes = paramTypes;
        }
    }

    // 探測目標：Bukkit API、全域 scheduler、Adventure 訊息發送與 payload、Folia region scheduler。
    // 順序與 CapabilityKey 對應；REGION_SCHEDULER 僅在 Folia 環境為 required（見 requiredKeys）。
    private static final Target[] TARGETS = {
        new Target(CapabilityKey.BUKKIT_API, "org.bukkit.Server", null, null),
        new Target(CapabilityKey.GLOBAL_SCHEDULER, "org.bukkit.Server", "getScheduler", new String[0]),
        new Target(CapabilityKey.ADVENTURE_ACTION, "net.kyori.adventure.audience.Audience",
            "sendMessage", new String[]{"net.kyori.adventure.text.Component"}),
        new Target(CapabilityKey.ADVENTURE_PAYLOAD, "net.kyori.adventure.text.Component", null, null),
        new Target(CapabilityKey.REGION_SCHEDULER, "org.bukkit.Server", "getGlobalRegionScheduler", new String[0]),
    };

    private CapabilityProbe() {
        // utility class
    }

    /**
     * 依平台回傳 required capability 集合。
     *
     * <p>Folia 額外要求 {@link CapabilityKey#REGION_SCHEDULER}；其餘平台只需要
     * Bukkit API / 全域 scheduler / Adventure action + payload。</p>
     */
    static Set<CapabilityKey> requiredKeys(Platform platform) {
        Set<CapabilityKey> keys = EnumSet.noneOf(CapabilityKey.class);
        keys.add(CapabilityKey.BUKKIT_API);
        keys.add(CapabilityKey.GLOBAL_SCHEDULER);
        keys.add(CapabilityKey.ADVENTURE_ACTION);
        keys.add(CapabilityKey.ADVENTURE_PAYLOAD);
        if (platform == Platform.FOLIA) {
            keys.add(CapabilityKey.REGION_SCHEDULER);
        }
        return keys;
    }

    /**
     * 對所有目標執行探測，回傳 {@link CapabilityKey} → {@link ProbeOutcome} 對照。
     *
     * <p>本方法不拋出：任何探測例外都會被 {@link #probeOne(ClassLoader, Target)}
     * 轉為對應的失敗 outcome，使呼叫端可 fail-closed。</p>
     */
    static EnumMap<CapabilityKey, ProbeOutcome> probe(ClassLoader classLoader, Platform platform) {
        EnumMap<CapabilityKey, ProbeOutcome> results = new EnumMap<>(CapabilityKey.class);
        for (Target t : TARGETS) {
            results.put(t.key, probeOne(classLoader, t));
        }
        return results;
    }

    private static ProbeOutcome probeOne(ClassLoader classLoader, Target t) {
        try {
            Class<?> clazz = Class.forName(t.className, false, classLoader);
            if (t.methodName == null) {
                return ProbeOutcome.present();
            }
            Class<?>[] params = new Class<?>[t.paramTypes.length];
            for (int i = 0; i < t.paramTypes.length; i++) {
                params[i] = Class.forName(t.paramTypes[i], false, classLoader);
            }
            clazz.getMethod(t.methodName, params);
            return ProbeOutcome.present();
        } catch (ClassNotFoundException e) {
            return ProbeOutcome.classAbsent(t.className);
        } catch (NoSuchMethodException e) {
            return ProbeOutcome.methodAbsent(t.className, t.methodName);
        } catch (LinkageError e) {
            return ProbeOutcome.linkageFailure(t.className, e);
        } catch (SecurityException e) {
            return ProbeOutcome.securityFailure(t.className, e);
        } catch (Exception e) {
            // 其他反射 / 類別載入異常（含 classloader 內部 NPE 等）一律視為 linkage 失敗，
            // 確保 gate 能 fail-closed 而非冒泡中斷 onEnable。
            return ProbeOutcome.linkageFailure(t.className, e);
        }
    }

    /** 供 {@link CompatibilityGate} 讀取 outcome 的便利方法（null-safe）。 */
    static boolean isPresent(Map<CapabilityKey, ProbeOutcome> outcomes, CapabilityKey key) {
        Objects.requireNonNull(outcomes, "outcomes");
        ProbeOutcome o = outcomes.get(key);
        return o != null && o.isPresent();
    }
}
