package com.smile.acelib.testing.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.form.FormResponse;
import com.smile.acelib.form.FormResponseStatus;
import com.smile.acelib.form.FormSendResult;
import com.smile.acelib.form.FormService;
import com.smile.acelib.form.FormSpec;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 表單服務契約（真實作與假實作共用）。
 *
 * <p>鎖定「現有」公開 {@link FormService} 語意：發送結果、回應最多執行一次、
 * 過時／重複投遞丟棄、拒絕與停用語意、傳輸層缺席。子類提供可操控傳輸的
 * harness（生產側用錄製 sender＋同步派送，假側用 {@code FakeFormService}）：</p>
 * <ul>
 *   <li>{@link Harness#service()} — 受測服務</li>
 *   <li>{@link Harness#requireSinglePending()} — 唯一的待回應 token</li>
 *   <li>{@link Harness#pendingCount()} — 目前待回應數</li>
 *   <li>{@link Harness#emit(UUID, FormResponse)} — 模擬傳輸層回呼</li>
 *   <li>{@link Harness#rejectNextSend()} — 下一次發送回 REJECTED</li>
 *   <li>{@link Harness#setTransportAbsent()} — 切為傳輸層缺席（FORM-001）</li>
 * </ul>
 *
 * @since 1.4.0
 */
@DisplayName("表單服務契約（真／假共用）")
public abstract class FormServiceContract {

    /** 可操控傳輸的測試 harness（每測試全新）。 */
    protected interface Harness {
        FormService service();

        UUID requireSinglePending();

        int pendingCount();

        void emit(UUID token, FormResponse response);

        void rejectNextSend();

        void setTransportAbsent();
    }

    /** 每個測試全新 harness。 */
    protected abstract Harness newHarness();

    private static FormSpec sampleForm() {
        return FormSpec.simple("Contract").content("Body").button("OK").build();
    }

    private static FormResponse validResponse() {
        return new FormResponse(FormResponseStatus.VALID, 0, List.of());
    }

    @Test
    @DisplayName("發送成功回 SENT；有效回應恰好交付一次")
    void send_deliversResponseExactlyOnce() {
        Harness harness = newHarness();
        UUID player = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<FormResponse> received = new AtomicReference<>();

        FormSendResult result = harness.service().sendForm(player, sampleForm(), response -> {
            calls.incrementAndGet();
            received.set(response);
        });
        assertEquals(FormSendResult.SENT, result);

        UUID token = harness.requireSinglePending();
        harness.emit(token, validResponse());
        assertEquals(1, calls.get());
        assertEquals(FormResponseStatus.VALID, received.get().status());
        assertEquals(0, harness.pendingCount(), "交付後不得殘留 pending");
    }

    @Test
    @DisplayName("重複投遞只生效一次；未知 token 投遞零執行")
    void duplicateAndUnknownEmits_areDropped() {
        Harness harness = newHarness();
        UUID player = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();

        harness.service().sendForm(player, sampleForm(), response -> calls.incrementAndGet());
        UUID token = harness.requireSinglePending();

        harness.emit(token, validResponse());
        harness.emit(token, validResponse());
        assertEquals(1, calls.get(), "重複回應只生效一次");

        harness.emit(UUID.randomUUID(), validResponse());
        assertEquals(1, calls.get(), "未知 token 回應零執行");
    }

    @Test
    @DisplayName("關閉回應（CLOSED）正常交付，狀態原樣傳遞")
    void closedResponse_isDelivered() {
        Harness harness = newHarness();
        AtomicReference<FormResponse> received = new AtomicReference<>();

        harness.service().sendForm(UUID.randomUUID(), sampleForm(), received::set);
        harness.emit(harness.requireSinglePending(),
            new FormResponse(FormResponseStatus.CLOSED, null, List.of()));

        assertEquals(FormResponseStatus.CLOSED, received.get().status());
    }

    @Test
    @DisplayName("遞送被拒時 consumer 永不呼叫且不留 pending")
    void rejectedSend_neverCallsConsumer() {
        Harness harness = newHarness();
        AtomicInteger calls = new AtomicInteger();
        harness.rejectNextSend();

        FormSendResult result = harness.service().sendForm(UUID.randomUUID(),
            sampleForm(), response -> calls.incrementAndGet());

        assertEquals(FormSendResult.REJECTED, result);
        assertEquals(0, calls.get());
        assertEquals(0, harness.pendingCount());
    }

    @Test
    @DisplayName("停用後發送以 FORM-002 拒絕；待回應清空")
    void shutdown_rejectsSendAndClearsPending() {
        Harness harness = newHarness();
        harness.service().sendForm(UUID.randomUUID(), sampleForm(), response -> {
        });
        assertEquals(1, harness.pendingCount());

        harness.service().shutdown();
        assertEquals(0, harness.pendingCount());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> harness.service().sendForm(UUID.randomUUID(), sampleForm()));
        assertTrue(failure.getMessage().contains("ACELIB-FORM-002"),
            "停用後發送必須攜帶 ACELIB-FORM-002，實際: " + failure.getMessage());
    }

    @Test
    @DisplayName("傳輸層缺席時發送以 FORM-001 拒絕")
    void absentTransport_rejectsWithForm001() {
        Harness harness = newHarness();
        harness.setTransportAbsent();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> harness.service().sendForm(UUID.randomUUID(), sampleForm()));
        assertTrue(failure.getMessage().contains("ACELIB-FORM-001"),
            "缺席傳輸層必須攜帶 ACELIB-FORM-001，實際: " + failure.getMessage());
    }

    @Test
    @DisplayName("邊界：null 輸入一律拋 IllegalArgumentException")
    void nullInputs_throw() {
        Harness harness = newHarness();
        UUID player = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,
            () -> harness.service().sendForm(null, sampleForm()));
        assertThrows(IllegalArgumentException.class,
            () -> harness.service().sendForm(player, null));
        assertThrows(IllegalArgumentException.class,
            () -> harness.service().sendForm(null, sampleForm(), response -> {
            }));
        assertThrows(IllegalArgumentException.class,
            () -> harness.service().sendForm(player, null, response -> {
            }));
        assertThrows(IllegalArgumentException.class,
            () -> harness.service().sendForm(player, sampleForm(), null));
    }
}
