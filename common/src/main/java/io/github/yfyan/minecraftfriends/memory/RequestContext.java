package io.github.yfyan.minecraftfriends.memory;

import java.util.List;
import java.util.Objects;

/** Immutable prompt inputs captured together with the generation token. */
public record RequestContext(
        MemoryRequestToken token,
        PersonaSettings persona,
        List<MemoryEntry> memories,
        List<ContextMessage> shortTermContext) {
    public RequestContext {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(persona, "persona");
        memories = List.copyOf(memories);
        shortTermContext = List.copyOf(shortTermContext);
    }
}
