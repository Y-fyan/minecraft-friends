package io.github.yfyan.minecraftfriends.companion;

import com.mojang.authlib.GameProfile;
import io.github.yfyan.minecraftfriends.action.ActionRuntime;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;

/** Local server player with a callback for pickups performed by vanilla ItemEntity.playerTouch. */
public final class CompanionServerPlayer extends ServerPlayer {
    public CompanionServerPlayer(MinecraftServer server, ServerLevel level, GameProfile profile,
                                 ClientInformation clientInformation) {
        super(server, level, profile, clientInformation);
    }

    @Override
    public void take(Entity entity, int amount) {
        // Inventory.add may already have emptied the stack before vanilla invokes take.
        // The runtime validates the UUID against this task's source-bound oak-drop ledger.
        boolean itemPickup = entity instanceof ItemEntity;
        int stackCountBeforePickup = entity instanceof ItemEntity item ? item.getItem().getCount() + amount : 0;
        java.util.UUID itemEntityId = entity.getUUID();
        super.take(entity, amount);
        if (itemPickup) {
            ActionRuntime.onItemPickedUp(this, itemEntityId, amount, stackCountBeforePickup);
        }
    }
}
