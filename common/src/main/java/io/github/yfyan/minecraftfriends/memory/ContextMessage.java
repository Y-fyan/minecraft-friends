package io.github.yfyan.minecraftfriends.memory;

import java.time.Instant;
import java.util.Objects;

/** One short-term conversation turn. Context is held in memory only. */
public record ContextMessage(ContextSpeaker speaker, String text, Instant createdAt) {
    public static final int MAX_TEXT_CHARS = 1_200;

    public ContextMessage {
        Objects.requireNonNull(speaker, "speaker");
        Objects.requireNonNull(createdAt, "createdAt");
        text = MemoryEntry.requireText(text, MAX_TEXT_CHARS, "text");
    }
}
