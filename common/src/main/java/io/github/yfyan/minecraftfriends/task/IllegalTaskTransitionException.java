package io.github.yfyan.minecraftfriends.task;

public final class IllegalTaskTransitionException extends IllegalStateException {
    public IllegalTaskTransitionException(TaskState from, TaskState to) {
        super("Cannot transition task from " + from + " to " + to);
    }
}
