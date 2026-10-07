package com.example.acelibdisplayprobe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 顯示探針指令格式測試。 */
class DisplayProbeCommandTest {

    @Test
    @DisplayName("run 別名維持原本的完整顯示流程")
    void runCommand_isAccepted() {
        assertEquals(Optional.of(new DisplayProbeCommand(DisplayProbeCommand.Action.RUN, null)),
            DisplayProbeCommand.parse(new String[] {"run"}));
        assertEquals(Optional.of(new DisplayProbeCommand(DisplayProbeCommand.Action.RUN, null)),
            DisplayProbeCommand.parse(new String[0]));
    }

    @Test
    @DisplayName("keep 指令格式被接受")
    void keepCommand_isAccepted() {
        assertEquals(Optional.of(new DisplayProbeCommand(DisplayProbeCommand.Action.KEEP, null)),
            DisplayProbeCommand.parse(new String[] {"keep"}));
    }

    @Test
    @DisplayName("finish 只接受帶 UUID 的既有全息字 id")
    void finishCommand_requiresUuid() {
        UUID hologramId = UUID.fromString("9a9b063a-1e1b-4f22-bf71-3c41d16cbef0");

        assertEquals(Optional.of(new DisplayProbeCommand(
            DisplayProbeCommand.Action.FINISH, hologramId)),
            DisplayProbeCommand.parse(new String[] {"finish", hologramId.toString()}));
        assertTrue(DisplayProbeCommand.parse(new String[] {"finish"}).isEmpty());
        assertTrue(DisplayProbeCommand.parse(new String[] {"finish", "not-a-uuid"}).isEmpty());
        assertTrue(DisplayProbeCommand.parse(new String[] {"keep", "extra"}).isEmpty());
    }
}
