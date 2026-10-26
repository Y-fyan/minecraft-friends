package io.github.yfyan.minecraftfriends.chat;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Parses the optional private companion-address prefix without granting session membership. */
public final class ChatPrefix {
    private static final Pattern COMPANION_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private ChatPrefix() {
    }

    public static Optional<Address> parse(String text, String prefix) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(prefix, "prefix");
        if (prefix.isBlank() || text.startsWith("/") || !text.startsWith(prefix)) {
            return Optional.empty();
        }
        int nameStart = prefix.length();
        int separator = nameStart;
        while (separator < text.length() && !Character.isWhitespace(text.charAt(separator))) {
            separator++;
        }
        String name = text.substring(nameStart, separator);
        if (!COMPANION_NAME.matcher(name).matches() || separator == text.length()) {
            return Optional.empty();
        }
        int messageStart = separator;
        while (messageStart < text.length() && Character.isWhitespace(text.charAt(messageStart))) {
            messageStart++;
        }
        if (messageStart == text.length()) {
            return Optional.empty();
        }
        return Optional.of(new Address(name, text.substring(messageStart)));
    }

    public record Address(String companionName, String message) {
        public Address {
            Objects.requireNonNull(companionName, "companionName");
            Objects.requireNonNull(message, "message");
        }
    }
}
