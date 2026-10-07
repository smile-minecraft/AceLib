package com.example.acelibextprobe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.external.BuildCheckResult;
import com.smile.acelib.external.EconomyResult;
import com.smile.acelib.external.ExternalIntegrationService;
import com.smile.acelib.external.ExternalOperationResult;
import com.smile.acelib.external.ExternalResultState;
import com.smile.acelib.external.IntegrationProbeResult;
import com.smile.acelib.external.IntegrationStatus;
import com.smile.acelib.external.PermissionResult;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 探針輸出格式契約測試。
 *
 * <p>探針輸出是主代理實機驗收的比對依據，因此格式本身就是契約：成功列攜帶
 * 數值、非成功列攜帶狀態＋錯誤碼＋訊息、缺席時明確標示而非靜默跳過。</p>
 */
@DisplayName("外部整合探針輸出格式")
class ProbeReportTest {

    @Test
    @DisplayName("成功列攜帶數值，失敗列攜帶狀態與錯誤碼")
    void resultLinesCarryValuesOrErrorCodes() {
        assertEquals("[economy] SUCCESS balance=100.5",
            ExternalIntegrationProbePlugin.formatEconomy(
                EconomyResult.success(100.5, "ok")));
        assertEquals("[permission] SUCCESS primary=admin groups=admin,default",
            ExternalIntegrationProbePlugin.formatPermission(
                PermissionResult.success("admin", Set.of("admin", "default"),
                    Map.of(), "ok")));
        assertEquals("[build] SUCCESS allowed=true",
            ExternalIntegrationProbePlugin.formatBuild(
                BuildCheckResult.success(true, "ok")));

        String unavailable = ExternalIntegrationProbePlugin.formatEconomy(
            EconomyResult.failure(ExternalResultState.UNAVAILABLE,
                "ACELIB-EXT-008", "vault economy is not available"));
        assertTrue(unavailable.startsWith("[economy] UNAVAILABLE code=ACELIB-EXT-008"),
            "非成功列必須攜帶錯誤碼，實際：" + unavailable);
    }

    @Test
    @DisplayName("狀態列匯總三個整合與模組聚合")
    void statusLineSummarizesIntegrations() {
        ExternalIntegrationService service = stubService();
        String line = ExternalIntegrationProbePlugin.formatStatus(service);
        assertTrue(line.contains("module=AVAILABLE"), line);
        assertTrue(line.contains("vault=AVAILABLE"), line);
        assertTrue(line.contains("luckperms=INIT_FAILED"), line);
        assertTrue(line.contains("placeholderapi=INIT_FAILED"), line);
    }

    @Test
    @DisplayName("佔位符往返：註冊成功且清理成功時標示無殘留")
    void placeholderCycleReportsNoResidue() {
        String line = ExternalIntegrationProbePlugin.formatPlaceholderCycle(stubService());
        assertTrue(line.contains("register SUCCESS"), line);
        assertTrue(line.contains("無殘留"), line);
    }

    @Test
    @DisplayName("console 執行（無玩家對象）時玩家門面明確標示跳過原因")
    void consoleRunSkipsPlayerFacadesExplicitly() {
        List<String> lines =
            ExternalIntegrationProbePlugin.probe(stubService(), null);
        assertEquals(5, lines.size());
        assertTrue(lines.get(1).contains("console"), lines.get(1));
        assertTrue(lines.get(2).contains("console"), lines.get(2));
        assertTrue(lines.get(4).contains("在線玩家"), lines.get(4));
    }

    /**
     * 以動態代理 stub 整個門面（探針測試不依賴伺服器）。
     *
     * @return vault 可用、其餘缺席的門面 stub
     */
    private static ExternalIntegrationService stubService() {
        return (ExternalIntegrationService) Proxy.newProxyInstance(
            ProbeReportTest.class.getClassLoader(),
            new Class<?>[]{ExternalIntegrationService.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getModuleStatus" -> "AVAILABLE";
                case "getStatus" -> "vault".equals(args[0])
                    ? IntegrationProbeResult.of(IntegrationStatus.AVAILABLE, "ok")
                    : IntegrationProbeResult.of(IntegrationStatus.INIT_FAILED, "absent");
                case "registerPlaceholder", "unregisterPlaceholder" ->
                    ExternalOperationResult.success("ok");
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }
}
