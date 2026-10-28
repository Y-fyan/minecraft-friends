package io.github.yfyan.minecraftfriends.action;

import com.google.gson.Gson;
import io.github.yfyan.minecraftfriends.capability.CapabilityCall;
import io.github.yfyan.minecraftfriends.capability.CapabilityCallParser;
import io.github.yfyan.minecraftfriends.capability.CapabilityException;
import io.github.yfyan.minecraftfriends.capability.CapabilityRegistry;
import io.github.yfyan.minecraftfriends.capability.CapabilitySpec;
import io.github.yfyan.minecraftfriends.model.ChatMessage;
import io.github.yfyan.minecraftfriends.model.ChatRole;
import io.github.yfyan.minecraftfriends.model.ModelProvider;
import io.github.yfyan.minecraftfriends.model.ModelRequest;
import io.github.yfyan.minecraftfriends.model.ModelResponse;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

/** Bounded planner. Provider invocation/parsing run off the game thread; validation/application run on main. */
public final class ActionPlanner implements AutoCloseable {
    public static final int MAX_PENDING = 16;
    public static final int MAX_INSTRUCTION_CHARS = 512;
    public static final int MAX_PROMPT_CHARS = 16_000;
    private static final int MAX_STATUS = 128;
    private final Executor main;
    private final LongSupplier clock;
    private final CapabilityRegistry registry;
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final LinkedHashMap<UUID, Status> statuses = new LinkedHashMap<>();
    private long generation;
    private boolean closed;

    public ActionPlanner(Executor main, LongSupplier clock) {
        this(main, clock, CapabilityRegistry.builtin());
    }

    public ActionPlanner(Executor main, LongSupplier clock, CapabilityRegistry registry) {
        this.main = Objects.requireNonNull(main, "main");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public synchronized Ticket request(ActionPerception snapshot, String instruction, ModelProvider provider,
                                       Executor worker, Duration timeout, int contextBudget, Handler handler) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(worker, "worker");
        Objects.requireNonNull(handler, "handler");
        if (closed || instruction == null || instruction.isBlank()
                || instruction.length() > MAX_INSTRUCTION_CHARS || timeout.isNegative() || timeout.isZero()
                || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("Planning unavailable or request outside limits.");
        }
        String system = registry.systemPrompt();
        String user = "显式任务请求：" + instruction + "\n服务端观测快照：" + new Gson().toJson(snapshot);
        if (user.length() + system.length() > Math.min(MAX_PROMPT_CHARS, contextBudget)) {
            throw new IllegalArgumentException("Planning context budget is too small.");
        }
        cancel(snapshot.ownerId());
        if (pending.size() >= MAX_PENDING) {
            throw new IllegalStateException("Planning queue is full.");
        }
        Ticket ticket = new Ticket(UUID.randomUUID(), snapshot.ownerId(), ++generation);
        Pending entry = new Pending(ticket, snapshot, clock.getAsLong() + timeout.toNanos(), handler);
        pending.put(ticket.ownerId(), entry);
        remember(ticket.ownerId(), new Status(ticket.requestId(), State.PLANNING, ""));
        ModelRequest request = new ModelRequest(ticket.requestId(), List.of(
                new ChatMessage(ChatRole.SYSTEM, system), new ChatMessage(ChatRole.USER, user)), timeout);
        try {
            worker.execute(() -> invoke(entry, provider, request));
        } catch (RuntimeException rejected) {
            dispatch(entry, null, "QUEUE_FULL");
        }
        return ticket;
    }

    private void invoke(Pending entry, ModelProvider provider, ModelRequest request) {
        if (!current(entry)) {
            return;
        }
        if (clock.getAsLong() >= entry.deadline) {
            dispatch(entry, null, "TIMED_OUT");
            return;
        }
        try {
            CompletableFuture<ModelResponse> call = provider.complete(request).toCompletableFuture();
            synchronized (this) {
                if (!current(entry)) {
                    call.cancel(true);
                    return;
                }
                entry.call = call;
            }
            call.whenComplete((response, failure) -> {
                if (!current(entry)) {
                    return;
                }
                if (failure != null) {
                    dispatch(entry, null, "PROVIDER_FAILED");
                    return;
                }
                try {
                    dispatch(entry, CapabilityCallParser.parse(response.content(), registry), "");
                } catch (CapabilityException malformed) {
                    dispatch(entry, null, malformed.code().name());
                } catch (RuntimeException malformed) {
                    dispatch(entry, null, "BAD_JSON");
                }
            });
        } catch (RuntimeException failure) {
            dispatch(entry, null, "PROVIDER_FAILED");
        }
    }

    private void dispatch(Pending entry, CapabilityCall call, String error) {
        try {
            main.execute(() -> finish(entry, call, error));
        } catch (RuntimeException stopped) {
            synchronized (this) {
                if (current(entry)) {
                    cancel(entry.ticket.ownerId());
                }
            }
        }
    }

    private void finish(Pending entry, CapabilityCall call, String error) {
        Status outcome;
        synchronized (this) {
            if (!current(entry)) {
                return;
            }
            if (clock.getAsLong() >= entry.deadline) {
                error = "TIMED_OUT";
            }
            CapabilitySpec spec = call == null ? null : registry.find(call.capability()).orElse(null);
            if (error.isEmpty() && spec == null) {
                error = "UNKNOWN_CAPABILITY";
            }
            if (error.isEmpty() && spec.requiresAreaAuthorization() && entry.snapshot.authorizedArea() == null) {
                error = "UNAUTHORIZED";
            }
            if (error.isEmpty()) {
                try {
                    if (!entry.handler.revalidate(entry.ticket, entry.snapshot, call)) {
                        error = "STALE_CONTEXT";
                    }
                } catch (RuntimeException invalid) {
                    error = "STALE_CONTEXT";
                }
            }
            pending.remove(entry.ticket.ownerId());
            if (!error.isEmpty()) {
                if (entry.call != null) {
                    entry.call.cancel(true);
                }
                outcome = new Status(entry.ticket.requestId(), error.equals("TIMED_OUT")
                        ? State.TIMED_OUT : State.FAILED, error);
            } else {
                outcome = new Status(entry.ticket.requestId(), State.DISPATCHED, "");
                // Only the capability executor can set a call's result, never the model.
                try {
                    entry.handler.apply(entry.ticket, entry.snapshot, call);
                } catch (RuntimeException refused) {
                    outcome = new Status(entry.ticket.requestId(), State.FAILED, "EXECUTION_REFUSED");
                }
            }
            remember(entry.ticket.ownerId(), outcome);
        }
        entry.handler.onOutcome(outcome);
    }

    /** Called by the server tick; at most MAX_PENDING deadlines are examined. */
    public void expire() {
        List<Pending> expired;
        synchronized (this) {
            long now = clock.getAsLong();
            expired = pending.values().stream().filter(entry -> now >= entry.deadline).toList();
        }
        for (Pending entry : expired) {
            dispatch(entry, null, "TIMED_OUT");
        }
    }

    public synchronized void cancel(UUID ownerId) {
        Pending entry = pending.remove(ownerId);
        if (entry != null) {
            remember(ownerId, new Status(entry.ticket.requestId(), State.CANCELLED, "CANCELLED"));
            if (entry.call != null) {
                entry.call.cancel(true);
            }
        }
    }

    public synchronized void cancelAll() {
        for (UUID ownerId : new ArrayList<>(pending.keySet())) {
            cancel(ownerId);
        }
    }

    public synchronized Status status(UUID ownerId) {
        return statuses.getOrDefault(ownerId, new Status(new UUID(0L, 0L), State.IDLE, ""));
    }

    private synchronized boolean current(Pending entry) {
        return !closed && pending.get(entry.ticket.ownerId()) == entry;
    }

    private void remember(UUID ownerId, Status status) {
        statuses.remove(ownerId);
        statuses.put(ownerId, status);
        while (statuses.size() > MAX_STATUS) {
            statuses.remove(statuses.keySet().iterator().next());
        }
    }

    @Override
    public synchronized void close() {
        cancelAll();
        closed = true;
    }

    public enum State { IDLE, PLANNING, DISPATCHED, FAILED, CANCELLED, TIMED_OUT }
    public record Ticket(UUID requestId, UUID ownerId, long generation) { }
    public record Status(UUID requestId, State state, String reason) { }
    public interface Handler {
        boolean revalidate(Ticket ticket, ActionPerception snapshot, CapabilityCall call);
        void apply(Ticket ticket, ActionPerception snapshot, CapabilityCall call);
        void onOutcome(Status status);
    }

    private static final class Pending {
        private final Ticket ticket;
        private final ActionPerception snapshot;
        private final long deadline;
        private final Handler handler;
        private CompletableFuture<ModelResponse> call;

        private Pending(Ticket ticket, ActionPerception snapshot, long deadline, Handler handler) {
            this.ticket = ticket;
            this.snapshot = snapshot;
            this.deadline = deadline;
            this.handler = handler;
        }
    }
}
