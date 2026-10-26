package io.github.yfyan.minecraftfriends.bootstrap;

import io.github.yfyan.minecraftfriends.adapter.LoaderAdapter;
import io.github.yfyan.minecraftfriends.config.ConfigLoader;
import io.github.yfyan.minecraftfriends.config.ProviderMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BootstrapTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void startsWithOfflineDefaultsWhenNoConfigurationExists() {
        RecordingAdapter adapter = new RecordingAdapter(temporaryDirectory);

        StartupReport report = Bootstrap.initialize(adapter);

        assertTrue(report.createdDefaultConfig());
        assertTrue(report.config().enabled());
        assertEquals("MOCK", report.config().provider().name());
        assertTrue(report.notices().stream()
                .anyMatch(notice -> notice.message().contains("Mock mode remains offline")));
        assertTrue(adapter.messages.stream()
                .anyMatch(message -> message.contains("Startup completed without sending a model request")));
        assertFalse(adapter.warnings);
    }

    @Test
    void usesSafeDefaultsWhenConfigDirectoryCannotBePrepared() throws IOException {
        Path fileInsteadOfDirectory = temporaryDirectory.resolve("not-a-directory");
        java.nio.file.Files.writeString(fileInsteadOfDirectory, "occupied");
        RecordingAdapter adapter = new RecordingAdapter(fileInsteadOfDirectory);

        StartupReport report = Bootstrap.initialize(adapter);

        assertEquals("MOCK", report.config().provider().name());
        assertTrue(report.config().enabled());
        assertTrue(adapter.warnings);
    }

    @Test
    void fallsBackSafelyForMalformedUnicodeWithoutChangingOrLoggingTheFile() throws IOException {
        Path config = temporaryDirectory.resolve(ConfigLoader.FILE_NAME);
        String secretSentinel = "bootstrap-config-sentinel";
        String original = "model.name=" + secretSentinel + (char) 92 + "u12X4\n";
        Files.writeString(config, original);
        RecordingAdapter adapter = new RecordingAdapter(temporaryDirectory);

        StartupReport report = Bootstrap.initialize(adapter);

        assertEquals(ProviderMode.MOCK, report.config().provider());
        assertTrue(adapter.warnings);
        assertFalse(String.join("\n", adapter.messages).contains(secretSentinel));
        assertEquals(original, Files.readString(config));
    }

    private static final class RecordingAdapter implements LoaderAdapter {
        private final Path configDirectory;
        private final List<String> messages = new ArrayList<>();
        private boolean warnings;

        private RecordingAdapter(Path configDirectory) {
            this.configDirectory = configDirectory;
        }

        @Override
        public String loaderName() {
            return "test";
        }

        @Override
        public Path configDirectory() {
            return configDirectory;
        }

        @Override
        public void logInfo(String message) {
            messages.add(message);
        }

        @Override
        public void logWarn(String message, Throwable error) {
            warnings = true;
            messages.add(message);
        }
    }
}
