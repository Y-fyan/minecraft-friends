package io.github.yfyan.minecraftfriends.capability;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Capability selected by the model. The server assigns the call id; the model cannot. */
public record CapabilityCall(String capability, Map<String, Object> arguments) {
    public CapabilityCall {
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(arguments, "arguments");
        arguments = Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }

    public Optional<Integer> integer(String name) {
        Object value = arguments.get(name);
        return value instanceof Integer number ? Optional.of(number) : Optional.empty();
    }
}
