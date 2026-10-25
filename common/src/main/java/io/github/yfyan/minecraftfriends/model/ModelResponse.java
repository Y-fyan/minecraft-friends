package io.github.yfyan.minecraftfriends.model;

import java.util.Objects;

public record ModelResponse(String content) {
    public ModelResponse {
        Objects.requireNonNull(content, "content");
    }
}
