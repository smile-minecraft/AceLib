package com.example.acelibextprobe;

import com.smile.acelib.AceLibApi;
import com.smile.acelib.external.BuildCheckResult;
import com.smile.acelib.external.EconomyResult;
import com.smile.acelib.external.ExternalIntegrationService;
import com.smile.acelib.external.ExternalOperationResult;
import com.smile.acelib.external.PermissionResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 外部整合門面探針 plugin。
 *
 * <p>經 Bukkit {@code ServicesManager} 取得 {@link AceLibApi}（正式下游入口，
 * 不碰 {@code AceLibPlugin}），再以 {@code /extprobe} 依序呼叫四類業務門面並
 * 把結果輸出到 console 與執行者：</p>
 * <ol>
 *   <li>整合狀態（vault／luckperms／placeholderapi＋模組狀態聚合）</li>
 *   <li>經濟（自身餘額查詢；只讀，不扣款不入帳）</li>
 *   <li>權限（自身群組查詢；只讀）</li>
 *   <li>佔位符（註冊 {@code extprobe} 測試鍵後立即清理，驗證無殘留）</li>
 *   <li>建造查詢（自身腳下位置；無區域保護外掛時預期為明確 UNAVAILABLE）</li>
 * </ol>
 *
 * <p><strong>唯讀為主</strong>：經濟只查餘額；佔位符寫入後立即清理；
 * 不觸碰世界／實體狀態。文件註明四類門面皆可非同步呼叫（provider 端可能有
 * I/O），本探針為求輸出順序可讀而在指令執行緒同步呼叫。</p>
 */
public class ExternalIntegrationProbePlugin extends JavaPlugin implements CommandExecutor {

    /** 探針用的自有佔位符鍵（註冊後立即清理，驗證無殘留）。 */
    static final String PROBE_KEY = "extprobe";

    @Override
    public void onEnable() {
        if (getCommand("extprobe") != null) {
            getCommand("extprobe").setExecutor(this);
        }
        getLogger().info("external integration probe ready; run /extprobe in game");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command,
            String label, String[] args) {
        if (!"extprobe".equalsIgnoreCase(command.getName())) {
            return false;
        }
        RegisteredServiceProvider<AceLibApi.AceLibProvider> registration =
            getServer().getServicesManager().getRegistration(AceLibApi.AceLibProvider.class);
        if (registration == null) {
            report(sender, List.of("AceLib provider 未註冊（AceLib 尚未啟用？）"));
            return true;
        }
        AceLibApi api = registration.getProvider().api();
        if (!api.isReady()) {
            report(sender, List.of("AceLib 存在但尚未 ready；稍後再試。"));
            return true;
        }
        OfflinePlayer target = sender instanceof Player player ? player : null;
        List<String> lines = probe(api.getExternalIntegrationService(), target);
        report(sender, lines);
        return true;
    }

    private void report(CommandSender sender, List<String> lines) {
        for (String line : lines) {
            getLogger().info(line);
            sender.sendMessage(line);
        }
    }

    /**
     * 依序呼叫四類門面並整理為輸出列（純函式：格式化可單元測試）。
     *
     * @param service 外部整合門面；呼叫端保證非 null 且 ready
     * @param target 探測對象；console 執行時為 null（玩家相關門面輸出缺席說明）
     * @return 輸出列；永不為 null 或空
     */
    static List<String> probe(ExternalIntegrationService service, OfflinePlayer target) {
        List<String> lines = new ArrayList<>();
        lines.add(formatStatus(service));
        lines.add(target == null
            ? "[economy] skip: console 執行無玩家對象（遊戲內執行以查詢自身餘額）"
            : formatEconomy(service.getBalance(target)));
        lines.add(target == null
            ? "[permission] skip: console 執行無玩家對象（遊戲內執行以查詢自身群組）"
            : formatPermission(service.getPermissionGroups(target.getUniqueId())));
        lines.add(formatPlaceholderCycle(service));
        lines.add(target == null || !(target instanceof Player player)
            ? "[build] skip: 需要在線玩家位置（遊戲內執行以查詢腳下位置）"
            : formatBuild(service.canBuild(player.getUniqueId(),
                player.getWorld().getName(),
                player.getLocation().getBlockX(),
                player.getLocation().getBlockY() - 1,
                player.getLocation().getBlockZ())));
        return lines;
    }

    /** 整合狀態列（vault／luckperms／placeholderapi＋模組聚合）。 */
    static String formatStatus(ExternalIntegrationService service) {
        return "[status] module=" + service.getModuleStatus()
            + " vault=" + service.getStatus("vault").status()
            + " luckperms=" + service.getStatus("luckperms").status()
            + " placeholderapi=" + service.getStatus("placeholderapi").status();
    }

    /** 經濟餘額列。 */
    static String formatEconomy(EconomyResult result) {
        if (result.isSuccess()) {
            return "[economy] SUCCESS balance=" + result.balance();
        }
        return "[economy] " + result.state()
            + " code=" + result.errorCode()
            + " detail=" + result.detail();
    }

    /** 權限群組列。 */
    static String formatPermission(PermissionResult result) {
        if (result.isSuccess()) {
            return "[permission] SUCCESS primary=" + result.primaryGroup()
                + " groups=" + String.join(",", result.groups());
        }
        return "[permission] " + result.state()
            + " code=" + result.errorCode()
            + " detail=" + result.detail();
    }

    /**
     * 佔位符註冊→清理往返列（寫入後立即清理，驗證無殘留；任一步非成功即如實輸出）。
     */
    static String formatPlaceholderCycle(ExternalIntegrationService service) {
        ExternalOperationResult registered = service.registerPlaceholder(PROBE_KEY,
            (playerId, params) -> "probe:" + params.toLowerCase(Locale.ROOT));
        if (!registered.isSuccess()) {
            return "[placeholder] register " + registered.state()
                + " code=" + registered.errorCode()
                + " detail=" + registered.detail();
        }
        ExternalOperationResult cleaned = service.unregisterPlaceholder(PROBE_KEY);
        return "[placeholder] register SUCCESS; unregister "
            + (cleaned.isSuccess() ? "SUCCESS（無殘留）"
                : cleaned.state() + " code=" + cleaned.errorCode()
                    + " detail=" + cleaned.detail());
    }

    /** 建造查詢列（無區域保護提供者時預期為明確 UNAVAILABLE）。 */
    static String formatBuild(BuildCheckResult result) {
        if (result.isSuccess()) {
            return "[build] SUCCESS allowed=" + result.allowed();
        }
        return "[build] " + result.state()
            + " code=" + result.errorCode()
            + " detail=" + result.detail();
    }
}
