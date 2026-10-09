package com.smile.acelib.data;

import java.util.ArrayList;
import java.util.Collection;
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
 * <h2>寫入方式</h2>
 * <p>玩家節點一律以點分隔路徑（{@code players.&lt;uuid&gt;.&lt;field&gt;}）
 * 直接對 delegate 的根視圖 {@code set}／{@code remove}，只寫入
 * {@link JsonCodec} 白名單內的純值，不把 {@link Record} 實例當值存入。
 * delegate 的根視圖不接受 {@link Record} 當值（例如 {@link MemoryRecord}
 * 以 {@code ACELIB-DATA-006} 拒絕），沿用視圖實例寫回會讓成功路徑直接失敗，
 * 也會讓落盤編碼無從處理。</p>
 *
 * <h2>失敗回復</h2>
 * <p>{@link #applyChanges} 與 {@link #deletePlayer} 在改動記憶體樹之前，
 * 先對受影響玩家的欄位與 {@code revision} 做快照；寫入或 {@code save()}
 * 失敗時把記憶體樹與 {@code revision} 還原為操作前，再以
 * {@code ACELIB-DATA-008} 回報（含原始原因）。還原本身失敗時不靜默：
 * 還原遇到的每個例外都掛在回報例外的 {@code suppressed}，訊息標示還原不完整。
 * 只保證 AceLib 自己的記憶體視圖回到操作前；delegate 的 {@code save()}
 * 若已部分落盤，不可逆，也不自動重試。</p>
 *
 * <h2>revision 的生命週期</h2>
 * <p>{@code revision} 只放在本轉接實例的記憶體裡（每次成功 {@code save()}
 * 後推進），<strong>只對同一轉接實例的生命週期有效</strong>：重建轉接、
 * 重啟伺服器都不保留，也不持久化、不跨行程。</p>
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
        Map<FieldKey, FieldChange> deduped = new LinkedHashMap<>();
        for (FieldChange change : changes) {
            deduped.put(new FieldKey(change.uuid(), change.field()), change);
        }
        // 快照只讀不寫：此階段失敗表示 delegate 連讀都有問題，
        // 尚未改動任何狀態，直接拋出，不進還原流程。
        boolean playersExisted = root.getRecord(PLAYER_ROOT, null) != null;
        Map<UUID, PlayerSnapshot> snapshots = new LinkedHashMap<>();
        for (FieldChange change : deduped.values()) {
            snapshots.computeIfAbsent(change.uuid(), uuid -> capturePlayer(root, uuid));
        }
        try {
            for (FieldChange change : deduped.values()) {
                String base = PLAYER_ROOT + "." + uuidKey(change.uuid());
                if (change.deletion()) {
                    root.remove(base + "." + change.field());
                    // 整節點為空時移除，避免留下空的 players.<uuid> 節點
                    Record playerNode = root.getRecord(base, null);
                    if (playerNode != null && playerNode.keys().isEmpty()) {
                        root.remove(base);
                    }
                } else {
                    root.set(base + "." + change.field(), change.value());
                }
            }
            delegate.save();
        } catch (RuntimeException ex) {
            throw rollbackFailure("apply", deduped.size(), ex, root, snapshots.values(), playersExisted);
        }
        // delegate 是整棵 tree 落盤，無法逐欄位追蹤寫入，因此 revision 只作為
        // 「至少成功落地過一次」的觀察訊號：每次成功 save 後 +1。
        // 只在成功後推進，失敗回報前不碰，失敗路徑的 revision 自然維持操作前。
        for (FieldChange change : deduped.values()) {
            revisions.merge(change.uuid(), 1L, Long::sum);
        }
    }

    @Override
    public void deletePlayer(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        requireReady();
        Record root = delegate.root();
        boolean playersExisted = root.getRecord(PLAYER_ROOT, null) != null;
        PlayerSnapshot snapshot = capturePlayer(root, uuid);
        if (!snapshot.existed()) {
            // 從未寫入即 no-op：樹沒有變動，不需要 save；
            // revision 若有殘留（例如 delegate 被外部改過）仍清掉。
            revisions.remove(uuid);
            return;
        }
        try {
            root.remove(PLAYER_ROOT + "." + uuidKey(uuid));
            delegate.save();
        } catch (RuntimeException ex) {
            throw rollbackFailure("delete", 1, ex, root, List.of(snapshot), playersExisted);
        }
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

    /**
     * 對一位玩家做操作前快照（只讀，不改動樹）。
     *
     * <p>頂層條目取自視圖的原始底層 map，不走點路徑解析：字面點號鍵的值與
     * {@code null} 值條目都原樣保留，否則快照本身就會遺失它們。</p>
     *
     * @param root delegate 根視圖
     * @param uuid 玩家 UUID
     * @return 該玩家的操作前快照
     */
    private PlayerSnapshot capturePlayer(Record root, UUID uuid) {
        Record node = root.getRecord(PLAYER_ROOT + "." + uuidKey(uuid), null);
        Long revisionBefore = revisions.get(uuid);
        if (node == null) {
            return new PlayerSnapshot(uuid, false, Map.of(), revisionBefore);
        }
        Map<String, Object> raw;
        if (node instanceof MemoryRecord memory) {
            // 生產路徑的根視圖一律是 MemoryRecord：snapshot() 拷貝原始頂層
            // map（含 null 值條目與字面點號鍵），不經點路徑解析。
            raw = memory.snapshot();
        } else {
            raw = new LinkedHashMap<>();
            for (String key : node.keys()) {
                raw.put(key, node.get(key));
            }
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            fields.put(entry.getKey(), deepCopyValue(entry.getValue()));
        }
        return new PlayerSnapshot(uuid, true, fields, revisionBefore);
    }

    /**
     * 寫入或 {@code save()} 失敗時，把記憶體樹與 revision 還原為操作前，
     * 包成 {@code ACELIB-DATA-008} 回報。
     *
     * <p>還原採 best-effort：逐一還原每個快照，單一快照還原失敗不中斷其餘，
     * 失敗原因全掛在回報例外的 {@code suppressed}，不靜默。存在過的玩家節點
     * 以快照的整節點深拷貝一次寫回（而非逐欄位 {@code set}），因此頂層
     * {@code null} 值、空節點與字面點號鍵都能回到原始結構。</p>
     *
     * @param stage          失敗階段（訊息的 {@code [player-adapter:...]} 標籤）
     * @param changeCount    本次變更筆數（訊息用）
     * @param cause          原始失敗
     * @param root           delegate 根視圖
     * @param snapshots      操作前快照
     * @param playersExisted 操作前 {@code players} 節點是否存在
     * @return 包好還原結果的回報例外
     */
    private DataStoreException rollbackFailure(String stage, int changeCount, RuntimeException cause,
            Record root, Collection<PlayerSnapshot> snapshots, boolean playersExisted) {
        List<RuntimeException> restoreFailures = new ArrayList<>();
        for (PlayerSnapshot snapshot : snapshots) {
            try {
                String base = PLAYER_ROOT + "." + uuidKey(snapshot.uuid());
                if (snapshot.existed()) {
                    // 整節點一次寫回：純值 Map 是白名單型別，可直接當節點值；
                    // 逐欄位 set 會把 null 值當刪除、把空節點還原成不存在、
                    // 把字面點號鍵重新解讀成路徑。
                    @SuppressWarnings("unchecked")
                    Map<String, Object> whole = (Map<String, Object>) deepCopyValue(snapshot.fields());
                    root.set(base, whole);
                } else {
                    root.remove(base);
                }
            } catch (RuntimeException restoreEx) {
                restoreFailures.add(restoreEx);
            }
        }
        if (!playersExisted) {
            try {
                root.remove(PLAYER_ROOT);
            } catch (RuntimeException restoreEx) {
                restoreFailures.add(restoreEx);
            }
        }
        // revision 還原只碰本轉接的記憶體，不會失敗；即使樹還原不完整，
        // revision 也要回到操作前，避免「樹是舊的、計數是新的」不一致。
        for (PlayerSnapshot snapshot : snapshots) {
            if (snapshot.revisionBefore() == null) {
                revisions.remove(snapshot.uuid());
            } else {
                revisions.put(snapshot.uuid(), snapshot.revisionBefore());
            }
        }
        String restored = restoreFailures.isEmpty()
            ? "；記憶體樹與 revision 已還原為操作前"
            : "；記憶體樹還原不完整，詳見 suppressed";
        DataStoreException failure = new DataStoreException("ACELIB-DATA-008",
            "[player-adapter:" + stage + "] failed to apply " + changeCount
                + " change(s) on store '" + delegate.name() + "': " + cause.getMessage()
                + restored, cause);
        for (RuntimeException restoreEx : restoreFailures) {
            failure.addSuppressed(restoreEx);
        }
        return failure;
    }

    /**
     * 快照用的深拷貝：JSON 容器（{@code Map}／{@code List}）遞迴重建，
     * 基本型別與 {@code null} 不可變，直接共用。
     *
     * @param value 欄位值
     * @return 與原值內容相同但容器已隔離的值
     */
    private static Object deepCopyValue(Object value) {
        if (value == null
                || value instanceof String
                || value instanceof Number
                || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>(map.size() + 1);
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new DataStoreException("ACELIB-DATA-006",
                        "player field map key must be String, got "
                            + (entry.getKey() == null ? "null" : entry.getKey().getClass().getName()));
                }
                copy.put(key, deepCopyValue(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size() + 1);
            for (Object item : list) {
                copy.add(deepCopyValue(item));
            }
            return copy;
        }
        // 未知型別（例如第三方 Record 實作存入的子視圖）無法安全複製：
        // 共用參考，還原時寫回同一物件，不擴大快照語意。
        return value;
    }

    /**
     * 一位玩家的操作前快照。
     *
     * @param uuid           玩家 UUID
     * @param existed        操作前該玩家是否有資料
     * @param fields         操作前欄位（已深拷貝；不存在時為空）
     * @param revisionBefore 操作前 revision（從未推進時為 null）
     */
    private record PlayerSnapshot(UUID uuid, boolean existed,
            Map<String, Object> fields, Long revisionBefore) {
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