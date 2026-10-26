package io.github.yfyan.minecraftfriends.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PersistentWorldIdentityTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void missingIdentityIsCreatedOnceAndSurvivesASecondLoad() throws IOException {
        Path file = temporaryDirectory.resolve("world-a/data/" + PersistentWorldIdentity.FILE_NAME);

        UUID first = PersistentWorldIdentity.loadOrCreate(file);
        UUID second = PersistentWorldIdentity.loadOrCreate(file);

        assertEquals(first, second);
        assertEquals(first.toString(), Files.readString(file).strip());
    }

    @Test
    void malformedIdentityFailsClosedWithoutReplacingOriginalBytes() throws IOException {
        Path file = temporaryDirectory.resolve("world-b/data/" + PersistentWorldIdentity.FILE_NAME);
        Files.createDirectories(file.getParent());
        byte[] original = "broken-world-id\nkeep-this-file".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        Files.write(file, original);

        assertThrows(IOException.class, () -> PersistentWorldIdentity.loadOrCreate(file));
        assertArrayEquals(original, Files.readAllBytes(file));
    }

    @Test
    void distinctWorldDataFilesReceiveDistinctIds() throws IOException {
        Path first = temporaryDirectory.resolve("save-one/data/" + PersistentWorldIdentity.FILE_NAME);
        Path second = temporaryDirectory.resolve("save-two/data/" + PersistentWorldIdentity.FILE_NAME);

        assertNotEquals(PersistentWorldIdentity.loadOrCreate(first), PersistentWorldIdentity.loadOrCreate(second));
    }
}
