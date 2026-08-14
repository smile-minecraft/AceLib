package com.example.acelibconsumer;

import com.smile.acelib.AceLibApi;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 下游 plugin 使用 AceLib 的 Quick Start 範例（正式 provider contract）。
 *
 * <p>重點：</p>
 * <ul>
 *   <li>不 import {@code com.smile.acelib.AceLibPlugin}、不做 unchecked cast；
 *       正式取得入口是 {@code AceLibApi.AceLibProvider}（Bukkit {@code ServicesManager} 註冊）。</li>
 *   <li>{@code depend: [AceLib]} 保證 AceLib 先於本 plugin 載入；但 runtime 仍須
 *       處理 provider missing / not-ready 兩種防禦（AceLib 可能尚未 enable 或已 disable）。</li>
 *   <li>{@code provider.api()} 永不回傳 null；disable 後 {@code isReady()} 為 false，
 *       呼叫端必須檢查後再使用。</li>
 * </ul>
 */
public class QuickStartPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        // 1. 經 ServicesManager 取得正式 provider（enable 後註冊、disable 時解除）。
        RegisteredServiceProvider<AceLibApi.AceLibProvider> registration =
            getServer().getServicesManager().getRegistration(AceLibApi.AceLibProvider.class);

        // 2. missing / not-ready：registration 為 null（AceLib 尚未 enable 或已 disable）。
        if (registration == null) {
            getLogger().warning("AceLib provider 未註冊（AceLib 尚未啟用？）；停用本 plugin。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 3. provider.api() 永不回傳 null；但 reload / disable 後可能不 ready。
        AceLibApi api = registration.getProvider().api();
        if (!api.isReady()) {
            getLogger().warning("AceLib 存在但尚未 ready；停用本 plugin 避免半初始化。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getLogger().info("AceLib " + api.getVersion()
            + " on " + api.getPlatform().getDisplayName());

        // 4. 依平台能力決定分支（Folia region 排程 / Paper 全域排程）。
        if (api.getPlatformCapability().regionScheduling()) {
            // Folia 環境：操作實體 / 方塊 / 玩家必須在 region thread 上執行，
            // 使用 AceLib 提供的安全排程 API（本範例僅示意，不實際排程）。
        } else if (api.getPlatformCapability().globalScheduler()) {
            // Paper 環境：可安全使用全域 BukkitScheduler。
        }
    }
}
