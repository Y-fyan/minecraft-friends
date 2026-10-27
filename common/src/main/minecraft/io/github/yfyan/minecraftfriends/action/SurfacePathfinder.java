package io.github.yfyan.minecraftfriends.action;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/** Bounded A* over loaded, walkable ground positions only. */
final class SurfacePathfinder {
    private static final int NODE_LIMIT = 512;
    private static final int SEARCH_RADIUS = 24;
    private static final int[][] HORIZONTAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private SurfacePathfinder() {
    }

    static List<BlockPos> find(ServerLevel level, BlockPos start, BlockPos target, int goalRadius) {
        BlockPos origin = start.immutable();
        PriorityQueue<Node> frontier = new PriorityQueue<>(Comparator.comparingInt(Node::score));
        Map<BlockPos, Integer> bestCost = new HashMap<>();
        frontier.add(new Node(origin, null, 0, heuristic(origin, target, goalRadius)));
        bestCost.put(origin, 0);

        int expanded = 0;
        while (!frontier.isEmpty() && expanded++ < NODE_LIMIT) {
            Node current = frontier.remove();
            if (current.cost() != bestCost.getOrDefault(current.position(), Integer.MAX_VALUE)) {
                continue;
            }
            if (isGoal(current.position(), target, goalRadius)) {
                return path(current);
            }
            for (int[] direction : HORIZONTAL) {
                int x = current.position().getX() + direction[0];
                int z = current.position().getZ() + direction[1];
                for (int dy : new int[]{1, 0, -1}) {
                    BlockPos next = new BlockPos(x, current.position().getY() + dy, z);
                    if (Math.abs(next.getX() - origin.getX()) > SEARCH_RADIUS
                            || Math.abs(next.getZ() - origin.getZ()) > SEARCH_RADIUS
                            || !canStand(level, next)) {
                        continue;
                    }
                    int nextCost = current.cost() + 10 + (dy == 0 ? 0 : 4);
                    if (nextCost >= bestCost.getOrDefault(next, Integer.MAX_VALUE)) {
                        continue;
                    }
                    bestCost.put(next, nextCost);
                    frontier.add(new Node(next, current, nextCost,
                            nextCost + heuristic(next, target, goalRadius)));
                }
            }
        }
        return List.of();
    }

    static boolean canStand(ServerLevel level, BlockPos feet) {
        BlockPos head = feet.above();
        BlockPos floor = feet.below();
        if (!level.hasChunkAt(feet) || !level.hasChunkAt(head) || !level.hasChunkAt(floor)
                || !level.getWorldBorder().isWithinBounds(feet) || !level.getWorldBorder().isWithinBounds(head)) {
            return false;
        }
        BlockState feetState = level.getBlockState(feet);
        BlockState headState = level.getBlockState(head);
        BlockState floorState = level.getBlockState(floor);
        return bodyPassable(level, feet, feetState)
                && bodyPassable(level, head, headState)
                && floorState.isFaceSturdy(level, floor, Direction.UP)
                && level.getFluidState(feet).isEmpty()
                && level.getFluidState(head).isEmpty();
    }

    private static boolean bodyPassable(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) {
            return DoorInteractions.canPlanThrough(level, pos, state);
        }
        return state.getCollisionShape(level, pos).isEmpty();
    }

    static boolean isGoal(BlockPos position, BlockPos target, int radius) {
        int dx = position.getX() - target.getX();
        int dz = position.getZ() - target.getZ();
        return dx * dx + dz * dz <= radius * radius
                && Math.abs(position.getY() - target.getY()) <= (radius == 0 ? 0 : 2);
    }

    private static int heuristic(BlockPos position, BlockPos target, int goalRadius) {
        int horizontal = Math.abs(position.getX() - target.getX())
                + Math.abs(position.getZ() - target.getZ());
        return Math.max(0, horizontal - goalRadius) * 10
                + Math.abs(position.getY() - target.getY()) * 10;
    }

    private static List<BlockPos> path(Node goal) {
        List<BlockPos> reversed = new ArrayList<>();
        for (Node node = goal; node.parent() != null; node = node.parent()) {
            reversed.add(node.position());
        }
        java.util.Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    private record Node(BlockPos position, Node parent, int cost, int score) {
    }
}
