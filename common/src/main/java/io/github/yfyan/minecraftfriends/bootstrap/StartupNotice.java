package io.github.yfyan.minecraftfriends.bootstrap;

import java.util.Objects;

public record StartupNotice(Level level, String message) {
    public StartupNotice {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(message, "message");
    }

    public enum Level {
        INFO,
        WARNING
    }
}
