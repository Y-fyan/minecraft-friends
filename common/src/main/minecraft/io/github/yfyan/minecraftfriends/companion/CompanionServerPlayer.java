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
        // Local connections do not drive ServerPlayer.doTick/Player.aiStep.
        // Run only vanilla item contact, using the non-riding Player.aiStep bounds.
        if (!isAlive() || isSpectator() || isPassenger() || !CompanionRuntime.isCompanion(this)) {
            return;
        }
        var nearby = new ArrayList<ItemEntity>();
        serverLevel().getEntities(EntityTypeTest.forClass(ItemEntity.class),
                getBoundingBox().inflate(1.0, 0.5, 1.0), ItemEntity::isAlive, nearby, 32);
        for (ItemEntity item : nearby) {
            item.playerTouch(this);
        }
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
