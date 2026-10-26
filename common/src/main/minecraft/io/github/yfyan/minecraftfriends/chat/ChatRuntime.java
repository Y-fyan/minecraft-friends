package io.github.yfyan.minecraftfriends.chat;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.yfyan.minecraftfriends.companion.CompanionData;
import io.github.yfyan.minecraftfriends.companion.CompanionIdentity;
import io.github.yfyan.minecraftfriends.companion.CompanionRuntime;
import io.github.yfyan.minecraftfriends.config.ConfigLoader;
import io.github.yfyan.minecraftfriends.config.ModConfig;
import io.github.yfyan.minecraftfriends.config.ProviderMode;
import io.github.yfyan.minecraftfriends.memory.CompanionMemoryStore;
import io.github.yfyan.minecraftfriends.memory.ContextMessage;
import io.github.yfyan.minecraftfriends.memory.ContextSpeaker;
import io.github.yfyan.minecraftfriends.memory.MemoryEntry;
import io.github.yfyan.minecraftfriends.memory.MemoryScope;
import io.github.yfyan.minecraftfriends.memory.PersonaSettings;
import io.github.yfyan.minecraftfriends.memory.ProactiveEvent;
import io.github.yfyan.minecraftfriends.memory.RequestContext;
import io.github.yfyan.minecraftfriends.model.ChatMessage;
import io.github.yfyan.minecraftfriends.model.ChatRole;
import io.github.yfyan.minecraftfriends.model.ModelProvider;
import io.github.yfyan.minecraftfriends.model.ModelProviders;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/** Minecraft server adapter for private/shared chat, memory commands, and verified greetings. */
public final class ChatRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger("minecraft-friends/chat");
    private static final String DEFAULT_PREFIX = "@";
    private static final Map<MinecraftServer, RuntimeState> STATES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile ProviderFactory providerFactory = ModelProviders::create;

    private ChatRuntime() {
    }

    /** Called by the loader's server-start event after configDir has been resolved. */
    public static void start(MinecraftServer server, Path configDir) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(configDir, "configDir");
        stop(server);

        ConfigLoader.LoadedConfig loaded = null;
        ModConfig config = ModConfig.defaults();
        String disabledReason = "";
        try {
            loaded = new ConfigLoader().loadOrCreate(configDir);
            config = loaded.config();
        } catch (IOException failure) {
            disabledReason = "配置文件不可读取";
            LOGGER.warn("Chat runtime disabled because configuration could not be loaded ({})",
                    failure.getClass().getSimpleName());
        }

        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        UUID worldId;
        try {
            worldId = PersistentWorldIdentity.loadOrCreate(
                    worldRoot.resolve("data").resolve(PersistentWorldIdentity.FILE_NAME));
        } catch (IOException | RuntimeException failure) {
            worldId = null;
            disabledReason = "世界身份数据不可用";
            LOGGER.warn("Chat runtime disabled because the persistent world UUID could not be loaded ({})",
                    failure.getClass().getSimpleName());
        }

        ModelProvider provider = null;
        boolean enabled = config.enabled() && config.provider() != ProviderMode.DISABLED
                && worldId != null && disabledReason.isEmpty();
        if (enabled && config.provider() == ProviderMode.CHAT_COMPLETIONS
                && (config.modelMaxContextMessages() < 2 || config.modelMaxContextCharacters() < 128)) {
            enabled = false;
            disabledReason = "模型上下文预算不足；至少需要 2 条消息和 128 个字符。";
        }
        if (enabled) {
            try {
                provider = providerFactory.create(config);
                if (provider == null) {
                    enabled = false;
                    disabledReason = "模型提供方尚未配置";
                }
            } catch (RuntimeException failure) {
                enabled = false;
                disabledReason = "模型提供方初始化失败";
                LOGGER.warn("Chat runtime disabled because the model provider factory failed ({})",
                        failure.getClass().getSimpleName());
            }
        } else if (disabledReason.isEmpty()) {
            disabledReason = config.enabled() ? "模型提供方已关闭" : "模组功能已关闭";
        }

        String prefix = loadPrefix(loaded == null ? configDir.resolve(ConfigLoader.FILE_NAME) : loaded.path());
        RuntimeState state = new RuntimeState(
                server,
                config,
                loaded != null && config.enabled(),
                worldId,
                prefix,
                worldRoot.resolve("data").resolve("memory"),
                enabled,
                disabledReason,
                provider
        );
        synchronized (STATES) {
            STATES.put(server, state);
        }
        LOGGER.info("Chat runtime started: enabled={}, provider={}, prefix={}",
                enabled, provider == null ? "disabled" : provider.id(), prefix);
    }

    /** Install the #5 provider factory before server startup; provider creation must not perform network I/O. */
    public static void setProviderFactory(ProviderFactory factory) {
        providerFactory = Objects.requireNonNull(factory, "factory");
    }

    public static void stop(MinecraftServer server) {
        RuntimeState state;
        synchronized (STATES) {
            state = STATES.remove(server);
        }
        if (state != null) {
            state.close();
        }
    }

    /** Also used by integrations that receive a world-unload event before server-stopping. */
    public static void onWorldUnloaded(MinecraftServer server) {
        stop(server);
    }

    public static void onLogin(ServerPlayer player) {
        RuntimeState state = state(player.server);
        if (state == null || !state.chatEnabled || state.worldId == null) {
            return;
        }
        LoginToken token = new LoginToken();
        LoginToken previous = state.loginTokens.put(player.getUUID(), token);
        if (previous != null) {
            synchronized (previous) {
                previous.active = false;
            }
        }
        state.executeMain(() -> offerLoginGreeting(state, player, token));
    }

    /** Called after a newly created or summoned companion is available; it only claims FIRST_MEETING. */
    public static void onCompanionAvailable(ServerPlayer owner) {
        RuntimeState state = state(owner.server);
        if (state == null || !state.chatEnabled || state.worldId == null) {
            return;
        }
        LoginToken token = state.loginTokens.computeIfAbsent(owner.getUUID(), ignored -> new LoginToken());
        state.executeMain(() -> {
            if (!isCurrent(state) || !state.isCurrentLogin(owner.getUUID(), token)
                    || state.server.getPlayerList().getPlayer(owner.getUUID()) != owner) {
                return;
            }
            CompanionData.Entry entry = CompanionRuntime.data(state.server).find(owner.getUUID()).orElse(null);
            if (entry == null || !companionAvailable(state.server, entry)) {
                return;
            }
            claimProactiveEvent(state, owner, entry, ProactiveEvent.FIRST_MEETING, token);
        });
    }

    public static void onLogout(ServerPlayer player) {
        RuntimeState state = state(player.server);
        if (state == null) {
            return;
        }
        LoginToken token = state.loginTokens.remove(player.getUUID());
        if (token != null) {
            synchronized (token) {
                token.active = false;
            }
        }
        state.firstMeetingClaimedSinceLogin.remove(player.getUUID());
        if (state.sessions != null) {
            state.sessions.onPlayerLogout(player.getUUID());
        }
    }

    /** Call only after a loader has verified that this owned companion actually took damage. */
    public static void onCompanionInjured(ServerPlayer companion) {
        RuntimeState state = state(companion.server);
        if (state == null || !state.chatEnabled || state.worldId == null || !companion.isAlive()) {
            return;
        }
        CompanionData.Entry entry = CompanionRuntime.data(companion.server)
                .findByCompanion(companion.getUUID()).orElse(null);
        if (entry == null || !entry.active()) {
            return;
        }
        ServerPlayer owner = companion.server.getPlayerList().getPlayer(entry.identity().ownerId());
        if (owner != null) {
            LoginToken token = state.loginTokens.get(owner.getUUID());
            if (token != null) {
                claimProactiveEvent(state, owner, entry, ProactiveEvent.INJURED, token);
            }
        }
    }

    public static void onCompanionRemoved(UUID ownerId) {
        Objects.requireNonNull(ownerId, "ownerId");
        synchronized (STATES) {
            for (RuntimeState state : STATES.values()) {
                if (state.sessions != null) {
                    state.sessions.onCompanionRemoved(ownerId);
                }
            }
        }
    }

    /** Server-scoped variant; loaders should prefer it so equal player UUIDs in other worlds stay isolated. */
    public static void onCompanionRemoved(MinecraftServer server, UUID ownerId) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(ownerId, "ownerId");
        RuntimeState state = state(server);
        if (state != null && state.sessions != null) {
            state.sessions.onCompanionRemoved(ownerId);
        }
    }

    public static void onTick(MinecraftServer server) {
        RuntimeState state = state(server);
        if (state != null && state.sessions != null) {
            state.sessions.prune();
        }
    }

    /** Returns true only when a valid owned or already-joined shared companion prefix was handled. */
    public static boolean onChat(ServerPlayer player, String text) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(text, "text");
        if (text.startsWith("/")) {
            return false;
        }
        RuntimeState state = state(player.server);
        if (state == null) {
            return false;
        }
        Optional<ChatPrefix.Address> parsed = ChatPrefix.parse(text, state.prefix);
        if (parsed.isEmpty()) {
            return false;
        }
        ChatPrefix.Address address = parsed.orElseThrow();
        if (state.worldId == null) {
            CompanionData.Entry owned = CompanionRuntime.data(state.server).find(player.getUUID()).orElse(null);
            if (owned != null && sameName(owned.identity().name(), address.companionName())) {
                player.sendSystemMessage(Component.literal("聊天功能当前已关闭：" + state.disabledReason + "。"));
                return true;
            }
            return false;
        }
        List<Target> matches = findAddressTargets(state, player, address.companionName());
        if (matches.isEmpty()) {
            return false;
        }
        if (matches.size() > 1) {
            player.sendSystemMessage(Component.literal("有多个伙伴使用这个名字，请用 /mf chat 或加入唯一的共享会话。"));
            return true;
        }
        Target target = matches.getFirst();
        if (!state.chatEnabled || state.sessions == null) {
            player.sendSystemMessage(Component.literal("聊天功能当前已关闭：" + state.disabledReason + "。"));
            return true;
        }
        if (!companionAvailable(state.server, target.entry())) {
            player.sendSystemMessage(Component.literal(target.entry().identity().name()
                    + " 当前暂离或离线，请先召唤伙伴。"));
            return true;
        }
        if (state.isMemoryMutationPending(scope(state, target.entry()))) {
            player.sendSystemMessage(Component.literal("记忆正在安全保存，请稍后再发送。"));
            return true;
        }
        ChatSessionService.SubmitStatus status = submit(state, target.conversation(), player, address.message());
        if (status == ChatSessionService.SubmitStatus.ACCEPTED
                || status == ChatSessionService.SubmitStatus.QUEUED) {
            echoToMembers(state, target.conversation(), player, address.message());
            if (status == ChatSessionService.SubmitStatus.QUEUED) {
                player.sendSystemMessage(Component.literal("伙伴正在回复，消息已按顺序排队。"));
            }
        } else {
            player.sendSystemMessage(Component.literal(submitMessage(status)));
        }
        return true;
    }

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        var chat = literal("chat")
                .then(argument("message", StringArgumentType.greedyString())
                        .executes(context -> chatCommand(context.getSource(),
                                StringArgumentType.getString(context, "message"))))
                .then(literal("cancel").executes(context -> cancelCommand(context.getSource())))
                .then(literal("clear").executes(context -> clearChatCommand(context.getSource())))
                        .then(literal("status").executes(context -> statusCommand(context.getSource())))
                        .then(literal("shared")
                                .then(literal("create").executes(context -> createSharedCommand(context.getSource())))
                                .then(literal("send").then(argument("session", StringArgumentType.word())
                                        .then(argument("message", StringArgumentType.greedyString())
                                                .executes(context -> sendSharedCommand(context.getSource(),
                                                        StringArgumentType.getString(context, "session"),
                                                        StringArgumentType.getString(context, "message"))))))
                                .then(literal("invite").then(argument("player", StringArgumentType.word())
                                .executes(context -> inviteCommand(context.getSource(),
                                        StringArgumentType.getString(context, "player")))))
                        .then(literal("join").then(argument("session", StringArgumentType.word())
                                .executes(context -> joinSharedCommand(context.getSource(),
                                        StringArgumentType.getString(context, "session")))))
                        .then(literal("leave").then(argument("session", StringArgumentType.word())
                                .executes(context -> leaveSharedCommand(context.getSource(),
                                        StringArgumentType.getString(context, "session"))))));
        var memory = literal("memory")
                .then(literal("list").executes(context -> listMemoryCommand(context.getSource())))
                .then(literal("remember").then(argument("text", StringArgumentType.greedyString())
                        .executes(context -> rememberCommand(context.getSource(),
                                StringArgumentType.getString(context, "text")))))
                .then(literal("delete").then(argument("id", StringArgumentType.word())
                        .executes(context -> deleteMemoryCommand(context.getSource(),
                                StringArgumentType.getString(context, "id")))))
                .then(literal("clear").executes(context -> clearMemoryCommand(context.getSource())))
                .then(literal("address").then(argument("name", StringArgumentType.greedyString())
                        .executes(context -> addressCommand(context.getSource(),
                                StringArgumentType.getString(context, "name")))))
                .then(literal("tone").then(argument("tone", StringArgumentType.greedyString())
                        .executes(context -> toneCommand(context.getSource(),
                                StringArgumentType.getString(context, "tone")))))
                .then(literal("proactive").then(argument("enabled", BoolArgumentType.bool())
                        .executes(context -> proactiveCommand(context.getSource(),
                                BoolArgumentType.getBool(context, "enabled")))));
        dispatcher.register(literal("mf").then(chat).then(memory));
    }

    /** Snapshot for loader tests and in-game diagnostics; reading it does not touch the memory files. */
    public static ChatStatus status(ServerPlayer player) {
        return status(player.server, player.getUUID());
    }

    public static ChatStatus status(MinecraftServer server, UUID ownerId) {
        RuntimeState state = state(server);
        if (state == null) {
            return ChatStatus.notStarted();
        }
        CompanionData.Entry entry = CompanionRuntime.data(server).find(ownerId).orElse(null);
        boolean online = entry != null && server.getPlayerList().getPlayer(entry.identity().companionId()) != null;
        ChatSessionService.OwnerMetrics metrics = state.sessions == null
                ? new ChatSessionService.OwnerMetrics(0, 0, 0, 0, 0)
                : state.sessions.metricsForOwner(ownerId);
        ChatMemoryCoordinator.Summary memory = entry == null || state.memory == null || state.worldId == null
                ? ChatMemoryCoordinator.Summary.notLoaded()
                : state.memory.summary(scope(state, entry));
        return new ChatStatus(
                state.chatEnabled,
                state.globalEnabled,
                state.disabledReason,
                state.worldId,
                state.provider == null ? "disabled" : state.provider.id(),
                state.prefix,
                entry == null ? null : entry.identity().companionId(),
                entry == null ? "" : entry.identity().name(),
                entry != null && entry.active(),
                online,
                metrics.sessionCount(),
                metrics.inFlightCount(),
                metrics.queuedCount(),
                metrics.sharedSessionCount(),
                metrics.sharedMemberCount(),
                memory.loaded(),
                memory.generation(),
                memory.memoryCount(),
                memory.persona().preferredAddress(),
                memory.persona().tone(),
                memory.proactiveEnabled()
        );
    }

    /** Allows the loader's companion creation command to enforce enabled=false before making an entity. */
    public static boolean isEnabled(MinecraftServer server) {
        RuntimeState state = state(server);
        return state != null && state.globalEnabled;
    }

    private static int chatCommand(CommandSourceStack source, String message) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        RuntimeState state = state(player.server);
        if (state == null || !state.chatEnabled || state.sessions == null) {
            player.sendSystemMessage(Component.literal("聊天功能当前已关闭"));
            return 0;
        }
        CompanionData.Entry entry = CompanionRuntime.data(player.server).find(player.getUUID()).orElse(null);
        if (entry == null) {
            player.sendSystemMessage(Component.literal("你还没有伙伴，请先使用 /mf companion create。"));
            return 0;
        }
        return sendCommandMessage(state, player, new ChatSessionService.PrivateConversation(
                state.worldId, player.getUUID(), entry.identity().companionId()), entry, message);
    }

    private static int sendCommandMessage(
            RuntimeState state,
            ServerPlayer player,
            ChatSessionService.ConversationRef conversation,
            CompanionData.Entry entry,
            String message
    ) {
        if (!state.chatEnabled || state.sessions == null) {
            player.sendSystemMessage(Component.literal("聊天功能当前已关闭：" + state.disabledReason + "。"));
            return 0;
        }
        if (!companionAvailable(state.server, entry)) {
            player.sendSystemMessage(Component.literal(entry.identity().name() + " 当前暂离或离线，请先召唤伙伴。"));
            return 0;
        }
        if (state.isMemoryMutationPending(scope(state, entry))) {
            player.sendSystemMessage(Component.literal("记忆正在安全保存，请稍后再发送。"));
            return 0;
        }
        ChatSessionService.SubmitStatus status = submit(state, conversation, player, message);
        if (status == ChatSessionService.SubmitStatus.ACCEPTED
                || status == ChatSessionService.SubmitStatus.QUEUED) {
            echoToMembers(state, conversation, player, message);
            if (status == ChatSessionService.SubmitStatus.QUEUED) {
                player.sendSystemMessage(Component.literal("伙伴正在回复，消息已按顺序排队。"));
            }
            return 1;
        }
        player.sendSystemMessage(Component.literal(submitMessage(status)));
        return 0;
    }

    private static ChatSessionService.SubmitStatus submit(
            RuntimeState state,
            ChatSessionService.ConversationRef conversation,
            ServerPlayer player,
            String message
    ) {
        if (conversation instanceof ChatSessionService.PrivateConversation privateConversation) {
            return state.sessions.submit(privateConversation, player.getUUID(), message);
        }
        return state.sessions.submit((ChatSessionService.SharedConversation) conversation, player.getUUID(), message);
    }

    private static void echoToMembers(
            RuntimeState state,
            ChatSessionService.ConversationRef conversation,
            ServerPlayer sender,
            String message
    ) {
        String line = sender.getGameProfile().getName() + "：" + message;
        for (UUID recipientId : state.sessions.members(conversation)) {
            ServerPlayer recipient = state.server.getPlayerList().getPlayer(recipientId);
            if (recipient != null && state.sessions.isMember(conversation, recipientId)) {
                recipient.sendSystemMessage(Component.literal(line));
            }
        }
    }

    private static int cancelCommand(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        RuntimeState state = state(player.server);
        int cancelled = state == null || state.sessions == null ? 0 : state.sessions.cancelPlayerWork(player.getUUID());
        player.sendSystemMessage(Component.literal(cancelled == 0
                ? "没有可取消的聊天请求。"
                : "已取消 " + cancelled + " 条聊天请求。"));
        return cancelled == 0 ? 0 : 1;
    }

    private static int clearChatCommand(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        RuntimeState state = state(player.server);
        CompanionData.Entry entry = CompanionRuntime.data(player.server).find(player.getUUID()).orElse(null);
        if (state == null || entry == null || state.worldId == null || state.memory == null) {
            player.sendSystemMessage(Component.literal("没有可清空的伙伴聊天。"));
            return 0;
        }
        MemoryScope scope = scope(state, entry);
        if (!state.beginMemoryMutation(scope)) {
            player.sendSystemMessage(Component.literal("记忆操作正在处理中，请稍后再试。"));
            return 0;
        }
        boolean accepted = state.memory.clearConversationContext(scope, (cleared, failure) -> state.executeMain(() -> {
            state.endMemoryMutation(scope);
            if (failure != null) {
                LOGGER.warn("Chat context clear failed ({})", failure.getClass().getSimpleName());
                sendIfOnline(state, player, "聊天已清空；短期记忆未能保存清理结果，请重试。", true);
            } else {
                sendIfOnline(state, player, "聊天记录与短期上下文已清空；长期记忆和称呼偏好已保留。", true);
            }
        }));
        if (!accepted) {
            state.endMemoryMutation(scope);
            player.sendSystemMessage(Component.literal("记忆队列繁忙，请稍后再试。"));
            return 0;
        }
        state.cancelOwnerWork(entry);
        if (state.sessions != null) {
            state.sessions.clear(privateConversation(state, entry), player.getUUID());
        }
        player.sendSystemMessage(Component.literal("正在清空聊天记录与短期上下文……"));
        return 1;
    }

    private static int statusCommand(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ChatStatus status = status(player);
        player.sendSystemMessage(Component.literal("聊天 " + (status.enabled() ? "已启用" : "已关闭")
                + " / 全局功能 " + (status.globalEnabled() ? "已启用" : "已关闭")
                + " / Provider " + status.providerId()
                + " / World UUID " + status.worldId()
                + " / 前缀 " + status.prefix()
                + " / 伙伴 " + (status.companionName().isEmpty() ? "未创建" : status.companionName())
                + " / 在线 " + status.companionOnline()
                + " / 会话 " + status.sessionCount()
                + " / 在途 " + status.inFlightCount()
                + " / 排队 " + status.queuedCount()
                + " / 共享成员 " + status.sharedMemberCount()
                + " / 记忆代次 " + status.memoryGeneration()
                + " / 长期记忆 " + status.memoryCount()
                + " / 记忆已加载 " + status.memoryLoaded()
                + " / 称呼 " + (status.preferredAddress().isBlank() ? "默认" : status.preferredAddress())
                + " / 主动问候 " + status.proactiveEnabled()));
        if (!status.enabled() && !status.disabledReason().isBlank()) {
            player.sendSystemMessage(Component.literal("关闭原因：" + status.disabledReason()));
        }
        return 1;
    }

    private static int createSharedCommand(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        RuntimeState state = state(player.server);
        CompanionData.Entry entry = CompanionRuntime.data(player.server).find(player.getUUID()).orElse(null);
        if (state == null || state.sessions == null || !state.chatEnabled) {
            player.sendSystemMessage(Component.literal("聊天功能当前已关闭。"));
            return 0;
        }
        if (entry == null || !companionAvailable(player.server, entry)) {
            player.sendSystemMessage(Component.literal("需要先创建并召唤自己的伙伴，才能建立共享会话。"));
            return 0;
        }
        ChatSessionService.ShareResult result = state.sessions.createShared(privateConversation(state, entry), player.getUUID());
        if (result.conversation() == null) {
            player.sendSystemMessage(Component.literal(shareMessage(result.status())));
            return 0;
        }
        player.sendSystemMessage(Component.literal("共享会话已创建，编号 " + result.conversation().sessionId()
                + "。使用 /mf chat shared invite <在线玩家> 邀请成员。"));
        return 1;
    }

    private static int sendSharedCommand(
            CommandSourceStack source,
            String sessionText,
            String message
    ) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        RuntimeState state = state(player.server);
        UUID sessionId = parseUuid(sessionText);
        if (state == null || state.sessions == null || !state.chatEnabled
                || state.worldId == null || sessionId == null) {
            player.sendSystemMessage(Component.literal("共享会话编号无效，或聊天功能当前已关闭。"));
            return 0;
        }
        ChatSessionService.SharedConversation shared = state.sessions
                .findShared(state.worldId, sessionId).orElse(null);
        if (shared == null) {
            player.sendSystemMessage(Component.literal("没有找到这个共享会话。"));
            return 0;
        }
        if (!state.sessions.isMember(shared, player.getUUID())) {
            player.sendSystemMessage(Component.literal("你的玩家 UUID 尚未加入这个共享会话。"));
            return 0;
        }
        CompanionData.Entry entry = CompanionRuntime.data(player.server).find(shared.ownerId()).orElse(null);
        if (entry == null || !entry.identity().companionId().equals(shared.companionId())
                || !companionAvailable(player.server, entry)) {
            player.sendSystemMessage(Component.literal("共享会话中的伙伴当前暂离或离线。"));
            return 0;
        }
        ChatSessionService.SubmitStatus status = state.sessions.submit(shared, player.getUUID(), message);
        if (status == ChatSessionService.SubmitStatus.ACCEPTED
                || status == ChatSessionService.SubmitStatus.QUEUED) {
            echoToMembers(state, shared, player, message);
            if (status == ChatSessionService.SubmitStatus.QUEUED) {
                player.sendSystemMessage(Component.literal("伙伴正在回复，共享消息已按顺序排队。"));
            }
            return 1;
        }
        player.sendSystemMessage(Component.literal(submitMessage(status)));
        return 0;
    }

    private static int inviteCommand(CommandSourceStack source, String playerName) throws CommandSyntaxException {
        ServerPlayer owner = source.getPlayerOrException();
        RuntimeState state = state(owner.server);
        CompanionData.Entry entry = CompanionRuntime.data(owner.server).find(owner.getUUID()).orElse(null);
        if (state == null || state.sessions == null || entry == null) {
            owner.sendSystemMessage(Component.literal("没有可邀请的共享会话；先使用 /mf chat shared create。"));
            return 0;
        }
        ChatSessionService.PrivateConversation privateConversation = privateConversation(state, entry);
        ChatSessionService.SharedConversation shared = state.sessions.ownedShared(privateConversation).orElse(null);
        ServerPlayer target = owner.server.getPlayerList().getPlayerByName(playerName);
        if (shared == null || target == null || target == owner) {
            owner.sendSystemMessage(Component.literal("共享会话不存在，或目标玩家当前不在线。"));
            return 0;
        }
        ChatSessionService.ShareStatus result = state.sessions.invite(shared, owner.getUUID(), target.getUUID());
        if (result != ChatSessionService.ShareStatus.INVITED
                && result != ChatSessionService.ShareStatus.EXISTING_INVITATION) {
            owner.sendSystemMessage(Component.literal(shareMessage(result)));
            return 0;
        }
        target.sendSystemMessage(Component.literal(owner.getGameProfile().getName() + " 邀请你加入伙伴共享会话。"
                + "接受请执行 /mf chat shared join " + shared.sessionId()));
        owner.sendSystemMessage(Component.literal("已向 " + target.getGameProfile().getName()
                + " 发送邀请；对方接受前不能访问共享会话。"));
        return 1;
    }

    private static int joinSharedCommand(CommandSourceStack source, String sessionText) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        RuntimeState state = state(player.server);
        UUID sessionId = parseUuid(sessionText);
        if (state == null || state.sessions == null || state.worldId == null || sessionId == null) {
            player.sendSystemMessage(Component.literal("共享会话编号无效或聊天功能未启动。"));
            return 0;
        }
        ChatSessionService.ShareResult result = state.sessions.joinShared(state.worldId, sessionId, player.getUUID());
        player.sendSystemMessage(Component.literal(shareMessage(result.status())));
        return result.status() == ChatSessionService.ShareStatus.JOINED
                || result.status() == ChatSessionService.ShareStatus.ALREADY_MEMBER ? 1 : 0;
    }

    private static int leaveSharedCommand(CommandSourceStack source, String sessionText) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        RuntimeState state = state(player.server);
        UUID sessionId = parseUuid(sessionText);
        if (state == null || state.sessions == null || state.worldId == null || sessionId == null) {
            player.sendSystemMessage(Component.literal("共享会话编号无效或聊天功能未启动。"));
            return 0;
        }
        ChatSessionService.ShareStatus result = state.sessions.leaveShared(state.worldId, sessionId, player.getUUID());
        player.sendSystemMessage(Component.literal(shareMessage(result)));
        return result == ChatSessionService.ShareStatus.LEFT ? 1 : 0;
    }

    private static int listMemoryCommand(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        RuntimeState state = state(player.server);
        CompanionData.Entry entry = CompanionRuntime.data(player.server).find(player.getUUID()).orElse(null);
        if (state == null || entry == null || state.worldId == null || state.memory == null) {
            player.sendSystemMessage(Component.literal("你还没有可查看的伙伴记忆。"));
            return 0;
        }
        MemoryScope scope = scope(state, entry);
        state.readMemory(player, scope, CompanionMemoryStore::listMemories, entries -> {
            if (entries.isEmpty()) {
                player.sendSystemMessage(Component.literal("长期记忆为空。"));
                return;
            }
            player.sendSystemMessage(Component.literal("伙伴长期记忆（" + entries.size() + " 条）："));
            for (MemoryEntry memory : entries) {
                player.sendSystemMessage(Component.literal(memory.id() + " / " + memory.kind() + " / " + memory.text()));
            }
        });
        return 1;
    }

    private static int rememberCommand(CommandSourceStack source, String text) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        RuntimeState state = state(player.server);
        CompanionData.Entry entry = CompanionRuntime.data(player.server).find(player.getUUID()).orElse(null);
        if (state == null || entry == null || state.worldId == null || state.memory == null) {
            player.sendSystemMessage(Component.literal("你还没有可保存记忆的伙伴。"));
            return 0;
        }
        state.mutateMemory(player, entry, store -> store.rememberPreference(text, Instant.now()),
                ignored -> "已保存这条明确偏好。", false);
        return 1;
    }

    private static int deleteMemoryCommand(CommandSourceStack source, String idText) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        RuntimeState state = state(player.server);
        CompanionData.Entry entry = CompanionRuntime.data(player.server).find(player.getUUID()).orElse(null);
        UUID id = parseUuid(idText);
        if (state == null || entry == null || state.worldId == null || state.memory == null || id == null) {
            player.sendSystemMessage(Component.literal("记忆编号无效，或你还没有可管理记忆的伙伴。"));
            return 0;
        }
        state.mutateMemory(player, entry, store -> store.deleteMemory(id), removed -> removed
                ? "已删除该记忆；旧上下文和在途回复已失效。"
                : "未找到该记忆；短期上下文和旧请求仍已清理。", true);
        return 1;
    }

    private static int clearMemoryCommand(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        RuntimeState state = state(player.server);
        CompanionData.Entry entry = CompanionRuntime.data(player.server).find(player.getUUID()).orElse(null);
        if (state == null || entry == null || state.worldId == null || state.memory == null) {
            player.sendSystemMessage(Component.literal("你还没有可清空记忆的伙伴。"));
            return 0;
        }
        state.mutateMemory(player, entry, store -> {
            store.clear();
            return true;
        }, ignored -> "已清空长期/短期记忆、人设称呼和主动事件冷却。", true);
        return 1;
    }

    private static int addressCommand(CommandSourceStack source, String address) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        RuntimeState state = state(player.server);
        CompanionData.Entry entry = CompanionRuntime.data(player.server).find(player.getUUID()).orElse(null);
        if (state == null || entry == null || state.worldId == null || state.memory == null) {
            player.sendSystemMessage(Component.literal("你还没有可设置称呼的伙伴。"));
            return 0;
        }
        state.mutateMemory(player, entry, store -> {
            PersonaSettings current = store.persona();
            String preferredAddress = address.equalsIgnoreCase("reset") ? "" : address;
            store.setPersona(new PersonaSettings(current.tone(), preferredAddress));
            return store.persona();
        }, persona -> persona.preferredAddress().isBlank()
                ? "已恢复默认称呼。"
                : "伙伴以后会称呼你为“" + persona.preferredAddress() + "”。", false);
        return 1;
    }

    private static int toneCommand(CommandSourceStack source, String tone) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        RuntimeState state = state(player.server);
        CompanionData.Entry entry = CompanionRuntime.data(player.server).find(player.getUUID()).orElse(null);
        if (state == null || entry == null || state.worldId == null || state.memory == null) {
            player.sendSystemMessage(Component.literal("你还没有可设置口吻的伙伴。"));
            return 0;
        }
        state.mutateMemory(player, entry, store -> {
            PersonaSettings current = store.persona();
            String selectedTone = tone.equalsIgnoreCase("reset")
                    ? PersonaSettings.defaults().tone() : tone;
            store.setPersona(new PersonaSettings(selectedTone, current.preferredAddress()));
            return store.persona();
        }, persona -> "伙伴口吻已设为“" + persona.tone() + "”。", false);
        return 1;
    }

    private static int proactiveCommand(CommandSourceStack source, boolean enabled) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        RuntimeState state = state(player.server);
        CompanionData.Entry entry = CompanionRuntime.data(player.server).find(player.getUUID()).orElse(null);
        if (state == null || entry == null || state.worldId == null || state.memory == null) {
            player.sendSystemMessage(Component.literal("你还没有可设置主动问候的伙伴。"));
            return 0;
        }
        state.mutateMemory(player, entry, store -> {
            store.setProactiveEnabled(enabled);
            return enabled;
        }, value -> "真实游戏事件主动问候" + (value ? "已开启。" : "已关闭。"), false);
        return 1;
    }

    private static void offerLoginGreeting(RuntimeState state, ServerPlayer player, LoginToken token) {
        if (!isCurrent(state) || !state.isCurrentLogin(player.getUUID(), token)
                || !state.chatEnabled || state.worldId == null
                || state.server.getPlayerList().getPlayer(player.getUUID()) != player) {
            return;
        }
        if (state.firstMeetingClaimedSinceLogin.contains(player.getUUID())) {
            return;
        }
        CompanionData.Entry entry = CompanionRuntime.data(state.server).find(player.getUUID()).orElse(null);
        if (entry == null || !companionAvailable(state.server, entry)) {
            return;
        }
        claimProactiveEvent(state, player, entry, null, token);
    }

    private static void claimProactiveEvent(
            RuntimeState state,
            ServerPlayer owner,
            CompanionData.Entry entry,
            ProactiveEvent requestedEvent,
            LoginToken loginToken
    ) {
        MemoryScope scope = scope(state, entry);
        boolean accepted = state.memory.submit(scope, store -> {
            if (!state.isCurrentLogin(owner.getUUID(), loginToken)) {
                return null;
            }
            Instant now = Instant.now();
            ProactiveEvent event = requestedEvent;
            if (event == null) {
                if (state.firstMeetingClaimedSinceLogin.contains(owner.getUUID())) {
                    return null;
                }
                if (store.tryClaimProactiveEvent(ProactiveEvent.FIRST_MEETING, now)) {
                    event = ProactiveEvent.FIRST_MEETING;
                } else if (store.tryClaimProactiveEvent(ProactiveEvent.REJOIN, now)) {
                    event = ProactiveEvent.REJOIN;
                }
            } else if (!store.tryClaimProactiveEvent(event, now)) {
                event = null;
            }
            if (event == null || !state.isCurrentLogin(owner.getUUID(), loginToken)) {
                return null;
            }
            if (event == ProactiveEvent.FIRST_MEETING
                    && !state.markFirstMeetingClaimed(owner.getUUID(), loginToken)) {
                return null;
            }
            String eventText = eventDescription(event);
            try {
                store.recordVerifiedEvent(eventText, now);
            } catch (IllegalStateException full) {
                // The event remains verified and cooldown-protected even when the fact list is full.
            }
            return new ProactivePrompt(event, "已确认的游戏事件：" + eventText
                    + "。请简短、自然地向主人问候，并明确你是 AI 伙伴。", now);
        }, (prompt, failure) -> state.executeMain(() -> {
            if (!isCurrent(state) || !state.isCurrentLogin(owner.getUUID(), loginToken)
                    || failure != null || prompt == null
                    || state.server.getPlayerList().getPlayer(owner.getUUID()) != owner
                    || !companionAvailable(state.server, entry)) {
                if (failure != null) {
                    LOGGER.warn("Proactive memory event failed ({})", failure.getClass().getSimpleName());
                }
                return;
            }
            ChatSessionService.PrivateConversation privateConversation = privateConversation(state, entry);
            ChatSessionService.SubmitStatus result = state.sessions.submitProactive(
                    privateConversation, owner.getUUID(), prompt.text());
            if (result == ChatSessionService.SubmitStatus.QUEUE_FULL
                    || result == ChatSessionService.SubmitStatus.SESSION_LIMIT) {
                owner.sendSystemMessage(Component.literal("伙伴目前正在忙，刚才的主动问候未能排队。"));
            }
        }));
        if (!accepted) {
            LOGGER.warn("Proactive memory event was skipped because the bounded memory queue is full");
        }
    }

    private static String eventDescription(ProactiveEvent event) {
        return switch (event) {
            case FIRST_MEETING -> "主人第一次加入并与伙伴相遇";
            case REJOIN -> "主人重新加入服务器";
            case INJURED -> "伙伴刚刚受到伤害";
        };
    }

    private static List<Target> findAddressTargets(RuntimeState state, ServerPlayer player, String name) {
        List<Target> matches = new ArrayList<>();
        CompanionData data = CompanionRuntime.data(state.server);
        CompanionData.Entry owned = data.find(player.getUUID()).orElse(null);
        Target ownedTarget = null;
        if (owned != null && sameName(owned.identity().name(), name)) {
            ownedTarget = new Target(owned,
                    new ChatSessionService.PrivateConversation(state.worldId, player.getUUID(), owned.identity().companionId()));
        }
        if (state.sessions != null && state.worldId != null) {
            for (ChatSessionService.SharedConversation shared : state.sessions.joinedShared(
                    state.worldId, player.getUUID())) {
                CompanionData.Entry entry = data.find(shared.ownerId()).orElse(null);
                if (entry != null && entry.identity().companionId().equals(shared.companionId())
                        && sameName(entry.identity().name(), name)) {
                    matches.add(new Target(entry, shared));
                }
            }
        }
        return ChatAddressRouter.preferOwnedPrivate(ownedTarget, matches);
    }

    private static boolean sameName(String first, String second) {
        return first.equalsIgnoreCase(second);
    }

    private static boolean companionAvailable(MinecraftServer server, CompanionData.Entry entry) {
        return entry.active() && server.getPlayerList().getPlayer(entry.identity().companionId()) != null;
    }

    private static ChatSessionService.Limits chatLimits(ModConfig config) {
        ChatSessionService.Limits defaults = ChatSessionService.Limits.defaults();
        int totalMessages = config.modelMaxContextMessages();
        int contextMessages = Math.min(4, Math.max(0, totalMessages - 2));
        int historyMessages = Math.max(1, totalMessages - contextMessages - 1);
        int contextChars = Math.min(2_000, config.modelMaxContextCharacters() / 4);
        int historyChars = Math.max(1, config.modelMaxContextCharacters() - contextChars - 512);
        int messageChars = Math.min(defaults.maxMessageChars(), historyChars);
        return defaults.withModelBudgets(historyMessages, historyChars, contextMessages,
                contextChars, messageChars);
    }

    private static String submitMessage(ChatSessionService.SubmitStatus status) {
        return switch (status) {
            case MESSAGE_TOO_LONG -> "消息太长，请控制在 512 个字符以内。";
            case EMPTY_MESSAGE -> "消息不能为空。";
            case RATE_LIMITED -> "你发送得太快了，请稍等再试。";
            case QUEUE_FULL -> "伙伴当前忙碌，队列已满，请稍后再试。";
            case SESSION_LIMIT -> "服务器当前聊天会话已满，请稍后再试。";
            case NOT_MEMBER -> "你不属于这个共享会话。";
            case UNAVAILABLE -> "聊天服务正在关闭或不可用。";
            case ACCEPTED, QUEUED -> "消息已发送。";
        };
    }

    private static String shareMessage(ChatSessionService.ShareStatus status) {
        return switch (status) {
            case CREATED -> "共享会话已创建。";
            case EXISTING -> "共享会话已存在。";
            case INVITED -> "邀请已发送。";
            case EXISTING_INVITATION -> "该玩家已有待处理邀请。";
            case ALREADY_MEMBER -> "该玩家已经加入会话。";
            case JOINED -> "你已加入共享会话。";
            case LEFT -> "你已离开共享会话。";
            case NOT_FOUND -> "没有找到这个共享会话。";
            case NOT_OWNER -> "只有伙伴主人可以管理共享会话。";
            case NOT_INVITED -> "你的玩家 UUID 没有收到该会话邀请。";
            case NOT_MEMBER -> "你还没有加入这个共享会话。";
            case OWNER_CANNOT_LEAVE -> "主人不能离开自己管理的共享会话。";
            case MEMBER_LIMIT -> "共享会话成员已满。";
            case INVITATION_LIMIT -> "共享会话的待处理邀请已满。";
            case SESSION_LIMIT -> "服务器当前会话已满，请稍后再试。";
            case UNAVAILABLE -> "共享会话服务不可用。";
        };
    }

    private static int memoryCommandError(ServerPlayer player, Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause != cause.getCause()) {
            cause = cause.getCause();
        }
        LOGGER.warn("Companion memory operation failed ({})", cause.getClass().getSimpleName());
        player.sendSystemMessage(Component.literal("记忆操作失败，未确认保存；请稍后重试。"));
        return 0;
    }

    private static void sendIfOnline(RuntimeState state, ServerPlayer player, String message, boolean currentStateRequired) {
        if ((!currentStateRequired || isCurrent(state))
                && state.server.getPlayerList().getPlayer(player.getUUID()) == player) {
            player.sendSystemMessage(Component.literal(message));
        }
    }

    private static MemoryScope scope(RuntimeState state, CompanionData.Entry entry) {
        if (state.worldId == null) {
            throw new IllegalStateException("Persistent world UUID is unavailable.");
        }
        CompanionIdentity identity = entry.identity();
        return new MemoryScope(state.worldId, identity.ownerId(), identity.companionId());
    }

    private static ChatSessionService.PrivateConversation privateConversation(
            RuntimeState state,
            CompanionData.Entry entry
    ) {
        return new ChatSessionService.PrivateConversation(
                state.worldId, entry.identity().ownerId(), entry.identity().companionId());
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static RuntimeState state(MinecraftServer server) {
        synchronized (STATES) {
            return STATES.get(server);
        }
    }

    private static boolean isCurrent(RuntimeState state) {
        synchronized (STATES) {
            return STATES.get(state.server) == state;
        }
    }

    private static String loadPrefix(Path configPath) {
        if (Files.notExists(configPath)) {
            return DEFAULT_PREFIX;
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            properties.load(reader);
            String prefix = properties.getProperty("chat.prefix", DEFAULT_PREFIX).strip();
            if (!prefix.isEmpty() && prefix.length() <= 8 && prefix.chars().noneMatch(Character::isWhitespace)
                    && !prefix.contains("/")) {
                return prefix;
            }
        } catch (IOException ignored) {
            // ConfigLoader has already reported that the main configuration could not be read.
        }
        return DEFAULT_PREFIX;
    }

    @FunctionalInterface
    public interface ProviderFactory {
        /** Construct a provider without opening a network connection. */
        ModelProvider create(ModConfig config);
    }

    public record ChatStatus(
            boolean enabled,
            boolean globalEnabled,
            String disabledReason,
            UUID worldId,
            String providerId,
            String prefix,
            UUID companionId,
            String companionName,
            boolean companionActive,
            boolean companionOnline,
            int sessionCount,
            int inFlightCount,
            int queuedCount,
            int sharedSessionCount,
            int sharedMemberCount,
            boolean memoryLoaded,
            long memoryGeneration,
            int memoryCount,
            String preferredAddress,
            String tone,
            boolean proactiveEnabled
    ) {
        private static ChatStatus notStarted() {
            return new ChatStatus(false, false, "服务端聊天运行时尚未启动", null, "disabled", DEFAULT_PREFIX,
                    null, "", false, false, 0, 0, 0, 0, 0,
                    false, 0, 0, "", "", false);
        }
    }

    private record Target(CompanionData.Entry entry, ChatSessionService.ConversationRef conversation) {
    }

    private record ProactivePrompt(ProactiveEvent event, String text, Instant occurredAt) {
    }

    private static final class LoginToken {
        private volatile boolean active = true;
    }

    private static final class RuntimeState {
        private final MinecraftServer server;
        private final ModConfig config;
        private final boolean globalEnabled;
        private final UUID worldId;
        private final String prefix;
        private final boolean chatEnabled;
        private final String disabledReason;
        private final ModelProvider provider;
        private final ExecutorService memoryExecutor;
        private final ExecutorService modelExecutor;
        private final ChatMemoryCoordinator memory;
        private final ChatSessionService sessions;
        private final java.util.Set<MemoryScope> memoryMutations = ConcurrentHashMap.newKeySet();
        private final java.util.Set<UUID> firstMeetingClaimedSinceLogin = ConcurrentHashMap.newKeySet();
        private final ConcurrentHashMap<UUID, LoginToken> loginTokens = new ConcurrentHashMap<>();

        private RuntimeState(
                MinecraftServer server,
                ModConfig config,
                boolean globalEnabled,
                UUID worldId,
                String prefix,
                Path memoryRoot,
                boolean chatEnabled,
                String disabledReason,
                ModelProvider provider
        ) {
            this.server = server;
            this.config = config;
            this.globalEnabled = globalEnabled;
            this.worldId = worldId;
            this.prefix = prefix;
            this.chatEnabled = chatEnabled;
            this.disabledReason = disabledReason;
            this.provider = provider;
            this.memoryExecutor = new ThreadPoolExecutor(
                    2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(128),
                    namedThreads("mf-memory"), new ThreadPoolExecutor.AbortPolicy());
            this.memory = new ChatMemoryCoordinator(memoryRoot, memoryExecutor);
            if (chatEnabled && provider != null) {
                this.modelExecutor = new ThreadPoolExecutor(
                        2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(64),
                        namedThreads("mf-chat"), new ThreadPoolExecutor.AbortPolicy());
                this.sessions = new ChatSessionService(
                        provider,
                        modelExecutor,
                        task -> executeMain(task),
                        new ChatSessionService.Listener() {
                            @Override
                            public void onReply(ChatSessionService.Reply reply) {
                                deliver(reply);
                            }

                            @Override
                            public void onFailure(ChatSessionService.Failure failure) {
                                reportFailure(failure);
                            }

                            @Override
                            public void onHookFailure(
                                    ChatSessionService.ConversationRef conversation,
                                    ChatSessionService.FailureReason reason
                            ) {
                                LOGGER.warn("Chat context hook failed ({})", reason);
                            }
                        },
                        new MemoryHooks(this),
                        chatLimits(config)
                );
            } else {
                this.modelExecutor = null;
                this.sessions = null;
            }
        }

        private boolean beginMemoryMutation(MemoryScope scope) {
            return memoryMutations.add(scope);
        }

        private void endMemoryMutation(MemoryScope scope) {
            memoryMutations.remove(scope);
        }

        private boolean isMemoryMutationPending(MemoryScope scope) {
            return memoryMutations.contains(scope);
        }

        private boolean isCurrentLogin(UUID ownerId, LoginToken token) {
            return token != null && token.active && loginTokens.get(ownerId) == token;
        }

        private boolean markFirstMeetingClaimed(UUID ownerId, LoginToken token) {
            synchronized (token) {
                if (!isCurrentLogin(ownerId, token)) {
                    return false;
                }
                firstMeetingClaimedSinceLogin.add(ownerId);
                return true;
            }
        }

        private void cancelOwnerWork(CompanionData.Entry entry) {
            if (sessions != null) {
                sessions.cancelOwnerWork(entry.identity().ownerId(), entry.identity().companionId());
            }
        }

        private <T> void readMemory(ServerPlayer player, MemoryScope scope,
                                    ChatMemoryCoordinator.StoreOperation<T> operation,
                                    java.util.function.Consumer<T> success) {
            boolean queued = memory.submit(scope, operation, (value, failure) -> executeMain(() -> {
                if (!isCurrent(this) || server.getPlayerList().getPlayer(player.getUUID()) != player) {
                    return;
                }
                if (failure != null) {
                    memoryCommandError(player, failure);
                } else {
                    success.accept(value);
                }
            }));
            if (!queued) {
                player.sendSystemMessage(Component.literal("记忆后台队列繁忙，请稍后再试。"));
            }
        }

        private <T> void mutateMemory(ServerPlayer player, CompanionData.Entry entry,
                                      ChatMemoryCoordinator.StoreOperation<T> operation,
                                      Function<T, String> successMessage,
                                      boolean cancelAndClearChat) {
            MemoryScope scope = scope(this, entry);
            if (memory == null) {
                player.sendSystemMessage(Component.literal("聊天记忆服务未启动。"));
                return;
            }
            if (!beginMemoryMutation(scope)) {
                player.sendSystemMessage(Component.literal("该伙伴的记忆操作正在处理中。"));
                return;
            }
            cancelOwnerWork(entry);
            if (cancelAndClearChat && sessions != null) {
                sessions.clear(privateConversation(this, entry), player.getUUID());
            }
            boolean queued = memory.submit(scope, operation, (value, failure) -> executeMain(() -> {
                endMemoryMutation(scope);
                if (!isCurrent(this) || server.getPlayerList().getPlayer(player.getUUID()) != player) {
                    return;
                }
                if (failure != null) {
                    memoryCommandError(player, failure);
                } else {
                    player.sendSystemMessage(Component.literal(successMessage.apply(value)));
                }
            }));
            if (!queued) {
                endMemoryMutation(scope);
                player.sendSystemMessage(Component.literal("记忆后台队列繁忙，请稍后再试。"));
                return;
            }
            player.sendSystemMessage(Component.literal("正在安全保存伙伴记忆……"));
        }

        private void deliver(ChatSessionService.Reply reply) {
            if (!isCurrent(this) || worldId == null || !worldId.equals(reply.conversation().worldId())) {
                return;
            }
            CompanionData.Entry entry = CompanionRuntime.data(server)
                    .find(reply.conversation().ownerId()).orElse(null);
            if (entry == null || !entry.active()
                    || !entry.identity().companionId().equals(reply.conversation().companionId())
                    || !companionAvailable(server, entry)) {
                return;
            }
            for (UUID recipientId : reply.recipients()) {
                if (!sessions.isMember(reply.conversation(), recipientId)) {
                    continue;
                }
                ServerPlayer recipient = server.getPlayerList().getPlayer(recipientId);
                if (recipient != null && recipient.server == server) {
                    recipient.sendSystemMessage(Component.literal(entry.identity().name() + "：" + reply.text()));
                }
            }
        }

        private void reportFailure(ChatSessionService.Failure failure) {
            if (!isCurrent(this) || !sessions.isMember(failure.conversation(), failure.speakerId())) {
                return;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(failure.speakerId());
            if (player == null) {
                return;
            }
            String message = switch (failure.reason()) {
                case TIMEOUT -> "伙伴思考超时，请稍后再试。";
                case BUSY -> "聊天服务当前繁忙，请稍后再试。";
                case EMPTY_RESPONSE -> "伙伴暂时没有生成可用回复，请重试。";
                case CONTEXT_HOOK -> "伙伴上下文暂时不可用，请稍后重试。";
                case PROVIDER -> "模型暂时无法回复，请检查模型配置。";
            };
            player.sendSystemMessage(Component.literal(message));
        }

        private void executeMain(Runnable task) {
            try {
                server.execute(task);
            } catch (RuntimeException ignored) {
                // The server may already be stopping; late work has no user-facing destination.
            }
        }

        private void close() {
            if (sessions != null) {
                sessions.close();
            }
            if (modelExecutor != null) {
                modelExecutor.shutdownNow();
            }
            memory.shutdownWhenIdle();
            if (provider != null) {
                Thread closer = new Thread(() -> {
                    try {
                        provider.close();
                    } catch (Exception failure) {
                        LOGGER.warn("Model provider close failed ({})", failure.getClass().getSimpleName());
                    }
                }, "mf-provider-close");
                closer.setDaemon(true);
                closer.start();
            }
        }
    }

    private static final class MemoryHooks implements ChatSessionService.ContextHooks {
        private final RuntimeState state;

        private MemoryHooks(RuntimeState state) {
            this.state = state;
        }

        @Override
        public ChatSessionService.HookContext privateContext(
                ChatSessionService.PrivateConversation conversation,
                UUID speakerId,
                String currentMessage,
                List<ChatMessage> recentHistory
        ) {
            MemoryScope scope = new MemoryScope(conversation.worldId(), conversation.ownerId(), conversation.companionId());
            RequestContext request = state.memory.call(scope, CompanionMemoryStore::beginRequest);
            List<ChatMessage> context = new ArrayList<>();
            context.add(new ChatMessage(ChatRole.SYSTEM, request.persona().systemInstructions()));
            for (MemoryEntry memory : request.memories()) {
                context.add(new ChatMessage(ChatRole.SYSTEM, "已保存的" + memory.kind() + "：" + memory.text()));
            }
            for (ContextMessage message : request.shortTermContext()) {
                context.add(new ChatMessage(
                        message.speaker() == ContextSpeaker.USER ? ChatRole.USER : ChatRole.ASSISTANT,
                        message.text()));
            }
            return new ChatSessionService.HookContext(context, request);
        }

        @Override
        public void afterPrivateReply(
                ChatSessionService.PrivateConversation conversation,
                UUID speakerId,
                String userMessage,
                String assistantReply,
                ChatSessionService.HookContext context,
                boolean proactive
        ) {
            if (context == null || !(context.state() instanceof RequestContext request)) {
                return;
            }
            MemoryScope scope = new MemoryScope(conversation.worldId(), conversation.ownerId(), conversation.companionId());
            String shortTermUserText = proactive ? "[已验证游戏事件] " + userMessage : userMessage;
            boolean queued = state.memory.submit(scope, store -> store.commitResponse(
                    request.token(), shortTermUserText, assistantReply, Instant.now()), (accepted, failure) -> {
                if (failure != null) {
                    LOGGER.warn("Companion memory response commit failed ({})", failure.getClass().getSimpleName());
                }
            });
            if (!queued) {
                LOGGER.warn("Companion memory response commit skipped because its bounded queue is full");
            }
        }
    }

    private static ThreadFactory namedThreads(String prefix) {
        AtomicInteger index = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + "-" + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

}
