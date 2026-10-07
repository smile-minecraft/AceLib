package com.example.acelibdisplayprobe;

import com.smile.acelib.AceLibApi;
import com.smile.acelib.display.DisplayResult;
import com.smile.acelib.display.DisplayService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Paper／Folia 顯示模組探針。所有實體變更都透過 AceLib 的公開服務，
 * 只記錄結果，不操作其他 plugin 的顯示。
 */
public final class DisplayProbePlugin extends JavaPlugin implements CommandExecutor {

    private static final long PHASE_DELAY_TICKS = 3L;

    private final Map<UUID, ActiveProbeRun> activeRuns = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        var command = getCommand("dprobe");
        if (command == null) {
            getLogger().severe("plugin.yml 未定義 dprobe 指令；停用本 plugin。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        command.setExecutor(this);
        getLogger().info("AceLib 顯示探針已啟用；可執行 /dprobe [run|keep|finish <id>]。");
    }

    @Override
    public void onDisable() {
        activeRuns.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            report(sender, "此探針需由線上玩家執行：/dprobe [run|keep|finish <id>]");
            return true;
        }
        if (!sender.hasPermission("acelibdisplayprobe.use")) {
            report(sender, "沒有使用顯示探針的權限。");
            return true;
        }
        Optional<DisplayProbeCommand> parsed = DisplayProbeCommand.parse(args);
        if (parsed.isEmpty()) {
            report(sender, "用法：/dprobe [run]、/dprobe keep、/dprobe finish <id>");
            return true;
        }
        DisplayProbeCommand request = parsed.orElseThrow();

        RegisteredServiceProvider<AceLibApi.AceLibProvider> registration = getServer()
            .getServicesManager().getRegistration(AceLibApi.AceLibProvider.class);
        if (registration == null || !registration.getProvider().api().isReady()) {
            report(sender, "AceLib provider 未就緒；拒絕執行探針。");
            return true;
        }
        if (activeRuns.containsKey(player.getUniqueId())) {
            report(sender, "這位玩家已有探針執行中，請稍後再試。");
            return true;
        }

        DisplayService displays = registration.getProvider().api().getDisplayService();
        if (request.action() == DisplayProbeCommand.Action.KEEP) {
            keepHologram(player, displays);
            return true;
        }
        if (request.action() == DisplayProbeCommand.Action.FINISH) {
            startFinish(player, displays, request.hologramId());
            return true;
        }

        Player secondViewer = getServer().getOnlinePlayers().stream()
            .filter(candidate -> !candidate.getUniqueId().equals(player.getUniqueId()))
            .findFirst().orElse(null);
        UUID secondViewerId = secondViewer == null ? null : secondViewer.getUniqueId();

        DisplayResult scoreboard = displays.showScoreboard(player.getUniqueId(),
            Component.text("dprobe：建立"), List.of(
                Component.text("owner=" + player.getName()), Component.text("step=create")));
        DisplayResult bossBar = displays.showBossBar(player.getUniqueId(),
            Component.text("dprobe：建立"), 0.35, BarColor.BLUE, BarStyle.SOLID);
        DisplayResult hologram = displays.showHologram(player.getLocation(),
            Component.text("dprobe：建立"));

        ProbeRun run = new ProbeRun(displays, player.getUniqueId(), secondViewerId,
            hologram.hologramId());
        activeRuns.put(player.getUniqueId(), run);
        report(sender, "建立結果：scoreboard=" + describe(scoreboard)
            + ", bossbar=" + describe(bossBar) + ", hologram=" + describe(hologram));
        report(sender, secondViewer == null
            ? "目前沒有第二位玩家；可見性對照將略過。"
            : "可見性對照玩家：" + secondViewer.getName() + "（全息字只顯示給執行者）。");
        log("create owner=" + player.getUniqueId() + " "
            + "scoreboard=" + describe(scoreboard) + " bossbar=" + describe(bossBar)
            + " hologram=" + describe(hologram));

        schedule(player, run, "update", () -> updateDisplays(player, run));
        return true;
    }

    private void keepHologram(Player player, DisplayService displays) {
        DisplayResult result = displays.showHologram(
            player.getLocation(), Component.text("dprobe：保留"));
        String id = result.hologramId() == null ? "" : " id=" + result.hologramId();
        report(player, "保留結果：" + describe(result) + id);
        log("keep owner=" + player.getUniqueId() + " hologram=" + describe(result) + id);
    }

    private void startFinish(Player player, DisplayService displays, UUID hologramId) {
        if (displays.findHologram(hologramId).isEmpty()) {
            report(player, "找不到已生成的全息字 id=" + hologramId + "；請確認 keep 已完成。");
            return;
        }
        FinishRun run = new FinishRun(displays, player.getUniqueId(), hologramId);
        activeRuns.put(player.getUniqueId(), run);
        report(player, "跨區檢查開始：id=" + hologramId + "；執行者將作為遠端觀看者。");
        finishUpdate(player, run);
    }

    private void finishUpdate(Player player, FinishRun run) {
        DisplayResult result = run.displays.updateHologram(
            run.hologramId, Component.text("dprobe：跨區更新"));
        report(player, "跨區更新：" + describe(result));
        log("finish update owner=" + run.ownerId + " hologram=" + run.hologramId
            + " result=" + describe(result));
        schedule(player, run, "visibility-show", () -> finishShow(player, run));
    }

    private void finishShow(Player player, FinishRun run) {
        DisplayResult result = run.displays.setHologramVisible(
            run.hologramId, run.ownerId, true);
        report(player, "遠端觀看者顯示：" + describe(result));
        log("finish visibility-show owner=" + run.ownerId + " hologram=" + run.hologramId
            + " result=" + describe(result));
        schedule(player, run, "visibility-hide", () -> finishHide(player, run));
    }

    private void finishHide(Player player, FinishRun run) {
        DisplayResult result = run.displays.setHologramVisible(
            run.hologramId, run.ownerId, false);
        report(player, "遠端觀看者隱藏：" + describe(result));
        log("finish visibility-hide owner=" + run.ownerId + " hologram=" + run.hologramId
            + " result=" + describe(result));
        schedule(player, run, "remove", () -> finishRemove(player, run));
    }

    private void finishRemove(Player player, FinishRun run) {
        DisplayResult result = run.displays.removeHologram(run.hologramId);
        report(player, "跨區移除：" + describe(result));
        log("finish remove owner=" + run.ownerId + " hologram=" + run.hologramId
            + " result=" + describe(result));
        schedule(player, run, "residue-check", () -> finishCheckResidue(player, run));
    }

    private void finishCheckResidue(Player player, FinishRun run) {
        if (run.displays.findHologram(run.hologramId).isEmpty()) {
            finishReportClean(player, run, false);
            return;
        }
        DisplayResult retry = run.displays.removeHologram(run.hologramId);
        report(player, "第一次自檢仍有追蹤，已再送一次移除：" + describe(retry));
        log("finish residue retry owner=" + run.ownerId + " hologram=" + run.hologramId
            + " result=" + describe(retry));
        schedule(player, run, "final-residue-check",
            () -> finishFinalResidueCheck(player, run));
    }

    private void finishFinalResidueCheck(Player player, FinishRun run) {
        finishReportClean(player, run,
            run.displays.findHologram(run.hologramId).isPresent());
    }

    private void finishReportClean(Player player, FinishRun run, boolean residue) {
        report(player, "殘留自檢：tracked=" + residue
            + (residue ? "（仍有追蹤，請查看伺服器記錄）" : "（乾淨；另以 RCON 檢查世界實體）"));
        log("finish residue owner=" + run.ownerId + " hologram=" + run.hologramId
            + " tracked=" + residue);
        activeRuns.remove(run.ownerId, run);
    }

    private void updateDisplays(Player player, ProbeRun run) {
        DisplayResult scoreboard = run.displays.showScoreboard(run.ownerId,
            Component.text("dprobe：更新"), List.of(
                Component.text("owner=" + player.getName()), Component.text("step=update")));
        DisplayResult bossBar = run.displays.updateBossBar(run.ownerId,
            Component.text("dprobe：更新"), 0.72);
        DisplayResult hologram = run.hologramId == null
            ? null : run.displays.updateHologram(run.hologramId, Component.text("dprobe：更新"));
        DisplayResult ownerVisibility = run.hologramId == null
            ? null : run.displays.setHologramVisible(run.hologramId, run.ownerId, true);
        DisplayResult secondVisibility = run.hologramId == null || run.secondViewerId == null
            ? null : run.displays.setHologramVisible(run.hologramId, run.secondViewerId, false);

        report(player, "更新結果：scoreboard=" + describe(scoreboard)
            + ", bossbar=" + describe(bossBar) + ", hologram=" + describe(hologram));
        if (run.secondViewerId != null) {
            report(player, "可見性結果：執行者=" + describe(ownerVisibility)
                + ", 第二玩家維持隱藏=" + describe(secondVisibility));
        }
        log("update owner=" + run.ownerId + " scoreboard=" + describe(scoreboard)
            + " bossbar=" + describe(bossBar) + " hologram=" + describe(hologram)
            + " viewer=" + describe(ownerVisibility) + " second=" + describe(secondVisibility));

        schedule(player, run, "remove", () -> removeDisplays(player, run));
    }

    private void removeDisplays(Player player, ProbeRun run) {
        DisplayResult scoreboard = run.displays.hideScoreboard(run.ownerId);
        DisplayResult bossBar = run.displays.hideBossBar(run.ownerId);
        DisplayResult hologram = run.hologramId == null
            ? null : run.displays.removeHologram(run.hologramId);
        report(player, "移除結果：scoreboard=" + describe(scoreboard)
            + ", bossbar=" + describe(bossBar) + ", hologram=" + describe(hologram));
        log("remove owner=" + run.ownerId + " scoreboard=" + describe(scoreboard)
            + " bossbar=" + describe(bossBar) + " hologram=" + describe(hologram));

        schedule(player, run, "residue-check", () -> checkResidue(player, run));
    }

    private void checkResidue(Player player, ProbeRun run) {
        boolean hologramResidue = run.hologramId != null
            && run.displays.findHologram(run.hologramId).isPresent();
        if (hologramResidue) {
            run.displays.removeHologram(run.hologramId);
        }
        boolean playerResidue = run.displays.closePlayer(run.ownerId);
        activeRuns.remove(run.ownerId, run);
        String outcome = "residue hologram=" + hologramResidue + " player=" + playerResidue;
        report(player, "殘留自檢：" + outcome + (hologramResidue || playerResidue
            ? "（已再送一次清理，請檢查伺服器執行緒紀錄）" : "（乾淨）"));
        log(outcome);
    }

    private void schedule(Player player, ActiveProbeRun run, String nextPhase, Runnable next) {
        player.getScheduler().runDelayed(this, ignored -> next.run(), () -> {
            activeRuns.remove(run.ownerId(), run);
            log("retired owner=" + run.ownerId() + " before phase=" + nextPhase);
        }, PHASE_DELAY_TICKS);
    }

    private void report(CommandSender sender, String message) {
        sender.sendMessage(Component.text("[dprobe] " + message));
    }

    private void log(String message) {
        getLogger().info("[dprobe] " + message);
    }

    private static String describe(DisplayResult result) {
        if (result == null) {
            return "skipped";
        }
        return result.state() + (result.errorCode() == null ? "" : "/" + result.errorCode());
    }

    private interface ActiveProbeRun {
        UUID ownerId();
    }

    private record ProbeRun(DisplayService displays, UUID ownerId, UUID secondViewerId,
                            UUID hologramId) implements ActiveProbeRun {
    }

    private record FinishRun(DisplayService displays, UUID ownerId,
                             UUID hologramId) implements ActiveProbeRun {
    }
}
