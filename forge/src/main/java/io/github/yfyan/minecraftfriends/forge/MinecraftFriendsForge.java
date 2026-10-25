package io.github.yfyan.minecraftfriends.forge;

import io.github.yfyan.minecraftfriends.MinecraftFriends;
import io.github.yfyan.minecraftfriends.bootstrap.Bootstrap;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(MinecraftFriends.MOD_ID)
public final class MinecraftFriendsForge {
    private static final Logger LOGGER = LoggerFactory.getLogger(MinecraftFriends.MOD_ID);

    public MinecraftFriendsForge() {
        Bootstrap.initialize(new ForgeLoaderAdapter(LOGGER));
    }
}
