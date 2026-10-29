package io.github.yfyan.minecraftfriends.tree;

import io.github.yfyan.minecraftfriends.action.BlockArea;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.yfyan.minecraftfriends.tree.TreePlanner.Cell;
import static io.github.yfyan.minecraftfriends.tree.TreePlanner.Reason;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TreePlannerTest {
    private static final TreePosition BASE = new TreePosition(0, 64, 0);
    private static final BlockArea AREA = new BlockArea(-2, 64, -2, 2, 75, 2);

    @Test
    void selectsOnlyExactOakTrunkAndNeverAddsCanopyToTargets() {
        Map<TreePosition, Cell> tree = ordinaryTree(TreeSpecies.OAK, 5);
        TreePlanner.Inspection inspection = inspect(tree);
        TreePlan plan = inspection.plan().orElseThrow();

        assertEquals(Reason.CANDIDATE, inspection.reason());
        assertEquals(TreeSpecies.OAK, plan.species());
        assertEquals(BASE, plan.base());
        assertEquals(AREA, plan.authorizedArea());
        assertEquals(5, plan.logs().size());
        for (int index = 0; index < 5; index++) {
            assertEquals(BASE.offset(0, index, 0), plan.logs().get(index));
        }
        assertTrue(inspection.message().contains("无法证明天然来源"));
    }

    @Test
    void selectsBirchWithoutConvertingItsMaterialToOak() {
        TreePlan plan = inspect(ordinaryTree(TreeSpecies.BIRCH, 6)).plan().orElseThrow();
        assertEquals(TreeSpecies.BIRCH, plan.species());
        assertEquals("minecraft:birch_log", plan.species().logId());
        assertEquals("minecraft:birch_leaves", plan.species().leavesId());
        assertEquals("minecraft:birch_sapling", plan.species().saplingId());
    }

    @Test
    void boundedObservationReadsEveryCellAtMostOnce() {
        Map<TreePosition, Cell> tree = ordinaryTree(TreeSpecies.OAK, 5);
        Set<TreePosition> observed = new HashSet<>();
        AtomicInteger calls = new AtomicInteger();
        TreePlanner.Inspection result = TreePlanner.inspect(AREA, BASE, pos -> {
            calls.incrementAndGet();
            assertTrue(observed.add(pos), "duplicate observation " + pos);
            assertTrue(Math.abs(pos.x() - BASE.x()) <= 3);
            assertTrue(Math.abs(pos.z() - BASE.z()) <= 3);
            assertTrue(pos.y() >= BASE.y() - 1 && pos.y() <= BASE.y() + 10);
            return tree.getOrDefault(pos, Cell.AIR);
        });
        assertTrue(result.plan().isPresent());
        assertEquals(TreePlanner.MAX_OBSERVATIONS, calls.get());
        assertEquals(588, calls.get());
    }

    @Test
    void authorizationNeedOnlyCoverExactTargetsNotObservation() {
        Map<TreePosition, Cell> tree = ordinaryTree(TreeSpecies.OAK, 5);
        BlockArea exactTrunk = new BlockArea(0, 64, 0, 0, 68, 0);
        TreePlan plan = TreePlanner.inspect(exactTrunk, BASE, pos -> tree.getOrDefault(pos, Cell.AIR))
                .plan().orElseThrow();
        assertEquals(exactTrunk, plan.authorizedArea());
        assertEquals(5, plan.logs().size());
    }

    @Test
    void selectedBaseOutsideAuthorizationPerformsNoObservation() {
        AtomicInteger calls = new AtomicInteger();
        TreePlanner.Inspection result = TreePlanner.inspect(new BlockArea(5, 64, 0, 5, 68, 0), BASE, pos -> {
            calls.incrementAndGet();
            return Cell.AIR;
        });
        assertEquals(Reason.OUTSIDE_AUTHORIZATION, result.reason());
        assertEquals(0, calls.get());
    }

    @Test
    void rejectsTrunkExtendingAboveAuthorizationWithoutClippingIt() {
        Map<TreePosition, Cell> tree = ordinaryTree(TreeSpecies.OAK, 5);
        TreePlanner.Inspection result = TreePlanner.inspect(new BlockArea(0, 64, 0, 0, 67, 0), BASE,
                pos -> tree.getOrDefault(pos, Cell.AIR));
        assertEquals(Reason.OUTSIDE_AUTHORIZATION, result.reason());
        assertTrue(result.plan().isEmpty());
    }

    @Test
    void rejectsAdjacentDecorationEvenWhenItIsOutsideAuthorization() {
        Map<TreePosition, Cell> tree = ordinaryTree(TreeSpecies.OAK, 5);
        tree.put(BASE.offset(1, 1, 0), Cell.OAK_LOG);
        BlockArea trunkOnly = new BlockArea(0, 64, 0, 0, 68, 0);
        TreePlanner.Inspection result = TreePlanner.inspect(trunkOnly, BASE,
                pos -> tree.getOrDefault(pos, Cell.AIR));
        assertEquals(Reason.AMBIGUOUS_STRUCTURE, result.reason());
        assertTrue(result.plan().isEmpty());
    }

    @Test
    void refusesDiagonalConnectionAndMixedSpeciesLog() {
        Map<TreePosition, Cell> tree = ordinaryTree(TreeSpecies.OAK, 5);
        tree.put(BASE.offset(1, 2, 1), Cell.BIRCH_LOG);
        assertRefused(tree, Reason.AMBIGUOUS_STRUCTURE);
    }

    @Test
    void refusesLogBranchInsideCanopy() {
        Map<TreePosition, Cell> tree = ordinaryTree(TreeSpecies.OAK, 5);
        tree.put(BASE.offset(1, 4, 0), Cell.OAK_LOG);
        assertRefused(tree, Reason.AMBIGUOUS_STRUCTURE);
    }

    @Test
    void refusesNearbyWoodenHouseAndTreeHouse() {
        Map<TreePosition, Cell> nearbyHouse = ordinaryTree(TreeSpecies.OAK, 5);
        nearbyHouse.put(BASE.offset(3, 1, 0), Cell.OTHER);
        assertRefused(nearbyHouse, Reason.AMBIGUOUS_STRUCTURE);
        Map<TreePosition, Cell> treeHouse = ordinaryTree(TreeSpecies.OAK, 5);
        treeHouse.put(BASE.offset(1, 3, 1), Cell.OTHER);
        assertRefused(treeHouse, Reason.AMBIGUOUS_STRUCTURE);
    }

    @Test
    void refusesPersistentLeavesAndNonVerticalLogsRepresentedByAdapterAsOther() {
        Map<TreePosition, Cell> persistent = ordinaryTree(TreeSpecies.OAK, 5);
        persistent.put(BASE.offset(1, 3, 0), Cell.OTHER);
        assertRefused(persistent, Reason.AMBIGUOUS_STRUCTURE);
        Map<TreePosition, Cell> horizontal = ordinaryTree(TreeSpecies.OAK, 5);
        horizontal.put(BASE.offset(0, 2, 0), Cell.OTHER);
        assertRefused(horizontal, Reason.UNSUPPORTED_SHAPE);
    }

    @Test
    void refusesMixedCanopy() {
        Map<TreePosition, Cell> tree = ordinaryTree(TreeSpecies.OAK, 5);
        tree.put(BASE.offset(1, 3, 0), Cell.BIRCH_LEAVES);
        assertRefused(tree, Reason.AMBIGUOUS_STRUCTURE);
    }

    @Test
    void refusesCanopyAtObservationBoundary() {
        Map<TreePosition, Cell> tree = ordinaryTree(TreeSpecies.OAK, 5);
        tree.put(BASE.offset(3, 3, 0), Cell.OAK_LEAVES);
        assertRefused(tree, Reason.INCOMPLETE_CANOPY);
    }

    @Test
    void refusesDisconnectedLogsAboveTrunk() {
        Map<TreePosition, Cell> tree = ordinaryTree(TreeSpecies.OAK, 5);
        tree.put(BASE.offset(0, 8, 0), Cell.OAK_LOG);
        assertRefused(tree, Reason.AMBIGUOUS_STRUCTURE);
    }

    @Test
    void refusesMissingAndInsufficientCanopy() {
        Map<TreePosition, Cell> tree = bareTrunk(TreeSpecies.OAK, 5);
        assertRefused(tree, Reason.MISSING_CANOPY);
        tree.put(BASE.offset(0, 5, 0), Cell.OAK_LEAVES);
        assertRefused(tree, Reason.MISSING_CANOPY);
    }

    @Test
    void refusesOneSidedCanopyEvenWithManyLeaves() {
        Map<TreePosition, Cell> tree = bareTrunk(TreeSpecies.OAK, 5);
        for (int dx = 1; dx <= 2; dx++) {
            for (int dy = 3; dy <= 5; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    tree.put(BASE.offset(dx, dy, dz), Cell.OAK_LEAVES);
                }
            }
        }
        assertRefused(tree, Reason.MISSING_CANOPY);
    }

    @Test
    void refusesCanopyThatIsTooLowOrTooHigh() {
        Map<TreePosition, Cell> low = ordinaryTree(TreeSpecies.OAK, 5);
        low.put(BASE.offset(1, 0, 0), Cell.OAK_LEAVES);
        assertRefused(low, Reason.UNSUPPORTED_SHAPE);
        Map<TreePosition, Cell> high = ordinaryTree(TreeSpecies.OAK, 5);
        high.put(BASE.offset(1, 8, 0), Cell.OAK_LEAVES);
        assertRefused(high, Reason.UNSUPPORTED_SHAPE);
    }

    @Test
    void refusesShortAndTallTrunks() {
        assertRefused(ordinaryTree(TreeSpecies.OAK, 3), Reason.UNSUPPORTED_SHAPE);
        assertRefused(ordinaryTree(TreeSpecies.BIRCH, 9), Reason.UNSUPPORTED_SHAPE);
    }

    @Test
    void ordinaryMinimumAndMaximumHeightFitBoundedTargets() {
        assertEquals(4, inspect(ordinaryTree(TreeSpecies.OAK, 4)).plan().orElseThrow().logs().size());
        assertEquals(8, inspect(ordinaryTree(TreeSpecies.BIRCH, 8)).plan().orElseThrow().logs().size());
    }

    @Test
    void refusesWrongSoilAndSelectingMiddleOfTrunk() {
        Map<TreePosition, Cell> tree = ordinaryTree(TreeSpecies.OAK, 5);
        tree.put(BASE.offset(0, -1, 0), Cell.OTHER);
        assertRefused(tree, Reason.INVALID_BASE);
        tree.put(BASE.offset(0, -1, 0), Cell.OAK_LOG);
        assertRefused(tree, Reason.INVALID_BASE);
    }

    @Test
    void refusesNonLogSelectedBase() {
        Map<TreePosition, Cell> tree = ordinaryTree(TreeSpecies.OAK, 5);
        tree.put(BASE, Cell.OTHER);
        assertRefused(tree, Reason.NOT_SUPPORTED_TRUNK);
    }

    @Test
    void refusesUnloadedOrUnknownCellsWithoutFinishingTheScan() {
        AtomicInteger calls = new AtomicInteger();
        TreePlanner.Inspection unloaded = TreePlanner.inspect(AREA, BASE, pos -> {
            calls.incrementAndGet();
            return Cell.UNLOADED;
        });
        assertEquals(Reason.UNLOADED, unloaded.reason());
        assertEquals(1, calls.get());
        assertEquals(Reason.UNLOADED, TreePlanner.inspect(AREA, BASE, pos -> null).reason());
    }

    @Test
    void refusesUnloadedBoundaryEvenWhenTheTrunkIsFullyKnown() {
        Map<TreePosition, Cell> tree = ordinaryTree(TreeSpecies.OAK, 5);
        tree.put(BASE.offset(3, 10, 3), Cell.UNLOADED);
        assertRefused(tree, Reason.UNLOADED);
    }

    @Test
    void positionBoundsAreCheckedBeforeObservingWorld() {
        for (TreePosition edge : new TreePosition[]{
                new TreePosition(TreePosition.MAX_HORIZONTAL, 64, 0),
                new TreePosition(-TreePosition.MAX_HORIZONTAL, 64, 0),
                new TreePosition(0, TreePosition.MAX_HEIGHT, 0),
                new TreePosition(0, TreePosition.MIN_HEIGHT, 0),
                new TreePosition(0, 64, TreePosition.MAX_HORIZONTAL),
                new TreePosition(0, 64, -TreePosition.MAX_HORIZONTAL)}) {
            AtomicInteger calls = new AtomicInteger();
            BlockArea area = new BlockArea(edge.x(), edge.y(), edge.z(), edge.x(), edge.y(), edge.z());
            TreePlanner.Inspection result = TreePlanner.inspect(area, edge, pos -> {
                calls.incrementAndGet();
                return Cell.AIR;
            });
            assertEquals(Reason.OUT_OF_BOUNDS, result.reason());
            assertEquals(0, calls.get());
        }
    }

    @Test
    void planningDoesNotMutateSnapshotOrGrantAuthorityToOtherLogs() {
        Map<TreePosition, Cell> tree = ordinaryTree(TreeSpecies.OAK, 5);
        Map<TreePosition, Cell> before = Map.copyOf(tree);
        TreePlan first = inspect(tree).plan().orElseThrow();
        assertEquals(before, tree);
        tree.put(BASE.offset(1, 1, 0), Cell.OAK_LOG);
        assertTrue(inspect(tree).plan().isEmpty());
        assertEquals(5, first.logs().size());
        assertFalse(first.logs().contains(BASE.offset(1, 1, 0)));
    }

    private static TreePlanner.Inspection inspect(Map<TreePosition, Cell> tree) {
        return TreePlanner.inspect(AREA, BASE, pos -> tree.getOrDefault(pos, Cell.AIR));
    }

    private static void assertRefused(Map<TreePosition, Cell> tree, Reason reason) {
        TreePlanner.Inspection result = inspect(tree);
        assertEquals(reason, result.reason());
        assertTrue(result.plan().isEmpty());
    }

    private static Map<TreePosition, Cell> bareTrunk(TreeSpecies species, int height) {
        Map<TreePosition, Cell> tree = new HashMap<>();
        tree.put(BASE.offset(0, -1, 0), Cell.SOIL);
        Cell log = species == TreeSpecies.OAK ? Cell.OAK_LOG : Cell.BIRCH_LOG;
        for (int dy = 0; dy < height; dy++) {
            tree.put(BASE.offset(0, dy, 0), log);
        }
        return tree;
    }

    private static Map<TreePosition, Cell> ordinaryTree(TreeSpecies species, int height) {
        Map<TreePosition, Cell> tree = bareTrunk(species, height);
        Cell leaves = species == TreeSpecies.OAK ? Cell.OAK_LEAVES : Cell.BIRCH_LEAVES;
        // Deterministic ordinary canopy: the planner must not depend on a random world seed.
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx != 0 || dz != 0) {
                    tree.put(BASE.offset(dx, height - 2, dz), leaves);
                }
            }
        }
        tree.put(BASE.offset(0, height, 0), leaves);
        return tree;
    }
}
