package com.smile.acelib.command;

import com.smile.acelib.diagnostics.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 指令冷卻追蹤器。
 *
 * <p>防止「短時間重複觸發」：對於 {@link SubCommandSpec#cooldownMillis()} 大於 0
 * 的子指令，dispatcher 在每次進入時透過 {@link #tryAcquire} 嘗試取得鎖。</p>
 *
 * <h2>語意</h2>
 * <ul>
 *   <li>key = {@code "<command>:<subcommand>"}（dispatcher 內部組合）</li>
 *   <li>同一玩家對同一 subcommand 在冷卻時間內只能 acquire 一次</li>
 *   <li>acquire 成功 → 記錄新的過期時間；acquire 失敗 → 既有過期時間不變，
 *       可透過 {@link #remainingMillis} 查剩餘時間</li>
 *   <li>非玩家 sender（console）→ 視為不可冷卻（永遠 acquire 成功）</li>
 * </ul>
 *
 * <h2>Reload 行為</h2>
 * <p>冷卻狀態<strong>不</strong>在 {@link #clearAll()} 以外的流程清除 —
 * 冷卻 / 防重複觸發在 reload 過程中也不破壞狀態。Plugin disable 時
 * registry 整個釋放，冷卻 tracker 仍可保留狀態直到 GC。</p>
 *
 * <h2>執行緒安全</h2>
 * <p>使用 {@link ConcurrentHashMap}，但臨界區一律落在<strong>外層的 per-player
 * 槽位</strong>：{@link #tryAcquire} 與 {@link #pruneExpired} 都對
 * {@code expiresAt.compute/computeIfPresent(playerId, ...)} 操作，因此兩者共用同一把
 * per-player 鎖。</p>
 *
 * <p>這樣做而不是對內層 subKey 做 compute：{@link #pruneExpired} 會在表清空時把整張
 * 內層 map 從外層摘掉；若取得 map 參考與寫入之間沒有同一把鎖護著，寫入就會落在一張
 * 已經沒有人引用的孤兒 map 上 — 冷卻直接消失、同一冷卻窗被放行多次。共用一把鎖之後，
 * 「讀既有過期時間 → 寫新過期時間 → 決定放行」與「清過期 → 必要時摘掉表」不可能交錯。</p>
 *
 * <p>同一 key 因此不會在同一冷卻窗被多個執行緒同時放行。</p>
 *
 * @see SubCommandSpec#cooldownMillis()
 * @since 1.0.0
 */
public final class CooldownTracker {

    /**
     * 過期時間表：{@code playerId → (subKey → epochMillis expiresAt)}。
     *
     * <p>{@code subKey} 由 dispatcher 組合為 {@code "<commandName>:<subName>"}，
     * 確保同一個 {@link SubCommandSpec} 在不同主指令下冷卻獨立。</p>
     */
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, Long>> expiresAt;
    private final Clock clock;

    /**
     * 主要建構子（production code）。
     *
     * <p>使用 {@link Clock#system()} 作為時間來源；測試可改用注入
     * {@link #CooldownTracker(Clock)} 以 deterministic 行為。</p>
     */
    public CooldownTracker() {
        this(Clock.system());
    }

    /**
     * 注入式建構子（測試 seam）。
     *
     * @param clock 時鐘來源；不可為 null
     * @throws NullPointerException 當 {@code clock} 為 null
     */
    public CooldownTracker(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.expiresAt = new ConcurrentHashMap<>();
    }

    /**
     * 嘗試取得冷卻鎖。
     *
     * <p>規則：</p>
     * <ul>
     *   <li>{@code cooldownMillis <= 0} → 直接成功（無冷卻）</li>
     *   <li>既有過期時間 &gt; now → 失敗</li>
     *   <li>既有過期時間 &le; now 或無記錄 → 成功並更新過期時間</li>
     * </ul>
     *
     * @param playerId        玩家 UUID；不可為 null
     * @param subKey          子指令 key（dispatcher 組合為 {@code "<cmd>:<sub>"}）；不可為 null
     * @param cooldownMillis  冷卻時間（毫秒）；&le;0 表示無冷卻
     * @return true 表示取得鎖（可繼續執行）；false 表示仍在冷卻中
     * @throws NullPointerException 當 {@code playerId} 或 {@code subKey} 為 null
     */
    public boolean tryAcquire(UUID playerId, String subKey, long cooldownMillis) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(subKey, "subKey");
        if (cooldownMillis <= 0) {
            return true;
        }
        long now = clock.currentTimeMillis();
        boolean[] granted = new boolean[1];
        // 單次原子決策，而且臨界區必須落在「該玩家的整張冷卻表」上，而不是
        // 內層的單一 subKey：
        //
        // - 對內層 subKey 做 compute 只能擋住同一 subKey 的併發寫入，但
        //   pruneExpired 會把整張內層 map 從外層摘掉；acquire 先取得內層 map
        //   參考、prune 再摘掉、acquire 後寫入，就會把「有效冷卻」寫進一張
        //   已經沒有人引用的孤兒 map（冷卻直接消失）。
        // - 對外層 playerId 做 compute 讓「取得內層 map → 讀 prev → 寫新 expiry
        //   → 決定放行」整段都在同一把 per-player 鎖內完成，prune 也用同一把鎖，
        //   兩者就不可能交錯成遺失寫入。
        expiresAt.compute(playerId, (uuid, per) -> {
            ConcurrentHashMap<String, Long> table =
                per != null ? per : new ConcurrentHashMap<>();
            Long prev = table.get(subKey);
            if (prev != null && prev > now) {
                granted[0] = false;
                return table;
            }
            table.put(subKey, now + cooldownMillis);
            granted[0] = true;
            return table;
        });
        return granted[0];
    }

    /**
     * 查詢剩餘冷卻時間。
     *
     * @param playerId 玩家 UUID；不可為 null
     * @param subKey   子指令 key
     * @return 剩餘毫秒數；若未冷卻中或冷卻已過期則回傳 0
     * @throws NullPointerException 當 {@code playerId} 或 {@code subKey} 為 null
     */
    public long remainingMillis(UUID playerId, String subKey) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(subKey, "subKey");
        ConcurrentHashMap<String, Long> perPlayer = expiresAt.get(playerId);
        if (perPlayer == null) return 0;
        Long exp = perPlayer.get(subKey);
        if (exp == null) return 0;
        return Math.max(0, exp - clock.currentTimeMillis());
    }

    /**
     * 清除單一玩家的所有冷卻記錄。
     *
     * <p>給 {@code /cooldown clear <player>} 等管理指令使用；正常 dispatch 流程
     * 不會呼叫此方法。</p>
     *
     * @param playerId 玩家 UUID；不可為 null
     */
    public void clear(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        expiresAt.remove(playerId);
    }

    /**
     * 清除所有冷卻記錄。
     *
     * <p>給 plugin disable / reload 流程使用 — 但 disable 時<strong>不</strong>
     * 清除（保留 reload 連續性）。</p>
     */
    public void clearAll() {
        expiresAt.clear();
    }

    /**
     * 移除所有已過期的冷卻紀錄（含空的 per-player map）。
     *
     * <p>長期運行時大量一次性 key 會讓 {@link #trackedPlayerCount()} 與
     * map 成長；呼叫方應在適當時機（例如 reload、定期維護）呼叫本方法。</p>
     */
    public void pruneExpired() {
        long now = clock.currentTimeMillis();
        // 逐玩家在「外層 playerId 的鎖」內清理，而不是先 forEach 再 remove：
        // 刪除與 {@link #tryAcquire} 共用同一把鎖，acquire 就不可能取得一張
        // 隨即被摘掉的內層 map 而把有效冷卻寫丟。
        //
        // computeIfPresent 回傳 null 代表移除這個 playerId，正好對應「清空後
        // 不留空殼」的語意；同時避免長期運行留下大量空 per-player map。
        for (UUID playerId : expiresAt.keySet()) {
            expiresAt.computeIfPresent(playerId, (uuid, per) -> {
                per.entrySet().removeIf(entry -> entry.getValue() <= now);
                return per.isEmpty() ? null : per;
            });
        }
    }

    /**
     * 取得當前追蹤的玩家數（測試用）。
     *
     * @return tracker 中不同玩家 UUID 的數量
     */
    public int trackedPlayerCount() {
        return expiresAt.size();
    }

    /**
     * 取得當前 tracker 的不可變快照（測試用）。
     *
     * <p>回傳 map 的 key 為玩家 UUID，value 為該玩家的 subKey → 過期時間 map；
     * 修改回傳值不影響內部狀態。</p>
     *
     * @return 不可變快照
     */
    public Map<UUID, Map<String, Long>> snapshot() {
        Map<UUID, Map<String, Long>> snap = new java.util.HashMap<>();
        expiresAt.forEach((uuid, per) -> snap.put(uuid, Map.copyOf(per)));
        return Map.copyOf(snap);
    }
}