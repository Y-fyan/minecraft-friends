package io.github.yfyan.minecraftfriends.config;

import io.github.yfyan.minecraftfriends.bootstrap.StartupNotice;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

public final class ConfigLoader {
    public static final String FILE_NAME = "minecraft-friends.properties";

    private static final String DEFAULT_FILE = """
            # Minecraft Friends configuration.
            # M0 only supports a no-network Mock provider.
            # API credentials are intentionally not accepted or stored by this version.
            enabled=true
            model.provider=mock
            model.timeout-seconds=30
            diagnostics.enabled=true
            """;

    public LoadedConfig loadOrCreate(Path configDirectory) throws IOException {
        Objects.requireNonNull(configDirectory, "configDirectory");
        Files.createDirectories(configDirectory);

        Path configPath = configDirectory.resolve(FILE_NAME);
        boolean created = Files.notExists(configPath);
        if (created) {
            writeDefaultAtomically(configPath);
        }

        List<StartupNotice> notices = new ArrayList<>();
        if (created) {
            notices.add(info("Created default configuration at " + configPath));
        }

        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }

        ModConfig defaults = ModConfig.defaults();
        boolean enabled = parseBoolean(properties, "enabled", defaults.enabled(), notices);
        boolean diagnostics = parseBoolean(
                properties,
                "diagnostics.enabled",
                defaults.diagnosticsEnabled(),
                notices
        );
        ProviderMode provider = parseProvider(properties, defaults.provider(), notices);
        int timeout = parseTimeout(properties, defaults.requestTimeoutSeconds(), notices);

        ModConfig config = new ModConfig(enabled, provider, timeout, diagnostics);
        notices.add(info("No model credentials were loaded; Mock mode remains offline."));
        return new LoadedConfig(config, configPath, created, List.copyOf(notices));
    }

    private static void writeDefaultAtomically(Path configPath) throws IOException {
        Path temporary = Files.createTempFile(configPath.getParent(), FILE_NAME + ".", ".tmp");
        try {
            Files.writeString(temporary, DEFAULT_FILE, StandardCharsets.UTF_8);
            try {
                Files.move(
                        temporary,
                        configPath,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, configPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static boolean parseBoolean(
            Properties properties,
            String key,
            boolean fallback,
            List<StartupNotice> notices
    ) {
        String raw = properties.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        if ("true".equalsIgnoreCase(raw.trim())) {
            return true;
        }
        if ("false".equalsIgnoreCase(raw.trim())) {
            return false;
        }
        notices.add(warning("Invalid boolean for " + key + "; using " + fallback + "."));
        return fallback;
    }

    private static ProviderMode parseProvider(
            Properties properties,
            ProviderMode fallback,
            List<StartupNotice> notices
    ) {
        String raw = properties.getProperty("model.provider");
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return ProviderMode.fromConfigValue(raw);
        } catch (IllegalArgumentException exception) {
            notices.add(warning(exception.getMessage() + "; using " + fallback + "."));
            return fallback;
        }
    }

    private static int parseTimeout(
            Properties properties,
            int fallback,
            List<StartupNotice> notices
    ) {
        String raw = properties.getProperty("model.timeout-seconds");
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int timeout = Integer.parseInt(raw.trim());
            if (timeout < 1 || timeout > 300) {
                notices.add(warning(
                        "model.timeout-seconds must be between 1 and 300; using " + fallback + "."
                ));
                return fallback;
            }
            return timeout;
        } catch (NumberFormatException exception) {
            notices.add(warning("Invalid model.timeout-seconds; using " + fallback + "."));
            return fallback;
        }
    }

    private static StartupNotice info(String message) {
        return new StartupNotice(StartupNotice.Level.INFO, message);
    }

    private static StartupNotice warning(String message) {
        return new StartupNotice(StartupNotice.Level.WARNING, message);
    }

    public record LoadedConfig(
            ModConfig config,
            Path path,
            boolean created,
            List<StartupNotice> notices
    ) {
        public LoadedConfig {
            Objects.requireNonNull(config, "config");
            Objects.requireNonNull(path, "path");
            notices = List.copyOf(notices);
        }
    }
}
