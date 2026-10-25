package io.github.yfyan.minecraftfriends.model;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ModelRequest(
        UUID requestId,
        List<ChatMessage> messages,
        Duration timeout
) {
    public ModelRequest {
        Objects.requireNonNull(requestId, "requestId");
        messages = List.copyOf(messages);
        Objects.requireNonNull(timeout, "timeout");
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }
}
