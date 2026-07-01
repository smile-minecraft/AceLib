package com.smile.acelib;

/**
 * AceLib 版本常數。
 *
 * 此值會在建構期同步至 plugin.yml 的 {@code version} 欄位（透過 Gradle
 * {@code processResources} 的 {@code ReplaceTokens} 過濾器）。
 * 任何在 runtime 取得的版本字串都應回傳此常數，確保對外一致。
 */
public final class AceLibVersion {

    /**
     * 對外版本字串。Phase 0 對應 0.1.0-SNAPSHOT；後續 phase 升級時同步更新
     * {@code build.gradle.kts} 的 {@code version} 即可。
     */
    public static final String VERSION = "0.1.0-SNAPSHOT";

    private AceLibVersion() {
        // utility class
    }
}