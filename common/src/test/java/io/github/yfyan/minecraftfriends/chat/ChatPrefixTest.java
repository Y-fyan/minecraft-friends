package io.github.yfyan.minecraftfriends.chat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatPrefixTest {
    @Test
    void parsesOnlyAWellFormedCompanionAddress() {
        ChatPrefix.Address address = ChatPrefix.parse("@MF_Alex 你好，伙伴", "@").orElseThrow();

        assertEquals("MF_Alex", address.companionName());
        assertEquals("你好，伙伴", address.message());
    }

    @Test
    void leavesOrdinaryChatAndCommandsUntouched() {
        assertTrue(ChatPrefix.parse("普通聊天", "@").isEmpty());
        assertTrue(ChatPrefix.parse("/mf chat 你好", "@").isEmpty());
        assertTrue(ChatPrefix.parse("@MF_Alex", "@").isEmpty());
        assertTrue(ChatPrefix.parse("@not-a-name hi", "@").isEmpty());
    }

    @Test
    void ownerPrefixKeepsItsPrivateRouteWhenASharedRouteAlsoExists() {
        assertEquals(List.of("private"), ChatAddressRouter.preferOwnedPrivate(
                "private", List.of("shared")));
        assertEquals(List.of("shared"), ChatAddressRouter.preferOwnedPrivate(
                null, List.of("shared")));
    }
}
