package io.github.yfyan.minecraftfriends.action;

import io.github.yfyan.minecraftfriends.tree.TreePlan;

import java.util.Objects;
import java.util.UUID;

/** Immutable, typed action request. Authorization is checked again by the game adapter. */
public record ActionRequest(UUID taskId, UUID ownerId, ActionType type, BlockArea area, int requestedCount,
                            TreePlan treePlan, boolean replant) {
    public ActionRequest {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(type, "type");
        if (type == ActionType.NONE || type == ActionType.STOP) {
            throw new IllegalArgumentException("Idle and stop are scheduler controls, not queued actions.");
        }
        if (type == ActionType.FELL_TREE) {
            Objects.requireNonNull(treePlan, "treePlan");
            if (!treePlan.authorizedArea().equals(area) || requestedCount != treePlan.logs().size()) {
                throw new IllegalArgumentException("Tree request must use exactly its confirmed plan.");
            }
        } else if (type == ActionType.COLLECT_OAK_LOGS) {
            Objects.requireNonNull(area, "area");
            if (requestedCount < 1 || requestedCount > 64) {
                throw new IllegalArgumentException("Oak log count must be between 1 and 64.");
            }
        } else if (area != null || requestedCount != 0) {
            throw new IllegalArgumentException("Only collection actions accept an area and count.");
        }
        if (type != ActionType.FELL_TREE && (treePlan != null || replant)) {
            throw new IllegalArgumentException("Only tree tasks accept a tree plan or replanting.");
        }
    }

    public ActionRequest(UUID taskId, UUID ownerId, ActionType type, BlockArea area, int requestedCount) {
        this(taskId, ownerId, type, area, requestedCount, null, false);
    }

    public static ActionRequest fellTree(UUID taskId, UUID ownerId, TreePlan plan, boolean replant) {
        Objects.requireNonNull(plan, "plan");
        return new ActionRequest(taskId, ownerId, ActionType.FELL_TREE, plan.authorizedArea(),
                plan.logs().size(), plan, replant);
    }

    public static ActionRequest follow(UUID taskId, UUID ownerId) {
        return new ActionRequest(taskId, ownerId, ActionType.FOLLOW, null, 0);
    }

    public static ActionRequest waitForOwner(UUID taskId, UUID ownerId) {
        return new ActionRequest(taskId, ownerId, ActionType.WAIT, null, 0);
    }

    public static ActionRequest collectOakLogs(UUID taskId, UUID ownerId, BlockArea area, int count) {
        return new ActionRequest(taskId, ownerId, ActionType.COLLECT_OAK_LOGS, area, count);
    }
}
