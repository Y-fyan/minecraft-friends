package io.github.yfyan.minecraftfriends.memory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/**
 * Pure-Java, per-companion memory with bounded in-memory context and atomic durable facts.
 * Operations on one instance are synchronized; callers should keep one instance per scope.
 */
public final class CompanionMemoryStore {
    public static final int MAX_MEMORIES = 64;
    public static final int MAX_CONTEXT_MESSAGES = 12;
    public static final int MAX_CONTEXT_CHARS = 6_000;
    public static final long MAX_FILE_BYTES = 256 * 1024;
    public static final Duration PROACTIVE_COOLDOWN = Duration.ofMinutes(10);

    private static final int SCHEMA_VERSION = 1;
    private static final String FILE_NAME = "memory.properties";

    private final MemoryScope scope;
    private final Path file;
    private final UUID storeInstanceId = UUID.randomUUID();
    private final ArrayDeque<ContextMessage> shortTermContext = new ArrayDeque<>();
    private MemoryState state;

    /**
     * Opens the store under {@code persistenceRoot}. Existing invalid files fail explicitly and
     * are never replaced. A missing file starts with defaults and is created on the first change.
     */
    public CompanionMemoryStore(Path persistenceRoot, MemoryScope scope) throws IOException {
        Objects.requireNonNull(persistenceRoot, "persistenceRoot");
        this.scope = Objects.requireNonNull(scope, "scope");
        Path root = persistenceRoot.toAbsolutePath().normalize();
        this.file = root.resolve(scope.worldId().toString())
                .resolve(scope.ownerId().toString())
                .resolve(scope.companionId().toString())
                .resolve(FILE_NAME);
        this.state = Files.exists(file) ? readState() : MemoryState.defaults();
    }

    public MemoryScope scope() {
        return scope;
    }

    /** Captures prompt data and a token that must accompany the eventual response. */
    public synchronized RequestContext beginRequest() {
        MemoryRequestToken token = new MemoryRequestToken(scope, storeInstanceId, state.generation());
        return new RequestContext(token, state.persona(), state.memories(), List.copyOf(shortTermContext));
    }

    /**
     * Commits one completed user/companion exchange to bounded short-term context.
     * Returns false if clear/delete (or a different store instance) made the token stale.
     */
    public synchronized boolean commitResponse(
            MemoryRequestToken token, String userText, String companionText, Instant completedAt) {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(completedAt, "completedAt");
        ContextMessage user = new ContextMessage(ContextSpeaker.USER, userText, completedAt);
        ContextMessage companion = new ContextMessage(ContextSpeaker.COMPANION, companionText, completedAt);
        if (!isCurrent(token)) {
            return false;
        }
        appendContext(user);
        appendContext(companion);
        return true;
    }

    public synchronized boolean isCurrent(MemoryRequestToken token) {
        Objects.requireNonNull(token, "token");
        return token.scope().equals(scope)
                && token.storeInstanceId().equals(storeInstanceId)
                && token.generation() == state.generation();
    }

    /** Records only a preference explicitly stated by the user. */
    public synchronized MemoryEntry rememberPreference(String text, Instant occurredAt) throws IOException {
        return addMemory(MemoryKind.PREFERENCE, MemorySource.USER_EXPLICIT, text, occurredAt);
    }

    /**
     * Records an event only after the game adapter has independently verified it happened.
     * This API deliberately accepts no model-generated provenance.
     */
    public synchronized MemoryEntry recordVerifiedEvent(String text, Instant occurredAt) throws IOException {
        return addMemory(MemoryKind.EVENT, MemorySource.VERIFIED_GAME_EVENT, text, occurredAt);
    }

    public synchronized List<MemoryEntry> listMemories() {
        return List.copyOf(state.memories());
    }

    /**
     * Advances the generation even when the id is absent, then clears short-term context.
     * The return value indicates whether a durable memory item was found and removed.
     */
    public synchronized boolean deleteMemory(UUID id) throws IOException {
        Objects.requireNonNull(id, "id");
        List<MemoryEntry> nextEntries = state.memories().stream()
                .filter(entry -> !entry.id().equals(id))
                .toList();
        boolean removed = nextEntries.size() != state.memories().size();
        MemoryState next = new MemoryState(
                nextGeneration(), state.persona(), state.proactiveEnabled(), nextEntries,
                state.lastProactiveEvent());
        saveState(next);
        state = next;
        shortTermContext.clear();
        return removed;
    }

    /** Clears durable memories, persona customization, cooldown history, and short-term context. */
    public synchronized void clear() throws IOException {
        MemoryState next = new MemoryState(
                nextGeneration(), PersonaSettings.defaults(), state.proactiveEnabled(), List.of(), Map.of());
        saveState(next);
        state = next;
        shortTermContext.clear();
    }

    public synchronized PersonaSettings persona() {
        return state.persona();
    }

    public synchronized void setPersona(PersonaSettings persona) throws IOException {
        Objects.requireNonNull(persona, "persona");
        MemoryState next = new MemoryState(
                state.generation(), persona, state.proactiveEnabled(), state.memories(), state.lastProactiveEvent());
        saveState(next);
        state = next;
    }

    public synchronized boolean isProactiveEnabled() {
        return state.proactiveEnabled();
    }

    public synchronized void setProactiveEnabled(boolean enabled) throws IOException {
        MemoryState next = new MemoryState(
                state.generation(), state.persona(), enabled, state.memories(), state.lastProactiveEvent());
        saveState(next);
        state = next;
    }

    /**
     * Claims a real event for proactive output if enabled and outside its cooldown.
     * FIRST_MEETING is claimable once until clear; REJOIN and INJURED use a ten-minute cooldown.
     */
    public synchronized boolean tryClaimProactiveEvent(ProactiveEvent event, Instant occurredAt) throws IOException {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (!state.proactiveEnabled()) {
            return false;
        }
        Instant previous = state.lastProactiveEvent().get(event);
        if (previous != null) {
            if (event == ProactiveEvent.FIRST_MEETING
                    || occurredAt.isBefore(previous)
                    || Duration.between(previous, occurredAt).compareTo(PROACTIVE_COOLDOWN) < 0) {
                return false;
            }
        }
        Map<ProactiveEvent, Instant> nextEvents = new EnumMap<>(ProactiveEvent.class);
        nextEvents.putAll(state.lastProactiveEvent());
        nextEvents.put(event, occurredAt);
        MemoryState next = new MemoryState(
                state.generation(), state.persona(), state.proactiveEnabled(), state.memories(), nextEvents);
        saveState(next);
        state = next;
        return true;
    }

    private MemoryEntry addMemory(MemoryKind kind, MemorySource source, String text, Instant occurredAt)
            throws IOException {
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (state.memories().size() >= MAX_MEMORIES) {
            throw new IllegalStateException("Memory limit reached; delete an item before adding another");
        }
        MemoryEntry entry = new MemoryEntry(UUID.randomUUID(), kind, source, text, occurredAt);
        List<MemoryEntry> nextEntries = new ArrayList<>(state.memories());
        nextEntries.add(entry);
        MemoryState next = new MemoryState(
                state.generation(), state.persona(), state.proactiveEnabled(), nextEntries,
                state.lastProactiveEvent());
        saveState(next);
        state = next;
        return entry;
    }

    private void appendContext(ContextMessage message) {
        shortTermContext.addLast(message);
        while (shortTermContext.size() > MAX_CONTEXT_MESSAGES || contextChars() > MAX_CONTEXT_CHARS) {
            shortTermContext.removeFirst();
        }
    }

    private int contextChars() {
        return shortTermContext.stream().mapToInt(message -> message.text().length()).sum();
    }

    private long nextGeneration() throws IOException {
        if (state.generation() == Long.MAX_VALUE) {
            throw new IOException("Companion memory generation is exhausted");
        }
        return state.generation() + 1;
    }

    private MemoryState readState() throws IOException {
        try {
            long fileBytes = Files.size(file);
            if (fileBytes > MAX_FILE_BYTES) {
                throw formatError("file exceeds the " + MAX_FILE_BYTES + " byte limit");
            }
            StrictProperties properties = new StrictProperties();
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            return decode(properties);
        } catch (MemoryStoreFormatException exception) {
            throw exception;
        } catch (IllegalArgumentException | DateTimeException exception) {
            throw formatError("malformed property or schema value");
        }
    }

    private MemoryState decode(Properties properties) throws MemoryStoreFormatException {
        int schema = parseInt(required(properties, "schema"), "schema");
        if (schema != SCHEMA_VERSION) {
            throw formatError("unsupported schema version " + schema);
        }
        if (!scope.worldId().toString().equals(required(properties, "scope.world"))
                || !scope.ownerId().toString().equals(required(properties, "scope.owner"))
                || !scope.companionId().toString().equals(required(properties, "scope.companion"))) {
            throw formatError("scope identity does not match the requested world, owner, and companion");
        }
        long generation = parseLong(required(properties, "generation"), "generation");
        if (generation < 0) {
            throw formatError("generation must not be negative");
        }

        PersonaSettings persona;
        try {
            persona = new PersonaSettings(required(properties, "persona.tone"),
                    required(properties, "persona.preferredAddress"));
        } catch (IllegalArgumentException exception) {
            throw formatError("persona fields failed validation");
        }
        String enabledValue = required(properties, "proactive.enabled");
        if (!enabledValue.equals("true") && !enabledValue.equals("false")) {
            throw formatError("proactive.enabled must be true or false");
        }
        boolean proactiveEnabled = Boolean.parseBoolean(enabledValue);

        EnumMap<ProactiveEvent, Instant> lastEvents = new EnumMap<>(ProactiveEvent.class);
        for (ProactiveEvent event : ProactiveEvent.values()) {
            String value = required(properties, eventKey(event));
            if (!value.isEmpty()) {
                lastEvents.put(event, parseInstant(value, eventKey(event)));
            }
        }

        int count = parseInt(required(properties, "memories.count"), "memories.count");
        if (count < 0 || count > MAX_MEMORIES) {
            throw formatError("memories.count is outside the supported range");
        }
        validateKeys(properties, count);
        List<MemoryEntry> memories = new ArrayList<>(count);
        Set<UUID> ids = new HashSet<>();
        for (int index = 0; index < count; index++) {
            String prefix = "memories." + index + ".";
            UUID id = parseUuid(required(properties, prefix + "id"), prefix + "id");
            if (!ids.add(id)) {
                throw formatError("duplicate memory id");
            }
            MemoryKind kind = parseEnum(MemoryKind.class, required(properties, prefix + "kind"), prefix + "kind");
            MemorySource source = parseEnum(
                    MemorySource.class, required(properties, prefix + "source"), prefix + "source");
            String text = required(properties, prefix + "text");
            Instant createdAt = parseInstant(required(properties, prefix + "createdAt"), prefix + "createdAt");
            try {
                memories.add(new MemoryEntry(id, kind, source, text, createdAt));
            } catch (IllegalArgumentException exception) {
                throw formatError("memory entry " + index + " failed validation");
            }
        }
        return new MemoryState(generation, persona, proactiveEnabled, memories, lastEvents);
    }

    private void validateKeys(Properties properties, int memoryCount) throws MemoryStoreFormatException {
        Set<String> expected = new HashSet<>(Set.of(
                "schema", "scope.world", "scope.owner", "scope.companion", "generation",
                "persona.tone", "persona.preferredAddress", "proactive.enabled", "memories.count"));
        for (ProactiveEvent event : ProactiveEvent.values()) {
            expected.add(eventKey(event));
        }
        for (int index = 0; index < memoryCount; index++) {
            String prefix = "memories." + index + ".";
            expected.add(prefix + "id");
            expected.add(prefix + "kind");
            expected.add(prefix + "source");
            expected.add(prefix + "text");
            expected.add(prefix + "createdAt");
        }
        if (!properties.stringPropertyNames().equals(expected)) {
            throw formatError("property keys do not match the schema");
        }
    }

    private void saveState(MemoryState next) throws IOException {
        Path parent = file.getParent();
        Files.createDirectories(parent);
        Properties properties = encode(next);
        Path temporary = Files.createTempFile(parent, FILE_NAME, ".tmp");
        try {
            try {
                Files.setPosixFilePermissions(temporary,
                        EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX filesystems use their platform's default private file permissions.
            }
            try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                properties.store(writer, "Minecraft Friends companion memory; schema " + SCHEMA_VERSION);
            }
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException("Filesystem does not support atomic companion memory saves: " + file, exception);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Properties encode(MemoryState value) {
        Properties properties = new Properties();
        properties.setProperty("schema", Integer.toString(SCHEMA_VERSION));
        properties.setProperty("scope.world", scope.worldId().toString());
        properties.setProperty("scope.owner", scope.ownerId().toString());
        properties.setProperty("scope.companion", scope.companionId().toString());
        properties.setProperty("generation", Long.toString(value.generation()));
        properties.setProperty("persona.tone", value.persona().tone());
        properties.setProperty("persona.preferredAddress", value.persona().preferredAddress());
        properties.setProperty("proactive.enabled", Boolean.toString(value.proactiveEnabled()));
        for (ProactiveEvent event : ProactiveEvent.values()) {
            Instant last = value.lastProactiveEvent().get(event);
            properties.setProperty(eventKey(event), last == null ? "" : last.toString());
        }
        properties.setProperty("memories.count", Integer.toString(value.memories().size()));
        for (int index = 0; index < value.memories().size(); index++) {
            MemoryEntry entry = value.memories().get(index);
            String prefix = "memories." + index + ".";
            properties.setProperty(prefix + "id", entry.id().toString());
            properties.setProperty(prefix + "kind", entry.kind().name());
            properties.setProperty(prefix + "source", entry.source().name());
            properties.setProperty(prefix + "text", entry.text());
            properties.setProperty(prefix + "createdAt", entry.createdAt().toString());
        }
        return properties;
    }

    private static String eventKey(ProactiveEvent event) {
        return "events.last." + event.name();
    }

    private String required(Properties properties, String key) throws MemoryStoreFormatException {
        String value = properties.getProperty(key);
        if (value == null) {
            throw formatError("missing required property " + key);
        }
        return value;
    }

    private MemoryStoreFormatException formatError(String reason) {
        return new MemoryStoreFormatException(file, reason);
    }

    private int parseInt(String value, String key) throws MemoryStoreFormatException {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw formatError(key + " must be an integer");
        }
    }

    private long parseLong(String value, String key) throws MemoryStoreFormatException {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw formatError(key + " must be an integer");
        }
    }

    private UUID parseUuid(String value, String key) throws MemoryStoreFormatException {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw formatError(key + " must be a UUID");
        }
    }

    private Instant parseInstant(String value, String key) throws MemoryStoreFormatException {
        try {
            return Instant.parse(value);
        } catch (DateTimeException exception) {
            throw formatError(key + " must be an ISO-8601 instant");
        }
    }

    private <E extends Enum<E>> E parseEnum(Class<E> enumType, String value, String key)
            throws MemoryStoreFormatException {
        try {
            return Enum.valueOf(enumType, value);
        } catch (IllegalArgumentException exception) {
            throw formatError(key + " has an unsupported value");
        }
    }

    private static final class StrictProperties extends Properties {
        @Override
        public synchronized Object put(Object key, Object value) {
            if (containsKey(key)) {
                throw new IllegalArgumentException("duplicate property key");
            }
            return super.put(key, value);
        }
    }

    private record MemoryState(
            long generation,
            PersonaSettings persona,
            boolean proactiveEnabled,
            List<MemoryEntry> memories,
            Map<ProactiveEvent, Instant> lastProactiveEvent) {
        private MemoryState {
            if (generation < 0) {
                throw new IllegalArgumentException("generation must not be negative");
            }
            Objects.requireNonNull(persona, "persona");
            memories = List.copyOf(memories);
            EnumMap<ProactiveEvent, Instant> events = new EnumMap<>(ProactiveEvent.class);
            events.putAll(lastProactiveEvent);
            lastProactiveEvent = Map.copyOf(events);
        }

        private static MemoryState defaults() {
            return new MemoryState(0, PersonaSettings.defaults(), true, List.of(), Map.of());
        }
    }
}
