package io.github.yfyan.minecraftfriends.bootstrap;

import io.github.yfyan.minecraftfriends.adapter.LoaderAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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
                .anyMatch(message -> message.contains("Startup completed without model credentials")));
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
