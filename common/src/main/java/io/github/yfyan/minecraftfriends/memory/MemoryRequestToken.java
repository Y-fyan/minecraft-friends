package io.github.yfyan.minecraftfriends.memory;

import java.util.UUID;

/** Opaque request epoch; only its originating store can accept it. */
public final class MemoryRequestToken {
    private final MemoryScope scope;
    private final UUID storeInstanceId;
    private final long generation;

    MemoryRequestToken(MemoryScope scope, UUID storeInstanceId, long generation) {
        this.scope = scope;
        this.storeInstanceId = storeInstanceId;
        this.generation = generation;
    }

    public long generation() {
        return generation;
    }

    MemoryScope scope() {
        return scope;
    }

    UUID storeInstanceId() {
        return storeInstanceId;
    }
}
