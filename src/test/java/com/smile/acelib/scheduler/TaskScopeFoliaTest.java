package com.smile.acelib.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.smile.acelib.AceLibPlugin;
import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import com.smile.acelib.platform.PlatformDetector;
import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * 作用域任務群組測試（Folia 路徑，以可控 stub backend 驗證分流）。
 *
 * <p>MockBukkit 內建 Folia mock 的句柄取消會拋未實作例外，無法驗證真實轉發；
 * 因此本類別以 Mockito stub 實作 Folia 四種 scheduler，驗證：</p>
 * <ul>
 *   <li>玩家／實體作用域任務走 entity scheduler（與 Paper 全域派送分流）；</li>
 *   <li>跨區移動後，回覆派送跟著玩家所在的新 region（resolver 回傳新 scheduler）；</li>
 *   <li>實體退休（null 句柄／retired 回呼）記 SCHED-003 而非 SCHED-005，且為終態。</li>
 * </ul>
 */
@DisplayName("排程作用域 — 玩家／實體群組（Folia）")
class TaskScopeFoliaTest {

    private ServerMock server;
    private AceLibPlugin plugin;
    private SafeSchedulerImpl foliaScheduler;
    private SafeSchedulerImpl paperScheduler;

    private GlobalRegionScheduler global;
    private AsyncScheduler async;
    private RegionScheduler region;
    private EntityScheduler entityA;
    private EntityScheduler entityB;
    private final AtomicReference<Function<Entity, EntityScheduler>> resolver =
        new AtomicReference<>(entity -> entityA);
    private final List<Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask>> asyncQueue =
        new ArrayList<>();

    private static io.papermc.paper.threadedregions.scheduler.ScheduledTask foliaHandle(
            AtomicBoolean cancelled) {
        io.papermc.paper.threadedregions.scheduler.ScheduledTask handle =
            Mockito.mock(io.papermc.paper.threadedregions.scheduler.ScheduledTask.class);
        Mockito.doAnswer(invocation -> {
            cancelled.set(true);
            return null;
        }).when(handle).cancel();
        Mockito.when(handle.isCancelled()).thenAnswer(invocation -> cancelled.get());
        return handle;
    }

    private static Answer<Object> immediateEntityAnswer() {
        return invocation -> {
            Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> task =
                invocation.getArgument(1);
            io.papermc.paper.threadedregions.scheduler.ScheduledTask handle =
                foliaHandle(new AtomicBoolean());
            task.accept(handle);
            return handle;
        };
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);
        plugin.onEnable(server, new PlatformDetector(getClass().getClassLoader()));
        server.getPluginManager().enablePlugin(plugin);

        global = Mockito.mock(GlobalRegionScheduler.class);
        async = Mockito.mock(AsyncScheduler.class);
        region = Mockito.mock(RegionScheduler.class);
        entityA = Mockito.mock(EntityScheduler.class);
        entityB = Mockito.mock(EntityScheduler.class);

        Mockito.when(async.runNow(any(), any())).thenAnswer(invocation -> {
            Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> task =
                invocation.getArgument(1);
            asyncQueue.add(task);
            return foliaHandle(new AtomicBoolean());
        });
        Mockito.when(entityA.run(any(), any(), any())).thenAnswer(immediateEntityAnswer());
        Mockito.when(entityB.run(any(), any(), any())).thenAnswer(immediateEntityAnswer());

        foliaScheduler = new SafeSchedulerImpl(
            plugin,
            Platform.FOLIA,
            PlatformCapability.forPlatform(Platform.FOLIA),
            new FoliaSchedulerBackend(plugin, global, entity -> resolver.get().apply(entity),
                async, region));
        paperScheduler = new SafeSchedulerImpl(
            plugin, Platform.PAPER, PlatformCapability.forPlatform(Platform.PAPER));
    }

    @AfterEach
    void tearDown() {
        if (foliaScheduler != null && !foliaScheduler.isDisabled()) {
            foliaScheduler.onPluginDisable();
        }
        if (paperScheduler != null && !paperScheduler.isDisabled()) {
            paperScheduler.onPluginDisable();
        }
        MockBukkit.unmock();
    }

    private void drainAsync() {
        List<Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask>> due =
            new ArrayList<>(asyncQueue);
        asyncQueue.clear();
        for (Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> task : due) {
            task.accept(foliaHandle(new AtomicBoolean()));
        }
    }

    private void pumpUntilDone(TaskTicket<?> ticket, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10000L;
        while (!ticket.isDone()) {
            drainAsync();
            server.getScheduler().performTicks(1L);
            if (ticket.isDone()) {
                return;
            }
            Thread.sleep(20L);
            if (System.currentTimeMillis() > deadline) {
                fail(what + " 未在時限內完成");
            }
        }
    }

    private static boolean hasCode(SafeSchedulerImpl target, String code) {
        return target.getRecorderErrors(50).stream().anyMatch(e -> e.code().contains(code));
    }

    // -----------------------------------------------------------------
    // Paper／Folia 分流
    // -----------------------------------------------------------------

    @Test
    @DisplayName("分流：Folia 走 entity scheduler，Paper 走全域 Bukkit 派送")
    void dispatch_routesByPlatform() throws Exception {
        Player foliaPlayer = server.addPlayer();
        TaskTicket<Void> foliaTicket = foliaScheduler.scopeFor(foliaPlayer).run(() -> {
        });
        TaskResult<Void> foliaResult = foliaTicket.await(5L, TimeUnit.SECONDS);
        assertEquals(TaskOutcome.COMPLETED, foliaResult.outcome());
        verify(entityA, Mockito.times(1)).run(any(), any(), any());
        verify(global, never()).run(any(), any());

        Player paperPlayer = server.addPlayer();
        TaskTicket<Void> paperTicket = paperScheduler.scopeFor(paperPlayer).run(() -> {
        });
        assertFalse(paperTicket.isDone(), "Paper 任務需等 tick 才執行");
        server.getScheduler().performTicks(2L);
        assertEquals(TaskOutcome.COMPLETED, paperTicket.await(5L, TimeUnit.SECONDS).outcome());
    }

    // -----------------------------------------------------------------
    // 跨區
    // -----------------------------------------------------------------

    @Test
    @DisplayName("跨區：async 階段 pending 期間移動，回覆跟著新 region 派送")
    void regionSwitch_replyFollowsNewRegion() throws Exception {
        Player player = server.addPlayer();
        TaskScope scope = foliaScheduler.scopeFor(player);
        AtomicBoolean replied = new AtomicBoolean();

        TaskTicket<String> ticket = scope.pipeline(
            () -> "raw",
            raw -> raw.toUpperCase(),
            ignored -> replied.set(true));
        assertFalse(ticket.isDone());
        assertEquals(1, asyncQueue.size(), "讀取階段應在 async 佇列等待");

        // 跨區：resolver 從此回傳新 region 的 scheduler（生產環境每次派送
        // 都重新解析實體當下位置；測試以切換 resolver 模擬移動）。
        resolver.set(entity -> entityB);
        pumpUntilDone(ticket, "跨區流程");

        assertEquals(TaskOutcome.COMPLETED, ticket.await(5L, TimeUnit.SECONDS).outcome());
        assertTrue(replied.get());
        verify(entityA, never()).run(any(), any(), any());
        verify(entityB, Mockito.times(1)).run(any(), any(), any());
    }

    @Test
    @DisplayName("跨區（同 scope 後續派送）：移動後的新任務走新 region scheduler")
    void regionSwitch_laterDispatchUsesNewScheduler() throws Exception {
        Player player = server.addPlayer();
        AtomicReference<Function<Entity, EntityScheduler>> liveResolver =
            new AtomicReference<>(entity -> entityA);
        SafeSchedulerImpl liveScheduler = new SafeSchedulerImpl(
            plugin,
            Platform.FOLIA,
            PlatformCapability.forPlatform(Platform.FOLIA),
            new FoliaSchedulerBackend(plugin, global,
                entity -> liveResolver.get().apply(entity), async, region));
        EntityScheduler liveA = Mockito.mock(EntityScheduler.class);
        EntityScheduler liveB = Mockito.mock(EntityScheduler.class);
        Mockito.when(liveA.run(any(), any(), any())).thenAnswer(immediateEntityAnswer());
        Mockito.when(liveB.run(any(), any(), any())).thenAnswer(immediateEntityAnswer());
        liveResolver.set(entity -> liveA);
        try {
            TaskScope scope = liveScheduler.scopeFor(player);
            TaskTicket<Void> before = scope.run(() -> {
            });
            assertEquals(TaskOutcome.COMPLETED, before.await(5L, TimeUnit.SECONDS).outcome());
            verify(liveA, Mockito.times(1)).run(any(), any(), any());

            liveResolver.set(entity -> liveB);
            TaskTicket<Void> after = scope.run(() -> {
            });
            assertEquals(TaskOutcome.COMPLETED, after.await(5L, TimeUnit.SECONDS).outcome());
            verify(liveA, Mockito.times(1)).run(any(), any(), any());
            verify(liveB, Mockito.times(1)).run(any(), any(), any());
        } finally {
            liveScheduler.onPluginDisable();
        }
    }

    // -----------------------------------------------------------------
    // 退休
    // -----------------------------------------------------------------

    @Test
    @DisplayName("退休（null 句柄）：REJECTED 終態並記 SCHED-003，不記 SCHED-005")
    void retiredNullHandle_rejectedSched003() throws Exception {
        EntityScheduler retiredScheduler = Mockito.mock(EntityScheduler.class);
        Mockito.when(retiredScheduler.run(any(), any(), any())).thenReturn(null);
        SafeSchedulerImpl retired = new SafeSchedulerImpl(
            plugin,
            Platform.FOLIA,
            PlatformCapability.forPlatform(Platform.FOLIA),
            new FoliaSchedulerBackend(plugin, global, entity -> retiredScheduler, async, region));
        try {
            Player player = server.addPlayer();
            TaskTicket<Void> ticket = retired.scopeFor((Entity) player).run(() -> {
                fail("退休實體的動作不得執行");
            });
            assertTrue(ticket.isDone());
            TaskResult<Void> result = ticket.await(5L, TimeUnit.SECONDS);
            assertEquals(TaskOutcome.REJECTED, result.outcome());
            assertTrue(result.errorRecord().code().contains("SCHED-003"));
            assertTrue(hasCode(retired, "SCHED-003"));
            assertFalse(hasCode(retired, "SCHED-005"), "退休不得誤記為平台不支援");
        } finally {
            retired.onPluginDisable();
        }
    }

    @Test
    @DisplayName("退休（retired 回呼）：CANCELLED 終態，事後執行的動作被守衛擋下")
    void retiredCallback_cancelsSched003() throws Exception {
        AtomicReference<Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask>> captured =
            new AtomicReference<>();
        AtomicReference<Runnable> capturedRetired = new AtomicReference<>();
        EntityScheduler retiring = Mockito.mock(EntityScheduler.class);
        Mockito.when(retiring.run(any(), any(), any())).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(1));
            capturedRetired.set(invocation.getArgument(2));
            return foliaHandle(new AtomicBoolean());
        });
        SafeSchedulerImpl retiringScheduler = new SafeSchedulerImpl(
            plugin,
            Platform.FOLIA,
            PlatformCapability.forPlatform(Platform.FOLIA),
            new FoliaSchedulerBackend(plugin, global, entity -> retiring, async, region));
        try {
            Player player = server.addPlayer();
            AtomicBoolean ran = new AtomicBoolean();
            TaskTicket<Void> ticket =
                retiringScheduler.scopeFor((Entity) player).run(() -> ran.set(true));
            assertFalse(ticket.isDone());

            capturedRetired.get().run();
            assertTrue(ticket.isDone());
            assertEquals(TaskOutcome.CANCELLED, ticket.await(5L, TimeUnit.SECONDS).outcome());
            assertTrue(hasCode(retiringScheduler, "SCHED-003"));

            captured.get().accept(foliaHandle(new AtomicBoolean()));
            assertFalse(ran.get(), "退休後即使底層誤觸發也不得執行使用者程式");
        } finally {
            retiringScheduler.onPluginDisable();
        }
    }

    @Test
    @DisplayName("Folia 退服：待執行任務自動取消並通知 CANCELLED")
    void foliaQuit_cancelsPending() throws Exception {
        AtomicReference<Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask>> captured =
            new AtomicReference<>();
        AtomicBoolean underlyingCancelled = new AtomicBoolean();
        EntityScheduler pending = Mockito.mock(EntityScheduler.class);
        Mockito.when(pending.run(any(), any(), any())).thenAnswer(invocation -> {
            Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> task =
                invocation.getArgument(1);
            captured.set(task);
            return foliaHandle(underlyingCancelled);
        });
        SafeSchedulerImpl pendingScheduler = new SafeSchedulerImpl(
            plugin,
            Platform.FOLIA,
            PlatformCapability.forPlatform(Platform.FOLIA),
            new FoliaSchedulerBackend(plugin, global, entity -> pending, async, region));
        try {
            PlayerMock player = server.addPlayer();
            AtomicBoolean ran = new AtomicBoolean();
            TaskTicket<Void> ticket =
                pendingScheduler.scopeFor(player).run(() -> ran.set(true));
            assertFalse(ticket.isDone());

            player.disconnect();
            pumpUntilDone(ticket, "Folia 退服取消");
            assertEquals(TaskOutcome.CANCELLED, ticket.await(5L, TimeUnit.SECONDS).outcome());
            assertTrue(underlyingCancelled.get(), "取消必須轉發到底層 Folia 句柄");
            assertFalse(ran.get());
        } finally {
            pendingScheduler.onPluginDisable();
        }
    }
}
