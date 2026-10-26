package io.github.yfyan.minecraftfriends.config;

import io.github.yfyan.minecraftfriends.bootstrap.StartupNotice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        assertTrue(content.contains("model.api-key-env="));
        assertTrue(!content.toLowerCase().contains("api-key="));
        assertEquals(ProviderMode.MOCK, loaded.config().provider());
        assertEquals("", loaded.config().modelEndpoint());
        assertEquals(24, loaded.config().modelMaxContextMessages());
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

    @Test
    void parsesOnlyModelSettingsAndRedactsInvalidValuesFromNotices() throws IOException {
        Path config = temporaryDirectory.resolve(ConfigLoader.FILE_NAME);
        Files.writeString(config, """
                model.provider=chat-completions
                model.endpoint=http://user:private-value@example.invalid/v1/chat/completions
                model.name=local-test-model
                model.api-key-env=not-a-valid-env-name!
                model.allow-unauthenticated-local=true
                model.retries=2
                model.max-context-messages=8
                model.max-context-characters=4096
                model.max-response-bytes=2048
                chat.max-history=999
                """);

        ConfigLoader.LoadedConfig loaded = new ConfigLoader().loadOrCreate(temporaryDirectory);
        String allNotices = loaded.notices().toString();

        assertEquals(ProviderMode.CHAT_COMPLETIONS, loaded.config().provider());
        assertEquals("", loaded.config().modelEndpoint());
        assertEquals("local-test-model", loaded.config().modelName());
        assertEquals("", loaded.config().apiKeyEnvironmentVariable());
        assertEquals(2, loaded.config().modelRetries());
        assertEquals(8, loaded.config().modelMaxContextMessages());
        assertEquals(4096, loaded.config().modelMaxContextCharacters());
        assertEquals(2048, loaded.config().modelMaxResponseBytes());
        assertFalse(allNotices.contains("private-value"));
        assertFalse(allNotices.contains("not-a-valid-env-name"));
        assertFalse(loaded.config().toString().contains("private-value"));
        assertFalse(loaded.config().toString().contains("local-test-model"));
    }

    @Test
    void keepsTheOriginalFourFieldConstructor() {
        ModConfig config = new ModConfig(true, ProviderMode.MOCK, 30, true);

        assertEquals(ModConfig.defaults(), config);
    }

    @Test
    void acceptsAnExplicitLoopbackChatCompletionsEndpoint() throws IOException {
        Path config = temporaryDirectory.resolve(ConfigLoader.FILE_NAME);
        Files.writeString(config, """
                model.provider=chat-completions
                model.endpoint=http://localhost:12345/v1/chat/completions
                model.name=local-model
                model.allow-unauthenticated-local=true
                """);

        ModConfig loaded = new ConfigLoader().loadOrCreate(temporaryDirectory).config();

        assertEquals(ProviderMode.CHAT_COMPLETIONS, loaded.provider());
        assertEquals("http://localhost:12345/v1/chat/completions", loaded.modelEndpoint());
        assertEquals("local-model", loaded.modelName());
        assertTrue(loaded.allowUnauthenticatedLocalEndpoint());
        assertEquals("", loaded.apiKeyEnvironmentVariable());
    }

    @Test
    void neverEchoesInvalidPropertyValuesThatCouldContainSecrets() throws IOException {
        Path config = temporaryDirectory.resolve(ConfigLoader.FILE_NAME);
        Files.writeString(config, """
                enabled=enabled-secret-sentinel!
                model.provider=provider-secret-sentinel!
                model.timeout-seconds=timeout-secret-sentinel!
                diagnostics.enabled=diagnostics-secret-sentinel!
                model.endpoint=https://user:endpoint-secret-sentinel@example.invalid/path?token=query-secret-sentinel
                model.api-key-env=api-key-secret-sentinel!
                """);

        ConfigLoader.LoadedConfig loaded = new ConfigLoader().loadOrCreate(temporaryDirectory);
        String notices = loaded.notices().toString();

        for (String sentinel : List.of(
                "enabled-secret-sentinel",
                "provider-secret-sentinel",
                "timeout-secret-sentinel",
                "diagnostics-secret-sentinel",
                "endpoint-secret-sentinel",
                "query-secret-sentinel",
                "api-key-secret-sentinel"
        )) {
            assertFalse(notices.contains(sentinel));
            assertFalse(loaded.config().toString().contains(sentinel));
        }
    }

    @Test
    void wrapsMalformedUnicodeEscapeWithoutChangingOrEchoingTheFile() throws IOException {
        Path config = temporaryDirectory.resolve(ConfigLoader.FILE_NAME);
        String secretSentinel = "private-config-sentinel";
        String original = "model.provider=mock\nmodel.name=" + secretSentinel + (char) 92 + "u12X4\n";
        Files.writeString(config, original);

        IOException failure = assertThrows(
                IOException.class,
                () -> new ConfigLoader().loadOrCreate(temporaryDirectory)
        );

        assertEquals("Invalid syntax in minecraft-friends.properties.", failure.getMessage());
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains(secretSentinel));
        assertEquals(original, Files.readString(config));
    }
}
