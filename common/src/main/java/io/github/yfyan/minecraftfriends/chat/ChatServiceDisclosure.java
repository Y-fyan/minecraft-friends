package io.github.yfyan.minecraftfriends.chat;

import java.net.URI;

/** Safe display helpers for model-service notices. */
final class ChatServiceDisclosure {
    private ChatServiceDisclosure() {
    }

    static String endpointHost(String endpoint) {
        try {
            String host = URI.create(endpoint).getHost();
            if (host != null && !host.isBlank()) {
                return host;
            }
        } catch (IllegalArgumentException ignored) {
            // Never fall back to displaying the configured endpoint.
        }
        return "未知";
    }
}
