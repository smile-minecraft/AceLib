package com.smile.acelib.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 外部業務門面的執行緒／上下文測試。
 *
 * <p>本門面不觸碰世界／實體狀態：同一呼叫在呼叫端執行緒（Paper 主執行緒語意）
 * 與非同步執行緒（Folia 非同步呼叫語意）回相同結果；公開 SPI／結果／門面原始碼
 * 不得引用世界／實體／方塊型別（靜態掃描證明）。</p>
 */
@DisplayName("外部業務門面 — 執行緒與上下文")
class ExternalFacadeThreadingTest {

    private static ExternalIntegrationServiceImpl serviceWithFakes() {
        ExternalIntegrationServiceImpl service =
            new ExternalIntegrationServiceImpl(new IntegrationRegistry());
        OfflinePlayer ignored = Mockito.mock(OfflinePlayer.class);
        service.setEconomyProvider(new EconomyProvider() {
            @Override
            public EconomyResult getBalance(OfflinePlayer player) {
                return EconomyResult.success(42.0, "ok");
            }

            @Override
            public EconomyResult withdraw(OfflinePlayer player, double amount) {
                return EconomyResult.success(42.0 - amount, "ok");
            }

            @Override
            public EconomyResult deposit(OfflinePlayer player, double amount) {
                return EconomyResult.success(42.0 + amount, "ok");
            }
        });
        service.setPermissionProvider(playerId -> PermissionResult.success("member",
            Set.of("member"), Map.of(), "ok"));
        service.setBuildCheckProvider(
            (playerId, world, x, y, z) -> BuildCheckResult.success(true, "ok"));
        return service;
    }

    @Nested
    @DisplayName("Paper：呼叫端執行緒直接呼叫")
    class OnCallingThread {

        @Test
        @DisplayName("同步呼叫回正確結果（不切換執行緒、不抛例外）")
        void syncCall_correct() {
            ExternalIntegrationServiceImpl service = serviceWithFakes();
            OfflinePlayer player = Mockito.mock(OfflinePlayer.class);
            UUID id = UUID.randomUUID();

            assertEquals(42.0, service.getBalance(player).balance());
            assertEquals("member", service.getPermissionGroups(id).primaryGroup());
            assertTrue(service.canBuild(id, "world", 1, 2, 3).allowed());
        }
    }

    @Nested
    @DisplayName("Folia：非同步執行緒呼叫同語意")
    class OnAsyncThread {

        @Test
        @DisplayName("非同步呼叫回相同結果（門面不綁定呼叫端執行緒）")
        void asyncCall_sameResult() throws Exception {
            ExternalIntegrationServiceImpl service = serviceWithFakes();
            OfflinePlayer player = Mockito.mock(OfflinePlayer.class);
            UUID id = UUID.randomUUID();

            CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() ->
                service.getBalance(player).isSuccess()
                    && service.getPermissionGroups(id).isSuccess()
                    && service.canBuild(id, "world", 1, 2, 3).allowed());
            assertTrue(future.get(10, TimeUnit.SECONDS));
        }
    }

    @Nested
    @DisplayName("靜態：公開型別不觸碰世界／實體狀態")
    class NoWorldAccess {

        private static final String[] FORBIDDEN = {
            "org.bukkit.entity.", "org.bukkit.block.", "org.bukkit.World",
            "org.bukkit.Location", "org.bukkit.Chunk", "RegionScheduler",
            "getWorld()", "getLocation()"
        };

        @Test
        @DisplayName("公開 SPI／結果／門面原始碼不引用世界／實體／方塊型別")
        void publicTypes_doNotTouchWorld() throws Exception {
            Path dir = projectRoot().resolve("src/main/java/com/smile/acelib/external");
            String[] publicFiles = {
                "ExternalIntegrationService.java", "ExternalResultState.java",
                "EconomyResult.java", "PermissionResult.java",
                "ExternalOperationResult.java", "BuildCheckResult.java",
                "EconomyProvider.java", "PermissionProvider.java",
                "PlaceholderProvider.java", "PlaceholderHandler.java",
                "BuildCheckProvider.java"
            };
            for (String file : publicFiles) {
                String source = Files.readString(dir.resolve(file));
                for (String forbidden : FORBIDDEN) {
                    assertFalse(source.contains(forbidden),
                        file + " 不得引用世界／實體狀態：" + forbidden);
                }
            }
        }

        private static Path projectRoot() {
            Path dir = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath();
            for (int i = 0; i < 12; i++) {
                if (Files.exists(dir.resolve("docs/reference/api-surface.json"))
                        || Files.exists(dir.resolve("build.gradle.kts"))) {
                    return dir;
                }
                Path parent = dir.getParent();
                if (parent == null) {
                    break;
                }
                dir = parent;
            }
            return Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath();
        }

        @Test
        @DisplayName("位置查詢只收 UUID＋世界名＋座標（不收 Location／World）")
        void buildCheckSignature_noWorldTypes() throws Exception {
            var method = ExternalIntegrationService.class.getMethod("canBuild",
                UUID.class, String.class, int.class, int.class, int.class);
            assertEquals(BuildCheckResult.class, method.getReturnType());
            for (var param : method.getParameterTypes()) {
                assertFalse(param.getName().startsWith("org.bukkit"),
                    "canBuild 不得收 Bukkit 世界型別：" + param.getName());
            }
        }
    }
}
