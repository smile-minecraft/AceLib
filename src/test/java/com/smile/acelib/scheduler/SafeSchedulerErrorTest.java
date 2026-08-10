package com.smile.acelib.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.AceLibPlugin;
import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import com.smile.acelib.platform.PlatformDetector;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockito.Mockito;

/**
 * SafeScheduler 錯誤代碼 + 邊界測試。
 *
 * <p>對應 Plan §七 Phase 2 邊界條件：</p>
 * <ul>
 *   <li>「玩家排程後離線」</li>
 *   <li>「實體排程後死亡或移除」</li>
 *   <li>「世界排程後卸載」</li>
 *   <li>「chunk 不可用」</li>
 *   <li>「任務內部拋錯」</li>
 *   <li>驗收標準 #3「所有任務錯誤可記錄並定位來源」</li>
 * </ul>
 */
@DisplayName("SafeScheduler — 錯誤代碼與邊界")
class SafeSchedulerErrorTest {

    private ServerMock server;
    private AceLibPlugin plugin;
    private SafeSchedulerImpl scheduler;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);
        plugin.onEnable(server, new PlatformDetector(getClass().getClassLoader()));
        scheduler = new SafeSchedulerImpl(
            plugin, Platform.PAPER, PlatformCapability.forPlatform(Platform.PAPER)
        );
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null && !scheduler.isDisabled()) {
            scheduler.onPluginDisable();
        }
        MockBukkit.unmock();
    }

    // -----------------------------------------------------------------
    // ACELIB-SCHED-001：任務內部拋 exception
    // -----------------------------------------------------------------

    @Test
    @DisplayName("任務拋 RuntimeException → 記錄 SCHED-001，不影響後續任務")
    void taskThrows_recordsSched001() {
        RuntimeException boom = new RuntimeException("user-code explosion");
        scheduler.runGlobal(() -> { throw boom; });
        server.getScheduler().performTicks(1L);

        assertTrue(scheduler.getRecorder().contains("ACELIB-SCHED-001"),
            "必須記錄 SCHED-001");

        // 後續任務仍應正常執行
        boolean[] ran = {false};
        scheduler.runGlobal(() -> ran[0] = true);
        server.getScheduler().performTicks(1L);
        assertTrue(ran[0], "後續任務不應被 SCHED-001 影響");
    }

    @Test
    @DisplayName("runLater 拋錯 → 仍記錄 SCHED-001")
    void runLaterThrows_recordsSched001() {
        scheduler.runLater(() -> { throw new IllegalStateException("later boom"); }, 1L);
        server.getScheduler().performTicks(2L);
        assertTrue(scheduler.getRecorder().contains("ACELIB-SCHED-001"));
    }

    @Test
    @DisplayName("runTimer 拋錯 → 仍記錄 SCHED-001；週期繼續執行")
    void runTimerThrows_recordsSched001_continues() {
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger(0);
        scheduler.runTimer(() -> {
            n.incrementAndGet();
            if (n.get() == 1) {
                throw new RuntimeException("first-tick boom");
            }
        }, 0L, 1L);
        server.getScheduler().performTicks(3L);
        assertTrue(scheduler.getRecorder().contains("ACELIB-SCHED-001"));
        assertTrue(n.get() >= 2, "第一個 tick 拋錯後，後續 tick 仍應執行，實際: " + n.get());
    }

    // -----------------------------------------------------------------
    // ACELIB-SCHED-002：玩家離線
    // -----------------------------------------------------------------

    @Test
    @DisplayName("runForPlayer 對離線玩家 → 記錄 SCHED-002，回傳 cancelled")
    void runForPlayer_offline_recordsSched002() {
        var player = server.addPlayer();
        player.disconnect();
        ScheduledTask t = scheduler.runForPlayer(player, () -> {});
        assertNotNull(t);
        assertTrue(t.isCancelled());
        assertTrue(scheduler.getRecorder().contains("ACELIB-SCHED-002"));
    }

    @Test
    @DisplayName("runForPlayerLater 對離線玩家 → 記錄 SCHED-002")
    void runForPlayerLater_offline_recordsSched002() {
        var player = server.addPlayer();
        player.disconnect();
        ScheduledTask t = scheduler.runForPlayerLater(player, () -> {}, 5L);
        assertTrue(t.isCancelled());
        assertTrue(scheduler.getRecorder().contains("ACELIB-SCHED-002"));
    }

    @Test
    @DisplayName("runForPlayerLater 對離線玩家 + 負 delay 仍走離線檢查（不先拋 IAE）")
    void runForPlayerLater_offlineShortCircuits() {
        var player = server.addPlayer();
        player.disconnect();
        // 即使 delay 為負，離線檢查在前，呼叫正常
        ScheduledTask t = scheduler.runForPlayerLater(player, () -> {}, -1L);
        assertTrue(t.isCancelled());
    }

    // -----------------------------------------------------------------
    // ACELIB-SCHED-003：實體失效
    // -----------------------------------------------------------------

    @Test
    @DisplayName("runForEntity 對死亡實體 → 記錄 SCHED-003")
    void runForEntity_dead_recordsSched003() {
        // Player.remove() 在 MockBukkit 會拋 IllegalStateException；
        // 改用 setHealth(0) 觸發 LivingEntity.isDead() == true 來模擬死亡
        var player = server.addPlayer();
        player.setHealth(0.0);
        ScheduledTask t = scheduler.runForEntity(player, () -> {});
        assertTrue(t.isCancelled(), "setHealth(0) 後的 player 應視為 dead");
        assertTrue(scheduler.getRecorder().contains("ACELIB-SCHED-003"),
            "死亡實體必須記錄 SCHED-003");
    }

    @Test
    @DisplayName("runForEntity 對無效實體（isValid()=false）→ 記錄 SCHED-003")
    void runForEntity_invalidEntity_recordsSched003() {
        // 用 Mockito 直接 mock 一個 invalid entity
        org.bukkit.entity.Entity mockEntity = Mockito.mock(org.bukkit.entity.Entity.class);
        Mockito.when(mockEntity.isDead()).thenReturn(false);
        Mockito.when(mockEntity.isValid()).thenReturn(false);
        Mockito.when(mockEntity.getType()).thenReturn(org.bukkit.entity.EntityType.ZOMBIE);

        ScheduledTask t = scheduler.runForEntity(mockEntity, () -> {});
        assertTrue(t.isCancelled());
        assertTrue(scheduler.getRecorder().contains("ACELIB-SCHED-003"));
    }

    // -----------------------------------------------------------------
    // ACELIB-SCHED-004：chunk 不可用
    // -----------------------------------------------------------------

    @Test
    @DisplayName("runAtLocation 對未載入 chunk → 記錄 SCHED-004")
    void runAtLocation_unloadedChunk_recordsSched004() {
        // MockBukkit 的 WorldMock 會自動載入 chunk；用 Mockito mock world + chunk
        // 來精準模擬「chunk 未載入」情境。
        Chunk mockChunk = Mockito.mock(Chunk.class);
        Mockito.when(mockChunk.isLoaded()).thenReturn(false);
        World mockWorld = Mockito.mock(World.class);
        Mockito.when(mockWorld.getChunkAt(Mockito.any(Location.class))).thenReturn(mockChunk);

        Location loc = new Location(mockWorld, 100.0, 64.0, 100.0);
        ScheduledTask t = scheduler.runAtLocation(loc, () -> {});
        assertTrue(t.isCancelled());
        assertTrue(scheduler.getRecorder().contains("ACELIB-SCHED-004"),
            "未載入 chunk 必須記錄 SCHED-004");
    }

    // -----------------------------------------------------------------
    // ACELIB-SCHED-005：平台不支援
    // -----------------------------------------------------------------

    @Test
    @DisplayName("UNSUPPORTED platform → 記錄 SCHED-005")
    void unsupportedPlatform_recordsSched005() {
        // 顯式建立 capability 全 false 的 scheduler
        SafeSchedulerImpl unknown = new SafeSchedulerImpl(
            plugin, Platform.UNKNOWN, PlatformCapability.forPlatform(Platform.UNKNOWN)
        );
        ScheduledTask t = unknown.runGlobal(() -> {});
        assertNotNull(t);
        assertTrue(t.isCancelled(), "UNSUPPORTED 平台必須回傳 cancelled task");
        assertTrue(unknown.getRecorder().contains("ACELIB-SCHED-005"),
            "必須記錄 SCHED-005");
        unknown.onPluginDisable();
    }

    // -----------------------------------------------------------------
    // ACELIB-SCHED-006：插件停用（覆蓋，與 lifecycle test 互補）
    // -----------------------------------------------------------------

    @Test
    @DisplayName("disable 後 runLater/runForEntity/runAtLocation 全部記錄 SCHED-006")
    void disabledSchedulers_recordSched006() {
        scheduler.onPluginDisable();

        scheduler.runLater(() -> {}, 0L);
        scheduler.runTimer(() -> {}, 0L, 1L);
        scheduler.runForEntity(server.addPlayer(), () -> {});
        scheduler.runAtLocation(new Location(server.getWorlds().get(0), 0, 64, 0), () -> {});

        assertTrue(scheduler.getRecorder().contains("ACELIB-SCHED-006"));
    }

    // -----------------------------------------------------------------
    // 記錄器內容
    // -----------------------------------------------------------------

    @Test
    @DisplayName("getRecentErrors 回傳不可變清單")
    void getRecentErrors_isImmutable() {
        scheduler.runGlobal(() -> { throw new RuntimeException("x"); });
        server.getScheduler().performTicks(1L);

        List<TaskErrorRecord> errors = scheduler.getRecorderErrors(10);
        assertNotNull(errors);
        // 嘗試修改必須丟 UnsupportedOperationException
        org.junit.jupiter.api.Assertions.assertThrows(
            UnsupportedOperationException.class,
            () -> errors.add(TaskErrorRecord.cancelled(TaskType.GLOBAL, "X", "x"))
        );
    }

    @Test
    @DisplayName("錯誤紀錄包含正確 type 與 code 欄位")
    void errorRecord_containsTypeAndCode() {
        scheduler.runGlobal(() -> { throw new RuntimeException("boom"); });
        server.getScheduler().performTicks(1L);

        List<TaskErrorRecord> errors = scheduler.getRecorderErrors(10);
        assertEquals(1, errors.size());
        TaskErrorRecord rec = errors.get(0);
        assertEquals(TaskType.GLOBAL, rec.type());
        assertEquals("ACELIB-SCHED-001", rec.code());
        assertNotNull(rec.cause());
        assertNotNull(rec.detail());
    }

    // -----------------------------------------------------------------
    // Phase 14：recordSink hook（讓 DiagnosticsService 可訂閱錯誤流）
    // -----------------------------------------------------------------

    @Test
    @DisplayName("setRecordSink 後，recorder.record 自動觸發 sink.accept(code, detail)")
    void setRecordSink_invokedOnRecord() {
        AtomicInteger called = new AtomicInteger(0);
        String[] lastCode = new String[1];
        String[] lastDetail = new String[1];
        scheduler.setRecordSink((code, detail) -> {
            called.incrementAndGet();
            lastCode[0] = code;
            lastDetail[0] = detail;
        });

        scheduler.runGlobal(() -> { throw new RuntimeException("user-boom"); });
        server.getScheduler().performTicks(1L);

        assertTrue(called.get() >= 1,
            "sink 必須被呼叫至少一次（任務拋錯 → SCHED-001 → sink）");
        assertEquals("ACELIB-SCHED-001", lastCode[0]);
        assertNotNull(lastDetail[0]);
        assertTrue(lastDetail[0].contains("user-boom") || lastDetail[0].contains("RuntimeException"),
            "sink detail 應包含錯誤訊息，實際: " + lastDetail[0]);
    }

    @Test
    @DisplayName("clearRecordSink 後，sink 不再被呼叫（unbind 安全）")
    void clearRecordSink_disablesCallback() {
        AtomicInteger called = new AtomicInteger(0);
        scheduler.setRecordSink((code, detail) -> called.incrementAndGet());
        scheduler.clearRecordSink();

        scheduler.runGlobal(() -> { throw new RuntimeException("x"); });
        server.getScheduler().performTicks(1L);

        assertEquals(0, called.get(),
            "clearRecordSink 後 sink 必須不被呼叫，實際: " + called.get());
    }

    @Test
    @DisplayName("setRecordSink(null) 等同 clear：sink 不再被呼叫")
    void setRecordSinkNull_disablesCallback() {
        AtomicInteger called = new AtomicInteger(0);
        scheduler.setRecordSink((code, detail) -> called.incrementAndGet());
        scheduler.setRecordSink(null);

        scheduler.runLater(() -> {}, 1L);
        server.getScheduler().performTicks(2L);

        assertEquals(0, called.get(),
            "setRecordSink(null) 後 sink 必須不被呼叫，實際: " + called.get());
    }

    @Test
    @DisplayName("sink 拋例外不影響 scheduler 主流程與 recorder")
    void sinkThrowing_doesNotBreakScheduler() {
        scheduler.setRecordSink((code, detail) -> {
            throw new RuntimeException("sink-boom");
        });

        // scheduler 主流程仍應正常運作；任務拋錯仍記錄 SCHED-001
        scheduler.runGlobal(() -> { throw new RuntimeException("user-boom"); });
        server.getScheduler().performTicks(1L);

        assertTrue(scheduler.getRecorder().contains("ACELIB-SCHED-001"),
            "sink 拋例外不應阻擋 recorder 記錄錯誤");
    }
}