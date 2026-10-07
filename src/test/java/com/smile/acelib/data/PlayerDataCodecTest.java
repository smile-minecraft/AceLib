package com.smile.acelib.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link PlayerDataCodec} 與 {@link RecordPlayerDataCodec} 的行為測試。
 *
 * <p>驗證 record 資料模型由 AceLib 編解碼：型別對應、缺欄位預設值、非法資料失敗，
 * 以及巢狀 record／集合的 round-trip。</p>
 */
@DisplayName("PlayerDataCodec")
class PlayerDataCodecTest {

    /** 測試用資料模型：對應一位玩家的完整欄位集合。 */
    record BasicProfile(long coins, String nickname, boolean active, double ratio) {
    }

    /** 巢狀 record。 */
    record Location(String world, int x, int y, int z) {
    }

    /** 含巢狀與集合的資料模型。 */
    record RichProfile(long id, Location home, List<String> tags, Map<String, Object> extra) {
    }

    /** 列舉欄位。 */
    record WithEnum(long id, Rank rank) {
    }

    enum Rank {
        NOVICE, EXPERT
    }

    private final PlayerDataCodec<BasicProfile> codec =
        new RecordPlayerDataCodec<>(BasicProfile.class);

    @Nested
    @DisplayName("encode / decode round-trip")
    class RoundTrip {

        @Test
        @DisplayName("基本型別完整往返")
        void basicRoundTrip() {
            Record encoded = codec.encode(new BasicProfile(100, "ace", true, 0.25d));
            assertEquals(100L, encoded.get("coins"));
            assertEquals("ace", encoded.get("nickname"));
            assertEquals(Boolean.TRUE, encoded.get("active"));
            assertEquals(0.25d, ((Number) encoded.get("ratio")).doubleValue(), 1e-9);

            BasicProfile decoded = codec.decode(encoded);
            assertEquals(100L, decoded.coins());
            assertEquals("ace", decoded.nickname());
            assertTrue(decoded.active());
            assertEquals(0.25d, decoded.ratio(), 1e-9);
        }

        @Test
        @DisplayName("巢狀 record 往返")
        void nestedRecordRoundTrip() {
            PlayerDataCodec<RichProfile> rich =
                new RecordPlayerDataCodec<>(RichProfile.class);
            RichProfile source = new RichProfile(7L, new Location("world", 1, 64, -3),
                List.of("a", "b"), Map.of("k", "v"));
            Record encoded = rich.encode(source);
            RichProfile decoded = rich.decode(encoded);
            assertEquals(7L, decoded.id());
            assertNotNull(decoded.home());
            assertEquals("world", decoded.home().world());
            assertEquals(64, decoded.home().y());
            assertEquals(-3, decoded.home().z());
            assertEquals(List.of("a", "b"), decoded.tags());
            assertEquals("v", decoded.extra().get("k"));
        }

        @Test
        @DisplayName("List 欄位往返（含空清單）")
        void listRoundTrip() {
            PlayerDataCodec<RichProfile> rich =
                new RecordPlayerDataCodec<>(RichProfile.class);
            RichProfile source = new RichProfile(1L, new Location("w", 0, 0, 0),
                List.of(), Map.of());
            RichProfile decoded = rich.decode(rich.encode(source));
            assertEquals(List.of(), decoded.tags());
            assertEquals(Map.of(), decoded.extra());
        }

        @Test
        @DisplayName("列舉欄位往返")
        void enumRoundTrip() {
            PlayerDataCodec<WithEnum> withEnum =
                new RecordPlayerDataCodec<>(WithEnum.class);
            WithEnum decoded = withEnum.decode(
                withEnum.encode(new WithEnum(3L, Rank.EXPERT)));
            assertEquals(3L, decoded.id());
            assertEquals(Rank.EXPERT, decoded.rank());
        }
    }

    @Nested
    @DisplayName("缺欄位與非法資料")
    class MissingAndInvalid {

        @Test
        @DisplayName("缺少 primitive 欄位時使用型別預設值，不拋例外")
        void missingPrimitiveUsesDefault() {
            BasicProfile decoded = codec.decode(new MemoryRecord());
            assertEquals(0L, decoded.coins());
            assertNull(decoded.nickname());
            assertEquals(false, decoded.active());
            assertEquals(0.0d, decoded.ratio(), 1e-9);
        }

        @Test
        @DisplayName("缺少巢狀 record 欄位時為 null")
        void missingNestedRecordIsNull() {
            PlayerDataCodec<RichProfile> rich =
                new RecordPlayerDataCodec<>(RichProfile.class);
            RichProfile decoded = rich.decode(new MemoryRecord());
            assertNull(decoded.home());
        }

        @Test
        @DisplayName("型別不符擲 ACELIB-DATA-002 並帶欄位路徑")
        void typeMismatchThrowsWithFieldPath() {
            Record broken = new MemoryRecord("coins", Map.of("coins", "not-a-number"));
            DataStoreException failure =
                assertThrows(DataStoreException.class, () -> codec.decode(broken));
            assertEquals("ACELIB-DATA-002", failure.getCode());
            assertTrue(failure.getMessage().contains("coins"),
                "錯誤訊息必須指出出問題的欄位：" + failure.getMessage());
        }

        @Test
        @DisplayName("巢狀節點型別不符擲 ACELIB-DATA-002")
        void nestedTypeMismatchThrows() {
            PlayerDataCodec<RichProfile> rich =
                new RecordPlayerDataCodec<>(RichProfile.class);
            Record broken = new MemoryRecord("home", Map.of("home", "should-be-map"));
            DataStoreException failure =
                assertThrows(DataStoreException.class, () -> rich.decode(broken));
            assertEquals("ACELIB-DATA-002", failure.getCode());
            assertTrue(failure.getMessage().contains("home"));
        }

        @Test
        @DisplayName("未支援的欄位型別在建構時拒絕")
        void unsupportedFieldTypeRejectedAtConstruction() {
            assertThrows(IllegalArgumentException.class,
                () -> new RecordPlayerDataCodec<>(WithThreadField.class));
        }

        @Test
        @DisplayName("非 record 類別在建構時拒絕")
        void nonRecordTypeRejected() {
            assertThrows(IllegalArgumentException.class,
                () -> new RecordPlayerDataCodec<>(NotARecord.class));
        }

        @Test
        @DisplayName("null 型別在建構時拒絕")
        void nullTypeRejected() {
            assertThrows(NullPointerException.class,
                () -> new RecordPlayerDataCodec<RichProfile>(null));
        }
    }

    @Nested
    @DisplayName("與 PlayerDataStore 協作")
    class StoreIntegration {

        @Test
        @DisplayName("編碼後寫入 store 再解回，資料等價")
        void encodeWriteDecodeRoundTrip() throws java.io.IOException {
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("codec-rt");
            try (PlayerDataStore store = openStore(dir)) {
                UUID uuid = UUID.randomUUID();
                BasicProfile source = new BasicProfile(42, "zeta", true, 0.5d);
                Record encoded = codec.encode(source);
                store.applyChanges(encoded.keys().stream()
                    .map(field -> PlayerDataStore.FieldChange.upsert(
                        uuid, field, encoded.get(field)))
                    .toList());

                BasicProfile decoded = codec.decode(store.load(uuid).orElseThrow());
                assertEquals(source, decoded);
            }
        }

        private PlayerDataStore openStore(java.nio.file.Path dir) {
            PlayerDataStore store = PlayerDataStores.sqlite(
                dir.resolve("codec.db"), SchemaVersion.V1_0);
            store.init();
            return store;
        }
    }

    /** 含不支援型別的 record。 */
    record WithThreadField(Thread thread) {
    }

    /** 非 record 類別。 */
    static final class NotARecord {
        private final long value = 1L;
    }
}