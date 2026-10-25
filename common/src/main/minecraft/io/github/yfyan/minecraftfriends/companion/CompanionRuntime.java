package io.github.yfyan.minecraftfriends.companion;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;

import java.util.UUID;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class CompanionRuntime {
    private CompanionRuntime() {
    }

    public static CompanionData data(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(CompanionData.FACTORY, CompanionData.FILE_NAME);
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
    }

    public static void onOwnerLogin(ServerPlayer owner) {
        data(owner.server).find(owner.getUUID())
                .filter(CompanionData.Entry::active)
                .ifPresent(entry -> summon(owner.server, owner, entry));
    }

    public static CompanionData.Entry createFor(ServerPlayer owner, String requestedName) {
        CompanionData store = data(owner.server);
        CompanionData.Entry existing = store.find(owner.getUUID()).orElse(null);
        if (existing != null) {
            store.setActive(owner.getUUID(), true);
            summon(owner.server, owner, existing);
            return store.find(owner.getUUID()).orElseThrow();
        }
        String name = requestedName == null ? defaultName(owner) : requestedName;
        CompanionData.Entry created = store.create(new CompanionIdentity(
                UUID.randomUUID(), owner.getUUID(), name));
        summon(owner.server, owner, created);
        return created;
    }

    public static void summonFor(ServerPlayer owner) {
        CompanionData store = data(owner.server);
        CompanionData.Entry entry = store.find(owner.getUUID())
                .orElseThrow(() -> new IllegalStateException("No companion is registered for this owner."));
        store.setActive(owner.getUUID(), true);
        summon(owner.server, owner, entry);
    }

    public static void dismissFor(ServerPlayer owner) {
        CompanionData store = data(owner.server);
        CompanionData.Entry entry = store.find(owner.getUUID())
                .orElseThrow(() -> new IllegalStateException("No companion is registered for this owner."));
        store.setActive(owner.getUUID(), false);
        removeLive(owner.server, entry.identity());
    }

    public static void onOwnerLogout(ServerPlayer owner) {
        data(owner.server).find(owner.getUUID()).ifPresent(entry -> removeLive(owner.server, entry.identity()));
    }

    public static void onServerStopping(MinecraftServer server) {
        for (CompanionData.Entry entry : data(server).entries()) {
            removeLive(server, entry.identity());
        }
    }

    public static void onCompanionDeath(ServerPlayer dead) {
        CompanionData.Entry entry = data(dead.server).findByCompanion(dead.getUUID()).orElse(null);
        if (entry == null || !entry.active()) {
            return;
        }
        dead.server.execute(() -> {
            if (dead.server.getPlayerList().getPlayer(dead.getUUID()) == dead && !dead.isAlive()) {
                ServerPlayer respawned = dead.server.getPlayerList()
                        .respawn(dead, false, Entity.RemovalReason.KILLED);
                respawned.setGameMode(GameType.SURVIVAL);
            }
        });
    }

    private static int create(CommandSourceStack source, String requestedName) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer owner = source.getPlayerOrException();
        CompanionData.Entry entry;
        try {
            entry = createFor(owner, requestedName);
        } catch (IllegalArgumentException invalidName) {
            throw new SimpleCommandExceptionType(Component.literal(invalidName.getMessage())).create();
        }
        owner.sendSystemMessage(Component.literal("伙伴 " + entry.identity().name() + " 已创建并加入。"));
        return 1;
    }

    private static String defaultName(ServerPlayer owner) {
        String prefix = "MF_";
        String name = owner.getGameProfile().getName().replaceAll("[^A-Za-z0-9_]", "_");
        return prefix + name.substring(0, Math.min(name.length(), 16 - prefix.length()));
    }

    private static int summon(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer owner = source.getPlayerOrException();
        CompanionData.Entry entry = data(owner.server).find(owner.getUUID())
                .orElseThrow(() -> new SimpleCommandExceptionType(
                        Component.literal("你还没有伙伴。使用 /mf companion create 创建。")).create());
        summonFor(owner);
        owner.sendSystemMessage(Component.literal("伙伴 " + entry.identity().name() + " 已召唤。"));
        return 1;
    }

    private static int dismiss(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer owner = source.getPlayerOrException();
        CompanionData.Entry entry = data(owner.server).find(owner.getUUID())
                .orElseThrow(() -> new SimpleCommandExceptionType(
                        Component.literal("你还没有伙伴。使用 /mf companion create 创建。")).create());
        dismissFor(owner);
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

    private static void summon(MinecraftServer server, ServerPlayer owner, CompanionData.Entry entry) {
        UUID companionId = entry.identity().companionId();
        if (server.getPlayerList().getPlayer(companionId) != null) {
            return;
        }
        ServerLevel level = owner != null ? owner.serverLevel() : server.overworld();
        GameProfile profile = new GameProfile(companionId, entry.identity().name());
        ServerPlayer companion = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        CompanionConnection connection = new CompanionConnection();
        server.getPlayerList().placeNewPlayer(connection, companion,
                CommonListenerCookie.createInitial(profile, false));
        companion.setGameMode(GameType.SURVIVAL);
        if (companion.serverLevel().dimension() == Level.OVERWORLD && owner != null
                && owner.serverLevel().dimension() != Level.OVERWORLD) {
            companion.teleportTo(owner.serverLevel(), owner.getX() + 1.5, owner.getY(), owner.getZ() + 1.5,
                    owner.getYRot(), owner.getXRot());
        }
    }

    private static void removeLive(MinecraftServer server, CompanionIdentity identity) {
        ServerPlayer companion = server.getPlayerList().getPlayer(identity.companionId());
        if (companion == null) {
            return;
        }
        server.getPlayerList().remove(companion);
        companion.connection.disconnect(new net.minecraft.network.DisconnectionDetails(
                Component.literal("Companion temporarily unloaded")));
    }
}
