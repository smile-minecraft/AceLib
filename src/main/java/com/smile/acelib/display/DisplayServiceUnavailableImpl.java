package com.smile.acelib.display;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;

/**
 * 未啟用／已停用狀態下的可診斷 facade（package-private）。
 *
 * <p>所有變更操作一律回 {@code FAILED + 建構時指定的代碼}；
 * 查詢與清理為無操作（回 empty／false／0），永不為 null 且永不拋例外。</p>
 */
final class DisplayServiceUnavailableImpl implements DisplayService {

    private final String code;

    DisplayServiceUnavailableImpl(String code) {
        if (!DisplayErrorCode.NOT_READY.equals(code)
                && !DisplayErrorCode.SHUTDOWN.equals(code)) {
            throw new IllegalArgumentException("[" + DisplayErrorCode.INVALID_INPUT
                + "] code 必須為 NOT_READY 或 SHUTDOWN，實際為 " + code);
        }
        this.code = Objects.requireNonNull(code, "code");
    }

    private DisplayResult failed(String detail) {
        return DisplayResult.failed(code, detail);
    }

    @Override
    public DisplayResult showScoreboard(UUID playerId, Component title, List<Component> lines) {
        return failed("display service unavailable: " + code);
    }

    @Override
    public DisplayResult hideScoreboard(UUID playerId) {
        return failed("display service unavailable: " + code);
    }

    @Override
    public DisplayResult showBossBar(UUID playerId, Component title, double progress,
            BarColor color, BarStyle style) {
        return failed("display service unavailable: " + code);
    }

    @Override
    public DisplayResult updateBossBar(UUID playerId, Component title, double progress) {
        return failed("display service unavailable: " + code);
    }

    @Override
    public DisplayResult hideBossBar(UUID playerId) {
        return failed("display service unavailable: " + code);
    }

    @Override
    public DisplayResult showHologram(Location location, Component text) {
        return failed("display service unavailable: " + code);
    }

    @Override
    public DisplayResult updateHologram(UUID hologramId, Component text) {
        return failed("display service unavailable: " + code);
    }

    @Override
    public DisplayResult setHologramVisible(UUID hologramId, UUID viewerId, boolean visible) {
        return failed("display service unavailable: " + code);
    }

    @Override
    public DisplayResult removeHologram(UUID hologramId) {
        return failed("display service unavailable: " + code);
    }

    @Override
    public Optional<Hologram> findHologram(UUID hologramId) {
        return Optional.empty();
    }

    @Override
    public boolean closePlayer(UUID playerId) {
        return false;
    }

    @Override
    public void handlePlayerQuit(UUID playerId) {
        // 無操作：unavailable facade 不持有任何追蹤
    }

    @Override
    public int closeAll() {
        return 0;
    }
}
