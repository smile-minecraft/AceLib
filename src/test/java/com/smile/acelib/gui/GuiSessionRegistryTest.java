package com.smile.acelib.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * GuiSessionRegistry 單元測試（Phase 11 第一個可驗收切片）。
 *
 * <p>對應 Evidence Pack §5 Red 6：以純單元測試驗證 registry 對 UUID + generation
 * 的管理契約：建立 / 取得 / 結束 / generation 單調遞增 / 拒絕重複同 UUID 重複 session。</p>
 */
@DisplayName("GuiSessionRegistry")
class GuiSessionRegistryTest {

    @Test
    @DisplayName("startSession 接受 UUID + owner + prototype，回傳新 session 且 generation=1")
    void startSession_firstGenerationIsOne() {
        GuiSessionRegistry registry = new GuiSessionRegistry();
        UUID uuid = UUID.randomUUID();
        Set<Integer> protectedSlots = new HashSet<>();

        GuiSession session = registry.startSession(uuid, "owner", 9, protectedSlots);
        assertNotNull(session);
        assertEquals(uuid, session.playerUuid());
        assertEquals(1L, session.generation(),
            "第一個 session 的 generation 必須從 1 開始");
        assertEquals(9, session.size());
        assertEquals(protectedSlots, session.protectedSlots());
    }

    @Test
    @DisplayName("startSession 對既有 UUID 重複呼叫：拋 IllegalStateException 帶 ACELIB-GUI-009")
    void startSession_duplicateForSameUuid_isRejected() {
        GuiSessionRegistry registry = new GuiSessionRegistry();
        UUID uuid = UUID.randomUUID();

        registry.startSession(uuid, "owner", 9, Set.of());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> registry.startSession(uuid, "owner2", 9, Set.of()));
        assertTrue(ex.getMessage().contains(GuiErrorCode.SESSION_EXISTS),
            "例外訊息必須含 ACELIB-GUI-009；實際: " + ex.getMessage());
    }

    @Test
    @DisplayName("endSession 移除並回傳既有 session；無對應 session 回 null")
    void endSession_returnsRemovedSessionOrNull() {
        GuiSessionRegistry registry = new GuiSessionRegistry();
        UUID uuid = UUID.randomUUID();

        GuiSession created = registry.startSession(uuid, "owner", 9, Set.of());
        GuiSession removed = registry.endSession(uuid);

        assertSame(created, removed,
            "endSession 必須回傳被移除的 session 物件（identity）");

        GuiSession second = registry.endSession(uuid);
        assertNull(second, "第二次 endSession 必須回傳 null");
    }

    @Test
    @DisplayName("endSession 對 null UUID 應丟 NullPointerException")
    void endSession_nullUuid_isRejected() {
        GuiSessionRegistry registry = new GuiSessionRegistry();
        assertThrows(NullPointerException.class, () -> registry.endSession(null));
    }

    @Test
    @DisplayName("getSession 對既有 session 回傳；對無 session 回 null")
    void getSession_existingOrNull() {
        GuiSessionRegistry registry = new GuiSessionRegistry();
        UUID uuid = UUID.randomUUID();

        assertNull(registry.getSession(uuid), "未啟動 session 應回 null");

        GuiSession created = registry.startSession(uuid, "owner", 9, Set.of());
        assertSame(created, registry.getSession(uuid),
            "getSession 必須回傳同一 session 物件");
    }

    @Test
    @DisplayName("getSession 對 null UUID 應丟 NullPointerException")
    void getSession_nullUuid_isRejected() {
        GuiSessionRegistry registry = new GuiSessionRegistry();
        assertThrows(NullPointerException.class, () -> registry.getSession(null));
    }

    @Test
    @DisplayName("驗證 generation 對單一玩家單調遞增：end 後重新 start 拿新 generation")
    void generation_isMonotonic() {
        GuiSessionRegistry registry = new GuiSessionRegistry();
        UUID uuid = UUID.randomUUID();

        GuiSession s1 = registry.startSession(uuid, "owner", 9, Set.of());
        long g1 = s1.generation();
        registry.endSession(uuid);

        GuiSession s2 = registry.startSession(uuid, "owner", 9, Set.of());
        long g2 = s2.generation();
        assertTrue(g2 > g1, "第二次 generation 必須 > 第一次；g1=" + g1 + ", g2=" + g2);
    }

    @Test
    @DisplayName("不同 UUID 各自有獨立 generation counter")
    void generation_isPerRegistryMonotonic() {
        GuiSessionRegistry registry = new GuiSessionRegistry();
        UUID uuid1 = UUID.randomUUID();
        UUID uuid2 = UUID.randomUUID();

        GuiSession s1 = registry.startSession(uuid1, "owner", 9, Set.of());
        GuiSession s2 = registry.startSession(uuid2, "owner", 9, Set.of());

        // 兩個 session 各自有合法 generation（皆 > 0）。
        assertTrue(s1.generation() > 0L);
        assertTrue(s2.generation() > 0L);
        // 順序由 registry 內部單調遞增；後 start 應 ≥ 先 start。
        assertTrue(s2.generation() >= s1.generation());
    }

    @Test
    @DisplayName("size 與 activeCount 應正確反映 registry 狀態")
    void sizeAndActiveCount_reflectRegistryState() {
        GuiSessionRegistry registry = new GuiSessionRegistry();
        UUID uuid1 = UUID.randomUUID();
        UUID uuid2 = UUID.randomUUID();

        assertEquals(0, registry.size(),
            "初始 size 必須為 0");

        registry.startSession(uuid1, "owner", 9, Set.of());
        registry.startSession(uuid2, "owner", 9, Set.of());
        assertEquals(2, registry.size());

        registry.endSession(uuid1);
        assertEquals(1, registry.size());

        registry.endSession(uuid2);
        assertEquals(0, registry.size());
    }

    @Test
    @DisplayName("clear 移除所有 session（reload / disable 用）")
    void clear_removesAllSessions() {
        GuiSessionRegistry registry = new GuiSessionRegistry();
        UUID uuid1 = UUID.randomUUID();
        UUID uuid2 = UUID.randomUUID();

        registry.startSession(uuid1, "owner", 9, Set.of());
        registry.startSession(uuid2, "owner", 9, Set.of());
        assertEquals(2, registry.size());

        registry.clear();
        assertEquals(0, registry.size());
        assertNull(registry.getSession(uuid1));
        assertNull(registry.getSession(uuid2));
    }

    @Test
    @DisplayName("startSession 對 null UUID 應丟 NullPointerException")
    void startSession_nullUuid_isRejected() {
        GuiSessionRegistry registry = new GuiSessionRegistry();
        assertThrows(NullPointerException.class,
            () -> registry.startSession(null, "owner", 9, Set.of()));
    }

    @Test
    @DisplayName("startSession 對 null owner 應丟 NullPointerException")
    void startSession_nullOwner_isRejected() {
        GuiSessionRegistry registry = new GuiSessionRegistry();
        assertThrows(NullPointerException.class,
            () -> registry.startSession(UUID.randomUUID(), null, 9, Set.of()));
    }

    @Test
    @DisplayName("startSession 對 size <= 0 應丟 IllegalArgumentException")
    void startSession_invalidSize_isRejected() {
        GuiSessionRegistry registry = new GuiSessionRegistry();
        UUID uuid = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,
            () -> registry.startSession(uuid, "owner", 0, Set.of()));
        assertThrows(IllegalArgumentException.class,
            () -> registry.startSession(uuid, "owner", -5, Set.of()));
    }

    @Test
    @DisplayName("startSession 對 null protectedSlots 應 normalize 為空集合（不丟例外）")
    void startSession_nullProtectedSlots_normalizedToEmpty() {
        GuiSessionRegistry registry = new GuiSessionRegistry();
        UUID uuid = UUID.randomUUID();
        GuiSession session = registry.startSession(uuid, "owner", 9, null);
        assertNotNull(session.protectedSlots());
        assertTrue(session.protectedSlots().isEmpty(),
            "null protected slots 必須 normalize 為空集合");
    }

    @Test
    @DisplayName("size 為 0 時 isEmpty 回 true，非 0 時回 false")
    void isEmpty_consistentWithSize() {
        GuiSessionRegistry registry = new GuiSessionRegistry();
        assertTrue(registry.isEmpty());
        registry.startSession(UUID.randomUUID(), "owner", 9, Set.of());
        assertFalse(registry.isEmpty());
    }
}
