package io.github.yfyan.minecraftfriends.action;

import io.github.yfyan.minecraftfriends.model.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class ActionPlannerTest {
    private final Queue<Runnable> workers = new ArrayDeque<>();
    private final Queue<Runnable> main = new ArrayDeque<>();
    private final AtomicLong now = new AtomicLong();
    private final ActionPlanner planner = new ActionPlanner(main::add, now::get);
    private final List<ActionPlan> applied = new ArrayList<>();
    private final List<ModelRequest> requests = new ArrayList<>();
    private boolean valid = true;
    private final ActionPerception snapshot = snapshot(UUID.randomUUID(), null);
    private final ActionPlanner.Handler handler = new ActionPlanner.Handler() {
        public boolean revalidate(ActionPlanner.Ticket ticket, ActionPerception seen, ActionPlan plan) { return valid; }
        public void apply(ActionPlanner.Ticket ticket, ActionPerception seen, ActionPlan plan) { applied.add(plan); }
        public void onOutcome(ActionPlanner.Status status) { }
    };

    @Test void invocationAndApplicationUseSeparateExecutorsAndNeverClaimSuccess() {
        var response = new CompletableFuture<ModelResponse>();
        request(snapshot, provider(response));
        assertTrue(requests.isEmpty());
        workers.remove().run();
        assertEquals(2, requests.getFirst().messages().size());
        assertTrue(requests.getFirst().messages().get(1).content().contains(snapshot.ownerId().toString()));
        response.complete(new ModelResponse("{\"action\":\"follow\"}"));
        assertTrue(applied.isEmpty());
        drainMain();
        assertEquals(List.of(new ActionPlan(ActionType.FOLLOW, 0)), applied);
        assertEquals(ActionPlanner.State.DISPATCHED, status().state());
    }

    @Test void cancellationRejectsProviderThatIgnoresCancellation() {
        var response = nonCancellable();
        request(snapshot, provider(response)); workers.remove().run();
        planner.cancel(snapshot.ownerId());
        response.complete(new ModelResponse("{\"action\":\"follow\"}")); drainMain();
        assertTrue(applied.isEmpty()); assertEquals(ActionPlanner.State.CANCELLED, status().state());
    }

    @Test void stopAfterResponseButBeforeMainDispatchWins() {
        var response = new CompletableFuture<ModelResponse>();
        request(snapshot, provider(response)); workers.remove().run();
        response.complete(new ModelResponse("{\"action\":\"follow\"}"));
        planner.cancel(snapshot.ownerId()); drainMain();
        assertTrue(applied.isEmpty());
    }

    @Test void generationPreventsOldResultFromReplacingNewRequest() {
        var old = nonCancellable(); var current = nonCancellable();
        var first = request(snapshot, provider(old)); workers.remove().run();
        var second = request(snapshot, provider(current)); workers.remove().run();
        assertTrue(second.generation() > first.generation());
        old.complete(new ModelResponse("{\"action\":\"follow\"}"));
        current.complete(new ModelResponse("{\"action\":\"wait\"}")); drainMain();
        assertEquals(List.of(new ActionPlan(ActionType.WAIT, 0)), applied);
        assertEquals(second.requestId(), status().requestId());
    }

    @Test void malformedResponsesAndForgedSuccessNeverDispatch() {
        for (String json : List.of("[]", "null", "{\"action\":\"wait\",\"success\":true}",
                "{\"action\":\"collect_oak_logs\",\"count\":1.5}",
                "{\"action\":\"collect_oak_logs\",\"count\":2147483648}", "执行成功")) {
            request(snapshot, provider(CompletableFuture.completedFuture(new ModelResponse(json))));
            workers.remove().run(); drainMain();
            assertEquals("BAD_JSON", status().reason());
        }
        assertTrue(applied.isEmpty());
    }

    @Test void actualOfflineMockFailsSafelyRatherThanPretendingToPlan() {
        request(snapshot, new MockModelProvider()); workers.remove().run(); drainMain();
        assertEquals("BAD_JSON", status().reason()); assertTrue(applied.isEmpty());
    }

    @Test void collectionRequiresExplicitAreaAndMainThreadRevalidation() {
        var response = CompletableFuture.completedFuture(new ModelResponse("{\"action\":\"collect_oak_logs\",\"count\":8}"));
        request(snapshot, provider(response)); workers.remove().run(); drainMain();
        assertEquals("UNAUTHORIZED", status().reason()); assertTrue(applied.isEmpty());
        var authorized = snapshot(snapshot.ownerId(), new BlockArea(0,0,0,1,1,1));
        request(authorized, provider(response)); workers.remove().run(); valid = false; drainMain();
        assertEquals("STALE_CONTEXT", status().reason()); assertTrue(applied.isEmpty());
        valid = true;
        request(authorized, provider(response)); workers.remove().run(); drainMain();
        assertEquals(List.of(new ActionPlan(ActionType.COLLECT_OAK_LOGS,8)), applied);
    }

    @Test void timeoutAndCloseInvalidateLateResponses() {
        var response = nonCancellable();
        request(snapshot, provider(response)); workers.remove().run();
        now.set(Duration.ofSeconds(31).toNanos()); planner.expire(); drainMain();
        assertEquals(ActionPlanner.State.TIMED_OUT, status().state());
        response.complete(new ModelResponse("{\"action\":\"follow\"}")); drainMain();
        assertTrue(applied.isEmpty());
        request(snapshot, provider(nonCancellable())); planner.close(); workers.remove().run();
        assertEquals(ActionPlanner.State.CANCELLED, status().state());
        assertThrows(IllegalArgumentException.class, () -> request(snapshot, new MockModelProvider()));
    }

    @Test void boundsAndRejectedWorkerFailWithoutInvokingProvider() {
        for (int i = 0; i < ActionPlanner.MAX_PENDING; i++) request(snapshot(UUID.randomUUID(), null), new MockModelProvider());
        assertThrows(IllegalStateException.class, () -> request(snapshot, new MockModelProvider()));
        planner.cancelAll();
        assertThrows(IllegalArgumentException.class, () -> planner.request(snapshot, "x".repeat(513),
                new MockModelProvider(), workers::add, Duration.ofSeconds(30), 16000, handler));
        assertThrows(IllegalArgumentException.class, () -> planner.request(snapshot, "跟随", new MockModelProvider(),
                workers::add, Duration.ofSeconds(30), 1, handler));
        planner.request(snapshot, "跟随", new MockModelProvider(), command -> { throw new RejectedExecutionException(); },
                Duration.ofSeconds(30), 16000, handler);
        drainMain(); assertEquals("QUEUE_FULL", status().reason()); assertTrue(requests.isEmpty());
    }

    @Test void cancellationBeforeWorkerStartsDoesNotInvokeProvider() {
        request(snapshot, provider(nonCancellable()));
        planner.cancel(snapshot.ownerId()); workers.remove().run(); drainMain();
        assertTrue(requests.isEmpty()); assertTrue(applied.isEmpty());
    }

    @Test void providerFailureIsReportedOnMainWithoutApplyingActions() {
        request(snapshot, provider(CompletableFuture.failedFuture(new IllegalStateException("test failure"))));
        workers.remove().run();
        assertEquals(ActionPlanner.State.PLANNING, status().state());
        drainMain(); assertEquals("PROVIDER_FAILED", status().reason()); assertTrue(applied.isEmpty());
    }

    @Test void queuedRequestAtDeadlineNeverInvokesProviderEvenBeforeExpiryTick() {
        request(snapshot, provider(nonCancellable()));
        now.set(Duration.ofSeconds(30).toNanos());
        // No expire() call: the worker must enforce its own admission deadline.
        workers.remove().run();
        assertTrue(requests.isEmpty());
        assertEquals(ActionPlanner.State.PLANNING, status().state());
        drainMain();
        assertEquals(ActionPlanner.State.TIMED_OUT, status().state());
        assertTrue(applied.isEmpty());
    }

    @Test void deadlineIsRecheckedEvenWhenAResponseIsAlreadyQueued() {
        request(snapshot, provider(CompletableFuture.completedFuture(new ModelResponse("{\"action\":\"follow\"}"))));
        workers.remove().run(); now.set(Duration.ofSeconds(30).toNanos()); drainMain();
        assertEquals(ActionPlanner.State.TIMED_OUT, status().state()); assertTrue(applied.isEmpty());
    }

    @Test void perceptionIsImmutableAndBounded() {
        var blocks = new ArrayList<ActionPerception.Block>();
        var copied = new ActionPerception(snapshot.worldSession(), snapshot.ownerId(), snapshot.companionId(),
                snapshot.dimension(), 0, snapshot.ownerPosition(), snapshot.companionPosition(), 20,20,
                blocks,List.of(),null);
        blocks.add(new ActionPerception.Block(0,0,0,"minecraft:stone"));
        assertTrue(copied.blocks().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> copied.blocks().add(blocks.getFirst()));
        assertThrows(IllegalArgumentException.class, () -> new ActionPerception(snapshot.worldSession(), snapshot.ownerId(),
                snapshot.companionId(), snapshot.dimension(),0,snapshot.ownerPosition(),snapshot.companionPosition(),20,20,
                Collections.nCopies(246, blocks.getFirst()),List.of(),null));
        assertThrows(IllegalArgumentException.class, () -> new ActionPerception.Position(Double.NaN,0,0));
    }

    private ActionPlanner.Ticket request(ActionPerception seen, ModelProvider provider) {
        return planner.request(seen, "跟着我", provider, workers::add, Duration.ofSeconds(30),16000,handler);
    }
    private ActionPlanner.Status status() { return planner.status(snapshot.ownerId()); }
    private void drainMain() { while (!main.isEmpty()) main.remove().run(); }
    private ModelProvider provider(CompletableFuture<ModelResponse> response) {
        return new ModelProvider() {
            public String id() { return "test"; }
            public CompletionStage<ModelResponse> complete(ModelRequest request) { requests.add(request); return response; }
        };
    }
    private static CompletableFuture<ModelResponse> nonCancellable() {
        return new CompletableFuture<>() { @Override public boolean cancel(boolean interrupt) { return false; } };
    }
    private static ActionPerception snapshot(UUID owner, BlockArea area) {
        return new ActionPerception(UUID.randomUUID(),owner,UUID.randomUUID(),"minecraft:overworld",0,
                new ActionPerception.Position(0,64,0), new ActionPerception.Position(1,64,1),20,20,List.of(),List.of(),area);
    }
}
