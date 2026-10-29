package io.github.yfyan.minecraftfriends.tree;

import io.github.yfyan.minecraftfriends.action.BlockArea;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded, read-only candidate recognition for an explicitly selected ordinary trunk base.
 * This is deliberately a conservative shape heuristic, not proof of natural provenance.
 * Even an accepted candidate needs the owner's confirmation of its exact target list.
 * The adapter must not load chunks and must classify persistent leaves/non-vertical logs as OTHER.
 */
public final class TreePlanner {
    public static final int OBSERVATION_RADIUS = 3;
    public static final int OBSERVATION_MIN_DY = -1;
    public static final int OBSERVATION_MAX_DY = 10;
    public static final int MAX_OBSERVATIONS = 588;
    public static final int MIN_CANOPY_LEAVES = 12;

    private TreePlanner() {
    }

    /** Reads at most 588 cells synchronously; never mutates the view or broadens authorization. */
    public static Inspection inspect(BlockArea authorization, TreePosition selectedBase, WorldView view) {
        Objects.requireNonNull(authorization, "authorization");
        Objects.requireNonNull(selectedBase, "selectedBase");
        Objects.requireNonNull(view, "view");
        if (!authorization.contains(selectedBase.x(), selectedBase.y(), selectedBase.z())) {
            return refused(Reason.OUTSIDE_AUTHORIZATION, "所选树干基部不在授权区域内；未选择任何方块。");
        }
        if (!observationFits(selectedBase)) {
            return refused(Reason.OUT_OF_BOUNDS, "树木观测范围越界；未选择任何方块。");
        }
        Cell[][][] cells = new Cell[7][12][7];
        for (int dx = -OBSERVATION_RADIUS; dx <= OBSERVATION_RADIUS; dx++) {
            for (int dy = OBSERVATION_MIN_DY; dy <= OBSERVATION_MAX_DY; dy++) {
                for (int dz = -OBSERVATION_RADIUS; dz <= OBSERVATION_RADIUS; dz++) {
                    Cell cell = view.cell(selectedBase.offset(dx, dy, dz));
                    if (cell == null || cell == Cell.UNLOADED) {
                        return refused(Reason.UNLOADED, "树木周围观测不完整；不会加载区块或猜测隐藏结构。");
                    }
                    cells[dx + OBSERVATION_RADIUS][dy - OBSERVATION_MIN_DY][dz + OBSERVATION_RADIUS] = cell;
                }
            }
        }
        Cell root = at(cells, 0, 0, 0);
        TreeSpecies species;
        Cell log;
        Cell leaves;
        if (root == Cell.OAK_LOG) {
            species = TreeSpecies.OAK;
            log = Cell.OAK_LOG;
            leaves = Cell.OAK_LEAVES;
        } else if (root == Cell.BIRCH_LOG) {
            species = TreeSpecies.BIRCH;
            log = Cell.BIRCH_LOG;
            leaves = Cell.BIRCH_LEAVES;
        } else {
            return refused(Reason.NOT_SUPPORTED_TRUNK, "请选择普通橡树或白桦树最底部的竖直原木。");
        }
        if (at(cells, 0, -1, 0) != Cell.SOIL) {
            return refused(Reason.INVALID_BASE, "所选原木下方不是可补种土壤；可能不是树干基部。");
        }
        int height = 0;
        while (height <= OBSERVATION_MAX_DY && at(cells, 0, height, 0) == log) {
            height++;
        }
        if (height < TreePlan.MIN_LOGS || height > TreePlan.MAX_LOGS) {
            return refused(Reason.UNSUPPORTED_SHAPE, "仅支持连续 4 至 8 格的普通直树干；矮堆、巨树或复杂树形需要更明确的方案。");
        }
        List<TreePosition> targets = new ArrayList<>(height);
        for (int dy = 0; dy < height; dy++) {
            TreePosition target = selectedBase.offset(0, dy, 0);
            if (!authorization.contains(target.x(), target.y(), target.z())) {
                return refused(Reason.OUTSIDE_AUTHORIZATION, "完整树干超出授权区域；不会把邻接原木自动加入任务。");
            }
            targets.add(target);
        }
        int leafCount = 0;
        boolean east = false;
        boolean west = false;
        boolean north = false;
        boolean south = false;
        boolean above = false;
        for (int dx = -OBSERVATION_RADIUS; dx <= OBSERVATION_RADIUS; dx++) {
            for (int dy = 0; dy <= OBSERVATION_MAX_DY; dy++) {
                for (int dz = -OBSERVATION_RADIUS; dz <= OBSERVATION_RADIUS; dz++) {
                    Cell cell = at(cells, dx, dy, dz);
                    boolean target = dx == 0 && dz == 0 && dy < height;
                    if (target) {
                        continue;
                    }
                    if (cell == Cell.OAK_LOG || cell == Cell.BIRCH_LOG) {
                        return refused(Reason.AMBIGUOUS_STRUCTURE, "树旁存在额外原木或分叉；可能连接木屋、装饰或另一棵树，请重新明确目标。");
                    }
                    if (cell != Cell.AIR && cell != leaves) {
                        return refused(Reason.AMBIGUOUS_STRUCTURE, "树旁存在其他结构、混合树冠或人工树叶；无法安全确认普通天然树。");
                    }
                    if (cell == leaves) {
                        if (Math.abs(dx) == OBSERVATION_RADIUS || Math.abs(dz) == OBSERVATION_RADIUS
                                || dy == OBSERVATION_MAX_DY) {
                            return refused(Reason.INCOMPLETE_CANOPY, "树冠延伸到观测边界；请明确较独立的普通树目标。");
                        }
                        if (dy < height - 3 || dy > height + 2) {
                            return refused(Reason.UNSUPPORTED_SHAPE, "树叶位置不符合当前支持的普通树冠范围。");
                        }
                        leafCount++;
                        east |= dx > 0;
                        west |= dx < 0;
                        north |= dz < 0;
                        south |= dz > 0;
                        above |= dy >= height;
                    }
                }
            }
        }
        if (leafCount < MIN_CANOPY_LEAVES || !east || !west || !north || !south || !above) {
            return refused(Reason.MISSING_CANOPY, "树冠证据不足；不能仅凭原木认定是天然树，请明确目标。");
        }
        TreePlan plan = new TreePlan(species, selectedBase, authorization, targets);
        return new Inspection(Optional.of(plan), Reason.CANDIDATE,
                "发现候选普通树，但无法证明天然来源；请核对并确认这 " + targets.size() + " 个原木，附近其他方块不获授权。");
    }

    private static boolean observationFits(TreePosition base) {
        return base.x() >= -TreePosition.MAX_HORIZONTAL + OBSERVATION_RADIUS
                && base.x() <= TreePosition.MAX_HORIZONTAL - OBSERVATION_RADIUS
                && base.z() >= -TreePosition.MAX_HORIZONTAL + OBSERVATION_RADIUS
                && base.z() <= TreePosition.MAX_HORIZONTAL - OBSERVATION_RADIUS
                && base.y() >= TreePosition.MIN_HEIGHT - OBSERVATION_MIN_DY
                && base.y() <= TreePosition.MAX_HEIGHT - OBSERVATION_MAX_DY;
    }

    private static Cell at(Cell[][][] cells, int dx, int dy, int dz) {
        return cells[dx + OBSERVATION_RADIUS][dy - OBSERVATION_MIN_DY][dz + OBSERVATION_RADIUS];
    }

    private static Inspection refused(Reason reason, String message) {
        return new Inspection(Optional.empty(), reason, message);
    }

    @FunctionalInterface
    public interface WorldView {
        /** Return UNLOADED rather than loading chunks or reading beyond the available snapshot. */
        Cell cell(TreePosition position);
    }

    public enum Cell {
        AIR, OAK_LOG, BIRCH_LOG, OAK_LEAVES, BIRCH_LEAVES, SOIL, OTHER, UNLOADED
    }

    public enum Reason {
        CANDIDATE, OUTSIDE_AUTHORIZATION, OUT_OF_BOUNDS, UNLOADED, NOT_SUPPORTED_TRUNK,
        INVALID_BASE, UNSUPPORTED_SHAPE, AMBIGUOUS_STRUCTURE, INCOMPLETE_CANOPY, MISSING_CANOPY
    }

    public record Inspection(Optional<TreePlan> plan, Reason reason, String message) {
        public Inspection {
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(message, "message");
            if (plan.isPresent() != (reason == Reason.CANDIDATE)) {
                throw new IllegalArgumentException("Only candidate inspections may contain a plan.");
            }
        }
    }
}
