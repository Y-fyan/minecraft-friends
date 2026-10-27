package io.github.yfyan.minecraftfriends.action;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable server-thread capture; contains no live world, entity or container references. */
public record ActionPerception(UUID worldSession, UUID ownerId, UUID companionId, String dimension,
                               long capturedTick, Position ownerPosition, Position companionPosition,
                               float ownerHealth, float companionHealth, List<Block> blocks,
                               List<InventorySlot> inventory, BlockArea authorizedArea) {
    public static final int MAX_BLOCKS = 245;
    public static final int MAX_INVENTORY_SLOTS = 41;

    public ActionPerception {
        Objects.requireNonNull(worldSession, "worldSession");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(companionId, "companionId");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(ownerPosition, "ownerPosition");
        Objects.requireNonNull(companionPosition, "companionPosition");
        blocks = List.copyOf(blocks);
        inventory = List.copyOf(inventory);
        if (dimension.length() > 256 || blocks.size() > MAX_BLOCKS
                || inventory.size() > MAX_INVENTORY_SLOTS
                || !Float.isFinite(ownerHealth) || !Float.isFinite(companionHealth)
                || ownerHealth < 0 || companionHealth < 0) {
            throw new IllegalArgumentException("Perception exceeds its bounds.");
        }
    }

    public record Position(double x, double y, double z) {
        public Position {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("Invalid position.");
            }
        }
    }

    public record Block(int x, int y, int z, String blockId) {
        public Block {
            Objects.requireNonNull(blockId, "blockId");
            if (blockId.length() > 256) {
                throw new IllegalArgumentException("Block ID too long.");
            }
        }
    }

    public record InventorySlot(int slot, String itemId, int count) {
        public InventorySlot {
            Objects.requireNonNull(itemId, "itemId");
            if (slot < 0 || slot >= MAX_INVENTORY_SLOTS || count < 0 || count > 99
                    || itemId.length() > 256) {
                throw new IllegalArgumentException("Invalid inventory sample.");
            }
        }
    }
}
