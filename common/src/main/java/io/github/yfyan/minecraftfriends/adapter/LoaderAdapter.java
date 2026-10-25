package io.github.yfyan.minecraftfriends.adapter;

import java.nio.file.Path;

/**
 * The small loader-facing boundary used by the common layer during startup.
 * Game events and world actions will get separate, narrowly typed adapters.
 */
public interface LoaderAdapter {
    String loaderName();

    Path configDirectory();

    void logInfo(String message);

    void logWarn(String message, Throwable error);
}
