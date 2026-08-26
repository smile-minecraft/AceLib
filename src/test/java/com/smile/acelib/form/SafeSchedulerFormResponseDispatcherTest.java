package com.smile.acelib.form;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smile.acelib.scheduler.SafeScheduler;
import com.smile.acelib.scheduler.ScheduledTask;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Production {@link SafeSchedulerFormResponseDispatcher} 測試（MockBukkit）：
 * UUID → Player 解析、SafeScheduler 拒絕（cancelled no-op task）映射為 false、
 * 離線玩家回 false，以及在線重檢語意。
 */
@DisplayName("SafeSchedulerFormResponseDispatcher")
class SafeSchedulerFormResponseDispatcherTest {

    private ServerMock server;

    @BeforeEach
    void freshServer() {
        MockBukkit.unmock();
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** scheduler mock：接受任務並立即在當前執行緒執行（模擬 Paper main thread 路徑）。 */
    private static SafeScheduler acceptingScheduler() {
        SafeScheduler scheduler = mock(SafeScheduler.class);
        ScheduledTask live = mock(ScheduledTask.class);
        when(live.isCancelled()).thenReturn(false);
        when(scheduler.runForPlayer(any(Player.class), any(Runnable.class)))
            .thenAnswer(invocation -> {
                ((Runnable) invocation.getArgument(1)).run();
                return live;
            });
        return scheduler;
    }

    private static SafeScheduler rejectingScheduler() {
        SafeScheduler scheduler = mock(SafeScheduler.class);
        ScheduledTask cancelled = mock(ScheduledTask.class);
        when(cancelled.isCancelled()).thenReturn(true);
        when(scheduler.runForPlayer(any(Player.class), any(Runnable.class)))
            .thenReturn(cancelled);
        return scheduler;
    }

    @Test
    @DisplayName("在線玩家 + scheduler 接受：dispatch 回 true 且 runnable 已執行")
    void onlinePlayer_acceptingScheduler_dispatchesAndRuns() {
        PlayerMock player = server.addPlayer();
        SafeSchedulerFormResponseDispatcher dispatcher =
            SafeSchedulerFormResponseDispatcher.viaSafeScheduler(() -> acceptingScheduler());

        AtomicInteger executed = new AtomicInteger();
        boolean dispatched = dispatcher.dispatch(player.getUniqueId(), executed::incrementAndGet);

        assertTrue(dispatched, "在線玩家且 scheduler 接受時必須回 true");
        assertEquals(1, executed.get(), "MockBukkit（Paper-like）main thread 同步執行");
        assertTrue(dispatcher.isPlayerOnline(player.getUniqueId()));
    }

    @Test
    @DisplayName("SafeScheduler 拒絕（isCancelled=true）：dispatch 回 false")
    void schedulerRejected_mapsToFalse() {
        PlayerMock player = server.addPlayer();
        SafeSchedulerFormResponseDispatcher dispatcher =
            SafeSchedulerFormResponseDispatcher.viaSafeScheduler(() -> rejectingScheduler());

        AtomicInteger executed = new AtomicInteger();
        boolean dispatched = dispatcher.dispatch(player.getUniqueId(), executed::incrementAndGet);

        assertFalse(dispatched, "cancelled no-op task 必須視為拒絕派送");
        assertEquals(0, executed.get(), "被拒派送不得執行 runnable");
    }

    @Test
    @DisplayName("離線玩家（UUID 解析不到 Player）：dispatch 回 false、isPlayerOnline false")
    void offlinePlayer_rejectedWithoutScheduling() {
        UUID unknownPlayer = UUID.fromString("00000000-0000-0000-0000-000000000010");
        SafeScheduler scheduler = acceptingScheduler();
        SafeSchedulerFormResponseDispatcher dispatcher =
            SafeSchedulerFormResponseDispatcher.viaSafeScheduler(() -> scheduler);

        AtomicInteger executed = new AtomicInteger();
        boolean dispatched = dispatcher.dispatch(unknownPlayer, executed::incrementAndGet);

        assertFalse(dispatched, "解析不到 Player（離線）必須回 false");
        assertEquals(0, executed.get());
        assertFalse(dispatcher.isPlayerOnline(unknownPlayer));
    }
}
