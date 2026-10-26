package io.github.yfyan.minecraftfriends.acceptance;

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
        RUNS.computeIfAbsent(server, AcceptanceScenario::new).step();
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
                        done("RESTORE_PASS");
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
                    done("LIFECYCLE_PASS");
                }
                default -> throw new IllegalStateException("Unexpected acceptance phase");
            }
        } catch (Throwable failure) {
            finished = true;
            emit("result", "FAIL " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            owner.sendSystemMessage(Component.literal("MF_ACCEPTANCE FAIL " + failure.getClass().getSimpleName()));
        }
    }
    private void beginDeath(boolean keepInventory) {
        ServerPlayer companion = live();
        deathLevel = companion.serverLevel();
        companion.teleportTo(owner.getX() + 8.0, owner.getY(), owner.getZ() + 8.0);
        deathArea = companion.getBoundingBox().inflate(32.0);
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
