package com.smile.acelib.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 指令目錄接線工廠（{@link CommandCatalog#forProduction()} /
 * {@link CommandCatalog#forUnavailable()}）與生命週期（{@link CommandCatalog#shutdown()}）測試。
 *
 * <p>驗證對外 API 接線所需的最小面：production 實例可用、unavailable 實例安全退回
 *（{@code publish} 回 {@code REJECTED} 並記 {@code ACELIB-CMD-014}、
 * {@code snapshot()} 空、{@code unpublishAll} 無害）、{@code shutdown()}
 * 清空並標記不可用（冪等），以及並行發布／撤下不遺失資料。</p>
 */
@DisplayName("CommandCatalog 接線工廠與生命週期")
class CommandCatalogWiringTest {

    private static Plugin plugin(String name) {
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
        return new CatalogMeta("admin", Optional.empty(), Set.of());
    }

    private static CommandDoc doc(String owner, String name) {
        return new CommandDoc(owner, name, List.of(), name + " desc",
            "/" + name, null, "admin", Optional.empty(), List.of());
    }

    /** 擷取 "AceLib" logger 的記錄；呼叫端負責還原。 */
    private static List<LogRecord> captureAll(Runnable action) {
        Logger logger = Logger.getLogger("AceLib");
        List<LogRecord> records = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Level previous = logger.getLevel();
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
        try {
            action.run();
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previous);
        }
        return records;
    }

    @Test
    @DisplayName("forProduction() 起始可用：發布成功、快照可見、revision 推進")
    void forProduction_startsAvailable() {
        CommandCatalog catalog = CommandCatalog.forProduction();
        assertNotNull(catalog, "forProduction() 永不回傳 null");

        Plugin owner = plugin("Shop");
        assertEquals(CatalogResult.PUBLISHED, catalog.publish(owner, spec("shop"), meta()));
        assertEquals(1, catalog.snapshot().size(), "發布後快照應可見");
        assertEquals(1, catalog.revision(), "一次實際發布 revision +1");
    }

    @Test
    @DisplayName("forUnavailable() 安全退回：發布拒絕、快照空、撤下無害")
    void forUnavailable_safeFallback() {
        CommandCatalog catalog = CommandCatalog.forUnavailable();
        assertNotNull(catalog, "forUnavailable() 永不回傳 null");

        Plugin owner = plugin("Shop");
        List<LogRecord> records = captureAll(() -> {
            assertEquals(CatalogResult.REJECTED,
                catalog.publish(owner, spec("shop"), meta()),
                "不可用時 spec 發布必須回 REJECTED");
            assertEquals(CatalogResult.REJECTED,
                catalog.publish(owner, doc("Shop", "shop")),
                "不可用時純描述發布必須回 REJECTED");
        });
        assertTrue(records.stream()
                .anyMatch(r -> r.getMessage() != null
                    && r.getMessage().contains("ACELIB-CMD-014")),
            "拒絕必須記錄 ACELIB-CMD-014，實際：" + records.stream()
                .map(LogRecord::getMessage).toList());
        assertTrue(catalog.snapshot().isEmpty(), "不可用時快照必須為空");
        assertEquals(0, catalog.revision(), "拒絕不推進 revision");
        catalog.unpublishAll(owner); // 無害 no-op，不丟例外
        assertEquals(0, catalog.revision(), "不可用時撤下不推進 revision");
    }

    @Test
    @DisplayName("shutdown() 清空並標記不可用：舊參考不可再發布")
    void production_shutdown_clearsAndRejects() {
        CommandCatalog catalog = CommandCatalog.forProduction();
        Plugin shop = plugin("Shop");
        Plugin bank = plugin("Bank");
        catalog.publish(shop, spec("shop"), meta());
        catalog.publish(bank, spec("bank"), meta());
        assertEquals(2, catalog.snapshot().size());

        catalog.shutdown();

        assertTrue(catalog.snapshot().isEmpty(), "shutdown 後快照必須為空");
        long afterShutdown = catalog.revision();
        assertEquals(CatalogResult.REJECTED,
            catalog.publish(shop, spec("shop"), meta()),
            "shutdown 後舊參考再發布必須回 REJECTED");
        assertEquals(afterShutdown, catalog.revision(),
            "shutdown 後的拒絕不得推進 revision");
        catalog.unpublishAll(shop); // 無害，不丟例外
    }

    @Test
    @DisplayName("shutdown() 冪等：重複呼叫不拋例外且維持不可用")
    void shutdown_isIdempotent() {
        CommandCatalog catalog = CommandCatalog.forProduction();
        catalog.publish(plugin("Shop"), spec("shop"), meta());

        catalog.shutdown();
        catalog.shutdown();

        assertTrue(catalog.snapshot().isEmpty());
        assertEquals(CatalogResult.REJECTED,
            catalog.publish(plugin("Shop"), spec("other"), meta()));
    }

    @Test
    @DisplayName("forUnavailable() 的 shutdown() 為無害 no-op")
    void unavailable_shutdown_noop() {
        CommandCatalog catalog = CommandCatalog.forUnavailable();
        catalog.shutdown();
        assertTrue(catalog.snapshot().isEmpty());
        assertEquals(0, catalog.revision());
    }

    @Test
    @DisplayName("並行發布不遺失：相異擁有者同時發布全數收錄")
    void concurrentPublish_distinctOwners_noLostUpdates() throws Exception {
        CommandCatalog catalog = CommandCatalog.forProduction();
        int threads = 8;
        int perThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            final int slot = t;
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    Plugin owner = plugin("Owner-" + slot);
                    for (int i = 0; i < perThread; i++) {
                        catalog.publish(owner, spec("cmd-" + slot + "-" + i), meta());
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS), "執行緒應就緒");
        go.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "並行發布應完成");
        pool.shutdownNow();
        assertEquals(threads * perThread, catalog.snapshot().size(),
            "相異擁有者並行發布不得遺失");
        assertEquals(threads * perThread, catalog.revision(),
            "每次實際發布 revision +1");
    }

    @Test
    @DisplayName("並行撤下不遺失：相異擁有者同時撤下各算一次變更")
    void concurrentUnpublish_distinctOwners_eachCountsOnce() throws Exception {
        CommandCatalog catalog = CommandCatalog.forProduction();
        int owners = 8;
        List<Plugin> plugins = new ArrayList<>();
        for (int i = 0; i < owners; i++) {
            Plugin owner = plugin("Concurrent-" + i);
            plugins.add(owner);
            catalog.publish(owner, spec("cmd-" + i), meta());
        }
        assertEquals(owners, catalog.revision());

        ExecutorService pool = Executors.newFixedThreadPool(owners);
        CountDownLatch ready = new CountDownLatch(owners);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(owners);
        for (Plugin owner : plugins) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    catalog.unpublishAll(owner);
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS), "執行緒應就緒");
        go.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "並行撤下應完成");
        pool.shutdownNow();
        assertTrue(catalog.snapshot().isEmpty(), "相異擁有者並行撤下後應全空");
        assertEquals(owners * 2L, catalog.revision(),
            "8 次發布 + 8 次實際撤下各 +1");
    }
}
