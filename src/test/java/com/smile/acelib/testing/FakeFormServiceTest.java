package com.smile.acelib.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.form.FormResponse;
import com.smile.acelib.form.FormResponseStatus;
import com.smile.acelib.form.FormSendResult;
import com.smile.acelib.form.FormSpec;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code FakeFormService} 專屬行為：過時／重複投遞、遞送被拒、傳輸缺席。
 *
 * <p>與 production 共通的語意由 {@code FormServiceContract} 覆蓋；本檔只鎖定
 * 假實作的投遞計數與編排開關。</p>
 */
@DisplayName("FakeFormService — 投遞編排")
class FakeFormServiceTest {

    private static FormSpec form() {
        return FormSpec.simple("Fake").content("Body").button("OK").build();
    }

    private static FormResponse valid() {
        return new FormResponse(FormResponseStatus.VALID, 0, List.of());
    }

    @Test
    @DisplayName("過時 token 投遞零執行並計為丟棄；重複投遞只生效一次")
    void staleAndDuplicateAreDropped() {
        FakeFormService service = new FakeFormService();
        AtomicInteger calls = new AtomicInteger();
        service.sendForm(UUID.randomUUID(), form(), response -> calls.incrementAndGet());
        UUID token = service.pendingTokens().get(0);

        assertTrue(service.deliverResponse(token, valid()));
        assertEquals(1, calls.get());
        assertEquals(1, service.deliveredCount());

        assertFalse(service.deliverResponse(token, valid()));
        assertFalse(service.deliverResponse(UUID.randomUUID(), valid()));
        assertEquals(1, calls.get());
        assertEquals(2, service.droppedCount());
        assertEquals(0, service.pendingCount());
    }

    @Test
    @DisplayName("deliverFirst 在唯一待回應時投遞；數量不為一時拒絕")
    void deliverFirst_requiresExactlyOne() {
        FakeFormService service = new FakeFormService();
        assertThrows(IllegalStateException.class, () -> service.deliverFirst(valid()));

        AtomicInteger calls = new AtomicInteger();
        service.sendForm(UUID.randomUUID(), form(), response -> calls.incrementAndGet());
        service.sendForm(UUID.randomUUID(), form(), response -> calls.incrementAndGet());
        assertThrows(IllegalStateException.class, () -> service.deliverFirst(valid()));
        assertEquals(0, calls.get());
    }

    @Test
    @DisplayName("rejectNextSend 只影響一次；關閉回應可重現關閉失敗語意外的正常關閉")
    void rejectNextSend_isOneShot() {
        FakeFormService service = new FakeFormService();
        AtomicInteger calls = new AtomicInteger();
        service.rejectNextSend();

        assertEquals(FormSendResult.REJECTED,
            service.sendForm(UUID.randomUUID(), form(), response -> calls.incrementAndGet()));
        assertEquals(0, service.pendingCount());

        assertEquals(FormSendResult.SENT,
            service.sendForm(UUID.randomUUID(), form(), response -> calls.incrementAndGet()));
        assertTrue(service.deliverFirst(
            new FormResponse(FormResponseStatus.CLOSED, null, List.of())));
        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("傳輸缺席時兩種 sendForm 都以 FORM-001 拒絕；恢復後正常")
    void absentTransport_rejectsBothSendForms() {
        FakeFormService service = new FakeFormService();
        service.setTransportAbsent(true);

        IllegalStateException first = assertThrows(IllegalStateException.class,
            () -> service.sendForm(UUID.randomUUID(), form()));
        assertTrue(first.getMessage().contains("ACELIB-FORM-001"));
        assertThrows(IllegalStateException.class,
            () -> service.sendForm(UUID.randomUUID(), form(), response -> {
            }));

        service.setTransportAbsent(false);
        assertEquals(FormSendResult.SENT, service.sendForm(UUID.randomUUID(), form()));
    }

    @Test
    @DisplayName("停用清空待回應；之後發送以 FORM-002 拒絕")
    void shutdown_clearsPending() {
        FakeFormService service = new FakeFormService();
        service.sendForm(UUID.randomUUID(), form(), response -> {
        });
        assertEquals(1, service.pendingCount());

        service.shutdown();
        assertEquals("FAILED", service.getModuleStatus());
        assertEquals(0, service.pendingCount());
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> service.sendForm(UUID.randomUUID(), form()));
        assertTrue(failure.getMessage().contains("ACELIB-FORM-002"));
    }
}
