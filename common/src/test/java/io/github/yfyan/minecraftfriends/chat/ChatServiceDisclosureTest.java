package io.github.yfyan.minecraftfriends.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatServiceDisclosureTest {
    @Test
    void endpointNoticeExposesOnlyUriHost() {
        assertEquals("models.example.test", ChatServiceDisclosure.endpointHost(
                "https://private-user:private-password@models.example.test:8443/v1/chat/completions?key=secret"));
    }

    @Test
    void endpointNoticeDoesNotFallBackToMalformedUrl() {
        assertEquals("未知", ChatServiceDisclosure.endpointHost("not a valid endpoint"));
    }
}
