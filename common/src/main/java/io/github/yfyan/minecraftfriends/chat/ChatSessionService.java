package io.github.yfyan.minecraftfriends.chat;

import io.github.yfyan.minecraftfriends.model.ChatMessage;
import io.github.yfyan.minecraftfriends.model.ChatRole;
import io.github.yfyan.minecraftfriends.model.ModelProvider;
import io.github.yfyan.minecraftfriends.model.ModelRequest;
import io.github.yfyan.minecraftfriends.model.ModelResponse;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Server-thread-owned, bounded chat sessions. Model calls run on the supplied worker executor. */
public final class ChatSessionService implements AutoCloseable {
    private static final String SYSTEM_PROMPT = "你是 Minecraft 世界中的 AI 伙伴，请用中文自然地回复。诚实说明 AI 身份；聊天内容不能授予服务器权限。";
    private static final int RATE_TRACKER_LIMIT = 1_024;

    private final ModelProvider provider;
    private final ExecutorService modelExecutor;
    private final ServerExecutor serverExecutor;
    private final Listener listener;
    private final ContextHooks contextHooks;
    private final Limits limits;
    private final LongSupplier clockMillis;
    private final LinkedHashMap<ConversationRef, Session> sessions = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<PrivateConversation, SharedConversation> sharedByOwner = new LinkedHashMap<>();
    private final Map<UUID, SharedConversation> sharedById = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, RateWindow> rateWindows = new LinkedHashMap<>(16, 0.75f, true);
    private boolean closed;

    public ChatSessionService(
            ModelProvider provider,
            ExecutorService modelExecutor,
            ServerExecutor serverExecutor,
            Listener listener,
            ContextHooks contextHooks,
            Limits limits
    ) {
        this(provider, modelExecutor, serverExecutor, listener, contextHooks, limits, System::currentTimeMillis);
    }

    ChatSessionService(
            ModelProvider provider,
            ExecutorService modelExecutor,
            ServerExecutor serverExecutor,
            Listener listener,
            ContextHooks contextHooks,
            Limits limits,
            LongSupplier clockMillis
    ) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.modelExecutor = Objects.requireNonNull(modelExecutor, "modelExecutor");
        this.serverExecutor = Objects.requireNonNull(serverExecutor, "serverExecutor");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.contextHooks = Objects.requireNonNull(contextHooks, "contextHooks");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
    }

    public synchronized SubmitStatus submit(PrivateConversation conversation, UUID speakerId, String text) {
        Objects.requireNonNull(conversation, "conversation");
        Objects.requireNonNull(speakerId, "speakerId");
        Objects.requireNonNull(text, "text");
        if (closed) {
            return SubmitStatus.UNAVAILABLE;
        }
        if (!conversation.ownerId().equals(speakerId)) {
            return SubmitStatus.NOT_MEMBER;
        }
        SubmitStatus validation = validateTextAndRate(speakerId, text);
        if (validation != SubmitStatus.ACCEPTED) {
            return validation;
        }
        Session session = getOrCreate(conversation);
        if (session == null) {
            return SubmitStatus.SESSION_LIMIT;
        }
        return enqueue(session, speakerId, text);
    }

    /** Schedules a verified game event as a private turn without charging the player's rate limit. */
    public synchronized SubmitStatus submitProactive(
            PrivateConversation conversation,
            UUID ownerId,
            String verifiedEventPrompt
    ) {
        Objects.requireNonNull(conversation, "conversation");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(verifiedEventPrompt, "verifiedEventPrompt");
        if (closed) {
            return SubmitStatus.UNAVAILABLE;
        }
        if (!conversation.ownerId().equals(ownerId)) {
            return SubmitStatus.NOT_MEMBER;
        }
        if (verifiedEventPrompt.isBlank() || verifiedEventPrompt.length() > limits.maxMessageChars()) {
            return SubmitStatus.MESSAGE_TOO_LONG;
        }
        Session session = getOrCreate(conversation);
        if (session == null) {
            return SubmitStatus.SESSION_LIMIT;
        }
        return enqueue(session, ownerId, verifiedEventPrompt, true);
    }

    public synchronized SubmitStatus submit(SharedConversation conversation, UUID speakerId, String text) {
        Objects.requireNonNull(conversation, "conversation");
        Objects.requireNonNull(speakerId, "speakerId");
        Objects.requireNonNull(text, "text");
        if (closed) {
            return SubmitStatus.UNAVAILABLE;
        }
        Session session = sessions.get(conversation);
        if (session == null || !session.members.contains(speakerId)) {
            return SubmitStatus.NOT_MEMBER;
        }
        SubmitStatus validation = validateTextAndRate(speakerId, text);
        if (validation != SubmitStatus.ACCEPTED) {
            return validation;
        }
        return enqueue(session, speakerId, text);
    }

    private SubmitStatus validateTextAndRate(UUID speakerId, String text) {
        if (text.isBlank()) {
            return SubmitStatus.EMPTY_MESSAGE;
        }
        if (text.length() > limits.maxMessageChars()) {
            return SubmitStatus.MESSAGE_TOO_LONG;
        }
        if (!allowRequest(speakerId)) {
            return SubmitStatus.RATE_LIMITED;
        }
        return SubmitStatus.ACCEPTED;
    }

    private SubmitStatus enqueue(Session session, UUID speakerId, String text) {
        return enqueue(session, speakerId, text, false);
    }

    private SubmitStatus enqueue(Session session, UUID speakerId, String text, boolean proactive) {
        boolean alreadyBusy = session.active != null || !session.queue.isEmpty();
        if (session.active != null && session.queue.size() >= limits.maxQueuePerSession()) {
            return SubmitStatus.QUEUE_FULL;
        }
        session.queue.addLast(new Turn(UUID.randomUUID(), speakerId, text, proactive));
        session.lastTouchedMillis = clockMillis.getAsLong();
        pump(session);
        return alreadyBusy ? SubmitStatus.QUEUED : SubmitStatus.ACCEPTED;
    }

    public synchronized ShareResult createShared(PrivateConversation conversation, UUID actorId) {
        Objects.requireNonNull(conversation, "conversation");
        Objects.requireNonNull(actorId, "actorId");
        if (closed) {
            return new ShareResult(ShareStatus.UNAVAILABLE, null);
        }
        if (!conversation.ownerId().equals(actorId)) {
            return new ShareResult(ShareStatus.NOT_OWNER, null);
        }
        SharedConversation existing = sharedByOwner.get(conversation);
        if (existing != null && sessions.containsKey(existing)) {
            return new ShareResult(ShareStatus.EXISTING, existing);
        }
        if (!makeRoom()) {
            return new ShareResult(ShareStatus.SESSION_LIMIT, null);
        }
        SharedConversation created;
        do {
            created = new SharedConversation(
                    conversation.worldId(), UUID.randomUUID(), conversation.ownerId(), conversation.companionId());
        } while (sharedById.containsKey(created.sessionId()));
        Session session = new Session(created);
        session.members.add(actorId);
        session.lastTouchedMillis = clockMillis.getAsLong();
        sessions.put(created, session);
        sharedByOwner.put(conversation, created);
        sharedById.put(created.sessionId(), created);
        return new ShareResult(ShareStatus.CREATED, created);
    }

    public synchronized ShareStatus invite(SharedConversation conversation, UUID actorId, UUID targetId) {
        Objects.requireNonNull(conversation, "conversation");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(targetId, "targetId");
        Session session = sessions.get(conversation);
        if (closed) {
            return ShareStatus.UNAVAILABLE;
        }
        if (session == null) {
            return ShareStatus.NOT_FOUND;
        }
        if (!conversation.ownerId().equals(actorId)) {
            return ShareStatus.NOT_OWNER;
        }
        if (targetId.equals(actorId)) {
            return ShareStatus.ALREADY_MEMBER;
        }
        if (session.members.contains(targetId)) {
            return ShareStatus.ALREADY_MEMBER;
        }
        if (session.invitations.contains(targetId)) {
            return ShareStatus.EXISTING_INVITATION;
        }
        if (session.invitations.size() >= limits.maxInvitationsPerSharedSession()) {
            return ShareStatus.INVITATION_LIMIT;
        }
        session.invitations.add(targetId);
        session.lastTouchedMillis = clockMillis.getAsLong();
        return ShareStatus.INVITED;
    }

    public synchronized ShareResult joinShared(UUID worldId, UUID sessionId, UUID memberId) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(memberId, "memberId");
        if (closed) {
            return new ShareResult(ShareStatus.UNAVAILABLE, null);
        }
        SharedConversation conversation = sharedById.get(sessionId);
        if (conversation == null || !conversation.worldId().equals(worldId)) {
            return new ShareResult(ShareStatus.NOT_FOUND, null);
        }
        Session session = sessions.get(conversation);
        if (session == null) {
            return new ShareResult(ShareStatus.NOT_FOUND, null);
        }
        if (session.members.contains(memberId)) {
            return new ShareResult(ShareStatus.ALREADY_MEMBER, conversation);
        }
        if (!session.invitations.contains(memberId)) {
            return new ShareResult(ShareStatus.NOT_INVITED, conversation);
        }
        if (session.members.size() >= limits.maxSharedMembers()) {
            return new ShareResult(ShareStatus.MEMBER_LIMIT, conversation);
        }
        session.invitations.remove(memberId);
        session.members.add(memberId);
        session.lastTouchedMillis = clockMillis.getAsLong();
        return new ShareResult(ShareStatus.JOINED, conversation);
    }

    public synchronized ShareStatus leaveShared(UUID worldId, UUID sessionId, UUID memberId) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(memberId, "memberId");
        SharedConversation conversation = sharedById.get(sessionId);
        if (conversation == null || !conversation.worldId().equals(worldId)) {
            return ShareStatus.NOT_FOUND;
        }
        Session session = sessions.get(conversation);
        if (session == null) {
            return ShareStatus.NOT_FOUND;
        }
        if (conversation.ownerId().equals(memberId)) {
            return ShareStatus.OWNER_CANNOT_LEAVE;
        }
        if (!session.members.contains(memberId)) {
            return ShareStatus.NOT_MEMBER;
        }
        removeMember(session, memberId);
        session.lastTouchedMillis = clockMillis.getAsLong();
        return ShareStatus.LEFT;
    }

    public synchronized OperationStatus clear(ConversationRef conversation, UUID actorId) {
        Objects.requireNonNull(conversation, "conversation");
        Objects.requireNonNull(actorId, "actorId");
        if (closed) {
            return OperationStatus.UNAVAILABLE;
        }
        if (!conversation.ownerId().equals(actorId)) {
            return OperationStatus.NOT_OWNER;
        }
        Session session = sessions.get(conversation);
        if (session == null && conversation instanceof PrivateConversation privateConversation) {
            session = getOrCreate(privateConversation);
        }
        if (session == null) {
            return OperationStatus.NOT_FOUND;
        }
        invalidateActive(session);
        session.queue.clear();
        session.history.clear();
        session.lastTouchedMillis = clockMillis.getAsLong();
        return OperationStatus.CLEARED;
    }

    public synchronized CancelResult cancel(UUID actorId, ConversationRef conversation) {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(conversation, "conversation");
        Session session = sessions.get(conversation);
        if (session == null || !isMember(session, actorId)) {
            return new CancelResult(OperationStatus.NOT_MEMBER, 0);
        }
        int cancelled = 0;
        for (Iterator<Turn> iterator = session.queue.iterator(); iterator.hasNext();) {
            if (iterator.next().speakerId.equals(actorId)) {
                iterator.remove();
                cancelled++;
            }
        }
        if (session.active != null && session.active.turn.speakerId.equals(actorId)) {
            invalidateActive(session);
            cancelled++;
            pump(session);
        }
        session.lastTouchedMillis = clockMillis.getAsLong();
        return new CancelResult(OperationStatus.CANCELLED, cancelled);
    }

    public synchronized int cancelPlayerWork(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        int cancelled = 0;
        for (Session session : sessions.values()) {
            if (!isMember(session, playerId)) {
                continue;
            }
            int before = session.queue.size();
            session.queue.removeIf(turn -> turn.speakerId.equals(playerId));
            cancelled += before - session.queue.size();
            if (session.active != null && session.active.turn.speakerId.equals(playerId)) {
                invalidateActive(session);
                cancelled++;
                pump(session);
            }
        }
        return cancelled;
    }

    public synchronized Optional<SharedConversation> ownedShared(PrivateConversation conversation) {
        Objects.requireNonNull(conversation, "conversation");
        SharedConversation shared = sharedByOwner.get(conversation);
        return shared != null && sessions.containsKey(shared) ? Optional.of(shared) : Optional.empty();
    }

    /** Resolves a world-scoped session ID; send still rechecks the actor UUID against current membership. */
    public synchronized Optional<SharedConversation> findShared(UUID worldId, UUID sessionId) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(sessionId, "sessionId");
        SharedConversation conversation = sharedById.get(sessionId);
        return conversation != null && conversation.worldId().equals(worldId) && sessions.containsKey(conversation)
                ? Optional.of(conversation)
                : Optional.empty();
    }

    /** Cancels all work for one private owner/companion while retaining ordinary chat history. */
    public synchronized int cancelOwnerWork(UUID ownerId, UUID companionId) {
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(companionId, "companionId");
        int cancelled = 0;
        for (Session session : sessions.values()) {
            if (!session.conversation.ownerId().equals(ownerId)
                    || !session.conversation.companionId().equals(companionId)) {
                continue;
            }
            if (session.conversation instanceof PrivateConversation) {
                cancelled += session.queue.size();
                session.queue.clear();
            } else {
                int before = session.queue.size();
                session.queue.removeIf(turn -> turn.speakerId.equals(ownerId));
                cancelled += before - session.queue.size();
            }
            if (session.active != null && (session.conversation instanceof PrivateConversation
                    || session.active.turn.speakerId.equals(ownerId))) {
                invalidateActive(session);
                cancelled++;
                if (session.conversation instanceof SharedConversation) {
                    pump(session);
                }
            }
        }
        return cancelled;
    }

    public synchronized OwnerMetrics metricsForOwner(UUID ownerId) {
        Objects.requireNonNull(ownerId, "ownerId");
        int count = 0;
        int inFlight = 0;
        int queued = 0;
        int shared = 0;
        int members = 0;
        for (Session session : sessions.values()) {
            if (!session.conversation.ownerId().equals(ownerId)) {
                continue;
            }
            count++;
            if (session.active != null) {
                inFlight++;
            }
            queued += session.queue.size();
            if (session.conversation instanceof SharedConversation) {
                shared++;
                members += session.members.size();
            }
        }
        return new OwnerMetrics(count, inFlight, queued, shared, members);
    }

    /** Cancels this player's work and removes shared membership when they disconnect. */
    public synchronized void onPlayerLogout(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        for (Session session : new ArrayList<>(sessions.values())) {
            if (session.conversation.ownerId().equals(playerId)) {
                removeSession(session.conversation);
            } else if (session.conversation instanceof SharedConversation && session.members.contains(playerId)) {
                removeMember(session, playerId);
            } else {
                session.invitations.remove(playerId);
                cancelTurnsFrom(session, playerId);
            }
        }
        rateWindows.remove(playerId);
    }

    /** Invalidates every session tied to a dismissed or removed companion. */
    public synchronized void onCompanionRemoved(UUID ownerId) {
        Objects.requireNonNull(ownerId, "ownerId");
        List<ConversationRef> remove = sessions.keySet().stream()
                .filter(conversation -> conversation.ownerId().equals(ownerId))
                .toList();
        remove.forEach(this::removeSession);
    }

    /** Leaves shared sessions, invalidates owned sessions, and drops pending invites for this UUID. */
    private void cancelTurnsFrom(Session session, UUID playerId) {
        session.queue.removeIf(turn -> turn.speakerId.equals(playerId));
        if (session.active != null && session.active.turn.speakerId.equals(playerId)) {
            invalidateActive(session);
            pump(session);
        }
    }

    private void removeMember(Session session, UUID memberId) {
        session.members.remove(memberId);
        session.invitations.remove(memberId);
        cancelTurnsFrom(session, memberId);
    }

    public synchronized List<SharedConversation> joinedShared(UUID worldId, UUID memberId) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(memberId, "memberId");
        return sessions.values().stream()
                .filter(session -> session.conversation instanceof SharedConversation)
                .filter(session -> session.conversation.worldId().equals(worldId))
                .filter(session -> session.members.contains(memberId))
                .map(session -> (SharedConversation) session.conversation)
                .toList();
    }

    public synchronized List<UUID> members(ConversationRef conversation) {
        Objects.requireNonNull(conversation, "conversation");
        if (conversation instanceof PrivateConversation) {
            return List.of(conversation.ownerId());
        }
        Session session = sessions.get(conversation);
        return session == null ? List.of() : List.copyOf(session.members);
    }

    public synchronized boolean isMember(ConversationRef conversation, UUID playerId) {
        Objects.requireNonNull(conversation, "conversation");
        Objects.requireNonNull(playerId, "playerId");
        if (conversation instanceof PrivateConversation) {
            return conversation.ownerId().equals(playerId);
        }
        Session session = sessions.get(conversation);
        return session != null && session.members.contains(playerId);
    }

    public synchronized int sessionCount() {
        return sessions.size();
    }

    public synchronized void prune() {
        if (closed) {
            return;
        }
        long now = clockMillis.getAsLong();
        rateWindows.entrySet().removeIf(entry -> now - entry.getValue().startedAtMillis >= limits.rateWindow().toMillis());
        while (rateWindows.size() > RATE_TRACKER_LIMIT) {
            rateWindows.remove(rateWindows.keySet().iterator().next());
        }
        List<ConversationRef> expired = sessions.values().stream()
                .filter(session -> session.active == null && session.queue.isEmpty())
                .filter(session -> now - session.lastTouchedMillis >= limits.idleSessionTtl().toMillis())
                .map(session -> session.conversation)
                .toList();
        expired.forEach(this::removeSession);
    }

    private Session getOrCreate(PrivateConversation conversation) {
        Session existing = sessions.get(conversation);
        if (existing != null) {
            return existing;
        }
        if (!makeRoom()) {
            return null;
        }
        Session created = new Session(conversation);
        created.lastTouchedMillis = clockMillis.getAsLong();
        sessions.put(conversation, created);
        return created;
    }

    private boolean makeRoom() {
        if (sessions.size() < limits.maxSessions()) {
            return true;
        }
        Session candidate = sessions.values().stream()
                .filter(session -> session.active == null && session.queue.isEmpty())
                .findFirst()
                .orElse(null);
        if (candidate == null) {
            return false;
        }
        removeSession(candidate.conversation);
        return true;
    }

    private void removeSession(ConversationRef conversation) {
        Session removed = sessions.remove(conversation);
        if (removed == null) {
            return;
        }
        invalidateActive(removed);
        removed.queue.clear();
        if (conversation instanceof SharedConversation sharedConversation) {
            sharedById.remove(sharedConversation.sessionId());
            sharedByOwner.remove(new PrivateConversation(
                    sharedConversation.worldId(), sharedConversation.ownerId(), sharedConversation.companionId()));
        }
    }

    private boolean allowRequest(UUID playerId) {
        long now = clockMillis.getAsLong();
        RateWindow window = rateWindows.get(playerId);
        if (window == null || now - window.startedAtMillis >= limits.rateWindow().toMillis()) {
            rateWindows.put(playerId, new RateWindow(now, 1));
            trimRateWindows();
            return true;
        }
        if (window.count >= limits.requestsPerWindow()) {
            return false;
        }
        window.count++;
        return true;
    }

    private void trimRateWindows() {
        while (rateWindows.size() > RATE_TRACKER_LIMIT) {
            rateWindows.remove(rateWindows.keySet().iterator().next());
        }
    }

    private void pump(Session session) {
        if (closed || session.active != null) {
            return;
        }
        Turn turn;
        do {
            turn = session.queue.pollFirst();
        } while (turn != null && !isMember(session, turn.speakerId));
        if (turn == null) {
            return;
        }
        Turn selected = turn;
        ActiveTurn active = new ActiveTurn(selected);
        session.active = active;
        session.history.add(new HistoryMessage(selected.requestId,
                new ChatMessage(selected.proactive ? ChatRole.SYSTEM : ChatRole.USER, selected.text)));
        trimHistory(session);
        session.lastTouchedMillis = clockMillis.getAsLong();
        List<ChatMessage> history = session.history.stream().map(HistoryMessage::message).toList();
        ConversationRef conversation = session.conversation;
        try {
            active.workerTask = modelExecutor.submit(() -> invokeProvider(conversation, active, history));
        } catch (RejectedExecutionException rejected) {
            serverExecutor.execute(() -> finishFailure(conversation, selected.requestId, FailureReason.BUSY));
        }
    }

    private void invokeProvider(ConversationRef conversation, ActiveTurn active, List<ChatMessage> history) {
        if (!isCurrent(conversation, active.turn.requestId)) {
            return;
        }
        HookContext hookContext;
        try {
            hookContext = collectContext(conversation, active.turn, history);
        } catch (RuntimeException contextFailure) {
            serverExecutor.execute(() -> finishFailure(
                    conversation, active.turn.requestId, FailureReason.CONTEXT_HOOK));
            return;
        }
        synchronized (this) {
            if (!isCurrentLocked(conversation, active.turn.requestId)) {
                return;
            }
            active.hookContext = hookContext;
        }
        try {
            List<ChatMessage> context = hookContext.messages();
            List<ChatMessage> messages = new ArrayList<>(1 + context.size() + history.size());
            messages.add(new ChatMessage(ChatRole.SYSTEM, SYSTEM_PROMPT));
            messages.addAll(context);
            messages.addAll(history);
            ModelRequest request = new ModelRequest(active.turn.requestId, messages, limits.requestTimeout());
            CompletableFuture<ModelResponse> providerFuture = provider.complete(request).toCompletableFuture();
            synchronized (this) {
                if (!isCurrentLocked(conversation, active.turn.requestId)) {
                    providerFuture.cancel(true);
                    return;
                }
                active.providerFuture = providerFuture;
            }
            providerFuture.orTimeout(limits.requestTimeout().toMillis(), TimeUnit.MILLISECONDS)
                    .whenComplete((response, error) -> serverExecutor.execute(
                            () -> finish(conversation, active.turn.requestId, response, error)));
        } catch (RuntimeException exception) {
            serverExecutor.execute(() -> finishFailure(conversation, active.turn.requestId, FailureReason.PROVIDER));
        }
    }

    private HookContext collectContext(ConversationRef conversation, Turn turn, List<ChatMessage> history) {
        HookContext supplied;
        if (conversation instanceof PrivateConversation privateConversation) {
            supplied = contextHooks.privateContext(privateConversation, turn.speakerId, turn.text, history);
        } else {
            supplied = contextHooks.sharedContext((SharedConversation) conversation, turn.speakerId, turn.text, history);
        }
        if (supplied == null || supplied.messages().isEmpty()) {
            return supplied == null ? HookContext.empty() : supplied;
        }
        List<ChatMessage> bounded = new ArrayList<>();
        int chars = 0;
        for (ChatMessage message : supplied.messages()) {
            if (message == null || bounded.size() >= limits.maxContextMessages()) {
                break;
            }
            if (message.content().length() > limits.maxMessageChars()) {
                continue;
            }
            if (chars + message.content().length() > limits.maxContextChars()) {
                break;
            }
            bounded.add(message);
            chars += message.content().length();
        }
        return new HookContext(bounded, supplied.state());
    }

    private synchronized boolean isCurrent(ConversationRef conversation, UUID requestId) {
        return isCurrentLocked(conversation, requestId);
    }

    private boolean isCurrentLocked(ConversationRef conversation, UUID requestId) {
        Session session = sessions.get(conversation);
        return !closed && session != null && session.active != null
                && session.active.turn.requestId.equals(requestId)
                && isMember(session, session.active.turn.speakerId);
    }

    private synchronized void finish(
            ConversationRef conversation,
            UUID requestId,
            ModelResponse response,
            Throwable error
    ) {
        Session session = sessions.get(conversation);
        if (closed || session == null || session.active == null
                || !session.active.turn.requestId.equals(requestId)
                || !isMember(session, session.active.turn.speakerId)) {
            return;
        }
        ActiveTurn completed = session.active;
        Turn turn = completed.turn;
        session.active = null;
        if (error != null) {
            removeHistoryMessage(session, requestId);
            listener.onFailure(new Failure(conversation, turn.speakerId, requestId, classify(error)));
        } else if (response == null || response.content().isBlank()) {
            removeHistoryMessage(session, requestId);
            listener.onFailure(new Failure(conversation, turn.speakerId, requestId, FailureReason.EMPTY_RESPONSE));
        } else {
            String replyText = truncate(response.content().strip(), limits.maxResponseChars());
            session.history.add(new HistoryMessage(requestId, new ChatMessage(ChatRole.ASSISTANT, replyText)));
            trimHistory(session);
            try {
                if (conversation instanceof PrivateConversation privateConversation) {
                    contextHooks.afterPrivateReply(privateConversation, turn.speakerId, turn.text, replyText,
                            completed.hookContext, turn.proactive);
                } else {
                    contextHooks.afterSharedReply((SharedConversation) conversation, turn.speakerId, turn.text, replyText);
                }
            } catch (RuntimeException exception) {
                listener.onHookFailure(conversation, FailureReason.CONTEXT_HOOK);
            }
            List<UUID> recipients = conversation instanceof PrivateConversation
                    ? List.of(conversation.ownerId())
                    : List.copyOf(session.members);
            listener.onReply(new Reply(conversation, requestId, turn.speakerId, replyText, recipients));
        }
        session.lastTouchedMillis = clockMillis.getAsLong();
        pump(session);
    }

    private synchronized void finishFailure(ConversationRef conversation, UUID requestId, FailureReason reason) {
        Session session = sessions.get(conversation);
        if (closed || session == null || session.active == null
                || !session.active.turn.requestId.equals(requestId)) {
            return;
        }
        Turn turn = session.active.turn;
        session.active = null;
        removeHistoryMessage(session, requestId);
        listener.onFailure(new Failure(conversation, turn.speakerId, requestId, reason));
        session.lastTouchedMillis = clockMillis.getAsLong();
        pump(session);
    }

    private static FailureReason classify(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause != cause.getCause()) {
            cause = cause.getCause();
        }
        return cause instanceof java.util.concurrent.TimeoutException
                ? FailureReason.TIMEOUT
                : FailureReason.PROVIDER;
    }

    private void invalidateActive(Session session) {
        ActiveTurn active = session.active;
        if (active == null) {
            return;
        }
        session.active = null;
        removeHistoryMessage(session, active.turn.requestId);
        if (active.workerTask != null) {
            active.workerTask.cancel(true);
        }
        if (active.providerFuture != null) {
            active.providerFuture.cancel(true);
        }
    }

    private static void removeHistoryMessage(Session session, UUID requestId) {
        session.history.removeIf(message -> message.requestId.equals(requestId));
    }

    private void trimHistory(Session session) {
        while (session.history.size() > limits.maxHistoryMessages()
                || historyChars(session) > limits.maxHistoryChars()) {
            session.history.remove(0);
        }
    }

    private static int historyChars(Session session) {
        return session.history.stream().mapToInt(message -> message.message.content().length()).sum();
    }

    private static boolean isMember(Session session, UUID playerId) {
        return session.conversation instanceof PrivateConversation
                ? session.conversation.ownerId().equals(playerId)
                : session.members.contains(playerId);
    }

    private static String truncate(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, Math.min(maxChars, text.codePointCount(0, text.length()))));
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (Session session : sessions.values()) {
            invalidateActive(session);
            session.queue.clear();
            session.history.clear();
        }
        sessions.clear();
        sharedByOwner.clear();
        sharedById.clear();
        rateWindows.clear();
    }

    @FunctionalInterface
    public interface ServerExecutor {
        /** Schedule the task on the owning Minecraft server thread. */
        void execute(Runnable task);
    }

    public interface Listener {
        void onReply(Reply reply);

        void onFailure(Failure failure);

        default void onHookFailure(ConversationRef conversation, FailureReason reason) {
        }
    }

    /** Memory integrations get separate private/shared hooks and immutable recent history snapshots. */
    public interface ContextHooks {
        default HookContext privateContext(
                PrivateConversation conversation,
                UUID speakerId,
                String currentMessage,
                List<ChatMessage> recentHistory
        ) {
            return HookContext.empty();
        }

        default HookContext sharedContext(
                SharedConversation conversation,
                UUID speakerId,
                String currentMessage,
                List<ChatMessage> recentHistory
        ) {
            return HookContext.empty();
        }

        default void afterPrivateReply(
                PrivateConversation conversation,
                UUID speakerId,
                String userMessage,
                String assistantReply,
                HookContext context,
                boolean proactive
        ) {
        }

        default void afterSharedReply(
                SharedConversation conversation,
                UUID speakerId,
                String userMessage,
                String assistantReply
        ) {
        }
    }

    public sealed interface ConversationRef permits PrivateConversation, SharedConversation {
        UUID worldId();

        UUID ownerId();

        UUID companionId();
    }

    public record PrivateConversation(UUID worldId, UUID ownerId, UUID companionId) implements ConversationRef {
        public PrivateConversation {
            Objects.requireNonNull(worldId, "worldId");
            Objects.requireNonNull(ownerId, "ownerId");
            Objects.requireNonNull(companionId, "companionId");
        }
    }

    public record SharedConversation(
            UUID worldId,
            UUID sessionId,
            UUID ownerId,
            UUID companionId
    ) implements ConversationRef {
        public SharedConversation {
            Objects.requireNonNull(worldId, "worldId");
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(ownerId, "ownerId");
            Objects.requireNonNull(companionId, "companionId");
        }
    }

    public record Reply(
            ConversationRef conversation,
            UUID requestId,
            UUID speakerId,
            String text,
            List<UUID> recipients
    ) {
        public Reply {
            recipients = List.copyOf(recipients);
        }
    }

    public record Failure(ConversationRef conversation, UUID speakerId, UUID requestId, FailureReason reason) {
    }

    public record HookContext(List<ChatMessage> messages, Object state) {
        public HookContext {
            messages = List.copyOf(messages);
        }

        public static HookContext empty() {
            return new HookContext(List.of(), null);
        }
    }

    public record OwnerMetrics(int sessionCount, int inFlightCount, int queuedCount,
                               int sharedSessionCount, int sharedMemberCount) {
    }

    public record ShareResult(ShareStatus status, SharedConversation conversation) {
    }

    public record CancelResult(OperationStatus status, int cancelledRequests) {
    }

    public record Limits(
            int maxSessions,
            int maxQueuePerSession,
            int maxMessageChars,
            int maxHistoryMessages,
            int maxHistoryChars,
            int maxContextMessages,
            int maxContextChars,
            int maxResponseChars,
            int maxSharedMembers,
            int maxInvitationsPerSharedSession,
            int requestsPerWindow,
            Duration rateWindow,
            Duration requestTimeout,
            Duration idleSessionTtl
    ) {
        public Limits {
            if (maxSessions < 1 || maxQueuePerSession < 0 || maxMessageChars < 1
                    || maxHistoryMessages < 1 || maxHistoryChars < 0
                    || maxContextMessages < 0 || maxContextChars < 0
                    || maxResponseChars < 1 || maxSharedMembers < 1 || maxInvitationsPerSharedSession < 0
                    || requestsPerWindow < 1) {
                throw new IllegalArgumentException("Chat limits must be positive and internally consistent.");
            }
            requirePositive(rateWindow, "rateWindow");
            requirePositive(requestTimeout, "requestTimeout");
            requirePositive(idleSessionTtl, "idleSessionTtl");
        }

        public static Limits defaults() {
            return new Limits(
                    128, 4, 512, 24, 12_000, 8, 4_000, 1_000,
                    16, 16, 8, Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMinutes(30));
        }

        public Limits withQueuePerSession(int value) {
            return new Limits(maxSessions, value, maxMessageChars, maxHistoryMessages, maxHistoryChars, maxContextMessages,
                    maxContextChars, maxResponseChars, maxSharedMembers, maxInvitationsPerSharedSession,
                    requestsPerWindow, rateWindow, requestTimeout, idleSessionTtl);
        }

        public Limits withRequestsPerWindow(int count, Duration window) {
            return new Limits(maxSessions, maxQueuePerSession, maxMessageChars, maxHistoryMessages, maxHistoryChars,
                    maxContextMessages, maxContextChars, maxResponseChars, maxSharedMembers,
                    maxInvitationsPerSharedSession, count, window, requestTimeout, idleSessionTtl);
        }

        public Limits withSessionCapacity(int count) {
            return new Limits(count, maxQueuePerSession, maxMessageChars, maxHistoryMessages, maxHistoryChars,
                    maxContextMessages, maxContextChars, maxResponseChars, maxSharedMembers,
                    maxInvitationsPerSharedSession, requestsPerWindow, rateWindow, requestTimeout, idleSessionTtl);
        }

        public Limits withModelBudgets(
                int historyMessages,
                int historyChars,
                int contextMessages,
                int contextChars,
                int messageChars
        ) {
            return new Limits(maxSessions, maxQueuePerSession, messageChars, historyMessages, historyChars,
                    contextMessages, contextChars, maxResponseChars, maxSharedMembers,
                    maxInvitationsPerSharedSession, requestsPerWindow, rateWindow, requestTimeout, idleSessionTtl);
        }

        private static void requirePositive(Duration value, String name) {
            Objects.requireNonNull(value, name);
            if (value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(name + " must be positive");
            }
        }
    }

    public enum SubmitStatus {
        ACCEPTED,
        QUEUED,
        EMPTY_MESSAGE,
        MESSAGE_TOO_LONG,
        RATE_LIMITED,
        QUEUE_FULL,
        SESSION_LIMIT,
        NOT_MEMBER,
        UNAVAILABLE
    }

    public enum ShareStatus {
        CREATED,
        EXISTING,
        INVITED,
        EXISTING_INVITATION,
        ALREADY_MEMBER,
        JOINED,
        LEFT,
        NOT_FOUND,
        NOT_OWNER,
        NOT_INVITED,
        NOT_MEMBER,
        OWNER_CANNOT_LEAVE,
        MEMBER_LIMIT,
        INVITATION_LIMIT,
        SESSION_LIMIT,
        UNAVAILABLE
    }

    public enum OperationStatus {
        CLEARED,
        CANCELLED,
        NOT_FOUND,
        NOT_MEMBER,
        NOT_OWNER,
        UNAVAILABLE
    }

    public enum FailureReason {
        PROVIDER,
        TIMEOUT,
        BUSY,
        EMPTY_RESPONSE,
        CONTEXT_HOOK
    }

    private static final class Session {
        private final ConversationRef conversation;
        private final ArrayDeque<Turn> queue = new ArrayDeque<>();
        private final List<HistoryMessage> history = new ArrayList<>();
        private final Set<UUID> members = new LinkedHashSet<>();
        private final Set<UUID> invitations = new LinkedHashSet<>();
        private ActiveTurn active;
        private long lastTouchedMillis;

        private Session(ConversationRef conversation) {
            this.conversation = conversation;
        }
    }

    private record Turn(UUID requestId, UUID speakerId, String text, boolean proactive) {
    }

    private record HistoryMessage(UUID requestId, ChatMessage message) {
    }

    private static final class ActiveTurn {
        private final Turn turn;
        private Future<?> workerTask;
        private CompletableFuture<ModelResponse> providerFuture;
        private HookContext hookContext;

        private ActiveTurn(Turn turn) {
            this.turn = turn;
        }
    }

    private static final class RateWindow {
        private final long startedAtMillis;
        private int count;

        private RateWindow(long startedAtMillis, int count) {
            this.startedAtMillis = startedAtMillis;
            this.count = count;
        }
    }
}
