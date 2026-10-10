package com.smile.acelib.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.AceLibPlugin;
import com.smile.acelib.platform.PlatformDetector;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * 跨欄位驗證測試。
 *
 * <p>鎖住路線圖缺口：「跨欄位驗證的入口。整份設定通過後才發布新快照，
 * 失敗時保留舊快照」。規則讀候選快照的多個路徑（例如下限不得大於上限）；
 * 失敗拋 {@code ACELIB-CFG-007} 並指出失敗的規則，不發布新快照、
 * 不推進世代、不動最後成功副本。</p>
 *
 * <p>後備語意：{@code startup()} 損壞時的最後成功副本／呼叫端後備快照
 * 不重新執行新登記的規則（後備是當時已驗證通過的狀態）；監看重載走
 * {@code reload()} 管線，候選內容仍要過規則。</p>
 */
@DisplayName("ConfigManager 跨欄位驗證（通過發布／失敗保留）")
class ConfigCrossFieldValidationTest {

    private ServerMock server;
    private AceLibPlugin plugin;
    private File dataFolder;
    private ConfigSchema schema;
    private final ConfigVersion currentVersion = new ConfigVersion(1, 0);

    /**
     * 下限不得大於上限。兩個值都存在才檢查（缺值由 schema 預設補齊，
     * 型別錯誤由綁定層負責，這裡只管跨欄位的關係）。
     */
    private static ConfigCrossFieldValidator minNotGreaterThanMax() {
        return candidate -> {
            Object minRaw = candidate.get("limits.min");
            Object maxRaw = candidate.get("limits.max");
            if (minRaw instanceof Number min && maxRaw instanceof Number max
                && min.doubleValue() > max.doubleValue()) {
                throw new ConfigBindingException("limits",
                    "規則 minNotGreaterThanMax 失敗：下限 " + min + " 大於上限 " + max);
            }
        };
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);
        plugin.onEnable(server, new PlatformDetector(getClass().getClassLoader()));
        dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dataFolder.mkdirs();
        }
        schema = new ConfigSchema(
            new ConfigVersion(1, 0),
            List.of(
                new FieldSpec("greeting", "hello", true),
                new FieldSpec("limits.min", 1, false),
                new FieldSpec("limits.max", 10, false)
            )
        );
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("規則通過：發布新快照且世代 +1")
    void crossField_pass_publishesNewSnapshotAndBumpsGeneration() throws Exception {
        Files.writeString(dataFolder.toPath().resolve("cross-pass.yml"),
            "version: '1.0'\ngreeting: 'first'\nlimits:\n  min: 1\n  max: 10\n",
            StandardCharsets.UTF_8);
        ConfigManager mgr = new ConfigManager(plugin, "cross-pass.yml", schema, currentVersion)
            .registerCrossFieldValidator(minNotGreaterThanMax());
        mgr.load();
        ConfigSnapshot before = mgr.snapshot();
        long generation = before.generation();

        Files.writeString(dataFolder.toPath().resolve("cross-pass.yml"),
            "version: '1.0'\ngreeting: 'second'\nlimits:\n  min: 2\n  max: 10\n",
            StandardCharsets.UTF_8);
        assertTrue(mgr.reload());

        assertNotSame(before, mgr.snapshot());
        assertEquals(generation + 1, mgr.snapshot().generation());
        assertEquals("second", mgr.snapshot().getString("greeting", ""));
    }

    @Test
    @DisplayName("規則失敗：reload 回 false，快照實例不變、世代不推進、值保留")
    void crossField_fail_reloadKeepsSnapshotAndGeneration() throws Exception {
        Files.writeString(dataFolder.toPath().resolve("cross-fail.yml"),
            "version: '1.0'\ngreeting: 'running'\nlimits:\n  min: 1\n  max: 10\n",
            StandardCharsets.UTF_8);
        ConfigManager mgr = new ConfigManager(plugin, "cross-fail.yml", schema, currentVersion)
            .registerCrossFieldValidator(minNotGreaterThanMax());
        mgr.load();
        ConfigSnapshot before = mgr.snapshot();
        long generation = before.generation();

        Files.writeString(dataFolder.toPath().resolve("cross-fail.yml"),
            "version: '1.0'\ngreeting: 'broken'\nlimits:\n  min: 20\n  max: 10\n",
            StandardCharsets.UTF_8);
        try (LogCapture logs = LogCapture.attachTo(plugin)) {
            assertTrue(!mgr.reload(), "跨欄位規則失敗時 reload 必須回傳 false");
            assertTrue(logs.hasMessageContaining("ACELIB-CFG-007"),
                "失敗必須帶錯誤代碼可查，實際記錄：" + logs.messages());
            assertTrue(logs.hasMessageContaining("minNotGreaterThanMax"),
                "訊息必須指出失敗的規則，實際記錄：" + logs.messages());
        }

        assertSame(before, mgr.snapshot(), "失敗不得更換快照實例");
        assertEquals(generation, mgr.snapshot().generation(), "失敗不得推進世代");
        assertEquals("running", mgr.snapshot().getString("greeting", ""),
            "失敗必須保留舊值");
    }

    @Test
    @DisplayName("規則失敗：最後成功副本內容不變（仍能還原舊值）")
    void crossField_fail_lastGoodKeepsOldContent() throws Exception {
        File configFile = new File(dataFolder, "cross-lastgood.yml");
        Files.writeString(configFile.toPath(),
            "version: '1.0'\ngreeting: 'running'\nlimits:\n  min: 1\n  max: 10\n",
            StandardCharsets.UTF_8);
        ConfigManager mgr = new ConfigManager(plugin, "cross-lastgood.yml", schema, currentVersion)
            .registerCrossFieldValidator(minNotGreaterThanMax());
        mgr.load();
        String lastGoodBefore = InstallStateStore.readLastGood(configFile.toPath());
        assertTrue(lastGoodBefore.contains("running"));

        Files.writeString(configFile.toPath(),
            "version: '1.0'\ngreeting: 'broken'\nlimits:\n  min: 20\n  max: 10\n",
            StandardCharsets.UTF_8);
        assertTrue(!mgr.reload());

        assertEquals(lastGoodBefore, InstallStateStore.readLastGood(configFile.toPath()),
            "跨欄位失敗不得改動最後成功副本");
    }

    @Test
    @DisplayName("規則失敗且磁碟損壞疊加：load 直接拋 CFG-007 並指出規則")
    void crossField_fail_loadThrowsWithRuleName() throws Exception {
        Files.writeString(dataFolder.toPath().resolve("cross-load.yml"),
            "version: '1.0'\ngreeting: 'broken'\nlimits:\n  min: 20\n  max: 10\n",
            StandardCharsets.UTF_8);
        ConfigManager mgr = new ConfigManager(plugin, "cross-load.yml", schema, currentVersion)
            .registerCrossFieldValidator(minNotGreaterThanMax());

        ConfigBindingException ex = assertThrows(ConfigBindingException.class, mgr::load);

        assertEquals("ACELIB-CFG-007", ex.getCode());
        assertTrue(ex.getMessage().contains("minNotGreaterThanMax"),
            "錯誤必須指出失敗的規則，實際：" + ex.getMessage());
    }

    // -----------------------------------------------------------------
    // 後備語意：損壞時的後備快照不重跑新規則
    // -----------------------------------------------------------------

    /**
     * 監看回呼記錄器（只記診斷，不碰遊戲物件）。
     */
    private static final class RecordingListener implements ConfigChangeListener {
        final List<String> invalidCodes = new CopyOnWriteArrayList<>();
        final List<String> invalidDetails = new CopyOnWriteArrayList<>();

        @Override
        public void onReload(ConfigSnapshot snapshot) {
        }

        @Override
        public void onInvalidReload(String code, String detail) {
            invalidCodes.add(code);
            invalidDetails.add(detail);
        }
    }

    @Test
    @DisplayName("後備語意：startup 損壞時最後成功副本直接採用，不重跑新規則")
    void fallback_startupCorrupt_adoptsLastGoodWithoutRevalidation() throws Exception {
        File configFile = new File(dataFolder, "cross-fallback.yml");
        Files.writeString(configFile.toPath(),
            "version: '1.0'\ngreeting: 'running'\nlimits:\n  min: 1\n  max: 10\n",
            StandardCharsets.UTF_8);
        ConfigManager mgr = new ConfigManager(plugin, "cross-fallback.yml", schema, currentVersion);
        mgr.load();

        // 事後才登記一條「什麼都否決」的規則：若後備路徑重跑規則，
        // 這裡的 startup 就不會是 CORRUPT＋舊快照，而是直接拋錯。
        mgr.registerCrossFieldValidator(candidate -> {
            throw new ConfigBindingException("limits", "規則 alwaysReject 失敗：後備不該跑到這裡");
        });
        Files.writeString(configFile.toPath(), "greeting: [unclosed\n", StandardCharsets.UTF_8);

        StartupResult result = mgr.startup();

        assertEquals(StartupResult.Status.CORRUPT, result.status());
        assertEquals("running", result.snapshot().getString("greeting", ""),
            "後備必須是損壞前的最後成功內容，不被新規則攔截");
        assertTrue(result.detail().contains("ACELIB-CFG-002"),
            "診斷應指向損壞本身，實際：" + result.detail());
    }

    @Test
    @DisplayName("監看重載：外部變更違反跨欄位規則 → 診斷 CFG-007 並保留舊快照")
    void fallback_watcherCrossFieldFail_diagnosesAndKeepsOldSnapshot() throws Exception {
        Files.writeString(dataFolder.toPath().resolve("cross-watch.yml"),
            "version: '1.0'\ngreeting: 'steady'\nlimits:\n  min: 1\n  max: 10\n",
            StandardCharsets.UTF_8);
        ConfigManager mgr = new ConfigManager(plugin, "cross-watch.yml", schema, currentVersion)
            .registerCrossFieldValidator(minNotGreaterThanMax());
        mgr.load();
        RecordingListener listener = new RecordingListener();
        mgr.startWatching(listener);
        ConfigSnapshot before = mgr.snapshot();

        Files.writeString(dataFolder.toPath().resolve("cross-watch.yml"),
            "version: '1.0'\ngreeting: 'changed'\nlimits:\n  min: 20\n  max: 10\n",
            StandardCharsets.UTF_8);
        mgr.pollWatcherOnce();

        assertEquals(1, listener.invalidCodes.size(), "違反規則的外部變更必須診斷一次");
        assertEquals("ACELIB-CFG-007", listener.invalidCodes.get(0));
        assertTrue(listener.invalidDetails.get(0).contains("minNotGreaterThanMax"),
            "診斷必須指出失敗的規則，實際：" + listener.invalidDetails);
        assertSame(before, mgr.snapshot(), "監看重載失敗必須保留舊快照實例");
        assertEquals("steady", mgr.snapshot().getString("greeting", ""));
        mgr.close();
    }
}
