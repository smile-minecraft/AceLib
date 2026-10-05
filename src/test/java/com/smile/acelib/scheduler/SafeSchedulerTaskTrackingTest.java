package com.smile.acelib.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.AceLibPlugin;
import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import com.smile.acelib.platform.PlatformDetector;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * SafeScheduler 任務追蹤生命週期測試。
 *
 * <p>{@code tracked} 集合過去只增不減：一次性任務跑完、任務被取消之後
 * 都還留在集合裡，長時間運行會讓 {@code getTrackedTaskCount()} 無界成長，
 * 讓 {@code cancelAll()} 每次都掃到大量早已結束的任務。本類別鎖定
 * 「任務結束即解除追蹤」這條規則，並同時覆蓋 Paper 與 Folia 兩條路徑。</p>
 */
@DisplayName("SafeScheduler — 任務追蹤解除")
class SafeSchedulerTaskTrackingTest {

    private ServerMock server;
    private AceLibPlugin plugin;
    private SafeSchedulerImpl scheduler;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);
        plugin.onEnable(server, new PlatformDetector(getClass().getClassLoader()));
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

    @Test
    @DisplayName("一次性任務執行完成後必須從 tracked 移除")
    void oneShotGlobal_removedFromTrackedAfterRun() {
        int before = scheduler.getTrackedTaskCount();
        scheduler.runGlobal(() -> {});
        assertEquals(before + 1, scheduler.getTrackedTaskCount(), "派送後應先被追蹤");
        server.getScheduler().performTicks(1L);
        assertEquals(before, scheduler.getTrackedTaskCount(),
            "一次性任務執行完成後不得留在 tracked");
    }

    @Test
    @DisplayName("runLater / runForEntity / runForPlayer 一次性任務完成後皆移除")
    void oneShotVariants_removedFromTrackedAfterRun() {
        int before = scheduler.getTrackedTaskCount();
        var player = server.addPlayer();
        scheduler.runLater(() -> {}, 0L);
        scheduler.runForEntity(player, () -> {});
        scheduler.runForPlayer(player, () -> {});
        assertEquals(before + 3, scheduler.getTrackedTaskCount());
        server.getScheduler().performTicks(1L);
        assertEquals(before, scheduler.getTrackedTaskCount(),
            "一次性任務（含 entity / player 上下文）完成後不得留在 tracked");
    }

    @Test
    @DisplayName("一次性任務被取消後必須從 tracked 移除（即使尚未執行）")
    void oneShot_cancelled_removedFromTracked() {
        int before = scheduler.getTrackedTaskCount();
        ScheduledTask task = scheduler.runLater(() -> {}, 100L);
        assertEquals(before + 1, scheduler.getTrackedTaskCount());
        task.cancel();
        assertEquals(before, scheduler.getTrackedTaskCount(),
            "取消後不得留在 tracked（否則 cancelAll 會重複處理）");
    }

    @Test
    @DisplayName("cancelAll 之後 tracked 清空（不論任務是否已執行過）")
    void cancelAll_clearsTracked() {
        int before = scheduler.getTrackedTaskCount();
        scheduler.runLater(() -> {}, 50L);
        scheduler.runTimer(() -> {}, 0L, 1L);
        scheduler.cancelAll();
        assertEquals(before, scheduler.getTrackedTaskCount(),
            "cancelAll 後 tracked 必須回到呼叫前數量");
    }

    @Test
    @DisplayName("週期任務在重複執行期間維持被追蹤；取消後才移除")
    void repeatingTask_staysTrackedWhileRunning() {
        int before = scheduler.getTrackedTaskCount();
        int[] runs = {0};
        ScheduledTask timer = scheduler.runTimer(() -> runs[0]++, 0L, 1L);
        server.getScheduler().performTicks(3L);
        assertTrue(runs[0] >= 3, "timer 應已重複執行，實際: " + runs[0]);
        assertEquals(before + 1, scheduler.getTrackedTaskCount(),
            "週期任務執行期間仍需被追蹤（否則 disable 無法取消它）");
        timer.cancel();
        assertEquals(before, scheduler.getTrackedTaskCount(), "取消後必須移除");
    }

    @Test
    @DisplayName("大量一次性任務跑完後 tracked 計數不累積（長跑穩定）")
    void longRun_doesNotAccumulate() {
        int before = scheduler.getTrackedTaskCount();
        for (int i = 0; i < 200; i++) {
            scheduler.runGlobal(() -> {});
        }
        server.getScheduler().performTicks(1L);
        assertEquals(before, scheduler.getTrackedTaskCount(),
            "200 個一次性任務跑完後 tracked 不得殘留");
    }

    @Test
    @DisplayName("tracked 只在任務存活期間含有該任務；執行順序不受移除影響")
    void tracking_removal_keepsOtherTasksIntact() {
        int before = scheduler.getTrackedTaskCount();
        boolean[] late = {false};
        scheduler.runGlobal(() -> {});
        ScheduledTask later = scheduler.runLater(() -> late[0] = true, 5L);
        assertNotNull(later);
        server.getScheduler().performTicks(1L);
        assertEquals(before + 1, scheduler.getTrackedTaskCount(),
            "尚未到期的 runLater 必須仍被追蹤");
        server.getScheduler().performTicks(6L);
        assertTrue(late[0]);
        assertEquals(before, scheduler.getTrackedTaskCount());
    }

    @Test
    @DisplayName("取消任務後任務仍回報 cancelled（移除追蹤不得改變取消語意）")
    void cancelledTask_stillReportsCancelled() {
        ScheduledTask task = scheduler.runLater(() -> {}, 50L);
        task.cancel();
        assertTrue(task.isCancelled(), "取消語意不得因為解除追蹤而改變");
        task.cancel(); // 重複 cancel 冪等
        assertTrue(task.isCancelled());
    }

    @Test
    @DisplayName("backend 在 dispatch 內同步執行 runnable：任務不得殘留 tracked")
    void synchronousExecution_insideDispatch_leavesNoResidue() {
        // 某些 backend 會在 dispatch 內部就同步跑完一次性任務（例如測試替身、
        // 或把當前 region 的工作直接執行完畢）。此時解除追蹤發生在登記之前，
        // tracked 不得因此留下一個「已完成卻仍存活」的任務。
        AtomicInteger runs = new AtomicInteger();
        RecordingHandle handle = new RecordingHandle();
        SchedulerBackend syncBackend = new SchedulerBackend() {
            @Override
            public PlatformTaskHandle dispatch(TaskType type, Runnable wrapped, Runnable retired,
                                               Player player, Object entityOrLoc, long delayTicks,
                                               long periodTicks, boolean async) {
                // 即時任務（delay <= 0）在 dispatch 內同步執行完畢，模擬「當前 region
                // 直接執行」的 backend；有延遲的任務保持待執行，否則「尚未到期仍被
                // 追蹤」的規則無法被驗證。
                if (delayTicks <= 0L) {
                    wrapped.run();
                }
                return handle;
            }
        };
        SafeSchedulerImpl syncScheduler = new SafeSchedulerImpl(
            plugin, Platform.PAPER, PlatformCapability.forPlatform(Platform.PAPER), syncBackend);

        assertEquals(0, syncScheduler.getTrackedTaskCount());
        syncScheduler.runGlobal(runs::incrementAndGet);
        assertEquals(1, runs.get(), "同步 backend 應已執行 runnable");
        assertEquals(0, syncScheduler.getTrackedTaskCount(),
            "已完成的任務不得留在 tracked");

        // 尚未被取消的句柄不應因任務結束而被 dispatch 端主動取消。
        assertFalse(handle.cancelled, "任務自然結束不等於被取消");

        // 尚未執行的任務仍必須被追蹤，否則 cancelAll 取消不到它。
        AtomicInteger neverRun = new AtomicInteger();
        syncScheduler.runLater(neverRun::incrementAndGet, 100L);
        assertEquals(1, syncScheduler.getTrackedTaskCount(),
            "尚未到期的任務必須仍被追蹤");
        syncScheduler.cancelAll();
        assertEquals(0, syncScheduler.getTrackedTaskCount());
        assertEquals(1, handle.cancelledCount,
            "cancelAll 必須仍把取消轉發到底層句柄");
        syncScheduler.onPluginDisable();
    }

    @Test
    @DisplayName("Paper 路徑：dispatch 同步執行的 backend 也不得殘留 tracked")
    void paperBackend_tracksOnlyLiveTasks() {
        int before = scheduler.getTrackedTaskCount();
        scheduler.runGlobal(() -> {});
        scheduler.runLater(() -> {}, 20L);
        assertEquals(before + 2, scheduler.getTrackedTaskCount(),
            "兩種一次性任務在到期前都必須被追蹤");
        server.getScheduler().performTicks(1L);
        assertEquals(before + 1, scheduler.getTrackedTaskCount(),
            "已執行者解除追蹤，未到期者仍被追蹤");
        server.getScheduler().performTicks(25L);
        assertEquals(before, scheduler.getTrackedTaskCount());
    }

    /** 可記錄的真實句柄替身：cancel() 真的會改變狀態並計次。 */
    private static final class RecordingHandle implements PlatformTaskHandle {
        private int id = 1;
        boolean cancelled;
        int cancelledCount;

        @Override
        public int taskId() {
            return id++;
        }

        @Override
        public void cancel() {
            cancelled = true;
            cancelledCount++;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }
}
