package io.github.yfyan.minecraftfriends.action;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** Resolves cancellation from live action ownership, independently of saved companion data. */
public final class ActionLifecycle {
    private ActionLifecycle() {
    }

    /** Snapshot allows callers to remove affected owners while cancelling their tasks. */
    public static <T> List<UUID> affectedOwners(Map<UUID, T> owners,
                                               Function<T, UUID> companionId, UUID playerId) {
        return owners.entrySet().stream()
                .filter(entry -> entry.getKey().equals(playerId)
                        || playerId.equals(companionId.apply(entry.getValue())))
                .map(Map.Entry::getKey).toList();
    }
}
