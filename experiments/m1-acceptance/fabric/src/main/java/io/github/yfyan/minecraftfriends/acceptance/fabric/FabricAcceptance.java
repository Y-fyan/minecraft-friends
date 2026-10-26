package io.github.yfyan.minecraftfriends.acceptance.fabric;

import io.github.yfyan.minecraftfriends.acceptance.AcceptanceScenario;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public final class FabricAcceptance implements ModInitializer {
    @Override
    public void onInitialize() {
        if (!Boolean.getBoolean("mf.acceptance")) { return; }
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> AcceptanceScenario.registerCommands(dispatcher));
        ServerTickEvents.END_SERVER_TICK.register(AcceptanceScenario::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(AcceptanceScenario::remove);
    }
}
