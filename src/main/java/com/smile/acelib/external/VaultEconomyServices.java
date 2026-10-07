package com.smile.acelib.external;

import org.bukkit.Bukkit;

/**
 * Bukkit {@code ServicesManager} 的 Vault {@code Economy} 反射解析器
 *（package-private）。
 *
 * <p>以字串 FQCN 載入 {@code net.milkbowl.vault.economy.Economy}（不觸發外部類別
 * 初始化以外的載入皆在此處收斂），再經 {@code ServicesManager#getRegistration}
 * 取得當前註冊；任何環節缺席或異常皆回 null（由呼叫端轉為 {@code UNAVAILABLE}，
 * 不拋例外、不快取）。</p>
 */
final class VaultEconomyServices {

    private VaultEconomyServices() {
        // utility class
    }

    /**
     * 解析當前 Vault {@code Economy} 實例。
     *
     * <p>每次呼叫重新解析，不快取：先以字串 FQCN 載入
     * {@code net.milkbowl.vault.economy.Economy}（Vault 缺席時
     * {@code ClassNotFoundException} 即回 null，不觸發外部類別初始化），
     * 再經 {@code ServicesManager#getRegistration} 取得當前註冊。
     * 任何環節缺席或異常皆回 null（由呼叫端轉為 {@code UNAVAILABLE}）。</p>
     *
     * @return {@code Economy} 實例；缺席或解析失敗時為 null
     */
    static Object resolve() {
        try {
            Class<?> economyClass = Class.forName(
                VaultEconomyProvider.ECONOMY_FQCN, false,
                VaultEconomyServices.class.getClassLoader());
            org.bukkit.plugin.RegisteredServiceProvider<?> registration =
                Bukkit.getServicesManager().getRegistration(
                    castEconomyClass(economyClass));
            if (registration == null) {
                return null;
            }
            return registration.getProvider();
        } catch (ClassNotFoundException | IllegalStateException e) {
            // Vault 未安裝（marker 不在 classpath）或伺服器尚未就緒：缺席。
            return null;
        } catch (LinkageError | RuntimeException e) {
            // 探測被拒、傳遞依賴缺失或 ServicesManager 異常：保守視為缺席，
            // 由呼叫端回不可用（不逃逸崩潰呼叫端執行緒）。
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Class<Object> castEconomyClass(Class<?> economyClass) {
        return (Class<Object>) economyClass;
    }
}
