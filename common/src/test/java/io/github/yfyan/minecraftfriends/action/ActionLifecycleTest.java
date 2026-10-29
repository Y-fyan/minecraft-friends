package io.github.yfyan.minecraftfriends.action;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionLifecycleTest {
    @Test
    void unrelatedPlayerAndEmptyRuntimeNeedNoCompanionLookup() {
        UUID ordinary = UUID.randomUUID();
        assertTrue(ActionLifecycle.affectedOwners(Map.<UUID, UUID>of(), value -> {
            throw new AssertionError("Empty/disabled runtime must not look up saved data");
        }, ordinary).isEmpty());
        assertTrue(ActionLifecycle.affectedOwners(Map.of(UUID.randomUUID(), UUID.randomUUID()),
                value -> value, ordinary).isEmpty());
    }

    @Test
    void cancelsOnlyTheAffectedOwnerOrCompanionIncludingPendingPreviews() {
        UUID owner = UUID.randomUUID();
        UUID companion = UUID.randomUUID();
        UUID otherOwner = UUID.randomUUID();
        Map<UUID, UUID> owners = new HashMap<>();
        owners.put(owner, companion);
        owners.put(otherOwner, UUID.randomUUID());
        assertEquals(java.util.List.of(owner), ActionLifecycle.affectedOwners(owners, value -> value, owner));
        var affected = ActionLifecycle.affectedOwners(owners, value -> value, companion);
        assertEquals(java.util.List.of(owner), affected);
        affected.forEach(owners::remove);
        assertEquals(1, owners.size());
        assertTrue(owners.containsKey(otherOwner));
        assertTrue(ActionLifecycle.affectedOwners(owners, value -> value, companion).isEmpty());
    }

    @Test
    void ownerCancellationDoesNotNeedACompanionIdentityOrStorage() {
        UUID owner = UUID.randomUUID();
        assertEquals(java.util.List.of(owner), ActionLifecycle.affectedOwners(Map.of(owner, owner), value -> {
            throw new AssertionError("Owner cancellation must use the in-memory owner key");
        }, owner));
    }
}
