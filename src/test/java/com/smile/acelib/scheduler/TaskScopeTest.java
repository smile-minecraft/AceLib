package com.smile.acelib.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.smile.acelib.AceLibPlugin;
import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import com.smile.acelib.platform.PlatformDetector;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * 玩家作用域任務群組測試（Paper 路徑）。
 *
 * <p>鎖定三件事：</p>
 * <ul>
 *   <li>公開結果區分「已接受排程」（{@code ScheduledTask} 句柄）與
 *       「動作完成」（{@code TaskTicket} 終態），支援等待與串接；</li>
 *   <li>玩家退服、plugin 停用時群組任務自動取消並通知呼叫端，且只完成一次；</li>
 *   <li>讀取 → 背景計算 → 回玩家執行緒回覆可組成單一流程，各階段失敗皆為終態。</li>
 * </ul>
 *
 * <p>MockBukkit 以非同步執行緒派送事件，因此退服觸發的取消必須輪詢等待，
 * 不可假設 {@code disconnect()} 當下已生效。</p>
 */
@DisplayName("排程作用域 — 玩家群組（Paper）")
class TaskScopeTest {

    private ServerMock server;
    private AceLibPlugin plugin;
    private SafeSchedulerImpl scheduler;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);
        plugin.onEnable(server, new PlatformDetector(getClass().getClassLoader()));
        server.getPluginManager().enablePlugin(plugin);
        scheduler = new SafeSchedulerImpl(
            plugin, Platform.PAPER, PlatformCapability.forPlatform(Platform.PAPER));
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null && !scheduler.isDisabled()) {
            scheduler.onPluginDisable();
        }
        MockBukkit.unmock();
    }

    private static boolean hasCode(SafeSchedulerImpl target, String code) {
        return target.getRecorderErrors(50).stream().anyMatch(e -> e.code().contains(code));
    }

    private void pumpUntilDone(TaskTicket<?> ticket, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10000L;
        while (!ticket.isDone()) {
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

    private void awaitScopeInactive(TaskScope scope, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 8000L;
        while (scope.isActive()) {
            Thread.sleep(20L);
            if (System.currentTimeMillis() > deadline) {
                fail(what + "：作用域未在時限內失效");
            }
        }
    }

    // -----------------------------------------------------------------
    // 接受 vs 完成
    // -----------------------------------------------------------------

    @Test
    @DisplayName("已接受排程不等於動作完成：派送當下未完成，tick 後才完成")
    void run_acceptedIsNotCompleted() throws Exception {
        Player player = server.addPlayer();
        TaskScope scope = scheduler.scopeFor(player);
        AtomicInteger runs = new AtomicInteger();

        TaskTicket<Void> ticket = scope.run(runs::incrementAndGet);

        assertNotNull(ticket);
        assertFalse(ticket.isCancelled(), "剛派送不得已取消");
        assertFalse(ticket.isDone(), "派送當下動作尚未完成");
        assertEquals(0, runs.get(), "Paper 延遲到下一個 tick 才執行");

        server.getScheduler().performTicks(2L);
        TaskResult<Void> result = ticket.await(5L, TimeUnit.SECONDS);
        assertEquals(TaskOutcome.COMPLETED, result.outcome());
        assertEquals(1, runs.get());
    }

    @Test
    @DisplayName("supply 攜回背景值")
    void supply_capturesValue() throws Exception {
        Player player = server.addPlayer();
        TaskScope scope = scheduler.scopeFor(player);

        TaskTicket<String> ticket = scope.supply(() -> "hi");
        pumpUntilDone(ticket, "supply");
        assertEquals("hi", ticket.await(5L, TimeUnit.SECONDS).value());
    }

    @Test
    @DisplayName("動作拋錯 → FAILED 終態並記 SCHED-001，取消狀態為 false")
    void run_throwingAction_settlesFailed() throws Exception {
        Player player = server.addPlayer();
        TaskScope scope = scheduler.scopeFor(player);
        IllegalStateException boom = new IllegalStateException("boom");

        TaskTicket<Void> ticket = scope.run(() -> {
            throw boom;
        });
        pumpUntilDone(ticket, "拋錯任務");

        TaskResult<Void> result = ticket.await(5L, TimeUnit.SECONDS);
        assertEquals(TaskOutcome.FAILED, result.outcome());
        assertTrue(result.cause() == boom, "必須保留原始例外");
        assertNotNull(result.errorRecord());
        assertTrue(result.errorRecord().code().contains("SCHED-001"));
        assertTrue(hasCode(scheduler, "SCHED-001"));
        assertFalse(ticket.isCancelled(), "有執行（雖拋錯）不算取消");
    }

    // -----------------------------------------------------------------
    // 拒派
    // -----------------------------------------------------------------

    @Test
    @DisplayName("離線玩家派送 → REJECTED 終態並記 SCHED-002，動作不執行")
    void run_offlinePlayer_rejected() throws Exception {
        PlayerMock player = server.addPlayer();
        player.disconnect();
        TaskScope scope = scheduler.scopeFor(player);
        AtomicBoolean ran = new AtomicBoolean();

        TaskTicket<Void> ticket = scope.run(() -> ran.set(true));

        assertTrue(ticket.isCancelled());
        assertTrue(ticket.isDone(), "拒派本身就是終態");
        TaskResult<Void> result = ticket.await(5L, TimeUnit.SECONDS);
        assertEquals(TaskOutcome.REJECTED, result.outcome());
        assertTrue(result.errorRecord().code().contains("SCHED-002"));
        assertFalse(ran.get());
        assertTrue(hasCode(scheduler, "SCHED-002"));
    }

    @Test
    @DisplayName("停用後的作用域不活躍，派送 → REJECTED 並記 SCHED-006")
    void scope_inactiveAfterDisable() throws Exception {
        Player player = server.addPlayer();
        TaskScope scope = scheduler.scopeFor(player);
        scheduler.onPluginDisable();

        assertFalse(scope.isActive());
        TaskTicket<Void> ticket = scope.run(() -> {
        });
        assertTrue(ticket.isDone());
        assertEquals(TaskOutcome.REJECTED, ticket.await(5L, TimeUnit.SECONDS).outcome());
        assertTrue(hasCode(scheduler, "SCHED-006"));
    }

    // -----------------------------------------------------------------
    // 取消
    // -----------------------------------------------------------------

    @Test
    @DisplayName("顯式取消：待執行任務不跑，終態 CANCELLED")
    void run_explicitCancel_preventsRun() throws Exception {
        Player player = server.addPlayer();
        TaskScope scope = scheduler.scopeFor(player);
        AtomicBoolean ran = new AtomicBoolean();

        TaskTicket<Void> ticket = scope.run(() -> ran.set(true));
        ticket.cancel();

        assertTrue(ticket.isCancelled());
        assertTrue(ticket.isDone());
        assertEquals(TaskOutcome.CANCELLED, ticket.await(5L, TimeUnit.SECONDS).outcome());
        server.getScheduler().performTicks(3L);
        assertFalse(ran.get(), "取消後不得執行");
    }

    @Test
    @DisplayName("完成後再取消不改變終態（仍為 COMPLETED）")
    void cancel_afterComplete_keepsCompleted() throws Exception {
        Player player = server.addPlayer();
        TaskScope scope = scheduler.scopeFor(player);

        TaskTicket<Void> ticket = scope.run(() -> {
        });
        pumpUntilDone(ticket, "任務");
        assertEquals(TaskOutcome.COMPLETED, ticket.await(5L, TimeUnit.SECONDS).outcome());

        ticket.cancel();
        ticket.cancel();
        assertEquals(TaskOutcome.COMPLETED, ticket.await(5L, TimeUnit.SECONDS).outcome());
        assertFalse(ticket.isCancelled());
    }

    @Test
    @DisplayName("退服：待執行任務自動取消，呼叫端收到 CANCELLED，動作不執行")
    void quit_cancelsPendingAndNotifies() throws Exception {
        PlayerMock player = server.addPlayer();
        TaskScope scope = scheduler.scopeFor(player);
        AtomicBoolean ran = new AtomicBoolean();

        TaskTicket<Void> ticket = scope.run(() -> ran.set(true));
        assertFalse(ticket.isDone());
        player.disconnect();

        pumpUntilDone(ticket, "退服取消");
        assertEquals(TaskOutcome.CANCELLED, ticket.await(5L, TimeUnit.SECONDS).outcome());
        assertTrue(ticket.isCancelled());
        assertFalse(scope.isActive());
        assertTrue(hasCode(scheduler, "SCHED-002"));
        server.getScheduler().performTicks(3L);
        assertFalse(ran.get(), "退服後不得再回呼使用者程式");
    }

    @Test
    @DisplayName("停用：群組任務自動取消且作用域失效")
    void disable_cancelsScope() throws Exception {
        Player player = server.addPlayer();
        TaskScope scope = scheduler.scopeFor(player);
        AtomicBoolean ran = new AtomicBoolean();

        TaskTicket<Void> ticket = scope.run(() -> ran.set(true));
        scheduler.onPluginDisable();

        pumpUntilDone(ticket, "停用取消");
        assertEquals(TaskOutcome.CANCELLED, ticket.await(5L, TimeUnit.SECONDS).outcome());
        assertFalse(scope.isActive());
        server.getScheduler().performTicks(3L);
        assertFalse(ran.get());
    }

    @Test
    @DisplayName("scope.cancelAll 取消群組任務但作用域仍可用")
    void scopeCancelAll_keepsScopeUsable() throws Exception {
        Player player = server.addPlayer();
        TaskScope scope = scheduler.scopeFor(player);
        AtomicInteger runs = new AtomicInteger();

        TaskTicket<Void> first = scope.run(runs::incrementAndGet);
        TaskTicket<Void> second = scope.run(runs::incrementAndGet);
        scope.cancelAll();

        assertEquals(TaskOutcome.CANCELLED, first.await(5L, TimeUnit.SECONDS).outcome());
        assertEquals(TaskOutcome.CANCELLED, second.await(5L, TimeUnit.SECONDS).outcome());
        assertTrue(scope.isActive());

        TaskTicket<Void> after = scope.run(runs::incrementAndGet);
        pumpUntilDone(after, "cancelAll 後的新任務");
        assertEquals(TaskOutcome.COMPLETED, after.await(5L, TimeUnit.SECONDS).outcome());
        assertEquals(1, runs.get());
    }

    // -----------------------------------------------------------------
    // 單一流程：讀取 → 背景計算 → 回玩家執行緒回覆
    // -----------------------------------------------------------------

    @Test
    @DisplayName("流程：三階段依序執行，回覆落在主執行緒且攜計算值")
    void pipeline_happyPath() throws Exception {
        Player player = server.addPlayer();
        TaskScope scope = scheduler.scopeFor(player);
        List<String> order = new ArrayList<>();
        AtomicBoolean replyOnPrimary = new AtomicBoolean();
        AtomicReference<String> replied = new AtomicReference<>();

        TaskTicket<String> ticket = scope.pipeline(
            () -> {
                order.add("read");
                return "raw";
            },
            raw -> {
                order.add("compute");
                return raw.toUpperCase();
            },
            upper -> {
                order.add("reply");
                replyOnPrimary.set(Bukkit.isPrimaryThread());
                replied.set(upper);
            });

        assertFalse(ticket.isDone(), "流程派送不得阻塞呼叫執行緒");
        pumpUntilDone(ticket, "流程");

        TaskResult<String> result = ticket.await(5L, TimeUnit.SECONDS);
        assertEquals(TaskOutcome.COMPLETED, result.outcome());
        assertEquals("RAW", result.value());
        assertEquals("RAW", replied.get());
        assertEquals(List.of("read", "compute", "reply"), order);
        assertTrue(replyOnPrimary.get(), "Paper 回覆必須在主執行緒");
    }

    @Test
    @DisplayName("流程：讀取失敗 → FAILED 終態，後續階段不執行")
    void pipeline_readFailure_isTerminal() throws Exception {
        Player player = server.addPlayer();
        TaskScope scope = scheduler.scopeFor(player);
        AtomicBoolean computed = new AtomicBoolean();
        AtomicBoolean replied = new AtomicBoolean();

        TaskTicket<String> ticket = scope.<String, String>pipeline(
            () -> {
                throw new IllegalStateException("read boom");
            },
            raw -> {
                computed.set(true);
                return raw;
            },
            ignored -> replied.set(true));
        pumpUntilDone(ticket, "讀取失敗流程");

        TaskResult<String> result = ticket.await(5L, TimeUnit.SECONDS);
        assertEquals(TaskOutcome.FAILED, result.outcome());
        assertTrue(result.cause().getMessage().contains("read boom"));
        assertFalse(computed.get());
        assertFalse(replied.get());
        assertTrue(hasCode(scheduler, "SCHED-001"));
    }

    @Test
    @DisplayName("流程：計算失敗 → FAILED 終態，回覆不執行")
    void pipeline_computeFailure_isTerminal() throws Exception {
        Player player = server.addPlayer();
        TaskScope scope = scheduler.scopeFor(player);
        AtomicBoolean replied = new AtomicBoolean();

        TaskTicket<String> ticket = scope.pipeline(
            () -> "raw",
            raw -> {
                throw new IllegalStateException("compute boom");
            },
            ignored -> replied.set(true));
        pumpUntilDone(ticket, "計算失敗流程");

        assertEquals(TaskOutcome.FAILED, ticket.await(5L, TimeUnit.SECONDS).outcome());
        assertFalse(replied.get());
    }

    @Test
    @DisplayName("流程：回覆拋錯 → FAILED 終態並記 SCHED-001")
    void pipeline_replyFailure_isTerminal() throws Exception {
        Player player = server.addPlayer();
        TaskScope scope = scheduler.scopeFor(player);

        TaskTicket<String> ticket = scope.pipeline(
            () -> "raw",
            raw -> raw.toUpperCase(),
            ignored -> {
                throw new IllegalStateException("reply boom");
            });
        pumpUntilDone(ticket, "回覆失敗流程");

        TaskResult<String> result = ticket.await(5L, TimeUnit.SECONDS);
        assertEquals(TaskOutcome.FAILED, result.outcome());
        assertTrue(result.cause().getMessage().contains("reply boom"));
        assertTrue(hasCode(scheduler, "SCHED-001"));
    }

    @Test
    @DisplayName("流程：離線玩家直接拒派，各階段皆不執行")
    void pipeline_offlinePlayer_rejected() throws Exception {
        PlayerMock player = server.addPlayer();
        player.disconnect();
        TaskScope scope = scheduler.scopeFor(player);
        AtomicBoolean anyStage = new AtomicBoolean();

        TaskTicket<String> ticket = scope.pipeline(
            () -> {
                anyStage.set(true);
                return "raw";
            },
            raw -> {
                anyStage.set(true);
                return raw;
            },
            ignored -> anyStage.set(true));

        assertTrue(ticket.isDone());
        assertEquals(TaskOutcome.REJECTED, ticket.await(5L, TimeUnit.SECONDS).outcome());
        assertFalse(anyStage.get());
    }

    @Test
    @DisplayName("流程：讀取中退服 → CANCELLED 終態，回覆不執行")
    void pipeline_quitMidFlight_cancelled() throws Exception {
        PlayerMock player = server.addPlayer();
        TaskScope scope = scheduler.scopeFor(player);
        CountDownLatch readEntered = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);
        AtomicBoolean replied = new AtomicBoolean();

        TaskTicket<String> ticket = scope.pipeline(
            () -> {
                readEntered.countDown();
                try {
                    if (!releaseRead.await(8L, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("讀取等待逾時");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("讀取被中斷", interrupted);
                }
                return "raw";
            },
            raw -> raw.toUpperCase(),
            ignored -> replied.set(true));

        assertTrue(readEntered.await(8L, TimeUnit.SECONDS), "讀取階段必須先進入");
        player.disconnect();
        awaitScopeInactive(scope, "退服後作用域");
        releaseRead.countDown();
        pumpUntilDone(ticket, "退服中斷流程");

        assertEquals(TaskOutcome.CANCELLED, ticket.await(5L, TimeUnit.SECONDS).outcome());
        assertFalse(replied.get(), "退服後不得再回呼使用者程式");
        assertTrue(hasCode(scheduler, "SCHED-002"));
    }

    // -----------------------------------------------------------------
    // 只完成一次與等待
    // -----------------------------------------------------------------

    @Test
    @DisplayName("終態只完成一次：先勝出者保留，後續完成被拒")
    void completion_isExactlyOnce() throws Exception {
        Player player = server.addPlayer();
        TaskScope scope = scheduler.scopeFor(player);

        TaskTicket<Void> ticket = scope.run(() -> {
        });
        assertFalse(ticket.isDone());
        TicketTask<Void> internal = (TicketTask<Void>) ticket;

        assertTrue(internal.trySettle(TaskResult.completed(null)), "首次完成必須成功");
        assertFalse(internal.trySettle(TaskResult.completed(null)), "重複完成必須被拒");
        assertFalse(internal.trySettle(TaskResult.cancelled(null)), "事後取消不得覆寫終態");

        ticket.cancel();
        server.getScheduler().performTicks(2L);
        assertEquals(TaskOutcome.COMPLETED, ticket.await(5L, TimeUnit.SECONDS).outcome());
    }

    @Test
    @DisplayName("並行完成競爭：恰好一方勝出")
    void concurrentSettle_exactlyOneWins() throws Exception {
        Player player = server.addPlayer();
        TaskScope scope = scheduler.scopeFor(player);
        TicketTask<Void> internal = (TicketTask<Void>) scope.run(() -> {
        });

        int threads = 4;
        CountDownLatch gun = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger wins = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            Thread worker = new Thread(() -> {
                try {
                    gun.await(5L, TimeUnit.SECONDS);
                    if (internal.trySettle(TaskResult.completed(null))) {
                        wins.incrementAndGet();
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            worker.setDaemon(true);
            worker.start();
        }
        gun.countDown();
        assertTrue(done.await(8L, TimeUnit.SECONDS), "競爭執行緒必須結束");
        assertEquals(1, wins.get(), "並行完成必須恰好一方勝出");
        internal.cancel();
    }

    @Test
    @DisplayName("等待：另一執行緒 await，呼叫執行緒推進 tick 後取回終態")
    void await_fromAnotherThread() throws Exception {
        Player player = server.addPlayer();
        TaskScope scope = scheduler.scopeFor(player);
        TaskTicket<Void> ticket = scope.run(() -> {
        });

        AtomicReference<TaskResult<Void>> observed = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            try {
                observed.set(ticket.await(10L, TimeUnit.SECONDS));
            } catch (InterruptedException | java.util.concurrent.TimeoutException failure) {
                fail("等待執行緒不應失敗：" + failure);
            }
        });
        waiter.setDaemon(true);
        waiter.start();
        pumpUntilDone(ticket, "等待中的任務");
        waiter.join(8000L);
        assertNotNull(observed.get());
        assertEquals(TaskOutcome.COMPLETED, observed.get().outcome());
    }

    // -----------------------------------------------------------------
    // 邊界
    // -----------------------------------------------------------------

    @Test
    @DisplayName("scopeFor 起始必要條件：plugin 未啟用時拒絕（listener 無法註冊）")
    void scopeFor_pluginNotEnabled_throws() {
        JavaPlugin dormant = Mockito.mock(JavaPlugin.class);
        Mockito.when(dormant.isEnabled()).thenReturn(false);
        SafeSchedulerImpl dormantScheduler = new SafeSchedulerImpl(
            dormant, Platform.PAPER, PlatformCapability.forPlatform(Platform.PAPER));
        Player player = Mockito.mock(Player.class);

        assertThrows(IllegalStateException.class, () -> dormantScheduler.scopeFor(player));
        assertThrows(IllegalStateException.class, () -> dormantScheduler.scopeFor(player));
    }

    @Test
    @DisplayName("scopeFor 拒絕 null 作用域目標；流程拒絕 null 階段")
    void scopeFor_nullTargets_throw() {
        assertThrows(NullPointerException.class, () -> scheduler.scopeFor((Player) null));
        assertThrows(NullPointerException.class,
            () -> scheduler.scopeFor((org.bukkit.entity.Entity) null));
        Player player = server.addPlayer();
        TaskScope scope = scheduler.scopeFor(player);
        assertThrows(NullPointerException.class, () -> scope.run(null));
        assertThrows(NullPointerException.class, () -> scope.supply(null));
        assertThrows(NullPointerException.class, () -> scope.supplyAsync(null));
        assertThrows(NullPointerException.class,
            () -> scope.pipeline(null, raw -> raw, ignored -> { }));
        assertThrows(NullPointerException.class,
            () -> scope.pipeline(() -> "x", null, ignored -> { }));
        assertThrows(NullPointerException.class,
            () -> scope.pipeline(() -> "x", raw -> raw, null));
    }

    @Test
    @DisplayName("拒派（SCHED-005）：無 backend 與 backend 拋錯皆為 REJECTED 終態")
    void rejectedWhenUnsupported_recordsSched005() throws Exception {
        Player player = server.addPlayer();

        SafeSchedulerImpl noBackend = new SafeSchedulerImpl(
            plugin, Platform.PAPER, PlatformCapability.forPlatform(Platform.PAPER), null);
        try {
            TaskTicket<Void> ticket = noBackend.scopeFor(player).run(() -> {
                fail("不支援平台的動作不得執行");
            });
            assertTrue(ticket.isDone());
            TaskResult<Void> result = ticket.await(5L, TimeUnit.SECONDS);
            assertEquals(TaskOutcome.REJECTED, result.outcome());
            assertTrue(result.errorRecord().code().contains("SCHED-005"));
        } finally {
            noBackend.onPluginDisable();
        }

        SchedulerBackend failing = (type, wrapped, retired, target, entityOrLoc,
                                    delayTicks, periodTicks, async) -> {
            throw new UnsupportedOperationException("no scheduler for this combo");
        };
        SafeSchedulerImpl failingScheduler = new SafeSchedulerImpl(
            plugin, Platform.PAPER, PlatformCapability.forPlatform(Platform.PAPER), failing);
        try {
            TaskTicket<Void> ticket = failingScheduler.scopeFor(player).run(() -> {
                fail("派送失敗的動作不得執行");
            });
            assertTrue(ticket.isDone());
            TaskResult<Void> result = ticket.await(5L, TimeUnit.SECONDS);
            assertEquals(TaskOutcome.REJECTED, result.outcome());
            assertTrue(result.errorRecord().code().contains("SCHED-005"));
            assertTrue(failingScheduler.getRecorderErrors(10).stream()
                .anyMatch(e -> e.code().contains("SCHED-005")));
        } finally {
            failingScheduler.onPluginDisable();
        }
    }

    @Test
    @DisplayName("關閉紀錄尚未寫入時 pipeline 拒派不丟 NPE（必攜非 null 紀錄）")
    void pipeline_closeRecordMissing_rejectsWithoutNpe() throws Exception {
        Player player = server.addPlayer();
        // 以 package-private 建構子直接構造「已失活但關閉紀錄尚未寫入」的作用域，
        // 鎖定 deactivate 兩次寫入之間的窗口：呼叫端必須拿到 REJECTED 而非 NPE。
        TaskScopeImpl scope = new TaskScopeImpl(
            scheduler, plugin, TaskScopeImpl.Kind.PLAYER, player, null, false, null);

        TaskTicket<String> ticket = scope.pipeline(
            () -> "raw",
            raw -> raw.toUpperCase(),
            ignored -> fail("拒派流程的回覆不得執行"));

        assertTrue(ticket.isDone());
        TaskResult<String> result = ticket.await(5L, TimeUnit.SECONDS);
        assertEquals(TaskOutcome.REJECTED, result.outcome());
        assertNotNull(result.errorRecord(), "拒派必須攜帶非 null 紀錄");
    }

    @Test
    @DisplayName("派送阻塞期間停用：派送返回後票據得到 CANCELLED 終態（攜停用紀錄）")
    void dispatchBlockedDuringDisable_settlesCancelled() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PlatformTaskHandle handle = Mockito.mock(PlatformTaskHandle.class);
        Mockito.when(handle.isCancelled()).thenReturn(false);
        SchedulerBackend blocking = (type, wrapped, retired, target, entityOrLoc,
                                     delayTicks, periodTicks, async) -> {
            entered.countDown();
            assertTrue(release.await(10L, TimeUnit.SECONDS), "派送不應被困住");
            return handle;
        };
        SafeSchedulerImpl blockedScheduler = new SafeSchedulerImpl(
            plugin, Platform.PAPER, PlatformCapability.forPlatform(Platform.PAPER), blocking);
        try {
            Player player = server.addPlayer();
            TaskScope scope = blockedScheduler.scopeFor(player);
            AtomicReference<TaskTicket<Void>> ticketRef = new AtomicReference<>();
            Thread dispatching = new Thread(
                () -> ticketRef.set(scope.run(() -> fail("停用後的動作不得執行"))));
            dispatching.setDaemon(true);
            dispatching.start();

            assertTrue(entered.await(8L, TimeUnit.SECONDS), "派送必須先進入 backend");
            blockedScheduler.onPluginDisable();
            release.countDown();
            dispatching.join(8000L);

            TaskTicket<Void> ticket = ticketRef.get();
            assertNotNull(ticket, "派送執行緒必須返回票據");
            TaskResult<Void> result = ticket.await(5L, TimeUnit.SECONDS);
            assertEquals(TaskOutcome.CANCELLED, result.outcome());
            assertNotNull(result.errorRecord(), "停用取消必須攜帶關閉紀錄");
            assertTrue(result.errorRecord().code().contains("SCHED-006"));
        } finally {
            release.countDown();
            if (!blockedScheduler.isDisabled()) {
                blockedScheduler.onPluginDisable();
            }
        }
    }

    @Test
    @DisplayName("流程取消窗口：回覆派送與登記之間取消，回覆不執行且流程終態不變")
    void pipeline_replyCancelledBeforeTrack_replyNeverRuns() throws Exception {
        List<Runnable> asyncQueue = new ArrayList<>();
        AtomicReference<Runnable> replyCaptured = new AtomicReference<>();
        AtomicReference<TaskTicket<String>> flowRef = new AtomicReference<>();
        PlatformTaskHandle handle = Mockito.mock(PlatformTaskHandle.class);
        Mockito.when(handle.isCancelled()).thenReturn(false);
        SchedulerBackend backend = (type, wrapped, retired, target, entityOrLoc,
                                    delayTicks, periodTicks, async) -> {
            if (async) {
                asyncQueue.add(wrapped);
                return handle;
            }
            // 回覆派送當下（登記之前）取消流程：onCancel 讀到的是舊階段，
            // 回覆票據未被取消——修正後登記時的複查必須補上取消。
            replyCaptured.set(wrapped);
            flowRef.get().cancel();
            return handle;
        };
        SafeSchedulerImpl staged = new SafeSchedulerImpl(
            plugin, Platform.PAPER, PlatformCapability.forPlatform(Platform.PAPER), backend);
        try {
            Player player = server.addPlayer();
            AtomicBoolean replied = new AtomicBoolean();
            TaskTicket<String> flow = staged.scopeFor(player).pipeline(
                () -> "raw",
                raw -> raw.toUpperCase(),
                ignored -> replied.set(true));
            flowRef.set(flow);

            drainQueue(asyncQueue);
            drainQueue(asyncQueue);
            assertNotNull(replyCaptured.get(), "回覆必須已派送");

            replyCaptured.get().run();
            assertFalse(replied.get(), "流程取消後回覆不得執行");
            assertEquals(TaskOutcome.CANCELLED, flow.await(5L, TimeUnit.SECONDS).outcome());
        } finally {
            if (!staged.isDisabled()) {
                staged.onPluginDisable();
            }
        }
    }

    private static void drainQueue(List<Runnable> queue) {
        List<Runnable> due = new ArrayList<>(queue);
        queue.clear();
        for (Runnable task : due) {
            task.run();
        }
    }

    @Test
    @DisplayName("停用後自家 listener 解除註冊，不再殘留")
    void disable_unregistersScopeListener() throws Exception {
        Player player = server.addPlayer();
        TaskScope scope = scheduler.scopeFor(player);
        assertTrue(scope.isActive());

        boolean presentBefore = java.util.Arrays.stream(
                PlayerQuitEvent.getHandlerList().getRegisteredListeners())
            .anyMatch(registered -> registered.getPlugin() == plugin
                && registered.getListener() instanceof ScopeListener);
        assertTrue(presentBefore, "作用域建立後自家 listener 必須已註冊");

        scheduler.onPluginDisable();

        boolean retained = java.util.Arrays.stream(
                PlayerQuitEvent.getHandlerList().getRegisteredListeners())
            .anyMatch(registered -> registered.getPlugin() == plugin
                && registered.getListener() instanceof ScopeListener);
        assertFalse(retained, "停用後作用域 listener 不得殘留");
    }
}
