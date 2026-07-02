package com.smile.acelib.config;

import java.util.Objects;

/**
 * 設定檔版本（immutable record）。
 *
 * <p>對應 Plan §九 Phase 4「設定檔版本欄位存在；版本過舊自動觸發遷移」。
 * 設定檔在 YAML 內以 {@code version: "major.minor"} 形式存在；
 * {@link ConfigManager#load()} 會比對檔案版本與 schema 宣告的當前版本，
 * 若檔案版本較舊，自動呼叫 {@link MigrationChain} 進行遷移。</p>
 *
 * <h2>排序規則</h2>
 * <ul>
 *   <li>先比 major：major 越大版本越新</li>
 *   <li>major 相同時比 minor：minor 越大版本越新</li>
 * </ul>
 *
 * <h2>相容性規則</h2>
 * <ul>
 *   <li>{@link #isCompatible(ConfigVersion)}：當 {@code major} 相同視為相容，
 *       表示可以直接讀取而無需 migration</li>
 *   <li>跨 major 視為不相容，必須透過 {@link MigrationChain} 升級</li>
 * </ul>
 *
 * @since Phase 4 (Plan §九)
 */
public record ConfigVersion(int major, int minor) implements Comparable<ConfigVersion> {

    /**
     * 預設當前版本常數 {@code 1.0}（Phase 4 起始版本）。
     */
    public static final ConfigVersion V1_0 = new ConfigVersion(1, 0);

    /**
     * Compact constructor：允許任何 int（含 0/負值），
     * 對齊測試情境下的「任意版本都視為合法」契約。
     *
     * <p>若未來決定拒絕負值，請改為：
     * <pre>{@code
     * if (major < 0) throw new IllegalArgumentException("major must be >= 0");
     * if (minor < 0) throw new IllegalArgumentException("minor must be >= 0");
     * }</pre>
     * 並同步更新 {@code ConfigVersionTest}。</p>
     */
    public ConfigVersion {
        // 目前不限制正負值；保持與 record 自動規範一致。
        // 呼叫方若需驗證，使用 Objects.requireNonNull 或自訂 check。
        Objects.requireNonNull(Integer.valueOf(major), "major");
        Objects.requireNonNull(Integer.valueOf(minor), "minor");
    }

    /**
     * 與另一版本比較：先比 major，後比 minor。
     *
     * @return 負數表示此版本較舊；0 表示相同；正數表示此版本較新
     */
    @Override
    public int compareTo(ConfigVersion other) {
        int cmp = Integer.compare(this.major, other.major);
        if (cmp != 0) return cmp;
        return Integer.compare(this.minor, other.minor);
    }

    /**
     * 兩版本是否相容（同 major）。
     *
     * <p>相容意義：可以直接讀取既有欄位而無需 migration。minor 差異視為向後相容
     * （新欄位補 default、舊欄位沿用既有值）。</p>
     *
     * @param other 另一版本；不可為 null
     * @return 當 {@code major} 相同時回傳 true；否則 false
     * @throws NullPointerException 當 {@code other} 為 null
     */
    public boolean isCompatible(ConfigVersion other) {
        Objects.requireNonNull(other, "other");
        return this.major == other.major;
    }

    /**
     * 將版本序列化為 YAML 友善字串，例如 {@code "1.0"}。
     *
     * <p>使用 {@link #major()} + {@code "."} + {@link #minor()} 的簡明格式，
     * 方便 {@code YamlConfiguration.set("version", toString())} 與讀回。</p>
     *
     * @return {@code "major.minor"} 格式字串
     */
    @Override
    public String toString() {
        return major + "." + minor;
    }
}