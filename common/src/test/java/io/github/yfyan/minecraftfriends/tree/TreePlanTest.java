package io.github.yfyan.minecraftfriends.tree;

import io.github.yfyan.minecraftfriends.action.BlockArea;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TreePlanTest {
    private static final TreePosition BASE = new TreePosition(10, 64, 20);
    private static final BlockArea AREA = new BlockArea(10, 64, 20, 10, 71, 20);

    @Test
    void copiesTargetsAndRejectsMutationOfReturnedTargets() {
        List<TreePosition> input = trunk(4);
        TreePlan plan = plan(input);
        input.clear();
        assertEquals(4, plan.logs().size());
        assertThrows(UnsupportedOperationException.class, () -> plan.logs().clear());
    }

    @Test
    void rejectsEmptyShortAndOversizedTargets() {
        for (int size : new int[]{0, 1, 3, 9, 64}) {
            assertThrows(IllegalArgumentException.class, () -> plan(trunk(size)));
        }
    }

    @Test
    void rejectsDuplicateGappedReorderedAndBranchedTargets() {
        List<TreePosition> duplicate = trunk(4);
        duplicate.set(1, BASE);
        assertThrows(IllegalArgumentException.class, () -> plan(duplicate));
        List<TreePosition> gap = trunk(4);
        gap.set(3, BASE.offset(0, 4, 0));
        assertThrows(IllegalArgumentException.class, () -> plan(gap));
        List<TreePosition> reversed = trunk(4).reversed();
        assertThrows(IllegalArgumentException.class, () -> plan(reversed));
        List<TreePosition> branch = trunk(4);
        branch.set(3, BASE.offset(1, 3, 0));
        assertThrows(IllegalArgumentException.class, () -> plan(branch));
    }

    @Test
    void rejectsAnyTargetOutsideAuthorization() {
        BlockArea clipped = new BlockArea(10, 64, 20, 10, 66, 20);
        assertThrows(IllegalArgumentException.class,
                () -> new TreePlan(TreeSpecies.OAK, BASE, clipped, trunk(4)));
    }

    @Test
    void rejectsNullPlanInputsAndNullTarget() {
        assertThrows(NullPointerException.class, () -> new TreePlan(null, BASE, AREA, trunk(4)));
        assertThrows(NullPointerException.class, () -> new TreePlan(TreeSpecies.OAK, null, AREA, trunk(4)));
        assertThrows(NullPointerException.class, () -> new TreePlan(TreeSpecies.OAK, BASE, null, trunk(4)));
        assertThrows(NullPointerException.class, () -> plan(null));
        List<TreePosition> target = trunk(4);
        target.set(1, null);
        assertThrows(NullPointerException.class, () -> plan(target));
    }

    @Test
    void checksPositionsAndOffsetsWithoutIntegerWraparound() {
        assertThrows(IllegalArgumentException.class, () -> new TreePosition(Integer.MAX_VALUE, 64, 0));
        assertThrows(IllegalArgumentException.class, () -> new TreePosition(0, Integer.MIN_VALUE, 0));
        assertThrows(IllegalArgumentException.class, () -> new TreePosition(0, 64, -30_000_001));
        assertThrows(IllegalArgumentException.class, () -> new TreePosition(0, 2049, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new TreePosition(30_000_000, 64, 0).offset(1, 0, 0));
        assertThrows(ArithmeticException.class, () -> BASE.offset(Integer.MAX_VALUE, 0, 0));
    }

    @Test
    void candidateAndRefusalResultsCannotContradictTheirPlan() {
        assertThrows(IllegalArgumentException.class,
                () -> new TreePlanner.Inspection(Optional.empty(), TreePlanner.Reason.CANDIDATE, "wrong"));
        assertThrows(IllegalArgumentException.class,
                () -> new TreePlanner.Inspection(Optional.of(plan(trunk(4))), TreePlanner.Reason.UNLOADED, "wrong"));
    }

    @Test
    void bothSpeciesHaveExactVanillaMaterials() {
        assertEquals("minecraft:oak_log", TreeSpecies.OAK.logId());
        assertEquals("minecraft:oak_leaves", TreeSpecies.OAK.leavesId());
        assertEquals("minecraft:oak_sapling", TreeSpecies.OAK.saplingId());
        assertEquals("minecraft:birch_log", TreeSpecies.BIRCH.logId());
        assertEquals("minecraft:birch_leaves", TreeSpecies.BIRCH.leavesId());
        assertEquals("minecraft:birch_sapling", TreeSpecies.BIRCH.saplingId());
    }

    private static TreePlan plan(List<TreePosition> logs) {
        return new TreePlan(TreeSpecies.OAK, BASE, AREA, logs);
    }

    private static List<TreePosition> trunk(int height) {
        List<TreePosition> logs = new ArrayList<>();
        for (int dy = 0; dy < height; dy++) {
            logs.add(BASE.offset(0, dy, 0));
        }
        return logs;
    }
}
