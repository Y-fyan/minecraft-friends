package io.github.yfyan.minecraftfriends.config;

import io.github.yfyan.minecraftfriends.bootstrap.StartupNotice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void createsReadableDefaultConfigWithoutCredentials() throws IOException {
        ConfigLoader.LoadedConfig loaded = new ConfigLoader().loadOrCreate(temporaryDirectory);

        assertTrue(loaded.created());
        assertEquals(ModConfig.defaults(), loaded.config());
        String content = Files.readString(loaded.path());
        assertTrue(content.contains("model.provider=mock"));
        assertTrue(content.contains("diagnostics.enabled=true"));
        assertTrue(!content.toLowerCase().contains("api.key"));
        try (var files = Files.list(temporaryDirectory)) {
            assertEquals(List.of(loaded.path()), files.toList());
        }
    }

    @Test
    void fallsBackForInvalidValuesAndReportsWarnings() throws IOException {
        Path config = temporaryDirectory.resolve(ConfigLoader.FILE_NAME);
        Files.writeString(config, """
                enabled=maybe
                model.provider=remote
                model.timeout-seconds=999
                diagnostics.enabled=false
                """);

        ConfigLoader.LoadedConfig loaded = new ConfigLoader().loadOrCreate(temporaryDirectory);

        assertEquals(true, loaded.config().enabled());
        assertEquals(ProviderMode.MOCK, loaded.config().provider());
        assertEquals(30, loaded.config().requestTimeoutSeconds());
        assertEquals(false, loaded.config().diagnosticsEnabled());
        assertEquals(
                3,
                loaded.notices().stream()
                        .filter(notice -> notice.level() == StartupNotice.Level.WARNING)
                        .count()
        );
    }

    @Test
    void acceptsDisabledModeAndCustomTimeout() throws IOException {
        Path config = temporaryDirectory.resolve(ConfigLoader.FILE_NAME);
        Files.writeString(config, """
                enabled=false
                model.provider=disabled
                model.timeout-seconds=5
                diagnostics.enabled=false
                """);

        ModConfig loaded = new ConfigLoader().loadOrCreate(temporaryDirectory).config();

        assertEquals(false, loaded.enabled());
        assertEquals(ProviderMode.DISABLED, loaded.provider());
        assertEquals(5, loaded.requestTimeoutSeconds());
        assertEquals(false, loaded.diagnosticsEnabled());
    }
}
