package io.github.yfyan.minecraftfriends.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelProtocolTest {
    @Test
    void mockProviderCompletesWithoutNetworkAccess() throws ExecutionException, InterruptedException {
        ModelRequest request = new ModelRequest(
                UUID.randomUUID(),
                List.of(new ChatMessage(ChatRole.USER, "hello")),
                Duration.ofSeconds(5)
        );

        ModelResponse response = new MockModelProvider().complete(request).toCompletableFuture().get();

        assertEquals("Mock response", response.content());
    }

    @Test
    void rejectsAnEmptyConversation() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ModelRequest(UUID.randomUUID(), List.of(), Duration.ofSeconds(1))
        );
    }
}
