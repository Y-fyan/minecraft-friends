package io.github.yfyan.minecraftfriends.companion;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import io.github.yfyan.minecraftfriends.chat.ChatRuntime;
import io.github.yfyan.minecraftfriends.action.ActionRuntime;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class CompanionRuntime {
    private static volatile boolean featureEnabled = true;
    private static final Logger LOGGER = LoggerFactory.getLogger("minecraft_friends");
    private static final Map<MinecraftServer, Map<UUID, PendingDeath>> PENDING_DEATHS = new WeakHashMap<>();
    private static final Map<MinecraftServer, Set<ServerPlayer>> COMPANION_PLAYERS = new WeakHashMap<>();
    private static final Map<MinecraftServer, Set<UUID>> RESPAWNING_COMPANIONS = new WeakHashMap<>();
    private static final Set<MinecraftServer> VALIDATED_DATA_STORES = Collections.newSetFromMap(new WeakHashMap<>());

    private CompanionRuntime() {
    }

    /** Applies the game/server process configuration before registering gameplay callbacks. */
    public static void configure(boolean enabled) {
        featureEnabled = enabled;
    }

    public static boolean isEnabled() {
        return featureEnabled;
    }

    private static void requireEnabled() {
        if (!featureEnabled) {
            throw new IllegalStateException("Minecraft Friends 已在配置中关闭。");
        }
    }

    public static CompanionData data(MinecraftServer server) {
        DimensionDataStorage storage = server.overworld().getDataStorage();
        if (!VALIDATED_DATA_STORES.contains(server)) {
            validateExistingDataFile(server, storage);
            CompanionData loaded = storage.computeIfAbsent(CompanionData.FACTORY, CompanionData.FILE_NAME);
            VALIDATED_DATA_STORES.add(server);
            return loaded;
        }
        return storage.computeIfAbsent(CompanionData.FACTORY, CompanionData.FILE_NAME);
    }

    private static void validateExistingDataFile(MinecraftServer server, DimensionDataStorage storage) {
        Path file = server.getWorldPath(LevelResource.ROOT)
                .resolve("data")
                .resolve(CompanionData.FILE_NAME + ".dat");
        try {
            if (Files.notExists(file)) {
                return;
            }
            CompoundTag root = storage.readTagFromDisk(CompanionData.FILE_NAME, null,
                    SharedConstants.getCurrentVersion().getDataVersion().getVersion());
            if (!root.contains("data", Tag.TAG_COMPOUND)) {
                throw new IllegalStateException("Saved data has no companion payload.");
            }
            CompanionData.load(root.getCompound("data"), server.registryAccess());
        } catch (IOException | RuntimeException invalidStore) {
            throw new IllegalStateException("Existing companion data is unreadable or invalid; refusing to replace it.");
        }
    }

    public static void registerCommands(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(literal("mf")
                .then(literal("companion")
                        .then(literal("create").executes(context -> create(context.getSource(), null))
                                .then(argument("name", StringArgumentType.word())
                                        .executes(context -> create(context.getSource(),
                                                StringArgumentType.getString(context, "name")))))
                        .then(literal("summon").executes(context -> summon(context.getSource())))
                        .then(literal("dismiss").executes(context -> dismiss(context.getSource())))
                        .then(literal("status").executes(context -> status(context.getSource())))));
        ActionRuntime.registerCommands(dispatcher);
    }

    public static void onServerStarted(MinecraftServer server) {
        ActionRuntime.onServerStarted(server);
    }

    public static void onOwnerLogin(ServerPlayer owner) {
        if (!featureEnabled || isCompanion(owner)) {
            return;
        }
        try {
            CompanionData store = data(owner.server);
            if (isRespawning(owner.server, owner.getUUID())) {
                return;
            }
            if (store.findByCompanion(owner.getUUID()).isPresent()) {
                LOGGER.warn("Companion restoration was refused because its UUID is occupied; owner login continues.");
                owner.sendSystemMessage(Component.literal(
                        "伙伴无法自动恢复：伙伴身份与当前玩家冲突；当前登录不受影响，请检查伙伴记录。"));
                return;
            }
            store.find(owner.getUUID()).filter(CompanionData.Entry::active)
                    .ifPresent(entry -> summon(owner.server, owner, entry, false));
        } catch (RuntimeException refusal) {
            LOGGER.warn("Companion restoration was refused; owner login continues.");
            owner.sendSystemMessage(Component.literal(
                    "伙伴无法自动恢复：数据或名称与当前玩家冲突；当前登录不受影响，请检查伙伴记录。"));
        }
    }

    public static CompanionData.Entry createFor(ServerPlayer owner, String requestedName) {
        requireEnabled();
        CompanionData store = data(owner.server);
        CompanionData.Entry existing = store.find(owner.getUUID()).orElse(null);
        if (existing != null) {
            store.setActive(owner.getUUID(), true);
            summon(owner.server, owner, existing, false);
            ChatRuntime.onCompanionAvailable(owner);
            return store.find(owner.getUUID()).orElseThrow();
        }
        String name = requestedName == null ? defaultName(owner, store) : requestedName;
        if (onlinePlayerByName(owner.server, name) != null) {
            throw new IllegalArgumentException("That companion name is already in use.");
        }
        CompanionData.Entry created = store.create(new CompanionIdentity(
                uniqueCompanionId(owner.server, store, owner.getUUID()), owner.getUUID(), name));
        summon(owner.server, owner, created, true);
        ChatRuntime.onCompanionAvailable(owner);
        return created;
    }

    public static void summonFor(ServerPlayer owner) {
        requireEnabled();
        CompanionData store = data(owner.server);
        CompanionData.Entry entry = store.find(owner.getUUID())
                .orElseThrow(() -> new IllegalStateException("No companion is registered for this owner."));
        store.setActive(owner.getUUID(), true);
        summon(owner.server, owner, entry, false);
        ChatRuntime.onCompanionAvailable(owner);
    }

    public static void dismissFor(ServerPlayer owner) {
        CompanionData store = data(owner.server);
        CompanionData.Entry entry = store.find(owner.getUUID())
                .orElseThrow(() -> new IllegalStateException("No companion is registered for this owner."));
        ChatRuntime.onCompanionRemoved(owner.server, owner.getUUID());
        if (!removeLive(owner.server, entry.identity())) {
            throw new IllegalStateException("Refused to remove a player that is not a local companion.");
        }
        store.setActive(owner.getUUID(), false);
    }

    public static void onOwnerLogout(ServerPlayer owner) {
        ActionRuntime.onOwnerLogout(owner);
        if (isCompanion(owner)) {
            if (owner.server.getPlayerList().getPlayer(owner.getUUID()) != owner) {
                forgetCompanion(owner);
            }
            return;
        }
        ChatRuntime.onLogout(owner);
        try {
            CompanionData store = data(owner.server);
            if (isRespawning(owner.server, owner.getUUID())
                    || store.findByCompanion(owner.getUUID()).isPresent()) {
                return;
            }
            store.find(owner.getUUID()).ifPresent(entry -> removeLive(owner.server, entry.identity()));
        } catch (RuntimeException refusal) {
            LOGGER.warn("Companion cleanup was refused; owner logout continues.");
        }
    }

    public static void onServerStopping(MinecraftServer server) {
        ActionRuntime.onServerStopping(server);
        ChatRuntime.stop(server);
        try {
            for (CompanionData.Entry entry : data(server).entries()) {
                removeLive(server, entry.identity());
            }
        } catch (RuntimeException refusal) {
            LOGGER.warn("Companion cleanup was refused; server shutdown continues.");
        } finally {
            PENDING_DEATHS.remove(server);
            COMPANION_PLAYERS.remove(server);
            RESPAWNING_COMPANIONS.remove(server);
            VALIDATED_DATA_STORES.remove(server);
        }
    }

    /** Records a death for validation after the vanilla death/drop flow has completed. */
    public static void onCompanionDeath(ServerPlayer dead) {
        if (!isCompanion(dead)) {
            return;
        }
        ActionRuntime.onCompanionDeath(dead);
        CompanionData.Entry entry = data(dead.server).findByCompanion(dead.getUUID()).orElse(null);
        if (entry == null || !entry.active()) {
            return;
        }
        ChatRuntime.onCompanionRemoved(dead.server, entry.identity().ownerId());
        PENDING_DEATHS.computeIfAbsent(dead.server, ignored -> new HashMap<>())
                .put(dead.getUUID(), new PendingDeath(dead, false));
    }

    /** Processes registered deaths at END_SERVER_TICK, after vanilla drops and event cancellation. */
    public static void onServerTick(MinecraftServer server) {
        ActionRuntime.onTick(server);
        Map<UUID, PendingDeath> pending = PENDING_DEATHS.get(server);
        if (pending == null || pending.isEmpty()) {
            return;
        }

        for (PendingDeath death : new ArrayList<>(pending.values())) {
            if (!death.waitedOneTick()) {
                pending.put(death.player().getUUID(), new PendingDeath(death.player(), true));
                continue;
            }
            pending.remove(death.player().getUUID());
            ServerPlayer dead = death.player();
            if (server.getPlayerList().getPlayer(dead.getUUID()) != dead || dead.isAlive()
                    || !isCompanion(dead)) {
                forgetCompanion(dead);
                continue;
            }
            CompanionData.Entry entry = data(server).findByCompanion(dead.getUUID()).orElse(null);
            if (entry == null || !entry.active()) {
                forgetCompanion(dead);
                continue;
            }
            RESPAWNING_COMPANIONS.computeIfAbsent(server, ignored -> new HashSet<>()).add(dead.getUUID());
            ServerPlayer respawned;
            try {
                respawned = server.getPlayerList().respawn(dead, false, Entity.RemovalReason.KILLED);
            } finally {
                Set<UUID> respawning = RESPAWNING_COMPANIONS.get(server);
                if (respawning != null) {
                    respawning.remove(dead.getUUID());
                    if (respawning.isEmpty()) {
                        RESPAWNING_COMPANIONS.remove(server);
                    }
                }
            }
            forgetCompanion(dead);
            if (respawned != null) {
                markCompanion(respawned);
                respawned.setGameMode(GameType.SURVIVAL);
            }
        }
        if (pending.isEmpty()) {
            PENDING_DEATHS.remove(server);
        }
    }

    private static int create(CommandSourceStack source, String requestedName) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer owner = source.getPlayerOrException();
        CompanionData.Entry entry;
        try {
            entry = createFor(owner, requestedName);
        } catch (IllegalArgumentException | IllegalStateException invalidRequest) {
            throw new SimpleCommandExceptionType(Component.literal(invalidRequest.getMessage())).create();
        }
        owner.sendSystemMessage(Component.literal("伙伴 " + entry.identity().name() + " 已创建并加入。"));
        return 1;
    }

    private static String defaultName(ServerPlayer owner, CompanionData store) {
        String ownerName = owner.getGameProfile().getName().replaceAll("[^A-Za-z0-9_]", "_");
        if (ownerName.isBlank()) {
            ownerName = "Player";
        }
        String compactId = owner.getUUID().toString().replace("-", "");
        for (int suffixLength = 4; suffixLength <= 13; suffixLength++) {
            if (suffixLength == 13) {
                String candidate = "MF_" + compactId.substring(0, suffixLength);
                if (nameIsAvailable(owner.server, store, candidate)) {
                    return candidate;
                }
                continue;
            }
            int prefixLength = 16 - 3 - suffixLength - 1;
            String prefix = ownerName.substring(0, Math.min(ownerName.length(), prefixLength));
            String candidate = "MF_" + prefix + (prefix.isEmpty() ? "" : "_")
                    + compactId.substring(0, suffixLength);
            if (nameIsAvailable(owner.server, store, candidate)) {
                return candidate;
            }
        }
        for (int counter = 1; counter < 100_000; counter++) {
            String suffix = Integer.toString(counter, 36);
            String candidate = "MF_" + compactId.substring(0, 13 - suffix.length()) + suffix;
            if (nameIsAvailable(owner.server, store, candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not allocate a unique default companion name.");
    }

    private static boolean nameIsAvailable(MinecraftServer server, CompanionData store, String name) {
        return !store.hasName(name) && onlinePlayerByName(server, name) == null;
    }

    private static ServerPlayer onlinePlayerByName(MinecraftServer server, String name) {
        return server.getPlayerList().getPlayers().stream()
                .filter(player -> player.getGameProfile().getName().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }

    private static UUID uniqueCompanionId(MinecraftServer server, CompanionData store, UUID ownerId) {
        for (int attempt = 0; attempt < 16; attempt++) {
            UUID candidate = UUID.randomUUID();
            if (!candidate.equals(ownerId)
                    && store.find(candidate).isEmpty()
                    && store.findByCompanion(candidate).isEmpty()
                    && server.getPlayerList().getPlayer(candidate) == null) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not allocate a unique companion UUID.");
    }

    /** Returns whether this exact runtime player instance was created as a local companion. */
    public static boolean isCompanion(ServerPlayer player) {
        Set<ServerPlayer> marked = COMPANION_PLAYERS.get(player.server);
        return marked != null && marked.contains(player);
    }

    private static void markCompanion(ServerPlayer player) {
        COMPANION_PLAYERS.computeIfAbsent(player.server,
                ignored -> Collections.newSetFromMap(new IdentityHashMap<>())).add(player);
    }

    private static void forgetCompanion(ServerPlayer player) {
        Set<ServerPlayer> marked = COMPANION_PLAYERS.get(player.server);
        if (marked != null) {
            marked.remove(player);
            if (marked.isEmpty()) {
                COMPANION_PLAYERS.remove(player.server);
            }
        }
    }

    private static boolean isRespawning(MinecraftServer server, UUID playerId) {
        Set<UUID> respawning = RESPAWNING_COMPANIONS.get(server);
        return respawning != null && respawning.contains(playerId);
    }

    private static int summon(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer owner = source.getPlayerOrException();
        CompanionData.Entry entry = data(owner.server).find(owner.getUUID())
                .orElseThrow(() -> new SimpleCommandExceptionType(
                        Component.literal("你还没有伙伴。使用 /mf companion create 创建。")).create());
        try {
            summonFor(owner);
        } catch (IllegalStateException refusal) {
            throw new SimpleCommandExceptionType(Component.literal(refusal.getMessage())).create();
        }
        owner.sendSystemMessage(Component.literal("伙伴 " + entry.identity().name() + " 已召唤。"));
        return 1;
    }

    private static int dismiss(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer owner = source.getPlayerOrException();
        CompanionData.Entry entry = data(owner.server).find(owner.getUUID())
                .orElseThrow(() -> new SimpleCommandExceptionType(
                        Component.literal("你还没有伙伴。使用 /mf companion create 创建。")).create());
        try {
            dismissFor(owner);
        } catch (IllegalStateException refusal) {
            throw new SimpleCommandExceptionType(Component.literal(refusal.getMessage())).create();
        }
        owner.sendSystemMessage(Component.literal("伙伴 " + entry.identity().name() + " 已暂离；使用 /mf companion summon 重新加入。"));
        return 1;
    }

    private static int status(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer owner = source.getPlayerOrException();
        CompanionData.Entry entry = data(owner.server).find(owner.getUUID()).orElse(null);
        if (entry == null) {
            owner.sendSystemMessage(Component.literal("你还没有伙伴。使用 /mf companion create 创建。"));
            return 0;
        }
        boolean online = owner.server.getPlayerList().getPlayer(entry.identity().companionId()) != null;
        owner.sendSystemMessage(Component.literal("伙伴 " + entry.identity().name() + " / UUID "
                + entry.identity().companionId() + " / 归属 " + entry.identity().ownerId()
                + " / 状态 " + (online ? "已加入" : entry.active() ? "等待恢复" : "暂离")));
        return 1;
    }

    private static void summon(MinecraftServer server, ServerPlayer owner, CompanionData.Entry entry,
                               boolean initialSpawn) {
        UUID companionId = entry.identity().companionId();
        ServerPlayer existing = server.getPlayerList().getPlayer(companionId);
        if (existing != null) {
            if (!isCompanion(existing)) {
                throw new IllegalStateException("A non-companion player occupies the registered companion UUID.");
            }
            return;
        }
        ServerPlayer nameConflict = onlinePlayerByName(server, entry.identity().name());
        if (nameConflict != null && !nameConflict.getUUID().equals(companionId)) {
            throw new IllegalStateException("A player already uses the registered companion name.");
        }
        ServerLevel level = owner != null ? owner.serverLevel() : server.overworld();
        GameProfile profile = new GameProfile(companionId, entry.identity().name());
        ServerPlayer companion = new CompanionServerPlayer(server, level, profile, ClientInformation.createDefault());
        CompanionConnection connection = new CompanionConnection();
        markCompanion(companion);
        try {
            server.getPlayerList().placeNewPlayer(connection, companion,
                    CommonListenerCookie.createInitial(profile, false));
        } catch (RuntimeException failure) {
            forgetCompanion(companion);
            throw failure;
        }
        companion.setGameMode(GameType.SURVIVAL);
        if (initialSpawn && owner != null) {
            companion.teleportTo(owner.serverLevel(), owner.getX() + 1.5, owner.getY(), owner.getZ() + 1.5,
                    owner.getYRot(), owner.getXRot());
        }
    }

    private static boolean removeLive(MinecraftServer server, CompanionIdentity identity) {
        ServerPlayer companion = server.getPlayerList().getPlayer(identity.companionId());
        if (companion == null) {
            return true;
        }
        if (!isCompanion(companion)) {
            LOGGER.warn("Refusing to remove a player occupying a companion UUID; the player was left connected.");
            return false;
        }
        server.getPlayerList().remove(companion);
        companion.connection.disconnect(new net.minecraft.network.DisconnectionDetails(
                Component.literal("Companion temporarily unloaded")));
        forgetCompanion(companion);
        return true;
    }

    private record PendingDeath(ServerPlayer player, boolean waitedOneTick) {
    }
}
