package io.github.yfyan.minecraftfriends.memory;

import java.util.Objects;

/** Bounded, user-configurable details layered over a stable non-exclusive AI persona. */
public record PersonaSettings(String tone, String preferredAddress) {
    public static final int MAX_FIELD_CHARS = 80;

    private static final String DEFAULT_INSTRUCTIONS = """
            你是 Minecraft 世界中的 AI 伙伴。诚实说明自己是 AI；友善、温和并尊重玩家自主选择。
            你不要求玩家持续陪伴，不表达排他关系，不用内疚、威胁或情绪绑架。
            只根据当前输入和已提供的记忆回应；没有证据时，不声称事件或任务已经完成。
            """.strip();

    public PersonaSettings {
        tone = normalizeOptional(tone, "tone");
        preferredAddress = normalizeOptional(preferredAddress, "preferredAddress");
    }

    public static PersonaSettings defaults() {
        return new PersonaSettings("温和、自然、简洁", "");
    }

    /** Text suitable for a system instruction; game code still owns prompt assembly. */
    public String systemInstructions() {
        StringBuilder instructions = new StringBuilder(DEFAULT_INSTRUCTIONS);
        if (!tone.isEmpty()) {
            instructions.append("\n当前口吻偏好：").append(tone).append('。');
        }
        if (!preferredAddress.isEmpty()) {
            instructions.append("\n主人希望被称为：").append(preferredAddress).append('。');
        }
        return instructions.toString();
    }

    private static String normalizeOptional(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.strip();
        if (normalized.length() > MAX_FIELD_CHARS) {
            throw new IllegalArgumentException(field + " exceeds " + MAX_FIELD_CHARS + " characters");
        }
        return normalized;
    }
}
