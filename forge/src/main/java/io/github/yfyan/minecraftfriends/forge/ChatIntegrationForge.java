package io.github.yfyan.minecraftfriends.forge;

import io.github.yfyan.minecraftfriends.chat.ChatRuntime;
import io.github.yfyan.minecraftfriends.integration.M1LifecycleBridge;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.loading.FMLPaths;

/** Register after companion lifecycle listeners so restored companions precede chat login. */
public final class ChatIntegrationForge {
    private static boolean registered;

    private ChatIntegrationForge() {
    }

    /** Called once from the end of the official Forge mod constructor. */
    public static synchronized void register() {
        if (!registered) {
            MinecraftForge.EVENT_BUS.register(new ChatIntegrationForge());
            registered = true;
        }
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        ChatRuntime.start(event.getServer(), FMLPaths.CONFIGDIR.get());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onServerStopping(ServerStoppingEvent event) {
        M1LifecycleBridge.stop(event.getServer());
        ChatRuntime.stop(event.getServer());
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        M1LifecycleBridge.stop(event.getServer());
        ChatRuntime.onWorldUnloaded(event.getServer());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ChatRuntime.onLogin(player);
        }
    }

    @SubscribeEvent
    public void registerCommands(RegisterCommandsEvent event) {
        ChatRuntime.registerCommands(event.getDispatcher());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = false)
    public void onChat(ServerChatEvent event) {
        if (!event.isCanceled() && ChatRuntime.onChat(event.getPlayer(), event.getRawText())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.MONITOR, receiveCanceled = false)
    public void beforeDamage(LivingDamageEvent event) {
        if (!event.isCanceled() && event.getAmount() > 0 && event.getEntity() instanceof ServerPlayer player) {
            M1LifecycleBridge.beforeDamage(player);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void afterTick(TickEvent.ServerTickEvent.Post event) {
        M1LifecycleBridge.afterTick(event.getServer());
    }
}
