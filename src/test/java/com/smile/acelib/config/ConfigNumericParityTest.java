package com.smile.acelib.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 數值轉換兩入口對照：同一組值分別經 {@link ConfigSnapshot} 的 getter 與
 * {@link ConfigBinder#bind}，數字值的結果相同（值相同、例外型別與錯誤碼相同）。
 *
 * <p>非 Number 與缺值是刻意不同的設計（getter 回預設值、綁定器拋錯），
 * 見 {@link #nonNumber_divergenceIsDocumented}。</p>
 */
@DisplayName("ConfigSnapshot 與 ConfigBinder 數值轉換對照")
class ConfigNumericParityTest {

    public record ParityInt(@ConfigBinder.ConfigKey("v") int v) {
    }

    public record ParityLong(@ConfigBinder.ConfigKey("v") long v) {
    }

    public record ParityDouble(@ConfigBinder.ConfigKey("v") double v) {
    }

    /** 取值結果：成功帶值，失敗帶錯誤碼。 */
    private record Outcome(Object value, String errorCode) {
    }

    private static Outcome capture(Callable<Object> action) {
        try {
            return new Outcome(action.call(), null);
        } catch (ConfigBindingException ex) {
            return new Outcome(null, ex.getCode());
        } catch (Exception ex) {
            fail("不應拋非綁定例外：" + ex);
            throw new AssertionError(ex);
        }
    }

    private static void assertParity(String kind, Object raw) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("v", raw);
        ConfigSnapshot snapshot = new ConfigSnapshot(values);
        Outcome getter;
        Outcome binder;
        switch (kind) {
            case "int" -> {
                getter = capture(() -> snapshot.getInt("v", 0));
                binder = capture(() -> ConfigBinder.bind(snapshot, ParityInt.class).v());
            }
            case "long" -> {
                getter = capture(() -> snapshot.getLong("v", 0L));
                binder = capture(() -> ConfigBinder.bind(snapshot, ParityLong.class).v());
            }
            case "double" -> {
                getter = capture(() -> snapshot.getDouble("v", 0.0));
                binder = capture(() -> ConfigBinder.bind(snapshot, ParityDouble.class).v());
            }
            default -> throw new IllegalArgumentException(kind);
        }
        assertEquals(getter, binder, kind + " 兩入口結果應一致，輸入：" + raw);
        if (getter.errorCode() != null) {
            assertEquals("ACELIB-CFG-007", getter.errorCode(), "輸入：" + raw);
        }
    }

    @Test
    @DisplayName("int 兩入口一致：合法整數、小數、溢位、非有限值")
    void int_parity() {
        Object[] raws = {
            42, -7, 0, Integer.MAX_VALUE, Integer.MIN_VALUE,
            2.9, 99999999999L, Long.MIN_VALUE, Long.MAX_VALUE,
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            1e19, Math.nextAfter(9223372036854775808.0, 0.0),
            new BigDecimal("123"), new BigDecimal("9223372036854775808")
        };
        for (Object raw : raws) {
            assertParity("int", raw);
        }
    }

    @Test
    @DisplayName("long 兩入口一致：端點、邊界相鄰值、超範圍")
    void long_parity() {
        Object[] raws = {
            42, 2.5, 1e19,
            Long.MIN_VALUE, Long.MAX_VALUE,
            -9223372036854775808.0,
            Math.nextAfter(-9223372036854775808.0, Double.NEGATIVE_INFINITY),
            Math.nextAfter(9223372036854775808.0, 0.0),
            9223372036854775808.0,
            Double.NaN, Double.POSITIVE_INFINITY,
            new BigDecimal("9223372036854775808"),
            new BigDecimal("-18446744073709551616")
        };
        for (Object raw : raws) {
            assertParity("long", raw);
        }
    }

    @Test
    @DisplayName("double 兩入口一致：合法值與非有限值")
    void double_parity() {
        Object[] raws = {
            2.5, 3, 1e19, new BigDecimal("0.1"),
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY
        };
        for (Object raw : raws) {
            assertParity("double", raw);
        }
    }

    @Test
    @DisplayName("非 Number 與缺值是刻意不同的設計：getter 回預設、綁定器拋錯")
    void nonNumber_divergenceIsDocumented() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("text", "nope");
        values.put("flag", true);
        ConfigSnapshot snapshot = new ConfigSnapshot(values);

        assertEquals(7, snapshot.getInt("text", 7));
        assertEquals(7L, snapshot.getLong("flag", 7L));
        assertEquals(0.5, snapshot.getDouble("missing", 0.5));

        for (String path : new String[]{"text", "flag", "missing"}) {
            Map<String, Object> single = new LinkedHashMap<>();
            if (!path.equals("missing")) {
                single.put("v", values.get(path));
            }
            ConfigSnapshot one = new ConfigSnapshot(single);
            ConfigBindingException ex = null;
            try {
                ConfigBinder.bind(one, ParityInt.class);
            } catch (ConfigBindingException caught) {
                ex = caught;
            }
            assertTrue(ex != null, "綁定器對非 Number／缺失應拋錯：" + path);
            assertEquals("ACELIB-CFG-007", ex.getCode());
        }
    }
}
