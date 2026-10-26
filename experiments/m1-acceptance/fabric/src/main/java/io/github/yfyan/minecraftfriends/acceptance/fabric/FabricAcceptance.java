package io.github.yfyan.minecraftfriends.acceptance.fabric;

import io.github.yfyan.minecraftfriends.acceptance.ServerAcceptanceDispatch;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public final class FabricAcceptance implements ModInitializer {
    @Override
    public void onInitialize() {
        if (!Boolean.getBoolean("mf.acceptance")) { return; }
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> ServerAcceptanceDispatch.commands(dispatcher));
        ServerLifecycleEvents.SERVER_STARTING.register(ServerAcceptanceDispatch::starting);
        ServerTickEvents.END_SERVER_TICK.register(ServerAcceptanceDispatch::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(ServerAcceptanceDispatch::stopped);
    }
}
