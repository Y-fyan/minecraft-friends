package io.github.yfyan.minecraftfriends.chat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.UUID;

/** A strict UUID file stored in one world's data directory, independent of seed and path. */
public final class PersistentWorldIdentity {
    public static final String FILE_NAME = "minecraft_friends_chat_world.uuid";
    private static final int MAX_FILE_BYTES = 64;

    private PersistentWorldIdentity() {
    }

    /** Creates only a missing file; malformed existing data is preserved and fails closed. */
    public static UUID loadOrCreate(Path identityFile) throws IOException {
        Objects.requireNonNull(identityFile, "identityFile");
        Path file = identityFile.toAbsolutePath().normalize();
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return readExisting(file);
        }

        Path parent = file.getParent();
        if (parent == null) {
            throw new IOException("World identity file has no parent directory.");
        }
        Files.createDirectories(parent);
        UUID created = UUID.randomUUID();
        byte[] bytes = (created + "\n").getBytes(StandardCharsets.US_ASCII);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
            return created;
        } catch (FileAlreadyExistsException racedCreation) {
            return readExisting(file);
        }
    }

    private static UUID readExisting(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("World identity path is not a regular file.");
        }
        long size = Files.size(file);
        if (size < 1 || size > MAX_FILE_BYTES) {
            throw new IOException("World identity file has an invalid size.");
        }
        String value = Files.readString(file, StandardCharsets.US_ASCII).strip();
        try {
            UUID identity = UUID.fromString(value);
            if (!identity.toString().equalsIgnoreCase(value)) {
                throw new IllegalArgumentException("Non-canonical UUID");
            }
            return identity;
        } catch (IllegalArgumentException invalid) {
            throw new IOException("World identity file does not contain a canonical UUID.");
        }
    }
}
