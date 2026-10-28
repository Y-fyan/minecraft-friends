package io.github.yfyan.minecraftfriends.capability;

import java.util.List;
import java.util.Objects;

/**
 * Declarative description of one capability that is actually implemented and accepted.
 * Registering a capability is a contract: execution still revalidates owner, companion,
 * dimension, chunks, resources and area authorization on the server thread.
 */
public record CapabilitySpec(String name, String summary, List<CapabilityParameter> parameters,
                             Access access, boolean requiresAreaAuthorization) {
    public static final int MAX_PARAMETERS = 4;

    public CapabilitySpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(access, "access");
        parameters = List.copyOf(parameters);
        if (!name.matches("[a-z][a-z0-9_]{0,47}")) {
            throw new IllegalArgumentException("Capability names use lower_snake_case.");
        }
        if (summary.isBlank() || summary.length() > 160) {
            throw new IllegalArgumentException("Capability summary is empty or too long.");
        }
        if (parameters.size() > MAX_PARAMETERS) {
            throw new IllegalArgumentException("Too many capability parameters.");
        }
        if (access == Access.READ_ONLY && requiresAreaAuthorization) {
            throw new IllegalArgumentException("Read-only capabilities never consume area authorization.");
        }
    }

    public String describe() {
        StringBuilder text = new StringBuilder(name).append('(');
        for (int i = 0; i < parameters.size(); i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(parameters.get(i).describe());
        }
        return text.append(")：").append(summary).toString();
    }

    public enum Access {
        /** Reads a bounded server-thread snapshot and changes no world state. */
        READ_ONLY,
        /** Changes the companion, inventory or world through vanilla survival interactions. */
        WORLD_WRITE
    }
}
