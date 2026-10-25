package io.github.yfyan.minecraftfriends.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskStateMachineTest {
    @Test
    void followsTheSuccessfulTaskPath() {
        TaskStateMachine machine = new TaskStateMachine();

        machine.transitionTo(TaskState.RUNNING);
        machine.transitionTo(TaskState.SUCCEEDED);

        assertEquals(TaskState.SUCCEEDED, machine.state());
        assertTrue(machine.state().isTerminal());
    }

    @Test
    void allowsQueuedTasksToBeCancelled() {
        TaskStateMachine machine = new TaskStateMachine();

        machine.transitionTo(TaskState.CANCELLED);

        assertTrue(machine.state().isTerminal());
    }

    @Test
    void lateResultsCannotReopenTerminalTasks() {
        TaskStateMachine machine = new TaskStateMachine(TaskState.CANCELLED);

        assertFalse(machine.canTransitionTo(TaskState.RUNNING));
        assertThrows(
                IllegalTaskTransitionException.class,
                () -> machine.transitionTo(TaskState.RUNNING)
        );
    }

    @Test
    void cannotSkipFromQueuedToSucceeded() {
        TaskStateMachine machine = new TaskStateMachine();

        assertThrows(
                IllegalTaskTransitionException.class,
                () -> machine.transitionTo(TaskState.SUCCEEDED)
        );
    }
}
