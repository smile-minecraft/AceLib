package com.smile.acelib.player;

import com.smile.acelib.diagnostics.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家冷卻服務（Plan §十四 Phase 9）。
 *
 * <p>獨立於指令系統的純 {@link UUID} + cooldown key 冷卻管理；提供
 * start / query / tryAcquire / end / clear 完整 API。與
 * {@code command.CooldownTracker} 的差異：</p>
 *
 * <ul>
 *   <li>本服務以「明確 API」對外（{@link #start} / {@link #end}），
 *       命令冷卻則是 dispatcher 內部隱式管理</li>
 *   <li>本服務不區分子指令組合 key（key 由 caller 提供）</li>
 *   <li>本服務支援「清除指定 key」、「清除單一玩家」、「清除全部」
 *       三種粒度，便於 reload/disable 管理</li>
 * </ul>
 *
 * <h2>時鐘來源</h2>
 * <p>透過 {@link Clock} 注入時間，預設使用 {@link Clock#system()}。
 * 測試全程使用 deterministic clock，禁止 sleep。</p>
 *
 * <h2>執行緒安全</h2>
 * <p>內部使用 {@link ConcurrentHashMap}，所有 public 方法 thread-safe。</p>
 *
 * <h2>名稱變更</h2>
 * <p>以 {@link UUID} 為唯一索引 key；玩家更名（同 UUID 不同 name）不影響
 * 既有冷卻狀態。</p>
 *
 * @see PlayerSession
 * @since Phase 9 (Plan §十四)
 */
public final class PlayerCooldownService {

    /** key = playerId, value = subKey → expiresAt epochMillis */
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, Long>> expiresAt;
    private final Clock clock;

    /**
     * 主要建構子（production code）。
     *
     * <p>使用 {@link Clock#system()} 作為時間來源。</p>
     */
    public PlayerCooldownService() {
        this(Clock.system());
    }

    /**
     * 注入式建構子（測試 seam）。
     *
     * @param clock 時鐘來源；不可為 null
     * @throws NullPointerException 當 {@code clock} 為 null
     */
    public PlayerCooldownService(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.expiresAt = new ConcurrentHashMap<>();
    }

    /**
     * 啟動冷卻（管理員指令 / 技能觸發後呼叫）。
     *
     * <p>規則：</p>
     * <ul>
     *   <li>{@code durationMillis <= 0} → 拋 {@link IllegalArgumentException}</li>
     *   <li>既有冷卻將被新值覆寫（重新觸發場景）</li>
     * </ul>
     *
     * @param playerId       玩家 UUID；不可為 null
     * @param cooldownKey    冷卻 key；不可為 null
     * @param durationMillis 冷卻時長（毫秒）；必須 &gt; 0
     * @throws NullPointerException     {@code playerId} 或 {@code cooldownKey} 為 null
     * @throws IllegalArgumentException {@code durationMillis <= 0}
     */
    public void start(UUID playerId, String cooldownKey, long durationMillis) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(cooldownKey, "cooldownKey");
        if (durationMillis <= 0) {
            throw new IllegalArgumentException(
                "durationMillis must be > 0, actual: " + durationMillis);
        }
        ConcurrentHashMap<String, Long> perPlayer =
            expiresAt.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
        perPlayer.put(cooldownKey, clock.currentTimeMillis() + durationMillis);
    }

    /**
     * 嘗試取得冷卻鎖；若冷卻中則不更新既有過期時間。
     *
     * <p>規則：</p>
     * <ul>
     *   <li>{@code durationMillis <= 0} → 永遠 true（無冷卻）</li>
     *   <li>既有過期時間 &gt; now → 回傳 false</li>
     *   <li>既有過期時間 &le; now 或無記錄 → 回傳 true 並啟動新冷卻</li>
     * </ul>
     *
     * @param playerId       玩家 UUID；不可為 null
     * @param cooldownKey    冷卻 key；不可為 null
     * @param durationMillis 冷卻時長（毫秒）；&le;0 表示無冷卻
     * @return true 表示取得鎖（可繼續執行）；false 表示仍在冷卻中
     */
    public boolean tryAcquire(UUID playerId, String cooldownKey, long durationMillis) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(cooldownKey, "cooldownKey");
        if (durationMillis <= 0) {
            return true;
        }
        ConcurrentHashMap<String, Long> perPlayer =
            expiresAt.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
        long now = clock.currentTimeMillis();
        Long prev = perPlayer.get(cooldownKey);
        if (prev != null && prev > now) {
            return false;
        }
        perPlayer.put(cooldownKey, now + durationMillis);
        return true;
    }

    /**
     * 查詢剩餘冷卻時間。
     *
     * @param playerId    玩家 UUID；不可為 null
     * @param cooldownKey 冷卻 key；不可為 null
     * @return 剩餘毫秒數；若未啟動或已過期回傳 0
     */
    public long remainingMillis(UUID playerId, String cooldownKey) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(cooldownKey, "cooldownKey");
        ConcurrentHashMap<String, Long> perPlayer = expiresAt.get(playerId);
        if (perPlayer == null) {
            return 0L;
        }
        Long exp = perPlayer.get(cooldownKey);
        if (exp == null) {
            return 0L;
        }
        return Math.max(0L, exp - clock.currentTimeMillis());
    }

    /**
     * 清除單一玩家的單一 key 冷卻。
     *
     * @param playerId    玩家 UUID；不可為 null
     * @param cooldownKey 冷卻 key；不可為 null
     */
    public void end(UUID playerId, String cooldownKey) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(cooldownKey, "cooldownKey");
        ConcurrentHashMap<String, Long> perPlayer = expiresAt.get(playerId);
        if (perPlayer != null) {
            perPlayer.remove(cooldownKey);
        }
    }

    /**
     * 清除單一玩家的所有冷卻。
     *
     * @param playerId 玩家 UUID；不可為 null
     */
    public void endAll(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        expiresAt.remove(playerId);
    }

    /**
     * 清除所有玩家的所有冷卻（reload / disable 使用）。
     *
     * <p>冪等；重複呼叫不丟例外。</p>
     */
    public void clearAll() {
        expiresAt.clear();
    }

    /**
     * 取得當前追蹤的不同玩家數（測試 / 觀察用）。
     *
     * @return tracker 中不同玩家 UUID 的數量
     */
    public int trackedPlayerCount() {
        return expiresAt.size();
    }

    /**
     * 取得當前 tracker 的不可變快照（測試用）。
     *
     * @return 不可變快照
     */
    public Map<UUID, Map<String, Long>> snapshot() {
        Map<UUID, Map<String, Long>> snap = new java.util.HashMap<>();
        expiresAt.forEach((uuid, per) -> snap.put(uuid, Map.copyOf(per)));
        return Map.copyOf(snap);
    }
}
