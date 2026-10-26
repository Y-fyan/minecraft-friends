package io.github.yfyan.minecraftfriends.chat;

import io.github.yfyan.minecraftfriends.memory.CompanionMemoryStore;
import io.github.yfyan.minecraftfriends.memory.MemoryEntry;
import io.github.yfyan.minecraftfriends.memory.MemoryScope;
import io.github.yfyan.minecraftfriends.memory.PersonaSettings;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;

/** Reuses one store per scope and serializes every disk operation away from the tick thread. */
final class ChatMemoryCoordinator {
    private static final int MAX_STORES = 128;
    private static final int MAX_PENDING_PER_STORE = 8;

    private final Path persistenceRoot;
    private final ExecutorService executor;
    private final LinkedHashMap<MemoryScope, Slot> slots = new LinkedHashMap<>(16, 0.75f, true);
    private boolean closing;

    ChatMemoryCoordinator(Path persistenceRoot, ExecutorService executor) {
        this.persistenceRoot = Objects.requireNonNull(persistenceRoot, "persistenceRoot");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /**
     * Waits for a serialized memory read. Only the model worker uses this path to assemble a
     * request context; server events and commands must use {@link #submit} instead.
     */
    <T> T call(MemoryScope scope, StoreOperation<T> operation) {
        CompletableFuture<T> future = enqueue(scope, operation);
        if (future == null) {
            throw new MemoryBusyException("Memory store queue is full or all bounded slots are busy.");
        }
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new MemoryBusyException("Memory operation was interrupted.", interrupted);
        } catch (ExecutionException failure) {
            throw new MemoryOperationException(unwrap(failure.getCause()));
        }
    }

    <T> boolean submit(MemoryScope scope, StoreOperation<T> operation, BiConsumer<T, Throwable> completion) {
        Objects.requireNonNull(completion, "completion");
        CompletableFuture<T> future = enqueue(scope, operation);
        if (future == null) {
            return false;
        }
        future.whenComplete((value, failure) -> completion.accept(value, failure == null ? null : unwrap(failure)));
        return true;
    }

    boolean clearConversationContext(MemoryScope scope, BiConsumer<Boolean, Throwable> completion) {
        return submit(scope, store -> {
            Set<UUID> existing = new HashSet<>();
            for (MemoryEntry entry : store.listMemories()) {
                existing.add(entry.id());
            }
            UUID absent = new UUID(0, 0);
            while (existing.contains(absent)) {
                absent = new UUID(absent.getMostSignificantBits(), absent.getLeastSignificantBits() + 1);
            }
            // deleteMemory intentionally advances generation and clears short-term context for any ID.
            return !store.deleteMemory(absent);
        }, completion);
    }

    synchronized Summary summary(MemoryScope scope) {
        Slot slot = slots.get(scope);
        return slot == null ? Summary.notLoaded() : slot.summary;
    }

    void shutdownWhenIdle() {
        CompletableFuture<?>[] pending;
        synchronized (this) {
            if (closing) {
                return;
            }
            closing = true;
            pending = slots.values().stream().map(slot -> slot.tail).toArray(CompletableFuture[]::new);
        }
        CompletableFuture.allOf(pending).whenComplete((ignored, failure) -> executor.shutdown());
    }

    private <T> CompletableFuture<T> enqueue(MemoryScope scope, StoreOperation<T> operation) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(operation, "operation");
        synchronized (this) {
            if (closing) {
                return null;
            }
            Slot slot = slots.get(scope);
            if (slot == null) {
                if (slots.size() >= MAX_STORES) {
                    MemoryScope idle = slots.entrySet().stream()
                            .filter(entry -> entry.getValue().pending == 0)
                            .map(Map.Entry::getKey)
                            .findFirst()
                            .orElse(null);
                    if (idle == null) {
                        return null;
                    }
                    slots.remove(idle);
                }
                slot = new Slot();
                slots.put(scope, slot);
            }
            synchronized (slot) {
                if (slot.pending >= MAX_PENDING_PER_STORE) {
                    return null;
                }
                slot.pending++;
                Slot selected = slot;
                CompletableFuture<T> operationFuture = selected.tail
                        .handle((ignored, previousFailure) -> null)
                        .thenApplyAsync(ignored -> {
                            try {
                                if (selected.store == null) {
                                    selected.store = new CompanionMemoryStore(persistenceRoot, scope);
                                }
                                T value = operation.apply(selected.store);
                                RequestSnapshot snapshot = new RequestSnapshot(selected.store.beginRequest(),
                                        selected.store.isProactiveEnabled());
                                selected.summary = new Summary(
                                        true,
                                        snapshot.context().token().generation(),
                                        snapshot.context().memories().size(),
                                        snapshot.context().persona(),
                                        snapshot.proactiveEnabled());
                                return value;
                            } catch (Exception failure) {
                                throw new java.util.concurrent.CompletionException(failure);
                            }
                        }, executor);
                selected.tail = operationFuture.handle((value, failure) -> {
                    synchronized (selected) {
                        selected.pending--;
                    }
                    return null;
                });
                return operationFuture;
            }
        }
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable result = failure;
        while ((result instanceof java.util.concurrent.CompletionException
                || result instanceof ExecutionException) && result.getCause() != null) {
            result = result.getCause();
        }
        return result;
    }

    private static final class Slot {
        private CompanionMemoryStore store;
        private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
        private volatile int pending;
        private volatile Summary summary = Summary.notLoaded();
    }

    private record RequestSnapshot(io.github.yfyan.minecraftfriends.memory.RequestContext context,
                                   boolean proactiveEnabled) {
    }

    @FunctionalInterface
    interface StoreOperation<T> {
        T apply(CompanionMemoryStore store) throws Exception;
    }

    record Summary(boolean loaded, long generation, int memoryCount, PersonaSettings persona,
                   boolean proactiveEnabled) {
        static Summary notLoaded() {
            return new Summary(false, 0, 0, PersonaSettings.defaults(), true);
        }
    }

    static final class MemoryOperationException extends RuntimeException {
        MemoryOperationException(Throwable cause) {
            super(cause);
        }
    }

    static final class MemoryBusyException extends RuntimeException {
        MemoryBusyException(String message) {
            super(message);
        }

        MemoryBusyException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
