package com.smile.acelib;

/**
 * AceLib 版本常數（Supported）。
 *
 * <p>此值會在建構期同步至 plugin.yml 的 {@code version} 欄位（透過 Gradle
 * {@code processResources} 的 {@code ReplaceTokens} 過濾器）。
 * 任何在 runtime 取得的版本字串都應回傳此常數，確保對外一致。</p>
 *
 * @since 1.0.0
 */
public final class AceLibVersion {

    /**
     * 對外版本字串。
     *
     * <p>由 {@code build.gradle.kts} 的 {@code version} 在發布流程中同步；
     * 此處為目前快照值。</p>
     *
     * @since 1.0.0
     */
    public static final String VERSION = "1.2.0";

    private AceLibVersion() {
        // utility class
    }
}