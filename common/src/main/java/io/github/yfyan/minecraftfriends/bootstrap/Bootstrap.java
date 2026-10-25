package io.github.yfyan.minecraftfriends.bootstrap;

import io.github.yfyan.minecraftfriends.adapter.LoaderAdapter;
import io.github.yfyan.minecraftfriends.config.ConfigLoader;
import io.github.yfyan.minecraftfriends.config.ModConfig;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

public final class Bootstrap {
    private Bootstrap() {
    }

    public static StartupReport initialize(LoaderAdapter adapter) {
        Objects.requireNonNull(adapter, "adapter");
        adapter.logInfo("Initializing " + io.github.yfyan.minecraftfriends.MinecraftFriends.MOD_NAME
                + " with " + adapter.loaderName());

        try {
            ConfigLoader.LoadedConfig loaded = new ConfigLoader()
                    .loadOrCreate(adapter.configDirectory());

            loaded.notices().forEach(notice -> {
                if (notice.level() == StartupNotice.Level.WARNING) {
                    adapter.logWarn(notice.message(), null);
                } else {
                    adapter.logInfo(notice.message());
                }
            });

            adapter.logInfo("Configuration source: " + loaded.path());
            adapter.logInfo("Model provider: " + loaded.config().provider()
                    + " (no network request or paid call is made during startup)");
            adapter.logInfo("Diagnostics enabled: " + loaded.config().diagnosticsEnabled());
            adapter.logInfo("Startup completed without model credentials.");
            return new StartupReport(loaded.config(), loaded.path(), loaded.created(), loaded.notices());
        } catch (IOException exception) {
            ModConfig fallback = ModConfig.defaults();
            StartupNotice warning = new StartupNotice(
                    StartupNotice.Level.WARNING,
                    "Could not read or create the configuration file. Using safe defaults: "
                            + exception.getMessage()
            );
            adapter.logWarn(warning.message(), exception);
            adapter.logInfo("Model provider: " + fallback.provider()
                    + " (safe fallback; no network request is made)");
            return new StartupReport(
                    fallback,
                    null,
                    false,
                    List.of(warning)
            );
        }
    }
}
