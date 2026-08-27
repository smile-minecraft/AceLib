package com.example.acelibmsgprobe;

import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 訊息相容性探針：部署到 Folia 測試服後，提供兩個指令把固定 Adventure Component
 * 案例發送給玩家，供真人用 Java 與 Bedrock（經 Geyser）客戶端觀察轉換結果。
 *
 * <p>指令：</p>
 * <ul>
 *   <li>{@code /mprobe list}：列出所有案例 id 與說明（不發送）。</li>
 *   <li>{@code /mprobe send [player]}：把全部案例發送給執令者本人；若給定
 *       {@code player} 則改發送給該線上玩家（可用來對準基岩玩家觀察 Geyser 轉換）。</li>
 * </ul>
 *
 * <p>安全約束：</p>
 * <ul>
 *   <li>所有案例皆為固定、無破壞性內容；RUN_COMMAND 只指向 {@code /list}，
 *       不會觸發刪除、重建世界、付款或外部訊息等不可逆操作。</li>
 *   <li>本 plugin 直接走 Bukkit/Paper 原生的 {@code Player.sendMessage(Component)}
 *       送出入口，不依賴 AceLib 訊息 API，也不改變任何正式 API。</li>
 *   <li>每個案例發送前後都會把 case id 與純文字寫入 server log，作為
 *       伺服器端可回溯的發送證據（即使沒有客戶端觀察也能確認「已送出」）。</li>
 * </ul>
 */
public final class MessageCompatibilityProbePlugin extends JavaPlugin implements CommandExecutor {

    private static final String USAGE = "/mprobe <list | send [player]>";

    @Override
    public void onEnable() {
        var command = getCommand("mprobe");
        if (command == null) {
            getLogger().severe("plugin.yml 未定義 mprobe 指令；停用本 plugin。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        command.setExecutor(this);
        getLogger().info("AceLib 訊息相容性探針已啟用；/mprobe 可用。");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            report(sender, USAGE);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> handleList(sender);
            case "send" -> handleSend(sender, args);
            default -> report(sender, "未知子指令。" + USAGE);
        }
        return true;
    }

    private void handleList(CommandSender sender) {
        List<CompatibilityCase> catalog = CompatibilityCases.buildCatalog();
        report(sender, "== /mprobe list（共 " + catalog.size() + " 個案例）==");
        for (CompatibilityCase c : catalog) {
            report(sender, "[" + c.id() + "] " + c.description());
        }
    }

    private void handleSend(CommandSender sender, String[] args) {
        Player target;
        if (args.length >= 2) {
            target = getServer().getPlayer(args[1]);
            if (target == null) {
                report(sender, "找不到線上玩家：" + args[1] + "（目標必須已登入）");
                return;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            report(sender, "console 執行 send 必須指定線上玩家：/mprobe send <player>");
            return;
        }

        List<CompatibilityCase> catalog = CompatibilityCases.buildCatalog();
        report(sender, "== /mprobe send → " + target.getName()
            + "（共 " + catalog.size() + " 個案例）==");
        for (CompatibilityCase c : catalog) {
            // 伺服器端證據：記錄 case id 與純文字，確認已送出。
            String plain = PlainTextComponentSerializer.plainText().serialize(c.component());
            getLogger().info("[mprobe-send] case=" + c.id()
                + ", target=" + target.getName() + ", text=" + plain);
            try {
                target.sendMessage(c.component());
            } catch (RuntimeException ex) {
                // 不吞錯：如實回報發送失敗（含例外訊息），由觀察者判斷。
                getLogger().severe("[mprobe-send] case=" + c.id()
                    + " 發送失敗: " + ex.getMessage());
                report(sender, "[mprobe-send] case=" + c.id() + " 發送失敗：" + ex.getMessage());
            }
        }
        report(sender, "已發送 " + catalog.size() + " 個案例給 " + target.getName()
            + "；請以 Java 與 Bedrock 客戶端分別觀察並回填矩陣報告。");
    }

    /** 同步輸出到呼叫者與 server log，方便真人驗收時對照。 */
    private void report(CommandSender sender, String line) {
        sender.sendMessage(Component.text(line));
        getLogger().info(line);
    }
}
