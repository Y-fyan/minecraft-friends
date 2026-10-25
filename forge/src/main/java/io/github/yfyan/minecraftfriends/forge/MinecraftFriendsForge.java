package io.github.yfyan.minecraftfriends.forge;

import io.github.yfyan.minecraftfriends.MinecraftFriends;
import io.github.yfyan.minecraftfriends.bootstrap.Bootstrap;
import io.github.yfyan.minecraftfriends.companion.CompanionRuntime;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(MinecraftFriends.MOD_ID)
public final class MinecraftFriendsForge {
    private static final Logger LOGGER = LoggerFactory.getLogger(MinecraftFriends.MOD_ID);

    public MinecraftFriendsForge() {
        Bootstrap.initialize(new ForgeLoaderAdapter(LOGGER));
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void registerCommands(RegisterCommandsEvent event) {
        CompanionRuntime.registerCommands(event.getDispatcher());
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            CompanionRuntime.onOwnerLogin(player);
        }
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            CompanionRuntime.onOwnerLogout(player);
        }
    }

    @SubscribeEvent
    public void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            CompanionRuntime.onCompanionDeath(player);
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        CompanionRuntime.onServerStopping(event.getServer());
    }
}
