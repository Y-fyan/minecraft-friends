package io.github.yfyan.minecraftfriends.fabric;

import io.github.yfyan.minecraftfriends.chat.ChatRuntime;
import io.github.yfyan.minecraftfriends.integration.M1LifecycleBridge;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;

/** Register after companion lifecycle listeners so restored companions precede chat login. */
public final class ChatIntegrationFabric {
    private static boolean registered;

    private ChatIntegrationFabric() {
    }

    /** Called once from the end of the official Fabric initializer. */
    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        ServerLifecycleEvents.SERVER_STARTED.register(server ->
                ChatRuntime.start(server, FabricLoader.getInstance().getConfigDir()));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            M1LifecycleBridge.stop(server);
            ChatRuntime.stop(server);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            M1LifecycleBridge.stop(server);
            ChatRuntime.onWorldUnloaded(server);
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                ChatRuntime.onLogin(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                ChatRuntime.onLogout(handler.player));
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                ChatRuntime.registerCommands(dispatcher));
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, parameters) ->
                !ChatRuntime.onChat(sender, message.signedContent()));
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (amount > 0 && entity instanceof ServerPlayer player) {
                M1LifecycleBridge.beforeDamage(player);
            }
            // Other listeners retain their right to veto this damage.
            return true;
        });
        ServerTickEvents.END_SERVER_TICK.register(M1LifecycleBridge::afterTick);
    }
}
