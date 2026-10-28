package io.github.yfyan.minecraftfriends.capability;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Strict parser for one model-proposed capability call. It rejects unknown capabilities, unknown or
 * duplicate fields, wrong value types and out-of-range numbers; it never grants authorization.
 */
public final class CapabilityCallParser {
    public static final int MAX_JSON_CHARS = 1024;

    private CapabilityCallParser() {
    }

    public static CapabilityCall parse(String json, CapabilityRegistry registry) {
        if (json == null || json.length() > MAX_JSON_CHARS) {
            throw new CapabilityException(CapabilityException.Code.BAD_JSON,
                    "Capability call must be one object of at most " + MAX_JSON_CHARS + " characters.");
        }
        try {
            JsonReader reader = new JsonReader(new StringReader(json));
            reader.setLenient(false);
            reader.beginObject();
            String capability = null;
            Map<String, Object> arguments = new LinkedHashMap<>();
            boolean argumentsSeen = false;
            Set<String> fields = new LinkedHashSet<>();
            while (reader.hasNext()) {
                String field = reader.nextName();
                if (!fields.add(field)) {
                    throw bad("Duplicate capability call field: " + bounded(field));
                }
                switch (field) {
                    case "capability" -> {
                        if (reader.peek() != JsonToken.STRING) {
                            throw bad("capability must be a string.");
                        }
                        capability = reader.nextString();
                    }
                    case "arguments" -> {
                        if (argumentsSeen) {
                            throw bad("Duplicate arguments object.");
                        }
                        argumentsSeen = true;
                        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                            throw bad("arguments must be an object.");
                        }
                        readArguments(reader, arguments);
                    }
                    default -> throw bad("Unknown capability call field: " + bounded(field));
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw bad("Capability call must be exactly one JSON object.");
            }
            if (capability == null || capability.isBlank()) {
                throw bad("capability is required.");
            }
            String requested = capability;
            CapabilitySpec spec = registry.find(requested).orElseThrow(() -> new CapabilityException(
                    CapabilityException.Code.UNKNOWN_CAPABILITY, "Unknown capability: " + bounded(requested)));
            validateArguments(spec, arguments);
            return new CapabilityCall(spec.name(), arguments);
        } catch (CapabilityException already) {
            throw already;
        } catch (IOException | ArithmeticException | IllegalStateException failure) {
            throw new CapabilityException(CapabilityException.Code.BAD_JSON, "Malformed capability call JSON.");
        }
    }

    private static void readArguments(JsonReader reader, Map<String, Object> arguments) throws IOException {
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if (arguments.containsKey(name)) {
                throw bad("Duplicate argument: " + bounded(name));
            }
            if (reader.peek() == JsonToken.NUMBER) {
                try {
                    arguments.put(name, new BigDecimal(reader.nextString()).intValueExact());
                } catch (ArithmeticException | NumberFormatException invalidNumber) {
                    throw bad("Argument must be an integer: " + bounded(name));
                }
            } else {
                throw bad("Argument must be an integer: " + bounded(name));
            }
        }
        reader.endObject();
    }

    private static void validateArguments(CapabilitySpec spec, Map<String, Object> arguments) {
        for (String name : arguments.keySet()) {
            if (spec.parameters().stream().noneMatch(parameter -> parameter.name().equals(name))) {
                throw bad("Unknown argument for " + spec.name() + ": " + bounded(name));
            }
        }
        for (CapabilityParameter parameter : spec.parameters()) {
            Object value = arguments.get(parameter.name());
            if (value == null) {
                if (parameter.required()) {
                    throw bad("Missing argument: " + parameter.name());
                }
                continue;
            }
            if (!(value instanceof Integer number)) {
                throw bad("Argument must be an integer: " + parameter.name());
            }
            if (parameter.kind() != CapabilityParameter.Kind.INTEGER) {
                throw bad("Unsupported argument type: " + parameter.name());
            }
            if (number < parameter.min() || number > parameter.max()) {
                throw new CapabilityException(CapabilityException.Code.OUT_OF_RANGE,
                        parameter.name() + " must be between " + parameter.min() + " and " + parameter.max() + ".");
            }
        }
    }

    private static CapabilityException bad(String message) {
        return new CapabilityException(CapabilityException.Code.BAD_ARGUMENT, message);
    }

    private static String bounded(String value) {
        return value.length() <= 48 ? value : value.substring(0, 48) + "…";
    }
}
