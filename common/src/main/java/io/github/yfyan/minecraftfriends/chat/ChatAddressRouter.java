package io.github.yfyan.minecraftfriends.chat;

import java.util.List;
import java.util.Objects;

/** Resolves prefix matches while keeping an owner's default address private. */
public final class ChatAddressRouter {
    private ChatAddressRouter() {
    }

    public static <T> List<T> preferOwnedPrivate(T ownedPrivate, List<T> joinedSharedMatches) {
        Objects.requireNonNull(joinedSharedMatches, "joinedSharedMatches");
        return ownedPrivate == null ? List.copyOf(joinedSharedMatches) : List.of(ownedPrivate);
    }
}
