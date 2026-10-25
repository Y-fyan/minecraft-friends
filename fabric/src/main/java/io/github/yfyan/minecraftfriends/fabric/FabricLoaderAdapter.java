package io.github.yfyan.minecraftfriends.fabric;

import io.github.yfyan.minecraftfriends.adapter.LoaderAdapter;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.Objects;

final class FabricLoaderAdapter implements LoaderAdapter {
    private final Logger logger;

    FabricLoaderAdapter(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public String loaderName() {
        return "Fabric";
    }

    @Override
    public Path configDirectory() {
        return FabricLoader.getInstance().getConfigDir();
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
