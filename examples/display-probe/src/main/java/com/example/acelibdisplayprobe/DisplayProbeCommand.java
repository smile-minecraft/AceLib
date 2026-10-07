package com.example.acelibdisplayprobe;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 已驗證的探針指令形式。 */
record DisplayProbeCommand(Action action, UUID hologramId) {

    enum Action {
        RUN,
        KEEP,
        FINISH
    }

    static Optional<DisplayProbeCommand> parse(String[] args) {
        Objects.requireNonNull(args, "args");
        if (args.length == 0) {
            return Optional.of(new DisplayProbeCommand(Action.RUN, null));
        }
        if (args.length == 1) {
            if ("run".equalsIgnoreCase(args[0])) {
                return Optional.of(new DisplayProbeCommand(Action.RUN, null));
            }
            if ("keep".equalsIgnoreCase(args[0])) {
                return Optional.of(new DisplayProbeCommand(Action.KEEP, null));
            }
        }
        if (args.length == 2 && "finish".equalsIgnoreCase(args[0])) {
            if (args[1] == null) {
                return Optional.empty();
            }
            try {
                UUID hologramId = UUID.fromString(args[1]);
                if (hologramId.toString().equalsIgnoreCase(args[1])) {
                    return Optional.of(new DisplayProbeCommand(Action.FINISH, hologramId));
                }
            } catch (IllegalArgumentException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }
}
