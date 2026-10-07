package com.smile.acelib.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 把既有 {@link DataStore} 包成 {@link PlayerDataStore} 語意（Internal）。
 *
 * <p>資料仍落在 delegate 的 {@code players.<uuid>} 路徑，因此既有資料不需要搬移
 * 即可讀寫。用途有兩個：</p>
 * <ul>
 *   <li>遷換到 SQLite／MySQL 之前的過渡期，讓 {@code PlayerDataService} 先以
 *       逐玩家語意運作，資料仍在現有檔案裡</li>
 *   <li>下游自備非 SQLite 的 key-value 儲存時，仍能使用同一套逐玩家服務與
 *       增量寫入語意</li>
 * </ul>
 *
 * <h2>與 delegate 的差異</h2>
 * <p>本轉接<strong>不提供欄位級增量</strong>：{@link DataStore} 的
 * {@code save()} 是整棵 tree 落盤，無法只寫部分欄位。因此本類的
 * {@link #revisionOf}／{@link #fieldCount} 以 delegate 的實際內容推導，
 * {@link #applyChanges} 仍只把變更欄位寫進記憶體 view 再觸發一次
 * {@code save()}。</p>
 *
 * <p>{@code close()} 不關閉 delegate：delegate 的生命週期屬於建立它的呼叫端。</p>
 *
 * @see PlayerDataStore
 * @see PlayerDataStores#fromDataStore(DataStore)
 * @since 1.4.0
 */
final class DataStorePlayerDataStore implements PlayerDataStore {

    private static final String PLAYER_ROOT = "players";

    private final DataStore delegate;
    private final Map<UUID, Long> revisions = new LinkedHashMap<>();

    private volatile boolean closed = false;

    DataStorePlayerDataStore(DataStore delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        if (!delegate.isInitialized()) {
            throw new DataStoreException("ACELIB-DATA-005",
                "delegate store '" + delegate.name() + "' must be initialized before wrapping");
        }
    }

    @Override
    public String name() {
        return delegate.name() + "-player-adapter";
    }

    @Override
    public String owner() {
        return PlayerDataStores.DEFAULT_OWNER;
    }

    @Override
    public SchemaVersion schemaVersion() {
        return delegate.schemaVersion();
    }

    @Override
    public boolean isInitialized() {
        return delegate.isInitialized();
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void init() {
        requireReady();
    }

    @Override
    public Optional<Record> load(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        requireReady();
        Record playersNode = delegate.root().getRecord(PLAYER_ROOT, null);
        if (playersNode == null) {
            return Optional.empty();
        }
        Object raw = playersNode.get(uuid.toString());
        if (!(raw instanceof Map<?, ?> playerMap)) {
            return Optional.empty();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : playerMap.entrySet()) {
            if (entry.getKey() instanceof String key) {
                values.put(key, entry.getValue());
            }
        }
        if (values.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new MemoryRecord("", values));
    }

    @Override
    public void applyChanges(List<FieldChange> changes) {
        Objects.requireNonNull(changes, "changes");
        requireReady();
        if (changes.isEmpty()) {
            return;
        }
        Record root = delegate.root();
        Record playersNode = root.getRecord(PLAYER_ROOT, null);
        if (playersNode == null) {
            playersNode = new MemoryRecord(PLAYER_ROOT, new LinkedHashMap<>());
            root.set(PLAYER_ROOT, playersNode);
        }
        Map<FieldKey, FieldChange> deduped = new LinkedHashMap<>();
        for (FieldChange change : changes) {
            deduped.put(new FieldKey(change.uuid(), change.field()), change);
        }
        for (FieldChange change : deduped.values()) {
            String path = uuidPath(change.uuid());
            Record playerNode = playersNode.getRecord(uuidKey(change.uuid()), null);
            if (playerNode == null) {
                playerNode = new MemoryRecord(uuidKey(change.uuid()), new LinkedHashMap<>());
                playersNode.set(uuidKey(change.uuid()), playerNode);
            }
            if (change.deletion()) {
                playerNode.remove(change.field());
                // 整節點為空時移除，避免留下空的 players.<uuid> 節點
                if (playerNode.keys().isEmpty()) {
                    playersNode.remove(uuidKey(change.uuid()));
                }
            } else {
                playerNode.set(change.field(), change.value());
            }
        }
        try {
            delegate.save();
        } catch (RuntimeException ex) {
            throw new DataStoreException("ACELIB-DATA-008",
                "[player-adapter:save] failed to apply " + deduped.size()
                    + " change(s) on store '" + delegate.name() + "': " + ex.getMessage(), ex);
        }
        // delegate 是整棵 tree 落盤，無法逐欄位追蹤寫入，因此 revision 只作為
        // 「至少成功落地過一次」的觀察訊號：每次成功 save 後 +1。
        for (FieldChange change : deduped.values()) {
            revisions.merge(change.uuid(), 1L, Long::sum);
        }
    }

    @Override
    public void deletePlayer(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        requireReady();
        Record playersNode = delegate.root().getRecord(PLAYER_ROOT, null);
        if (playersNode != null) {
            playersNode.remove(uuidKey(uuid));
        }
        delegate.save();
        revisions.remove(uuid);
    }

    @Override
    public long revisionOf(UUID uuid, String field) {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(field, "field");
        requireReady();
        return revisions.getOrDefault(uuid, 0L);
    }

    @Override
    public int fieldCount(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        requireReady();
        return load(uuid).map(record -> record.keys().size()).orElse(0);
    }

    /**
     * 取得此轉接所包覆的 delegate store（測試與遷移流程用）。
     *
     * @return 不可為 null 的 delegate
     */
    DataStore delegate() {
        return delegate;
    }

    @Override
    public void close() {
        // delegate 的生命週期屬於建立它的呼叫端，此處不代為關閉。
        this.closed = true;
    }

    private void requireReady() {
        if (closed) {
            throw new DataStoreException("ACELIB-DATA-005",
                "player data store adapter for '" + delegate.name() + "' is closed");
        }
        if (!delegate.isInitialized()) {
            throw new IllegalStateException("delegate store '" + delegate.name()
                + "' must be initialized before use");
        }
        if (delegate.isClosed()) {
            throw new DataStoreException("ACELIB-DATA-005",
                "delegate store '" + delegate.name() + "' is closed");
        }
    }

    private static String uuidKey(UUID uuid) {
        return uuid.toString();
    }

    private static String uuidPath(UUID uuid) {
        return PLAYER_ROOT + "." + uuid;
    }

    /**
     * 批次去重用的複合鍵。
     *
     * @param uuid  玩家 UUID
     * @param field 欄位名
     */
    private record FieldKey(UUID uuid, String field) {
    }

    /**
     * 給診斷使用的目前玩家 UUID 清單。
     *
     * @return delegate 中出現過的玩家 UUID
     */
    List<UUID> knownPlayers() {
        Record playersNode = delegate.root().getRecord(PLAYER_ROOT, null);
        if (playersNode == null) {
            return new ArrayList<>();
        }
        List<UUID> players = new ArrayList<>();
        for (String key : playersNode.keys()) {
            try {
                players.add(UUID.fromString(key));
            } catch (IllegalArgumentException ignore) {
                // 非 UUID 鍵（例如其他用途的條目）不列入
            }
        }
        return players;
    }
}