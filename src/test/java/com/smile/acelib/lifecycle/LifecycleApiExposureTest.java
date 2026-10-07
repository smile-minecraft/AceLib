package com.smile.acelib.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.smile.acelib.AceLibApi;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 確認生命周期宿主只經 AceLib 公開 facade 暴露。 */
@DisplayName("生命週期宿主公開入口")
class LifecycleApiExposureTest {

    @Test
    @DisplayName("AceLibApi 提供永不為 null 的 lifecycle host facade")
    void apiExposesNonNullLifecycleHost() throws ReflectiveOperationException {
        Method accessor = AceLibApi.class.getMethod("getLifecycleHost");
        assertEquals("LifecycleHost", accessor.getReturnType().getSimpleName());

        Object host = accessor.invoke(AceLibApi.uninitialized());
        assertNotNull(host);
        Object status = accessor.getReturnType().getMethod("status").invoke(host);
        assertEquals("NOT_READY", status.toString(),
            "未啟用 facade 的 lifecycle host 必須明確回報 NOT_READY");
    }
}
