package com.smile.acelib.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletionStage;
import com.smile.acelib.platform.Platform;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link WorldBackend} 鄰近查詢簽章契約（二進位相容守衛）。
 *
 * <p>{@code findNearby} / {@code findNearbyPlayers} 維持 HEAD 原 {@code List}
 * 簽章：次版本只加不改，既有公開簽章、語意、例外型別都不動。裸 {@code List}
 * 無法表達拒絕，內建實作把失敗坍縮成空清單，需要錯誤碼的呼叫端請走
 * {@link WorldService} 結構化管道（服務經由內建 package-private result 橋接保留原始錯誤碼）。</p>
 *
 * <p>內建 bounded 查詢橋接（{@code queryNearby} / {@code queryNearbyPlayers}）維持
 * package-private，不新增公開 result SPI；本測試同時守住「無新增 public」。</p>
 */
@DisplayName("WorldBackend nearby 簽章契約")
class WorldBackendSignatureContractTest {

    private static Class<?> listPayloadOf(String name, Class<?>... params) throws Exception {
        Method m = WorldBackend.class.getMethod(name, params);
        assertEquals(List.class, m.getReturnType(),
            name + " 必須回 java.util.List 以維持 HEAD 二進位相容");
        ParameterizedType payload = (ParameterizedType) m.getGenericReturnType();
        assertEquals(List.class, payload.getRawType());
        return (Class<?>) payload.getActualTypeArguments()[0];
    }

    @Test
    @DisplayName("findNearby 回 List<Entity>（HEAD 原簽章）")
    void findNearbyReturnsListOfEntity() throws Exception {
        assertEquals(Entity.class, listPayloadOf("findNearby", Location.class, double.class, EntityType.class));
    }

    @Test
    @DisplayName("findNearbyPlayers 回 List<Player>（HEAD 原簽章）")
    void findNearbyPlayersReturnsListOfPlayer() throws Exception {
        assertEquals(Player.class, listPayloadOf("findNearbyPlayers", Location.class, double.class));
    }

    @Test
    @DisplayName("鄰近查詢簽章為裸 List，與 api-surface 基準一致")
    void nearbySignaturesAreBareLists() throws Exception {
        for (Method m : List.of(
            WorldBackend.class.getMethod("findNearby", Location.class, double.class, EntityType.class),
            WorldBackend.class.getMethod("findNearbyPlayers", Location.class, double.class))) {
            String expected = "java.util.List<"
                + (m.getName().equals("findNearby")
                    ? "org.bukkit.entity.Entity"
                    : "org.bukkit.entity.Player") + ">";
            assertEquals(expected, m.getGenericReturnType().getTypeName(),
                m.getName() + " 簽章必須與 api-surface-signatures.json 的基準一致");
        }
    }

    @Test
    @DisplayName("裸 List 僅限 legacy 鄰近查詢；其餘可失敗 SPI 仍走 Result")
    void onlyLegacyNearbyMayReturnBareList() {
        // resolve* 與 server() 是刻意回 null 的查詢；teleportAsync 以 CompletionStage 表達失敗；
        // 唯二例外是 legacy 鄰近查詢（List 無法表達拒絕，文件已誠實載明局限）。
        Set<String> allowedBare = Set.of(
            "resolveWorld", "resolveEntity", "resolvePlayer", "server", "teleportAsync",
            "findNearby", "findNearbyPlayers");
        List<String> unexpectedBare = new ArrayList<>();
        for (Method m : WorldBackend.class.getDeclaredMethods()) {
            if (m.isSynthetic() || allowedBare.contains(m.getName())) {
                continue;
            }
            if (m.getReturnType() != WorldBackendResult.class
                && m.getReturnType() != CompletionStage.class) {
                unexpectedBare.add(m.getName() + " : " + m.getReturnType().getSimpleName());
            }
        }
        assertTrue(unexpectedBare.isEmpty(),
            "除 legacy 鄰近查詢外，可失敗 SPI 不得以裸型別回傳：" + unexpectedBare);
    }

    @Test
    @DisplayName("WorldBackend 無新增公開方法（僅還原兩 List）")
    void worldBackendHasNoNewPublicMethods() {
        Set<String> actual = new TreeSet<>();
        for (Method m : WorldBackend.class.getDeclaredMethods()) {
            if (m.isSynthetic()) {
                continue;
            }
            actual.add(m.getName() + "(" + paramSig(m) + "):" + m.getReturnType().getName());
        }
        Set<String> expected = Set.of(
            "resolveWorld(java.util.UUID):org.bukkit.World",
            "resolveEntity(java.util.UUID):org.bukkit.entity.Entity",
            "resolvePlayer(java.util.UUID):org.bukkit.entity.Player",
            "readBlockAt(org.bukkit.Location):com.smile.acelib.world.WorldBackendResult",
            "writeBlockAt(org.bukkit.Location,java.lang.String):com.smile.acelib.world.WorldBackendResult",
            "spawnAt(org.bukkit.Location,java.lang.String):com.smile.acelib.world.WorldBackendResult",
            "removeEntity(org.bukkit.entity.Entity):com.smile.acelib.world.WorldBackendResult",
            "playEffect(org.bukkit.Location,java.lang.String):com.smile.acelib.world.WorldBackendResult",
            "findNearby(org.bukkit.Location,double,org.bukkit.entity.EntityType):java.util.List",
            "findNearbyPlayers(org.bukkit.Location,double):java.util.List",
            "teleportAsync(org.bukkit.entity.Entity,org.bukkit.Location,boolean):java.util.concurrent.CompletionStage",
            "server():org.bukkit.Server");
        assertEquals(new TreeSet<>(expected), actual,
            "WorldBackend 公開方法必須與 HEAD 一致（僅還原兩 List，無新增 public）");
    }

    @Test
    @DisplayName("內建 result 橋接維持 package-private，不新增公開 SPI")
    void builtinResultBridgeStaysPackagePrivate() throws Exception {
        for (String name : List.of("queryNearby", "queryNearbyPlayers")) {
            Method found = null;
            for (Method m : BukkitWorldBackend.class.getDeclaredMethods()) {
                if (m.getName().equals(name)) {
                    found = m;
                    break;
                }
            }
            assertTrue(found != null, "內建應提供 " + name + " 橋接");
            int mod = found.getModifiers();
            assertFalse(Modifier.isPublic(mod), name + " 不得為 public（避免新增公開 SPI）");
            assertFalse(Modifier.isPrivate(mod), name + " 須為 package-private 以供同 package 服務呼叫");
            assertFalse(Modifier.isProtected(mod), name + " 不得為 protected（避免暴露內部）");
        }
        // legacy List 本身仍為 public（介面要求）
        assertTrue(Modifier.isPublic(
            BukkitWorldBackend.class.getMethod("findNearby",
                Location.class, double.class, EntityType.class).getModifiers()));
        assertTrue(Modifier.isPublic(
            BukkitWorldBackend.class.getMethod("findNearbyPlayers",
                Location.class, double.class).getModifiers()));
    }

    @Test
    @DisplayName("雙參數 ctor 維持 package-private；單參數 ctor 維持 public")
    void backendConstructorsStayMinimallyVisible() throws Exception {
        Constructor<?> explicit =
            BukkitWorldBackend.class.getDeclaredConstructor(Server.class, Platform.class);
        int explicitMod = explicit.getModifiers();
        assertFalse(Modifier.isPublic(explicitMod),
            "雙參數 ctor 僅為同 package 測試 seam，不得為 public");
        assertFalse(Modifier.isProtected(explicitMod),
            "雙參數 ctor 不得為 protected（避免暴露內部）");
        assertFalse(Modifier.isPrivate(explicitMod),
            "雙參數 ctor 須為 package-private 以供同 package 測試與服務呼叫");
        assertTrue(Modifier.isPublic(
            BukkitWorldBackend.class.getConstructor(Server.class).getModifiers()),
            "單參數 ctor 維持 public（HEAD 既有呼叫端經此建立）");
    }

    @Test
    @DisplayName("WorldBackendResult 工廠維持 package-private")
    void resultFactoriesStayPackagePrivate() throws Exception {
        for (String name : List.of("ok", "failed", "isOk", "value", "errorCode", "detail")) {
            boolean seen = false;
            for (Method m : WorldBackendResult.class.getDeclaredMethods()) {
                if (m.getName().equals(name)) {
                    seen = true;
                    assertFalse(Modifier.isPublic(m.getModifiers()),
                        "WorldBackendResult#" + name + " 不得為 public");
                }
            }
            assertTrue(seen, "應存在 WorldBackendResult#" + name);
        }
    }

    private static String paramSig(Method m) {
        StringBuilder sb = new StringBuilder();
        Class<?>[] ps = m.getParameterTypes();
        for (int i = 0; i < ps.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(ps[i].getName());
        }
        return sb.toString();
    }
}
