package com.smile.acelib.data;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 既有玩家資料轉換為 {@link PlayerDataStore} 的流程（Internal）。
 *
 * <p>來源有兩種：舊版 {@link JsonFileDataStore} 的 JSON 檔，以及舊版
 * {@link JdbcDataStore} 在 {@code acelib_data_kv} 的 {@code players} 列。
 * 兩者都只取 {@code players} 子樹，<strong>其他 store 的資料完全不碰</strong>。</p>
 *
 * <h2>流程（同步，服務啟動前完成）</h2>
 * <ol>
 *   <li>讀來源到記憶體（此階段不寫入任何檔案）</li>
 *   <li>備份來源（JSON 以 temp + fsync + ATOMIC_MOVE 複製；JDBC 為邏輯備份）</li>
 *   <li>每 {@value #BATCH_SIZE} 位玩家一個交易寫入；<strong>只補目標缺少的玩家</strong></li>
 *   <li>三層校驗（筆數／內容雜湊／逐玩家完整性），通過才寫 marker 與報告</li>
 * </ol>
 *
 * <h2>真資料零刪除</h2>
 * <p>來源檔案與來源資料表從不被修改或刪除；轉換只往新 store 加資料。因此：</p>
 * <ul>
 *   <li>中斷後重跑是安全的（只補缺行），不需要中斷復原狀態機</li>
 *   <li>轉換後才發生的現場資料永遠勝出，不會被舊資料蓋回去</li>
 * </ul>
 *
 * <h2>拒絕非同步轉換</h2>
 * <p>轉換刻意在服務啟動前同步完成：若玩家在轉換未完成時登入會拿到空資料，
 * 事後又不能覆寫，等於靜默丟資料。</p>
 *
 * @see PlayerDataStore
 * @since 1.4.0
 */
public final class PlayerDataConverter {

    /** 每批交易寫入的玩家數。 */
    static final int BATCH_SIZE = 200;

    /** 備份檔名時間戳格式（UTC）。 */
    private static final DateTimeFormatter STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    /** marker 檔名：記錄「哪一份來源已經轉換過」。 */
    static final String MARKER_FILE = "player-data.legacy.migrated";

    /** 校驗報告檔名前綴。 */
    static final String REPORT_PREFIX = "conversion-";

    private PlayerDataConverter() {
    }

    /**
     * 轉換結果。
     *
     * @param convertedPlayers 本次實際寫入的玩家數
     * @param failedPlayers    轉換失敗的玩家數（正常情況為 0）
     * @param convertedUuids   本次寫入的玩家 UUID（字串）
     * @param skipped          是否因 marker 或來源不存在而整段跳過
     * @param backupPath       備份檔案位置；未產生備份時為 {@code null}
     * @param reportPath       校驗報告位置；未校驗時為 {@code null}
     */
    public record Result(int convertedPlayers, int failedPlayers,
            List<String> convertedUuids, boolean skipped,
            Path backupPath, Path reportPath) {

        public Result {
            Objects.requireNonNull(convertedUuids, "convertedUuids");
            convertedUuids = List.copyOf(convertedUuids);
        }
    }

    /**
     * 取得指定目錄下的 marker 路徑。
     *
     * @param backupDir 轉換工作目錄；不可為 null
     * @return marker 檔案路徑
     */
    public static Path markerPath(Path backupDir) {
        Objects.requireNonNull(backupDir, "backupDir");
        return backupDir.resolve(MARKER_FILE);
    }

    /**
     * 從舊版 JSON 檔轉換玩家資料到 {@code target}。
     *
     * @param legacyJson 舊版 {@code player-data.json}；不可為 null
     * @param target     目標 {@link PlayerDataStore}；必須已 {@code init()}
     * @param backupDir  備份與 marker 的工作目錄；不可為 null，不存在時會建立
     * @return 轉換結果
     * @throws DataStoreException 當來源格式損壞（{@code ACELIB-DATA-002}）、
     *                             備份或寫入失敗（{@code ACELIB-DATA-001}／
     *                             {@code ACELIB-DATA-008}）或校驗不通過
     *                             （{@code ACELIB-DATA-013}）
     */
    public static Result fromLegacyJsonFile(Path legacyJson, PlayerDataStore target,
            Path backupDir) {
        Objects.requireNonNull(legacyJson, "legacyJson");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(backupDir, "backupDir");
        if (!Files.exists(legacyJson)) {
            return new Result(0, 0, List.of(), true, null, null);
        }
        String sourceHash = hashFile(legacyJson);
        if (isConverted(backupDir, sourceHash)) {
            return new Result(0, 0, List.of(), true, null, null);
        }

        Map<UUID, Map<String, Object>> players = readLegacyJson(legacyJson);
        Path backup = backupLegacyJson(legacyJson, backupDir);
        return convertInto(target, players, backupDir, backup, sourceHash, "json");
    }

    /**
     * 從舊版 {@code acelib_data_kv} 的 {@code players} 列轉換玩家資料到 {@code target}。
     *
     * <p>舊 {@link JdbcDataStore} 以「每個頂層 key 一列」儲存，因此玩家資料是
     * {@code store_name = 'acelib-player-data'}、{@code k = 'players'} 的單一列，
     * 值為整棵 {@code players} 子樹的 JSON。</p>
     *
     * @param legacyStoreName 舊 store 的 {@code store_name}；不可為 null 或空白
     * @param legacyTable     舊資料表名；不可為 null 或空白
     * @param legacyDataSource 舊資料來源的 {@link javax.sql.DataSource}；不可為 null
     * @param target          目標 {@link PlayerDataStore}；必須已 {@code init()}
     * @param backupDir       備份與 marker 的工作目錄；不可為 null
     * @return 轉換結果
     * @throws DataStoreException 當讀取失敗（{@code ACELIB-DATA-008}）、備份或寫入失敗、
     *                             或校驗不通過（{@code ACELIB-DATA-013}）
     */
    public static Result fromLegacyJdbcStore(String legacyStoreName, String legacyTable,
            javax.sql.DataSource legacyDataSource, PlayerDataStore target, Path backupDir) {
        Objects.requireNonNull(legacyStoreName, "legacyStoreName");
        Objects.requireNonNull(legacyTable, "legacyTable");
        Objects.requireNonNull(legacyDataSource, "legacyDataSource");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(backupDir, "backupDir");

        String playersJson = readLegacyJdbcPlayers(legacyStoreName, legacyTable, legacyDataSource);
        if (playersJson == null) {
            return new Result(0, 0, List.of(), true, null, null);
        }
        String sourceHash = sha256(playersJson.getBytes(StandardCharsets.UTF_8));
        if (isConverted(backupDir, sourceHash)) {
            return new Result(0, 0, List.of(), true, null, null);
        }

        Map<UUID, Map<String, Object>> players = decodePlayersSubtree(playersJson,
            "acelib_data_kv.players");
        Path backup = backupLegacyJdbc(playersJson, backupDir);
        return convertInto(target, players, backupDir, backup, sourceHash, "jdbc");
    }

    // -----------------------------------------------------------------
    // 核心轉換
    // -----------------------------------------------------------------

    private static Result convertInto(PlayerDataStore target,
            Map<UUID, Map<String, Object>> players, Path backupDir,
            Path backup, String sourceHash, String sourceKind) {
        ensureDirectory(backupDir);

        // 只補目標缺少的玩家：現場新值永遠勝出，重跑也不覆寫。
        List<UUID> pending = new ArrayList<>();
        for (UUID uuid : players.keySet()) {
            Map<String, Object> values = players.get(uuid);
            if (values != null && !values.isEmpty() && target.fieldCount(uuid) == 0) {
                pending.add(uuid);
            }
        }

        List<String> written = new ArrayList<>();
        for (int start = 0; start < pending.size(); start += BATCH_SIZE) {
            int end = Math.min(start + BATCH_SIZE, pending.size());
            List<PlayerDataStore.FieldChange> batch = new ArrayList<>();
            for (UUID uuid : pending.subList(start, end)) {
                Map<String, Object> values = players.get(uuid);
                if (values == null || values.isEmpty()) {
                    continue;
                }
                for (Map.Entry<String, Object> entry : values.entrySet()) {
                    batch.add(PlayerDataStore.FieldChange.upsert(
                        uuid, entry.getKey(), entry.getValue()));
                }
            }
            if (!batch.isEmpty()) {
                target.applyChanges(batch);
            }
            written.addAll(pending.subList(start, end).stream()
                .map(UUID::toString).toList());
        }

        VerificationReport report = verify(target, players, new HashSet<>(pending));
        Path reportPath = writeReport(backupDir, report);
        if (!report.passed()) {
            throw new DataStoreException("ACELIB-DATA-013",
                "player data conversion verification failed from " + sourceKind
                    + " source: " + report.describeFailures()
                    + "; source data is intact and a backup is at " + backup
                    + ". Nothing was rolled back in the source; inspect the report at "
                    + reportPath);
        }
        writeMarker(backupDir, sourceKind, sourceHash, report.playerCount());
        pruneBackups(backupDir, sourceKind);
        return new Result(written.size(), report.failedCount(), written, false,
            backup, reportPath);
    }

    /**
     * 三層校驗：來源玩家筆數、逐玩家內容雜湊與欄位存在性。
     *
     * <p>本次待匯入玩家會完整比對讀回欄位與來源雜湊；已存在的玩家只確認仍有資料，
     * 不比較內容，以保留轉換後的現場更新。不依賴 store 實作細節，因此同一套校驗
     * 對 SQLite、MySQL 與其他 {@link PlayerDataStore} 都成立。</p>
     */
    private static VerificationReport verify(PlayerDataStore target,
            Map<UUID, Map<String, Object>> expected, Set<UUID> pending) {
        int playersChecked = 0;
        int playersFailed = 0;
        List<String> failures = new ArrayList<>();
        List<String> rows = new ArrayList<>();

        for (Map.Entry<UUID, Map<String, Object>> entry : expected.entrySet()) {
            UUID uuid = entry.getKey();
            Map<String, Object> expectedValues = entry.getValue();
            if (!pending.contains(uuid)) {
                // 舊資料已存在時只能確認它仍存在；現場資料可能已比來源更新。
                boolean expectedHasData = expectedValues != null && !expectedValues.isEmpty();
                boolean exists = target.fieldCount(uuid) > 0;
                boolean ok = !expectedHasData || exists;
                if (ok) {
                    playersChecked++;
                } else {
                    playersFailed++;
                    failures.add("existing player " + uuid + " missing after conversion");
                }
                rows.add(reportRow(uuid, expectedValues, ok,
                    expectedHasData ? "existing-preserved" : "no-source-fields"));
                continue;
            }

            Map<String, Object> actualValues = target.load(uuid)
                .map(PlayerDataConverter::fieldsOf)
                .orElse(Map.of());
            String expectedHash = canonicalHash(expectedValues);
            String actualHash = canonicalHash(actualValues);
            boolean ok = expectedHash.equals(actualHash);
            if (ok) {
                playersChecked++;
            } else {
                playersFailed++;
                failures.add("player " + uuid + " content hash mismatch: expected "
                    + expectedHash + " but got " + actualHash);
            }
            rows.add(reportRow(uuid, expectedValues, ok, ok ? "" : "hash-mismatch"));
        }

        return new VerificationReport(playersChecked + playersFailed, playersChecked,
            playersFailed, rows, failures);
    }

    /** 把 {@link Record} 轉成欄位 map（Record 沒有 snapshot()，需逐欄位取出）。 */
    private static Map<String, Object> fieldsOf(Record record) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (String field : record.keys()) {
            fields.put(field, record.get(field));
        }
        return fields;
    }

    private static String reportRow(UUID uuid, Map<String, Object> values,
            boolean ok, String reason) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("uuid", uuid.toString());
        row.put("fieldCount", values == null ? 0 : values.size());
        row.put("hash", canonicalHash(values == null ? Map.of() : values));
        row.put("status", ok ? "OK" : "FAILED");
        if (!reason.isEmpty()) {
            row.put("reason", reason);
        }
        return new JsonCodecImpl().encode(row);
    }

    /**
     * 逐玩家校驗報告。
     *
     * @param playerCount  檢查的玩家總數
     * @param passedCount  通過的玩家數
     * @param failedCount  失敗的玩家數
     * @param rows         每位玩家一列的 JSON 片段
     * @param failures     失敗原因描述
     */
    record VerificationReport(int playerCount, int passedCount, int failedCount,
            List<String> rows, List<String> failures) {

        boolean passed() {
            return failedCount == 0;
        }

        String describeFailures() {
            if (failures.isEmpty()) {
                return "no failure";
            }
            int shown = Math.min(failures.size(), 5);
            String detail = String.join("; ", failures.subList(0, shown));
            return shown < failures.size()
                ? detail + "（另有 " + (failures.size() - shown) + " 筆，見報告檔）"
                : detail;
        }
    }

    private static Path writeReport(Path backupDir, VerificationReport report) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("generatedAt", Instant.now().toString());
        root.put("playerCount", report.playerCount());
        root.put("passed", report.passedCount());
        root.put("failed", report.failedCount());
        root.put("verified", report.passed());
        root.put("players", String.join("\n", report.rows()));
        Path path = backupDir.resolve(
            REPORT_PREFIX + STAMP.format(Instant.now()) + ".json");
        writeAtomic(path, new JsonCodecImpl().encode(root));
        return path;
    }

    // -----------------------------------------------------------------
    // marker
    // -----------------------------------------------------------------

    private static boolean isConverted(Path backupDir, String sourceHash) {
        Path marker = markerPath(backupDir);
        if (!Files.exists(marker)) {
            return false;
        }
        try {
            String text = Files.readString(marker, StandardCharsets.UTF_8);
            Map<String, Object> parsed = new JsonCodecImpl().decode(text);
            return sourceHash.equals(parsed.get("sourceHash"));
        } catch (IOException | DataStoreException ex) {
            // marker 讀不到或格式損壞：保守地視為未轉換（重跑只補缺行，仍安全）
            return false;
        }
    }

    private static void writeMarker(Path backupDir, String sourceKind,
            String sourceHash, int playerCount) {
        Map<String, Object> marker = new LinkedHashMap<>();
        marker.put("sourceKind", sourceKind);
        marker.put("sourceHash", sourceHash);
        marker.put("playerCount", playerCount);
        marker.put("convertedAt", Instant.now().toString());
        writeAtomic(markerPath(backupDir), new JsonCodecImpl().encode(marker));
    }

    /**
     * 只清理本次轉換自己產生、且已通過校驗的舊備份。
     *
     * <p>以檔名前綴比對，絕不碰非本次流程產生的檔案。</p>
     */
    private static void pruneBackups(Path backupDir, String sourceKind) {
        String prefix = switch (sourceKind) {
            case "json" -> "player-data.json.";
            case "jdbc" -> "jdbc-legacy-";
            default -> null;
        };
        if (prefix == null) {
            return;
        }
        try (var entries = Files.list(backupDir)) {
            entries.filter(path -> path.getFileName().toString().startsWith(prefix))
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .limit(Math.max(0, countBackups(backupDir, prefix) - KEEP_BACKUPS))
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignore) {
                        // 清理失敗不影響轉換結果；備份多留比少留安全
                    }
                });
        } catch (IOException ignore) {
            // 備份目錄不可讀：略過清理
        }
    }

    private static final int KEEP_BACKUPS = 5;

    private static long countBackups(Path backupDir, String prefix) throws IOException {
        try (var entries = Files.list(backupDir)) {
            return entries.filter(path -> path.getFileName().toString().startsWith(prefix))
                .count();
        }
    }

    // -----------------------------------------------------------------
    // 來源讀取
    // -----------------------------------------------------------------

    private static Map<UUID, Map<String, Object>> readLegacyJson(Path legacyJson) {
        String text;
        try {
            text = Files.readString(legacyJson, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new DataStoreException("ACELIB-DATA-001",
                "failed to read legacy player data file " + legacyJson + ": "
                    + ex.getMessage(), ex);
        }
        Map<String, Object> root;
        try {
            root = new JsonCodecImpl().decode(text);
        } catch (DataStoreException ex) {
            throw new DataStoreException("ACELIB-DATA-002",
                "legacy player data file is not valid JSON (" + legacyJson + "): "
                    + ex.getMessage() + "; the file was left untouched", ex);
        }
        Object players = root.get("players");
        if (players == null) {
            return Map.of();
        }
        return toPlayerIndex(players, legacyJson.toString());
    }

    private static String readLegacyJdbcPlayers(String storeName, String table,
            javax.sql.DataSource dataSource) {
        String sql = "SELECT v FROM " + table + " WHERE store_name = ? AND k = ?";
        try (java.sql.Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, storeName);
            ps.setString(2, "players");
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String raw = rs.getString(1);
                if (raw == null || raw.isBlank()) {
                    return null;
                }
                // 舊 DataStore 將 players 子樹存成 JSON 文字，交由共用解析器解碼。
                return raw;
            }
        } catch (java.sql.SQLException ex) {
            throw new DataStoreException("ACELIB-DATA-008",
                "[player-convert:read-legacy-jdbc] failed to read legacy players row: "
                    + ex.getMessage(), ex);
        }
    }

    private static Map<UUID, Map<String, Object>> decodePlayersSubtree(String json, String origin) {
        Map<String, Object> parsed;
        try {
            parsed = new JsonCodecImpl().decode(json);
        } catch (DataStoreException ex) {
            throw new DataStoreException("ACELIB-DATA-002",
                "legacy players payload is not valid JSON (" + origin + "): "
                    + ex.getMessage(), ex);
        }
        return toPlayerIndex(parsed, origin);
    }

    /**
     * 把 {@code players} 子樹轉成 {@code UUID → 欄位} 索引。
     *
     * <p>非 UUID 鍵（其他用途的條目）不列入，但會保留在原始備份裡，不會被刪除。</p>
     */
    private static Map<UUID, Map<String, Object>> toPlayerIndex(Object players, String origin) {
        if (!(players instanceof Map<?, ?> playerMap)) {
            throw new DataStoreException("ACELIB-DATA-002",
                "legacy 'players' node in " + origin + " must be an object, got "
                    + players.getClass().getName());
        }
        Map<UUID, Map<String, Object>> index = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : playerMap.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                continue;
            }
            UUID uuid;
            try {
                uuid = UUID.fromString(key);
            } catch (IllegalArgumentException ignore) {
                continue;
            }
            Object value = entry.getValue();
            if (!(value instanceof Map<?, ?> fields)) {
                continue;
            }
            Map<String, Object> converted = new LinkedHashMap<>();
            for (Map.Entry<?, ?> field : fields.entrySet()) {
                if (field.getKey() instanceof String fieldName) {
                    converted.put(fieldName, field.getValue());
                }
            }
            index.put(uuid, converted);
        }
        return index;
    }

    // -----------------------------------------------------------------
    // 備份
    // -----------------------------------------------------------------

    private static Path backupLegacyJson(Path legacyJson, Path backupDir) {
        ensureDirectory(backupDir);
        Path backup = backupDir.resolve("player-data.json."
            + STAMP.format(Instant.now()) + ".bak");
        try {
            // temp + ATOMIC_MOVE：備份不會出現「寫到一半」的狀態
            Path tmp = Files.createTempFile(backupDir, "acelib-backup-", ".tmp");
            Files.copy(legacyJson, tmp, StandardCopyOption.REPLACE_EXISTING);
            forceSync(tmp);
            move(tmp, backup);
        } catch (IOException ex) {
            throw new DataStoreException("ACELIB-DATA-001",
                "failed to back up legacy player data file " + legacyJson + ": "
                    + ex.getMessage() + "; conversion aborted, source untouched", ex);
        }
        return backup;
    }

    private static Path backupLegacyJdbc(String playersJson, Path backupDir) {
        ensureDirectory(backupDir);
        Path backup = backupDir.resolve(
            "jdbc-legacy-" + STAMP.format(Instant.now()) + ".json");
        writeAtomic(backup, playersJson);
        return backup;
    }

    // -----------------------------------------------------------------
    // 檔案與雜湊工具
    // -----------------------------------------------------------------

    private static void ensureDirectory(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException ex) {
            throw new DataStoreException("ACELIB-DATA-001",
                "failed to create backup directory " + dir + ": " + ex.getMessage(), ex);
        }
    }

    private static void writeAtomic(Path target, String content) {
        Path dir = target.toAbsolutePath().getParent();
        try {
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, "acelib-write-", ".tmp");
            try {
                Files.writeString(tmp, content, StandardCharsets.UTF_8);
                forceSync(tmp);
                move(tmp, target);
            } catch (IOException ex) {
                Files.deleteIfExists(tmp);
                throw ex;
            }
        } catch (IOException ex) {
            throw new DataStoreException("ACELIB-DATA-001",
                "failed to write " + target + ": " + ex.getMessage(), ex);
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void forceSync(Path path) throws IOException {
        try (java.nio.channels.FileChannel channel =
                java.nio.channels.FileChannel.open(path,
                    java.nio.file.StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static String hashFile(Path path) {
        try {
            return sha256(Files.readAllBytes(path));
        } catch (IOException ex) {
            throw new DataStoreException("ACELIB-DATA-001",
                "failed to hash legacy player data file " + path + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * 欄位集合的 canonical 雜湊。
     *
     * <p>鍵排序後以 JSON 編碼，讓 map 迭代順序不影響雜湊結果。</p>
     */
    static String canonicalHash(Map<String, Object> values) {
        Map<String, Object> sorted = new TreeMap<>();
        if (values != null) {
            sorted.putAll(values);
        }
        String json = new JsonCodecImpl().encode(sorted);
        return sha256(json.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16));
                hex.append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new UncheckedIOException(
                new IOException("SHA-256 not available", ex));
        }
    }
}
