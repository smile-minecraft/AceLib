package com.smile.acelib.external;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 外部整合 adapter registry 與生命週期協調器。
 *
 * <p>以 integration id 維護已註冊的 {@link IntegrationAdapter}，並提供
 * {@code register()} / {@code unregister()} / {@code initializeAll()} /
 * {@code shutdownAll()} / {@code reload()} 等操作。所有公開方法皆為 synchronized，
 * 確保 enable / disable / reload 期間的狀態一致。</p>
 *
 * <h2>失敗隔離</h2>
 * <p>{@code initializeAll()} 與 {@code reload()} 對每個 adapter 個別捕捉啟用例外：
 * 單一 adapter 啟用失敗不會中斷其他 adapter，失敗 adapter 保持非 active，其失敗原因
 * 可經由 {@link #getStatus(String)} 取得（不吞錯，錯誤記錄於狀態）。</p>
 *
 * <h2>reload 順序</h2>
 * <p>{@code reload(...)} 先 {@code shutdownAll()} 舊 adapters 並清空 registry，再註冊
 * 新 adapters 並 {@code initializeAll()}；過程中舊與新 adapters 不會同時可用。</p>
 *
 * @see IntegrationAdapter
 * @see IntegrationProbeResult
 * @since 1.0.0
 */
public class IntegrationRegistry {

    private final Map<String, IntegrationAdapter> adapters = new LinkedHashMap<>();

    /**
     * 註冊 adapter（依 integration id 唯一）。
     *
     * @param adapter 待註冊的 adapter；不可為 null
     * @throws IllegalArgumentException 當 {@code adapter} 為 null，或其 id 已註冊
     */
    public synchronized void register(IntegrationAdapter adapter) {
        if (adapter == null) {
            throw new IllegalArgumentException("adapter must not be null");
        }
        String id = adapter.getId();
        if (adapters.containsKey(id)) {
            throw new IllegalArgumentException(
                "integration adapter already registered: " + id);
        }
        adapters.put(id, adapter);
    }

    /**
     * 移除已註冊的 adapter；若其處於啟用狀態，先停用以釋放資源。
     *
     * @param id 待移除的 integration id；不可為 null
     * @return 被移除的 adapter
     * @throws IllegalArgumentException 當 {@code id} 為 null 或尚未註冊
     */
    public synchronized IntegrationAdapter unregister(String id) {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        IntegrationAdapter adapter = adapters.get(id);
        if (adapter == null) {
            throw new IllegalArgumentException(
                "integration adapter not registered: " + id);
        }
        if (adapter.isActive()) {
            adapter.shutdown();
        }
        adapters.remove(id);
        return adapter;
    }

    /**
     * 啟用所有已註冊 adapter；單一失敗不中斷其他 adapter（失敗隔離）。
     */
    public synchronized void initializeAll() {
        for (IntegrationAdapter adapter : adapters.values()) {
            try {
                adapter.initialize();
            } catch (Exception e) {
                // 失敗隔離：adapter 自身已於 getStatus() 記錄 INIT_FAILED，繼續下一個
            }
        }
    }

    /**
     * 停用所有已註冊且啟用中的 adapter（冪等；個別失敗不中斷其他）。
     */
    public synchronized void shutdownAll() {
        for (IntegrationAdapter adapter : adapters.values()) {
            try {
                adapter.shutdown();
            } catch (Exception e) {
                // 停用應安全；若具體 doShutdown 拋出仍繼續，避免卡住其他 adapter
            }
        }
    }

    /**
     * 以新 adapter 集合重新載入：先停用並清空舊 adapters，再註冊並啟用新 adapters。
     * 舊與新 adapters 不會同時可用；新集合內單一啟用失敗不污染其他。
     *
     * @param newAdapters 新 adapter 集合；不可為 null，元素不可為 null，id 不可重複
     * @throws IllegalArgumentException 當集合為 null、含 null 元素，或新集合內 id 重複
     */
    public synchronized void reload(Collection<? extends IntegrationAdapter> newAdapters) {
        if (newAdapters == null) {
            throw new IllegalArgumentException("newAdapters must not be null");
        }
        List<IntegrationAdapter> validated = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (IntegrationAdapter a : newAdapters) {
            if (a == null) {
                throw new IllegalArgumentException("adapter in reload set must not be null");
            }
            if (!seen.add(a.getId())) {
                throw new IllegalArgumentException(
                    "duplicate integration id in reload set: " + a.getId());
            }
            validated.add(a);
        }
        shutdownAll();
        adapters.clear();
        for (IntegrationAdapter a : validated) {
            adapters.put(a.getId(), a);
        }
        initializeAll();
    }

    /**
     * 查詢指定 integration id 目前的狀態。
     *
     * @param id integration id；不可為 null
     * @return 非 null 的 {@link IntegrationProbeResult}
     * @throws IllegalArgumentException 當 {@code id} 為 null 或尚未註冊
     */
    public synchronized IntegrationProbeResult getStatus(String id) {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        IntegrationAdapter adapter = adapters.get(id);
        if (adapter == null) {
            throw new IllegalArgumentException(
                "integration adapter not registered: " + id);
        }
        return adapter.getStatus();
    }

    /**
     * 查詢指定 integration id 是否已註冊。
     *
     * @param id integration id；不可為 null
     * @return {@code true} 表示已註冊
     */
    public synchronized boolean isRegistered(String id) {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        return adapters.containsKey(id);
    }

    /**
     * 查詢指定 integration id 目前是否啟用。
     *
     * @param id integration id；不可為 null
     * @return {@code true} 表示已註冊且啟用
     * @throws IllegalArgumentException 當 {@code id} 為 null 或尚未註冊
     */
    public synchronized boolean isActive(String id) {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        IntegrationAdapter adapter = adapters.get(id);
        if (adapter == null) {
            throw new IllegalArgumentException(
                "integration adapter not registered: " + id);
        }
        return adapter.isActive();
    }

    /**
     * 取得目前所有已註冊的 integration id（快照副本）。
     *
     * @return 不可變的 id 集合
     */
    public synchronized Set<String> getRegisteredIds() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(adapters.keySet()));
    }

    /**
     * 取得目前已註冊 adapter 數量。
     *
     * @return 註冊數
     */
    public synchronized int size() {
        return adapters.size();
    }
}
