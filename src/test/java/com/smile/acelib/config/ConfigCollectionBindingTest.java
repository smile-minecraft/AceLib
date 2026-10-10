package com.smile.acelib.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 集合綁定測試。
 *
 * <p>鎖住路線圖缺口：「設定繫結支援 {@code Map<String, T>}、
 * {@code Set<T>} 和物件清單，清單元素依宣告的型別驗證」。
 * 錯誤一律 {@code ACELIB-CFG-007} 並帶完整元素路徑：
 * Map 項目為 {@code <path>.<key>}、Set 與清單元素為 {@code <path>[i]}、
 * 巢狀元素內欄位為 {@code <path>[i].<field>}。</p>
 */
@DisplayName("ConfigBinder 集合綁定（Map／Set／物件清單＋元素路徑）")
class ConfigCollectionBindingTest {

    public record Endpoint(String host, int port) {
    }

    public enum Mode {
        SURVIVAL, CREATIVE
    }

    public record MapHolder(
        @ConfigBinder.ConfigKey("scores") Map<String, Integer> scores) {
    }

    public record MapNestedHolder(
        @ConfigBinder.ConfigKey("endpoints") Map<String, Endpoint> endpoints) {
    }

    public record SetHolder(
        @ConfigBinder.ConfigKey("tags") Set<String> tags) {
    }

    public record SetEnumHolder(
        @ConfigBinder.ConfigKey("modes") Set<Mode> modes) {
    }

    public record ListIntHolder(
        @ConfigBinder.ConfigKey("levels") List<Integer> levels) {
    }

    public record ListEnumHolder(
        @ConfigBinder.ConfigKey("modes") List<Mode> modes) {
    }

    public record ListNestedHolder(
        @ConfigBinder.ConfigKey("endpoints") List<Endpoint> endpoints) {
    }

    public record ListStringHolder(
        @ConfigBinder.ConfigKey("lst") List<String> lst) {
    }

    public record WildcardSetHolder(
        @ConfigBinder.ConfigKey("values") Set<? extends Integer> values) {
    }

    public record WildcardMapHolder(
        @ConfigBinder.ConfigKey("values") Map<String, ? extends Integer> values) {
    }

    public record StringMapHolder(
        @ConfigBinder.ConfigKey("values") Map<String, String> values) {
    }

    public record NestedListHolder(
        @ConfigBinder.ConfigKey("levels") List<List<Integer>> levels) {
    }

    public record GenericListHolder<T>(
        @ConfigBinder.ConfigKey("values") List<T> values) {
    }

    @SuppressWarnings("rawtypes")
    public record RawMapHolder(
        @ConfigBinder.ConfigKey("scores") Map scores) {
    }

    public record RangedListHolder(
        @ConfigBinder.ConfigKey("levels")
        @ConfigBinder.ConfigRange(min = 1, max = 10) List<Integer> levels) {
    }

    public static class PlainEndpoint {
        @ConfigBinder.ConfigKey("host")
        public String host;
        @ConfigBinder.ConfigKey("port")
        public int port;
    }

    public record ListPlainHolder(
        @ConfigBinder.ConfigKey("endpoints") List<PlainEndpoint> endpoints) {
    }

    // -----------------------------------------------------------------
    // Map
    // -----------------------------------------------------------------

    @Test
    @DisplayName("Map 合法對映成功（值依宣告型別驗證）")
    void map_valid_bindsAllEntries() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("scores", Map.of("alice", 10, "bob", 20));

        MapHolder holder = ConfigBinder.bind(new ConfigSnapshot(values), MapHolder.class);

        assertEquals(Map.of("alice", 10, "bob", 20), holder.scores());
    }

    @Test
    @DisplayName("Map 某一筆值型別錯：CFG-007 且訊息含 path.key")
    void map_badValue_reportsEntryPath() {
        Map<String, Object> scores = new LinkedHashMap<>();
        scores.put("alice", 10);
        scores.put("bob", "not-a-number");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("scores", scores);

        ConfigBindingException ex = assertThrows(ConfigBindingException.class,
            () -> ConfigBinder.bind(new ConfigSnapshot(values), MapHolder.class));

        assertEquals("ACELIB-CFG-007", ex.getCode());
        assertTrue(ex.getMessage().contains("scores.bob"),
            "錯誤必須帶完整項目路徑 scores.bob，實際：" + ex.getMessage());
    }

    @Test
    @DisplayName("Map 巢狀值缺必填欄位：訊息含 path.key.field")
    void map_nestedMissingField_reportsNestedEntryPath() {
        Map<String, Object> main = new LinkedHashMap<>();
        main.put("host", "play.example");
        Map<String, Object> endpoints = new LinkedHashMap<>();
        endpoints.put("main", main);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("endpoints", endpoints);

        ConfigBindingException ex = assertThrows(ConfigBindingException.class,
            () -> ConfigBinder.bind(new ConfigSnapshot(values), MapNestedHolder.class));

        assertEquals("ACELIB-CFG-007", ex.getCode());
        assertTrue(ex.getMessage().contains("endpoints.main.port"),
            "錯誤必須帶完整巢狀項目路徑 endpoints.main.port，實際：" + ex.getMessage());
    }

    @Test
    @DisplayName("Map 合法巢狀對映成功")
    void map_nestedValid_bindsAllEntries() {
        Map<String, Object> main = new LinkedHashMap<>();
        main.put("host", "play.example");
        main.put("port", 25565);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("endpoints", Map.of("main", main));

        MapNestedHolder holder = ConfigBinder.bind(new ConfigSnapshot(values), MapNestedHolder.class);

        assertEquals("play.example", holder.endpoints().get("main").host());
        assertEquals(25565, holder.endpoints().get("main").port());
    }

    // -----------------------------------------------------------------
    // Set
    // -----------------------------------------------------------------

    @Test
    @DisplayName("Set 去重且保留首次出現順序")
    void set_duplicates_removed_firstOrderKept() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("tags", List.of("b", "a", "b"));

        SetHolder holder = ConfigBinder.bind(new ConfigSnapshot(values), SetHolder.class);

        assertEquals(new java.util.LinkedHashSet<>(List.of("b", "a")), holder.tags());
    }

    @Test
    @DisplayName("Set<enum> 合法值成功")
    void set_enumValid_bindsAll() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("modes", List.of("SURVIVAL", "CREATIVE", "SURVIVAL"));

        SetEnumHolder holder = ConfigBinder.bind(new ConfigSnapshot(values), SetEnumHolder.class);

        assertEquals(Set.of(Mode.SURVIVAL, Mode.CREATIVE), holder.modes());
    }

    @Test
    @DisplayName("Set 非法元素：CFG-007 且訊息含 path[i]")
    void set_badElement_reportsIndexedPath() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("modes", List.of("SURVIVAL", "BOGUS"));

        ConfigBindingException ex = assertThrows(ConfigBindingException.class,
            () -> ConfigBinder.bind(new ConfigSnapshot(values), SetEnumHolder.class));

        assertEquals("ACELIB-CFG-007", ex.getCode());
        assertTrue(ex.getMessage().contains("modes[1]"),
            "錯誤必須帶完整元素路徑 modes[1]，實際：" + ex.getMessage());
    }

    // -----------------------------------------------------------------
    // 物件／型別清單
    // -----------------------------------------------------------------

    @Test
    @DisplayName("List<Integer> 逐元素驗證（合法成功）")
    void list_typedIntValid_bindsAll() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("levels", List.of(1, 2, 3));

        ListIntHolder holder = ConfigBinder.bind(new ConfigSnapshot(values), ListIntHolder.class);

        assertEquals(List.of(1, 2, 3), holder.levels());
    }

    @Test
    @DisplayName("List<Integer> 元素型別錯：CFG-007 且訊息含 path[i]")
    void list_typedIntBadElement_reportsIndexedPath() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("levels", java.util.Arrays.asList(1, "not-a-number"));

        ConfigBindingException ex = assertThrows(ConfigBindingException.class,
            () -> ConfigBinder.bind(new ConfigSnapshot(values), ListIntHolder.class));

        assertEquals("ACELIB-CFG-007", ex.getCode());
        assertTrue(ex.getMessage().contains("levels[1]"),
            "錯誤必須帶完整元素路徑 levels[1]，實際：" + ex.getMessage());
    }

    @Test
    @DisplayName("List<巢狀> 逐元素綁定（合法成功）")
    void list_nestedValid_bindsAll() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("host", "a.example");
        first.put("port", 1);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("host", "b.example");
        second.put("port", 2);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("endpoints", List.of(first, second));

        ListNestedHolder holder = ConfigBinder.bind(new ConfigSnapshot(values), ListNestedHolder.class);

        assertEquals(2, holder.endpoints().size());
        assertEquals("a.example", holder.endpoints().get(0).host());
        assertEquals(2, holder.endpoints().get(1).port());
    }

    @Test
    @DisplayName("List<巢狀> 元素內欄位錯：訊息含 path[i].field")
    void list_nestedBadField_reportsNestedIndexedPath() {
        Map<String, Object> element = new LinkedHashMap<>();
        element.put("host", "a.example");
        element.put("port", "not-a-number");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("endpoints", List.of(element));

        ConfigBindingException ex = assertThrows(ConfigBindingException.class,
            () -> ConfigBinder.bind(new ConfigSnapshot(values), ListNestedHolder.class));

        assertEquals("ACELIB-CFG-007", ex.getCode());
        assertTrue(ex.getMessage().contains("endpoints[0].port"),
            "錯誤必須帶完整巢狀元素路徑 endpoints[0].port，實際：" + ex.getMessage());
    }

    @Test
    @DisplayName("List<enum> 非法元素：訊息含 path[i] 與合法選項")
    void list_enumBadElement_reportsIndexedPathAndAllowed() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("modes", List.of("SURVIVAL", "ADVENTURE"));

        ConfigBindingException ex = assertThrows(ConfigBindingException.class,
            () -> ConfigBinder.bind(new ConfigSnapshot(values), ListEnumHolder.class));

        assertEquals("ACELIB-CFG-007", ex.getCode());
        assertTrue(ex.getMessage().contains("modes[1]"), "實際：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("SURVIVAL"), "實際：" + ex.getMessage());
    }

    @Test
    @DisplayName("集合元素同樣受欄位上的範圍約束（訊息帶 path[i] 與範圍）")
    void list_rangedElementOutOfRange_reportsIndexedPath() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("levels", List.of(5, 99));

        ConfigBindingException ex = assertThrows(ConfigBindingException.class,
            () -> ConfigBinder.bind(new ConfigSnapshot(values), RangedListHolder.class));

        assertEquals("ACELIB-CFG-007", ex.getCode());
        assertTrue(ex.getMessage().contains("levels[1]"), "實際：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("[1.0, 10.0]"), "實際：" + ex.getMessage());
    }

    @Test
    @DisplayName("List<一般類別> 逐元素注入（合法成功）")
    void list_plainPojoElement_bindsAll() {
        Map<String, Object> element = new LinkedHashMap<>();
        element.put("host", "c.example");
        element.put("port", 3);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("endpoints", List.of(element));

        ListPlainHolder holder = ConfigBinder.bind(new ConfigSnapshot(values), ListPlainHolder.class);

        assertEquals(1, holder.endpoints().size());
        assertEquals("c.example", holder.endpoints().get(0).host);
        assertEquals(3, holder.endpoints().get(0).port);
    }

    @Test
    @DisplayName("Map 路徑非對映時報錯（CFG-007，帶路徑）")
    void map_nonMapValue_reportsPath() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("scores", List.of("not", "a", "map"));

        ConfigBindingException ex = assertThrows(ConfigBindingException.class,
            () -> ConfigBinder.bind(new ConfigSnapshot(values), MapHolder.class));

        assertEquals("ACELIB-CFG-007", ex.getCode());
        assertTrue(ex.getMessage().contains("scores"), "實際：" + ex.getMessage());
    }

    // -----------------------------------------------------------------
    // 既有語意不變
    // -----------------------------------------------------------------

    @Test
    @DisplayName("萬用字元元素（Set<? extends Integer>）明確拒絕，不產生錯型別集合")
    void set_wildcardElement_rejectedWithCfg007() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("values", List.of(1, 2));

        ConfigBindingException ex = assertThrows(ConfigBindingException.class,
            () -> ConfigBinder.bind(new ConfigSnapshot(values), WildcardSetHolder.class));

        assertEquals("ACELIB-CFG-007", ex.getCode());
        assertTrue(ex.getMessage().contains("values"), "實際：" + ex.getMessage());
    }

    @Test
    @DisplayName("萬用字元值（Map<String, ? extends Integer>）明確拒絕")
    void map_wildcardValue_rejectedWithCfg007() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("values", Map.of("a", 1));

        ConfigBindingException ex = assertThrows(ConfigBindingException.class,
            () -> ConfigBinder.bind(new ConfigSnapshot(values), WildcardMapHolder.class));

        assertEquals("ACELIB-CFG-007", ex.getCode());
        assertTrue(ex.getMessage().contains("values"), "實際：" + ex.getMessage());
    }

    @Test
    @DisplayName("型別變數元素（List<T>）明確拒絕，不默默轉字串")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void list_typeVariable_rejectedWithCfg007() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("values", List.of(123));

        ConfigBindingException ex = assertThrows(ConfigBindingException.class,
            () -> ConfigBinder.bind(new ConfigSnapshot(values), (Class) GenericListHolder.class));

        assertEquals("ACELIB-CFG-007", ex.getCode());
        assertTrue(ex.getMessage().contains("values"), "實際：" + ex.getMessage());
    }

    @Test
    @DisplayName("Map<String, String> 值為數字時嚴格拒絕（訊息含 values.main）")
    void map_stringValueStrict_rejectsNumber() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("values", Map.of("main", 123));

        ConfigBindingException ex = assertThrows(ConfigBindingException.class,
            () -> ConfigBinder.bind(new ConfigSnapshot(values), StringMapHolder.class));

        assertEquals("ACELIB-CFG-007", ex.getCode());
        assertTrue(ex.getMessage().contains("values.main"), "實際：" + ex.getMessage());
    }

    @Test
    @DisplayName("Set<String> 元素為布林時嚴格拒絕（訊息含 tags[0]）")
    void set_stringElementStrict_rejectsBoolean() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("tags", List.of(true));

        ConfigBindingException ex = assertThrows(ConfigBindingException.class,
            () -> ConfigBinder.bind(new ConfigSnapshot(values), SetHolder.class));

        assertEquals("ACELIB-CFG-007", ex.getCode());
        assertTrue(ex.getMessage().contains("tags[0]"), "實際：" + ex.getMessage());
    }

    @Test
    @DisplayName("空集合綁定成功（不因無元素可驗而誤拒）")
    void emptyCollections_bindAsEmpty() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("levels", List.of());
        values.put("scores", new LinkedHashMap<>());
        values.put("tags", List.of());

        assertEquals(List.of(),
            ConfigBinder.bind(new ConfigSnapshot(values), ListIntHolder.class).levels());
        assertEquals(Map.of(),
            ConfigBinder.bind(new ConfigSnapshot(values), MapHolder.class).scores());
        assertEquals(Set.of(),
            ConfigBinder.bind(new ConfigSnapshot(values), SetHolder.class).tags());
    }

    @Test
    @DisplayName("巢狀集合合法值成功（List<List<Integer>>）")
    void nestedList_valid_bindsAll() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("levels", List.of(List.of(1, 2), List.of(3)));

        NestedListHolder holder = ConfigBinder.bind(new ConfigSnapshot(values), NestedListHolder.class);

        assertEquals(List.of(List.of(1, 2), List.of(3)), holder.levels());
    }

    @Test
    @DisplayName("巢狀集合內層元素錯：訊息含雙重索引 levels[1][0]")
    void nestedList_badInnerElement_reportsNestedIndex() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("levels", List.of(List.of(1), java.util.Arrays.asList("oops")));

        ConfigBindingException ex = assertThrows(ConfigBindingException.class,
            () -> ConfigBinder.bind(new ConfigSnapshot(values), NestedListHolder.class));

        assertEquals("ACELIB-CFG-007", ex.getCode());
        assertTrue(ex.getMessage().contains("levels[1][0]"), "實際：" + ex.getMessage());
    }

    @Test
    @DisplayName("未宣告泛型的 Map 明確拒絕（CFG-007）")
    void rawMap_rejectedWithCfg007() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("scores", Map.of("a", 1));

        ConfigBindingException ex = assertThrows(ConfigBindingException.class,
            () -> ConfigBinder.bind(new ConfigSnapshot(values), RawMapHolder.class));

        assertEquals("ACELIB-CFG-007", ex.getCode());
        assertTrue(ex.getMessage().contains("scores"), "實際：" + ex.getMessage());
    }

    @Test
    @DisplayName("List<String> 維持逐個轉字串（數字轉字串）")
    void list_stringLegacy_toStringElements() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("lst", List.of(1, 2));

        ListStringHolder holder = ConfigBinder.bind(new ConfigSnapshot(values), ListStringHolder.class);

        assertEquals(List.of("1", "2"), holder.lst());
    }

    @Test
    @DisplayName("缺失的集合欄位為 null（由呼叫端決定是否接受）")
    void missingCollections_bindAsNull() {
        ConfigSnapshot snapshot = new ConfigSnapshot(Map.of());

        assertNull(ConfigBinder.bind(snapshot, MapHolder.class).scores());
        assertNull(ConfigBinder.bind(snapshot, SetHolder.class).tags());
        assertNull(ConfigBinder.bind(snapshot, ListIntHolder.class).levels());
        assertNull(ConfigBinder.bind(snapshot, ListNestedHolder.class).endpoints());
    }
}
