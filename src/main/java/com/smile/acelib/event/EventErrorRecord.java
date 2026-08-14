package com.smile.acelib.event;

import java.util.Objects;
import org.bukkit.event.Event;

/**
 * 事件錯誤紀錄（immutable record）。
 *
 * <p>當 {@link SafeEventRegistry} dispatch listener 時發生例外、重複註冊、
 * 插件停用等情境，皆會包裝為 {@code EventErrorRecord} 並送入
 * {@link EventErrorRecorder}。</p>
 *
 * <h2>欄位語意</h2>
 * <ul>
 *   <li>{@link #eventType} — 觸發此錯誤的 Bukkit Event 型別（不可為 null）</li>
 *   <li>{@link #code} — 錯誤分類代碼，遵循 {@code ACELIB-EVT-xxx} 格式
 *       （參見 CONTRIBUTING.md §6）</li>
 *   <li>{@link #detail} — 人類可讀的詳細訊息（含目標 listener 資訊）</li>
 *   <li>{@link #cause} — 觸發此錯誤的例外（dispatch 失敗時攜帶；其他情境為 null）</li>
 *   <li>{@link #tick} — 紀錄產生時的伺服器 tick（測試環境若無
 *       {@code Bukkit.getCurrentTick()} 則為 0）</li>
 * </ul>
 *
 * <h2>硬性約束</h2>
 * <ul>
 *   <li>{@code eventType}、{@code code}、{@code detail} 不可為 null</li>
 *   <li>{@code cause} 在「取消類」錯誤下可為 null；於「執行錯誤」下必須帶實際例外</li>
 *   <li>{@code tick} 可為 0（測試環境），不視為錯誤</li>
 * </ul>
 *
 * @param eventType 觸發此錯誤的 Bukkit Event 型別；不可為 null
 * @param code      錯誤分類代碼（{@code ACELIB-EVT-*} 格式）；不可為 null
 * @param detail    人類可讀的詳細訊息；不可為 null
 * @param cause     觸發此錯誤的例外；取消類錯誤可為 null
 * @param tick      紀錄產生時的伺服器 tick；測試環境可能為 0
 * @see EventErrorRecorder
 * @see SafeEventRegistry
 * @since 1.0.0
 */
public record EventErrorRecord(
    Class<? extends Event> eventType,
    String code,
    String detail,
    Throwable cause,
    long tick
) {

    /**
     * Compact constructor：對不可空欄位做 null 檢查，給予清楚的失敗訊息。
     *
     * @throws NullPointerException 當 {@code eventType} / {@code code} / {@code detail} 為 null
     */
    public EventErrorRecord {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(detail, "detail");
        // cause 可為 null（取消類錯誤）；tick 可為任意 long
    }

    /**
     * 建立「取消類」錯誤紀錄（無 cause）。
     *
     * <p>適用情境：重複註冊（{@code ACELIB-EVT-003}）、插件停用（{@code ACELIB-EVT-004}）、
     * Folia 環境下 REQUIRES_REGION listener 在錯誤 context（{@code ACELIB-EVT-005}）。
     * 這些情境下沒有實際例外，因此 {@link #cause} 為 null。</p>
     *
     * @param eventType 觸發此錯誤的 Bukkit Event 型別；不可為 null
     * @param code      錯誤代碼；不可為 null
     * @param detail    詳細訊息；不可為 null
     * @return 新的 {@link EventErrorRecord} 實例
     */
    public static EventErrorRecord cancelled(Class<? extends Event> eventType,
                                             String code,
                                             String detail) {
        return new EventErrorRecord(eventType, code, detail, null, currentTick());
    }

    /**
     * 建立「例外類」錯誤紀錄（handler 內部拋錯）。
     *
     * <p>適用情境：listener 內部拋 exception（{@code ACELIB-EVT-001}）或
     * dispatch 階段拋錯。若傳入的 {@code cause} 為 null，會自動替換為
     * {@code new Throwable("null-cause")} 以保留例外槽位語意。</p>
     *
     * @param eventType 觸發此錯誤的 Bukkit Event 型別；不可為 null
     * @param code      錯誤代碼；不可為 null
     * @param detail    詳細訊息；不可為 null
     * @param cause     觸發此錯誤的例外；可為 null（會被替換為 sentinel）
     * @return 新的 {@link EventErrorRecord} 實例
     */
    public static EventErrorRecord threw(Class<? extends Event> eventType,
                                         String code,
                                         String detail,
                                         Throwable cause) {
        Throwable actual = (cause != null) ? cause : new Throwable("null-cause");
        return new EventErrorRecord(eventType, code, detail, actual, currentTick());
    }

    /**
     * 嘗試從 {@code Bukkit.getCurrentTick()} 取得目前 tick；若環境不支援
     * （純單元測試未初始化 MockBukkit，或舊版 Bukkit），回傳 0。
     *
     * <p>不丟例外，確保任何環境下都能建立紀錄。</p>
     */
    private static long currentTick() {
        try {
            return org.bukkit.Bukkit.getCurrentTick();
        } catch (Throwable t) {
            return 0L;
        }
    }
}