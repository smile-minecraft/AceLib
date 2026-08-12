package com.smile.acelib.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * GuiPage 不可變分頁 model 單元測試（Phase 11 延伸第一切片）。
 *
 * <p>對應 Evidence Pack §5 TDD：先以純單元測試鎖定分頁邊界、空資料 fallback、
 * 不可變 collection 與「session generation 不因 page 狀態污染」契約，再執行取得 Red。</p>
 */
@DisplayName("GuiPage")
class GuiPageTest {

    private List<String> range(int n) {
        List<String> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add("item-" + i);
        }
        return list;
    }

    // -----------------------------------------------------------------
    // 正常頁 / 分頁邊界
    // -----------------------------------------------------------------

    @Test
    @DisplayName("page 對正常中間頁回傳正確 pageIndex / totalPages / items")
    void page_normalMiddlePage() {
        List<String> all = range(25); // 每頁 10 → 3 頁
        GuiPage<String> page = GuiPage.page(all, 10, 1);

        assertTrue(page.isContent());
        assertEquals(GuiPage.Kind.CONTENT, page.kind());
        assertEquals(1, page.pageIndex());
        assertEquals(3, page.totalPages());
        assertEquals(10, page.items().size());
        assertEquals("item-10", page.items().get(0));
        assertEquals("item-19", page.items().get(9));
    }

    @Test
    @DisplayName("page 對第一頁（requestedPage=0）回傳第一頁內容")
    void page_firstPage() {
        List<String> all = range(25);
        GuiPage<String> page = GuiPage.page(all, 10, 0);

        assertEquals(0, page.pageIndex());
        assertEquals(3, page.totalPages());
        assertEquals("item-0", page.items().get(0));
        assertEquals("item-9", page.items().get(9));
    }

    @Test
    @DisplayName("page 對最後一頁（requestedPage=totalPages-1）回傳最後一頁內容")
    void page_lastPage() {
        List<String> all = range(25);
        GuiPage<String> page = GuiPage.page(all, 10, 2);

        assertEquals(2, page.pageIndex());
        assertEquals(3, page.totalPages());
        assertEquals(5, page.items().size());
        assertEquals("item-20", page.items().get(0));
        assertEquals("item-24", page.items().get(4));
    }

    @Test
    @DisplayName("page 對負數頁碼 clamp 到第一頁（不越界、不丟例外）")
    void page_negativeRequested_clampedToFirst() {
        List<String> all = range(25);
        GuiPage<String> page = GuiPage.page(all, 10, -3);

        assertEquals(0, page.pageIndex(), "負數頁碼必須 clamp 到第一頁");
        assertEquals(3, page.totalPages());
        assertEquals("item-0", page.items().get(0));
    }

    @Test
    @DisplayName("page 對超過上限頁碼 clamp 到最後一頁（不越界、不丟例外）")
    void page_overLimitRequested_clampedToLast() {
        List<String> all = range(25);
        GuiPage<String> page = GuiPage.page(all, 10, 99);

        assertEquals(2, page.pageIndex(), "超過上限頁碼必須 clamp 到最後一頁");
        assertEquals(3, page.totalPages());
        assertEquals(5, page.items().size());
    }

    @Test
    @DisplayName("page 對恰好整除的資料源計算正確 totalPages")
    void page_exactDivision() {
        List<String> all = range(20); // 每頁 10 → 2 頁
        GuiPage<String> page = GuiPage.page(all, 10, 1);

        assertEquals(2, page.totalPages());
        assertEquals(1, page.pageIndex());
        assertEquals(10, page.items().size());
        assertEquals("item-10", page.items().get(0));
    }

    // -----------------------------------------------------------------
    // 空資料 fallback
    // -----------------------------------------------------------------

    @Test
    @DisplayName("page 對空資料源回傳 EMPTY fallback（不丟例外）")
    void page_emptySource_returnsEmptyFallback() {
        GuiPage<String> page = GuiPage.page(List.of(), 10, 0);

        assertTrue(page.isEmpty());
        assertFalse(page.isContent());
        assertEquals(GuiPage.Kind.EMPTY, page.kind());
        assertEquals(0, page.totalPages());
        assertTrue(page.items().isEmpty());
    }

    @Test
    @DisplayName("empty() 工廠建立空資料 fallback 頁")
    void emptyFactory() {
        GuiPage<String> page = GuiPage.empty();
        assertTrue(page.isEmpty());
        assertEquals(0, page.pageIndex());
        assertEquals(0, page.totalPages());
        assertTrue(page.items().isEmpty());
    }

    // -----------------------------------------------------------------
    // loading / error fallback
    // -----------------------------------------------------------------

    @Test
    @DisplayName("loading() 工廠建立載入中 fallback 頁")
    void loadingFactory() {
        GuiPage<String> page = GuiPage.loading();
        assertTrue(page.isLoading());
        assertEquals(GuiPage.Kind.LOADING, page.kind());
        assertTrue(page.items().isEmpty());
    }

    @Test
    @DisplayName("error() 工廠建立錯誤 fallback 頁並攜帶 ACELIB-GUI-* 代碼")
    void errorFactory_withAcelibGuiCode() {
        GuiPage<String> page = GuiPage.error(GuiErrorCode.OPERATION_FAILED, "load failed");

        assertTrue(page.isError());
        assertEquals(GuiPage.Kind.ERROR, page.kind());
        assertEquals(GuiErrorCode.OPERATION_FAILED, page.errorCode());
        assertEquals("load failed", page.detail());
        assertTrue(page.items().isEmpty());
    }

    @Test
    @DisplayName("error() 拒絕非 ACELIB-GUI-* 錯誤代碼")
    void errorFactory_rejectsNonAcelibGuiCode() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> GuiPage.error("SOME-OTHER-CODE", "bad"));
        assertTrue(ex.getMessage().contains(GuiErrorCode.INVALID_INPUT),
            "例外必須含 ACELIB-GUI-007；實際: " + ex.getMessage());
    }

    @Test
    @DisplayName("error() 對 null 錯誤代碼丟 NullPointerException")
    void errorFactory_nullCode_rejected() {
        assertThrows(NullPointerException.class, () -> GuiPage.error(null, "detail"));
    }

    // -----------------------------------------------------------------
    // content() 防禦性驗證
    // -----------------------------------------------------------------

    @Test
    @DisplayName("content() 對 totalPages<=0 丟 IllegalArgumentException")
    void content_invalidTotalPages_rejected() {
        assertThrows(IllegalArgumentException.class,
            () -> GuiPage.content(0, 0, List.of("a")));
    }

    @Test
    @DisplayName("content() 對 pageIndex 越界丟 IllegalArgumentException")
    void content_outOfBoundsPageIndex_rejected() {
        assertThrows(IllegalArgumentException.class,
            () -> GuiPage.content(5, 3, List.of("a")));
    }

    // -----------------------------------------------------------------
    // 不可變 collection
    // -----------------------------------------------------------------

    @Test
    @DisplayName("items() 回傳不可變 List：修改會收到 UnsupportedOperationException")
    void items_isImmutable() {
        List<String> all = range(25);
        GuiPage<String> page = GuiPage.page(all, 10, 0);

        assertThrows(UnsupportedOperationException.class,
            () -> page.items().add("mutated"));
        assertThrows(UnsupportedOperationException.class,
            () -> page.items().clear());
    }

    @Test
    @DisplayName("page 不保留來源 List 的 mutable 參考：來源後續修改不影響 page items")
    void page_doesNotRetainSourceMutability() {
        List<String> source = new ArrayList<>(range(25));
        GuiPage<String> page = GuiPage.page(source, 10, 0);

        // 修改來源不應影響已建立的 page（證明做了防禦性 copy）
        source.add("injected-later");
        source.set(0, "mutated-first");

        assertEquals(10, page.items().size());
        assertEquals("item-0", page.items().get(0));
    }

    // -----------------------------------------------------------------
    // session generation 不因 page 狀態污染
    // -----------------------------------------------------------------

    @Test
    @DisplayName("建構多個 GuiPage 不影響 GuiSessionRegistry 的 session generation")
    void pageDoesNotPolluteSessionGeneration() {
        GuiSessionRegistry registry = new GuiSessionRegistry();
        UUID uuid = UUID.randomUUID();

        GuiSession session = registry.startSession(uuid, "owner", 9, java.util.Set.of());
        long generationBefore = session.generation();

        // 大量建構各種 page（含越界、空、loading、error）
        GuiPage<String> p1 = GuiPage.page(range(25), 10, -1);
        GuiPage<String> p2 = GuiPage.page(range(25), 10, 100);
        GuiPage<String> p3 = GuiPage.page(List.of(), 10, 0);
        GuiPage<String> p4 = GuiPage.loading();
        GuiPage<String> p5 = GuiPage.error(GuiErrorCode.OPERATION_FAILED, "x");

        // registry 仍只有原本那一個 session，generation 不變
        assertEquals(1, registry.size());
        GuiSession same = registry.getSession(uuid);
        assertSame(session, same, "session 物件必須保持同一個");
        assertEquals(generationBefore, same.generation(),
            "建構 page 不得改變 session generation");
        assertFalse(p1.isError());
        assertFalse(p2.isError());
        assertTrue(p3.isEmpty());
        assertTrue(p4.isLoading());
        p5.isError();
    }

    @Test
    @DisplayName("GuiPage 與 GuiSession 完全獨立：page 不要求任何 session 即可建立")
    void pageIndependentOfSession() {
        // 不建立任何 registry / session，直接建構 page
        GuiPage<String> page = GuiPage.page(range(5), 2, 0);
        assertTrue(page.isContent());
        assertEquals(3, page.totalPages());
        assertEquals(2, page.items().size());
    }
}
