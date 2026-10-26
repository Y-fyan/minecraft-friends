package io.github.yfyan.minecraftfriends.action;

import io.github.yfyan.minecraftfriends.task.TaskState;
import io.github.yfyan.minecraftfriends.task.TaskStateMachine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;

/** Bounded per-owner FIFO scheduler. Callers serialize world work on the server thread. */
public final class ActionScheduler {
    public static final int MAX_QUEUED_PER_OWNER = 16;
    private static final int MAX_RETAINED_TASK_IDS = 256;

    private final Map<UUID, OwnerQueue> owners = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, TaskRecord> retained = new LinkedHashMap<>();

    public synchronized SubmitResult submit(ActionRequest request) {
        Objects.requireNonNull(request, "request");
        if (retained.containsKey(request.taskId())) {
            return SubmitResult.DUPLICATE;
        }
        trimRetained();
        if (retained.size() >= MAX_RETAINED_TASK_IDS) {
            return SubmitResult.FULL;
        }
        OwnerQueue queue = owners.computeIfAbsent(request.ownerId(), ignored -> new OwnerQueue());
        if (queue.pending.size() + (queue.active == null ? 0 : 1) >= MAX_QUEUED_PER_OWNER) {
            removeIfEmpty(request.ownerId(), queue);
            return SubmitResult.FULL;
        }
        TaskRecord task = new TaskRecord(request);
        retained.put(request.taskId(), task);
        queue.pending.addLast(task);
        trimRetained();
        return SubmitResult.ACCEPTED;
    }

    public synchronized Optional<ActionStatus> startNext(UUID ownerId) {
        OwnerQueue queue = owners.get(ownerId);
        if (queue == null || queue.active != null || queue.pending.isEmpty()) {
            return Optional.empty();
        }
        TaskRecord task = queue.pending.removeFirst();
        task.machine.transitionTo(TaskState.RUNNING);
        queue.active = task;
        task.message = "running";
        return Optional.of(task.snapshot());
    }

    public synchronized boolean updateProgress(UUID taskId, int completed, int delivered, String message) {
        Objects.requireNonNull(message, "message");
        TaskRecord task = retained.get(taskId);
        if (task == null || task.machine.state() != TaskState.RUNNING
                || completed < task.completed || delivered < task.delivered
                || delivered > completed || completed > task.request.requestedCount()
                && task.request.type() == ActionType.COLLECT_OAK_LOGS) {
            return false;
        }
        task.completed = completed;
        task.delivered = delivered;
        task.message = message;
        return true;
    }

    public synchronized boolean finish(UUID taskId, TaskState terminalState, int completed,
                                       int delivered, String message) {
        Objects.requireNonNull(terminalState, "terminalState");
        Objects.requireNonNull(message, "message");
        if (terminalState == TaskState.QUEUED || terminalState == TaskState.RUNNING) {
            throw new IllegalArgumentException("A finished action needs a terminal state.");
        }
        TaskRecord task = retained.get(taskId);
        if (task == null || !task.machine.canTransitionTo(terminalState)
                || completed < task.completed || delivered < task.delivered
                || delivered > completed || (task.request.type() == ActionType.COLLECT_OAK_LOGS
                && completed > task.request.requestedCount())) {
            return false;
        }
        task.machine.transitionTo(terminalState);
        task.completed = completed;
        task.delivered = delivered;
        task.message = message;
        OwnerQueue queue = owners.get(task.request.ownerId());
        if (queue != null && queue.active == task) {
            queue.active = null;
            removeIfEmpty(task.request.ownerId(), queue);
        }
        return true;
    }

    /** Cancels active and queued work immediately; returns IDs whose game inputs must be cleared. */
    public synchronized List<UUID> stop(UUID ownerId) {
        OwnerQueue queue = owners.remove(ownerId);
        if (queue == null) {
            return List.of();
        }
        List<UUID> cancelled = new ArrayList<>();
        if (queue.active != null) {
            cancel(queue.active, cancelled);
        }
        while (!queue.pending.isEmpty()) {
            cancel(queue.pending.removeFirst(), cancelled);
        }
        return List.copyOf(cancelled);
    }

    public synchronized Optional<ActionStatus> status(UUID ownerId) {
        OwnerQueue queue = owners.get(ownerId);
        if (queue != null && queue.active != null) {
            return Optional.of(queue.active.snapshot());
        }
        if (queue != null && !queue.pending.isEmpty()) {
            return Optional.of(queue.pending.peekFirst().snapshot());
        }
        return retained.values().stream()
                .filter(task -> task.request.ownerId().equals(ownerId))
                .reduce((first, second) -> second)
                .map(TaskRecord::snapshot);
    }

    private void cancel(TaskRecord task, List<UUID> cancelled) {
        if (task.machine.canTransitionTo(TaskState.CANCELLED)) {
            task.machine.transitionTo(TaskState.CANCELLED);
            task.message = "cancelled";
            cancelled.add(task.request.taskId());
        }
    }

    private void removeIfEmpty(UUID ownerId, OwnerQueue queue) {
        if (queue.active == null && queue.pending.isEmpty()) {
            owners.remove(ownerId, queue);
        }
    }

    private void trimRetained() {
        while (retained.size() > MAX_RETAINED_TASK_IDS) {
            UUID removable = retained.entrySet().stream()
                    .filter(entry -> entry.getValue().machine.state().isTerminal())
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse(null);
            if (removable == null) {
                return;
            }
            retained.remove(removable);
        }
    }

    public enum SubmitResult { ACCEPTED, DUPLICATE, FULL }

    private static final class OwnerQueue {
        private final ArrayDeque<TaskRecord> pending = new ArrayDeque<>();
        private TaskRecord active;
    }

    private static final class TaskRecord {
        private final ActionRequest request;
        private final TaskStateMachine machine = new TaskStateMachine();
        private int completed;
        private int delivered;
        private String message = "queued";

        private TaskRecord(ActionRequest request) {
            this.request = request;
        }

        private ActionStatus snapshot() {
            int requested = request.type() == ActionType.COLLECT_OAK_LOGS ? request.requestedCount() : 0;
            return new ActionStatus(request.taskId(), request.type(), machine.state(),
                    completed, requested, delivered, message);
        }
    }
}
