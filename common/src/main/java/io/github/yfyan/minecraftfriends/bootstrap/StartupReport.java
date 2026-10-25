package io.github.yfyan.minecraftfriends.bootstrap;

import io.github.yfyan.minecraftfriends.config.ModConfig;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record StartupReport(
        ModConfig config,
        Path configPath,
        boolean createdDefaultConfig,
        List<StartupNotice> notices
) {
    public StartupReport {
        Objects.requireNonNull(config, "config");
        notices = List.copyOf(notices);
    }
}
