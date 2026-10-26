package io.github.yfyan.minecraftfriends.action;

import io.github.yfyan.minecraftfriends.task.TaskState;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionSchedulerTest {
    private final ActionScheduler scheduler = new ActionScheduler();
    private final UUID owner = UUID.randomUUID();

    @Test
    void rejectsDuplicateIdsAndBoundsThePerOwnerQueue() {
        UUID taskId = UUID.randomUUID();
        assertEquals(ActionScheduler.SubmitResult.ACCEPTED,
                scheduler.submit(ActionRequest.follow(taskId, owner)));
        assertEquals(ActionScheduler.SubmitResult.DUPLICATE,
                scheduler.submit(ActionRequest.follow(taskId, owner)));
        for (int i = 1; i < ActionScheduler.MAX_QUEUED_PER_OWNER; i++) {
            assertEquals(ActionScheduler.SubmitResult.ACCEPTED,
                    scheduler.submit(ActionRequest.follow(UUID.randomUUID(), owner)));
        }
        assertEquals(ActionScheduler.SubmitResult.FULL,
                scheduler.submit(ActionRequest.follow(UUID.randomUUID(), owner)));
    }

    @Test
    void startsFifoAndStatusIsAnImmutableSnapshot() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        scheduler.submit(ActionRequest.follow(first, owner));
        scheduler.submit(ActionRequest.waitForOwner(second, owner));

        ActionStatus started = scheduler.startNext(owner).orElseThrow();
        assertEquals(first, started.taskId());
        assertEquals(TaskState.RUNNING, started.state());
        assertEquals(TaskState.RUNNING, scheduler.status(owner).orElseThrow().state());
        assertEquals(second, scheduler.stop(owner).get(1));
        assertEquals(TaskState.CANCELLED, scheduler.status(owner).orElseThrow().state());
        assertEquals(TaskState.RUNNING, started.state());
    }

    @Test
    void stopCancelsActiveAndQueuedTasksAndRejectsLateCompletion() {
        UUID running = UUID.randomUUID();
        UUID queued = UUID.randomUUID();
        scheduler.submit(ActionRequest.follow(running, owner));
        scheduler.submit(ActionRequest.waitForOwner(queued, owner));
        scheduler.startNext(owner);

        assertEquals(2, scheduler.stop(owner).size());
        assertEquals(TaskState.CANCELLED, scheduler.status(owner).orElseThrow().state());
        assertFalse(scheduler.finish(running, TaskState.SUCCEEDED, 0, 0, "late"));
        assertTrue(scheduler.startNext(owner).isEmpty());
    }

    @Test
    void collectionIntentAndAreaAreTypeAndSizeBounded() {
        BlockArea area = new BlockArea(-1, 63, -1, 2, 64, 2);
        assertEquals(32, area.volume());
        assertTrue(area.contains(0, 64, 0));
        assertFalse(area.contains(3, 64, 0));
        scheduler.submit(ActionRequest.collectOakLogs(UUID.randomUUID(), owner, area, 8));

        assertEquals(ActionScheduler.SubmitResult.ACCEPTED,
                scheduler.submit(ActionRequest.follow(UUID.randomUUID(), owner)));
        assertThrows(IllegalArgumentException.class,
                () -> new BlockArea(Integer.MIN_VALUE, 0, 0, Integer.MAX_VALUE, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> ActionRequest.collectOakLogs(UUID.randomUUID(), owner, area, 65));
    }

    @Test
    void evictsOldestTerminalBeforeAcceptingMoreThanTheRetentionLimit() {
        for (int i = 0; i < 2 * ActionScheduler.MAX_RETAINED_TASK_IDS; i++) {
            UUID taskId = UUID.randomUUID();
            assertEquals(ActionScheduler.SubmitResult.ACCEPTED,
                    scheduler.submit(ActionRequest.follow(taskId, owner)));
            assertTrue(scheduler.startNext(owner).isPresent());
            assertTrue(scheduler.finish(taskId, TaskState.SUCCEEDED, 0, 0, "done"));
        }
        assertEquals(ActionScheduler.SubmitResult.ACCEPTED,
                scheduler.submit(ActionRequest.follow(UUID.randomUUID(), owner)));
    }

    @Test
    void keepsActiveTasksWhenRetentionIsFull() {
        UUID firstOwner = UUID.randomUUID();
        for (int i = 0; i < ActionScheduler.MAX_RETAINED_TASK_IDS; i++) {
            UUID taskId = UUID.randomUUID();
            UUID distinctOwner = i == 0 ? firstOwner : UUID.randomUUID();
            assertEquals(ActionScheduler.SubmitResult.ACCEPTED,
                    scheduler.submit(ActionRequest.follow(taskId, distinctOwner)));
            assertTrue(scheduler.startNext(distinctOwner).isPresent());
        }
        UUID overflowOwner = UUID.randomUUID();
        assertEquals(ActionScheduler.SubmitResult.FULL,
                scheduler.submit(ActionRequest.follow(UUID.randomUUID(), overflowOwner)));
        assertEquals(TaskState.RUNNING,
                scheduler.status(firstOwner).orElseThrow().state());
    }

    @Test
    void terminalQueuedTasksAreRemovedBeforeTheNextStart() {
        UUID queued = UUID.randomUUID();
        assertEquals(ActionScheduler.SubmitResult.ACCEPTED,
                scheduler.submit(ActionRequest.follow(queued, owner)));
        assertTrue(scheduler.finish(queued, TaskState.FAILED, 0, 0, "failed before start"));
        assertTrue(scheduler.startNext(owner).isEmpty());
        assertEquals(TaskState.FAILED, scheduler.status(owner).orElseThrow().state());
    }

}
