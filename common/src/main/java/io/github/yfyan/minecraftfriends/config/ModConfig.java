package io.github.yfyan.minecraftfriends.config;

import java.util.Objects;

public record ModConfig(
        boolean enabled,
        ProviderMode provider,
        int requestTimeoutSeconds,
        boolean diagnosticsEnabled
) {
    public ModConfig {
        Objects.requireNonNull(provider, "provider");
        if (requestTimeoutSeconds < 1 || requestTimeoutSeconds > 300) {
            throw new IllegalArgumentException("requestTimeoutSeconds must be between 1 and 300");
        }
    }

    public static ModConfig defaults() {
        return new ModConfig(true, ProviderMode.MOCK, 30, true);
    }
}
