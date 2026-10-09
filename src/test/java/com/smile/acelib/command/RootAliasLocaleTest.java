package com.smile.acelib.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 根別名語系正規化測試（t09 審查修正的延申）。
 *
 * <p>所有別名／名稱比對的小寫必須一律 {@link Locale#ROOT}：JVM 預設語系為
 * 土耳其語時，{@code "BIG".toLowerCase()} 是 {@code "bıg"} 而非
 * {@code "big"}，與 Brigadier 的 {@code Locale.ROOT} 不一致。</p>
 */
@DisplayName("根別名語系正規化")
class RootAliasLocaleTest {

    @Test
    @DisplayName("預設語系為土耳其語時，含大寫 I 的根別名查找與衝突行為與 ROOT 一致")
    void turkishLocale_rootAliasWithCapitalI() {
        Locale previous = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr"));
        try {
            CommandRegistryTest.RecordingReplySink sink =
                new CommandRegistryTest.RecordingReplySink();
            CommandRegistryImpl registry = new CommandRegistryImpl(sink);
            AtomicBoolean ran = new AtomicBoolean(false);
            CommandSpec spec = CommandSpec.builder("shop")
                .aliases("BIG")
                .subCommand(SubCommandSpec.builder("buy")
                    .handler(ctx -> ran.set(true))
                    .build())
                .build();
            registry.register(spec);
            // 小寫查找命中。
            assertSame(spec, registry.findCommand("big"));
            // 大寫查找同樣命中（與 ROOT 語系一致）。
            assertSame(spec, registry.findCommand("BIG"));
            // 衝突檢查：大小寫變體視為同一別名。
            assertThrows(IllegalArgumentException.class,
                () -> registry.register(CommandSpec.builder("other")
                    .aliases("big")
                    .subCommand(SubCommandSpec.builder("sell")
                        .handler(SubCommand.NOOP)
                        .build())
                    .build()));
            // dispatch 經大小寫別名都能執行。
            CommandRegistryTest.TestSender console =
                new CommandRegistryTest.TestSender("Console", false);
            registry.dispatch(console, "BIG", List.of("buy"));
            assertTrue(ran.get(), "大寫別名應執行到同一指令");
            // tabComplete 與 help 經大寫標籤同樣找到指令。
            assertTrue(registry.tabComplete(console, "BIG", List.of())
                .contains("buy"));
            assertTrue(registry.formatHelp("BIG", console).contains("shop"));
            // 經大寫別名解除註冊後，小寫查找不再命中。
            registry.unregister("BIG");
            assertNull(registry.findCommand("big"));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    @DisplayName("預設語系為土耳其語時，傳統 builder 建大寫主名仍以 ROOT 正規化")
    void turkishLocale_uppercasePrimaryName_traditionalBuilder() {
        Locale previous = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr"));
        try {
            CommandRegistryTest.RecordingReplySink sink =
                new CommandRegistryTest.RecordingReplySink();
            CommandRegistryImpl registry = new CommandRegistryImpl(sink);
            AtomicBoolean ran = new AtomicBoolean(false);
            registry.register(CommandSpec.builder("shop")
                .subCommand(SubCommandSpec.builder("BIG")
                    .handler(ctx -> ran.set(true))
                    .build())
                .build());
            CommandRegistryTest.TestSender console =
                new CommandRegistryTest.TestSender("Console", false);
            assertTrue(registry.findCommand("shop")
                .findSubCommand("BIG") != null);
            registry.dispatch(console, "shop", List.of("big"));
            assertTrue(ran.get(), "小寫輸入應命中 ROOT 正規化的主名");
        } finally {
            Locale.setDefault(previous);
        }
    }
}
