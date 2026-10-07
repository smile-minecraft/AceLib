package com.smile.acelib.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link PlayerDataConverter} 的行為測試：既有 JSON／JDBC 資料轉換、備份、校驗、
 * marker 與重跑冪等。
 *
 * <p>所有測試都在 {@code @TempDir} 內建立自己的複本：來源檔案與目標資料庫都由測試
 * 產生，不碰任何真實資料。</p>
 */
@DisplayName("PlayerDataConverter")
class PlayerDataConverterTest {

    @TempDir
    Path tempDir;

    private Path legacyJson;
    private Path dbFile;
    private PlayerDataStore target;

    @BeforeEach
    void setUp() {
        legacyJson = tempDir.resolve("player-data.json");
        dbFile = tempDir.resolve("players.db");
        target = PlayerDataStores.sqlite(dbFile, SchemaVersion.V1_0);
        target.init();
    }

    // -----------------------------------------------------------------
    // fixture
    // -----------------------------------------------------------------

    /**
     * 寫出含 unicode、emoji、巢狀結構、空紀錄、數字多型別的舊格式 JSON。
     */
    private void writeLegacyJson(Map<String, Object> players, String extraTopLevelKey) {
        Map<String, Object> root = new java.util.LinkedHashMap<>();
        root.put("_version", "1.0");
        root.put("players", players);
        root.put("otherStoreData", Map.of("keep", "me"));
        if (extraTopLevelKey != null) {
            root.put(extraTopLevelKey, "extra");
        }
        try {
            Files.writeString(legacyJson,
                new JsonCodecImpl().encode(root), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Map<String, Object> player(String coins, String nested, List<?> tags) {
        Map<String, Object> value = new java.util.LinkedHashMap<>();
        value.put("coins", coins);
        value.put("nested", nested);
        value.put("tags", tags);
        return value;
    }

    private Map<String, Object> fullFixture() {
        Map<String, Object> players = new java.util.LinkedHashMap<>();
        players.put(UUID.randomUUID().toString(),
            player("100", "值-😀-nested", List.of("a", "b")));
        players.put(UUID.randomUUID().toString(), player("0", "", List.of()));
        players.put(UUID.randomUUID().toString(), new java.util.LinkedHashMap<>());
        return players;
    }

    // -----------------------------------------------------------------
    // 轉換
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("從既有 JSON 轉換")
    class FromLegacyJson {

        @Test
        @DisplayName("轉換後資料完整：unicode、emoji、巢狀與清單皆保留")
        void convertsAllPlayerData() {
            Map<String, Object> players = fullFixture();
            writeLegacyJson(players, null);

            PlayerDataConverter.Result result =
                PlayerDataConverter.fromLegacyJsonFile(legacyJson, target, tempDir);

            List<String> expectedWrites = players.entrySet().stream()
                .filter(entry -> !((Map<?, ?>) entry.getValue()).isEmpty())
                .map(Map.Entry::getKey)
                .toList();
            assertEquals(expectedWrites.size(), result.convertedPlayers());
            assertEquals(expectedWrites, result.convertedUuids());
            assertEquals(0, result.failedPlayers());
            for (String uuidText : players.keySet()) {
                UUID uuid = UUID.fromString(uuidText);
                Map<?, ?> expectedMap = (Map<?, ?>) players.get(uuidText);
                if (expectedMap.isEmpty()) {
                    // 空紀錄沒有任何欄位可寫：不產生孤兒列，讀回應為 empty
                    assertEquals(0, target.fieldCount(uuid),
                        "空玩家紀錄不應寫出任何欄位");
                    continue;
                }
                Record loaded = target.load(uuid).orElseThrow();
                assertEquals(String.valueOf(expectedMap.get("coins")),
                    String.valueOf(loaded.get("coins")));
                assertEquals(String.valueOf(expectedMap.get("nested")),
                    String.valueOf(loaded.get("nested")));
                assertEquals(String.valueOf(expectedMap.get("tags")),
                    String.valueOf(loaded.get("tags")));
            }
        }

        @Test
        @DisplayName("來源檔案在轉換後逐位元不變（真資料零刪除）")
        void sourceFileUntouched() throws IOException {
            writeLegacyJson(fullFixture(), null);
            byte[] before = Files.readAllBytes(legacyJson);

            PlayerDataConverter.fromLegacyJsonFile(legacyJson, target, tempDir);

            assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(legacyJson)),
                "轉換不得修改來源檔案");
        }

        @Test
        @DisplayName("其他 store 的資料不進入玩家資料表")
        void otherStoreDataNotConverted() {
            writeLegacyJson(fullFixture(), "unrelatedStore");

            PlayerDataConverter.Result result =
                PlayerDataConverter.fromLegacyJsonFile(legacyJson, target, tempDir);

            // 只有 players 子樹被轉換；報告裡不應出現其他 store 的欄位
            assertTrue(result.convertedPlayers() > 0);
            UUID anyPlayer = UUID.fromString(result.convertedUuids().get(0));
            Record loaded = target.load(anyPlayer).orElseThrow();
            assertFalse(loaded.has("otherStoreData"));
            assertFalse(loaded.has("unrelatedStore"));
        }

        @Test
        @DisplayName("轉換前先產生備份，且備份內容等於來源")
        void createsBackupBeforeConverting() throws IOException {
            writeLegacyJson(fullFixture(), null);

            PlayerDataConverter.Result result =
                PlayerDataConverter.fromLegacyJsonFile(legacyJson, target, tempDir);

            assertTrue(Files.exists(result.backupPath()),
                "轉換必須先備份來源檔案：" + result.backupPath());
            byte[] backup = Files.readAllBytes(result.backupPath());
            assertTrue(java.util.Arrays.equals(
                backup, Files.readAllBytes(legacyJson)),
                "備份必須是來源檔案的逐位元副本");
        }

        @Test
        @DisplayName("備份放在 backupDir 之下且檔名帶時間戳")
        void backupLocationAndName() {
            Path backupDir = tempDir.resolve("backups");
            writeLegacyJson(fullFixture(), null);

            PlayerDataConverter.Result result = PlayerDataConverter.fromLegacyJsonFile(
                legacyJson, target, backupDir);

            assertEquals(backupDir, result.backupPath().getParent());
            assertTrue(result.backupPath().getFileName().toString()
                    .startsWith("player-data.json."),
                "備份檔名須帶前綴與時間戳：" + result.backupPath().getFileName());
        }

        @Test
        @DisplayName("來源不存在時轉換為 no-op，不拋例外")
        void missingSourceIsNoOp() {
            PlayerDataConverter.Result result =
                PlayerDataConverter.fromLegacyJsonFile(legacyJson, target, tempDir);
            assertEquals(0, result.convertedPlayers());
            assertTrue(result.skipped(), "來源不存在應標記為 skipped");
        }

        @Test
        @DisplayName("來源格式損壞時以 ACELIB-DATA-002 失敗，且不寫入任何資料")
        void corruptSourceFails() throws IOException {
            Files.writeString(legacyJson, "{not json", StandardCharsets.UTF_8);

            DataStoreException failure = assertThrows(DataStoreException.class,
                () -> PlayerDataConverter.fromLegacyJsonFile(legacyJson, target, tempDir));
            assertEquals("ACELIB-DATA-002", failure.getCode());
            assertEquals(0, target.fieldCount(UUID.randomUUID()));
        }
    }

    @Nested
    @DisplayName("marker 與重跑")
    class MarkerAndRerun {

        @Test
        @DisplayName("轉換成功後寫 marker；重跑時跳過")
        void writesMarkerAndSkipsOnRerun() {
            Map<String, Object> players = fullFixture();
            writeLegacyJson(players, null);

            PlayerDataConverter.Result first =
                PlayerDataConverter.fromLegacyJsonFile(legacyJson, target, tempDir);
            assertFalse(first.skipped());
            assertTrue(Files.exists(PlayerDataConverter.markerPath(tempDir)),
                "完整校驗通過後必須寫 marker");

            PlayerDataConverter.Result second =
                PlayerDataConverter.fromLegacyJsonFile(legacyJson, target, tempDir);
            assertTrue(second.skipped(), "marker 存在時重跑必須跳過");
            assertEquals(0, second.convertedPlayers());
        }

        @Test
        @DisplayName("重跑不覆寫既有資料：現場新值勝出")
        void rerunDoesNotOverwriteExistingData() {
            Map<String, Object> players = new java.util.LinkedHashMap<>();
            UUID uuid = UUID.randomUUID();
            players.put(uuid.toString(), player("100", "old", List.of()));
            writeLegacyJson(players, null);

            PlayerDataConverter.fromLegacyJsonFile(legacyJson, target, tempDir);
            // 模擬轉換後玩家登入並更新資料
            target.applyChanges(List.of(
                PlayerDataStore.FieldChange.upsert(uuid, "coins", "999")));

            PlayerDataConverter.Result rerun =
                PlayerDataConverter.fromLegacyJsonFile(legacyJson, target, tempDir);

            assertEquals(0, rerun.convertedPlayers(), "已存在的玩家不應被重複轉換");
            assertEquals("999", target.load(uuid).orElseThrow().getString("coins", null),
                "舊資料不得覆蓋現場新值");
        }

        @Test
        @DisplayName("來源變動後重跑會補上缺少的玩家")
        void rerunAddsNewPlayersWhenSourceChanged() {
            UUID first = UUID.randomUUID();
            Map<String, Object> players = new java.util.LinkedHashMap<>();
            players.put(first.toString(), player("1", "a", List.of()));
            writeLegacyJson(players, null);
            PlayerDataConverter.fromLegacyJsonFile(legacyJson, target, tempDir);

            // 模擬既有玩家在現場更新；重跑不得用來源雜湊覆蓋這筆資料。
            target.applyChanges(List.of(
                PlayerDataStore.FieldChange.upsert(first, "coins", "local-value")));

            UUID second = UUID.randomUUID();
            Map<String, Object> grown = new java.util.LinkedHashMap<>(players);
            grown.put(second.toString(), player("2", "b", List.of()));
            writeLegacyJson(grown, null);

            PlayerDataConverter.Result rerun =
                PlayerDataConverter.fromLegacyJsonFile(legacyJson, target, tempDir);

            assertEquals(1, rerun.convertedPlayers(), "只補上新增的那位玩家");
            assertTrue(target.load(second).isPresent());
            assertEquals("local-value",
                target.load(first).orElseThrow().getString("coins", null),
                "既有玩家資料不得覆寫，且只需確認玩家仍有資料");
        }

        @Test
        @DisplayName("convertedPlayers 只計入確實有欄位寫入的玩家")
        void convertedPlayersExcludesEmptyPlayerRecords() {
            UUID empty = UUID.randomUUID();
            UUID withData = UUID.randomUUID();
            Map<String, Object> players = new java.util.LinkedHashMap<>();
            players.put(empty.toString(), Map.of());
            players.put(withData.toString(), player("2", "b", List.of()));
            writeLegacyJson(players, null);

            PlayerDataConverter.Result result =
                PlayerDataConverter.fromLegacyJsonFile(legacyJson, target, tempDir);

            assertEquals(1, result.convertedPlayers());
            assertEquals(List.of(withData.toString()), result.convertedUuids());
            assertEquals(0, target.fieldCount(empty), "空紀錄不會產生儲存列");
            assertTrue(target.load(withData).isPresent());
        }
    }

    @Nested
    @DisplayName("校驗報告")
    class VerificationReport {

        @Test
        @DisplayName("報告檔存在且逐玩家記錄筆數與雜湊")
        void writesReportFile() throws IOException {
            writeLegacyJson(fullFixture(), null);

            PlayerDataConverter.Result result =
                PlayerDataConverter.fromLegacyJsonFile(legacyJson, target, tempDir);

            Path report = result.reportPath();
            assertTrue(Files.exists(report), "必須產生校驗報告");
            String text = Files.readString(report, StandardCharsets.UTF_8);
            for (String uuidText : result.convertedUuids()) {
                assertTrue(text.contains(uuidText), "報告須記錄每位玩家：" + uuidText);
            }
        }

        @Test
        @DisplayName("校驗不通過時以 ACELIB-DATA-013 失敗")
        void verificationFailureThrows() {
            writeLegacyJson(fullFixture(), null);
            // 注入一個破壞校驗的 store：轉換時看不到剛寫入的資料
            PlayerDataStore broken = new NonPersistingStore(target);

            DataStoreException failure = assertThrows(DataStoreException.class,
                () -> PlayerDataConverter.fromLegacyJsonFile(legacyJson, broken, tempDir));
            assertEquals("ACELIB-DATA-013", failure.getCode());
        }
    }

    /**
     * 包裝真 store 但讓 {@code load} 永遠回 empty 的測試替身，
     * 用來模擬「寫入後讀不到」導致校驗失敗的情境。
     */
    private static final class NonPersistingStore implements PlayerDataStore {

        private final PlayerDataStore delegate;

        NonPersistingStore(PlayerDataStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public String name() {
            return "non-persisting";
        }

        @Override
        public String owner() {
            return delegate.owner();
        }

        @Override
        public SchemaVersion schemaVersion() {
            return delegate.schemaVersion();
        }

        @Override
        public boolean isInitialized() {
            return true;
        }

        @Override
        public boolean isClosed() {
            return false;
        }

        @Override
        public void init() {
        }

        @Override
        public java.util.Optional<Record> load(UUID uuid) {
            return java.util.Optional.empty();
        }

        @Override
        public void applyChanges(List<FieldChange> changes) {
            delegate.applyChanges(changes);
        }

        @Override
        public void deletePlayer(UUID uuid) {
            delegate.deletePlayer(uuid);
        }

        @Override
        public long revisionOf(UUID uuid, String field) {
            return delegate.revisionOf(uuid, field);
        }

        @Override
        public int fieldCount(UUID uuid) {
            return delegate.fieldCount(uuid);
        }

        @Override
        public void close() {
        }
    }
}
