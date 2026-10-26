package io.github.yfyan.minecraftfriends.acceptance;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import io.github.yfyan.minecraftfriends.companion.CompanionData;
import io.github.yfyan.minecraftfriends.companion.CompanionRuntime;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.phys.AABB;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;

/** Test-mod observer of production JAR behavior; never part of the distributed mod. */
public final class AcceptanceScenario {
    private static final Map<MinecraftServer, AcceptanceScenario> RUNS = new IdentityHashMap<>();
    private static final Map<UUID, UUID> REJOIN_IDS = new HashMap<>();
    private static final Map<UUID, ChatRestore> CHAT_RESTORE = new HashMap<>();
    private static final boolean LIFECYCLE_ONLY = Boolean.getBoolean("mf.acceptance.lifecycleOnly");
    private UUID expectedNonce;
    private UUID acknowledgedNonce;
    private long generationBeforeClear;
    private boolean awaitingRejoin;
    private boolean logoutObserved;

    private record ChatRestore(UUID worldId) { }
    private record ChatSnapshot(boolean enabled, String providerId, UUID worldId, UUID companionId,
                                boolean memoryLoaded, long generation, int memoryCount,
                                String address, boolean proactive, int inFlight, int queued) { }

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("mfacceptance")
                .then(Commands.literal("ack").then(Commands.argument("nonce", StringArgumentType.word())
                        .executes(context -> {
                            ServerPlayer sender = context.getSource().getPlayerOrException();
                            AcceptanceScenario run = RUNS.get(sender.server);
                            if (run == null || run.owner == null || run.expectedNonce == null
                                    || !sender.getUUID().equals(run.owner.getUUID())) { return 0; }
                            String nonce = StringArgumentType.getString(context, "nonce");
                            if (!run.expectedNonce.toString().equals(nonce)) { return 0; }
                            run.acknowledgedNonce = run.expectedNonce;
                            return 1;
                        }))));
    }
    private final MinecraftServer server;
    private ServerPlayer owner;
    private UUID companionId;
    private ServerLevel deathLevel;
    private AABB deathArea;
    private int age;
    private int phase;
    private int phaseStart;
    private boolean finished;

    private AcceptanceScenario(MinecraftServer server) { this.server = server; }
    public static void tick(MinecraftServer server) {
        AcceptanceScenario run = RUNS.computeIfAbsent(server, AcceptanceScenario::new);
        if (run.finished && run.awaitingRejoin) {
            ServerPlayer current = server.getPlayerList().getPlayer(run.owner.getUUID());
            if (current == null) { run.logoutObserved = true; }
            if (current != null && run.logoutObserved) {
                run = new AcceptanceScenario(server);
                RUNS.put(server, run);
            }
        }
        run.step();
    }
    public static void remove(MinecraftServer server) { RUNS.remove(server); }

    private void step() {
        if (finished) { return; }
        age++;
        if (owner == null) {
            owner = server.getPlayerList().getPlayers().stream()
                    .filter(p -> CompanionRuntime.data(server).findByCompanion(p.getUUID()).isEmpty())
                    .findFirst().orElse(null);
            if (owner == null) { return; }
            owner.setInvulnerable(true);
            emit("server", server.getClass().getName());
            String runtimeKind = server instanceof net.minecraft.server.dedicated.DedicatedServer
                    ? "DEDICATED" : server.getClass().getName().equals("net.minecraft.client.server.IntegratedServer")
                    ? "INTEGRATED" : "OTHER";
            emit("server_kind", runtimeKind);
            phaseStart = age;
        }
        try {
            if (age - phaseStart > 1_200) { throw new IllegalStateException("phase timeout " + phase); }
            CompanionData.Entry entry = CompanionRuntime.data(server).find(owner.getUUID()).orElse(null);
            switch (phase) {
                case 0 -> {
                    if (age - phaseStart < 10) { return; }
                    UUID previous = REJOIN_IDS.get(owner.getUUID());
                    if (previous != null) {
                        check(entry != null && previous.equals(entry.identity().companionId()), "world_reopen_identity");
                        companionId = previous;
                        ServerPlayer restored = live();
                        check(restored != null && restored.getInventory().countItem(Items.OAK_LOG) == 4,
                                "world_reopen_inventory");
                        check(restored.getHealth() == 12.0F, "world_reopen_health");
                        if (LIFECYCLE_ONLY) { done("RESTORE_PASS"); }
                        else { phase = 20; phaseStart = age; }
                        return;
                    }
                    check(entry == null, "new_world_has_no_companion");
                    command("mf companion create MF_M1Bot");
                    next();
                }
                case 1 -> {
                    if (entry == null) { return; }
                    companionId = entry.identity().companionId();
                    check(!companionId.equals(owner.getUUID()), "separate_identity");
                    check(live() != null, "client_command_created_companion");
                    live().getInventory().clearContent();
                    live().getInventory().add(new ItemStack(Items.OAK_LOG, 4));
                    live().setHealth(15.0F);
                    command("mf companion dismiss");
                    next();
                }
                case 2 -> {
                    if (live() != null) { return; }
                    check(!CompanionRuntime.data(server).find(owner.getUUID()).orElseThrow().active(),
                            "client_command_dismissed");
                    command("mf companion summon");
                    next();
                }
                case 3 -> {
                    if (live() == null) { return; }
                    check(live().getInventory().countItem(Items.OAK_LOG) == 4, "summon_inventory");
                    check(live().getHealth() == 15.0F, "summon_health");
                    beginDeath(false);
                    next();
                }
                case 4 -> {
                    if (age - phaseStart < 5 || live() == null || !live().isAlive()) { return; }
                    check(live().getInventory().countItem(Items.OAK_LOG) == 0, "death_clears_inventory");
                    check(drops() == 4, "death_drops_exactly_four");
                    for (ItemEntity item : deathLevel.getEntitiesOfClass(ItemEntity.class, deathArea)) {
                        if (item.getItem().is(Items.OAK_LOG)) { item.discard(); }
                    }
                    live().getInventory().add(new ItemStack(Items.OAK_LOG, 4));
                    beginDeath(true);
                    next();
                }
                case 5 -> {
                    if (age - phaseStart < 5 || live() == null || !live().isAlive()) { return; }
                    check(live().getInventory().countItem(Items.OAK_LOG) == 4, "keep_inventory_retained");
                    check(drops() == 0, "keep_inventory_has_no_drops");
                    live().setHealth(12.0F);
                    REJOIN_IDS.put(owner.getUUID(), companionId);
                    if (LIFECYCLE_ONLY) { done("LIFECYCLE_PASS"); }
                    else { next(); }
                }
                case 6 -> {
                    ChatSnapshot status = chat();
                    if (!status.memoryLoaded() || !idle(status)) { return; }
                    check(status.enabled() && "chat-completions".equals(status.providerId()), "chat_provider_configured");
                    check(status.worldId() != null && companionId.equals(status.companionId()), "chat_scope_identity");
                    command("mf memory proactive false");
                    next();
                }
                case 7 -> {
                    if (chat().proactive()) { return; }
                    command("mf memory address 验收主人");
                    next();
                }
                case 8 -> {
                    if (!"验收主人".equals(chat().address())) { return; }
                    check(true, "private_address_saved");
                    command("mf memory remember MF_PRIVATE_FACT");
                    next();
                }
                case 9 -> {
                    ChatSnapshot status = chat();
                    if (status.memoryCount() < 1 || !idle(status)) { return; }
                    check(true, "private_memory_saved");
                    request("PRIVATE", false);
                    next();
                }
                case 10 -> {
                    if (!acknowledged() || !idle(chat())) { return; }
                    check(true, "private_chinese_reply_ack");
                    request("PREFIX", true);
                    next();
                }
                case 11 -> {
                    ChatSnapshot status = chat();
                    if (!acknowledged() || !idle(status)) { return; }
                    check(true, "prefix_chinese_reply_ack");
                    CHAT_RESTORE.put(owner.getUUID(), new ChatRestore(status.worldId()));
                    awaitingRejoin = true;
                    done("CHAT_REOPEN_READY");
                }
                case 20 -> {
                    ChatSnapshot status = chat();
                    if (!status.memoryLoaded() || !idle(status)) { return; }
                    ChatRestore previous = CHAT_RESTORE.get(owner.getUUID());
                    check(previous != null && previous.worldId().equals(status.worldId()), "world_reopen_chat_uuid");
                    check("验收主人".equals(status.address()), "world_reopen_address");
                    check(status.memoryCount() >= 1, "world_reopen_memory");
                    request("REJOIN", false);
                    next();
                }
                case 21 -> {
                    if (!acknowledged() || !idle(chat())) { return; }
                    check(true, "rejoin_chinese_reply_ack");
                    generationBeforeClear = chat().generation();
                    command("mf memory clear");
                    next();
                }
                case 22 -> {
                    ChatSnapshot status = chat();
                    if (!status.memoryLoaded() || status.generation() <= generationBeforeClear) { return; }
                    check(status.address().isBlank(), "memory_clear_address");
                    check(status.memoryCount() == 0, "memory_clear_facts");
                    check(true, "memory_clear_generation");
                    if (!idle(status)) { return; }
                    request("AFTER_CLEAR", false);
                    next();
                }
                case 23 -> {
                    if (!acknowledged() || !idle(chat())) { return; }
                    check(true, "after_clear_chinese_reply_ack");
                    done("CHAT_MEMORY_PASS");
                }
                default -> throw new IllegalStateException("Unexpected acceptance phase");
            }
        } catch (Throwable failure) {
            finished = true;
            emit("result", "FAIL " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            owner.sendSystemMessage(Component.literal("MF_ACCEPTANCE FAIL " + failure.getClass().getSimpleName()));
        }
    }
    private ChatSnapshot chat() throws ReflectiveOperationException {
        // Reflective public API bridge keeps --lifecycle-only usable with older lifecycle JARs.
        Class<?> runtime = Class.forName("io.github.yfyan.minecraftfriends.chat.ChatRuntime");
        Object status = runtime.getMethod("status", MinecraftServer.class, UUID.class)
                .invoke(null, server, owner.getUUID());
        Class<?> type = status.getClass();
        return new ChatSnapshot((boolean) type.getMethod("enabled").invoke(status),
                (String) type.getMethod("providerId").invoke(status),
                (UUID) type.getMethod("worldId").invoke(status),
                (UUID) type.getMethod("companionId").invoke(status),
                (boolean) type.getMethod("memoryLoaded").invoke(status),
                (long) type.getMethod("memoryGeneration").invoke(status),
                (int) type.getMethod("memoryCount").invoke(status),
                (String) type.getMethod("preferredAddress").invoke(status),
                (boolean) type.getMethod("proactiveEnabled").invoke(status),
                (int) type.getMethod("inFlightCount").invoke(status),
                (int) type.getMethod("queuedCount").invoke(status));
    }
    private boolean idle(ChatSnapshot status) { return status.inFlight() == 0 && status.queued() == 0; }
    private boolean acknowledged() { return expectedNonce != null && expectedNonce.equals(acknowledgedNonce); }
    private void request(String label, boolean prefix) {
        expectedNonce = UUID.randomUUID();
        acknowledgedNonce = null;
        owner.sendSystemMessage(Component.literal("MF_ACCEPTANCE EXPECT " + expectedNonce));
        String text = "MF_ACCEPT_" + label + ":" + expectedNonce
                + (("PRIVATE".equals(label) || "REJOIN".equals(label)) ? " MF_PRIVATE_HISTORY" : "")
                + " 请用中文回应。";
        if (prefix) { owner.sendSystemMessage(Component.literal("MF_ACCEPTANCE CHAT @MF_M1Bot " + text)); }
        else { command("mf chat " + text); }
    }
    private void beginDeath(boolean keepInventory) {
        ServerPlayer companion = live();
        deathLevel = companion.serverLevel();
        companion.teleportTo(owner.getX() + 24.0, owner.getY(), owner.getZ() + 24.0);
        deathArea = companion.getBoundingBox().inflate(8.0);
        deathLevel.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY).set(keepInventory, server);
        companion.hurt(deathLevel.damageSources().genericKill(), Float.MAX_VALUE);
        check(!companion.isAlive(), "death_started_" + keepInventory);
    }
    private int drops() {
        return deathLevel.getEntitiesOfClass(ItemEntity.class, deathArea).stream()
                .filter(e -> e.getItem().is(Items.OAK_LOG)).mapToInt(e -> e.getItem().getCount()).sum();
    }
    private ServerPlayer live() { return companionId == null ? null : server.getPlayerList().getPlayer(companionId); }
    private void command(String text) { owner.sendSystemMessage(Component.literal("MF_ACCEPTANCE COMMAND " + text)); }
    private void next() { phase++; phaseStart = age; }
    private void check(boolean condition, String name) {
        if (!condition) { throw new IllegalStateException(name); }
        emit(name, "PASS");
    }
    private void done(String value) {
        finished = true;
        emit("result", value);
        owner.sendSystemMessage(Component.literal("MF_ACCEPTANCE " + value));
    }
    private void emit(String check, String value) {
        System.out.println("MF_ACCEPTANCE_SERVER " + server.getClass().getName() + " " + check + " " + value);
    }
}
