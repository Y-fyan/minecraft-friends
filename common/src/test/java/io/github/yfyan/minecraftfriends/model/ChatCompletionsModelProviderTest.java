package io.github.yfyan.minecraftfriends.model;

import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.yfyan.minecraftfriends.config.ModConfig;
import io.github.yfyan.minecraftfriends.config.ProviderMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatCompletionsModelProviderTest {
    private final List<ModelProvider> providers = new ArrayList<>();

    @AfterEach
    void closeProviders() {
        providers.forEach(ModelProvider::close);
    }

    @Test
    void factoryDoesNotSendUntilCompleteAndParsesChatCompletion() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        AtomicBoolean requestShapeValid = new AtomicBoolean();
        AtomicBoolean authorizationHeaderPresent = new AtomicBoolean();
        try (StubServer stub = new StubServer(exchange -> {
            hits.incrementAndGet();
            String requestJson = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestShapeValid.set(requestJson.contains("\"model\":\"local-test-model\"")
                    && requestJson.contains("\"max_tokens\":512")
                    && requestJson.contains("\"role\":\"user\"")
                    && requestJson.contains("\"messages\""));
            authorizationHeaderPresent.set(exchange.getRequestHeaders().containsKey("Authorization"));
            reply(exchange, 200, success("stub reply"));
        })) {
            ModelProvider provider = create(stub.endpoint(), 0, 65_536);
            assertEquals(0, hits.get());

            ModelResponse response = provider.complete(request(Duration.ofSeconds(3)))
                    .toCompletableFuture().get(3, TimeUnit.SECONDS);

            assertEquals("stub reply", response.content());
            assertEquals(1, hits.get());
            assertTrue(requestShapeValid.get());
            assertFalse(authorizationHeaderPresent.get());
        }
    }

    @Test
    void malformedResponseIsClassifiedWithoutIncludingResponseBody() throws Exception {
        String privateBody = "{'choices':[{'message':{'content':'private-response-sentinel'}}]}";
        try (StubServer stub = new StubServer(exchange -> reply(exchange, 200, privateBody))) {
            ModelCallException failure = awaitFailure(create(stub.endpoint(), 0, 65_536), Duration.ofSeconds(3));

            assertEquals(ModelCallException.Category.INVALID_RESPONSE, failure.category());
            assertFalse(failure.toString().contains(privateBody));
        }
    }

    @Test
    void emptyAndWhitespaceOnlyContentIsRejected() throws Exception {
        List<String> contentCases = List.of("", "  \t\n", "\u2003");
        AtomicInteger contentIndex = new AtomicInteger();
        try (StubServer stub = new StubServer(exchange -> {
            String content = contentCases.get(contentIndex.getAndIncrement());
            reply(exchange, 200, success(content));
        })) {
            ModelProvider provider = create(stub.endpoint(), 0, 65_536);

            for (int index = 0; index < contentCases.size(); index++) {
                ModelCallException failure = awaitFailure(provider, Duration.ofSeconds(3));
                assertEquals(ModelCallException.Category.INVALID_RESPONSE, failure.category());
            }
            assertEquals(contentCases.size(), contentIndex.get());
        }
    }

    @Test
    void unauthorizedResponseHasSafeAuthenticationClassification() throws Exception {
        try (StubServer stub = new StubServer(exchange -> reply(exchange, 401, "stub auth error"))) {
            ModelCallException failure = awaitFailure(create(stub.endpoint(), 0, 65_536), Duration.ofSeconds(3));

            assertEquals(ModelCallException.Category.AUTHENTICATION, failure.category());
            assertTrue(failure.userMessage().contains("认证"));
            assertFalse(failure.toString().contains("stub auth error"));
        }
    }

    @Test
    void rateLimitRetriesOnlyWithinConfiguredBound() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        try (StubServer stub = new StubServer(exchange -> {
            hits.incrementAndGet();
            reply(exchange, 429, "stub rate limit");
        })) {
            ModelCallException failure = awaitFailure(create(stub.endpoint(), 1, 65_536), Duration.ofSeconds(3));

            assertEquals(ModelCallException.Category.RATE_LIMITED, failure.category());
            assertEquals(2, hits.get());
            assertFalse(failure.toString().contains("stub rate limit"));
        }
    }

    @Test
    void refusedLoopbackConnectionIsClassifiedAsNetworkFailure() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = socket.getLocalPort();
        }
        String endpoint = "http://127.0.0.1:" + port + "/v1/chat/completions";

        ModelCallException failure = awaitFailure(create(endpoint, 1, 65_536), Duration.ofSeconds(3));

        assertEquals(ModelCallException.Category.NETWORK, failure.category());
    }

    @Test
    void timeoutCancelsOutstandingHttpExchange() throws Exception {
        CountDownLatch requestReceived = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        CountDownLatch clientDisconnected = new CountDownLatch(1);
        try (StubServer stub = new StubServer(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            requestReceived.countDown();
            await(releaseResponse);
            try {
                writeUntilPeerCloses(exchange);
            } catch (IOException ignored) {
                clientDisconnected.countDown();
            }
        })) {
            ModelProvider provider = create(stub.endpoint(), 0, 65_536);
            var result = provider.complete(request(Duration.ofSeconds(1))).toCompletableFuture();
            assertTrue(requestReceived.await(2, TimeUnit.SECONDS));

            ExecutionException error = assertThrows(
                    ExecutionException.class,
                    () -> result.get(2, TimeUnit.SECONDS)
            );
            ModelCallException failure = assertInstanceOf(ModelCallException.class, error.getCause());
            assertEquals(ModelCallException.Category.TIMEOUT, failure.category());
            releaseResponse.countDown();
            assertTrue(clientDisconnected.await(3, TimeUnit.SECONDS));
        } finally {
            releaseResponse.countDown();
        }
    }

    @Test
    void callerCancellationCancelsOutstandingHttpExchange() throws Exception {
        CountDownLatch requestReceived = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        CountDownLatch clientDisconnected = new CountDownLatch(1);
        try (StubServer stub = new StubServer(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            requestReceived.countDown();
            await(releaseResponse);
            try {
                writeUntilPeerCloses(exchange);
            } catch (IOException ignored) {
                clientDisconnected.countDown();
            }
        })) {
            ModelProvider provider = create(stub.endpoint(), 0, 65_536);
            var result = provider.complete(request(Duration.ofSeconds(4))).toCompletableFuture();
            assertTrue(requestReceived.await(2, TimeUnit.SECONDS));

            assertTrue(result.cancel(true));
            releaseResponse.countDown();
            assertTrue(clientDisconnected.await(3, TimeUnit.SECONDS));
        } finally {
            releaseResponse.countDown();
        }
    }

    @Test
    void responseBodyIsCappedBeforeJsonParsing() throws Exception {
        String oversized = "{\"choices\":[{\"message\":{\"content\":\"" + "x".repeat(2_000) + "\"}}]}";
        try (StubServer stub = new StubServer(exchange -> reply(exchange, 200, oversized))) {
            ModelCallException failure = awaitFailure(create(stub.endpoint(), 0, 1_024), Duration.ofSeconds(3));

            assertEquals(ModelCallException.Category.RESPONSE_TOO_LARGE, failure.category());
        }
    }

    @Test
    void oversizedErrorBodyKeepsTheRateLimitClassification() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        String oversized = "x".repeat(2_000);
        try (StubServer stub = new StubServer(exchange -> {
            hits.incrementAndGet();
            reply(exchange, 429, oversized);
        })) {
            ModelCallException failure = awaitFailure(create(stub.endpoint(), 0, 1_024), Duration.ofSeconds(3));

            assertEquals(ModelCallException.Category.RATE_LIMITED, failure.category());
            assertEquals(1, hits.get());
            assertFalse(failure.toString().contains(oversized));
        }
    }

    @Test
    void missingRemoteCredentialFailsWithoutSendingARequest() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        try (StubServer stub = new StubServer(exchange -> {
            hits.incrementAndGet();
            reply(exchange, 200, success("unexpected"));
        })) {
            ModConfig config = new ModConfig(
                    true,
                    ProviderMode.CHAT_COMPLETIONS,
                    30,
                    true,
                    stub.endpoint(),
                    "local-test-model",
                    "MINECRAFT_FRIENDS_TEST_MISSING_MODEL_KEY",
                    false,
                    0,
                    24,
                    12_000,
                    65_536
            );
            ModelProvider provider = ModelProviders.create(config);

            ModelCallException failure = awaitFailure(provider, Duration.ofSeconds(2));

            assertEquals(ModelCallException.Category.CONFIGURATION, failure.category());
            assertEquals(0, hits.get());
            assertFalse(config.toString().contains("MINECRAFT_FRIENDS_TEST_MISSING_MODEL_KEY"));
        }
    }

    @Test
    void overBudgetContextIsRejectedBeforeNetworkAccess() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        try (StubServer stub = new StubServer(exchange -> {
            hits.incrementAndGet();
            reply(exchange, 200, success("unexpected"));
        })) {
            ModelProvider provider = create(stub.endpoint(), 0, 65_536, 1);
            ModelRequest oversized = new ModelRequest(
                    UUID.randomUUID(),
                    List.of(new ChatMessage(ChatRole.USER, "too many characters")),
                    Duration.ofSeconds(2)
            );

            ModelCallException failure = awaitFailure(provider, oversized);

            assertEquals(ModelCallException.Category.REQUEST_TOO_LARGE, failure.category());
            assertEquals(0, hits.get());
        }
    }

    @Test
    void concurrentRequestsHaveAFixedLimitAndAreNotQueued() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        CountDownLatch fourRequestsReceived = new CountDownLatch(4);
        CountDownLatch releaseResponses = new CountDownLatch(1);
        try (StubServer stub = new StubServer(exchange -> {
            hits.incrementAndGet();
            fourRequestsReceived.countDown();
            await(releaseResponses);
            reply(exchange, 200, success("stub reply"));
        })) {
            ModelProvider provider = create(stub.endpoint(), 0, 65_536);
            List<CompletableFuture<ModelResponse>> outstanding = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                outstanding.add(provider.complete(request(Duration.ofSeconds(4))).toCompletableFuture());
            }
            assertTrue(fourRequestsReceived.await(2, TimeUnit.SECONDS));

            ModelCallException failure = awaitFailure(provider, Duration.ofSeconds(2));

            assertEquals(ModelCallException.Category.OVERLOADED, failure.category());
            assertEquals(4, hits.get());
            releaseResponses.countDown();
            for (CompletableFuture<ModelResponse> result : outstanding) {
                assertEquals("stub reply", result.get(2, TimeUnit.SECONDS).content());
            }
        } finally {
            releaseResponses.countDown();
        }
    }

    private ModelProvider create(String endpoint, int retries, int maxResponseBytes) {
        return create(endpoint, retries, maxResponseBytes, 12_000);
    }

    private ModelProvider create(String endpoint, int retries, int maxResponseBytes, int maxContextCharacters) {
        ModConfig config = new ModConfig(
                true,
                ProviderMode.CHAT_COMPLETIONS,
                30,
                true,
                endpoint,
                "local-test-model",
                "",
                true,
                retries,
                24,
                maxContextCharacters,
                maxResponseBytes
        );
        ModelProvider provider = ModelProviders.create(config);
        providers.add(provider);
        return provider;
    }

    private static ModelRequest request(Duration timeout) {
        return new ModelRequest(
                UUID.randomUUID(),
                List.of(new ChatMessage(ChatRole.USER, "local stub prompt")),
                timeout
        );
    }

    private static ModelCallException awaitFailure(ModelProvider provider, Duration timeout) throws Exception {
        return awaitFailure(provider, request(timeout));
    }

    private static ModelCallException awaitFailure(ModelProvider provider, ModelRequest request) throws Exception {
        ExecutionException error = assertThrows(
                ExecutionException.class,
                () -> provider.complete(request).toCompletableFuture().get(4, TimeUnit.SECONDS)
        );
        return assertInstanceOf(ModelCallException.class, error.getCause());
    }

    private static String success(String content) {
        return "{\"choices\":[{\"message\":{\"content\":" + new JsonPrimitive(content) + "}}]}";
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static void writeUntilPeerCloses(HttpExchange exchange) throws IOException {
        // A small late response can fit in the OS send buffer even after cancellation.
        // Flush bounded chunks so the test observes the peer closing the actual HTTP stream.
        byte[] chunk = new byte[16_384];
        try (var output = exchange.getResponseBody()) {
            for (int index = 0; index < 2_048; index++) {
                output.write(chunk);
                output.flush();
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(4, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private static final class StubServer implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor;

        private StubServer(ExchangeHandler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            executor = Executors.newCachedThreadPool(task -> {
                Thread thread = new Thread(task, "model-http-stub");
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(executor);
            server.createContext("/v1/chat/completions", handler::handle);
            server.start();
        }

        private String endpoint() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
