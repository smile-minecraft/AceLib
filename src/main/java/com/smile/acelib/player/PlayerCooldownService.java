package com.smile.acelib.player;

import com.smile.acelib.diagnostics.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家冷卻服務。
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
 * <p>內部使用 {@link ConcurrentHashMap}，但臨界區一律落在<strong>外層的 per-player
 * 槽位</strong>：{@link #start}、{@link #tryAcquire}、{@link #end} 與
 * {@link #pruneExpired} 都對 {@code expiresAt.compute/computeIfPresent(playerId, ...)}
 * 操作，四者共用同一把 per-player 鎖。</p>
 *
 * <p>這樣做而不是「先 {@code computeIfAbsent} 拿到內層 map、再對內層 key 寫入」：
 * {@link #pruneExpired} 會在表清空時把整張內層 map 從外層摘掉；若取得 map 參考與寫入
 * 之間沒有同一把鎖護著，寫入就會落在一張已經沒有人引用的孤兒 map 上 — 冷卻直接消失、
 * 同一冷卻窗被放行多次。共用一把鎖之後，「取得表 → 讀既有過期時間 → 寫新過期時間 →
 * 決定放行」不可能與清理交錯。</p>
 *
 * <p>同一 key 因此不會在同一冷卻窗被多個執行緒同時放行。</p>
 *
 * <h2>名稱變更</h2>
 * <p>以 {@link UUID} 為唯一索引 key；玩家更名（同 UUID 不同 name）不影響
 * 既有冷卻狀態。</p>
 *
 * @see PlayerSession
 * @since 1.0.0
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
        long expiresAtMillis = clock.currentTimeMillis() + durationMillis;
        // 「建立內層 map（必要時）→ 寫入過期時間」整段放在外層 playerId 的鎖內。
        // 拆成 computeIfAbsent + put 兩步時，{@link #pruneExpired} 可以在兩步
        // 之間把整張空 map 摘掉，讓新冷卻寫進沒有人引用的孤兒 map（冷卻直接消失）。
        expiresAt.compute(playerId, (uuid, per) -> {
            ConcurrentHashMap<String, Long> table =
                per != null ? per : new ConcurrentHashMap<>();
            table.put(cooldownKey, expiresAtMillis);
            return table;
        });
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
        long now = clock.currentTimeMillis();
        boolean[] granted = new boolean[1];
        // 單次原子決策，而且臨界區落在「該玩家的整張冷卻表」上：
        //
        // - 只對內層 cooldownKey 做 compute 擋不住 prune 把整張內層 map 摘掉；
        //   acquire 先取得 map 參考、prune 再摘掉、acquire 後寫入，新冷卻就會寫進
        //   已經沒有人引用的孤兒 map（冷卻直接消失，同一冷卻窗被放行多次）。
        // - 對外層 playerId 做 compute 讓「取得 map → 讀 prev → 寫新 expiry →
        //   決定放行」整段都在同一把 per-player 鎖內完成，prune 用同一把鎖，
        //   兩者不可能交錯成遺失寫入。
        expiresAt.compute(playerId, (uuid, per) -> {
            ConcurrentHashMap<String, Long> table =
                per != null ? per : new ConcurrentHashMap<>();
            Long prev = table.get(cooldownKey);
            if (prev != null && prev > now) {
                granted[0] = false;
                return table;
            }
            table.put(cooldownKey, now + durationMillis);
            granted[0] = true;
            return table;
        });
        return granted[0];
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
        // 與 pruneExpired 共用外層鎖：刪除後的「這張表是否已空」由鎖內判定，
        // prune 才不會在旁邊看見非空而留下一張永遠不再被引用的空殼。
        expiresAt.computeIfPresent(playerId, (uuid, per) -> {
            per.remove(cooldownKey);
            return per;
        });
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
     * 移除所有已過期的冷卻紀錄（含空的 per-player map）。
     *
     * <p>長期運行時大量一次性 key 會讓 map 成長；呼叫方應在適當時機
     * （例如 reload、定期維護）呼叫本方法。</p>
     */
    public void pruneExpired() {
        long now = clock.currentTimeMillis();
        // 逐玩家在「外層 playerId 的鎖」內清理：刪除與 {@link #start} /
        // {@link #tryAcquire} 共用同一把鎖，寫入就不會寫進已被摘掉的孤兒 map。
        // computeIfPresent 回傳 null 代表移除這個 playerId，讓長期運行不會留下
        // 大量空殼 per-player map。
        for (UUID playerId : expiresAt.keySet()) {
            expiresAt.computeIfPresent(playerId, (uuid, per) -> {
                per.entrySet().removeIf(entry -> entry.getValue() <= now);
                return per.isEmpty() ? null : per;
            });
        }
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
