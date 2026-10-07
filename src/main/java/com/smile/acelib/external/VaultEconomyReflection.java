package com.smile.acelib.external;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Vault legacy API 的反射呼叫收斂點（package-private）。
 *
 * <p>方法／欄位名稱皆為字串常數；呼叫前 {@code setAccessible(true)} 以相容
 * 非公開測試替身（Vault 正式類別皆為公開，呼叫本身不受影響）。
 * 反射失敗以例外呈現，由呼叫端轉為明確失敗結果（不吞錯、不默認成功）。</p>
 */
final class VaultEconomyReflection {

    private VaultEconomyReflection() {
        // utility class
    }

    /**
     * 反射呼叫具名方法。
     *
     * @param target 呼叫對象；不可為 null
     * @param name 方法名；不可為 null
     * @param parameterTypes 參數型別；不可為 null
     * @param args 引數
     * @return 方法回傳值
     * @throws Exception 方法不存在、不可存取或呼叫拋例外（目標例外以 cause 攜帶）
     */
    static Object invoke(Object target, String name, Class<?>[] parameterTypes,
            Object... args) throws Exception {
        Method method = target.getClass().getMethod(name, parameterTypes);
        method.setAccessible(true);
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new IllegalStateException(
                "vault call threw: " + cause, cause);
        }
    }

    /**
     * 反射讀取 double 欄位。
     *
     * @param target 讀取對象；不可為 null
     * @param name 欄位名；不可為 null
     * @return 欄位值
     * @throws Exception 欄位不存在、不可存取或型別不符
     */
    static double readDoubleField(Object target, String name) throws Exception {
        Field field;
        try {
            field = target.getClass().getField(name);
        } catch (NoSuchFieldException e) {
            // Vault 正式回應的 amount／balance 為公開欄位；此回退只為相容
            // 可見度較低的測試替身，正式路徑不受影響。
            field = target.getClass().getDeclaredField(name);
        }
        field.setAccessible(true);
        Object value = field.get(target);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        throw new IllegalStateException(
            "vault field '" + name + "' is not numeric: " + value);
    }
}
