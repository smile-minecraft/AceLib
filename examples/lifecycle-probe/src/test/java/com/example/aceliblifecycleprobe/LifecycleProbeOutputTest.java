package com.example.aceliblifecycleprobe;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.smile.acelib.lifecycle.LifecycleHost;
import com.smile.acelib.lifecycle.LifecycleResult;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("lifecycle 探針輸出格式")
class LifecycleProbeOutputTest {

    @Test
    @DisplayName("reload 明確記錄 void 回傳契約與宿主結果")
    void reloadReportsReturnShapeAndLifecycleOutcome() {
        assertEquals("[lprobe] reload #2 return=void apiReady=true lifecycleStatus=READY "
                + "lastResult=SUCCESS/status=READY/modules=lifecycle-probe:probe/problems=none",
            LifecycleProbeOutput.reload(2, true, LifecycleHost.Status.READY,
                successResult()));
    }

    @Test
    @DisplayName("status 同時列出 API ready 與宿主狀態")
    void statusReportsReadinessAndLifecycleState() {
        assertEquals("[lprobe] status apiReady=false lifecycleStatus=SHUTDOWN "
                + "lastResult=SUCCESS/status=SHUTDOWN/modules=none/problems=none",
            LifecycleProbeOutput.status(false, LifecycleHost.Status.SHUTDOWN,
                successResult(LifecycleHost.Status.SHUTDOWN)));
    }

    @Test
    @DisplayName("模組啟用與關閉記錄字串維持可辨識")
    void lifecycleMessagesAreStable() {
        assertEquals("lprobe module enabled", LifecycleProbeOutput.MODULE_ENABLED);
        assertEquals("lprobe module closed", LifecycleProbeOutput.MODULE_CLOSED);
    }

    @Test
    @DisplayName("reload 失敗時輸出宿主錯誤代碼與訊息")
    void reloadReportsFailureDetails() {
        LifecycleResult failure = new LifecycleResult(LifecycleResult.Outcome.FAILED,
            LifecycleHost.Status.FAILED,
            List.of(new LifecycleResult.Problem(LifecycleResult.Code.RELOAD_FAILED, null,
                List.of("lifecycle-probe:probe"), "host reload failed")),
            List.of("lifecycle-probe:probe"));

        assertEquals("[lprobe] reload #1 return=void apiReady=false lifecycleStatus=FAILED "
                + "lastResult=FAILED/status=FAILED/modules=lifecycle-probe:probe/problems="
                + "ACELIB-LIFE-009=host reload failed",
            LifecycleProbeOutput.reload(1, false, LifecycleHost.Status.FAILED, failure));
    }

    private static LifecycleResult successResult() {
        return successResult(LifecycleHost.Status.READY);
    }

    private static LifecycleResult successResult(LifecycleHost.Status status) {
        return new LifecycleResult(LifecycleResult.Outcome.SUCCESS, status, List.of(),
            status == LifecycleHost.Status.READY ? List.of("lifecycle-probe:probe") : List.of());
    }
}
