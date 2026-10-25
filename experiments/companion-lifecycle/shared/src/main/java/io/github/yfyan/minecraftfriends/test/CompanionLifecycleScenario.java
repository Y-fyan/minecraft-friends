package io.github.yfyan.minecraftfriends.test;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import io.github.yfyan.minecraftfriends.companion.CompanionData;
import io.github.yfyan.minecraftfriends.companion.CompanionRuntime;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.UUID;

public final class CompanionLifecycleScenario {
    private final GameTestHelper helper;
    private final ServerLevel level;
    private final MinecraftServer server;
    private final UUID ownerId = UUID.randomUUID();
    private final UUID secondOwnerId = UUID.randomUUID();
    private final GameProfile ownerProfile = new GameProfile(ownerId, "MF_TestOwner");
    private ServerPlayer owner;
    private UUID companionId;
    private ServerLevel deathLevel;
    private int phase;
    private boolean done;

    private CompanionLifecycleScenario(GameTestHelper helper) {
        this.helper = helper;
        this.level = helper.getLevel();
        this.server = level.getServer();
    }

    public static void run(GameTestHelper helper) {
        CompanionLifecycleScenario scenario = new CompanionLifecycleScenario(helper);
        helper.onEachTick(scenario::tick);
    }

    private void tick() {
        if (done) {
            return;
        }
        try {
            switch (phase) {
                case 0 -> {
                    for (ServerLevel candidate : server.getAllLevels()) {
                        for (ItemEntity item : candidate.getEntitiesOfClass(ItemEntity.class,
                                new AABB(-30_000_000.0, -2048.0, -30_000_000.0,
                                        30_000_000.0, 4096.0, 30_000_000.0))) {
                            if (item.getItem().is(Items.OAK_LOG)) {
                                item.discard();
                            }
                        }
                    }
                    server.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY)
                            .set(false, server);
                    owner = addPlayer(ownerProfile);
                    CompanionData.Entry created = CompanionRuntime.createFor(owner, "MF_Ada");
                    companionId = created.identity().companionId();
                    check(!companionId.equals(ownerId), "separate_uuid");
                    check(liveCompanion() != null, "created_live");
                    CompanionData.Entry repeated = CompanionRuntime.createFor(owner, "MF_ChangedName");
                    check(repeated.identity().companionId().equals(companionId)
                            && repeated.identity().name().equals("MF_Ada"), "create_idempotent");
                    liveCompanion().getInventory().add(new ItemStack(Items.OAK_LOG, 4));
                    liveCompanion().setHealth(15.0F);
                    liveCompanion().teleportTo(server.getLevel(Level.NETHER), 8.5, 70.0, 8.5, 0.0F, 0.0F);
                    phase = 1;
                }
                case 1 -> {
                    CompanionRuntime.dismissFor(owner);
                    check(liveCompanion() == null, "dismiss_removes_entity");
                    CompanionRuntime.summonFor(owner);
                    ServerPlayer restored = liveCompanion();
                    check(restored != null && restored.getUUID().equals(companionId), "summon_stable_id");
                    check(restored.getInventory().countItem(Items.OAK_LOG) == 4, "dismiss_preserves_inventory");
                    check(restored.getHealth() == 15.0F, "dismiss_preserves_health");
                    check(restored.serverLevel().dimension() == Level.NETHER, "dismiss_preserves_dimension");
                    CompanionRuntime.summonFor(owner);
                    check(server.getPlayerList().getPlayers().stream()
                            .filter(player -> player.getUUID().equals(companionId)).count() == 1, "summon_idempotent");
                    phase = 2;
                }
                case 2 -> {
                    CompanionRuntime.onOwnerLogout(owner);
                    server.getPlayerList().remove(owner);
                    owner.connection.disconnect(new DisconnectionDetails(net.minecraft.network.chat.Component.literal("test disconnect")));
                    check(liveCompanion() == null, "owner_disconnect_unloads_companion");
                    owner = addPlayer(ownerProfile);
                    CompanionRuntime.onOwnerLogin(owner);
                    check(liveCompanion() != null && liveCompanion().getUUID().equals(companionId), "owner_rejoin_restores_companion");
                    phase = 3;
                }
                case 3 -> {
                    killAndCheckStart(liveCompanion());
                }
                case 4 -> {
                    ServerPlayer respawned = liveCompanion();
                    if (respawned == null || !respawned.isAlive()) {
                        return;
                    }
                    check(respawned.getUUID().equals(companionId), "respawn_stable_id");
                    check(respawned.getInventory().countItem(Items.OAK_LOG) == 0, "death_does_not_duplicate_inventory");
                    int droppedLogs = 0;
                    java.util.Set<UUID> countedDrops = new java.util.HashSet<>();
                    for (ServerLevel candidate : server.getAllLevels()) {
                        for (ItemEntity item : candidate.getEntitiesOfClass(ItemEntity.class,
                                new AABB(-30_000_000.0, -2048.0, -30_000_000.0,
                                        30_000_000.0, 4096.0, 30_000_000.0))) {
                            if (item.getItem().is(Items.OAK_LOG) && countedDrops.add(item.getUUID())) {
                                droppedLogs += item.getItem().getCount();
                            }
                        }
                    }
                    emit("death_dropped_log_count", Integer.toString(droppedLogs));
                    check(droppedLogs == 0 || droppedLogs == 4, "death_drop_count_has_no_duplication");
                    CompanionData store = CompanionRuntime.data(server);
                    CompanionData.Entry stored = store.find(ownerId).orElseThrow();
                    check(stored.identity().ownerId().equals(ownerId), "owner_persisted");
                    ServerPlayer secondOwner = addPlayer(new GameProfile(secondOwnerId, "MF_TestTwo"));
                    CompanionData.Entry second = CompanionRuntime.createFor(secondOwner, "MF_Bob");
                    check(!second.identity().companionId().equals(companionId), "second_owner_distinct_companion");
                    check(store.find(secondOwnerId).orElseThrow().identity().ownerId().equals(secondOwnerId), "owners_isolated");
                    CompanionRuntime.onServerStopping(server);
                    check(liveCompanion() == null, "shutdown_unloads_companions");
                    CompanionData.Entry afterUnload = store.find(ownerId).orElseThrow();
                    check(afterUnload.active(), "shutdown_preserves_rejoin_state");
                    emit("result", "PASS");
                    done = true;
                    helper.succeed();
                }
                default -> throw new IllegalStateException("Unexpected lifecycle test phase " + phase);
            }
        } catch (Throwable failure) {
            done = true;
            emit("result", "FAIL: " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            helper.fail(failure.toString());
        }
    }

    private void killAndCheckStart(ServerPlayer companion) {
        deathLevel = companion.serverLevel();
        deathLevel.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY)
                .set(false, server);
        emit("inventory_before_death", Integer.toString(companion.getInventory().countItem(Items.OAK_LOG)));
        companion.hurt(deathLevel.damageSources().genericKill(), Float.MAX_VALUE);
        check(!companion.isAlive(), "death_occurs");
        phase = 4;
    }

    private ServerPlayer addPlayer(GameProfile profile) {
        ServerPlayer player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        server.getPlayerList().placeNewPlayer(new TestConnection(), player,
                CommonListenerCookie.createInitial(profile, false));
        player.setGameMode(GameType.SURVIVAL);
        return player;
    }

    private ServerPlayer liveCompanion() {
        return companionId == null ? null : server.getPlayerList().getPlayer(companionId);
    }

    private void check(boolean condition, String name) {
        if (!condition) {
            throw new IllegalStateException(name);
        }
        emit(name, "PASS");
    }

    private void emit(String name, String value) {
        JsonObject record = new JsonObject();
        record.addProperty("loader", System.getProperty("mf.lifecycle.loader", "unknown"));
        record.addProperty("server_class", server.getClass().getName());
        record.addProperty("dedicated", server.isDedicatedServer());
        record.addProperty("check", name);
        record.addProperty("value", value);
        System.out.println("MF_LIFECYCLE " + record);
    }
}
