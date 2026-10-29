package io.github.yfyan.minecraftfriends.action;

import io.github.yfyan.minecraftfriends.task.TaskState;
import io.github.yfyan.minecraftfriends.tree.TreePlan;
import io.github.yfyan.minecraftfriends.tree.TreePosition;
import io.github.yfyan.minecraftfriends.tree.TreeSpecies;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure scheduler/request regression tests, not evidence of Minecraft world execution. */
class TreeActionContractTest {
    private final UUID owner = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();
    private final ActionScheduler scheduler = new ActionScheduler();
    private final BlockArea area = new BlockArea(-2, 64, -2, 2, 71, 2);

    @Test
    void treeRequestUsesTheExactTypedPlanAndItsCount() {
        TreePlan plan = plan(TreeSpecies.OAK);
        ActionRequest request = ActionRequest.fellTree(taskId, owner, plan, true);

        assertEquals(taskId, request.taskId());
        assertEquals(owner, request.ownerId());
        assertEquals(ActionType.FELL_TREE, request.type());
        assertEquals(plan, request.treePlan());
        assertEquals(area, request.area());
        assertEquals(4, request.requestedCount());
        assertTrue(request.replant());
        assertFalse(ActionRequest.fellTree(UUID.randomUUID(), owner, plan, false).replant());
    }

    @Test
    void rejectsTreeRequestsWithoutAnExactPlanOrWithMismatchedAreaAndCount() {
        TreePlan plan = plan(TreeSpecies.BIRCH);
        assertThrows(NullPointerException.class,
                () -> new ActionRequest(taskId, owner, ActionType.FELL_TREE, area, 4, null, true));
        assertThrows(NullPointerException.class,
                () -> new ActionRequest(taskId, owner, ActionType.FELL_TREE, area, 4));
        assertThrows(IllegalArgumentException.class,
                () -> new ActionRequest(taskId, owner, ActionType.FELL_TREE, area, 3, plan, true));
        assertThrows(IllegalArgumentException.class,
                () -> new ActionRequest(taskId, owner, ActionType.FELL_TREE,
                        new BlockArea(-1, 64, -1, 1, 71, 1), 4, plan, true));
    }

    @Test
    void otherActionsCannotSmuggleTreePlansOrReplantAuthorization() {
        TreePlan plan = plan(TreeSpecies.OAK);
        assertThrows(IllegalArgumentException.class,
                () -> new ActionRequest(taskId, owner, ActionType.COLLECT_OAK_LOGS, area, 4, plan, false));
        assertThrows(IllegalArgumentException.class,
                () -> new ActionRequest(taskId, owner, ActionType.COLLECT_OAK_LOGS, area, 4, null, true));
        assertThrows(IllegalArgumentException.class,
                () -> new ActionRequest(taskId, owner, ActionType.FOLLOW, null, 0, plan, false));
        assertThrows(IllegalArgumentException.class,
                () -> new ActionRequest(taskId, owner, ActionType.WAIT, null, 0, null, true));
    }

    @Test
    void legacyRequestsRemainCompatibleButLegacyModelPlanCannotAuthorizeATree() {
        ActionRequest legacy = new ActionRequest(taskId, owner, ActionType.COLLECT_OAK_LOGS, area, 8);
        assertEquals(ActionRequest.collectOakLogs(taskId, owner, area, 8), legacy);
        assertEquals(8, legacy.requestedCount());
        assertFalse(legacy.replant());
        assertNull(legacy.treePlan());
        assertThrows(IllegalArgumentException.class, () -> new ActionPlan(ActionType.FELL_TREE, 0));
        assertThrows(IllegalArgumentException.class, () -> new ActionPlan(ActionType.FELL_TREE, 4));
    }

    @Test
    void schedulerReportsSelectedLogCountForBothSpecies() {
        for (TreeSpecies species : TreeSpecies.values()) {
            UUID id = UUID.randomUUID();
            assertEquals(ActionScheduler.SubmitResult.ACCEPTED,
                    scheduler.submit(ActionRequest.fellTree(id, owner, plan(species), true)));
            ActionStatus running = scheduler.startNext(owner).orElseThrow();
            assertEquals(ActionType.FELL_TREE, running.type());
            assertEquals(4, running.requested());
            assertEquals(0, running.completed());
            assertEquals(0, running.delivered());
            assertTrue(scheduler.finish(id, TaskState.SUCCEEDED, 4, 4, "真实执行器确认"));
        }
    }

    @Test
    void rejectsOverCountOrDeliveryWithoutActualPickup() {
        startTree();
        assertFalse(scheduler.updateProgress(taskId, 5, 0, "超过目标"));
        assertFalse(scheduler.updateProgress(taskId, 0, 1, "没有拾取"));
        assertFalse(scheduler.finish(taskId, TaskState.SUCCEEDED, 5, 4, "超过目标"));
        assertFalse(scheduler.finish(taskId, TaskState.SUCCEEDED, 2, 3, "交付超过拾取"));
        assertEquals(0, scheduler.status(owner).orElseThrow().completed());
        assertEquals(TaskState.RUNNING, scheduler.status(owner).orElseThrow().state());
    }

    @Test
    void pickupAndDeliveryAreMonotonicAndMayBePartial() {
        startTree();
        assertTrue(scheduler.updateProgress(taskId, 3, 0, "拾取三件"));
        ActionStatus beforeDelivery = scheduler.status(owner).orElseThrow();
        assertEquals(3, beforeDelivery.completed());
        assertEquals(0, beforeDelivery.delivered());
        assertTrue(scheduler.updateProgress(taskId, 3, 2, "主人仅接收两件"));
        assertFalse(scheduler.updateProgress(taskId, 2, 2, "不能回退拾取"));
        assertFalse(scheduler.updateProgress(taskId, 3, 1, "不能回退交付"));
        assertFalse(scheduler.finish(taskId, TaskState.FAILED, 2, 2, "不能回退拾取"));
        assertFalse(scheduler.finish(taskId, TaskState.FAILED, 3, 1, "不能回退交付"));
        assertTrue(scheduler.finish(taskId, TaskState.FAILED, 3, 2, "部分完成；剩余物品仍在伙伴背包"));
        ActionStatus finished = scheduler.status(owner).orElseThrow();
        assertEquals(3, finished.completed());
        assertEquals(2, finished.delivered());
        assertEquals(4, finished.requested());
        assertEquals(TaskState.FAILED, finished.state());
        assertEquals(0, beforeDelivery.delivered());
    }

    @Test
    void repeatedTaskIdsNeverReplaceActiveOrTerminalTreeWork() {
        assertFalse(scheduler.knownTask(taskId));
        startTree();
        assertTrue(scheduler.knownTask(taskId));
        assertEquals(ActionScheduler.SubmitResult.DUPLICATE,
                scheduler.submit(ActionRequest.fellTree(taskId, owner, plan(TreeSpecies.BIRCH), false)));
        assertEquals(TaskState.RUNNING, scheduler.status(owner).orElseThrow().state());
        assertTrue(scheduler.finish(taskId, TaskState.SUCCEEDED, 4, 4, "完成"));
        assertTrue(scheduler.knownTask(taskId));
        assertEquals(ActionScheduler.SubmitResult.DUPLICATE,
                scheduler.submit(ActionRequest.fellTree(taskId, owner, plan(TreeSpecies.OAK), true)));
        assertFalse(scheduler.updateProgress(taskId, 4, 4, "迟到更新"));
        assertFalse(scheduler.finish(taskId, TaskState.SUCCEEDED, 4, 4, "重复交付"));
        assertTrue(scheduler.startNext(owner).isEmpty());
    }

    @Test
    void stoppingPreservesObservedCountersAndRejectsLateWork() {
        startTree();
        assertTrue(scheduler.updateProgress(taskId, 2, 0, "已拾取；尚未交付"));
        assertEquals(List.of(taskId), scheduler.stop(owner));
        ActionStatus cancelled = scheduler.status(owner).orElseThrow();
        assertEquals(TaskState.CANCELLED, cancelled.state());
        assertEquals(2, cancelled.completed());
        assertEquals(0, cancelled.delivered());
        assertFalse(scheduler.updateProgress(taskId, 3, 0, "停止后拾取回调"));
        assertFalse(scheduler.finish(taskId, TaskState.SUCCEEDED, 4, 4, "停止后交付"));
        assertTrue(scheduler.startNext(owner).isEmpty());
        assertTrue(scheduler.stop(owner).isEmpty());
    }

    @Test
    void cancellingOneOwnerDoesNotCancelAnotherOwnersTree() {
        UUID otherOwner = UUID.randomUUID();
        UUID otherTask = UUID.randomUUID();
        startTree();
        scheduler.submit(ActionRequest.fellTree(otherTask, otherOwner, plan(TreeSpecies.BIRCH), true));
        scheduler.startNext(otherOwner);
        scheduler.stop(owner);

        assertEquals(TaskState.CANCELLED, scheduler.status(owner).orElseThrow().state());
        assertEquals(otherTask, scheduler.status(otherOwner).orElseThrow().taskId());
        assertEquals(TaskState.RUNNING, scheduler.status(otherOwner).orElseThrow().state());
        assertTrue(scheduler.updateProgress(otherTask, 4, 0, "另一主人仍正常执行"));
        assertTrue(scheduler.finish(otherTask, TaskState.SUCCEEDED, 4, 4, "另一主人收到物品"));
    }

    private void startTree() {
        assertEquals(ActionScheduler.SubmitResult.ACCEPTED,
                scheduler.submit(ActionRequest.fellTree(taskId, owner, plan(TreeSpecies.OAK), true)));
        assertEquals(taskId, scheduler.startNext(owner).orElseThrow().taskId());
    }

    private TreePlan plan(TreeSpecies species) {
        TreePosition base = new TreePosition(0, 64, 0);
        return new TreePlan(species, base, area, List.of(base,
                new TreePosition(0, 65, 0), new TreePosition(0, 66, 0), new TreePosition(0, 67, 0)));
    }
}
