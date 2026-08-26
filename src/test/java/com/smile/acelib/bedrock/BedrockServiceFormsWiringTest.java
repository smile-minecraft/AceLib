package com.smile.acelib.bedrock;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.smile.acelib.form.FormService;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BedrockService 與 FormService 的接線測試：production factory 可注入明確的
 * 表單服務實例，forms() 必須回傳同一實例；單參數 overload 維持既有行為
 * （forms() 非 null 且可用）。
 */
@DisplayName("BedrockService forms() 接線")
class BedrockServiceFormsWiringTest {

    @Test
    @DisplayName("forProduction(lookup, formService)：forms() 回傳注入的同一實例")
    void forProduction_withExplicitFormService_formsReturnsSameInstance() {
        FormService formService = FormService.forProduction(FormService.FormSender.absent());
        BedrockService service = BedrockService.forProduction(
            BedrockService.PlayerLookup.absent(), formService);

        assertSame(formService, service.forms(),
            "forms() 必須回傳建構時注入的表單服務實例");
    }

    @Test
    @DisplayName("forProduction(lookup) 單參數 overload：forms() 仍非 null（既有行為不變）")
    void forProduction_singleArg_formsStillAvailable() {
        BedrockService service = BedrockService.forProduction(
            BedrockService.PlayerLookup.absent());

        assertNotNull(service.forms(), "單參數 overload 的 forms() 不得為 null");
    }

    @Test
    @DisplayName("注入的表單服務 shutdown 後，經 bedrockService.forms() 取得者同步呈現停用語意")
    void injectedFormService_shutdownReflectedThroughBedrockService() {
        FormService formService = FormService.forProduction(FormService.FormSender.absent());
        BedrockService service = BedrockService.forProduction(
            BedrockService.PlayerLookup.absent(), formService);

        formService.shutdown();

        assertNotNull(service.forms(), "bedrockService 未 shutdown 時 forms() 仍可取得");
        UUID playerId = UUID.fromString("00000000-0000-0000-0000-000000000005");
        var spec = com.smile.acelib.form.FormSpec.simple("t").content("c").button("b").build();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
            () -> service.forms().sendForm(playerId, spec),
            "已 shutdown 的表單服務必須拒絕發送");
    }
}
