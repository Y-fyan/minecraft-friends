package io.github.yfyan.minecraftfriends.action;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Per-task, bounded vanilla door interaction; never changes door state directly. */
final class DoorInteractions {
    private static final int COOLDOWN_TICKS = 20;
    private static final int MAX_ATTEMPTS = 3;
    private BlockPos lastDoor;
    private int attempts;
    private long nextAttemptTick;

    static boolean isSupported(BlockState state) {
        return state.getBlock() instanceof DoorBlock door && state.is(BlockTags.WOODEN_DOORS)
                && door.type().canOpenByHand();
    }

    /** A* may propose a wooden doorway; normal collision and permission checks still decide execution. */
    static boolean canPlanThrough(ServerLevel level, BlockPos pos, BlockState state) {
        if (!isSupported(state)) return false;
        BlockPos other = state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
        if (!level.hasChunkAt(other) || !level.getWorldBorder().isWithinBounds(pos)
                || !level.getWorldBorder().isWithinBounds(other)) return false;
        BlockState second = level.getBlockState(other);
        return second.is(state.getBlock()) && second.getValue(DoorBlock.HALF) != state.getValue(DoorBlock.HALF)
                && second.getValue(DoorBlock.OPEN).equals(state.getValue(DoorBlock.OPEN));
    }

    Result approach(ServerPlayer owner, ServerPlayer companion, BlockPos waypoint, long tick) {
        ServerLevel level = companion.serverLevel();
        // At most four positions, only along the immediate waypoint/current body; no door scan.
        for (BlockPos pos : new BlockPos[]{waypoint, waypoint.above(), companion.blockPosition(), companion.blockPosition().above()}) {
            if (!level.hasChunkAt(pos)) return Result.DENIED;
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof DoorBlock)) continue;
            if (!canPlanThrough(level, pos, state)) return Result.DENIED;
            if (state.getValue(DoorBlock.OPEN)) continue; // Never toggle an already open door closed.
            BlockPos lower = state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER ? pos : pos.below();
            BlockPos upper = lower.above();
            if (!owner.isAlive() || !companion.isAlive() || owner.serverLevel() != level
                    || !level.mayInteract(owner, lower) || !level.mayInteract(owner, upper)
                    || !level.mayInteract(companion, lower) || !level.mayInteract(companion, upper)
                    || owner.distanceToSqr(companion) > 48.0 * 48.0
                    || owner.distanceToSqr(Vec3.atCenterOf(lower)) > 48.0 * 48.0
                    || companion.isSpectator()) return Result.DENIED;
            Vec3 eye = companion.getEyePosition();
            BlockState closed = level.getBlockState(lower);
            var shape = closed.getCollisionShape(level, lower);
            if (shape.isEmpty()) return Result.DENIED;
            Vec3 aim = shape.bounds().getCenter().add(lower.getX(), lower.getY(), lower.getZ());
            if (eye.distanceToSqr(aim) > Math.pow(Math.min(3.0, companion.blockInteractionRange()), 2))
                return Result.CLEAR; // Walk closer using normal travel, then revalidate before interacting.
            // clip() must not reach an unloaded intermediate chunk, even across a chunk corner.
            for (int x = (int) Math.floor(Math.min(eye.x, aim.x)); x <= (int) Math.floor(Math.max(eye.x, aim.x)); x++) {
                for (int z = (int) Math.floor(Math.min(eye.z, aim.z)); z <= (int) Math.floor(Math.max(eye.z, aim.z)); z++) {
                    if (!level.hasChunkAt(new BlockPos(x, lower.getY(), z))) return Result.DENIED;
                }
            }
            BlockHitResult hit = level.clip(new ClipContext(eye, aim, ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE, companion));
            if (hit.getType() != HitResult.Type.BLOCK
                    || !(hit.getBlockPos().equals(lower) || hit.getBlockPos().equals(upper))) return Result.DENIED;
            // In 1.21.1 useItemOn only invokes default useWithoutItem for MAIN_HAND.
            // Select a real empty hotbar slot; never pass a fabricated empty stack while holding an item.
            int previousSlot = companion.getInventory().selected;
            int emptySlot = companion.getMainHandItem().isEmpty() ? previousSlot : -1;
            for (int slot = 0; emptySlot < 0 && slot < 9; slot++) {
                if (companion.getInventory().getItem(slot).isEmpty()) emptySlot = slot;
            }
            if (emptySlot < 0 || !companion.canInteractWithBlock(hit.getBlockPos(), 0.0)
                    || !companion.mayUseItemAt(hit.getBlockPos(), hit.getDirection(), ItemStack.EMPTY)) return Result.DENIED;
            if (tick < nextAttemptTick) return Result.WAITING;
            if (!lower.equals(lastDoor)) {
                lastDoor = lower.immutable();
                attempts = 0;
            }
            if (attempts >= MAX_ATTEMPTS) return Result.DENIED;
            attempts++;
            nextAttemptTick = tick + COOLDOWN_TICKS;
            // Empty main hand avoids item placement/use fallback when a mod denies block interaction.
            try {
                companion.getInventory().selected = emptySlot;
                companion.gameMode.useItemOn(companion, level, companion.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
            } catch (RuntimeException refused) {
                return Result.DENIED;
            } finally {
                companion.getInventory().selected = previousSlot;
            }
            if (companion.serverLevel() != level || owner.serverLevel() != level
                    || !owner.isAlive() || !companion.isAlive()
                    || !level.hasChunkAt(lower) || !level.hasChunkAt(upper)) return Result.DENIED;
            BlockState after = level.getBlockState(lower);
            if (!after.is(closed.getBlock()) || !canPlanThrough(level, lower, after)) return Result.DENIED;
            return after.getValue(DoorBlock.OPEN) ? Result.CLEAR : Result.WAITING;
        }
        return Result.CLEAR;
    }

    enum Result { CLEAR, WAITING, DENIED }
}
