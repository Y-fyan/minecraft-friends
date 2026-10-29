package io.github.yfyan.minecraftfriends.tree;

import io.github.yfyan.minecraftfriends.action.BlockArea;

import java.util.List;
import java.util.Objects;

/**
 * Exact immutable candidate targets, not an authorization token. A server-side owner confirmation
 * and current-world revalidation are still required before executing this plan.
 */
public record TreePlan(TreeSpecies species, TreePosition base, BlockArea authorizedArea,
                       List<TreePosition> logs) {
    public static final int MIN_LOGS = 4;
    public static final int MAX_LOGS = 8;

    public TreePlan {
        Objects.requireNonNull(species, "species");
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(authorizedArea, "authorizedArea");
        logs = List.copyOf(Objects.requireNonNull(logs, "logs"));
        if (logs.size() < MIN_LOGS || logs.size() > MAX_LOGS) {
            throw new IllegalArgumentException("Only ordinary 4 to 8 log trunks are supported.");
        }
        for (int i = 0; i < logs.size(); i++) {
            TreePosition target = logs.get(i);
            if (!target.equals(base.offset(0, i, 0))) {
                throw new IllegalArgumentException("Targets must be the exact contiguous trunk, bottom to top.");
            }
            if (!authorizedArea.contains(target.x(), target.y(), target.z())) {
                throw new IllegalArgumentException("Every trunk target must be inside the selected area.");
            }
        }
    }
}
