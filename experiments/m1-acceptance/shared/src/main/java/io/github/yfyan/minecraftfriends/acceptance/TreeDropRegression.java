package io.github.yfyan.minecraftfriends.acceptance;

import io.github.yfyan.minecraftfriends.action.TreeDropAccounting;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Standalone vanilla stack regression; no server, world, player, EULA or model request. */
public final class TreeDropRegression {
    private TreeDropRegression() {
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        verify();
    }

    /** Also runs from TreeScenario after the actual loader has initialized its game registries. */
    public static void verify() {
        for (var material : new net.minecraft.world.item.Item[]{Items.OAK_LOG, Items.BIRCH_LOG}) {
            ItemStack original = new ItemStack(material, 1);
            ItemStack unrelated = new ItemStack(material, 1);
            // The UUID-retaining recipient gets this new stack in vanilla ItemEntity.merge.
            ItemStack merged = ItemEntity.merge(original, unrelated, 64);
            require(merged.getCount() == 2, "vanilla merge did not combine two logs");
            require(merged != original, "vanilla no longer replaces the receiving stack");
            merged.shrink(1); // Another player's one-slot partial inventory pickup.
            require(merged.getCount() == 1, "partial pickup did not leave one log");
            require(!TreeDropAccounting.sameSource(original, merged), "mixed source accepted");
            int credit = TreeDropAccounting.sameSource(original, merged)
                    ? TreeDropAccounting.credit(1, merged.getCount(), 1, 1, 0, 4) : 0;
            require(credit == 0, "mixed remainder credited as task harvest");

            ItemStack clean = new ItemStack(material, 1);
            ItemStack witness = clean;
            clean.shrink(1); // Inventory.add consumes the same stack before Player.take.
            require(TreeDropAccounting.sameSource(witness, clean), "clean pickup lost provenance");
            require(TreeDropAccounting.credit(1, clean.getCount() + 1, 1, 1, 0, 4) == 1,
                    "clean pickup was not credited");
        }
        System.out.println("TREE_DROP_REGRESSION PASS: oak/birch vanilla merge and clean pickup");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
