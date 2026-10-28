package io.github.yfyan.minecraftfriends.action;

import io.github.yfyan.minecraftfriends.task.TaskState;

import java.util.Objects;
import java.util.UUID;

/** Executor-confirmed tree outcome. Harvested, picked up and delivered are never interchangeable. */
public record TreeTaskStatus(UUID taskId, TaskState state, int planned, int harvested, int pickedUp,
                             int delivered, Replant replant, String message) {
    public TreeTaskStatus {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(replant, "replant");
        Objects.requireNonNull(message, "message");
        if (planned < 1 || harvested < 0 || harvested > planned || pickedUp < 0
                || pickedUp > harvested || delivered < 0 || delivered > pickedUp) {
            throw new IllegalArgumentException("Tree counters are inconsistent.");
        }
    }

    public enum Replant {
        PENDING, DISABLED, PLANTED, NO_SAPLING, BLOCKED, UNREACHABLE, PARTIAL_TREE, CANCELLED
    }
}
