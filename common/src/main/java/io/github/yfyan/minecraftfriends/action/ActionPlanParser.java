package io.github.yfyan.minecraftfriends.action;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Strict JSON allowlist parser for model-suggested actions; it never grants world permission. */
public final class ActionPlanParser {
    private ActionPlanParser() {
    }

    public static ActionPlan parse(String json) {
        if (json == null || json.length() > 1024) {
            throw new IllegalArgumentException("Action plan must be at most 1024 characters.");
        }
        try {
            JsonReader reader = new JsonReader(new StringReader(json));
            reader.setLenient(false);
            reader.beginObject();
            Set<String> fields = new HashSet<>();
            String action = null;
            int count = 0;
            while (reader.hasNext()) {
                String field = reader.nextName();
                if (!fields.add(field)) {
                    throw new IllegalArgumentException("Duplicate action plan field: " + field);
                }
                if (field.equals("action")) {
                    if (reader.peek() != JsonToken.STRING) {
                        throw new IllegalArgumentException("action must be a string.");
                    }
                    action = reader.nextString().toLowerCase(Locale.ROOT);
                } else if (field.equals("count")) {
                    if (reader.peek() != JsonToken.NUMBER) {
                        throw new IllegalArgumentException("count must be an integer.");
                    }
                    count = new BigDecimal(reader.nextString()).intValueExact();
                } else {
                    throw new IllegalArgumentException("Unknown action plan field: " + field);
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT || action == null) {
                throw new IllegalArgumentException("Action plan must be one object with an action.");
            }
            ActionType type = switch (action) {
                case "follow" -> ActionType.FOLLOW;
                case "wait" -> ActionType.WAIT;
                case "stop" -> ActionType.STOP;
                case "collect_oak_logs" -> ActionType.COLLECT_OAK_LOGS;
                default -> throw new IllegalArgumentException("Unsupported action: " + action);
            };
            if (type != ActionType.COLLECT_OAK_LOGS && fields.contains("count")) {
                throw new IllegalArgumentException("count is allowed only for collect_oak_logs.");
            }
            return new ActionPlan(type, count);
        } catch (IOException | NumberFormatException failure) {
            throw new IllegalArgumentException("Malformed action plan JSON.", failure);
        }
    }
}
