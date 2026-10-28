package io.github.yfyan.minecraftfriends.capability;

import java.util.Objects;

/** One typed argument of a capability. Only integer arguments are exposed today. */
public record CapabilityParameter(String name, Kind kind, boolean required, long min, long max,
                                  String description) {
    public CapabilityParameter {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(description, "description");
        if (!name.matches("[a-z][a-z0-9_]{0,31}")) {
            throw new IllegalArgumentException("Parameter names use lower_snake_case.");
        }
        if (kind == Kind.INTEGER && min > max) {
            throw new IllegalArgumentException("Integer parameter range is inverted.");
        }
        if (description.length() > 96) {
            throw new IllegalArgumentException("Parameter description is too long.");
        }
    }

    public static CapabilityParameter count(String name, long min, long max, String description) {
        return new CapabilityParameter(name, Kind.INTEGER, true, min, max, description);
    }

    /** Human and prompt rendering. Bounded so the registry prompt cannot grow without review. */
    public String describe() {
        if (kind == Kind.INTEGER) {
            return name + "(" + (required ? "必需" : "可选") + " 整数 " + min + "-" + max + ")";
        }
        return name + "(" + (required ? "必需" : "可选") + ")";
    }

    public enum Kind {
        INTEGER
    }
}
