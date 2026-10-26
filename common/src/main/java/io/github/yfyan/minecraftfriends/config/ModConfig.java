package io.github.yfyan.minecraftfriends.config;

import java.util.Objects;

public record ModConfig(
        boolean enabled,
        ProviderMode provider,
        int requestTimeoutSeconds,
        boolean diagnosticsEnabled,
        String modelEndpoint,
        String modelName,
        String apiKeyEnvironmentVariable,
        boolean allowUnauthenticatedLocalEndpoint,
        int modelRetries,
        int modelMaxContextMessages,
        int modelMaxContextCharacters,
        int modelMaxResponseBytes
) {
    public ModConfig(
            boolean enabled,
            ProviderMode provider,
            int requestTimeoutSeconds,
            boolean diagnosticsEnabled
    ) {
        this(enabled, provider, requestTimeoutSeconds, diagnosticsEnabled, "", "", "", false,
                1, 24, 12_000, 65_536);
    }

    public ModConfig {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(modelEndpoint, "modelEndpoint");
        Objects.requireNonNull(modelName, "modelName");
        Objects.requireNonNull(apiKeyEnvironmentVariable, "apiKeyEnvironmentVariable");
        if (requestTimeoutSeconds < 1 || requestTimeoutSeconds > 300) {
            throw new IllegalArgumentException("requestTimeoutSeconds must be between 1 and 300");
        }
        if (modelRetries < 0 || modelRetries > 2) {
            throw new IllegalArgumentException("modelRetries must be between 0 and 2");
        }
        if (modelMaxContextMessages < 1 || modelMaxContextMessages > 64) {
            throw new IllegalArgumentException("modelMaxContextMessages must be between 1 and 64");
        }
        if (modelMaxContextCharacters < 1 || modelMaxContextCharacters > 16_000) {
            throw new IllegalArgumentException("modelMaxContextCharacters must be between 1 and 16000");
        }
        if (modelMaxResponseBytes < 1_024 || modelMaxResponseBytes > 1_048_576) {
            throw new IllegalArgumentException("modelMaxResponseBytes must be between 1024 and 1048576");
        }
    }

    public static ModConfig defaults() {
        return new ModConfig(true, ProviderMode.MOCK, 30, true, "", "", "", false,
                1, 24, 12_000, 65_536);
    }

    @Override
    public String toString() {
        return "ModConfig[enabled=" + enabled
                + ", provider=" + provider
                + ", requestTimeoutSeconds=" + requestTimeoutSeconds
                + ", diagnosticsEnabled=" + diagnosticsEnabled
                + ", modelEndpoint=<redacted>"
                + ", modelName=<redacted>"
                + ", apiKeyEnvironmentVariable=" + (apiKeyEnvironmentVariable.isBlank() ? "<unset>" : "<configured>")
                + ", allowUnauthenticatedLocalEndpoint=" + allowUnauthenticatedLocalEndpoint
                + ", modelRetries=" + modelRetries
                + ", modelMaxContextMessages=" + modelMaxContextMessages
                + ", modelMaxContextCharacters=" + modelMaxContextCharacters
                + ", modelMaxResponseBytes=" + modelMaxResponseBytes + ']';
    }
}
