package io.github.yfyan.minecraftfriends.companion;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Stable ownership identity for one server-side companion. */
public record CompanionIdentity(UUID companionId, UUID ownerId, String name) {
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    public CompanionIdentity {
        Objects.requireNonNull(companionId, "companionId");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(name, "name");
        if (companionId.equals(ownerId)) {
            throw new IllegalArgumentException("A companion must not share its owner's UUID.");
        }
        if (!VALID_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Companion names must be 1-16 ASCII letters, digits, or underscores.");
        }
    }
}
