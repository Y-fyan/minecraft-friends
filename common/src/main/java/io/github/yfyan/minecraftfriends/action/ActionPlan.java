package io.github.yfyan.minecraftfriends.action;

import java.util.Objects;

/** Parsed model suggestion. The server must still authorize and validate execution. */
public record ActionPlan(ActionType type, int count) {
    public ActionPlan {
        Objects.requireNonNull(type, "type");
        if (type == ActionType.NONE || type == ActionType.FELL_TREE) {
            throw new IllegalArgumentException("This action requires a separate validated executor request.");
        }
        if (type == ActionType.COLLECT_OAK_LOGS) {
            if (count < 1 || count > 64) {
                throw new IllegalArgumentException("Oak log count must be between 1 and 64.");
            }
        } else if (count != 0) {
            throw new IllegalArgumentException("Only collection actions accept a count.");
        }
    }
}
