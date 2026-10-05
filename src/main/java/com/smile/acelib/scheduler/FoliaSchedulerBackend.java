package com.smile.acelib.scheduler;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Folia regionized scheduler backend（Internal）。
 *
 * <p>直接呼叫 {@code io.papermc.paper.threadedregions.scheduler.*}（編譯期
 * 已由 paper-api 提供，不需要 reflection），並且<strong>保留底層回傳的
 * {@link ScheduledTask} 句柄</strong>，交給 {@link PlatformTaskHandle} 包裝。
 * 這是 {@link SafeSchedulerImpl#cancelAll()}、{@code cancel()} 與 plugin
 * disable 能在 Folia 上真正取消任務的前提；一旦在這裡改用本地旗標佔位，
 * 底層任務就會繼續執行。</p>
 *
 * <h2>tick 參數約定</h2>
 * <p>Folia 的 {@code runAtFixedRate} 要求 {@code initialDelayTicks} 與
 * {@code periodTicks} 皆 &ge; 1；{@code runDelayed} 要求 delay &ge; 1。
 * 因此：</p>
 * <ul>
 *   <li>{@code delayTicks == 0} 的一次性任務走 {@code run}（下一個 tick 執行），
 *       不會把 0 傳給需要 &ge; 1 的 API。</li>
 *   <li>{@code delayTicks == 0} 的週期任務走 {@code runAtFixedRate} 時，
 *       initial delay 會被正規化成 1。這與 Paper 的
 *       {@code BukkitScheduler#runTaskTimer(plugin, task, 0, period)} 語意相同
 *       ——兩者的「第一次執行」都落在下一個 tick，之後才依 period 間隔。</li>
 * </ul>
 *
 * <h2>實體退役</h2>
 * <p>{@link EntityScheduler} 的 retired callback 會在實體於任務執行前被移除時
 * 觸發。此時 runnable 永遠不會執行，任務等同結束；backend 必須把這個訊號
 * 轉發給呼叫端，否則任務會永久留在追蹤集合裡。</p>
 *
 * <h2>scheduler 實例的取得時機</h2>
 * <p>三個 region scheduler 以 supplier 延遲取得。純 Paper 伺服器上
 * {@code Bukkit#getGlobalRegionScheduler()} 會拋
 * {@link UnsupportedOperationException}；若在建構子裡就取得，錯誤會在
 * scheduler 建構階段爆開，而不是被 {@link SafeSchedulerImpl} 統一以
 * {@code ACELIB-SCHED-005} fail-closed 處理。延遲取得保留原有的失敗語意。</p>
 *
 * <p>本類別為 package-private，不進 API surface。</p>
 */
final class FoliaSchedulerBackend implements SchedulerBackend {

    private final JavaPlugin plugin;
    private final Schedulers schedulers;
    private final AtomicInteger taskIdSource = new AtomicInteger(Integer.MIN_VALUE);

    /**
     * 生產路徑。
     *
     * <p>此 backend 只在 capability 為 region scheduling（Folia）時才會被選用，
     * 所以取得 Folia 專屬 API 是合理的；純 Paper 環境下的
     * {@link UnsupportedOperationException} 會在 dispatch 時被
     * {@link SafeSchedulerImpl} 以 SCHED-005 fail-closed 記錄。</p>
     */
    FoliaSchedulerBackend(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.schedulers = new Schedulers(
            Bukkit::getGlobalRegionScheduler,
            Entity::getScheduler,
            Bukkit::getAsyncScheduler,
            Bukkit::getRegionScheduler);
    }

    /**
     * 受控注入 seam（測試用）：直接提供四種 scheduler 實作，讓派送行為
     * 可在沒有真實 Folia 的環境下被確定性驗證。
     *
     * @param plugin          派送任務的 plugin owner；不可為 null
     * @param globalScheduler 全域 region scheduler
     * @param entityScheduler entity scheduler 解析器（實體 → 其 scheduler）
     * @param asyncScheduler  async scheduler
     * @param regionScheduler region scheduler
     */
    FoliaSchedulerBackend(JavaPlugin plugin,
                          GlobalRegionScheduler globalScheduler,
                          Function<Entity, EntityScheduler> entityScheduler,
                          AsyncScheduler asyncScheduler,
                          RegionScheduler regionScheduler) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.schedulers = new Schedulers(
            () -> globalScheduler,
            entityScheduler,
            () -> asyncScheduler,
            () -> regionScheduler);
    }

    @Override
    public PlatformTaskHandle dispatch(TaskType type,
                                       Runnable wrapped,
                                       Runnable retired,
                                       Player player,
                                       Object entityOrLoc,
                                       long delayTicks,
                                       long periodTicks,
                                       boolean async) throws Exception {
        if (async) {
            return handle(schedulers.async().runNow(plugin, st -> wrapped.run()));
        }
        if (player != null) {
            return dispatchEntity(player, wrapped, retired, delayTicks, periodTicks);
        }
        if (entityOrLoc instanceof Entity entity) {
            return dispatchEntity(entity, wrapped, retired, delayTicks, periodTicks);
        }
        if (entityOrLoc instanceof Location loc) {
            // RegionScheduler.execute() 回傳 void，拿不到可取消的句柄；
            // run() 語意相同（下一個 tick 在該 region 執行）且回傳真實句柄。
            return handle(schedulers.region().run(plugin, loc, st -> wrapped.run()));
        }
        if (entityOrLoc != null) {
            throw new IllegalStateException("unsupported Folia dispatch target: " + entityOrLoc);
        }
        if (periodTicks > 0L) {
            return handle(schedulers.global().runAtFixedRate(
                plugin, st -> wrapped.run(),
                normalizeInitialDelay(delayTicks), periodTicks));
        }
        if (delayTicks > 0L) {
            return handle(schedulers.global().runDelayed(
                plugin, st -> wrapped.run(), delayTicks));
        }
        return handle(schedulers.global().run(plugin, st -> wrapped.run()));
    }

    private PlatformTaskHandle dispatchEntity(Entity entity,
                                              Runnable wrapped,
                                              Runnable retired,
                                              long delayTicks,
                                              long periodTicks) throws EntityRetiredException {
        EntityScheduler scheduler = schedulers.entityScheduler(entity);
        io.papermc.paper.threadedregions.scheduler.ScheduledTask raw;
        if (periodTicks > 0L) {
            raw = scheduler.runAtFixedRate(
                plugin, st -> wrapped.run(), retired,
                normalizeInitialDelay(delayTicks), periodTicks);
        } else if (delayTicks > 0L) {
            raw = scheduler.runDelayed(
                plugin, st -> wrapped.run(), retired, delayTicks);
        } else {
            raw = scheduler.run(plugin, st -> wrapped.run(), retired);
        }
        if (raw == null) {
            throw new EntityRetiredException(
                "entity scheduler retired before dispatch (entity=" + entity.getType() + ")");
        }
        return handle(raw);
    }

    private PlatformTaskHandle handle(ScheduledTask task) {
        if (task == null) {
            throw new IllegalStateException("Folia scheduler returned null handle");
        }
        return new FoliaPlatformTaskHandle(task, taskIdSource);
    }

    /**
     * Folia 的 fixed-rate API 拒絕 {@code initialDelayTicks == 0}，因此把 0
     * 正規化為 1（下一個 tick）。
     *
     * <p>與 Paper 的 {@code runTaskTimer(plugin, task, 0, period)} 等價：兩者
     * 第一次執行都落在下一個 tick。global 與 entity 兩條 fixed-rate 路徑都走
     * 同一個正規化，確保兩條路徑語意一致。</p>
     */
    private static long normalizeInitialDelay(long delayTicks) {
        return delayTicks < 1L ? 1L : delayTicks;
    }

    /**
     * 四種 scheduler 的取得方式。
     *
     * <p>以 supplier / function 形式保存，讓取得動作延後到 dispatch；
     * 每個實體各有一份 {@link EntityScheduler}，所以 entity 這項是
     * {@code Function<Entity, EntityScheduler>}。</p>
     */
    private record Schedulers(
        Supplier<GlobalRegionScheduler> globalSupplier,
        Function<Entity, EntityScheduler> entitySchedulerResolver,
        Supplier<AsyncScheduler> asyncSupplier,
        Supplier<RegionScheduler> regionSupplier) {

        GlobalRegionScheduler global() {
            return globalSupplier.get();
        }

        EntityScheduler entityScheduler(Entity entity) {
            return entitySchedulerResolver.apply(entity);
        }

        AsyncScheduler async() {
            return asyncSupplier.get();
        }

        RegionScheduler region() {
            return regionSupplier.get();
        }
    }
}
