package com.smile.acelib.context;

import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import com.smile.acelib.scheduler.SafeScheduler;
import com.smile.acelib.scheduler.ScheduledTask;
import com.smile.acelib.scheduler.TaskType;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 上下文安全執行器（Supported）。
 *
 * <p>為後續插件提供「自動選擇正確 scheduler + 主動攔截錯誤
 * 上下文 mutate」的統一入口。內部設計：</p>
 *
 * <ul>
 *   <li>{@link #executeAsync} — 派送到 async scheduler；接受 READ_ONLY；
 *       拒絕在 main thread 送出 mutate（CTX-002）</li>
 *   <li>{@link #executeOnRegion} —
 *       自動選擇 player scheduler（Folia 用 entity scheduler；Paper 用 main thread）</li>
 *   <li>{@link #executeOnRegion} —
 *       自動選擇 entity scheduler</li>
 *   <li>{@link #executeOnRegion} —
 *       自動選擇 region scheduler（Folia 用 region scheduler；Paper 用 main thread）</li>
 * </ul>
 *
 * <h2>錯誤代碼語意</h2>
 * <ul>
 *   <li>{@code ACELIB-CTX-001} — 在錯誤上下文 mutate 遊戲物件</li>
 *   <li>{@code ACELIB-CTX-002} — 非同步流程完成後嘗試 mutate（從 main thread 送出 runAsync mutate）</li>
 *   <li>{@code ACELIB-CTX-003} — Folia 環境下非 region thread 操作 region-bound 物件</li>
 *   <li>{@code ACELIB-CTX-004} — 平台不支援此操作（UNKNOWN）</li>
 * </ul>
 *
 * <h2>除錯模式</h2>
 * 當 {@link DebugMode#isEnabled()} 為 true 且 executeAsync 被拒絕時，輸出
 * fine-level log 攜帶 code / context / op / target 完整資訊，方便診斷。
 *
 * @see ContextInspector
 * @see SafeScheduler
 * @since 1.0.0
 */
public final class SafeExecutor {

    private static final Logger LOGGER = Logger.getLogger("AceLib");

    /** 非同步流程完成後嘗試 mutate（從 main thread 送出 runAsync mutate）。 */
    static final String ERR_ASYNC_MUTATE = "ACELIB-CTX-002";
    /** 平台不支援。 */
    static final String ERR_PLATFORM_UNSUPPORTED = "ACELIB-CTX-004";

    private SafeExecutor() {
        // utility class
    }

    // -----------------------------------------------------------------
    // executeAsync
    // -----------------------------------------------------------------

    /**
     * 在 async pool 執行一個 runnable。
     *
     * <p>規則：</p>
     * <ul>
     *   <li>{@link OperationType#READ_ONLY}：直接派送，不檢查 context</li>
     *   <li>mutate 操作：必須由 caller 在 async context 自行包裝到正確的 scheduler；
     *       若從 main thread 呼叫（典型誤用）拋 {@code ACELIB-CTX-002}</li>
     *   <li>{@link Platform#UNKNOWN}：mutate 操作一律拋 {@code ACELIB-CTX-004}</li>
     * </ul>
     *
     * @param plugin     plugin owner；不可為 null
     * @param platform   偵測到的平台；不可為 null
     * @param capability 對應的 capability profile；不可為 null
     * @param op         操作類型；不可為 null
     * @param runnable   要執行的程式；不可為 null
     * @return {@link ScheduledTask} 控制代碼
     * @throws ContextException 違反上述規則時
     * @throws NullPointerException 必要參數為 null
     */
    public static ScheduledTask executeAsync(JavaPlugin plugin,
                                              Platform platform,
                                              PlatformCapability capability,
                                              OperationType op,
                                              Runnable runnable) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(runnable, "runnable");

        // 規則 1：READ_ONLY 永遠允許
        if (op == OperationType.READ_ONLY) {
            return getOrCreateScheduler(plugin, platform, capability).runAsync(runnable);
        }

        // 規則 2：UNKNOWN 平台對 mutate 一律拒絕
        if (platform == Platform.UNKNOWN) {
            throw contextException(ERR_PLATFORM_UNSUPPORTED, platform, op, "n/a",
                "platform UNKNOWN cannot perform " + op + " via runAsync");
        }

        // 規則 3：mutate 操作不應透過 executeAsync 送出
        ThreadContext ctx = ContextInspector.currentContext(platform);
        boolean mainThread = ctx == ThreadContext.PAPER_MAIN || ctx == ThreadContext.FOLIA_REGION;
        String code = mainThread ? ERR_ASYNC_MUTATE : "ACELIB-CTX-001";
        String reason = mainThread
            ? "executeAsync called from " + ctx + " for mutate operation " + op
                + "; use executeOnRegion(...) to route to a region-bound scheduler"
            : "executeAsync called from " + ctx + " for mutate operation " + op
                + "; async threads cannot mutate game objects";
        throw contextException(code, platform, op, "n/a", reason);
    }

    // -----------------------------------------------------------------
    // executeOnRegion(Player)
    // -----------------------------------------------------------------

    /**
     * 在玩家所屬 region 執行一個 runnable（預設 {@link OperationType#PLAYER_MUTATE}）。
     *
     * @param plugin     plugin owner；不可為 null
     * @param platform   偵測到的平台；不可為 null
     * @param capability 對應的 capability profile；不可為 null
     * @param player     目標玩家；不可為 null
     * @param runnable   要執行的程式；不可為 null
     * @return {@link ScheduledTask} 控制代碼
     */
    public static ScheduledTask executeOnRegion(JavaPlugin plugin,
                                                 Platform platform,
                                                 PlatformCapability capability,
                                                 Player player,
                                                 Runnable runnable) {
        return executeOnRegion(plugin, platform, capability, player, runnable,
            OperationType.PLAYER_MUTATE);
    }

    /**
     * 同上但允許指定操作類型。
     *
     * @param plugin     plugin owner；不可為 null
     * @param platform   偵測到的平台；不可為 null
     * @param capability 對應的 capability profile；不可為 null
     * @param player     目標玩家；不可為 null
     * @param runnable   要執行的程式；不可為 null
     * @param op         操作類型；可為 null（僅用於除錯模式的 annotate log，
     *                   不影響實際派送路徑）
     * @return {@link ScheduledTask} 控制代碼
     */
    public static ScheduledTask executeOnRegion(JavaPlugin plugin,
                                                 Platform platform,
                                                 PlatformCapability capability,
                                                 Player player,
                                                 Runnable runnable,
                                                 OperationType op) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(runnable, "runnable");
        SafeScheduler scheduler = getOrCreateScheduler(plugin, platform, capability);
        ScheduledTask task = scheduler.runForPlayer(player, runnable);
        // 若 scheduler 因為玩家離線/平台不支援回傳 cancelled no-op，記錄對應 code
        annotateIfCancelled(task, platform, op, "player=" + safePlayerName(player));
        return task;
    }

    // -----------------------------------------------------------------
    // executeOnRegion(Entity)
    // -----------------------------------------------------------------

    /**
     * 在實體所屬 region 執行一個 runnable（預設 {@link OperationType#ENTITY_MUTATE}）。
     *
     * @param plugin     plugin owner；不可為 null
     * @param platform   偵測到的平台；不可為 null
     * @param capability 對應的 capability profile；不可為 null
     * @param entity     目標實體；不可為 null
     * @param runnable   要執行的程式；不可為 null
     * @return {@link ScheduledTask} 控制代碼
     */
    public static ScheduledTask executeOnRegion(JavaPlugin plugin,
                                                 Platform platform,
                                                 PlatformCapability capability,
                                                 Entity entity,
                                                 Runnable runnable) {
        return executeOnRegion(plugin, platform, capability, entity, runnable,
            OperationType.ENTITY_MUTATE);
    }

    /**
     * 同上但允許指定操作類型。
     *
     * @param plugin     plugin owner；不可為 null
     * @param platform   偵測到的平台；不可為 null
     * @param capability 對應的 capability profile；不可為 null
     * @param entity     目標實體；不可為 null
     * @param runnable   要執行的程式；不可為 null
     * @param op         操作類型；可為 null（僅用於除錯模式的 annotate log，
     *                   不影響實際派送路徑）
     * @return {@link ScheduledTask} 控制代碼
     */
    public static ScheduledTask executeOnRegion(JavaPlugin plugin,
                                                 Platform platform,
                                                 PlatformCapability capability,
                                                 Entity entity,
                                                 Runnable runnable,
                                                 OperationType op) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(runnable, "runnable");
        SafeScheduler scheduler = getOrCreateScheduler(plugin, platform, capability);
        ScheduledTask task = scheduler.runForEntity(entity, runnable);
        annotateIfCancelled(task, platform, op, "entity=" + entity.getType());
        return task;
    }

    // -----------------------------------------------------------------
    // executeOnRegion(Location)
    // -----------------------------------------------------------------

    /**
     * 在位置所在 region 執行一個 runnable（預設 {@link OperationType#WORLD_MUTATE}）。
     *
     * @param plugin     plugin owner；不可為 null
     * @param platform   偵測到的平台；不可為 null
     * @param capability 對應的 capability profile；不可為 null
     * @param location   目標位置；不可為 null
     * @param runnable   要執行的程式；不可為 null
     * @return {@link ScheduledTask} 控制代碼
     */
    public static ScheduledTask executeOnRegion(JavaPlugin plugin,
                                                 Platform platform,
                                                 PlatformCapability capability,
                                                 Location location,
                                                 Runnable runnable) {
        return executeOnRegion(plugin, platform, capability, location, runnable,
            OperationType.WORLD_MUTATE);
    }

    /**
     * 同上但允許指定操作類型。
     *
     * @param plugin     plugin owner；不可為 null
     * @param platform   偵測到的平台；不可為 null
     * @param capability 對應的 capability profile；不可為 null
     * @param location   目標位置；不可為 null
     * @param runnable   要執行的程式；不可為 null
     * @param op         操作類型；可為 null（僅用於除錯模式的 annotate log，
     *                   不影響實際派送路徑）
     * @return {@link ScheduledTask} 控制代碼
     */
    public static ScheduledTask executeOnRegion(JavaPlugin plugin,
                                                 Platform platform,
                                                 PlatformCapability capability,
                                                 Location location,
                                                 Runnable runnable,
                                                 OperationType op) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(runnable, "runnable");
        SafeScheduler scheduler = getOrCreateScheduler(plugin, platform, capability);
        ScheduledTask task = scheduler.runAtLocation(location, runnable);
        annotateIfCancelled(task, platform, op,
            "world=" + (location.getWorld() != null ? location.getWorld().getName() : "?"));
        return task;
    }

    // -----------------------------------------------------------------
    // 內部輔助
    // -----------------------------------------------------------------

    /**
     * 建立對應的 SafeScheduler。此 helper 不持有 scheduler 狀態 — 每次呼叫
     * new instance，dispatcher 內部使用弱一致的 recorder。
     */
    private static SafeScheduler getOrCreateScheduler(JavaPlugin plugin,
                                                       Platform platform,
                                                       PlatformCapability capability) {
        return new com.smile.acelib.scheduler.SafeSchedulerImpl(plugin, platform, capability);
    }

    private static void annotateIfCancelled(ScheduledTask task,
                                             Platform platform,
                                             OperationType op,
                                             String targetInfo) {
        if (task == null || !task.isCancelled()) {
            return;
        }
        TaskType type = task.getType();
        // dispatcher 已經透過 SafeSchedulerImpl.recorder 留下 SCHED-005/006 紀錄，
        // SafeExecutor 這層只在除錯模式下額外輸出 fine-level log。
        if (DebugMode.isEnabled()) {
            LOGGER.log(Level.FINE,
                "SafeExecutor: task cancelled; type={0}, op={1}, target={2}, platform={3}",
                new Object[] { type, op, targetInfo, platform });
        }
    }

    private static ContextException contextException(String code,
                                                      Platform platform,
                                                      OperationType op,
                                                      String targetInfo,
                                                      String message) {
        ThreadContext ctx = ContextInspector.currentContext(platform);
        ContextException ex = new ContextException(code, ctx, op, targetInfo, message);
        if (DebugMode.isEnabled()) {
            LOGGER.log(Level.FINE,
                "SafeExecutor: {0} | context={1}, op={2}, target={3}, msg={4}",
                new Object[] { code, ctx, op, targetInfo, message });
        }
        return ex;
    }

    private static String safePlayerName(Player player) {
        try {
            return player.getName() != null ? player.getName() : "?";
        } catch (Throwable t) {
            return "?";
        }
    }
}