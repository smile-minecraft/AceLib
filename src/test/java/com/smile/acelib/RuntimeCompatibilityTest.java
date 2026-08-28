package com.smile.acelib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.platform.Platform;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link CompatibilityGate} 與 {@link CapabilityProbe} 的純單元測試（不依賴 MockBukkit）。
 *
 * <p>覆蓋 TDD Requirements 第 5 節 (a)-(f)：</p>
 * <ul>
 *   <li>(a) 已驗證 26.1.2 profile → SUPPORTED</li>
 *   <li>(b) 未知未來版本但 required capabilities 完整 → UNVERIFIED</li>
 *   <li>(c) 缺必要 scheduler method/class → INCOMPATIBLE</li>
 *   <li>(d) Folia marker 存在但 region scheduler shape 矛盾 → INCOMPATIBLE</li>
 *   <li>(e) probe 拋 LinkageError → 分類 LINKAGE_FAILURE (PLAT-007) 且 gate → INCOMPATIBLE</li>
 *   <li>(f) probe 拋 SecurityException → 分類 SECURITY_FAILURE (PLAT-008)</li>
 * </ul>
 *
 * <p>probe 的 class / method 缺失分類（PLAT-005 / PLAT-006）亦一併驗證。</p>
 */
@DisplayName("Runtime compatibility gate")
class RuntimeCompatibilityTest {

    private static EnumMap<CapabilityProbe.CapabilityKey, CapabilityProbe.ProbeOutcome> allPresent() {
        EnumMap<CapabilityProbe.CapabilityKey, CapabilityProbe.ProbeOutcome> m =
            new EnumMap<>(CapabilityProbe.CapabilityKey.class);
        for (CapabilityProbe.CapabilityKey k : CapabilityProbe.CapabilityKey.values()) {
            m.put(k, CapabilityProbe.ProbeOutcome.present());
        }
        return m;
    }

    private static EnumMap<CapabilityProbe.CapabilityKey, CapabilityProbe.ProbeOutcome> copy(
            Map<CapabilityProbe.CapabilityKey, CapabilityProbe.ProbeOutcome> src) {
        return new EnumMap<>(src);
    }

    // ---------------------------------------------------------------------
    // (a) 已驗證 26.1.2 profile → SUPPORTED
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("(a) PAPER 26.1.2 + 完整 capability → SUPPORTED 且 ready")
    void verifiedProfile_isSupported() {
        EnumMap<CapabilityProbe.CapabilityKey, CapabilityProbe.ProbeOutcome> outcomes = allPresent();
        RuntimeFingerprint fp = RuntimeFingerprint.capture(Platform.PAPER, "26.1.2", "21", outcomes);
        CompatibilityStatus st = CompatibilityGate.decide(fp, outcomes);
        assertEquals(CompatibilityStatus.State.SUPPORTED, st.state);
        assertTrue(st.isReady());
    }

    @Test
    @DisplayName("(a) FOLIA 26.1.2 + 完整 capability → SUPPORTED 且 ready")
    void verifiedFoliaProfile_isSupported() {
        EnumMap<CapabilityProbe.CapabilityKey, CapabilityProbe.ProbeOutcome> outcomes = allPresent();
        RuntimeFingerprint fp = RuntimeFingerprint.capture(Platform.FOLIA, "26.1.2", "21", outcomes);
        CompatibilityStatus st = CompatibilityGate.decide(fp, outcomes);
        assertEquals(CompatibilityStatus.State.SUPPORTED, st.state);
        assertTrue(st.isReady());
    }

    // ---------------------------------------------------------------------
    // (b) 未知未來版本但 required capabilities 完整 → UNVERIFIED
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("(b) PAPER 未知版本 + 完整 capability → UNVERIFIED 但仍 ready")
    void unknownVersion_isUnverified() {
        EnumMap<CapabilityProbe.CapabilityKey, CapabilityProbe.ProbeOutcome> outcomes = allPresent();
        RuntimeFingerprint fp = RuntimeFingerprint.capture(Platform.PAPER, "1.21.99", "21", outcomes);
        CompatibilityStatus st = CompatibilityGate.decide(fp, outcomes);
        assertEquals(CompatibilityStatus.State.UNVERIFIED, st.state);
        assertTrue(st.isReady());
        assertTrue(st.reason.contains("verified matrix"));
    }

    // ---------------------------------------------------------------------
    // (c) 缺必要 scheduler method/class → INCOMPATIBLE
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("(c) 缺 GLOBAL_SCHEDULER method shape → INCOMPATIBLE 且 not ready")
    void missingSchedulerMethod_isIncompatible() {
        EnumMap<CapabilityProbe.CapabilityKey, CapabilityProbe.ProbeOutcome> outcomes = allPresent();
        outcomes.put(CapabilityProbe.CapabilityKey.GLOBAL_SCHEDULER,
            CapabilityProbe.ProbeOutcome.methodAbsent("org.bukkit.Server", "getScheduler"));
        RuntimeFingerprint fp = RuntimeFingerprint.capture(Platform.PAPER, "26.1.2", "21", outcomes);
        CompatibilityStatus st = CompatibilityGate.decide(fp, outcomes);
        assertEquals(CompatibilityStatus.State.INCOMPATIBLE, st.state);
        assertFalse(st.isReady());
        assertTrue(st.reason.contains("GLOBAL_SCHEDULER"));
    }

    @Test
    @DisplayName("(c) 缺 BUKKIT_API class → INCOMPATIBLE")
    void missingBukkitApiClass_isIncompatible() {
        EnumMap<CapabilityProbe.CapabilityKey, CapabilityProbe.ProbeOutcome> outcomes = allPresent();
        outcomes.put(CapabilityProbe.CapabilityKey.BUKKIT_API,
            CapabilityProbe.ProbeOutcome.classAbsent("org.bukkit.Server"));
        RuntimeFingerprint fp = RuntimeFingerprint.capture(Platform.PAPER, "26.1.2", "21", outcomes);
        CompatibilityStatus st = CompatibilityGate.decide(fp, outcomes);
        assertEquals(CompatibilityStatus.State.INCOMPATIBLE, st.state);
        assertFalse(st.isReady());
    }

    // ---------------------------------------------------------------------
    // (d) Folia marker 存在但 region scheduler shape 矛盾 → INCOMPATIBLE
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("(d) FOLIA 但 REGION_SCHEDULER 缺失 → INCOMPATIBLE")
    void foliaMissingRegionScheduler_isIncompatible() {
        EnumMap<CapabilityProbe.CapabilityKey, CapabilityProbe.ProbeOutcome> outcomes = allPresent();
        outcomes.put(CapabilityProbe.CapabilityKey.REGION_SCHEDULER,
            CapabilityProbe.ProbeOutcome.classAbsent("org.bukkit.Server"));
        RuntimeFingerprint fp = RuntimeFingerprint.capture(Platform.FOLIA, "26.1.2", "21", outcomes);
        CompatibilityStatus st = CompatibilityGate.decide(fp, outcomes);
        assertEquals(CompatibilityStatus.State.INCOMPATIBLE, st.state);
        assertFalse(st.isReady());
        assertTrue(st.reason.contains("REGION_SCHEDULER"));
    }

    // ---------------------------------------------------------------------
    // (e) probe 拋 LinkageError → LINKAGE_FAILURE (PLAT-007) 且 gate → INCOMPATIBLE
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("(e) LinkageError 探測 → LINKAGE_FAILURE (PLAT-007) 且 INCOMPATIBLE")
    void linkageError_isClassifiedAndIncompatible() {
        EnumMap<CapabilityProbe.CapabilityKey, CapabilityProbe.ProbeOutcome> outcomes =
            CapabilityProbe.probe(new ControlledClassLoader(ControlledClassLoader.Mode.LINKAGE), Platform.PAPER);
        for (CapabilityProbe.CapabilityKey k : CapabilityProbe.requiredKeys(Platform.PAPER)) {
            CapabilityProbe.ProbeOutcome o = outcomes.get(k);
            assertNotNull(o);
            assertEquals(CapabilityProbe.OutcomeKind.LINKAGE_FAILURE, o.kind);
            assertEquals("ACELIB-PLAT-007", o.errorCode);
        }
        RuntimeFingerprint fp = RuntimeFingerprint.capture(Platform.PAPER, "26.1.2", "21", outcomes);
        CompatibilityStatus st = CompatibilityGate.decide(fp, outcomes);
        assertEquals(CompatibilityStatus.State.INCOMPATIBLE, st.state);
    }

    // ---------------------------------------------------------------------
    // (f) probe 拋 SecurityException → SECURITY_FAILURE (PLAT-008)
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("(f) SecurityException 探測 → SECURITY_FAILURE (PLAT-008) 且 INCOMPATIBLE")
    void securityException_isClassifiedAndIncompatible() {
        EnumMap<CapabilityProbe.CapabilityKey, CapabilityProbe.ProbeOutcome> outcomes =
            CapabilityProbe.probe(new ControlledClassLoader(ControlledClassLoader.Mode.SECURITY), Platform.PAPER);
        for (CapabilityProbe.CapabilityKey k : CapabilityProbe.requiredKeys(Platform.PAPER)) {
            CapabilityProbe.ProbeOutcome o = outcomes.get(k);
            assertNotNull(o);
            assertEquals(CapabilityProbe.OutcomeKind.SECURITY_FAILURE, o.kind);
            assertEquals("ACELIB-PLAT-008", o.errorCode);
        }
        RuntimeFingerprint fp = RuntimeFingerprint.capture(Platform.PAPER, "26.1.2", "21", outcomes);
        CompatibilityStatus st = CompatibilityGate.decide(fp, outcomes);
        assertEquals(CompatibilityStatus.State.INCOMPATIBLE, st.state);
    }

    // ---------------------------------------------------------------------
    // probe class / method 缺失分類（PLAT-005 / PLAT-006）
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("probe CLASS_ABSENT → PLAT-005")
    void probeClassAbsent_isPlat005() {
        EnumMap<CapabilityProbe.CapabilityKey, CapabilityProbe.ProbeOutcome> outcomes =
            CapabilityProbe.probe(new ControlledClassLoader(ControlledClassLoader.Mode.CLASS_ABSENT), Platform.PAPER);
        CapabilityProbe.ProbeOutcome o = outcomes.get(CapabilityProbe.CapabilityKey.BUKKIT_API);
        assertEquals(CapabilityProbe.OutcomeKind.CLASS_ABSENT, o.kind);
        assertEquals("ACELIB-PLAT-005", o.errorCode);
    }

    @Test
    @DisplayName("probe METHOD_ABSENT → PLAT-006")
    void probeMethodAbsent_isPlat006() {
        EnumMap<CapabilityProbe.CapabilityKey, CapabilityProbe.ProbeOutcome> outcomes =
            CapabilityProbe.probe(new ControlledClassLoader(ControlledClassLoader.Mode.METHOD_ABSENT), Platform.PAPER);
        CapabilityProbe.ProbeOutcome o = outcomes.get(CapabilityProbe.CapabilityKey.GLOBAL_SCHEDULER);
        assertEquals(CapabilityProbe.OutcomeKind.METHOD_ABSENT, o.kind);
        assertEquals("ACELIB-PLAT-006", o.errorCode);
    }

    // ---------------------------------------------------------------------
    // RuntimeFingerprint 不變性 / 不持有 runtime service object
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("RuntimeFingerprint 欄位皆為 primitive / String / Platform，且 summary 可讀")
    void fingerprint_isStableAndPrimitive() {
        EnumMap<CapabilityProbe.CapabilityKey, CapabilityProbe.ProbeOutcome> outcomes = allPresent();
        RuntimeFingerprint fp = RuntimeFingerprint.capture(Platform.PAPER, "26.1.2", "21", outcomes);
        // 不持有任何 Server / Plugin / ClassLoader 參考：欄位型別皆為安全型別
        assertEquals(Platform.PAPER, fp.platform);
        assertEquals("26.1.2", fp.minecraftVersion);
        assertEquals("21", fp.javaVersion);
        assertTrue(fp.summary().contains("platform=PAPER"));
        assertTrue(fp.summary().contains("globalScheduler=true"));
    }

    @Test
    @DisplayName("RuntimeFingerprint.capture 拒絕 null outcomes（fail-closed 防禦）")
    void fingerprint_rejectsNullOutcomes() {
        assertThrows(NullPointerException.class,
            () -> RuntimeFingerprint.capture(Platform.PAPER, "26.1.2", "21", null));
    }

    // ---------------------------------------------------------------------
    // 受控 classloader：用於驗證 probe 的例外分類，不依賴真實 classpath。
    // ---------------------------------------------------------------------
    static final class ControlledClassLoader extends ClassLoader {
        enum Mode { CLASS_ABSENT, METHOD_ABSENT, LINKAGE, SECURITY }

        private final Mode mode;
        private final ConcurrentHashMap<String, Class<?>> defined = new ConcurrentHashMap<>();

        ControlledClassLoader(Mode mode) {
            super(ControlledClassLoader.class.getClassLoader());
            this.mode = mode;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("org.bukkit.") || name.startsWith("net.kyori.")) {
                switch (mode) {
                    case CLASS_ABSENT:
                        throw new ClassNotFoundException(name);
                    case LINKAGE:
                        throw new NoClassDefFoundError("linkage failure: " + name);
                    case SECURITY:
                        throw new SecurityException("security failure: " + name);
                    case METHOD_ABSENT:
                        // 定義一個與目標同名、但缺少所需 method 的空 class：
                        // class 探測通過，但 getMethod 必然拋 NoSuchMethodException → METHOD_ABSENT。
                        return defineEmptyClass(name, resolve);
                    default:
                        break;
                }
            }
            return super.loadClass(name, resolve);
        }

        private Class<?> defineEmptyClass(String name, boolean resolve) {
            Class<?> cached = defined.get(name);
            if (cached != null) {
                return cached;
            }
            byte[] bytes = emptyClassBytes(name.replace('.', '/'));
            Class<?> c = defineClass(name, bytes, 0, bytes.length);
            if (resolve) {
                resolveClass(c);
            }
            Class<?> prev = defined.putIfAbsent(name, c);
            return prev != null ? prev : c;
        }

        private static byte[] emptyClassBytes(String internalName) {
            byte[] nameUtf = internalName.getBytes(StandardCharsets.UTF_8);
            byte[] objUtf = "java/lang/Object".getBytes(StandardCharsets.UTF_8);
            try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
                 DataOutputStream d = new DataOutputStream(bos)) {
                d.writeInt(0xCAFEBABE);
                d.writeShort(0);          // minor
                d.writeShort(52);         // major (Java 8)
                d.writeShort(5);          // constant_pool_count = 5 (entries 1..4)
                d.writeByte(7); d.writeShort(2);                       // #1 Class -> #2
                d.writeByte(1); d.writeShort(nameUtf.length); d.write(nameUtf);  // #2 Utf8 name
                d.writeByte(7); d.writeShort(4);                       // #3 Class -> #4
                d.writeByte(1); d.writeShort(objUtf.length); d.write(objUtf);    // #4 Utf8 java/lang/Object
                d.writeShort(0x0021);     // access_flags: public super
                d.writeShort(1);          // this_class #1
                d.writeShort(3);          // super_class #3
                d.writeShort(0);          // interfaces_count
                d.writeShort(0);          // fields_count
                d.writeShort(0);          // methods_count
                d.writeShort(0);          // attributes_count
                return bos.toByteArray();
            } catch (java.io.IOException e) {
                throw new IllegalStateException("failed to build empty class", e);
            }
        }
    }
}
