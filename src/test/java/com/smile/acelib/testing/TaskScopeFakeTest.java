package com.smile.acelib.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.scheduler.TaskOutcome;
import com.smile.acelib.scheduler.TaskResult;
import com.smile.acelib.scheduler.TaskScope;
import com.smile.acelib.scheduler.TaskTicket;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 假作用域群組測試（{@code FakeSafeScheduler} 的 scope 語意）。
 *
 * <p>假排程器全程同步、deterministic：作用域任務立即執行並完成；
 * 離線／退休／停用／失活一律即時拒派。退服競態這類時序情境由生產側
 * MockBukkit 測試覆蓋，本類別鎖定假側與生產側一致的可觀察語意。</p>
 */
@DisplayName("排程作用域 — 假實作")
class TaskScopeFakeTest {

    private FakeSafeScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new FakeSafeScheduler(Mockito.mock(JavaPlugin.class), new FakeClock());
    }

    private static Player onlinePlayer() {
        Player player = Mockito.mock(Player.class);
        Mockito.when(player.isOnline()).thenReturn(true);
        Mockito.when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        return player;
    }

    private static Player offlinePlayer() {
        Player player = Mockito.mock(Player.class);
        Mockito.when(player.isOnline()).thenReturn(false);
        Mockito.when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        return player;
    }

    private static Entity liveEntity() {
        Entity entity = Mockito.mock(Entity.class);
        Mockito.when(entity.isDead()).thenReturn(false);
        Mockito.when(entity.isValid()).thenReturn(true);
        Mockito.when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
        return entity;
    }

    @Test
    @DisplayName("作用域任務立即完成並攜終態")
    void scopeRun_completesImmediately() throws Exception {
        TaskScope scope = scheduler.scopeFor(onlinePlayer());
        assertTrue(scope.isActive());

        TaskTicket<Void> ticket = scope.run(() -> {
        });
        assertTrue(ticket.isDone());
        assertEquals(TaskOutcome.COMPLETED, ticket.await(5L, TimeUnit.SECONDS).outcome());
    }

    @Test
    @DisplayName("supply 攜回值；流程三階段依序執行")
    void supplyAndPipeline_executeInOrder() throws Exception {
        TaskScope scope = scheduler.scopeFor(onlinePlayer());

        TaskTicket<String> supplied = scope.supply(() -> "hi");
        assertEquals("hi", supplied.await(5L, TimeUnit.SECONDS).value());

        List<String> order = new ArrayList<>();
        TaskTicket<String> flow = scope.pipeline(
            () -> {
                order.add("read");
                return "raw";
            },
            raw -> {
                order.add("compute");
                return raw.toUpperCase();
            },
            upper -> order.add("reply:" + upper));
        TaskResult<String> result = flow.await(5L, TimeUnit.SECONDS);
        assertEquals(TaskOutcome.COMPLETED, result.outcome());
        assertEquals("RAW", result.value());
        assertEquals(List.of("read", "compute", "reply:RAW"), order);
    }

    @Test
    @DisplayName("離線玩家作用域：派送即 REJECTED 並記 SCHED-002")
    void offlinePlayer_rejected() throws Exception {
        TaskScope scope = scheduler.scopeFor(offlinePlayer());
        AtomicBoolean ran = new AtomicBoolean();

        TaskTicket<Void> ticket = scope.run(() -> ran.set(true));
        assertTrue(ticket.isDone());
        assertEquals(TaskOutcome.REJECTED, ticket.await(5L, TimeUnit.SECONDS).outcome());
        assertFalse(ran.get());
        assertTrue(scheduler.getRecorderErrors(10).stream()
            .anyMatch(e -> e.code().contains("SCHED-002")));
    }

    @Test
    @DisplayName("標記離線後，同一作用域後續派送即拒派")
    void markOffline_rejectsLaterDispatches() throws Exception {
        Player player = onlinePlayer();
        TaskScope scope = scheduler.scopeFor(player);
        assertEquals(TaskOutcome.COMPLETED,
            scope.run(() -> { }).await(5L, TimeUnit.SECONDS).outcome());

        scheduler.markPlayerOffline(player.getUniqueId());
        assertEquals(TaskOutcome.REJECTED,
            scope.run(() -> { }).await(5L, TimeUnit.SECONDS).outcome());
    }

    @Test
    @DisplayName("退休實體作用域：派送即 REJECTED 並記 SCHED-003")
    void retiredEntity_rejected() throws Exception {
        Entity entity = liveEntity();
        TaskScope scope = scheduler.scopeFor(entity);
        scheduler.retire(entity);

        TaskTicket<Void> ticket = scope.run(() -> {
        });
        assertEquals(TaskOutcome.REJECTED, ticket.await(5L, TimeUnit.SECONDS).outcome());
        assertTrue(scheduler.getRecorderErrors(10).stream()
            .anyMatch(e -> e.code().contains("SCHED-003")));
    }

    @Test
    @DisplayName("停用後作用域失效，派送即 REJECTED 並記 SCHED-006")
    void disabledScope_rejected() throws Exception {
        TaskScope scope = scheduler.scopeFor(onlinePlayer());
        scheduler.disable();

        assertFalse(scope.isActive());
        assertEquals(TaskOutcome.REJECTED,
            scope.run(() -> { }).await(5L, TimeUnit.SECONDS).outcome());
    }

    @Test
    @DisplayName("失活（模擬退服）後派送即拒派；cancelAll 後仍可用")
    void deactivate_rejectsLaterDispatches() throws Exception {
        FakeTaskScope scope = (FakeTaskScope) scheduler.scopeFor(onlinePlayer());
        scope.deactivate();
        assertFalse(scope.isActive());
        assertEquals(TaskOutcome.REJECTED,
            scope.run(() -> { }).await(5L, TimeUnit.SECONDS).outcome());

        FakeTaskScope fresh = (FakeTaskScope) scheduler.scopeFor(onlinePlayer());
        fresh.cancelAll();
        assertTrue(fresh.isActive());
        assertEquals(TaskOutcome.COMPLETED,
            fresh.run(() -> { }).await(5L, TimeUnit.SECONDS).outcome());
    }

    @Test
    @DisplayName("流程讀取失敗 → FAILED，後續階段不執行")
    void pipelineReadFailure_isTerminal() throws Exception {
        TaskScope scope = scheduler.scopeFor(onlinePlayer());
        AtomicBoolean later = new AtomicBoolean();

        TaskTicket<String> ticket = scope.<String, String>pipeline(
            () -> {
                throw new IllegalStateException("read boom");
            },
            raw -> {
                later.set(true);
                return raw;
            },
            ignored -> later.set(true));

        assertEquals(TaskOutcome.FAILED, ticket.await(5L, TimeUnit.SECONDS).outcome());
        assertFalse(later.get());
    }
}
