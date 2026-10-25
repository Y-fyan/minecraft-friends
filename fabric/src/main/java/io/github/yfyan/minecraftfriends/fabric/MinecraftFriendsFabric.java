package io.github.yfyan.minecraftfriends.fabric;

import io.github.yfyan.minecraftfriends.MinecraftFriends;
import io.github.yfyan.minecraftfriends.bootstrap.Bootstrap;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MinecraftFriendsFabric implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(MinecraftFriends.MOD_ID);

    @Override
    public void onInitialize() {
        Bootstrap.initialize(new FabricLoaderAdapter(LOGGER));
    }
}
