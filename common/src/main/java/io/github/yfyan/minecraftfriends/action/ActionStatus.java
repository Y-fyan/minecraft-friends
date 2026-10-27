package io.github.yfyan.minecraftfriends.action;

import io.github.yfyan.minecraftfriends.task.TaskState;

import java.util.Objects;
import java.util.UUID;

/** Immutable status snapshot; reading it never changes task or world state. */
public record ActionStatus(
        UUID taskId,
        ActionType type,
        TaskState state,
        int completed,
        int requested,
        int delivered,
        String message) {

    public ActionStatus {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(message, "message");
        if (completed < 0 || requested < 0 || delivered < 0 || delivered > completed) {
            throw new IllegalArgumentException("Action counters are inconsistent.");
        }
    }
}
