package io.github.yfyan.minecraftfriends.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import io.github.yfyan.minecraftfriends.config.ModConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Java HttpClient implementation of the OpenAI-compatible chat-completions wire format. */
public final class ChatCompletionsModelProvider implements ModelProvider {
    private static final int MAX_REQUEST_BYTES = 128 * 1024;
    private static final int MAX_OUTPUT_TOKENS = 512;
    private static final int MAX_CONCURRENT_REQUESTS = 4;
    private static final AtomicInteger IN_FLIGHT_REQUESTS = new AtomicInteger();
    private static final ScheduledThreadPoolExecutor TIMER = createTimer();

    private final URI endpoint;
    private final String modelName;
    private final String apiKey;
    private final int configuredTimeoutSeconds;
    private final int retries;
    private final int maxContextMessages;
    private final int maxContextCharacters;
    private final int maxResponseBytes;
    private final ModelCallException configurationFailure;
    private final HttpClient client;
    private final Set<RequestControl> requests = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    ChatCompletionsModelProvider(ModConfig config) {
        URI parsedEndpoint = null;
        String resolvedKey = null;
        ModelCallException failure = null;
        try {
            parsedEndpoint = parseAndValidateEndpoint(config.modelEndpoint());
            validateModelName(config.modelName());
            resolvedKey = resolveCredential(config, parsedEndpoint);
        } catch (ModelCallException exception) {
            failure = exception;
        }
        endpoint = parsedEndpoint;
        modelName = config.modelName();
        apiKey = resolvedKey;
        configuredTimeoutSeconds = config.requestTimeoutSeconds();
        retries = config.modelRetries();
        maxContextMessages = config.modelMaxContextMessages();
        maxContextCharacters = config.modelMaxContextCharacters();
        maxResponseBytes = config.modelMaxResponseBytes();
        configurationFailure = failure;
        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public String id() {
        return "chat-completions";
    }

    @Override
    public CompletionStage<ModelResponse> complete(ModelRequest request) {
        Objects.requireNonNull(request, "request");
        if (configurationFailure != null) {
            return CompletableFuture.failedFuture(configurationFailure);
        }
        if (closed) {
            return CompletableFuture.failedFuture(new ModelCallException(ModelCallException.Category.CANCELLED));
        }

        final byte[] requestBody;
        try {
            requestBody = encodeRequest(request);
        } catch (ModelCallException exception) {
            return CompletableFuture.failedFuture(exception);
        }

        if (IN_FLIGHT_REQUESTS.incrementAndGet() > MAX_CONCURRENT_REQUESTS) {
            IN_FLIGHT_REQUESTS.decrementAndGet();
            return CompletableFuture.failedFuture(new ModelCallException(ModelCallException.Category.OVERLOADED));
        }
        RequestControl control = new RequestControl();
        requests.add(control);
        if (closed) {
            control.result.cancel(true);
            return control.result;
        }
        Duration timeout = request.timeout();
        Duration configuredTimeout = Duration.ofSeconds(configuredTimeoutSeconds);
        if (timeout.compareTo(configuredTimeout) > 0) {
            timeout = configuredTimeout;
        }
        control.start(requestBody, timeout);
        return control.result;
    }

    @Override
    public void close() {
        closed = true;
        for (RequestControl request : requests) {
            request.result.cancel(true);
        }
        client.shutdownNow();
    }

    private byte[] encodeRequest(ModelRequest request) {
        List<ChatMessage> messages = request.messages();
        if (messages.size() > maxContextMessages) {
            throw new ModelCallException(ModelCallException.Category.REQUEST_TOO_LARGE);
        }
        long characters = 0;
        for (ChatMessage message : messages) {
            characters += message.content().length();
            if (characters > maxContextCharacters) {
                throw new ModelCallException(ModelCallException.Category.REQUEST_TOO_LARGE);
            }
        }

        JsonObject body = new JsonObject();
        body.addProperty("model", modelName);
        body.addProperty("max_tokens", MAX_OUTPUT_TOKENS);
        JsonArray encodedMessages = new JsonArray();
        for (ChatMessage message : messages) {
            JsonObject encoded = new JsonObject();
            encoded.addProperty("role", message.role().name().toLowerCase(Locale.ROOT));
            encoded.addProperty("content", message.content());
            encodedMessages.add(encoded);
        }
        body.add("messages", encodedMessages);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_REQUEST_BYTES) {
            throw new ModelCallException(ModelCallException.Category.REQUEST_TOO_LARGE);
        }
        return bytes;
    }

    private void attempt(RequestControl control, byte[] body, int attemptNumber) {
        if (control.result.isDone()) {
            return;
        }
        long remainingNanos = control.remainingNanos();
        if (remainingNanos <= 0) {
            control.fail(ModelCallException.Category.TIMEOUT);
            return;
        }

        final CompletableFuture<HttpResponse<byte[]>> exchange;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofNanos(Math.max(1, remainingNanos)))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            if (apiKey != null) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            exchange = client.sendAsync(
                    builder.build(),
                    info -> new LimitedBodySubscriber(maxResponseBytes, info.statusCode())
            );
        } catch (RuntimeException ignored) {
            control.fail(ModelCallException.Category.NETWORK);
            return;
        }
        control.setExchange(exchange);
        exchange.whenComplete((response, throwable) -> {
            control.clearExchange(exchange);
            if (control.result.isDone()) {
                return;
            }
            if (throwable != null) {
                Throwable cause = unwrap(throwable);
                if (cause instanceof ResponseTooLargeException tooLarge) {
                    int status = tooLarge.statusCode;
                    if (attemptNumber < retries && isRetryableStatus(status)) {
                        control.retry(body, attemptNumber + 1, 100L * (attemptNumber + 1));
                    } else if (status < 200 || status >= 300) {
                        control.fail(categoryForStatus(status));
                    } else {
                        control.fail(ModelCallException.Category.RESPONSE_TOO_LARGE);
                    }
                } else if (cause instanceof HttpTimeoutException) {
                    control.fail(ModelCallException.Category.TIMEOUT);
                } else if (cause instanceof CancellationException) {
                    control.fail(ModelCallException.Category.CANCELLED);
                } else if (attemptNumber < retries && isNetworkFailure(cause)) {
                    control.retry(body, attemptNumber + 1, 100L * (attemptNumber + 1));
                } else {
                    control.fail(ModelCallException.Category.NETWORK);
                }
                return;
            }

            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                try {
                    control.succeed(new ModelResponse(parseContent(response.body())));
                } catch (ModelCallException exception) {
                    control.fail(exception.category());
                }
                return;
            }

            if (attemptNumber < retries && isRetryableStatus(status)) {
                control.retry(body, attemptNumber + 1, retryDelayMillis(response, attemptNumber));
                return;
            }
            control.fail(categoryForStatus(status));
        });
    }

    private String parseContent(byte[] responseBody) {
        try {
            String json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(responseBody))
                    .toString();
            JsonReader reader = new JsonReader(new StringReader(json));
            reader.setLenient(false);
            reader.beginObject();
            String content = null;
            boolean choicesSeen = false;
            while (reader.hasNext()) {
                if ("choices".equals(reader.nextName())) {
                    if (choicesSeen) {
                        throw new ModelCallException(ModelCallException.Category.INVALID_RESPONSE);
                    }
                    choicesSeen = true;
                    content = readFirstChoiceContent(reader);
                } else {
                    reader.skipValue();
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT || content == null) {
                throw new ModelCallException(ModelCallException.Category.INVALID_RESPONSE);
            }
            return content;
        } catch (ModelCallException exception) {
            throw exception;
        } catch (RuntimeException | IOException ignored) {
            throw new ModelCallException(ModelCallException.Category.INVALID_RESPONSE);
        }
    }

    private static String readFirstChoiceContent(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            throw new ModelCallException(ModelCallException.Category.INVALID_RESPONSE);
        }
        reader.beginArray();
        if (!reader.hasNext() || reader.peek() != JsonToken.BEGIN_OBJECT) {
            throw new ModelCallException(ModelCallException.Category.INVALID_RESPONSE);
        }
        reader.beginObject();
        String content = null;
        boolean messageSeen = false;
        while (reader.hasNext()) {
            if ("message".equals(reader.nextName())) {
                if (messageSeen) {
                    throw new ModelCallException(ModelCallException.Category.INVALID_RESPONSE);
                }
                messageSeen = true;
                content = readMessageContent(reader);
            } else {
                reader.skipValue();
            }
        }
        reader.endObject();
        while (reader.hasNext()) {
            reader.skipValue();
        }
        reader.endArray();
        return content;
    }

    private static String readMessageContent(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            throw new ModelCallException(ModelCallException.Category.INVALID_RESPONSE);
        }
        reader.beginObject();
        String content = null;
        boolean contentSeen = false;
        while (reader.hasNext()) {
            if ("content".equals(reader.nextName())) {
                if (contentSeen || reader.peek() != JsonToken.STRING) {
                    throw new ModelCallException(ModelCallException.Category.INVALID_RESPONSE);
                }
                contentSeen = true;
                content = reader.nextString();
            } else {
                reader.skipValue();
            }
        }
        reader.endObject();
        return content;
    }

    private String resolveCredential(ModConfig config, URI uri) {
        String variable = config.apiKeyEnvironmentVariable();
        if (!variable.isBlank() && !variable.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new ModelCallException(ModelCallException.Category.CONFIGURATION);
        }
        String configuredKey = null;
        if (!variable.isBlank()) {
            try {
                configuredKey = System.getenv(variable);
            } catch (SecurityException ignored) {
                throw new ModelCallException(ModelCallException.Category.CONFIGURATION);
            }
            if (configuredKey == null || configuredKey.isBlank()
                    || configuredKey.length() > 8_192
                    || configuredKey.chars().anyMatch(character -> character < 0x21 || character > 0x7e)) {
                throw new ModelCallException(ModelCallException.Category.CONFIGURATION);
            }
        }
        if (configuredKey != null) {
            return configuredKey;
        }
        if (config.allowUnauthenticatedLocalEndpoint() && isLoopbackHost(uri.getHost())) {
            return null;
        }
        throw new ModelCallException(ModelCallException.Category.CONFIGURATION);
    }

    private static URI parseAndValidateEndpoint(String rawEndpoint) {
        try {
            if (rawEndpoint.length() > 2_048) {
                throw new IllegalArgumentException();
            }
            URI uri = URI.create(rawEndpoint);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            boolean loopback = isLoopbackHost(host);
            if (host == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || uri.getPort() > 65_535
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || ("http".equalsIgnoreCase(scheme) && !loopback)) {
                throw new ModelCallException(ModelCallException.Category.CONFIGURATION);
            }
            return uri;
        } catch (IllegalArgumentException ignored) {
            throw new ModelCallException(ModelCallException.Category.CONFIGURATION);
        }
    }

    private static void validateModelName(String modelName) {
        if (modelName.isBlank() || modelName.length() > 128
                || modelName.chars().anyMatch(Character::isISOControl)) {
            throw new ModelCallException(ModelCallException.Category.CONFIGURATION);
        }
    }

    private static boolean isLoopbackHost(String host) {
        return host != null && ("localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "[::1]".equals(host)
                || "::1".equals(host));
    }

    private static boolean isNetworkFailure(Throwable cause) {
        return cause instanceof IOException;
    }

    private static boolean isRetryableStatus(int status) {
        return status == 408 || status == 429 || status >= 500;
    }

    private static long retryDelayMillis(HttpResponse<?> response, int attemptNumber) {
        String retryAfter = response.headers().firstValue("Retry-After").orElse("");
        try {
            long seconds = Long.parseLong(retryAfter.trim());
            return seconds <= 0 ? 0 : 1_000;
        } catch (NumberFormatException ignored) {
            return Math.min(500, 100L * (attemptNumber + 1));
        }
    }

    private static ModelCallException.Category categoryForStatus(int status) {
        if (status == 401 || status == 403) {
            return ModelCallException.Category.AUTHENTICATION;
        }
        if (status == 429) {
            return ModelCallException.Category.RATE_LIMITED;
        }
        if (status == 408 || status == 504) {
            return ModelCallException.Category.TIMEOUT;
        }
        if (status >= 500) {
            return ModelCallException.Category.SERVICE_UNAVAILABLE;
        }
        return ModelCallException.Category.SERVICE_REJECTED;
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static ScheduledThreadPoolExecutor createTimer() {
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "minecraft-friends-model-timeouts");
            thread.setDaemon(true);
            return thread;
        });
        timer.setRemoveOnCancelPolicy(true);
        return timer;
    }

    private final class RequestControl {
        private final CancellableFuture result = new CancellableFuture();
        private final AtomicReference<CompletableFuture<?>> exchange = new AtomicReference<>();
        private final AtomicReference<ScheduledFuture<?>> retryTask = new AtomicReference<>();
        private volatile ScheduledFuture<?> timeoutTask;
        private volatile long timeoutStartedNanos;
        private volatile long timeoutBudgetNanos;

        private RequestControl() {
            result.onCancel(this::cancelInFlight);
            result.whenComplete((ignored, failure) -> cleanup());
        }

        private void start(byte[] body, Duration timeout) {
            timeoutStartedNanos = System.nanoTime();
            timeoutBudgetNanos = timeout.toNanos();
            ScheduledFuture<?> scheduledTimeout = TIMER.schedule(
                    () -> fail(ModelCallException.Category.TIMEOUT),
                    timeoutBudgetNanos,
                    TimeUnit.NANOSECONDS
            );
            timeoutTask = scheduledTimeout;
            if (result.isDone()) {
                scheduledTimeout.cancel(false);
                return;
            }
            ChatCompletionsModelProvider.this.attempt(this, body, 0);
        }

        private long remainingNanos() {
            return timeoutBudgetNanos - (System.nanoTime() - timeoutStartedNanos);
        }

        private void setExchange(CompletableFuture<?> current) {
            exchange.set(current);
            if (result.isDone()) {
                current.cancel(true);
            }
        }

        private void clearExchange(CompletableFuture<?> current) {
            exchange.compareAndSet(current, null);
        }

        private void retry(byte[] body, int nextAttempt, long delayMillis) {
            ScheduledFuture<?> scheduled = TIMER.schedule(() -> {
                retryTask.set(null);
                ChatCompletionsModelProvider.this.attempt(this, body, nextAttempt);
            }, delayMillis, TimeUnit.MILLISECONDS);
            ScheduledFuture<?> previous = retryTask.getAndSet(scheduled);
            if (previous != null) {
                previous.cancel(true);
            }
            if (result.isDone()) {
                scheduled.cancel(true);
            }
        }

        private void succeed(ModelResponse response) {
            result.complete(response);
        }

        private void fail(ModelCallException.Category category) {
            result.completeExceptionally(new ModelCallException(category));
        }

        private void cancelInFlight() {
            CompletableFuture<?> current = exchange.getAndSet(null);
            if (current != null) {
                current.cancel(true);
            }
            ScheduledFuture<?> retry = retryTask.getAndSet(null);
            if (retry != null) {
                retry.cancel(true);
            }
        }

        private void cleanup() {
            ScheduledFuture<?> timeout = timeoutTask;
            if (timeout != null) {
                timeout.cancel(false);
            }
            cancelInFlight();
            requests.remove(this);
            IN_FLIGHT_REQUESTS.decrementAndGet();
        }
    }

    private static final class CancellableFuture extends CompletableFuture<ModelResponse> {
        private volatile Runnable cancellation = () -> { };

        private void onCancel(Runnable action) {
            cancellation = action;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            if (cancelled) {
                cancellation.run();
            }
            return cancelled;
        }
    }

    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int maxBytes;
        private final int statusCode;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private boolean tooLarge;

        private LimitedBodySubscriber(int maxBytes, int statusCode) {
            this.maxBytes = maxBytes;
            this.statusCode = statusCode;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription newSubscription) {
            subscription = newSubscription;
            newSubscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) {
                return;
            }
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > maxBytes - output.size()) {
                    tooLarge = true;
                    subscription.cancel();
                    body.completeExceptionally(new ResponseTooLargeException(statusCode));
                    return;
                }
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                output.write(bytes, 0, bytes.length);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable throwable) {
            if (tooLarge) {
                body.completeExceptionally(new ResponseTooLargeException(statusCode));
            } else {
                body.completeExceptionally(throwable);
            }
        }

        @Override
        public void onComplete() {
            if (!tooLarge) {
                body.complete(output.toByteArray());
            }
        }
    }

    private static final class ResponseTooLargeException extends RuntimeException {
        private final int statusCode;

        private ResponseTooLargeException(int statusCode) {
            this.statusCode = statusCode;
        }
    }
}
