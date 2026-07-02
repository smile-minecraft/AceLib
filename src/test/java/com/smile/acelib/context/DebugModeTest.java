package com.smile.acelib.context;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.AceLibPlugin;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * {@link DebugMode} 除錯模式測試。
 *
 * <p>對應 Plan §八 Phase 3：除錯模式可輸出額外診斷資訊；支援 config 與 system property
 * 兩種來源；setEnabled 修改當前狀態；getOrCompute 僅計算一次。</p>
 */
@DisplayName("DebugMode")
class DebugModeTest {

    private static final String SYS_PROP = "acelib.debug";
    private static String savedSysProp;
    private ServerMock server;
    private AceLibPlugin plugin;

    @BeforeAll
    static void saveSysProp() {
        savedSysProp = System.getProperty(SYS_PROP);
        System.clearProperty(SYS_PROP);
    }

    @AfterAll
    static void restoreSysProp() {
        if (savedSysProp != null) {
            System.setProperty(SYS_PROP, savedSysProp);
        } else {
            System.clearProperty(SYS_PROP);
        }
    }

    @BeforeEach
    void setUp() {
        // 確保測試環境乾淨
        DebugMode.setEnabled(false);
        server = MockBukkit.mock();
        plugin = (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);
        plugin.onEnable(server, new com.smile.acelib.platform.PlatformDetector(getClass().getClassLoader()));
    }

    @AfterEach
    void tearDown() {
        // 重置全局狀態避免污染其他測試
        DebugMode.setEnabled(false);
        System.clearProperty(SYS_PROP);
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("預設除錯模式為關閉（無 system property、無 plugin config）")
    void default_isDisabled() {
        DebugMode.setEnabled(false);
        System.clearProperty(SYS_PROP);
        assertFalse(DebugMode.isEnabled(),
            "無 config / system property 時，預設為 false");
    }

    @Test
    @DisplayName("system property 'acelib.debug=true' 應啟用除錯模式")
    void systemProperty_true_enables() {
        DebugMode.setEnabled(false);
        System.setProperty(SYS_PROP, "true");
        assertTrue(DebugMode.isEnabled(),
            "system property=true 應啟用除錯模式");
    }

    @Test
    @DisplayName("system property 'acelib.debug=false' 應關閉除錯模式")
    void systemProperty_false_disables() {
        DebugMode.setEnabled(true);
        System.setProperty(SYS_PROP, "false");
        assertFalse(DebugMode.isEnabled());
    }

    @Test
    @DisplayName("setEnabled(true) 應立即啟用除錯模式")
    void setEnabled_changesState() {
        DebugMode.setEnabled(false);
        DebugMode.setEnabled(true);
        assertTrue(DebugMode.isEnabled());

        DebugMode.setEnabled(false);
        assertFalse(DebugMode.isEnabled());
    }

    @Test
    @DisplayName("getOrCompute 僅在第一次呼叫時執行 supplier")
    void getOrCompute_cachesResult() {
        DebugMode.setEnabled(false);
        AtomicBoolean called = new AtomicBoolean(false);
        boolean v1 = DebugMode.getOrCompute(plugin, () -> {
            called.set(true);
            return true;
        });
        boolean v2 = DebugMode.getOrCompute(plugin, () -> {
            called.set(false); // 不應被呼叫
            return false;
        });
        assertTrue(v1);
        assertTrue(v2, "第二次 getOrCompute 應回傳緩存值");
        assertTrue(called.get(), "supplier 至少被呼叫一次");
    }

    @Test
    @DisplayName("除錯模式開關不應影響 plugin 正常操作")
    void debugModeToggle_isSafeToCallRepeatedly() {
        assertDoesNotThrow(() -> {
            for (int i = 0; i < 5; i++) {
                DebugMode.setEnabled(true);
                assertTrue(DebugMode.isEnabled());
                DebugMode.setEnabled(false);
                assertFalse(DebugMode.isEnabled());
            }
        });
    }

    @Test
    @DisplayName("isEnabled 不應回傳 null plugin 相關例外（null-safe）")
    void isEnabled_nullSafe() {
        assertDoesNotThrow(() -> DebugMode.isEnabled(null));
        // null plugin 時應回傳 system property 或預設值
        assertNotNull(DebugMode.isEnabled(null));
    }
}