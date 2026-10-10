package com.smile.acelib.data;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;

/**
 * 真實 MySQL / MariaDB 的逐玩家儲存相容驗證案例集。
 *
 * <p>與 {@link JdbcCompatSuite}（通用 key-value store）同一套執行纪律：</p>
 * <ul>
 *   <li>{@link PlayerStoreCompatMain}：獨立 {@code main()}，由
 *       {@code scripts/jdbc-mysql-compat.sh} 在臨時容器上執行，輸出含
 *       版本、案例結果與 {@code SHOW CREATE TABLE}。</li>
 *   <li>{@link PlayerStoreCompatGatedTest}：JUnit 包裝，僅在
 *       {@code ACELIB_PLAYER_STORE_URL} 等環境變數齊備時執行，
 *       否則略過（CI 不依賴 Docker）。</li>
 * </ul>
 *
 * <p>驗收要點（對應 t08 第 4 項）：增量 upsert 只寫變動欄位、欄位刪除、
 * 不同玩家並行變動不互相污染、交易失敗整批不落地。</p>
 *
 * <p>案例只用公開 API 與標準 {@link DataSource}，不依賴任何測試 fixture。</p>
 */
final class PlayerStoreCompatSuite {

    /** 驗證用的表名（獨立於通用 store 的 {@code acelib_data_kv}）。 */
    static final String TABLE = PlayerDataStores.DEFAULT_TABLE;

    /** 交易失敗注入使用獨立表，避免改動其他案例的共用 schema。 */
    private static final String TX_TABLE = "acelib_player_tx_compat";

    /** 轉換案例用的舊 store 名稱（舊版 AceLib 玩家資料在 acelib_data_kv 的列）。 */
    private static final String LEGACY_STORE = "acelib-player-data";

    private PlayerStoreCompatSuite() {
    }

    /**
     * 執行全部案例。
     *
     * @param fresh   新形狀驗證用的 DataSource（utf8mb4 資料庫）
     * @param legacy  舊資料轉換驗證用的 DataSource
     * @param workDir 轉換案例可寫的暫存目錄（備份與報告）
     * @param label   日誌標籤（例如 {@code mysql:8.4}）
     * @param log     驗證輸出
     * @return 失敗案例數（0 表全過）
     */
    static int runAll(DataSource fresh, DataSource legacy, Path workDir,
            String label, PrintStream log) {
        int failures = 0;
        failures += run(label, "fresh-init-new-shape", log,
            () -> freshInitNewShape(fresh, log));
        failures += run(label, "per-player-isolation-roundtrip", log,
            () -> perPlayerIsolationRoundtrip(fresh, log));
        failures += run(label, "incremental-upsert-only-changed", log,
            () -> incrementalUpsertOnlyChanged(fresh, log));
        failures += run(label, "field-and-player-deletion", log,
            () -> fieldAndPlayerDeletion(fresh, log));
        failures += run(label, "parallel-player-changes", log,
            () -> parallelPlayerChanges(fresh, log));
        failures += run(label, "transaction-failure-rollback", log,
            () -> transactionFailureRollback(fresh, log));
        failures += run(label, "conditional-write-revision-race", log,
            () -> conditionalWriteRevisionRace(fresh, log));
        failures += run(label, "read-check-apply-atomic", log,
            () -> readCheckApplyAtomic(fresh, log));
        failures += run(label, "legacy-jdbc-conversion-preserves-other-stores", log,
            () -> legacyJdbcConversion(legacy, workDir, log));
        failures += run(label, "connection-failure-stage", log,
            () -> connectionFailureStage(log));
        return failures;
    }

    // -----------------------------------------------------------------
    // cases
    // -----------------------------------------------------------------

    /** 新庫 init：表形狀為 (player_uuid, owner, field_hash) 主鍵，欄位齊備。 */
    static void freshInitNewShape(DataSource ds, PrintStream log) throws Exception {
        dropIfExists(ds, TABLE);
        PlayerDataStore store = PlayerDataStores.jdbc(ds, SchemaVersion.V1_0,
            "compat-player", TABLE);
        store.init();

        List<String> columns = JdbcCompatSuite.columnsOf(ds, TABLE);
        log.println("  columns=" + columns);
        for (String required : List.of("player_uuid", "owner", "field_hash",
                "field", "payload", "revision")) {
            check(columns.contains(required), "新表必須含欄位 " + required + "，實際：" + columns);
        }
        String payloadType;
        try (Connection conn = ds.getConnection();
             ResultSet rs = conn.getMetaData().getColumns(
                 conn.getCatalog(), null, TABLE, "payload")) {
            check(rs.next(), "新表必須含 payload 欄位");
            payloadType = rs.getString("TYPE_NAME");
        }
        check("MEDIUMTEXT".equalsIgnoreCase(payloadType),
            "MySQL／MariaDB payload 必須為 MEDIUMTEXT，實際：" + payloadType);
        log.println("  payload type=" + payloadType);
        List<String> pk = JdbcCompatSuite.primaryKeyOf(ds, TABLE);
        log.println("  primary-key=" + pk);
        check(pk.size() == 3 && pk.get(0).equalsIgnoreCase("player_uuid")
                && pk.get(1).equalsIgnoreCase("owner")
                && pk.get(2).equalsIgnoreCase("field_hash"),
            "主鍵必須是 (player_uuid, owner, field_hash)，實際：" + pk);

        // 同一張表、不同 owner 互不覆寫（多下游共用表的下界行為）。
        UUID uuid = UUID.randomUUID();
        PlayerDataStore other = PlayerDataStores.jdbc(ds, SchemaVersion.V1_0,
            "compat-other", TABLE);
        other.init();
        store.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(uuid, "k", "from-first")));
        other.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(uuid, "k", "from-second")));
        check("from-first".equals(store.load(uuid).orElseThrow().getString("k", null)),
            "同表不同 owner 的資料必須隔離");
        check("from-second".equals(other.load(uuid).orElseThrow().getString("k", null)),
            "同表不同 owner 的資料必須隔離");
        log.println("  owner isolation ok");
        other.close();
        store.close();
    }

    /**
     * 每位玩家獨立儲存：巢狀值、emoji、大值 round-trip，未註冊玩家為 empty。
     *
     * <p>大值超過舊 {@code TEXT} 的 65535 bytes 上限，驗證 MySQL／MariaDB
     * {@code MEDIUMTEXT} 能完整 round-trip 大於 64 KiB 的 payload。</p>
     */
    static void perPlayerIsolationRoundtrip(DataSource ds, PrintStream log) throws Exception {
        PlayerDataStore writer = PlayerDataStores.jdbc(ds, SchemaVersion.V1_0,
            "compat-iso", TABLE);
        writer.init();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("inner", List.of(1, "two", Map.of("deep", "值")));
        String big = "v".repeat(LARGE_VALUE_CHARS);
        writer.applyChanges(List.of(
            PlayerDataStore.FieldChange.upsert(a, "nested", nested),
            PlayerDataStore.FieldChange.upsert(a, "emoji-🔑", "😀🎉"),
            PlayerDataStore.FieldChange.upsert(a, "big", big),
            PlayerDataStore.FieldChange.upsert(b, "k", 7)));
        writer.close();

        PlayerDataStore reader = PlayerDataStores.jdbc(ds, SchemaVersion.V1_0,
            "compat-iso", TABLE);
        reader.init();
        Record ra = reader.load(a).orElseThrow();
        check("😀🎉".equals(ra.getString("emoji-🔑", null)), "emoji round-trip");
        check(big.equals(ra.getString("big", null)), LARGE_VALUE_CHARS + " 字元大值 round-trip");
        check(ra.get("nested") instanceof Map, "巢狀值 round-trip");
        check(7 == reader.load(b).orElseThrow().getInt("k", -1), "B 玩家資料獨立");
        check(reader.load(UUID.randomUUID()).isEmpty(), "未註冊玩家回 empty");
        log.println("  nested/emoji/" + LARGE_VALUE_CHARS
            + "chars (>64 KiB) ok, isolation ok");
        reader.close();
    }

    /** 大值案例的長度（ASCII，確保 bytes 數等於字元數且超過舊 TEXT 上限）。 */
    private static final int LARGE_VALUE_CHARS = 70_000;

    /** 增量 upsert：同值不寫入（revision 不動），值變了才 +1，其他欄位不受影響。 */
    static void incrementalUpsertOnlyChanged(DataSource ds, PrintStream log) throws Exception {
        PlayerDataStore store = PlayerDataStores.jdbc(ds, SchemaVersion.V1_0,
            "compat-incr", TABLE);
        store.init();
        UUID uuid = UUID.randomUUID();
        store.applyChanges(List.of(
            PlayerDataStore.FieldChange.upsert(uuid, "changed", 1),
            PlayerDataStore.FieldChange.upsert(uuid, "untouched", "keep")));

        long revChanged = store.revisionOf(uuid, "changed");
        long revUntouched = store.revisionOf(uuid, "untouched");
        log.println("  revision after first write: changed=" + revChanged
            + " untouched=" + revUntouched);
        check(revChanged == 1L, "首次寫入 revision 為 1");
        check(revUntouched == 1L, "首次寫入 revision 為 1");

        long updatedAtUntouched = updatedAtOf(ds, uuid, "untouched");
        // 同值重寫：不得產生寫入，revision 不得推進
        store.applyChanges(List.of(
            PlayerDataStore.FieldChange.upsert(uuid, "changed", 1),
            PlayerDataStore.FieldChange.upsert(uuid, "untouched", "keep")));
        check(store.revisionOf(uuid, "changed") == revChanged,
            "同值 upsert 不得推進 revision");
        check(updatedAtOf(ds, uuid, "untouched") == updatedAtUntouched,
            "未變動欄位不得被重寫（updated_at 不變）");

        // 只送一筆變更：另一欄位完全不受影響
        store.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(uuid, "changed", 2)));
        check(store.revisionOf(uuid, "changed") == revChanged + 1,
            "值變了 revision 才 +1");
        check(store.revisionOf(uuid, "untouched") == revUntouched,
            "單欄位批次不得推進其他欄位 revision");
        check("keep".equals(store.load(uuid).orElseThrow().getString("untouched", null)),
            "未變動欄位內容不變");

        store.applyChanges(List.of(
            PlayerDataStore.FieldChange.upsert(uuid, "changed", 3),
            PlayerDataStore.FieldChange.upsert(uuid, "changed", 4)));
        check(store.revisionOf(uuid, "changed") == revChanged + 2,
            "同批次重複欄位收斂後只算一次寫入");
        check(store.load(uuid).orElseThrow().getInt("changed", -1) == 4,
            "同批次重複欄位以最後一個值為準");
        log.println("  incremental: unchanged skipped, INSERT/UPDATE revision +1,"
            + " duplicate field coalesced once");
        store.close();
    }

    /** 欄位刪除與 deletePlayer：只影響目標，其他欄位與其他玩家不動。 */
    static void fieldAndPlayerDeletion(DataSource ds, PrintStream log) throws Exception {
        PlayerDataStore store = PlayerDataStores.jdbc(ds, SchemaVersion.V1_0,
            "compat-del", TABLE);
        store.init();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        store.applyChanges(List.of(
            PlayerDataStore.FieldChange.upsert(a, "gone", 1),
            PlayerDataStore.FieldChange.upsert(a, "kept", "yes"),
            PlayerDataStore.FieldChange.upsert(b, "k", "b")));

        store.applyChanges(List.of(PlayerDataStore.FieldChange.deletion(a, "gone")));
        Record ra = store.load(a).orElseThrow();
        check(!ra.has("gone"), "刪除後欄位不存在");
        check("yes".equals(ra.getString("kept", null)), "刪除不得影響同玩家其他欄位");

        // 刪除不存在欄位為 no-op
        store.applyChanges(List.of(PlayerDataStore.FieldChange.deletion(a, "never-existed")));
        check("yes".equals(store.load(a).orElseThrow().getString("kept", null)),
            "刪除不存在欄位不得影響其他資料");

        store.deletePlayer(a);
        check(store.load(a).isEmpty(), "deletePlayer 後玩家為 empty");
        check("b".equals(store.load(b).orElseThrow().getString("k", null)),
            "deletePlayer 不得影響其他玩家");
        // 刪掉最後一個欄位後不留孤兒列
        check(rowCount(ds, TABLE, b) == 1, "未刪玩家的列數維持 1");
        log.println("  field deletion + deletePlayer ok, no orphan rows");
        store.close();
    }

    /** 不同玩家的並行批次寫入：同時競爭多個主鍵區間仍不死結或互相污染。 */
    static void parallelPlayerChanges(DataSource ds, PrintStream log) throws Exception {
        PlayerDataStore store = PlayerDataStores.jdbc(ds, SchemaVersion.V1_0,
            "compat-par", TABLE);
        store.init();
        int workerCount = 6;
        int playersPerWorker = 12;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(workerCount);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Map<UUID, String> expected = new LinkedHashMap<>();
        List<Thread> writers = new ArrayList<>(workerCount);
        for (int worker = 0; worker < workerCount; worker++) {
            String value = "worker-" + worker;
            List<PlayerDataStore.FieldChange> changes = new ArrayList<>(playersPerWorker);
            for (int player = 0; player < playersPerWorker; player++) {
                UUID uuid = UUID.randomUUID();
                expected.put(uuid, value);
                changes.add(PlayerDataStore.FieldChange.upsert(uuid, "who", value));
            }
            Thread writer = new Thread(() -> {
                try {
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("並行寫入起跑閘門逾時");
                    }
                    store.applyChanges(changes);
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    done.countDown();
                }
            }, "compat-par-" + worker);
            writers.add(writer);
            writer.start();
        }
        start.countDown();
        boolean completed = done.await(60, TimeUnit.SECONDS);
        for (Thread writer : writers) {
            writer.join(5000L);
        }
        check(completed, "並行批次寫入必須在時限內完成");
        if (failure.get() != null) {
            throw new AssertionError("並行寫入失敗: " + failure.get());
        }
        for (Map.Entry<UUID, String> entry : expected.entrySet()) {
            check(entry.getValue().equals(
                    store.load(entry.getKey()).orElseThrow().getString("who", null)),
                "並行寫入資料正確：" + entry.getKey());
        }
        log.println("  parallel writes ok: " + workerCount + " concurrent batches x "
            + playersPerWorker + " players，無死結／資料交叉污染");
        store.close();
    }

    /**
     * 交易失敗：批次中途的真 SQL 失敗使整批 rollback，既有資料不動。
     *
     * <p>暫時縮小專用表的 payload 欄位，再提交一筆超長 payload，讓資料庫自身
     * 拋出欄位長度錯誤，驗證前面已執行的語句一併回滾。此方式不需要建立 trigger
     * 權限，且錯誤碼仍須為 {@code ACELIB-DATA-008}。</p>
     */
    static void transactionFailureRollback(DataSource ds, PrintStream log) throws Exception {
        dropIfExists(ds, TX_TABLE);
        PlayerDataStore store = PlayerDataStores.jdbc(ds, SchemaVersion.V1_0,
            "compat-tx", TX_TABLE);
        UUID uuid = UUID.randomUUID();
        try {
            store.init();
            store.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(uuid, "k", "old")));
            try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
                st.executeUpdate("ALTER TABLE " + TX_TABLE
                    + " MODIFY COLUMN payload VARCHAR(16) NOT NULL");
            }
            DataStoreException failure = expectDataStoreException(() -> store.applyChanges(
                List.of(
                    PlayerDataStore.FieldChange.upsert(uuid, "written_before_failure", "new"),
                    PlayerDataStore.FieldChange.upsert(uuid, "too_long", "x".repeat(40)))),
                "交易失敗必須拋 DataStoreException");
            check("ACELIB-DATA-008".equals(failure.getCode()),
                "交易失敗為 DATA-008，實際：" + failure.getCode());
            Record loaded = store.load(uuid).orElseThrow();
            check("old".equals(loaded.getString("k", null)), "失敗批次不得影響既有資料");
            check(!loaded.has("written_before_failure"),
                "失敗批次中先執行的語句必須一併 rollback");
            check(rowCount(ds, TX_TABLE, uuid) == 1,
                "失敗後該玩家列數必須維持 1（無殘留新列），實際="
                    + rowCount(ds, TX_TABLE, uuid));
            log.println("  transaction rollback ok, injected DB length failure, code="
                + failure.getCode());
        } finally {
            store.close();
            dropIfExists(ds, TX_TABLE);
        }
    }

    /**
     * 舊 JDBC 玩家資料轉換到逐玩家 store：轉換正確、其他 store 資料不遺失。
     *
     * <p>先在 {@code acelib_data_kv} 造出舊形狀（v1.0 key-value）玩家列與
     * 另一個無關 store 的列，再跑 {@link PlayerDataConverter}
     * 的 JDBC 轉換，最後驗證：玩家資料可從新 store 讀回、其他 store 的列
     * 原樣保留、備份與校驗報告存在、重跑因 marker 而跳過。</p>
     */
    static void legacyJdbcConversion(DataSource ds, Path workDir, PrintStream log)
            throws Exception {
        Path backupDir = workDir.resolve("player-store-compat-backup");
        Files.createDirectories(backupDir);
        dropIfExists(ds, "acelib_data_kv");

        UUID kept = UUID.randomUUID();
        UUID converted = UUID.randomUUID();
        JsonCodec codec = new JsonCodecImpl();
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS acelib_data_kv ("
                + "store_name VARCHAR(255) NOT NULL, k_hash CHAR(64) NOT NULL,"
                + " k VARCHAR(1024) NOT NULL, v TEXT,"
                + " PRIMARY KEY (store_name, k_hash))");
            String playersJson = "{"
                + "\"" + kept + "\":{\"a\":1,\"nested\":{\"x\":[1,2]}},"
                + "\"" + converted + "\":{\"b\":\"legacy-value\",\"c\":true}}";
            st.executeUpdate("INSERT INTO acelib_data_kv (store_name, k_hash, k, v) VALUES ("
                + "'" + LEGACY_STORE + "', '" + JdbcDataStore.hashKey("players") + "',"
                + " 'players', '" + playersJson.replace("'", "''") + "'),"
                + "('unrelated-store', '" + JdbcDataStore.hashKey("otherKey") + "',"
                + " 'otherKey', '\"otherValue\"')");
        }
        log.println("  legacy acelib_data_kv seeded (legacy-store + unrelated-store)");

        // 目標：SQLite 檔案（預設後端），證明跨引擎轉換可行。
        Path targetFile = workDir.resolve("compat-players.db");
        Files.deleteIfExists(targetFile);
        PlayerDataStore target = PlayerDataStores.sqlite(targetFile, SchemaVersion.V1_0);
        target.init();

        PlayerDataConverter.Result result = PlayerDataConverter.fromLegacyJdbcStore(
            LEGACY_STORE, "acelib_data_kv", ds, target, backupDir);
        log.println("  converted=" + result.convertedPlayers()
            + " failed=" + result.failedPlayers()
            + " backup=" + (result.backupPath() == null ? "none" : result.backupPath().getFileName())
            + " report=" + (result.reportPath() == null ? "none" : result.reportPath().getFileName()));
        check(result.convertedPlayers() == 2, "應轉換 2 位玩家，實際=" + result.convertedPlayers());
        check(result.failedPlayers() == 0, "轉換失敗玩家數應為 0");
        check(result.backupPath() != null && Files.exists(result.backupPath()),
            "轉換必須留下可還原的備份");
        check(result.reportPath() != null && Files.exists(result.reportPath()),
            "轉換必須留下校驗報告");

        Record keptRecord = target.load(kept).orElseThrow();
        check(keptRecord.getInt("a", -1) == 1, "轉換後欄位值正確");
        check(keptRecord.get("nested") instanceof Map, "轉換後巢狀值正確");
        Record convertedRecord = target.load(converted).orElseThrow();
        check("legacy-value".equals(convertedRecord.getString("b", null)),
            "latin1 可表示的舊值經轉換後正確");
        check(Boolean.TRUE.equals(convertedRecord.get("c")), "轉換後 boolean 值正確");

        // 其他 store 的資料必須原樣保留（不遺失、不被覆寫）
        check("\"otherValue\"".equals(rawValueOf(ds, "unrelated-store", "otherKey")),
            "轉換不得遺失或改動其他 store 的資料");
        // 舊來源本身不得被刪除或改寫
        check(legacyPlayersRowStillPresent(ds), "舊來源列必須保留（零刪除）");
        log.println("  other store preserved, legacy source retained");

        // 重跑：marker 命中即跳過，既有資料不被覆寫
        target.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(kept, "a", 999)));
        PlayerDataConverter.Result second = PlayerDataConverter.fromLegacyJdbcStore(
            LEGACY_STORE, "acelib_data_kv", ds, target, backupDir);
        check(second.skipped(), "同一來源重跑必須因 marker 而跳過");
        check(999 == target.load(kept).orElseThrow().getInt("a", -1),
            "重跑不得覆寫轉換後新發生的資料");
        log.println("  rerun skipped via marker, live values win");
        target.close();
    }

    /**
     * 欄位 revision 條件寫入：相符寫入、不符不寫、同一 revision 併發恰好一個成功。
     *
     * <p>條件更新走 {@code UPDATE ... WHERE revision = ?} 單一交易；併發雙方
     * 更新同一列時由列鎖序列化，輸家看到 0 列而回報不符（不拋死結）。</p>
     */
    static void conditionalWriteRevisionRace(DataSource ds, PrintStream log) throws Exception {
        PlayerDataStore store = PlayerDataStores.jdbc(ds, SchemaVersion.V1_0,
            "compat-cond", TABLE);
        store.init();
        try {
            UUID uuid = UUID.randomUUID();
            store.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(uuid, "k", "old")));

            PlayerDataStore.ConditionalWriteResult won =
                store.applyIfRevision(uuid, "k", "new", 1L);
            check(won.applied() && won.currentRevision() == 2L, "相符寫入成功且 revision 為 2");

            PlayerDataStore.ConditionalWriteResult lost =
                store.applyIfRevision(uuid, "k", "stale", 1L);
            check(!lost.applied() && lost.currentRevision() == 2L,
                "過期期望不寫並回報實際 revision 2");
            check("new".equals(store.load(uuid).orElseThrow().getString("k", null)),
                "不符寫入不得改變值");

            PlayerDataStore.ConditionalWriteResult created =
                store.applyIfRevision(uuid, "fresh", "v", 0L);
            check(created.applied() && created.currentRevision() == 1L,
                "不存在欄位以期望 0 建立");
            PlayerDataStore.ConditionalWriteResult refused =
                store.applyIfRevision(uuid, "absent", "v", 7L);
            check(!refused.applied() && refused.currentRevision() == 0L,
                "不存在欄位以非 0 期望拒絕");

            // 同一 revision 併發：恰好一個成功（列鎖序列化，無死結）。
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(2);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            List<Boolean> applied = java.util.Collections.synchronizedList(new ArrayList<>());
            List<Thread> threads = new ArrayList<>();
            for (String value : List.of("racer-a", "racer-b")) {
                Thread thread = new Thread(() -> {
                    try {
                        if (!start.await(10, TimeUnit.SECONDS)) {
                            throw new AssertionError("起跑閘門逾時");
                        }
                        applied.add(store.applyIfRevision(uuid, "k", value, 2L).applied());
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                    } finally {
                        done.countDown();
                    }
                });
                thread.setDaemon(true);
                threads.add(thread);
                thread.start();
            }
            start.countDown();
            check(done.await(60, TimeUnit.SECONDS), "併發條件寫入必須在時限內完成");
            for (Thread thread : threads) {
                thread.join(5000L);
            }
            if (failure.get() != null) {
                throw new AssertionError("併發條件寫入失敗: " + failure.get());
            }
            check(applied.size() == 2 && applied.stream().filter(b -> b).count() == 1,
                "同一 revision 併發恰好一個成功，實際：" + applied);
            check(store.revisionOf(uuid, "k") == 3L, "勝出者 revision 推進到 3");
            log.println("  conditional write ok: match/mismatch/create-if-absent,"
                + " race exactly-one-wins, applied=" + applied);
        } finally {
            store.close();
        }
    }

    /**
     * 原子讀檢查套用：檢查通過套用、拒絕不留修改、儲存失敗整批回滾。
     *
     * <p>失敗注入沿用 {@link #transactionFailureRollback} 的欄位長度限制
     * 做法（不需要 trigger 權限），錯誤碼同為 {@code ACELIB-DATA-008}。</p>
     */
    static void readCheckApplyAtomic(DataSource ds, PrintStream log)
            throws Exception {
        PlayerDataStore store = PlayerDataStores.jdbc(ds, SchemaVersion.V1_0,
            "compat-rca", TABLE);
        store.init();
        try {
            UUID uuid = UUID.randomUUID();
            store.applyChanges(List.of(
                PlayerDataStore.FieldChange.upsert(uuid, "a", 1),
                PlayerDataStore.FieldChange.upsert(uuid, "b", "keep")));

            PlayerDataStore.ReadCheckApplyResult done = store.readCheckApply(uuid,
                present -> present.isPresent() && present.get().getInt("a", -1) == 1,
                List.of(PlayerDataStore.FieldChange.upsert(uuid, "a", 2)));
            check(done.outcome() == PlayerDataStore.Outcome.APPLIED, "檢查通過應套用");
            check(store.load(uuid).orElseThrow().getInt("a", -1) == 2, "套用後可讀到新值");

            PlayerDataStore.ReadCheckApplyResult rejected = store.readCheckApply(uuid,
                present -> false,
                List.of(PlayerDataStore.FieldChange.upsert(uuid, "a", 999)));
            check(rejected.outcome() == PlayerDataStore.Outcome.CHECK_REJECTED,
                "檢查拒絕應回 CHECK_REJECTED");
            check(store.load(uuid).orElseThrow().getInt("a", -1) == 2,
                "檢查拒絕不得改變任何欄位");
            check(store.revisionOf(uuid, "a") == 2L, "檢查拒絕不得推進 revision");
            log.println("  read-check-apply ok: applied + rejected-no-change");
        } finally {
            store.close();
        }

        // 儲存失敗路徑：專用表 + 欄位長度限制，驗證整批回滾、無部分修改。
        dropIfExists(ds, TX_TABLE);
        PlayerDataStore txStore = PlayerDataStores.jdbc(ds, SchemaVersion.V1_0,
            "compat-tx-rca", TX_TABLE);
        UUID uuid = UUID.randomUUID();
        try {
            txStore.init();
            txStore.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(uuid, "k", "old")));
            try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
                st.executeUpdate("ALTER TABLE " + TX_TABLE
                    + " MODIFY COLUMN payload VARCHAR(16) NOT NULL");
            }
            DataStoreException failure = expectDataStoreException(() -> txStore.readCheckApply(
                uuid,
                present -> true,
                List.of(
                    PlayerDataStore.FieldChange.upsert(uuid, "written_before_failure", "new"),
                    PlayerDataStore.FieldChange.upsert(uuid, "too_long", "x".repeat(40)))),
                "儲存失敗必須拋 DataStoreException");
            check("ACELIB-DATA-008".equals(failure.getCode()),
                "儲存失敗為 DATA-008，實際：" + failure.getCode());
            Record loaded = txStore.load(uuid).orElseThrow();
            check("old".equals(loaded.getString("k", null)), "失敗不得影響既有資料");
            check(!loaded.has("written_before_failure"),
                "失敗批次中先執行的語句必須一併 rollback");
            log.println("  read-check-apply rollback ok, injected DB length failure");
        } finally {
            txStore.close();
            dropIfExists(ds, TX_TABLE);
        }
    }

    /** 連線失敗：ACELIB-DATA-008 且標示 [player-jdbc:init] 階段。 */
    static void connectionFailureStage(PrintStream log) {
        DataSource bad = new JdbcCompatSuite.DriverManagerDataSource(
            "jdbc:mysql://127.0.0.1:1/nonexistent?connectTimeout=1000", "no", "no");
        PlayerDataStore store =
            PlayerDataStores.jdbc(bad, SchemaVersion.V1_0, "compat-bad-player", TABLE);
        try {
            store.init();
            throw new AssertionError("預期連線失敗拋錯");
        } catch (DataStoreException ex) {
            log.println("  code=" + ex.getCode() + " message=" + ex.getMessage());
            check("ACELIB-DATA-008".equals(ex.getCode()), "連線失敗為 DATA-008");
            check(ex.getMessage().contains("[player-jdbc:init]"),
                "標示 [player-jdbc:init] 階段");
        }
    }

    // -----------------------------------------------------------------
    // infra
    // -----------------------------------------------------------------

    @FunctionalInterface
    private interface Case {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }

    private static int run(String label, String name, PrintStream log, Case c) {
        try {
            c.run();
            log.println("[PASS] " + label + " / " + name);
            return 0;
        } catch (AssertionError | Exception ex) {
            log.println("[FAIL] " + label + " / " + name + " :: " + ex);
            return 1;
        }
    }

    private static void check(boolean cond, String what) {
        if (!cond) {
            throw new AssertionError("check failed: " + what);
        }
    }

    private static DataStoreException expectDataStoreException(Action action, String what)
            throws Exception {
        try {
            action.run();
        } catch (DataStoreException ex) {
            return ex;
        }
        throw new AssertionError("check failed: " + what);
    }

    private static void dropIfExists(DataSource ds, String objectName) throws SQLException {
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("DROP TABLE IF EXISTS " + objectName);
        }
    }

    /** 某位玩家在此 owner 底下的列數（驗證無孤兒列）。 */
    private static int rowCount(DataSource ds, String table, UUID uuid) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + table + " WHERE player_uuid = ?";
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** 欄位最後一次寫入的時間戳（驗證未變動欄位未被重寫）。 */
    private static long updatedAtOf(DataSource ds, UUID uuid, String field)
            throws SQLException {
        String sql = "SELECT updated_at FROM " + TABLE
            + " WHERE player_uuid = ? AND owner = ? AND field_hash = ?";
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, "compat-incr");
            ps.setString(3, JdbcDataStore.hashKey(field));
            try (ResultSet rs = ps.executeQuery()) {
                check(rs.next(), "欄位 " + field + " 應存在");
                return rs.getLong(1);
            }
        }
    }

    /** 舊來源列是否仍在（轉換必須零刪除）。 */
    private static boolean legacyPlayersRowStillPresent(DataSource ds) throws SQLException {
        String sql = "SELECT COUNT(*) FROM acelib_data_kv WHERE store_name = ? AND k = ?";
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, LEGACY_STORE);
            ps.setString(2, "players");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) == 1;
            }
        }
    }

    static String rawValueOf(DataSource ds, String storeName, String key) throws Exception {
        String sql = "SELECT v FROM acelib_data_kv WHERE store_name = ? AND k_hash = ?";
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, storeName);
            ps.setString(2, JdbcDataStore.hashKey(key));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    }
