package io.github.yfyan.probe;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Basic server entity experiment, deliberately not navigation or production action execution. */
public final class PlayerScenario {
    private final GameTestHelper helper;
    private final String loader;
    private final ServerLevel level;
    private final MinecraftServer server;
    private final BlockPos origin;
    private final GameProfile profile;
    private ServerPlayer player;
    private ProbeConnection connection;
    private Vec3 initial;
    private boolean finished;

    private PlayerScenario(GameTestHelper helper, String loader) {
        this.helper = helper;
        this.loader = loader;
        level = helper.getLevel();
        server = level.getServer();
        origin = helper.absolutePos(new BlockPos(3, 2, 3));
        profile = new GameProfile(UUID.nameUUIDFromBytes(
                ("MinecraftFriendsProbe:" + loader).getBytes(StandardCharsets.UTF_8)), "MF_Probe");
    }

    public static void run(GameTestHelper helper, String loader) {
        PlayerScenario scenario = new PlayerScenario(helper, loader);
        helper.onEachTick(scenario::tick);
    }

    private void spawn() {
        connection = new ProbeConnection();
        player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        server.getPlayerList().placeNewPlayer(connection, player,
                CommonListenerCookie.createInitial(profile, false));
        player.setGameMode(GameType.SURVIVAL);
        player.teleportTo(level, origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5, 0, 0);
        player.setHealth(20);
    }

    private void tick() {
        if (finished) { return; }
        try {
            long tick = helper.getTick();
            if (player == null && tick < 5) {
                for (int x = -3; x <= 3; x++) {
                    for (int z = -3; z <= 3; z++) {
                        level.setBlockAndUpdate(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                        level.setBlockAndUpdate(origin.offset(x, 0, z), Blocks.AIR.defaultBlockState());
                        level.setBlockAndUpdate(origin.offset(x, 1, z), Blocks.AIR.defaultBlockState());
                    }
                }
                spawn();
                check(server.getPlayerList().getPlayer(profile.getId()) == player, "joined");
                player.getInventory().clearContent();
                player.getInventory().add(new ItemStack(Items.OAK_LOG, 7));
                initial = player.position();
                player.setDeltaMovement(0.25, 0, 0);
            }
            if (player != null && player.isAlive()) {
                player.doTick();
                level.getChunkSource().move(player);
            }
            if (tick == 20) {
                double moved = player.position().distanceTo(initial);
                check(moved > 0.1, "movement");
                emit("movement_distance", Double.toString(moved));
                player.teleportTo(level, origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5, 0, 0);
                BlockPos target = origin.east();
                level.setBlockAndUpdate(target, Blocks.DIRT.defaultBlockState());
                check(player.gameMode.destroyBlock(target) && level.getBlockState(target).isAir(), "mine_primitive");
                player.teleportTo(level, target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0, 0);
            }
            if (tick == 55) {
                check(player.getInventory().countItem(Items.DIRT) == 1, "pickup");
            }
            if (tick == 75) {
                boolean hurt = player.hurt(level.damageSources().generic(), 5);
                check(hurt && player.getHealth() < 20, "damage");
                emit("damaged_health", Float.toString(player.getHealth()));
                server.getPlayerList().remove(player);
                check(server.getPlayerList().getPlayer(profile.getId()) == null, "removed");
                spawn();
                check(player.getInventory().countItem(Items.OAK_LOG) == 7, "rejoin_inventory_7");
            }
            if (tick == 150) {
                player.hurt(level.damageSources().genericKill(), Float.MAX_VALUE);
                check(!player.isAlive(), "death");
                int dropped = level.getEntitiesOfClass(ItemEntity.class,
                        new AABB(origin).inflate(12)).stream()
                        .filter(e -> e.getItem().is(Items.OAK_LOG))
                        .mapToInt(e -> e.getItem().getCount()).sum();
                check(dropped == 7, "death_drops_7");
                player = server.getPlayerList().respawn(player, false, Entity.RemovalReason.KILLED);
                player.setHealth(player.getMaxHealth());
                check(player.isAlive() && player.getHealth() == 20, "respawn_adapter");
                check(player.getInventory().countItem(Items.OAK_LOG) == 0, "no_death_duplication");
            }
            if (tick == 170) {
                check(connection.discardedPackets() > 0, "packet_discard");
                emit("discarded_packets", Integer.toString(connection.discardedPackets()));
                cleanup();
                finished = true;
                emit("result", "PASS");
                helper.succeed();
            }
        } catch (Throwable failure) {
            finished = true;
            emit("result", "FAIL: " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            cleanup();
            helper.fail(failure.toString());
        }
    }

    private void check(boolean condition, String name) {
        if (!condition) { throw new IllegalStateException(name); }
        emit(name, "PASS");
    }

    private void cleanup() {
        if (player != null && server.getPlayerList().getPlayer(profile.getId()) != null) {
            server.getPlayerList().remove(player);
        }
        if (connection != null) { connection.disconnect(new net.minecraft.network.DisconnectionDetails(
                net.minecraft.network.chat.Component.literal("probe finished"))); }
    }

    private void emit(String name, String value) {
        JsonObject record = new JsonObject();
        record.addProperty("loader", loader);
        record.addProperty("server_class", server.getClass().getName());
        record.addProperty("dedicated", server.isDedicatedServer());
        record.addProperty("tick", helper.getTick());
        record.addProperty("check", name);
        record.addProperty("value", value);
        System.out.println("MF_PROBE " + record);
    }
}
