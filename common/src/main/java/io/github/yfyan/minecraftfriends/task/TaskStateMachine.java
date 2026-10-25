package io.github.yfyan.minecraftfriends.task;

import java.util.Objects;

public final class TaskStateMachine {
    private TaskState state;

    public TaskStateMachine() {
        this(TaskState.QUEUED);
    }

    public TaskStateMachine(TaskState initialState) {
        state = Objects.requireNonNull(initialState, "initialState");
    }

    public synchronized TaskState state() {
        return state;
    }

    public synchronized boolean canTransitionTo(TaskState next) {
        Objects.requireNonNull(next, "next");
        return switch (state) {
            case QUEUED -> next == TaskState.RUNNING
                    || next == TaskState.FAILED
                    || next == TaskState.CANCELLED;
            case RUNNING -> next == TaskState.SUCCEEDED
                    || next == TaskState.FAILED
                    || next == TaskState.TIMED_OUT
                    || next == TaskState.CANCELLED;
            case SUCCEEDED, FAILED, TIMED_OUT, CANCELLED -> false;
        };
    }

    public synchronized void transitionTo(TaskState next) {
        Objects.requireNonNull(next, "next");
        if (!canTransitionTo(next)) {
            throw new IllegalTaskTransitionException(state, next);
        }
        state = next;
    }
}
