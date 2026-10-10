package com.smile.acelib.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.AceLibPlugin;
import com.smile.acelib.platform.PlatformDetector;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * 缺檔還原前攔截測試。
 *
 * <p>鎖住路線圖缺口：「曾經成功使用後缺檔」在還原最後成功副本之前，
 * 下游可選擇失敗而非靜默還原。預設（未登記）維持還原；拒絕時不動磁碟、
 * 不發布快照、不推進世代。首次安裝不觸發；CFG-006 保護不受影響。</p>
 */
@DisplayName("ConfigManager 缺檔還原前攔截（預設還原／拒絕失敗）")
class ConfigMissingFileHandlerTest {

    private static final String REFUSE_CODE = "ACELIB-EXT-001";
    private static final String REFUSE_MESSAGE = "downstream refuses missing-file restore";

    private ServerMock server;
    private AceLibPlugin plugin;
    private File dataFolder;
    private ConfigSchema schema;
    private final ConfigVersion currentVersion = new ConfigVersion(1, 0);

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
                new FieldSpec("maxPlayers", 10, false)
            )
        );
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static ConfigMissingFileHandler refusingHandler() {
        return missing -> {
            throw new ConfigException(REFUSE_CODE, REFUSE_MESSAGE);
        };
    }

    @Test
    @DisplayName("未登記 handler：使用後缺檔照舊以最後成功副本還原（回歸）")
    void missingAfterUse_withoutHandler_restoresLastGood() throws Exception {
        Path file = dataFolder.toPath().resolve("intercept-regression.yml");
        Files.writeString(file,
            "version: '1.0'\ngreeting: 'kept-value'\n", StandardCharsets.UTF_8);

        ConfigManager first = new ConfigManager(plugin, "intercept-regression.yml", schema, currentVersion);
        assertEquals(StartupResult.Status.LOADED, first.startup().status());
        assertTrue(new File(dataFolder, "intercept-regression.yml").delete());

        ConfigManager second = new ConfigManager(plugin, "intercept-regression.yml", schema, currentVersion);
        StartupResult result = second.startup();

        assertEquals(StartupResult.Status.MISSING_AFTER_USE, result.status());
        assertEquals("kept-value", result.snapshot().getString("greeting", null));
        assertEquals("kept-value",
            YamlConfiguration.loadConfiguration(file.toFile()).getString("greeting"),
            "預設行為必須重建檔案並回到上次能跑的值");
    }

    @Test
    @DisplayName("handler 正常回傳：照現行流程還原，且 handler 確實被呼叫一次")
    void missingAfterUse_allowingHandler_restoresLastGood() throws Exception {
        Path file = dataFolder.toPath().resolve("intercept-allow.yml");
        Files.writeString(file,
            "version: '1.0'\ngreeting: 'kept-value'\n", StandardCharsets.UTF_8);

        ConfigManager first = new ConfigManager(plugin, "intercept-allow.yml", schema, currentVersion);
        assertEquals(StartupResult.Status.LOADED, first.startup().status());
        assertTrue(new File(dataFolder, "intercept-allow.yml").delete());

        AtomicInteger calls = new AtomicInteger();
        ConfigManager second = new ConfigManager(plugin, "intercept-allow.yml", schema, currentVersion)
            .registerMissingFileHandler(missing -> calls.incrementAndGet());
        StartupResult result = second.startup();

        assertEquals(1, calls.get(), "使用後缺檔必須觸發 handler");
        assertEquals(StartupResult.Status.MISSING_AFTER_USE, result.status());
        assertEquals("kept-value", result.snapshot().getString("greeting", null));
        assertTrue(Files.isRegularFile(file), "允許時必須照現行流程重建檔案");
    }

    @Test
    @DisplayName("handler 拒絕：startup 回 MISSING_AFTER_USE，不建檔、快照為 null、診斷帶下游錯誤碼")
    void missingAfterUse_refusingHandler_startupFailsWithoutTouchingDisk() throws Exception {
        ConfigManager first = new ConfigManager(plugin, "intercept-refuse.yml", schema, currentVersion);
        assertEquals(StartupResult.Status.FRESH_INSTALL, first.startup().status());
        assertTrue(new File(dataFolder, "intercept-refuse.yml").delete());

        ConfigManager second = new ConfigManager(plugin, "intercept-refuse.yml", schema, currentVersion)
            .registerMissingFileHandler(refusingHandler());
        StartupResult result = second.startup();

        assertEquals(StartupResult.Status.MISSING_AFTER_USE, result.status());
        assertTrue(result.detail().contains(REFUSE_CODE),
            "拒絕診斷必須帶下游錯誤碼，實際：" + result.detail());
        assertTrue(result.detail().contains(REFUSE_MESSAGE),
            "拒絕診斷必須帶下游訊息，實際：" + result.detail());
        assertNull(result.snapshot(), "拒絕時不得發布新快照");
        assertNull(second.snapshot(), "拒絕時 manager 不得持有新快照");
        assertFalse(new File(dataFolder, "intercept-refuse.yml").exists(),
            "拒絕時不得重建目標檔");
    }

    @Test
    @DisplayName("handler 拒絕：同一 manager 沿用舊快照實例、世代不推進、磁碟不動")
    void missingAfterUse_refusingHandler_sameManagerKeepsOldSnapshotAndGeneration() throws Exception {
        Path file = dataFolder.toPath().resolve("intercept-keep-old.yml");
        Files.writeString(file,
            "version: '1.0'\ngreeting: 'running'\n", StandardCharsets.UTF_8);

        ConfigManager mgr = new ConfigManager(plugin, "intercept-keep-old.yml", schema, currentVersion);
        mgr.load();
        ConfigSnapshot before = mgr.snapshot();
        long generation = before.generation();
        assertTrue(file.toFile().delete());

        mgr.registerMissingFileHandler(refusingHandler());
        StartupResult result = mgr.startup();

        assertEquals(StartupResult.Status.MISSING_AFTER_USE, result.status());
        assertSame(before, result.snapshot(), "拒絕時必須沿用記憶體舊快照，不發布新內容");
        assertEquals(generation, result.snapshot().generation(), "拒絕時世代不得推進");
        assertFalse(Files.exists(file), "拒絕時不得重建目標檔");
    }

    @Test
    @DisplayName("handler 拒絕：load() 原樣拋出同一例外，不建檔")
    void missingAfterUse_refusingHandler_loadThrowsSameException() throws Exception {
        ConfigManager first = new ConfigManager(plugin, "intercept-load.yml", schema, currentVersion);
        assertEquals(StartupResult.Status.FRESH_INSTALL, first.startup().status());
        Path file = dataFolder.toPath().resolve("intercept-load.yml");
        Path lastGood = file.resolveSibling(file.getFileName() + ".last-good");
        assertTrue(new File(dataFolder, "intercept-load.yml").delete());

        ConfigException refused = new ConfigException(REFUSE_CODE, REFUSE_MESSAGE);
        ConfigManager second = new ConfigManager(plugin, "intercept-load.yml", schema, currentVersion)
            .registerMissingFileHandler(missing -> {
                throw refused;
            });
        byte[] lastGoodBefore = Files.readAllBytes(lastGood);
        ConfigException thrown = assertThrows(ConfigException.class, second::load);

        assertSame(refused, thrown, "load 必須原樣拋出 handler 的同一例外實例，不得包裝或轉譯");
        assertEquals(REFUSE_CODE, thrown.getCode());
        assertEquals(REFUSE_MESSAGE, thrown.getMessage());
        assertArrayEquals(lastGoodBefore, Files.readAllBytes(lastGood),
            "load 拒絕時不得改動最後成功副本");
        assertFalse(new File(dataFolder, "intercept-load.yml").exists(),
            "load 拒絕時不得重建目標檔");
        assertNull(second.snapshot(), "load 拒絕時不得發布快照");
    }

    @Test
    @DisplayName("handler 拒絕且有呼叫端後備：新 manager 快照取後備，仍不建檔")
    void missingAfterUse_refusingHandlerWithFallback_usesFallbackSnapshot() throws Exception {
        ConfigManager first = new ConfigManager(plugin, "intercept-fallback.yml", schema, currentVersion);
        assertEquals(StartupResult.Status.FRESH_INSTALL, first.startup().status());
        assertTrue(new File(dataFolder, "intercept-fallback.yml").delete());

        YamlConfiguration fallback = new YamlConfiguration();
        fallback.set("greeting", "safe-mode");
        fallback.set("maxPlayers", 1);

        ConfigManager second = new ConfigManager(plugin, "intercept-fallback.yml", schema, currentVersion)
            .registerMissingFileHandler(refusingHandler());
        StartupResult result = second.startup(fallback);

        assertEquals(StartupResult.Status.MISSING_AFTER_USE, result.status());
        assertEquals("safe-mode", result.snapshot().getString("greeting", null));
        assertFalse(new File(dataFolder, "intercept-fallback.yml").exists(),
            "後備快照不得回寫成目標檔");
    }

    @Test
    @DisplayName("handler 拒絕且舊快照與後備並存：優先沿用記憶體舊快照")
    void missingAfterUse_refusingHandler_prefersMemorySnapshotOverFallback() throws Exception {
        Path file = dataFolder.toPath().resolve("intercept-prefer-memory.yml");
        Files.writeString(file,
            "version: '1.0'\ngreeting: 'running'\n", StandardCharsets.UTF_8);

        ConfigManager mgr = new ConfigManager(plugin, "intercept-prefer-memory.yml", schema, currentVersion);
        mgr.load();
        ConfigSnapshot before = mgr.snapshot();
        assertTrue(file.toFile().delete());

        YamlConfiguration fallback = new YamlConfiguration();
        fallback.set("greeting", "safe-mode");

        mgr.registerMissingFileHandler(refusingHandler());
        StartupResult result = mgr.startup(fallback);

        assertEquals(StartupResult.Status.MISSING_AFTER_USE, result.status());
        assertSame(before, result.snapshot(), "舊快照優先於呼叫端後備");
    }

    @Test
    @DisplayName("首次安裝不觸發 handler（計數為零），仍正常生成預設檔")
    void freshInstall_doesNotTriggerHandler() {
        AtomicInteger calls = new AtomicInteger();
        ConfigManager mgr = new ConfigManager(plugin, "intercept-brand-new.yml", schema, currentVersion)
            .registerMissingFileHandler(missing -> calls.incrementAndGet());

        StartupResult result = mgr.startup();

        assertEquals(StartupResult.Status.FRESH_INSTALL, result.status());
        assertEquals(0, calls.get(), "從未成功使用過不得觸發 handler");
        assertTrue(new File(dataFolder, "intercept-brand-new.yml").exists());
    }

    @Test
    @DisplayName("多個 handler 依登記順序執行，第一個拒絕就停住")
    void missingAfterUse_handlerChain_stopsAtFirstRefusal() throws Exception {
        ConfigManager first = new ConfigManager(plugin, "intercept-chain.yml", schema, currentVersion);
        assertEquals(StartupResult.Status.FRESH_INSTALL, first.startup().status());
        assertTrue(new File(dataFolder, "intercept-chain.yml").delete());

        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger secondCalls = new AtomicInteger();
        ConfigManager second = new ConfigManager(plugin, "intercept-chain.yml", schema, currentVersion)
            .registerMissingFileHandler(missing -> firstCalls.incrementAndGet())
            .registerMissingFileHandler(refusingHandler())
            .registerMissingFileHandler(missing -> secondCalls.incrementAndGet());

        StartupResult result = second.startup();

        assertEquals(StartupResult.Status.MISSING_AFTER_USE, result.status());
        assertEquals(1, firstCalls.get());
        assertEquals(0, secondCalls.get(), "拒絕之後的 handler 不得再執行");
        assertFalse(new File(dataFolder, "intercept-chain.yml").exists());
    }

    @Test
    @DisplayName("registerMissingFileHandler 拒收 null，且支援鏈式呼叫")
    void registerMissingFileHandler_rejectsNull_andChains() {
        ConfigManager mgr = new ConfigManager(plugin, "intercept-null.yml", schema, currentVersion);
        assertThrows(NullPointerException.class,
            () -> mgr.registerMissingFileHandler(null));
        ConfigManager chained = mgr.registerMissingFileHandler(missing -> {
        });
        assertSame(mgr, chained, "必須回傳 this 以支援鏈式登記");
    }
}
