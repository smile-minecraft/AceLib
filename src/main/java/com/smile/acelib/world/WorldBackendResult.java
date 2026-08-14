package com.smile.acelib.world;

import java.util.Objects;

/**
 * 低階 adapter 結果：成功時攜帶 value，失敗時攜帶錯誤代碼。
 *
 * <p>設計輕量；facade 層會把此結果轉成 {@link WorldResult} 對外型別。</p>
 *
 * @param <T> value 型別
 * @since 1.0.0
 */
public final class WorldBackendResult<T> {

    private final boolean ok;
    private final T value;
    private final String errorCode;
    private final String detail;

    private WorldBackendResult(boolean ok, T value, String errorCode, String detail) {
        this.ok = ok;
        this.value = value;
        this.errorCode = errorCode;
        this.detail = Objects.requireNonNull(detail, "detail");
    }

    static <T> WorldBackendResult<T> ok(T value, String detail) {
        return new WorldBackendResult<>(true, value, null, detail);
    }

    static <T> WorldBackendResult<T> failed(String errorCode, String detail) {
        return new WorldBackendResult<>(false, null, errorCode, detail);
    }

    boolean isOk() {
        return ok;
    }

    T value() {
        return value;
    }

    String errorCode() {
        return errorCode;
    }

    String detail() {
        return detail;
    }
}
