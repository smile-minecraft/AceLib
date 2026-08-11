package com.smile.acelib.player;

import com.smile.acelib.data.DataStoreException;
import com.smile.acelib.data.MemoryRecord;
import com.smile.acelib.data.Record;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Phase 9：對 {@link MemoryRecord} 的執行緒安全包裝。
 *
 * <p>對應 Momus G4 blocking finding：「caller 直接 mutation + service 並發 snapshot
 * 對非 thread-safe {@link MemoryRecord} 內部 {@code LinkedHashMap} 造成
 * {@link java.util.ConcurrentModificationException}」。</p>
 *
 * <h2>設計</h2>
 * <ul>
 *   <li>內部以同一個 {@code ReentrantLock}（{@link #lock}）保護所有
 *       {@link Record} 操作（{@code set}/{@code remove}/{@code get}/
 *       {@code keys}/{@code snapshot}/{@code copy} 等）</li>
 *   <li>caller 透過 {@link PlayerDataService#getData(UUID)} 取得此 wrapper；
 *       後續 {@code set}/{@code get} 等呼叫皆會 lock，與 service 在 serial
 *       executor 上執行的 snapshot 序列化</li>
 *   <li>底層 {@link MemoryRecord} 不可變（player service 透過
 *       {@code replaceContents} 變更整體內容時也會 lock）</li>
 * </ul>
 *
 * <h2>與既有 contract 的相容性</h2>
 * <ul>
 *   <li>仍實作 {@link Record} 介面；既有測試呼叫 {@code set(path, value)} 等
 *       行為不變</li>
 *   <li>{@link #snapshot()} 回傳的 {@link Map} 為不可變副本，caller 可安全
 *       使用而無需擔心後續 mutation</li>
 * </ul>
 *
 * @since Phase 9 (Plan §十四) — Momus G4 blocking 收斂
 */
final class LockedPlayerRecord implements Record {

    /** 對應玩家 session 的 lock — 與 service 內 snapshot 共享。 */
    final Object lock;
    /** 底層 mutable record — 所有存取皆須先取得 {@link #lock}。 */
    private final MemoryRecord delegate;

    LockedPlayerRecord(MemoryRecord delegate) {
        this(delegate, new Object());
    }

    private LockedPlayerRecord(MemoryRecord delegate, Object lock) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.lock = Objects.requireNonNull(lock, "lock");
    }

    /**
     * 取得底層 delegate（測試用）。
     *
     * @return 底層 {@link MemoryRecord}
     */
    MemoryRecord delegate() {
        return delegate;
    }

    @Override
    public String key() {
        synchronized (lock) {
            return delegate.key();
        }
    }

    @Override
    public boolean has(String path) {
        synchronized (lock) {
            return delegate.has(path);
        }
    }

    @Override
    public Object get(String path) {
        synchronized (lock) {
            return copyValue(delegate.get(path));
        }
    }

    @Override
    public Object set(String path, Object value) {
        synchronized (lock) {
            return copyValue(delegate.set(path, copyValue(value)));
        }
    }

    @Override
    public boolean remove(String path) {
        synchronized (lock) {
            return delegate.remove(path);
        }
    }

    @Override
    public Set<String> keys() {
        synchronized (lock) {
            return delegate.keys();
        }
    }

    @Override
    public Record copy() {
        synchronized (lock) {
            Map<String, Object> copied = snapshotContents(delegate.snapshot());
            return new LockedPlayerRecord(new MemoryRecord(delegate.key(), copied));
        }
    }

    @Override
    public String getString(String path, String defaultValue) {
        synchronized (lock) {
            return delegate.getString(path, defaultValue);
        }
    }

    @Override
    public int getInt(String path, int defaultValue) {
        synchronized (lock) {
            return delegate.getInt(path, defaultValue);
        }
    }

    @Override
    public long getLong(String path, long defaultValue) {
        synchronized (lock) {
            return delegate.getLong(path, defaultValue);
        }
    }

    @Override
    public double getDouble(String path, double defaultValue) {
        synchronized (lock) {
            return delegate.getDouble(path, defaultValue);
        }
    }

    @Override
    public boolean getBoolean(String path, boolean defaultValue) {
        synchronized (lock) {
            return delegate.getBoolean(path, defaultValue);
        }
    }

    @Override
    public Record getRecord(String path, Record defaultValue) {
        synchronized (lock) {
            Record nested = delegate.getRecord(path, null);
            if (nested instanceof MemoryRecord memoryRecord) {
                return new LockedPlayerRecord(memoryRecord, lock);
            }
            return defaultValue;
        }
    }

    @Override
    public <T> T getObject(String path, Class<T> type, T defaultValue) {
        synchronized (lock) {
            Objects.requireNonNull(type, "type");
            Object value = delegate.getObject(path, type, defaultValue);
            if (value instanceof MemoryRecord memoryRecord && Record.class.isAssignableFrom(type)) {
                return type.cast(new LockedPlayerRecord(memoryRecord, lock));
            }
            return type.cast(copyValue(value));
        }
    }

    /**
     * 在 {@link #lock} 保護下取得不可變 snapshot（service 在 serial executor 上呼叫）。
     *
     * @return 不可變的 {@link Map}
     */
    Map<String, Object> snapshotLocked() {
        synchronized (lock) {
            return snapshotContents(delegate.snapshot());
        }
    }

    private static Map<String, Object> snapshotContents(Map<String, Object> source) {
        Map<String, Object> copied = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            copied.put(entry.getKey(), copyValue(entry.getValue()));
        }
        return copied;
    }

    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copied = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copied.put(entry.getKey(), copyValue(entry.getValue()));
            }
            return copied;
        }
        if (value instanceof List<?> list) {
            List<Object> copied = new ArrayList<>(list.size());
            for (Object element : list) {
                copied.add(copyValue(element));
            }
            return copied;
        }
        return value;
    }
}
