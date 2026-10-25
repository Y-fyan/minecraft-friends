package io.github.yfyan.minecraftfriends.config;

import java.util.Locale;

public enum ProviderMode {
    MOCK("mock"),
    DISABLED("disabled");

    private final String configValue;

    ProviderMode(String configValue) {
        this.configValue = configValue;
    }

    public String configValue() {
        return configValue;
    }

    public static ProviderMode fromConfigValue(String raw) {
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (ProviderMode mode : values()) {
            if (mode.configValue.equals(normalized)) {
                return mode;
            }
        }
        throw new IllegalArgumentException(
                "Unsupported model.provider '" + raw + "'; M0 supports only mock and disabled"
        );
    }
}
