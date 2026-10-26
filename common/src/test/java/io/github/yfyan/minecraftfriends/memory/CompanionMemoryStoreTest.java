package io.github.yfyan.minecraftfriends.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionMemoryStoreTest {
    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void isolatesMemoriesByWorldAndOwner() throws IOException {
        MemoryScope firstScope = scope(1, 10, 100);
        MemoryScope otherWorld = scope(2, 10, 100);
        MemoryScope otherOwner = scope(1, 11, 100);
        CompanionMemoryStore first = new CompanionMemoryStore(tempDir, firstScope);
        CompanionMemoryStore worldStore = new CompanionMemoryStore(tempDir, otherWorld);
        CompanionMemoryStore ownerStore = new CompanionMemoryStore(tempDir, otherOwner);

        first.rememberPreference("称呼我为青禾", NOW);

        assertEquals(1, first.listMemories().size());
        assertTrue(worldStore.listMemories().isEmpty());
        assertTrue(ownerStore.listMemories().isEmpty());
    }

    @Test
    void reconstructsStoreWithUtf8PreferenceAndPersona() throws IOException {
        MemoryScope scope = scope(3, 12, 103);
        CompanionMemoryStore first = new CompanionMemoryStore(tempDir, scope);
        first.rememberPreference("请叫我小雨", NOW);
        first.setPersona(new PersonaSettings("轻松、耐心", "小雨"));

        CompanionMemoryStore reopened = new CompanionMemoryStore(tempDir, scope);

        assertEquals("请叫我小雨", reopened.listMemories().getFirst().text());
        assertEquals(new PersonaSettings("轻松、耐心", "小雨"), reopened.persona());
        assertTrue(reopened.persona().systemInstructions().contains("AI 伙伴"));
        assertTrue(reopened.persona().systemInstructions().contains("不表达排他关系"));
    }

    @Test
    void clearAndSingleDeleteInvalidateLateRequestTokens() throws IOException {
        CompanionMemoryStore store = new CompanionMemoryStore(tempDir, scope(4, 13, 104));
        MemoryEntry removable = store.rememberPreference("我喜欢探索洞穴", NOW);
        store.rememberPreference("称呼我为小禾", NOW);

        RequestContext beforeDelete = store.beginRequest();
        assertTrue(store.deleteMemory(removable.id()));
        assertEquals(beforeDelete.token().generation() + 1, store.beginRequest().token().generation());
        assertFalse(store.commitResponse(beforeDelete.token(), "迟到的问题", "迟到的回答", NOW));
        assertTrue(store.beginRequest().shortTermContext().isEmpty());

        RequestContext beforeClear = store.beginRequest();
        store.clear();
        assertEquals(beforeClear.token().generation() + 1, store.beginRequest().token().generation());
        assertFalse(store.commitResponse(beforeClear.token(), "旧问题", "旧回答", NOW));
        assertTrue(store.listMemories().isEmpty());
        assertTrue(store.beginRequest().shortTermContext().isEmpty());
    }

    @Test
    void capsShortTermContextAndRejectsOversizedFields() throws IOException {
        CompanionMemoryStore store = new CompanionMemoryStore(tempDir, scope(5, 14, 105));
        for (int index = 0; index < 8; index++) {
            RequestContext request = store.beginRequest();
            assertTrue(store.commitResponse(request.token(), "用户消息 " + index, "伙伴回复 " + index, NOW));
        }

        List<ContextMessage> context = store.beginRequest().shortTermContext();
        assertEquals(CompanionMemoryStore.MAX_CONTEXT_MESSAGES, context.size());
        assertEquals(ContextSpeaker.USER, context.getFirst().speaker());
        assertTrue(context.getFirst().text().contains("2"));
        assertThrows(IllegalArgumentException.class,
                () -> store.rememberPreference("x".repeat(MemoryEntry.MAX_TEXT_CHARS + 1), NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new PersonaSettings("t".repeat(PersonaSettings.MAX_FIELD_CHARS + 1), ""));
    }

    @Test
    void proactiveEventsAreEnabledAndCooledDownByDefault() throws IOException {
        CompanionMemoryStore store = new CompanionMemoryStore(tempDir, scope(6, 15, 106));

        assertTrue(store.isProactiveEnabled());
        assertTrue(store.tryClaimProactiveEvent(ProactiveEvent.FIRST_MEETING, NOW));
        assertFalse(store.tryClaimProactiveEvent(ProactiveEvent.FIRST_MEETING, NOW.plusSeconds(3_600)));
        assertTrue(store.tryClaimProactiveEvent(ProactiveEvent.REJOIN, NOW));
        assertFalse(store.tryClaimProactiveEvent(ProactiveEvent.REJOIN, NOW.plusSeconds(30)));
        assertTrue(store.tryClaimProactiveEvent(
                ProactiveEvent.REJOIN, NOW.plus(CompanionMemoryStore.PROACTIVE_COOLDOWN)));
        assertTrue(store.tryClaimProactiveEvent(ProactiveEvent.INJURED, NOW));
        assertFalse(store.tryClaimProactiveEvent(ProactiveEvent.INJURED, NOW.plusSeconds(1)));

        store.setProactiveEnabled(false);
        assertFalse(store.tryClaimProactiveEvent(ProactiveEvent.INJURED, NOW.plusSeconds(20_000)));
    }

    @Test
    void reportsCorruptStoreWithoutReplacingIt() throws IOException {
        MemoryScope scope = scope(7, 16, 107);
        CompanionMemoryStore store = new CompanionMemoryStore(tempDir, scope);
        store.rememberPreference("这是用于损坏检测的偏好", NOW);
        Path file = memoryFile(tempDir, scope);
        byte[] damaged = "schema=99\nsecret-like-test-value=keep\n".getBytes(StandardCharsets.UTF_8);
        Files.write(file, damaged);

        MemoryStoreFormatException failure = assertThrows(
                MemoryStoreFormatException.class, () -> new CompanionMemoryStore(tempDir, scope));

        assertTrue(failure.getMessage().contains("unsupported schema version 99"));
        assertEquals("schema=99\nsecret-like-test-value=keep\n", Files.readString(file));
    }

    private static MemoryScope scope(long world, long owner, long companion) {
        return new MemoryScope(new UUID(0, world), new UUID(0, owner), new UUID(0, companion));
    }

    private static Path memoryFile(Path root, MemoryScope scope) {
        return root.resolve(scope.worldId().toString())
                .resolve(scope.ownerId().toString())
                .resolve(scope.companionId().toString())
                .resolve("memory.properties");
    }
}
