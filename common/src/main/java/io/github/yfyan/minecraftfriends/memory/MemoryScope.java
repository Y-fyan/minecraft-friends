package io.github.yfyan.minecraftfriends.memory;

import java.util.Objects;
import java.util.UUID;

/** Stable identity boundary for one companion's memory. */
public record MemoryScope(UUID worldId, UUID ownerId, UUID companionId) {
    public MemoryScope {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(companionId, "companionId");
    }
}
