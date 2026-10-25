package io.github.yfyan.minecraftfriends.model;

import java.util.Objects;

public record ChatMessage(ChatRole role, String content) {
    public ChatMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
    }
}
