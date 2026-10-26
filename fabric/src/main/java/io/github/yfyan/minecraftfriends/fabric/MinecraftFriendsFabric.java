package io.github.yfyan.minecraftfriends.fabric;

import io.github.yfyan.minecraftfriends.MinecraftFriends;
import io.github.yfyan.minecraftfriends.bootstrap.Bootstrap;
import io.github.yfyan.minecraftfriends.companion.CompanionRuntime;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MinecraftFriendsFabric implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(MinecraftFriends.MOD_ID);

    @Override
    public void onInitialize() {
        Bootstrap.initialize(new FabricLoaderAdapter(LOGGER));
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                CompanionRuntime.registerCommands(dispatcher));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                CompanionRuntime.onOwnerLogin(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                CompanionRuntime.onOwnerLogout(handler.player));
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (entity instanceof ServerPlayer player) {
                CompanionRuntime.onCompanionDeath(player);
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(CompanionRuntime::onServerTick);
        ServerLifecycleEvents.SERVER_STOPPING.register(CompanionRuntime::onServerStopping);
    }
}
