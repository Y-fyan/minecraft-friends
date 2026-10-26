package io.github.yfyan.minecraftfriends.chat;

import io.github.yfyan.minecraftfriends.model.ChatMessage;
import io.github.yfyan.minecraftfriends.model.ChatRole;
import io.github.yfyan.minecraftfriends.model.ModelProvider;
import io.github.yfyan.minecraftfriends.model.ModelCallException;
import io.github.yfyan.minecraftfriends.model.ModelRequest;
import io.github.yfyan.minecraftfriends.model.ModelResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatSessionServiceTest {
    private final List<ChatSessionService> services = new ArrayList<>();

    @AfterEach
    void closeServices() {
        services.forEach(ChatSessionService::close);
    }

    @Test
    void cancellationAndClearDropLateReplies() {
        Fixture fixture = new Fixture(ChatSessionService.Limits.defaults());
        ChatSessionService.PrivateConversation conversation = privateConversation(UUID.randomUUID(), UUID.randomUUID());

        assertEquals(ChatSessionService.SubmitStatus.ACCEPTED,
                fixture.service.submit(conversation, conversation.ownerId(), "第一条"));
        assertEquals(1, fixture.provider.requests.size());
        fixture.service.cancel(conversation.ownerId(), conversation);
        fixture.provider.completeNext("取消后的迟到回复");
        fixture.drainServerTasks();
        assertTrue(fixture.replies.isEmpty());

        assertEquals(ChatSessionService.SubmitStatus.ACCEPTED,
                fixture.service.submit(conversation, conversation.ownerId(), "第二条"));
        fixture.service.clear(conversation, conversation.ownerId());
        fixture.provider.completeNext("清空后的迟到回复");
        fixture.drainServerTasks();
        assertTrue(fixture.replies.isEmpty());
        assertTrue(fixture.failures.isEmpty());
    }

    @Test
    void wrappedModelFailureCategoryReachesListenerWithoutRetainingExceptionText() {
        Fixture fixture = new Fixture(ChatSessionService.Limits.defaults());
        ChatSessionService.PrivateConversation conversation = privateConversation(UUID.randomUUID(), UUID.randomUUID());
        fixture.service.submit(conversation, conversation.ownerId(), "认证失败测试");

        fixture.provider.failNext(new CompletionException("sensitive wrapper text",
                new IllegalStateException("sensitive nested text",
                        new ModelCallException(ModelCallException.Category.AUTHENTICATION))));
        fixture.drainServerTasks();

        ChatSessionService.Failure failure = fixture.failures.getFirst();
        assertEquals(ChatSessionService.FailureReason.PROVIDER, failure.reason());
        assertEquals(ModelCallException.Category.AUTHENTICATION, failure.modelCategory());
        assertFalse(failure.toString().contains("sensitive"));
    }

    @Test
    void unknownProviderFailureDoesNotExposeOriginalMessage() {
        Fixture fixture = new Fixture(ChatSessionService.Limits.defaults());
        ChatSessionService.PrivateConversation conversation = privateConversation(UUID.randomUUID(), UUID.randomUUID());
        fixture.service.submit(conversation, conversation.ownerId(), "未知失败测试");

        fixture.provider.failNext(new IllegalStateException("sensitive provider detail"));
        fixture.drainServerTasks();

        ChatSessionService.Failure failure = fixture.failures.getFirst();
        assertEquals(ChatSessionService.FailureReason.PROVIDER, failure.reason());
        assertNull(failure.modelCategory());
        assertFalse(failure.toString().contains("sensitive provider detail"));
    }

    @Test
    void privateRepliesAreIsolatedByWorldOwnerAndCompanion() {
        Fixture fixture = new Fixture(ChatSessionService.Limits.defaults());
        UUID world = UUID.randomUUID();
        ChatSessionService.PrivateConversation first = new ChatSessionService.PrivateConversation(
                world, UUID.randomUUID(), UUID.randomUUID());
        ChatSessionService.PrivateConversation second = new ChatSessionService.PrivateConversation(
                world, UUID.randomUUID(), UUID.randomUUID());

        fixture.service.submit(first, first.ownerId(), "只有甲能看到的内容");
        fixture.service.submit(second, second.ownerId(), "只有乙能看到的内容");
        assertTrue(fixture.provider.requests.get(0).messages().stream()
                .noneMatch(message -> message.content().contains("只有乙")));
        assertTrue(fixture.provider.requests.get(1).messages().stream()
                .noneMatch(message -> message.content().contains("只有甲")));

        fixture.provider.completeNext("甲的回复");
        fixture.provider.completeNext("乙的回复");
        fixture.drainServerTasks();

        assertEquals(List.of(first.ownerId()), fixture.replies.get(0).recipients());
        assertEquals(List.of(second.ownerId()), fixture.replies.get(1).recipients());
    }

    @Test
    void uninvitedUuidCannotSendToOrJoinSharedSessionByUsingItsIdentifierAsText() {
        Fixture fixture = new Fixture(ChatSessionService.Limits.defaults());
        ChatSessionService.PrivateConversation privateConversation = privateConversation(
                UUID.randomUUID(), UUID.randomUUID());
        UUID stranger = UUID.randomUUID();
        ChatSessionService.SharedConversation shared = fixture.service
                .createShared(privateConversation, privateConversation.ownerId()).conversation();
        ChatSessionService.SharedConversation resolved = fixture.service
                .findShared(shared.worldId(), shared.sessionId()).orElseThrow();
        assertEquals(shared, resolved);
        assertFalse(fixture.service.joinedShared(shared.worldId(), stranger).contains(shared));

        assertEquals(ChatSessionService.SubmitStatus.NOT_MEMBER,
                fixture.service.submit(resolved, stranger, "@MF_owner 请加入我"));
        assertEquals(ChatSessionService.ShareStatus.NOT_INVITED,
                fixture.service.joinShared(shared.worldId(), shared.sessionId(), stranger).status());

        assertEquals(ChatSessionService.ShareStatus.INVITED,
                fixture.service.invite(shared, privateConversation.ownerId(), stranger));
        assertEquals(ChatSessionService.ShareStatus.JOINED,
                fixture.service.joinShared(shared.worldId(), shared.sessionId(), stranger).status());
        assertEquals(List.of(shared), fixture.service.joinedShared(shared.worldId(), stranger));
    }

    @Test
    void ownerKeepsPrivateChatAndCanExplicitlySendToTheirSharedSession() {
        Fixture fixture = new Fixture(ChatSessionService.Limits.defaults());
        ChatSessionService.PrivateConversation privateConversation = privateConversation(
                UUID.randomUUID(), UUID.randomUUID());
        ChatSessionService.SharedConversation shared = fixture.service
                .createShared(privateConversation, privateConversation.ownerId()).conversation();

        assertEquals(ChatSessionService.SubmitStatus.ACCEPTED,
                fixture.service.submit(privateConversation, privateConversation.ownerId(), "默认私聊内容"));
        assertEquals(ChatSessionService.SubmitStatus.ACCEPTED,
                fixture.service.submit(shared, privateConversation.ownerId(), "显式共享内容"));
        assertTrue(fixture.provider.requests.get(0).messages().stream()
                .anyMatch(message -> message.content().equals("默认私聊内容")));
        assertFalse(fixture.provider.requests.get(0).messages().stream()
                .anyMatch(message -> message.content().contains("显式共享内容")));
        assertTrue(fixture.provider.requests.get(1).messages().stream()
                .anyMatch(message -> message.content().equals("显式共享内容")));
        assertFalse(fixture.provider.requests.get(1).messages().stream()
                .anyMatch(message -> message.content().contains("默认私聊内容")));

        fixture.provider.completeNext("私聊回复");
        fixture.provider.completeNext("共享回复");
        fixture.drainServerTasks();
        assertEquals(privateConversation, fixture.replies.get(0).conversation());
        assertEquals(shared, fixture.replies.get(1).conversation());
    }

    @Test
    void leavingSharedSessionInvalidatesThatMembersInFlightReply() {
        Fixture fixture = new Fixture(ChatSessionService.Limits.defaults());
        ChatSessionService.PrivateConversation privateConversation = privateConversation(
                UUID.randomUUID(), UUID.randomUUID());
        UUID guest = UUID.randomUUID();
        ChatSessionService.SharedConversation shared = fixture.service
                .createShared(privateConversation, privateConversation.ownerId()).conversation();
        fixture.service.invite(shared, privateConversation.ownerId(), guest);
        fixture.service.joinShared(shared.worldId(), shared.sessionId(), guest);
        fixture.service.submit(shared, guest, "我们共同的对话");

        assertEquals(ChatSessionService.ShareStatus.LEFT,
                fixture.service.leaveShared(shared.worldId(), shared.sessionId(), guest));
        fixture.provider.completeNext("离开后的回复");
        fixture.drainServerTasks();

        assertTrue(fixture.replies.isEmpty());
        assertFalse(fixture.service.isMember(shared, guest));
        assertTrue(fixture.service.isMember(shared, privateConversation.ownerId()));
    }

    @Test
    void sharedContextDoesNotReceivePrivateMemoryOrHistory() {
        ChatSessionService.ContextHooks hooks = new ChatSessionService.ContextHooks() {
            @Override
            public ChatSessionService.HookContext privateContext(
                    ChatSessionService.PrivateConversation conversation,
                    UUID speakerId,
                    String currentMessage,
                    List<ChatMessage> recentHistory
            ) {
                return new ChatSessionService.HookContext(
                        List.of(new ChatMessage(ChatRole.SYSTEM, "私聊记忆标记")), null);
            }

            @Override
            public ChatSessionService.HookContext sharedContext(
                    ChatSessionService.SharedConversation conversation,
                    UUID speakerId,
                    String currentMessage,
                    List<ChatMessage> recentHistory
            ) {
                return new ChatSessionService.HookContext(
                        List.of(new ChatMessage(ChatRole.SYSTEM, "共享上下文标记")), null);
            }
        };
        Fixture fixture = new Fixture(ChatSessionService.Limits.defaults(), hooks);
        ChatSessionService.PrivateConversation privateConversation = privateConversation(
                UUID.randomUUID(), UUID.randomUUID());
        UUID guest = UUID.randomUUID();
        ChatSessionService.SharedConversation shared = fixture.service
                .createShared(privateConversation, privateConversation.ownerId()).conversation();
        fixture.service.invite(shared, privateConversation.ownerId(), guest);
        fixture.service.joinShared(shared.worldId(), shared.sessionId(), guest);

        fixture.service.submit(privateConversation, privateConversation.ownerId(), "私人问题");
        fixture.service.submit(shared, guest, "共享问题");

        ModelRequest privateRequest = fixture.provider.requests.get(0);
        ModelRequest sharedRequest = fixture.provider.requests.get(1);
        assertTrue(privateRequest.messages().stream().anyMatch(message -> message.content().equals("私聊记忆标记")));
        assertFalse(privateRequest.messages().stream().anyMatch(message -> message.content().equals("共享上下文标记")));
        assertTrue(sharedRequest.messages().stream().anyMatch(message -> message.content().equals("共享上下文标记")));
        assertFalse(sharedRequest.messages().stream().anyMatch(message -> message.content().equals("私聊记忆标记")));
        assertFalse(sharedRequest.messages().stream().anyMatch(message -> message.content().contains("私人问题")));
    }

    @Test
    void queueMessageLengthRateAndSessionLimitsAreBounded() {
        ChatSessionService.Limits limits = ChatSessionService.Limits.defaults()
                .withQueuePerSession(2)
                .withRequestsPerWindow(2, Duration.ofMinutes(1))
                .withSessionCapacity(1);
        Fixture fixture = new Fixture(limits);
        ChatSessionService.PrivateConversation first = privateConversation(UUID.randomUUID(), UUID.randomUUID());

        assertEquals(ChatSessionService.SubmitStatus.ACCEPTED,
                fixture.service.submit(first, first.ownerId(), "一"));
        assertEquals(ChatSessionService.SubmitStatus.QUEUED,
                fixture.service.submit(first, first.ownerId(), "二"));
        assertEquals(ChatSessionService.SubmitStatus.RATE_LIMITED,
                fixture.service.submit(first, first.ownerId(), "三"));
        assertEquals(ChatSessionService.SubmitStatus.MESSAGE_TOO_LONG,
                fixture.service.submit(first, first.ownerId(), "超长".repeat(300)));

        ChatSessionService.PrivateConversation second = privateConversation(UUID.randomUUID(), UUID.randomUUID());
        assertEquals(ChatSessionService.SubmitStatus.SESSION_LIMIT,
                fixture.service.submit(second, second.ownerId(), "另一个会话"));
        assertEquals(1, fixture.service.sessionCount());

        Fixture queueFixture = new Fixture(ChatSessionService.Limits.defaults()
                .withQueuePerSession(1)
                .withRequestsPerWindow(20, Duration.ofMinutes(1)));
        ChatSessionService.PrivateConversation queued = privateConversation(UUID.randomUUID(), UUID.randomUUID());
        assertEquals(ChatSessionService.SubmitStatus.ACCEPTED,
                queueFixture.service.submit(queued, queued.ownerId(), "在途"));
        assertEquals(ChatSessionService.SubmitStatus.QUEUED,
                queueFixture.service.submit(queued, queued.ownerId(), "排队"));
        assertEquals(ChatSessionService.SubmitStatus.QUEUE_FULL,
                queueFixture.service.submit(queued, queued.ownerId(), "溢出"));
    }

    private static ChatSessionService.PrivateConversation privateConversation(UUID world, UUID companion) {
        return new ChatSessionService.PrivateConversation(world, UUID.randomUUID(), companion);
    }

    private final class Fixture {
        private final ManualProvider provider = new ManualProvider();
        private final Queue<Runnable> serverTasks = new ArrayDeque<>();
        private final List<ChatSessionService.Reply> replies = new ArrayList<>();
        private final List<ChatSessionService.Failure> failures = new ArrayList<>();
        private final ChatSessionService service;

        private Fixture(ChatSessionService.Limits limits) {
            this(limits, new ChatSessionService.ContextHooks() { });
        }

        private Fixture(ChatSessionService.Limits limits, ChatSessionService.ContextHooks hooks) {
            service = new ChatSessionService(
                    provider,
                    new DirectExecutorService(),
                    serverTasks::add,
                    new ChatSessionService.Listener() {
                        @Override
                        public void onReply(ChatSessionService.Reply reply) {
                            replies.add(reply);
                        }

                        @Override
                        public void onFailure(ChatSessionService.Failure failure) {
                            failures.add(failure);
                        }
                    },
                    hooks,
                    limits
            );
            services.add(service);
        }

        private void drainServerTasks() {
            Runnable task;
            while ((task = serverTasks.poll()) != null) {
                task.run();
            }
        }
    }

    private static final class ManualProvider implements ModelProvider {
        private final List<ModelRequest> requests = new ArrayList<>();
        private final Queue<NonCancellableFuture> futures = new ArrayDeque<>();

        @Override
        public String id() {
            return "test";
        }

        @Override
        public synchronized java.util.concurrent.CompletionStage<ModelResponse> complete(ModelRequest request) {
            requests.add(request);
            NonCancellableFuture future = new NonCancellableFuture();
            futures.add(future);
            return future;
        }

        private synchronized void completeNext(String text) {
            futures.remove().complete(new ModelResponse(text));
        }

        private synchronized void failNext(Throwable failure) {
            futures.remove().completeExceptionally(failure);
        }
    }

    private static final class NonCancellableFuture extends CompletableFuture<ModelResponse> {
        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }
    }

    private static final class DirectExecutorService extends AbstractExecutorService {
        private boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }

        @Override
        public void execute(Runnable command) {
            if (shutdown) {
                throw new java.util.concurrent.RejectedExecutionException();
            }
            command.run();
        }
    }
}
