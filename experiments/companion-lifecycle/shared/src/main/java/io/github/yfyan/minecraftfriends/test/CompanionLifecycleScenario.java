package io.github.yfyan.minecraftfriends.test;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.yfyan.minecraftfriends.companion.CompanionIdentity;
import io.github.yfyan.minecraftfriends.companion.CompanionData;
import io.github.yfyan.minecraftfriends.companion.CompanionRuntime;
import net.minecraft.SharedConstants;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

public final class CompanionLifecycleScenario {
    private final GameTestHelper helper;
    private final ServerLevel level;
    private final MinecraftServer server;
    private final Consumer<ServerPlayer> cancelNextDeath;
    private final UUID ownerId = UUID.randomUUID();
    private final UUID secondOwnerId = UUID.randomUUID();
    private final GameProfile ownerProfile = new GameProfile(ownerId, "MF_TestOwner");
    private final String primaryCompanionName = "MF_"
            + ownerId.toString().replace("-", "").substring(0, 12);
    private final Map<UUID, TestConnection> testConnections = new HashMap<>();
    private ServerPlayer owner;
    private ServerPlayer forgeCanceledPlayer;
    private UUID companionId;
    private int phase;
    private boolean done;
    private boolean deathWaitObserved;
    private String currentCheck = "setup";
    private String lastCommandError;

    private CompanionLifecycleScenario(GameTestHelper helper, Consumer<ServerPlayer> cancelNextDeath) {
        this.helper = helper;
        this.level = helper.getLevel();
        this.server = level.getServer();
        this.cancelNextDeath = cancelNextDeath;
    }

    public static void run(GameTestHelper helper) {
        run(helper, null);
    }

    public static void run(GameTestHelper helper, Consumer<ServerPlayer> cancelNextDeath) {
        CompanionLifecycleScenario scenario = new CompanionLifecycleScenario(helper, cancelNextDeath);
        helper.onEachTick(scenario::tick);
    }

    private void tick() {
        if (done) {
            return;
        }
        try {
            switch (phase) {
                case 0 -> {
                    assertPersistenceRoundTrip();
                    assertDamagedPersistenceRejected();
                    assertCorruptStoreFilePreserved();
                    discardOakLogDrops();
                    setKeepInventory(false);
                    assertVanillaPlayerDropControl();
                    owner = addPlayer(ownerProfile);
                    check(runCommand(owner, "mf companion create " + primaryCompanionName) == 1,
                            "registered_create_command");
                    CompanionData.Entry created = CompanionRuntime.data(server).find(ownerId).orElseThrow();
                    companionId = created.identity().companionId();
                    check(!companionId.equals(ownerId), "separate_uuid");
                    ServerPlayer companion = liveCompanion();
                    check(companion != null, "created_live");
                    check(companion.distanceToSqr(owner) < 16.0, "new_companion_spawn_near_owner");
                    check(runCommand(owner, "mf companion create MF_ChangedName") == 1, "registered_repeat_create_command");
                    check(CompanionRuntime.data(server).find(ownerId).orElseThrow().identity().name()
                            .equals(primaryCompanionName), "create_idempotent");
                    check(runCommand(owner, "mf companion status") == 1, "registered_status_command");
                    companion.getInventory().add(new ItemStack(Items.OAK_LOG, 4));
                    companion.setHealth(15.0F);
                    companion.teleportTo(server.getLevel(Level.NETHER), 8.5, 70.0, 8.5, 0.0F, 0.0F);
                    phase = 1;
                }
                case 1 -> {
                    ServerPlayer companion = liveCompanion();
                    double savedX = companion.getX();
                    double savedY = companion.getY();
                    double savedZ = companion.getZ();
                    check(runCommand(owner, "mf companion dismiss") == 1, "registered_dismiss_command");
                    check(liveCompanion() == null, "dismiss_removes_entity");
                    check(runCommand(owner, "mf companion summon") == 1, "registered_summon_command");
                    ServerPlayer restored = liveCompanion();
                    check(restored != null && restored.getUUID().equals(companionId), "summon_stable_id");
                    check(CompanionRuntime.isCompanion(restored), "summon_sets_runtime_companion_marker");
                    check(restored.getInventory().countItem(Items.OAK_LOG) == 4, "dismiss_preserves_inventory");
                    check(restored.getHealth() == 15.0F, "dismiss_preserves_health");
                    check(restored.serverLevel().dimension() == Level.NETHER, "dismiss_preserves_dimension");
                    check(Math.abs(restored.getX() - savedX) < 0.1
                            && Math.abs(restored.getY() - savedY) < 0.1
                            && Math.abs(restored.getZ() - savedZ) < 0.1, "dismiss_preserves_position");
                    check(runCommand(owner, "mf companion summon") == 1, "registered_repeat_summon_command");
                    check(server.getPlayerList().getPlayers().stream()
                            .filter(player -> player.getUUID().equals(companionId)).count() == 1, "summon_idempotent");
                    CompanionRuntime.onOwnerLogin(restored);
                    CompanionRuntime.onOwnerLogout(restored);
                    check(liveCompanion() == restored, "companion_login_logout_not_owner_recursion");
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
                    if (cancelNextDeath == null) {
                        killAndCheckStart(liveCompanion(), false);
                    } else {
                        forgeCanceledPlayer = liveCompanion();
                        cancelNextDeath.accept(forgeCanceledPlayer);
                        forgeCanceledPlayer.hurt(forgeCanceledPlayer.serverLevel().damageSources().genericKill(), Float.MAX_VALUE);
                        emit("forge_canceled_player_alive_after_event", Boolean.toString(forgeCanceledPlayer.isAlive()));
                        emit("forge_canceled_player_inventory_after_event",
                                Integer.toString(forgeCanceledPlayer.getInventory().countItem(Items.OAK_LOG)));
                        check(server.getPlayerList().getPlayer(companionId) == forgeCanceledPlayer,
                                "forge_canceled_death_keeps_original_player_entry");
                        check(countDroppedOakLogs() == 0, "forge_canceled_death_has_no_drops");
                        phase = 6;
                    }
                }
                case 4 -> {
                    ServerPlayer respawned = liveCompanion();
                    if (respawned == null || !respawned.isAlive()) {
                        if (!deathWaitObserved) {
                            emit("drops_while_original_player_dead", Integer.toString(countDroppedOakLogs()));
                            deathWaitObserved = true;
                        }
                        return;
                    }
                    check(respawned.getUUID().equals(companionId), "false_rule_respawn_stable_id");
                    check(CompanionRuntime.isCompanion(respawned), "false_rule_respawn_retains_companion_marker");
                    check(respawned.getInventory().countItem(Items.OAK_LOG) == 0, "false_rule_inventory_not_duplicated");
                    int droppedLogs = countDroppedOakLogs();
                    emit("loaded_item_entity_count", Integer.toString(countLoadedItemEntities()));
                    emit("keep_inventory_false_drop_count", Integer.toString(droppedLogs));
                    check(droppedLogs == 4, "false_rule_drops_exactly_four");
                    discardOakLogDrops();
                    setKeepInventory(true);
                    respawned.getInventory().add(new ItemStack(Items.OAK_LOG, 4));
                    killAndCheckStart(respawned, true);
                }
                case 5 -> {
                    ServerPlayer respawned = liveCompanion();
                    if (respawned == null || !respawned.isAlive()) {
                        return;
                    }
                    check(respawned.getUUID().equals(companionId), "true_rule_respawn_stable_id");
                    check(CompanionRuntime.isCompanion(respawned), "true_rule_respawn_retains_companion_marker");
                    int droppedLogs = countDroppedOakLogs();
                    emit("keep_inventory_true_drop_count", Integer.toString(droppedLogs));
                    check(droppedLogs == 0, "true_rule_has_no_ground_drops");
                    check(respawned.getInventory().countItem(Items.OAK_LOG) == 4, "true_rule_keeps_inventory");

                    CompanionData store = CompanionRuntime.data(server);
                    CompanionData.Entry stored = store.find(ownerId).orElseThrow();
                    check(stored.identity().ownerId().equals(ownerId), "owner_persisted");
                    ServerPlayer secondOwner = addPlayer(new GameProfile(secondOwnerId, "MF_TestOwner"));
                    check(runCommand(secondOwner, "mf companion create") == 1, "second_owner_default_create");
                    CompanionData.Entry second = store.find(secondOwnerId).orElseThrow();
                    String ownerSuffix = secondOwnerId.toString().replace("-", "").substring(0, 4);
                    check(second.identity().name().startsWith("MF_")
                                    && second.identity().name().endsWith(ownerSuffix)
                                    && second.identity().name().length() <= 16,
                            "default_name_has_stable_unique_suffix");
                    check(!second.identity().companionId().equals(companionId), "second_owner_distinct_companion");
                    check(second.identity().ownerId().equals(secondOwnerId), "owners_isolated");

                    UUID thirdOwnerId = UUID.randomUUID();
                    ServerPlayer thirdOwner = addPlayer(new GameProfile(thirdOwnerId, "MF_TestThree"));
                    check(runCommand(thirdOwner, "mf companion create " + primaryCompanionName) == 0,
                            "custom_duplicate_name_command_rejected");
                    check(store.find(thirdOwnerId).isEmpty(), "custom_duplicate_name_not_registered");
                    check(liveCompanion() != null && liveCompanion().getUUID().equals(companionId),
                            "custom_duplicate_name_does_not_kick_existing_companion");

                    UUID nameOccupantId = UUID.randomUUID();
                    UUID nameConflictOwnerId = UUID.randomUUID();
                    String nameSuffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
                    String humanName = "MF_" + nameSuffix + "x";
                    String storedCompanionName = "MF_" + nameSuffix + "X";
                    ServerPlayer nameOccupant = addPlayer(new GameProfile(nameOccupantId, humanName));
                    ServerPlayer nameConflictOwner = addPlayer(
                            new GameProfile(nameConflictOwnerId, "MF_NameOwner"));
                    store.create(new CompanionIdentity(UUID.randomUUID(), nameConflictOwnerId, storedCompanionName));
                    CompanionRuntime.onOwnerLogin(nameConflictOwner);
                    check(server.getPlayerList().getPlayer(nameOccupantId) == nameOccupant,
                            "login_name_conflict_keeps_human_connected");
                    check(server.getPlayerList().getPlayer(nameConflictOwnerId) == nameConflictOwner,
                            "login_name_conflict_does_not_throw_or_kick_owner");
                    check(testConnections.get(nameConflictOwnerId).restoreConflictNoticeSent(),
                            "login_name_conflict_sends_owner_notice");

                    UUID uuidOccupantId = UUID.randomUUID();
                    UUID uuidConflictOwnerId = UUID.randomUUID();
                    ServerPlayer uuidOccupant = addPlayer(new GameProfile(uuidOccupantId, "MF_UUIDHuman"));
                    ServerPlayer uuidConflictOwner = addPlayer(
                            new GameProfile(uuidConflictOwnerId, "MF_UUIDOwner"));
                    String uuidCollisionName = "MF_" + UUID.randomUUID().toString()
                            .replace("-", "").substring(0, 13);
                    store.create(new CompanionIdentity(uuidOccupantId, uuidConflictOwnerId, uuidCollisionName));
                    CompanionRuntime.onOwnerLogin(uuidConflictOwner);
                    check(server.getPlayerList().getPlayer(uuidOccupantId) == uuidOccupant,
                            "uuid_conflict_login_keeps_human_connected");
                    check(testConnections.get(uuidConflictOwnerId).restoreConflictNoticeSent(),
                            "login_uuid_conflict_sends_owner_notice");
                    check(runCommand(uuidConflictOwner, "mf companion dismiss") == 0,
                            "dismiss_uuid_collision_refused");
                    check(server.getPlayerList().getPlayer(uuidOccupantId) == uuidOccupant,
                            "dismiss_uuid_collision_does_not_remove_human");

                    CompanionRuntime.onServerStopping(server);
                    check(liveCompanion() == null, "shutdown_unloads_companions");
                    check(server.getPlayerList().getPlayer(uuidOccupantId) == uuidOccupant,
                            "shutdown_uuid_collision_does_not_remove_human");
                    CompanionData.Entry afterUnload = store.find(ownerId).orElseThrow();
                    check(afterUnload.active(), "shutdown_preserves_rejoin_state");
                    emit("result", "PASS");
                    done = true;
                    helper.succeed();
                }
                case 6 -> {
                    ServerPlayer companion = liveCompanion();
                    check(companion == forgeCanceledPlayer,
                            "forge_canceled_death_has_no_deferred_respawn");
                    check(companion.getInventory().countItem(Items.OAK_LOG) == 4,
                            "forge_canceled_death_keeps_inventory");
                    CompanionRuntime.dismissFor(owner);
                    CompanionRuntime.summonFor(owner);
                    companion = liveCompanion();
                    check(companion != forgeCanceledPlayer && CompanionRuntime.isCompanion(companion),
                            "forge_canceled_test_recreates_marked_companion");
                    companion.getInventory().clearContent();
                    companion.getInventory().add(new ItemStack(Items.OAK_LOG, 4));
                    companion.setHealth(15.0F);
                    killAndCheckStart(companion, false);
                }
                default -> throw new IllegalStateException("Unexpected lifecycle test phase " + phase);
            }
        } catch (Throwable failure) {
            done = true;
            emit("result", "FAIL phase " + phase + " check " + currentCheck
                    + " (" + failure.getClass().getSimpleName() + ")"
                    + (lastCommandError == null ? "" : " command-error=" + lastCommandError));
            helper.fail("lifecycle phase " + phase + " check " + currentCheck + " failed");
        }
    }

    private void killAndCheckStart(ServerPlayer companion, boolean keepInventory) {
        setKeepInventory(keepInventory);
        if (owner.serverLevel() != level) {
            BlockPos testPosition = helper.absolutePos(BlockPos.ZERO);
            owner.teleportTo(level, testPosition.getX() + 0.5, testPosition.getY() + 1.0,
                    testPosition.getZ() + 0.5, owner.getYRot(), owner.getXRot());
        }
        if (companion.serverLevel() != level) {
            companion.teleportTo(level, owner.getX() + 1.5, owner.getY(), owner.getZ(),
                    owner.getYRot(), owner.getXRot());
        }
        owner.teleportTo(level, companion.getX() + 2.0, companion.getY(), companion.getZ(),
                owner.getYRot(), owner.getXRot());
        companion.setInvulnerable(false);
        companion.invulnerableTime = 0;
        check(!companion.isInvulnerable(), "companion_not_invulnerable_before_death");
        check(server.getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY) == keepInventory,
                keepInventory ? "true_rule_enabled_before_death" : "false_rule_enabled_before_death");
        check(companion.serverLevel().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY) == keepInventory,
                keepInventory ? "true_dimension_rule_enabled_before_death" : "false_dimension_rule_enabled_before_death");
        check(owner.serverLevel() == companion.serverLevel(), "owner_and_companion_share_death_level");
        check(companion.serverLevel().isPositionEntityTicking(companion.blockPosition()),
                "death_chunk_is_entity_ticking");
        check(companion.gameMode.getGameModeForPlayer() == GameType.SURVIVAL,
                "companion_is_in_survival_before_death");
        check(companion.isAlive() && companion.getHealth() > 0.0F,
                "companion_is_alive_before_death");
        check(!companion.isSpectator(), "companion_is_not_spectator_before_death");
        check(server.getGameRules().getBoolean(GameRules.RULE_DOENTITYDROPS),
                "entity_drops_rule_enabled_before_death");
        check(server.getGameRules().getBoolean(GameRules.RULE_DOMOBLOOT),
                "mob_loot_rule_enabled_before_death");
        emit("inventory_before_death", Integer.toString(companion.getInventory().countItem(Items.OAK_LOG)));
        companion.setHealth(0.0F);
        companion.die(companion.serverLevel().damageSources().genericKill());
        emit("drops_after_vanilla_player_die", Integer.toString(countDroppedOakLogs()));
        emit("inventory_after_vanilla_player_die", Integer.toString(companion.getInventory().countItem(Items.OAK_LOG)));
        check(!companion.isAlive(), "death_occurs");
        phase = keepInventory ? 5 : 4;
    }

    private void setKeepInventory(boolean keepInventory) {
        server.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY).set(keepInventory, server);
        for (ServerLevel candidate : server.getAllLevels()) {
            candidate.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY).set(keepInventory, server);
            candidate.getGameRules().getRule(GameRules.RULE_DOENTITYDROPS).set(true, server);
            candidate.getGameRules().getRule(GameRules.RULE_DOMOBLOOT).set(true, server);
        }
    }

    private void assertVanillaPlayerDropControl() {
        ServerPlayer probe = addPlayer(new GameProfile(UUID.randomUUID(), "MF_DropProbe"));
        probe.setGameMode(GameType.SURVIVAL);
        probe.getInventory().add(new ItemStack(Items.OAK_LOG, 1));
        probe.hurt(probe.serverLevel().damageSources().genericKill(), Float.MAX_VALUE);
        int droppedLogs = countDroppedOakLogs();
        emit("simulated_player_death_drop_control", Integer.toString(droppedLogs));
        check(!probe.isAlive(), "simulated_player_death_control_killed");
        check(droppedLogs == 1, "simulated_player_death_drops_exactly_one");
        discardOakLogDrops();
        server.getPlayerList().remove(probe);
        probe.connection.disconnect(new DisconnectionDetails(net.minecraft.network.chat.Component.literal("test control complete")));
    }

    private int countLoadedItemEntities() {
        int count = 0;
        for (ServerLevel candidate : server.getAllLevels()) {
            for (Entity entity : candidate.getAllEntities()) {
                if (entity instanceof ItemEntity) {
                    count++;
                }
            }
        }
        return count;
    }

    private int countDroppedOakLogs() {
        int count = 0;
        Set<UUID> countedDrops = new HashSet<>();
        for (ServerLevel candidate : server.getAllLevels()) {
            for (Entity entity : candidate.getAllEntities()) {
                if (entity instanceof ItemEntity item && item.getItem().is(Items.OAK_LOG)
                        && countedDrops.add(item.getUUID())) {
                    count += item.getItem().getCount();
                }
            }
        }
        return count;
    }

    private void discardOakLogDrops() {
        for (ServerLevel candidate : server.getAllLevels()) {
            for (Entity entity : candidate.getAllEntities()) {
                if (entity instanceof ItemEntity item && item.getItem().is(Items.OAK_LOG)) {
                    item.discard();
                }
            }
        }
    }

    private void assertDamagedPersistenceRejected() {
        CompoundTag unknownSchema = new CompoundTag();
        unknownSchema.putInt("schemaVersion", 77);
        unknownSchema.put("companions", new ListTag());
        expectInvalidData(unknownSchema, "unknown_schema_rejected");

        CompoundTag duplicateNames = new CompoundTag();
        duplicateNames.putInt("schemaVersion", 1);
        ListTag records = new ListTag();
        records.add(makeStoredRecord(0x100L, 0x200L, "MF_Reused"));
        records.add(makeStoredRecord(0x101L, 0x201L, "MF_REUSED"));
        duplicateNames.put("companions", records);
        expectInvalidData(duplicateNames, "duplicate_name_records_rejected");

        CompoundTag duplicateIds = new CompoundTag();
        duplicateIds.putInt("schemaVersion", 1);
        ListTag duplicateIdRecords = new ListTag();
        duplicateIdRecords.add(makeStoredRecord(0x300L, 0x400L, "MF_First"));
        duplicateIdRecords.add(makeStoredRecord(0x300L, 0x401L, "MF_Second"));
        duplicateIds.put("companions", duplicateIdRecords);
        expectInvalidData(duplicateIds, "duplicate_companion_uuid_rejected");

        CompoundTag duplicateOwners = new CompoundTag();
        duplicateOwners.putInt("schemaVersion", 1);
        ListTag duplicateOwnerRecords = new ListTag();
        duplicateOwnerRecords.add(makeStoredRecord(0x500L, 0x600L, "MF_OwnerOne"));
        duplicateOwnerRecords.add(makeStoredRecord(0x501L, 0x600L, "MF_OwnerTwo"));
        duplicateOwners.put("companions", duplicateOwnerRecords);
        expectInvalidData(duplicateOwners, "duplicate_owner_uuid_rejected");

        CompoundTag invalidActive = new CompoundTag();
        invalidActive.putInt("schemaVersion", 1);
        ListTag invalidActiveRecords = new ListTag();
        CompoundTag invalidBoolean = makeStoredRecord(0x700L, 0x800L, "MF_InvalidActive");
        invalidBoolean.putByte("active", (byte) 2);
        invalidActiveRecords.add(invalidBoolean);
        invalidActive.put("companions", invalidActiveRecords);
        expectInvalidData(invalidActive, "invalid_active_flag_rejected");

        CompoundTag damagedRecord = new CompoundTag();
        damagedRecord.putInt("schemaVersion", 1);
        ListTag damagedRecords = new ListTag();
        CompoundTag incomplete = new CompoundTag();
        incomplete.putUUID("companion", new UUID(0x999L, 0xabcL));
        incomplete.putString("name", "MF_Broken");
        incomplete.putBoolean("active", true);
        damagedRecords.add(incomplete);
        damagedRecord.put("companions", damagedRecords);
        expectInvalidData(damagedRecord, "corrupt_record_rejected");
    }

    private void assertPersistenceRoundTrip() {
        UUID owner = new UUID(0xabcL, 0x101L);
        CompanionIdentity identity = new CompanionIdentity(new UUID(0xdefL, 0x202L), owner, "MF_RoundTrip");
        CompanionData before = new CompanionData();
        before.create(identity);
        CompoundTag saved = before.save(new CompoundTag(), server.registryAccess());
        CompanionData after = CompanionData.load(saved, server.registryAccess());
        check(after.find(owner).orElseThrow().identity().equals(identity),
                "identity_persistence_round_trip");
    }

    private void assertCorruptStoreFilePreserved() throws IOException {
        currentCheck = "corrupt_store_file_preserved";
        Path file = server.getWorldPath(LevelResource.ROOT).resolve("data")
                .resolve(CompanionData.FILE_NAME + ".dat");
        Files.createDirectories(file.getParent());
        byte[] original = Files.exists(file) ? Files.readAllBytes(file) : null;
        CompoundTag payload = new CompoundTag();
        payload.putInt("schemaVersion", 77);
        payload.put("companions", new ListTag());
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        root.put("data", payload);
        try {
            NbtIo.writeCompressed(root, file);
            byte[] before = Files.readAllBytes(file);

            boolean rejected = false;
            try {
                CompanionRuntime.data(server);
            } catch (IllegalStateException expected) {
                rejected = expected.getMessage() != null
                        && expected.getMessage().contains("refusing to replace it");
            }
            byte[] after = Files.readAllBytes(file);
            CompoundTag roundTrip = NbtIo.readCompressed(file, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            check(rejected, "corrupt_store_file_rejected_before_default_creation");
            check(Arrays.equals(before, after), "corrupt_store_file_bytes_unchanged");
            check(roundTrip.getCompound("data").getInt("schemaVersion") == 77,
                    "corrupt_store_file_can_be_read_back");
        } finally {
            if (original == null) {
                Files.deleteIfExists(file);
            } else {
                Files.write(file, original);
            }
        }
    }

    private CompoundTag makeStoredRecord(long companionLeast, long ownerLeast, String name) {
        CompoundTag record = new CompoundTag();
        record.putUUID("companion", new UUID(0x1234L, companionLeast));
        record.putUUID("owner", new UUID(0x5678L, ownerLeast));
        record.putString("name", name);
        record.putBoolean("active", true);
        return record;
    }

    private void expectInvalidData(CompoundTag tag, String checkName) {
        currentCheck = checkName;
        boolean rejected = false;
        try {
            CompanionData.load(tag, server.registryAccess());
        } catch (IllegalStateException expected) {
            rejected = expected.getMessage() != null && !expected.getMessage().isBlank();
        }
        check(rejected, checkName);
    }

    private int runCommand(ServerPlayer player, String command) {
        currentCheck = "command " + command;
        lastCommandError = null;
        try {
            return server.getCommands().getDispatcher().execute(command, player.createCommandSourceStack());
        } catch (CommandSyntaxException rejected) {
            lastCommandError = rejected.getRawMessage().getString();
            return 0;
        }
    }

    private ServerPlayer addPlayer(GameProfile profile) {
        ServerPlayer player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        TestConnection connection = new TestConnection();
        testConnections.put(profile.getId(), connection);
        server.getPlayerList().placeNewPlayer(connection, player,
                CommonListenerCookie.createInitial(profile, false));
        player.setGameMode(GameType.SURVIVAL);
        return player;
    }

    private ServerPlayer liveCompanion() {
        return companionId == null ? null : server.getPlayerList().getPlayer(companionId);
    }

    private void check(boolean condition, String name) {
        currentCheck = name;
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
