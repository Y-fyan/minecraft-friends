package io.github.yfyan.minecraftfriends.memory;

import java.io.IOException;
import java.nio.file.Path;

/** A memory file exists but failed schema or content validation. */
public final class MemoryStoreFormatException extends IOException {
    public MemoryStoreFormatException(Path file, String reason) {
        super("Invalid companion memory file " + file + ": " + reason);
    }

    public MemoryStoreFormatException(Path file, String reason, Throwable cause) {
        super("Invalid companion memory file " + file + ": " + reason, cause);
    }
}
