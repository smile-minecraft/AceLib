package com.example.acelibcmdprobe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.smile.acelib.command.Arguments;
import com.smile.acelib.command.CommandArgument;
import com.smile.acelib.command.TypedCommand;
import com.smile.acelib.command.TypedSubCommand;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 探針第三階段指令形狀的組裝期驗證。
 *
 * <p>探針本體只能在真實伺服器上執行觀察；這個測試在無伺服器環境先擋下
 * 部署即失效的組裝錯誤（省略／重複位置矛盾、別名衝突），
 * 與 {@link CommandCompatibilityProbePlugin} 的新子指令保持同形。
 * 不斷言任何客戶端可見行為（補全可見性屬實機紀錄）。</p>
 */
@DisplayName("探針第三階段指令形狀（組裝期驗證）")
class ProbeCommandShapesTest {

    @Test
    @DisplayName("give 形狀：必要＋省略＋重複可組裝")
    void giveShapeBuilds() {
        TypedSubCommand give = TypedSubCommand.builder("give")
            .argument(Arguments.player("player"))
            .optional(Arguments.intArg("amount", 1, 64), sender -> 1)
            .repeatable(Arguments.material("extra"))
            .executes(ctx -> {
            })
            .build();
        assertEquals(3, give.arguments().size());
    }

    @Test
    @DisplayName("第三階段子指令與別名可組裝進同一根指令")
    void thirdStageRootBuilds() {
        List<String> dynOptions =
            new CopyOnWriteArrayList<>(List.of("alpha", "beta"));
        TypedCommand root = TypedCommand.builder("cprobe")
            .permission("acelibcmdprobe.use")
            .aliases("cp")
            .subcommand(TypedSubCommand.builder("parse-int")
                .aliases("pi")
                .argument(Arguments.intArg("value", 1, 64))
                .executes(ctx -> {
                })
                .build())
            .subcommand(TypedSubCommand.builder("parse-bigdecimal")
                .argument(Arguments.bigDecimal("value",
                    new BigDecimal("0"), new BigDecimal("1000"), 2))
                .executes(ctx -> {
                })
                .build())
            .subcommand(TypedSubCommand.builder("parse-dyn")
                .argument(Arguments.dynamic("value", () -> List.copyOf(dynOptions)))
                .executes(ctx -> {
                })
                .build())
            .subcommand(TypedSubCommand.builder("parse-percent")
                .argument(CommandArgument.custom("rate", "<rate:percent>",
                    (raw, messages) -> 0.0,
                    prefix -> List.of()))
                .executes(ctx -> {
                })
                .build())
            .build();
        assertEquals(4, root.subcommands().size());
    }

    @Test
    @DisplayName("別名與其他子指令主名衝突時拒絕")
    void aliasConflictRejected() {
        assertThrows(IllegalArgumentException.class, () -> TypedCommand.builder("cprobe")
            .subcommand(TypedSubCommand.builder("parse-int")
                .aliases("pi")
                .argument(Arguments.intArg("value", 1, 64))
                .executes(ctx -> {
                })
                .build())
            .subcommand(TypedSubCommand.builder("pi")
                .argument(Arguments.intArg("value", 1, 64))
                .executes(ctx -> {
                })
                .build())
            .build());
    }
}
