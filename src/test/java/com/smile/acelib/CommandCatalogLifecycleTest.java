package com.smile.acelib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.command.CatalogMeta;
import com.smile.acelib.command.CatalogResult;
import com.smile.acelib.command.CommandCatalog;
import com.smile.acelib.command.CommandDoc;
import com.smile.acelib.command.CommandSpec;
import com.smile.acelib.platform.PlatformDetector;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * 指令目錄對外 API 接線與插件生命週期測試。
 *
 * <p>驗證下游插件真的能用目錄：{@link AceLibApi#getCommandCatalog()}
 * 在未初始化／已啟用／已停用三種狀態皆回傳非 null 且行為安全；任一插件停用時
 * 其描述自動撤下；AceLib 自身停用清空且舊參考不可再寫入；內部 reload 保留內容；
 * {@code /acelib} 自我發布且既有執行行為不變（後者由既有指令測試把關）。</p>
 */
@DisplayName("CommandCatalog 對外接線與生命週期")
class CommandCatalogLifecycleTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        MockBukkit.unmock();
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private AceLibPlugin freshEnabled() {
        AceLibPlugin plugin = (AceLibPlugin) server.getPluginManager()
            .loadPlugin(AceLibPlugin.class);
        plugin.compatibilityOverride = ignored ->
            CompatibilityStatus.supported("catalog-lifecycle-test");
        plugin.onEnable(server, new PlatformDetector(getClass().getClassLoader()));
        // 讓 plugin 在 MockBukkit 中真的處於 enabled 狀態：MockBukkit 的
        // callEvent 會靜默跳過擁有者未啟用插件的 listener（PluginManagerMock
        // #callRegisteredListener 的 isEnabled 檢查），而 registerEvents 會
        // 直接丟 IllegalPluginAccessException；繞過 PluginManager 的註冊
        // 只能證明「有註冊」，無法證明「會被實際呼叫」。enablePlugin 只標記
        // enabled 並派送 PluginEnableEvent，不重跑 onEnable（冪等守門已擋下），
        // 再經由正式產品路徑 onPluginReady() 完成 listener 註冊。
        server.getPluginManager().enablePlugin(plugin);
        plugin.onPluginReady();
        return plugin;
    }

    private static Plugin mockPlugin(String name) {
        JavaPlugin mock = org.mockito.Mockito.mock(JavaPlugin.class);
        org.mockito.Mockito.when(mock.getName()).thenReturn(name);
        return mock;
    }

    private static CommandSpec spec(String name) {
        return CommandSpec.builder(name)
            .description(name + " desc")
            .usage("/" + name)
            .build();
    }

    private static CatalogMeta meta() {
        return new CatalogMeta("economy", Optional.empty(), Set.of());
    }

    private static CommandDoc doc(String owner, String name) {
        return new CommandDoc(owner, name, List.of(), name + " desc",
            "/" + name, null, "economy", Optional.empty(), List.of());
    }

    /** 统计 HandlerList 上屬於指定插件的註冊數。 */
    private static int registeredCountFor(Plugin plugin) {
        AtomicInteger count = new AtomicInteger();
        for (org.bukkit.plugin.RegisteredListener registered
                : PluginDisableEvent.getHandlerList().getRegisteredListeners()) {
            if (registered.getPlugin() == plugin) {
                count.incrementAndGet();
            }
        }
        return count.get();
    }

    // -----------------------------------------------------------------
    // 三種 facade 狀態
    // -----------------------------------------------------------------

    @Test
    @DisplayName("uninitialized：目錄非 null、發布拒絕、快照空")
    void uninitialized_catalogIsSafeFallback() {
        CommandCatalog catalog = AceLibApi.uninitialized().getCommandCatalog();
        assertNotNull(catalog, "uninitialized 的目錄必須非 null");
        assertEquals(CatalogResult.REJECTED,
            catalog.publish(mockPlugin("Shop"), spec("shop"), meta()));
        assertTrue(catalog.snapshot().isEmpty());
    }

    @Test
    @DisplayName("ready：目錄可用，發布與快照正常")
    void ready_catalogIsAvailable() {
        AceLibPlugin plugin = freshEnabled();
        CommandCatalog catalog = plugin.getApi().getCommandCatalog();
        assertNotNull(catalog, "ready 的目錄必須非 null");

        Plugin shop = mockPlugin("Shop");
        assertEquals(CatalogResult.PUBLISHED, catalog.publish(shop, spec("shop"), meta()));
        assertTrue(catalog.snapshot().stream()
            .anyMatch(d -> d.owner().equals("Shop") && d.name().equals("shop")));
    }

    @Test
    @DisplayName("舊 ready 多載維持可用面：未傳目錄時以不可用實作填補")
    void legacyReadyOverloads_fillUnavailableCatalog() {
        AceLibApi api = AceLibApi.ready(
            "1.3.0",
            com.smile.acelib.platform.Platform.PAPER,
            com.smile.acelib.platform.PlatformCapability.forPlatform(
                com.smile.acelib.platform.Platform.PAPER),
            AceLibApi.uninitialized().getWorldService(),
            AceLibApi.uninitialized().getGuiService(),
            () -> true,
            () -> { /* no-op */ });
        CommandCatalog catalog = api.getCommandCatalog();
        assertNotNull(catalog, "舊多載的目錄仍必須非 null");
        assertEquals(CatalogResult.REJECTED,
            catalog.publish(mockPlugin("Shop"), spec("shop"), meta()));
        assertTrue(catalog.snapshot().isEmpty());
    }

    // -----------------------------------------------------------------
    // AceLib 停用：清空 + 舊參考不可再寫入
    // -----------------------------------------------------------------

    @Test
    @DisplayName("AceLib 停用清空目錄，舊 facade 參考不可再發布")
    void disable_clearsCatalogAndOldRefRejected() {
        AceLibPlugin plugin = freshEnabled();
        AceLibApi apiBefore = plugin.getApi();
        CommandCatalog catalogBefore = apiBefore.getCommandCatalog();
        catalogBefore.publish(mockPlugin("Shop"), spec("shop"), meta());
        assertEquals(2, catalogBefore.snapshot().size(), "自我發布 + Shop 應有兩筆");

        plugin.onDisable();

        assertTrue(catalogBefore.snapshot().isEmpty(), "停用後舊參考快照必須為空");
        assertEquals(CatalogResult.REJECTED,
            catalogBefore.publish(mockPlugin("Shop"), spec("shop"), meta()),
            "停用後舊參考再發布必須回 REJECTED");
        CommandCatalog afterDisable = plugin.getApi().getCommandCatalog();
        assertNotNull(afterDisable, "停用後目錄仍必須非 null");
        assertTrue(afterDisable.snapshot().isEmpty());
    }

    // -----------------------------------------------------------------
    // PluginDisableEvent 撤下
    // -----------------------------------------------------------------

    @Test
    @DisplayName("插件停用撤下其描述，其他插件與自我發布不受影響且 revision 只 +1")
    void pluginDisable_removesOnlyThatPlugin() {
        AceLibPlugin plugin = freshEnabled();
        CommandCatalog catalog = plugin.getApi().getCommandCatalog();
        int baseline = catalog.snapshot().size();
        assertTrue(baseline >= 1, "自我發布應已存在");

        Plugin shop = mockPlugin("Shop");
        Plugin bank = mockPlugin("Bank");
        catalog.publish(shop, spec("shop"), meta());
        catalog.publish(bank, spec("bank"), meta());
        long beforeDisable = catalog.revision();

        server.getPluginManager().callEvent(new PluginDisableEvent(shop));

        List<CommandDoc> remaining = catalog.snapshot();
        assertTrue(remaining.stream().noneMatch(d -> d.owner().equals("Shop")),
            "Shop 描述必須消失");
        assertTrue(remaining.stream().anyMatch(d -> d.owner().equals("Bank")),
            "Bank 描述必須保留");
        assertTrue(remaining.stream().anyMatch(d -> d.name().equals("acelib")),
            "/acelib 自我發布必須保留");
        assertEquals(beforeDisable + 1, catalog.revision(),
            "一次實際撤下 revision 只 +1");
        assertEquals(baseline + 1, remaining.size());
    }

    @Test
    @DisplayName("停用未知插件不算變更：revision 不變")
    void pluginDisable_unknownPlugin_noRevisionChange() {
        AceLibPlugin plugin = freshEnabled();
        CommandCatalog catalog = plugin.getApi().getCommandCatalog();
        long before = catalog.revision();

        server.getPluginManager().callEvent(
            new PluginDisableEvent(mockPlugin("Ghost")));

        assertEquals(before, catalog.revision(), "撤下不存在的不算變更");
    }

    @Test
    @DisplayName("並行停用競爭：兩插件同時撤下皆生效且不遺失")
    void pluginDisable_concurrent_noLostUpdates() throws Exception {
        AceLibPlugin plugin = freshEnabled();
        CommandCatalog catalog = plugin.getApi().getCommandCatalog();
        Plugin shop = mockPlugin("Shop");
        Plugin bank = mockPlugin("Bank");
        catalog.publish(shop, spec("shop"), meta());
        catalog.publish(bank, spec("bank"), meta());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        for (Plugin target : List.of(shop, bank)) {
            pool.submit(() -> {
                try {
                    go.await(10, TimeUnit.SECONDS);
                    AceLibPlugin.handleCatalogPluginDisable(catalog, target);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        go.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "並行撤下應完成");
        pool.shutdownNow();

        assertTrue(catalog.snapshot().stream()
            .noneMatch(d -> d.owner().equals("Shop") || d.owner().equals("Bank")),
            "並行撤下不得遺失任一筆");
        assertTrue(catalog.snapshot().stream().anyMatch(d -> d.name().equals("acelib")),
            "自我發布必須保留");
    }

    // -----------------------------------------------------------------
    // 停用事件處理永不拋例外、不影響其他 listener
    // -----------------------------------------------------------------

    @Test
    @DisplayName("撤下分派永不拋例外：null 目錄、null 插件、會拋錯的目錄皆吞下")
    void disableDispatch_neverThrows() {
        CommandCatalog throwing = org.mockito.Mockito.mock(CommandCatalog.class);
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
            .when(throwing).unpublishAll(org.mockito.Mockito.any());

        // 以下三行任一行拋出即測試失敗
        AceLibPlugin.handleCatalogPluginDisable(null, mockPlugin("Shop"));
        AceLibPlugin.handleCatalogPluginDisable(
            CommandCatalog.forUnavailable(), null);
        AceLibPlugin.handleCatalogPluginDisable(throwing, mockPlugin("Shop"));
    }

    @Test
    @DisplayName("異常停用事件不影響其他 listener：後續 listener 仍被呼叫")
    void evilDisableEvent_otherListenersStillRun() {
        AceLibPlugin plugin = freshEnabled();
        List<String> calls = new CopyOnWriteArrayList<>();
        Listener recorder = new Listener() {
            @EventHandler(priority = EventPriority.MONITOR)
            public void onDisable(PluginDisableEvent event) {
                calls.add("recorder");
            }
        };
        server.getPluginManager().registerEvents(recorder, plugin);

        // null 擁有者事件：目錄分派必須內部吞下，不中斷後續 listener
        server.getPluginManager().callEvent(new PluginDisableEvent(null));

        assertTrue(calls.contains("recorder"), "其他 listener 必須仍被呼叫");
        HandlerList.unregisterAll(recorder);
    }

    // -----------------------------------------------------------------
    // listener 生命週期：可重複安裝、解除不殘留
    // -----------------------------------------------------------------

    @Test
    @DisplayName("listener 可重複安裝且只註冊一次，停用後 HandlerList 不殘留")
    void catalogListener_reinstallSafeAndCleanOnDisable() {
        AceLibPlugin plugin = freshEnabled();
        plugin.registerListenersForTest();

        assertEquals(1, registeredCountFor(plugin),
            "重複安裝不得重複註冊目錄 listener");

        plugin.onDisable();

        assertEquals(0, registeredCountFor(plugin),
            "停用後 HandlerList 不得殘留目錄 listener");
    }

    // -----------------------------------------------------------------
    // reload 保留
    // -----------------------------------------------------------------

    @Test
    @DisplayName("reload 成功保留目錄內容與同一實例")
    void reloadSuccess_retainsCatalog() {
        AceLibPlugin plugin = freshEnabled();
        CommandCatalog before = plugin.getApi().getCommandCatalog();
        before.publish(mockPlugin("Shop"), spec("shop"), meta());
        long revision = before.revision();

        assertTrue(plugin.reload(), "reload 應成功");

        CommandCatalog after = plugin.getApi().getCommandCatalog();
        assertSame(before, after, "reload 不重建目錄，必須是同一實例");
        assertEquals(revision, after.revision(), "reload 不得推進 revision");
        assertTrue(after.snapshot().stream()
            .anyMatch(d -> d.owner().equals("Shop") && d.name().equals("shop")),
            "reload 後 Shop 描述必須保留");
    }

    @Test
    @DisplayName("reload 失敗回復保留目錄內容")
    void reloadFailure_retainsCatalog() {
        AceLibPlugin plugin = freshEnabled();
        CommandCatalog before = plugin.getApi().getCommandCatalog();
        before.publish(mockPlugin("Shop"), spec("shop"), meta());
        plugin.reloadRebindFailureHook = () -> {
            throw new RuntimeException("forced rebind failure");
        };

        assertTrue(!plugin.reload(), "reload 應回報失敗");

        CommandCatalog after = plugin.getApi().getCommandCatalog();
        assertSame(before, after, "失敗回復不重建目錄");
        assertTrue(after.snapshot().stream()
            .anyMatch(d -> d.owner().equals("Shop") && d.name().equals("shop")),
            "失敗回復後 Shop 描述必須保留");
    }

    // -----------------------------------------------------------------
    // 自我發布
    // -----------------------------------------------------------------

    @Test
    @DisplayName("自我發布：/acelib 描述與既有 rootSpec 一致")
    void selfPublish_matchesRootSpec() {
        AceLibPlugin plugin = freshEnabled();
        CommandCatalog catalog = plugin.getApi().getCommandCatalog();

        List<CommandDoc> self = catalog.snapshot().stream()
            .filter(d -> d.name().equals("acelib"))
            .toList();
        assertEquals(1, self.size(), "/acelib 必須發布恰一筆");
        CommandDoc doc = self.get(0);
        assertEquals(plugin.getName(), doc.owner(), "擁有者必須是 AceLib 插件名");
        assertEquals("AceLib 管理指令根節點", doc.description());
        assertEquals("/acelib <status>", doc.usage());
        assertEquals("acelib.admin", doc.permission());
        assertEquals("admin", doc.category());
        assertTrue(doc.icon().isEmpty(), "icon 可為空");
        assertTrue(doc.subcommands().stream().anyMatch(s -> s.name().equals("status")),
            "子指令必須含 status");
        assertTrue(doc.subcommands().stream().noneMatch(
            com.smile.acelib.command.SubDoc::requiresConfirmation),
            "status 不需二次確認");
    }
}
