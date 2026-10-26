package io.github.yfyan.minecraftfriends.memory;

import io.github.yfyan.minecraftfriends.model.ChatMessage;
import io.github.yfyan.minecraftfriends.model.ChatRole;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Builds private memory prompt messages within the same budgets enforced by chat collection. */
public final class MemoryPromptComposer {
    private static final String COMPACT_PERSONA =
            "你是 Minecraft AI 伙伴；诚实说明 AI 身份，尊重玩家自主；不索陪伴、不排他、不操控；无证据不称事件或任务已完成。";
    private static final String PREFERENCE_LABEL = "用户明确偏好：";
    private static final String EVENT_LABEL = "已验证游戏事件：";

    private MemoryPromptComposer() {
    }

    /**
     * Composes a private prompt from one immutable request snapshot. Short-term memory is omitted
     * because the chat session already supplies its bounded recent history.
     */
    public static List<ChatMessage> compose(
            RequestContext request,
            int maxContextMessages,
            int maxContextChars,
            int maxMessageChars
    ) {
        Objects.requireNonNull(request, "request");
        if (maxContextMessages <= 0 || maxContextChars <= 0 || maxMessageChars <= 0) {
            return List.of();
        }

        int messageLimit = Math.min(maxMessageChars, maxContextChars);
        String persona = personaText(request.persona(), messageLimit);
        if (persona == null) {
            // The configured budget cannot carry the compact default safety/persona instruction.
            return List.of();
        }

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage(ChatRole.SYSTEM, persona));
        int usedChars = persona.length();
        StringBuilder currentFacts = new StringBuilder();

        for (MemoryEntry memory : prioritizedMemories(request.memories())) {
            if (messages.size() >= maxContextMessages && currentFacts.isEmpty()) {
                break;
            }
            String label = memory.source() == MemorySource.USER_EXPLICIT
                    ? PREFERENCE_LABEL : EVENT_LABEL;
            String separator = currentFacts.isEmpty() ? "" : "\n";
            int messageRemaining = maxMessageChars - currentFacts.length() - separator.length();
            int totalRemaining = maxContextChars - usedChars - separator.length();
            int factLimit = Math.min(messageRemaining, totalRemaining);

            int freshMessageLimit = Math.min(maxMessageChars, maxContextChars - usedChars);
            if (!currentFacts.isEmpty()
                    && messages.size() + 1 < maxContextMessages
                    && factLimit < Math.min(label.length() + memory.text().length(), freshMessageLimit)) {
                messages.add(new ChatMessage(ChatRole.SYSTEM, currentFacts.toString()));
                currentFacts.setLength(0);
                separator = "";
                messageRemaining = maxMessageChars;
                totalRemaining = maxContextChars - usedChars;
                factLimit = Math.min(messageRemaining, totalRemaining);
            }

            if (factLimit < label.length() + 2 && !currentFacts.isEmpty()) {
                messages.add(new ChatMessage(ChatRole.SYSTEM, currentFacts.toString()));
                currentFacts.setLength(0);
                if (messages.size() >= maxContextMessages) {
                    break;
                }
                separator = "";
                messageRemaining = maxMessageChars;
                totalRemaining = maxContextChars - usedChars;
                factLimit = Math.min(messageRemaining, totalRemaining);
            }
            if (factLimit < label.length() + 2) {
                break;
            }

            String fact = renderFact(label, memory.text(), factLimit);
            if (fact == null) {
                break;
            }
            if (!currentFacts.isEmpty()) {
                currentFacts.append('\n');
                usedChars++;
            }
            currentFacts.append(fact);
            usedChars += fact.length();

            if (fact.length() == factLimit) {
                // This fact filled the remaining message or character budget; later facts can
                // still use another allowed message when total character budget remains.
                if (currentFacts.length() == maxMessageChars) {
                    messages.add(new ChatMessage(ChatRole.SYSTEM, currentFacts.toString()));
                    currentFacts.setLength(0);
                }
            }
            if (usedChars >= maxContextChars) {
                break;
            }
        }

        if (!currentFacts.isEmpty() && messages.size() < maxContextMessages) {
            messages.add(new ChatMessage(ChatRole.SYSTEM, currentFacts.toString()));
        }
        return List.copyOf(messages);
    }

    private static String personaText(PersonaSettings persona, int capacity) {
        String complete = persona.systemInstructions();
        if (complete.length() <= capacity) {
            return complete;
        }
        if (COMPACT_PERSONA.length() > capacity) {
            return null;
        }

        StringBuilder compact = new StringBuilder(COMPACT_PERSONA);
        appendOptional(compact, "\n称呼偏好：", persona.preferredAddress(), capacity);
        appendOptional(compact, "\n口吻偏好：", persona.tone(), capacity);
        return compact.toString();
    }

    private static void appendOptional(StringBuilder target, String label, String value, int capacity) {
        if (value.isEmpty() || target.length() + label.length() + 1 > capacity) {
            return;
        }
        int available = capacity - target.length() - label.length();
        target.append(label).append(truncate(value, available));
    }

    private static List<MemoryEntry> prioritizedMemories(List<MemoryEntry> memories) {
        List<MemoryEntry> preferences = new ArrayList<>();
        List<MemoryEntry> events = new ArrayList<>();
        for (int index = memories.size() - 1; index >= 0; index--) {
            MemoryEntry memory = memories.get(index);
            (memory.source() == MemorySource.USER_EXPLICIT ? preferences : events).add(memory);
        }
        Comparator<MemoryEntry> newestFirst = Comparator.comparing(MemoryEntry::createdAt).reversed();
        preferences.sort(newestFirst);
        events.sort(newestFirst);
        preferences.addAll(events);
        return preferences;
    }

    private static String renderFact(String label, String text, int capacity) {
        int fullLength = label.length() + text.length();
        if (fullLength <= capacity) {
            return label + text;
        }
        int bodyCapacity = capacity - label.length() - 1;
        if (bodyCapacity < 2) {
            return null;
        }
        return label + truncate(text, bodyCapacity);
    }

    private static String truncate(String value, int capacity) {
        if (value.length() <= capacity) {
            return value;
        }
        int bodyLength = Math.max(0, capacity - 1);
        if (bodyLength > 0 && Character.isHighSurrogate(value.charAt(bodyLength - 1))) {
            bodyLength--;
        }
        return value.substring(0, bodyLength) + "…";
    }
}
