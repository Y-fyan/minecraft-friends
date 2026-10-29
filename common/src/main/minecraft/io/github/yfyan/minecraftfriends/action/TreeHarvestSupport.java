package io.github.yfyan.minecraftfriends.action;

import io.github.yfyan.minecraftfriends.tree.TreePlanner;
import io.github.yfyan.minecraftfriends.tree.TreePosition;
import io.github.yfyan.minecraftfriends.tree.TreeSpecies;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Minecraft-only tree observations and real item interactions; no block mutations or synthetic drops. */
final class TreeHarvestSupport {
    private TreeHarvestSupport() { }

    static BlockPos pos(TreePosition position) {
        return new BlockPos(position.x(), position.y(), position.z());
    }

    static Block log(TreeSpecies species) {
        return species == TreeSpecies.OAK ? Blocks.OAK_LOG : Blocks.BIRCH_LOG;
    }

    static Item logItem(TreeSpecies species) {
        return species == TreeSpecies.OAK ? Items.OAK_LOG : Items.BIRCH_LOG;
    }

    static Item saplingItem(TreeSpecies species) {
        return species == TreeSpecies.OAK ? Items.OAK_SAPLING : Items.BIRCH_SAPLING;
    }

    static Block sapling(TreeSpecies species) {
        return species == TreeSpecies.OAK ? Blocks.OAK_SAPLING : Blocks.BIRCH_SAPLING;
    }

    static TreePlanner.Cell cell(BlockState state) {
        if (state.isAir()) return TreePlanner.Cell.AIR;
        if (state.is(Blocks.OAK_LOG) || state.is(Blocks.BIRCH_LOG)) {
            if (state.getValue(RotatedPillarBlock.AXIS) != Direction.Axis.Y) return TreePlanner.Cell.OTHER;
            return state.is(Blocks.OAK_LOG) ? TreePlanner.Cell.OAK_LOG : TreePlanner.Cell.BIRCH_LOG;
        }
        if (state.is(Blocks.OAK_LEAVES) || state.is(Blocks.BIRCH_LEAVES)) {
            if (state.getValue(LeavesBlock.PERSISTENT)) return TreePlanner.Cell.OTHER;
            return state.is(Blocks.OAK_LEAVES) ? TreePlanner.Cell.OAK_LEAVES : TreePlanner.Cell.BIRCH_LEAVES;
        }
        if (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.PODZOL) || state.is(Blocks.ROOTED_DIRT)) return TreePlanner.Cell.SOIL;
        // Ignore only these known non-structural plants, not arbitrary replaceable mod blocks.
        if (state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS) || state.is(Blocks.FERN)
                || state.is(Blocks.LARGE_FERN)) return TreePlanner.Cell.AIR;
        return TreePlanner.Cell.OTHER;
    }

    static boolean equipAxe(ServerPlayer companion) {
        return equip(companion, stack -> stack.getItem() instanceof AxeItem && !stack.isEmpty()
                && (!stack.isDamageableItem() || stack.getDamageValue() < stack.getMaxDamage()));
    }

    private static boolean equip(ServerPlayer companion, java.util.function.Predicate<ItemStack> predicate) {
        if (predicate.test(companion.getMainHandItem())) return true;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack found = companion.getInventory().getItem(slot);
            if (!predicate.test(found)) continue;
            if (slot < 9) {
                companion.getInventory().selected = slot;
            } else {
                int selected = companion.getInventory().selected;
                ItemStack held = companion.getInventory().getItem(selected);
                companion.getInventory().setItem(selected, found);
                companion.getInventory().setItem(slot, held);
            }
            companion.getInventory().setChanged();
            return true;
        }
        return false;
    }

    static boolean canMine(ServerPlayer owner, ServerPlayer companion, BlockPos target) {
        ServerLevel level = companion.serverLevel();
        return level.mayInteract(owner, target) && level.mayInteract(companion, target)
                && companion.canInteractWithBlock(target, 0.0)
                && visible(level, companion, companion.getEyePosition(), target, Vec3.atCenterOf(target));
    }

    /** Only nearby ground cells around the ORIGINAL trunk base; never plan ascent to floating logs. */
    static BlockPos approach(ServerPlayer companion, BlockPos base, BlockPos target, boolean planting) {
        ServerLevel level = companion.serverLevel();
        BlockPos best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        Vec3 aim = planting ? new Vec3(base.getX() + 0.5, base.getY() - 0.001, base.getZ() + 0.5)
                : Vec3.atCenterOf(target);
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos feet = base.offset(dx, dy, dz);
                    if (!SurfacePathfinder.canStand(level, feet)) continue;
                    Vec3 eye = new Vec3(feet.getX() + 0.5, feet.getY() + companion.getEyeHeight(), feet.getZ() + 0.5);
                    if (!visible(level, companion, eye, target, aim)) continue;
                    double distance = companion.distanceToSqr(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = feet;
                    }
                }
            }
        }
        return best;
    }

    private static boolean visible(ServerLevel level, ServerPlayer companion, Vec3 eye, BlockPos target, Vec3 aim) {
        double reach = Math.min(4.5, companion.blockInteractionRange());
        if (eye.distanceToSqr(aim) > reach * reach) return false;
        // clip() must never cause an intermediate chunk to load.
        for (int x = (int) Math.floor(Math.min(eye.x, aim.x)); x <= (int) Math.floor(Math.max(eye.x, aim.x)); x++) {
            for (int z = (int) Math.floor(Math.min(eye.z, aim.z)); z <= (int) Math.floor(Math.max(eye.z, aim.z)); z++) {
                if (!level.hasChunkAt(new BlockPos(x, target.getY(), z))) return false;
            }
        }
        BlockHitResult hit = level.clip(new ClipContext(eye, aim, ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, companion));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target);
    }

    static boolean hasSapling(ServerPlayer companion, TreeSpecies species) {
        for (int slot = 0; slot < 36; slot++) {
            if (companion.getInventory().getItem(slot).is(saplingItem(species))) return true;
        }
        return false;
    }

    static TreeTaskStatus.Replant plant(ServerPlayer owner, ServerPlayer companion, BlockPos base,
                                       BlockState originalSoil, TreeSpecies species) {
        ServerLevel level = companion.serverLevel();
        BlockPos soil = base.below();
        if (!companion.gameMode.isSurvival() || !level.hasChunkAt(base) || !level.hasChunkAt(soil)
                || !level.getWorldBorder().isWithinBounds(base) || !level.getWorldBorder().isWithinBounds(soil)
                || !level.getBlockState(base).isAir() || !level.getBlockState(soil).equals(originalSoil)
                || !sapling(species).defaultBlockState().canSurvive(level, base)
                || !level.mayInteract(owner, base) || !level.mayInteract(owner, soil)
                || !level.mayInteract(companion, base) || !level.mayInteract(companion, soil)) {
            return TreeTaskStatus.Replant.BLOCKED;
        }
        Vec3 aim = new Vec3(base.getX() + 0.5, base.getY() - 0.001, base.getZ() + 0.5);
        if (!companion.canInteractWithBlock(soil, 0.0)
                || !visible(level, companion, companion.getEyePosition(), soil, aim)) {
            return TreeTaskStatus.Replant.UNREACHABLE;
        }
        if (!equip(companion, stack -> stack.is(saplingItem(species)))) return TreeTaskStatus.Replant.NO_SAPLING;
        ItemStack held = companion.getMainHandItem();
        if (!companion.mayUseItemAt(base, Direction.UP, held)) return TreeTaskStatus.Replant.BLOCKED;
        int before = held.getCount();
        BlockHitResult hit = new BlockHitResult(new Vec3(base.getX() + 0.5, base.getY(), base.getZ() + 0.5),
                Direction.UP, soil, false);
        companion.gameMode.useItemOn(companion, level, held, InteractionHand.MAIN_HAND, hit);
        // Both world placement AND real consumption must be observed. No retry or manual replacement.
        return level.getBlockState(base).is(sapling(species)) && held.getCount() == before - 1
                ? TreeTaskStatus.Replant.PLANTED : TreeTaskStatus.Replant.BLOCKED;
    }
}
