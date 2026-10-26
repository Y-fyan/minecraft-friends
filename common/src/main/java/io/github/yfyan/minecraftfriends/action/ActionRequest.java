package io.github.yfyan.minecraftfriends.action;

import java.util.Objects;
import java.util.UUID;

/** Immutable, typed action request. Authorization is checked again by the game adapter. */
public record ActionRequest(UUID taskId, UUID ownerId, ActionType type, BlockArea area, int requestedCount) {
    public ActionRequest {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(type, "type");
        if (type == ActionType.COLLECT_OAK_LOGS) {
            Objects.requireNonNull(area, "area");
            if (requestedCount < 1 || requestedCount > 64) {
                throw new IllegalArgumentException("Oak log count must be between 1 and 64.");
            }
        } else if (area != null || requestedCount != 0) {
            throw new IllegalArgumentException("Only collection actions accept an area and count.");
        }
    }

    public static ActionRequest follow(UUID taskId, UUID ownerId) {
        return new ActionRequest(taskId, ownerId, ActionType.FOLLOW, null, 0);
    }

    public static ActionRequest waitForOwner(UUID taskId, UUID ownerId) {
        return new ActionRequest(taskId, ownerId, ActionType.WAIT, null, 0);
    }

    public static ActionRequest collectOakLogs(UUID taskId, UUID ownerId, BlockArea area, int count) {
        return new ActionRequest(taskId, ownerId, ActionType.COLLECT_OAK_LOGS, area, count);
    }
}
