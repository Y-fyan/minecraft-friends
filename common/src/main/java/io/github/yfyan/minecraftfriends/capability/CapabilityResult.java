package io.github.yfyan.minecraftfriends.capability;

import io.github.yfyan.minecraftfriends.task.TaskState;

import java.util.Objects;
import java.util.UUID;

/**
 * Correlates one server-assigned call id with the real execution result. Counters come from the
 * executor, never from model text. Read-only calls have no task and use the idle task id.
 */
public record CapabilityResult(UUID callId, UUID taskId, String capability, TaskState state,
                               int completed, int requested, int delivered, String detail) {
    public static final UUID NO_TASK = new UUID(0L, 0L);
    public static final int MAX_DETAIL_CHARS = 512;

    public CapabilityResult {
        Objects.requireNonNull(callId, "callId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(detail, "detail");
        if (completed < 0 || requested < 0 || delivered < 0 || delivered > completed) {
            throw new IllegalArgumentException("Capability counters are inconsistent.");
        }
        if (capability.length() > 48 || detail.length() > MAX_DETAIL_CHARS) {
            throw new IllegalArgumentException("Capability result is too long.");
        }
    }

    public static CapabilityResult idle() {
        return new CapabilityResult(NO_TASK, NO_TASK, "none", TaskState.IDLE, 0, 0, 0, "idle");
    }
}
