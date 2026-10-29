package io.github.yfyan.minecraftfriends.capability;

/** Explicit, bounded failure reason returned instead of executing a malformed call. */
public final class CapabilityException extends IllegalArgumentException {
    private final Code code;

    public CapabilityException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        BAD_JSON,
        UNKNOWN_CAPABILITY,
        BAD_ARGUMENT,
        OUT_OF_RANGE
    }
}
