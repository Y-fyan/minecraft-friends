package io.github.yfyan.minecraftfriends.forge;

import io.github.yfyan.minecraftfriends.adapter.LoaderAdapter;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.Objects;

final class ForgeLoaderAdapter implements LoaderAdapter {
    private final Logger logger;

    ForgeLoaderAdapter(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public String loaderName() {
        return "Minecraft Forge";
    }

    @Override
    public Path configDirectory() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public void logInfo(String message) {
        logger.info(message);
    }

    @Override
    public void logWarn(String message, Throwable error) {
        if (error == null) {
            logger.warn(message);
        } else {
            logger.warn(message, error);
        }
    }
}
