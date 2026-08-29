package com.smile.acelib.scheduler;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Folia regionized scheduler backend（Internal）。
 *
 * <p>透過 reflection 呼叫 {@code io.papermc.paper.threadedregions.scheduler.*}
 * 系列 API；classpath 不含 Folia API 時（典型 MockBukkit 環境）拋
 * {@link IllegalStateException}，由 {@link SafeSchedulerImpl} 統一以
 * {@code ACELIB-SCHED-005} 記錄並回傳 no-op task（fail-closed）。</p>
 *
 * <p>本類別為 package-private，不進 API surface。</p>
 */
final class FoliaSchedulerBackend implements SchedulerBackend {

    private final JavaPlugin plugin;

    FoliaSchedulerBackend(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public BukkitTask dispatch(TaskType type,
                               Runnable wrapped,
                               Player player,
                               Object entityOrLoc,
                               long delayTicks,
                               long periodTicks,
                               boolean async) throws Exception {
        try {
            if (async) {
                // AsyncScheduler.runNow(plugin, consumer)
                Object asyncSched = Bukkit.class.getMethod("getAsyncScheduler").invoke(null);
                Class<?> asyncCls = asyncSched.getClass();
                invokeAsyncNow(asyncCls, asyncSched, plugin, wrapped);
                return new DetachedBukkitTask(plugin, type);
            }
            if (player == null && !(entityOrLoc instanceof Entity)) {
                // GlobalRegionScheduler
                Object grs = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
                if (periodTicks > 0L) {
                    invokeGlobalAtFixedRate(grs, plugin, wrapped, delayTicks, periodTicks);
                } else if (delayTicks > 0L) {
                    invokeGlobalDelayed(grs, plugin, wrapped, delayTicks);
                } else {
                    invokeGlobalRun(grs, plugin, wrapped);
                }
                return new DetachedBukkitTask(plugin, type);
            }
            if (player != null) {
                // EntityScheduler (player)
                Object es = Entity.class.getMethod("getScheduler").invoke(player);
                if (delayTicks > 0L) {
                    invokeEntityDelayed(es, plugin, wrapped, delayTicks);
                } else {
                    invokeEntityRun(es, plugin, wrapped);
                }
                return new DetachedBukkitTask(plugin, type);
            }
            if (entityOrLoc instanceof Entity ent) {
                Object es = Entity.class.getMethod("getScheduler").invoke(ent);
                if (delayTicks > 0L) {
                    invokeEntityDelayed(es, plugin, wrapped, delayTicks);
                } else {
                    invokeEntityRun(es, plugin, wrapped);
                }
                return new DetachedBukkitTask(plugin, type);
            }
            if (entityOrLoc instanceof Location loc) {
                Object rs = Bukkit.class.getMethod("getRegionScheduler").invoke(null);
                invokeRegionExecute(rs, plugin, loc, wrapped);
                return new DetachedBukkitTask(plugin, type);
            }
            throw new IllegalStateException("unsupported Folia dispatch combination for " + type);
        } catch (NoSuchMethodException e) {
            // Folia API 不存在於此 classpath（典型 MockBukkit 環境）
            throw new IllegalStateException("Folia scheduler API not present: " + e.getMessage(), e);
        } catch (InvocationTargetException e) {
            // 反射呼叫本身成功，但底層丟例外
            Throwable cause = e.getTargetException();
            throw new RuntimeException("Folia dispatch threw: " + safeMessage(cause), cause);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Folia API not accessible: " + e.getMessage(), e);
        }
    }

    // --- Folia reflection helpers (cache lookups per call; not on hot path) ---

    private static void invokeAsyncNow(Class<?> asyncCls, Object asyncSched,
                                       JavaPlugin plugin, Runnable r) throws Exception {
        // AsyncScheduler.runNow(Plugin, Consumer) — Consumer<Object>
        Method m = asyncCls.getMethod("runNow",
            org.bukkit.plugin.Plugin.class, java.util.function.Consumer.class);
        m.invoke(asyncSched, plugin, toConsumer(r));
    }

    private static void invokeGlobalRun(Object grs, JavaPlugin plugin, Runnable r) throws Exception {
        // GlobalRegionScheduler.run(Plugin, Consumer)
        Method m = grs.getClass().getMethod("run",
            org.bukkit.plugin.Plugin.class, java.util.function.Consumer.class);
        m.invoke(grs, plugin, toConsumer(r));
    }

    private static void invokeGlobalDelayed(Object grs, JavaPlugin plugin, Runnable r, long delay) throws Exception {
        Method m = grs.getClass().getMethod("runDelayed",
            org.bukkit.plugin.Plugin.class, java.util.function.Consumer.class, long.class);
        m.invoke(grs, plugin, toConsumer(r), delay);
    }

    private static void invokeGlobalAtFixedRate(Object grs, JavaPlugin plugin, Runnable r,
                                                long delay, long period) throws Exception {
        Method m = grs.getClass().getMethod("runAtFixedRate",
            org.bukkit.plugin.Plugin.class, java.util.function.Consumer.class, long.class, long.class);
        m.invoke(grs, plugin, toConsumer(r), delay, period);
    }

    private static void invokeEntityRun(Object es, JavaPlugin plugin, Runnable r) throws Exception {
        // EntityScheduler.run(Plugin, Consumer, Runnable)
        Method m = es.getClass().getMethod("run",
            org.bukkit.plugin.Plugin.class, java.util.function.Consumer.class, Runnable.class);
        m.invoke(es, plugin, toConsumer(r), null);
    }

    private static void invokeEntityDelayed(Object es, JavaPlugin plugin, Runnable r, long delay) throws Exception {
        Method m = es.getClass().getMethod("runDelayed",
            org.bukkit.plugin.Plugin.class, java.util.function.Consumer.class, Runnable.class, long.class);
        m.invoke(es, plugin, toConsumer(r), null, delay);
    }

    private static void invokeRegionExecute(Object rs, JavaPlugin plugin, Location loc, Runnable r) throws Exception {
        // RegionScheduler.execute(Plugin, Location, Consumer)
        Method m = rs.getClass().getMethod("execute",
            org.bukkit.plugin.Plugin.class, Location.class, java.util.function.Consumer.class);
        m.invoke(rs, plugin, loc, toConsumer(r));
    }

    /**
     * 將 {@link Runnable} 包裝成 Folia API 期待的 {@code Consumer<Object>}。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static java.util.function.Consumer<Object> toConsumer(Runnable r) {
        return o -> r.run();
    }

    private static String safeMessage(Throwable t) {
        if (t == null) {
            return "(null throwable)";
        }
        String m = t.getMessage();
        return m != null ? m : t.getClass().getSimpleName();
    }
}
