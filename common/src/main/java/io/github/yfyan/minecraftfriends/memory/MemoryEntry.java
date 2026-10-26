package io.github.yfyan.minecraftfriends.memory;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A bounded long-term fact with an explicit provenance. */
public record MemoryEntry(UUID id, MemoryKind kind, MemorySource source, String text, Instant createdAt) {
    public static final int MAX_TEXT_CHARS = 512;

    public MemoryEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(createdAt, "createdAt");
        text = requireText(text, MAX_TEXT_CHARS, "text");
        if ((kind == MemoryKind.PREFERENCE && source != MemorySource.USER_EXPLICIT)
                || (kind == MemoryKind.EVENT && source != MemorySource.VERIFIED_GAME_EVENT)) {
            throw new IllegalArgumentException("Memory kind and source do not match");
        }
    }

    static String requireText(String value, int maxChars, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (normalized.length() > maxChars) {
            throw new IllegalArgumentException(field + " exceeds " + maxChars + " characters");
        }
        return normalized;
    }
}
