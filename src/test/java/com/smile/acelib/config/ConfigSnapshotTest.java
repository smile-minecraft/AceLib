package com.smile.acelib.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.AceLibPlugin;
import com.smile.acelib.platform.PlatformDetector;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * 不可變快照 Red 測試。
 *
 * <p>鎖住路線圖缺口：「交給下游的設定物件可以被改動」。
 * 快照發布後外部任何修改都必須失敗；同一輪操作固定用同一個快照實例。</p>
 */
@DisplayName("ConfigSnapshot 不可變與同輪一致")
class ConfigSnapshotTest {

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

    @Test
    @DisplayName("快照頂層 map 不可修改")
    void snapshot_topLevelMap_isUnmodifiable() {
        ConfigManager mgr = new ConfigManager(plugin, "snap-immutable.yml", schema, currentVersion);
        mgr.load();
        ConfigSnapshot snapshot = mgr.snapshot();

        assertNotNull(snapshot);
        Map<String, Object> view = snapshot.asMap();
        assertThrows(UnsupportedOperationException.class, () -> view.put("greeting", "hacked"));
        assertThrows(UnsupportedOperationException.class, () -> view.remove("greeting"));
    }

    @Test
    @DisplayName("快照巢狀 map 與 list 不可修改")
    void snapshot_nestedStructures_areUnmodifiable() throws Exception {
        Files.writeString(dataFolder.toPath().resolve("snap-nested.yml"),
            "version: '1.0'\ngreeting: 'hi'\nserver:\n  host: 'example'\n  ports:\n    - 1\n    - 2\n",
            StandardCharsets.UTF_8);
        ConfigManager mgr = new ConfigManager(plugin, "snap-nested.yml", schema, currentVersion);
        mgr.load();
        ConfigSnapshot snapshot = mgr.snapshot();

        Object serverSection = snapshot.asMap().get("server");
        assertTrue(serverSection instanceof Map, "巢狀節點應為 Map，實際：" + serverSection);
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) serverSection;
        assertThrows(UnsupportedOperationException.class, () -> nested.put("host", "hacked"));

        Object ports = snapshot.get("server.ports");
        assertTrue(ports instanceof List, "清單節點應為 List，實際：" + ports);
        @SuppressWarnings("unchecked")
        List<Object> portList = (List<Object>) ports;
        assertThrows(UnsupportedOperationException.class, () -> portList.add(3));
    }

    @Test
    @DisplayName("同一輪操作固定用同一個快照：set／save 不換快照")
    void snapshot_sameRound_setAndSave_keepSameInstance() {
        ConfigManager mgr = new ConfigManager(plugin, "snap-round.yml", schema, currentVersion);
        mgr.load();

        ConfigSnapshot before = mgr.snapshot();
        mgr.set("greeting", "changed-in-memory");
        mgr.save();
        ConfigSnapshot after = mgr.snapshot();

        assertSame(before, after, "set／save 是同輪操作，不得更換快照實例");
        assertEquals("hello", after.getString("greeting", null),
            "已發布快照的值不得被同輪 set 影響");
    }

    @Test
    @DisplayName("清單含 null 元素可載入：不 NPE、不誤判損壞，null 保留")
    void listWithNullElement_loadsFine_andKeepsNull() throws Exception {
        Files.writeString(dataFolder.toPath().resolve("snap-null-list.yml"),
            "version: '1.0'\ngreeting: 'hi'\nlst:\n  - 'a'\n  - ~\n  - 'b'\n",
            StandardCharsets.UTF_8);
        ConfigManager mgr = new ConfigManager(plugin, "snap-null-list.yml", schema, currentVersion);

        // 不拋 NPE，且分類為有效設定（原本可載入的設定仍可載入）
        StartupResult result = mgr.startup();
        assertEquals(StartupResult.Status.LOADED, result.status());

        List<String> values = mgr.snapshot().getStringList("lst");
        assertEquals(3, values.size());
        assertEquals("a", values.get(0));
        assertNull(values.get(1), "null 元素必須保留，不得跳過或轉字串");
        assertEquals("b", values.get(2));

        // 快照視圖修改仍失敗（含 null 的不可變成績效不變）
        Object raw = mgr.snapshot().get("lst");
        assertTrue(raw instanceof List, "實際：" + raw);
        @SuppressWarnings("unchecked")
        List<Object> rawList = (List<Object>) raw;
        assertThrows(UnsupportedOperationException.class, () -> rawList.add("x"));
    }

    @Test
    @DisplayName("reload 成功才換新快照；舊快照仍保留舊值")
    void snapshot_reload_publishesNewSnapshot_oldOneKeepsOldValues() throws Exception {
        Files.writeString(dataFolder.toPath().resolve("snap-reload.yml"),
            "version: '1.0'\ngreeting: 'first'\n", StandardCharsets.UTF_8);
        ConfigManager mgr = new ConfigManager(plugin, "snap-reload.yml", schema, currentVersion);
        mgr.load();
        ConfigSnapshot oldSnapshot = mgr.snapshot();

        Files.writeString(dataFolder.toPath().resolve("snap-reload.yml"),
            "version: '1.0'\ngreeting: 'second'\n", StandardCharsets.UTF_8);
        assertTrue(mgr.reload());

        ConfigSnapshot newSnapshot = mgr.snapshot();
        assertTrue(oldSnapshot != newSnapshot, "reload 成功必須發布新快照");
        assertEquals("first", oldSnapshot.getString("greeting", null), "舊快照的值必須凍結");
        assertEquals("second", newSnapshot.getString("greeting", null));
        assertEquals("second", mgr.get("greeting"));
    }

    @Test
    @DisplayName("reload 失敗保留舊快照實例")
    void snapshot_failedReload_keepsSameInstance() throws Exception {
        Files.writeString(dataFolder.toPath().resolve("snap-fail.yml"),
            "version: '1.0'\ngreeting: 'steady'\n", StandardCharsets.UTF_8);
        ConfigManager mgr = new ConfigManager(plugin, "snap-fail.yml", schema, currentVersion);
        mgr.load();
        ConfigSnapshot before = mgr.snapshot();

        Files.writeString(dataFolder.toPath().resolve("snap-fail.yml"),
            "greeting: [unclosed\n", StandardCharsets.UTF_8);
        assertTrue(!mgr.reload());

        assertSame(before, mgr.snapshot(), "reload 失敗不得更換快照");
        assertEquals("steady", mgr.snapshot().getString("greeting", null));
    }
}
