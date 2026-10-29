package io.github.yfyan.minecraftfriends.action;

import io.github.yfyan.minecraftfriends.capability.CapabilityResult;
import io.github.yfyan.minecraftfriends.task.TaskState;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CapabilityResultHistoryTest {
    private final ActionScheduler scheduler = new ActionScheduler();
    private final UUID owner = UUID.randomUUID();

    private UUID follow(UUID player) {
        UUID id = UUID.randomUUID();
        assertEquals(ActionScheduler.SubmitResult.ACCEPTED,
                scheduler.submit(ActionRequest.follow(id, player)));
        scheduler.rememberCapability(id, "follow");
        scheduler.startNext(player);
        return id;
    }

    private CapabilityResult immediate(String name) {
        return new CapabilityResult(UUID.randomUUID(), CapabilityResult.NO_TASK, name,
                name.equals("stop") ? TaskState.CANCELLED : TaskState.SUCCEEDED, 0, 0, 0, name);
    }

    @Test
    void observeAndStopSupersedeRetainedCancelledFollowAndNewFollowSupersedesBoth() {
        for (String name : new String[]{"observe", "stop"}) {
            UUID old = follow(owner);
            scheduler.stop(owner);
            CapabilityResult latest = immediate(name);
            scheduler.rememberResult(owner, latest);
            assertEquals(old, scheduler.status(owner).orElseThrow().taskId());
            assertEquals(latest, scheduler.capabilityResult(owner));
            UUID next = follow(owner);
            assertEquals(next, scheduler.capabilityResult(owner).callId());
            assertEquals(TaskState.RUNNING, scheduler.capabilityResult(owner).state());
            scheduler.stop(owner);
        }
    }

    @Test
    void anotherOwnersRepeatedReplacementsCannotEvictActiveCorrelation() {
        UUID active = follow(owner);
        UUID other = UUID.randomUUID();
        for (int i = 0; i < 600; i++) {
            follow(other);
            scheduler.stop(other);
        }
        assertEquals(active, scheduler.capabilityResult(owner).callId());
        assertEquals("follow", scheduler.capabilityResult(owner).capability());
        assertEquals(TaskState.RUNNING, scheduler.capabilityResult(owner).state());
        scheduler.stop(owner);
        assertEquals(TaskState.CANCELLED, scheduler.capabilityResult(owner).state());
    }

    @Test
    void evictedImmediateResultCannotResurrectAnOlderTaskCall() {
        follow(owner);
        scheduler.stop(owner);
        scheduler.rememberResult(owner, immediate("observe"));
        for (int i = 0; i < 300; i++) {
            scheduler.rememberResult(UUID.randomUUID(), immediate("observe"));
        }
        assertEquals(CapabilityResult.idle(), scheduler.capabilityResult(owner));
    }

    @Test
    void taskResultsReflectRealProgressAndTerminalRetention() {
        UUID id = UUID.randomUUID();
        scheduler.submit(ActionRequest.collectOakLogs(id, owner, new BlockArea(0, 60, 0, 1, 61, 1), 4));
        scheduler.rememberCapability(id, "collect_oak_logs");
        scheduler.startNext(owner);
        scheduler.updateProgress(id, 3, 2, "returning");
        assertEquals(3, scheduler.capabilityResult(owner).completed());
        assertEquals(2, scheduler.capabilityResult(owner).delivered());
        scheduler.finish(id, TaskState.FAILED, 3, 2, "partial");
        assertEquals(TaskState.FAILED, scheduler.capabilityResult(owner).state());
        assertEquals("partial", scheduler.capabilityResult(owner).detail());
    }
}
