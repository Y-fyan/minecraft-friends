package io.github.yfyan.minecraftfriends.companion;

import com.mojang.authlib.GameProfile;
import io.github.yfyan.minecraftfriends.action.ActionRuntime;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.entity.EntityTypeTest;
import java.util.ArrayList;

/** Local server player with a callback for pickups performed by vanilla ItemEntity.playerTouch. */
public final class CompanionServerPlayer extends ServerPlayer {
    public CompanionServerPlayer(MinecraftServer server, ServerLevel level, GameProfile profile,
                                 ClientInformation clientInformation) {
        super(server, level, profile, clientInformation);
    }

    @Override
    public void tick() {
        super.tick();
        touchLocalItems(this);
    }

    /** Also supports vanilla ServerPlayer instances returned by PlayerList.respawn. */
    public static void touchLocalItems(ServerPlayer player) {
        if (!CompanionRuntime.isEnabled() || !player.isAlive() || player.isSpectator()
                || player.isPassenger() || !CompanionRuntime.isCompanion(player)) return;
        var nearby = new ArrayList<ItemEntity>();
        player.serverLevel().getEntities(EntityTypeTest.forClass(ItemEntity.class),
                player.getBoundingBox().inflate(1.0,0.5,1.0),ItemEntity::isAlive,nearby,32);
        for(ItemEntity item:nearby) {
            java.util.UUID id=item.getUUID();
            var sourceStack = item.getItem();
            int stackBefore=sourceStack.getCount();
            net.minecraft.world.item.Item material = item.getItem().getItem();
            boolean fallback = !(player instanceof CompanionServerPlayer)
                    && (material == net.minecraft.world.item.Items.OAK_LOG
                    || material == net.minecraft.world.item.Items.BIRCH_LOG);
            int inventoryBefore = fallback ? countMaterial(player, material) : 0;
            item.playerTouch(player);
            if(fallback) {
                int accepted=Math.min(stackBefore,Math.max(0,countMaterial(player, material)-inventoryBefore));
                if(accepted>0)ActionRuntime.onItemPickedUp(player,id,accepted,stackBefore,sourceStack);
            }
        }
    }

    private static int countMaterial(ServerPlayer player, net.minecraft.world.item.Item material) {
        int result=0;
        for(int slot=0;slot<player.getInventory().getContainerSize();slot++) {
            var stack=player.getInventory().getItem(slot);
            if(stack.is(material))result+=stack.getCount();
        }
        return result;
    }

    @Override
    public void take(Entity entity, int amount) {
        // Inventory.add may already have emptied the stack before vanilla invokes take.
        // The runtime validates the UUID against this task's source-bound log-drop ledger.
        var sourceStack = entity instanceof ItemEntity item ? item.getItem() : null;
        boolean itemPickup = sourceStack != null;
        int stackCountBeforePickup = itemPickup ? sourceStack.getCount() + amount : 0;
        java.util.UUID itemEntityId = entity.getUUID();
        super.take(entity, amount);
        if (itemPickup) {
            ActionRuntime.onItemPickedUp(this, itemEntityId, amount, stackCountBeforePickup, sourceStack);
        }
    }
}
