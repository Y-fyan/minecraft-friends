package io.github.yfyan.minecraftfriends.memory;

import io.github.yfyan.minecraftfriends.model.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryPromptComposerTest {
    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void bundlesThreeVerifiedEventsAndExplicitPreferenceBesideDefaultPersona() throws IOException {
        CompanionMemoryStore store = store(1);
        store.recordVerifiedEvent("首次见面时一起建造了木屋", NOW);
        store.recordVerifiedEvent("受伤后回到安全地点", NOW.plusSeconds(1));
        store.recordVerifiedEvent("重新加入了这个世界", NOW.plusSeconds(2));
        store.rememberPreference("请称呼我为青禾", NOW.plusSeconds(3));
        RequestContext beforeReply = store.beginRequest();
        assertTrue(store.commitResponse(beforeReply.token(), "重复历史内容", "重复的伙伴回复", NOW.plusSeconds(4)));

        List<ChatMessage> messages = compose(store.beginRequest(), 4, 2_000, 512);
        String prompt = content(messages);

        assertTrue(messages.size() == 2);
        assertTrue(messages.getFirst().content().contains("诚实说明自己是 AI"));
        assertTrue(messages.getFirst().content().contains("尊重玩家自主选择"));
        assertTrue(messages.getFirst().content().contains("不要求玩家持续陪伴"));
        assertTrue(messages.getFirst().content().contains("不表达排他关系"));
        assertTrue(messages.getFirst().content().contains("情绪绑架"));
        assertTrue(prompt.contains("用户明确偏好：请称呼我为青禾"));
        assertTrue(prompt.contains("已验证游戏事件：首次见面时一起建造了木屋"));
        assertTrue(prompt.contains("已验证游戏事件：受伤后回到安全地点"));
        assertTrue(prompt.contains("已验证游戏事件：重新加入了这个世界"));
        assertFalse(prompt.contains("重复历史内容"));
        assertTrue(messages.stream().allMatch(message -> message.content().length() <= 512));
    }

    @Test
    void prioritizesNewPreferenceAndBoundsLongFactsAndManyEvents() throws IOException {
        CompanionMemoryStore store = store(2);
        for (int index = 0; index < 12; index++) {
            store.recordVerifiedEvent("旧事件" + index + "-" + "方".repeat(250), NOW.plusSeconds(index));
        }
        store.rememberPreference("最新偏好：叫我小禾", NOW.plusSeconds(100));

        List<ChatMessage> messages = compose(store.beginRequest(), 3, 180, 80);
        String prompt = content(messages);

        assertTrue(messages.getFirst().content().contains("诚实说明 AI 身份"));
        assertTrue(messages.getFirst().content().contains("尊重玩家自主"));
        assertTrue(messages.getFirst().content().contains("不操控"));
        assertTrue(prompt.contains("用户明确偏好：最新偏好：叫我小禾"));
        assertTrue(prompt.contains("已验证游戏事件：旧事件11-"));
        assertTrue(prompt.indexOf("最新偏好：叫我小禾") < prompt.indexOf("旧事件11-"));
        assertTrue(prompt.contains("…"));
        assertTrue(messages.size() <= 3);
        assertTrue(messages.stream().allMatch(message -> message.content().length() <= 80));
        assertTrue(messages.stream().mapToInt(message -> message.content().length()).sum() <= 180);
        assertFalse(prompt.contains("旧事件0-"));
    }

    @Test
    void removedMemoryIsAbsentFromNewRequestPrompt() throws IOException {
        CompanionMemoryStore store = store(3);
        MemoryEntry removed = store.rememberPreference("删除后不能再提起的秘密", NOW);
        store.rememberPreference("仍保留的偏好", NOW.plusSeconds(1));

        assertTrue(store.deleteMemory(removed.id()));
        String prompt = content(compose(store.beginRequest(), 4, 2_000, 512));

        assertFalse(prompt.contains("删除后不能再提起的秘密"));
        assertTrue(prompt.contains("用户明确偏好：仍保留的偏好"));
    }

    private CompanionMemoryStore store(long scopeId) throws IOException {
        MemoryScope scope = new MemoryScope(new UUID(0, scopeId), new UUID(0, scopeId + 10),
                new UUID(0, scopeId + 100));
        return new CompanionMemoryStore(tempDir.resolve(Long.toString(scopeId)), scope);
    }

    private static List<ChatMessage> compose(RequestContext request, int messages, int chars, int messageChars) {
        return MemoryPromptComposer.compose(request, messages, chars, messageChars);
    }

    private static String content(List<ChatMessage> messages) {
        return String.join("\n", messages.stream().map(ChatMessage::content).toList());
    }
}
