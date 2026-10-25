package io.github.yfyan.minecraftfriends.companion;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompanionIdentityTest {
    @Test
    void acceptsIndependentStableIdentity() {
        UUID owner = UUID.randomUUID();
        assertDoesNotThrow(() -> new CompanionIdentity(UUID.randomUUID(), owner, "MF_Ada"));
    }

    @Test
    void rejectsOwnerIdentityReuse() {
        UUID owner = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new CompanionIdentity(owner, owner, "MF_Ada"));
    }

    @Test
    void rejectsNamesThatCouldBreakProfileAndCommandRules() {
        UUID owner = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,
                () -> new CompanionIdentity(UUID.randomUUID(), owner, "Ada Friend"));
        assertThrows(IllegalArgumentException.class,
                () -> new CompanionIdentity(UUID.randomUUID(), owner, "a-name-longer-than-16"));
    }
}
