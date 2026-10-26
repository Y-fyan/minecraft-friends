package io.github.yfyan.minecraftfriends.task;

public enum TaskState {
    IDLE(true),
    QUEUED(false),
    RUNNING(false),
    SUCCEEDED(true),
    FAILED(true),
    TIMED_OUT(true),
    CANCELLED(true);

    private final boolean terminal;

    TaskState(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }
}
