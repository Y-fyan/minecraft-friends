package io.github.yfyan.minecraftfriends.config;

import io.github.yfyan.minecraftfriends.bootstrap.StartupNotice;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.net.URISyntaxException;
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
            # Chat Completions is only contacted after it is explicitly selected and a chat request is sent.
            # Put credentials in the named environment variable; never store them in this file.
            enabled=true
            model.provider=mock
            model.timeout-seconds=30
            diagnostics.enabled=true
            model.endpoint=
            model.name=
            model.api-key-env=
            model.allow-unauthenticated-local=false
            model.retries=1
            model.max-context-messages=24
            model.max-context-characters=12000
            model.max-response-bytes=65536
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
        String endpoint = parseEndpoint(properties, defaults.modelEndpoint(), notices);
        String modelName = parseModelName(properties, defaults.modelName(), notices);
        String apiKeyEnvironmentVariable = parseEnvironmentVariable(
                properties,
                defaults.apiKeyEnvironmentVariable(),
                notices
        );
        boolean allowUnauthenticatedLocal = parseBoolean(
                properties,
                "model.allow-unauthenticated-local",
                defaults.allowUnauthenticatedLocalEndpoint(),
                notices
        );
        int retries = parseBoundedInteger(properties, "model.retries", defaults.modelRetries(), 0, 2, notices);
        int maxMessages = parseBoundedInteger(
                properties, "model.max-context-messages", defaults.modelMaxContextMessages(), 1, 64, notices
        );
        int maxCharacters = parseBoundedInteger(
                properties, "model.max-context-characters", defaults.modelMaxContextCharacters(), 1, 16_000, notices
        );
        int maxResponseBytes = parseBoundedInteger(
                properties, "model.max-response-bytes", defaults.modelMaxResponseBytes(), 1_024, 1_048_576, notices
        );

        ModConfig config = new ModConfig(
                enabled,
                provider,
                timeout,
                diagnostics,
                endpoint,
                modelName,
                apiKeyEnvironmentVariable,
                allowUnauthenticatedLocal,
                retries,
                maxMessages,
                maxCharacters,
                maxResponseBytes
        );
        if (provider == ProviderMode.MOCK) {
            notices.add(info("Mock mode remains offline; no model request will be sent."));
        } else if (provider == ProviderMode.CHAT_COMPLETIONS) {
            notices.add(info("Chat Completions is selected; network access begins only when a chat request is sent."));
        }
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
        } catch (IllegalArgumentException ignored) {
            notices.add(warning("Invalid model.provider; using " + fallback + "."));
            return fallback;
        }
    }

    private static String parseEndpoint(Properties properties, String fallback, List<StartupNotice> notices) {
        String raw = properties.getProperty("model.endpoint");
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String candidate = raw.trim();
        if (candidate.length() > 2_048) {
            notices.add(warning("Invalid model.endpoint; using the empty endpoint."));
            return fallback;
        }
        try {
            URI endpoint = new URI(candidate);
            String scheme = endpoint.getScheme();
            String host = endpoint.getHost();
            if (host == null
                    || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                    || endpoint.getUserInfo() != null
                    || endpoint.getQuery() != null
                    || endpoint.getFragment() != null
                    || endpoint.getPort() > 65_535
                    || ("http".equalsIgnoreCase(scheme) && !isLoopbackHost(host))) {
                notices.add(warning("Invalid model.endpoint; using the empty endpoint."));
                return fallback;
            }
            return endpoint.toASCIIString();
        } catch (URISyntaxException ignored) {
            notices.add(warning("Invalid model.endpoint; using the empty endpoint."));
            return fallback;
        }
    }

    private static boolean isLoopbackHost(String host) {
        return host != null && ("localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "[::1]".equals(host)
                || "::1".equals(host));
    }

    private static String parseModelName(Properties properties, String fallback, List<StartupNotice> notices) {
        String raw = properties.getProperty("model.name");
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String candidate = raw.trim();
        if (candidate.length() > 128 || candidate.chars().anyMatch(Character::isISOControl)) {
            notices.add(warning("Invalid model.name; using the empty model name."));
            return fallback;
        }
        return candidate;
    }

    private static String parseEnvironmentVariable(
            Properties properties,
            String fallback,
            List<StartupNotice> notices
    ) {
        String raw = properties.getProperty("model.api-key-env");
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String candidate = raw.trim();
        if (!candidate.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            notices.add(warning("Invalid model.api-key-env; no environment variable will be read."));
            return fallback;
        }
        return candidate;
    }

    private static int parseBoundedInteger(
            Properties properties,
            String key,
            int fallback,
            int minimum,
            int maximum,
            List<StartupNotice> notices
    ) {
        String raw = properties.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < minimum || value > maximum) {
                notices.add(warning(key + " must be between " + minimum + " and " + maximum + "; using " + fallback + "."));
                return fallback;
            }
            return value;
        } catch (NumberFormatException ignored) {
            notices.add(warning("Invalid " + key + "; using " + fallback + "."));
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
