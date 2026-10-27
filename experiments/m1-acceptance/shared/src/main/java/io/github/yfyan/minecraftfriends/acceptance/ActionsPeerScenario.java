package io.github.yfyan.minecraftfriends.acceptance;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.yfyan.minecraftfriends.action.ActionRuntime;
import io.github.yfyan.minecraftfriends.action.ActionStatus;
import io.github.yfyan.minecraftfriends.chat.ChatRuntime;
import io.github.yfyan.minecraftfriends.companion.CompanionRuntime;
import io.github.yfyan.minecraftfriends.task.TaskState;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Two real client packets drive all actions; teleports below are fixture placement only. */
public final class ActionsPeerScenario {
    private static final Map<MinecraftServer, ActionsPeerScenario> RUNS = new IdentityHashMap<>();
    private final MinecraftServer server;
    private final Map<UUID, UUID> expected = new HashMap<>();
    private final Set<UUID> ready = new HashSet<>();
    private ServerPlayer a, b, botA, botB;
    private UUID taskA, taskB;
    private Vec3 originA, originB, sampleA, sampleB, lastA, lastB;
    private int phase, age, total, observed;
    private boolean finished;
    private ActionsPeerScenario(MinecraftServer server) { this.server = server; }

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("mfacceptance").then(Commands.literal("actionspeer")
                .then(Commands.literal("ready").executes(c -> {
                    ActionsPeerScenario run = run(c.getSource().getServer());
                    ServerPlayer sender = c.getSource().getPlayerOrException();
                    if (fixed(sender)) { run.ready.add(sender.getUUID()); return 1; }
                    run.fail("unexpected_ready_identity"); return 0;
                }))
                .then(Commands.literal("sent").then(Commands.argument("nonce", StringArgumentType.word()).executes(c -> {
                    ActionsPeerScenario run = run(c.getSource().getServer());
                    ServerPlayer sender = c.getSource().getPlayerOrException();
                    try {
                        UUID nonce = UUID.fromString(StringArgumentType.getString(c, "nonce"));
                        if (!fixed(sender) || !sender.getUUID().equals(run.expected.remove(nonce))) {
                            run.fail("invalid_sent_ack"); return 0;
                        }
                        return 1;
                    } catch (IllegalArgumentException invalid) { run.fail("invalid_nonce"); return 0; }
                })))));
    }
    public static void tick(MinecraftServer server) { run(server).step(); }
    public static void remove(MinecraftServer server) { RUNS.remove(server); }
    private static ActionsPeerScenario run(MinecraftServer server) { return RUNS.computeIfAbsent(server, ActionsPeerScenario::new); }
    private static boolean fixed(ServerPlayer p) {
        String name = p.getGameProfile().getName();
        return !CompanionRuntime.isCompanion(p) && (name.equals("MF_M2_A") || name.equals("MF_M2_B"));
    }
    private void step() {
        if (finished) return;
        age++; total++;
        if (total > 12_000 || age > (phase == 0 ? 6_000 : 600)) { fail("timeout_phase_" + phase); return; }
        try {
            if (!(server instanceof DedicatedServer)
                    || !server.getWorldPath(LevelResource.ROOT).toRealPath().getFileName().toString().startsWith("mf-m2-acceptance-")) {
                fail("isolated_dedicated_world_required"); return;
            }
            if (phase == 0) { setup(); return; }
            if (server.getPlayerList().getPlayer(a.getUUID()) != a || server.getPlayerList().getPlayer(b.getUUID()) != b
                    || !a.isAlive() || !b.isAlive()
                    || server.getPlayerList().getPlayers().stream().filter(p -> !CompanionRuntime.isCompanion(p)).count() != 2) {
                fail("client_identity_or_presence_changed"); return;
            }
            if (phase > 1) {
                if (!owned(a, botA) || !owned(b, botB)) { fail("companion_ownership_changed"); return; }
                if (botA.position().distanceTo(lastA) > 0.95 || botB.position().distanceTo(lastB) > 0.95) {
                    fail("nonphysical_bot_step"); return;
                }
                lastA = botA.position(); lastB = botB.position();
            }
            switch (phase) {
                case 1 -> created();
                case 2 -> bothFollow();
                case 3 -> bStopped();
                case 4 -> aStopped();
                case 5 -> aRestarted();
                case 6 -> invalidOwnerStop();
                case 7 -> bothStopped();
                default -> fail("unknown_phase");
            }
        } catch (Exception error) { fail("exception_" + error.getClass().getSimpleName()); }
    }
    private void setup() {
        a = server.getPlayerList().getPlayerByName("MF_M2_A"); b = server.getPlayerList().getPlayerByName("MF_M2_B");
        if (a == null || b == null || !ready.contains(a.getUUID()) || !ready.contains(b.getUUID())) return;
        if (a == b || a.getUUID().equals(b.getUUID()) || ready.size() != 2
                || server.getPlayerList().getPlayers().size() != 2
                || CompanionRuntime.data(server).find(a.getUUID()).isPresent()
                || CompanionRuntime.data(server).find(b.getUUID()).isPresent()
                || !"mock".equals(ChatRuntime.status(a).providerId()) || !"mock".equals(ChatRuntime.status(b).providerId())) {
            fail("fresh_two_clients_mock_required"); return;
        }
        for (int x = -4; x <= 62; x++) for (int z = -4; z <= 20; z++) {
            server.overworld().setBlock(new BlockPos(x,-61,z), Blocks.STONE.defaultBlockState(),3);
            for (int y = -60; y <= -57; y++) server.overworld().setBlock(new BlockPos(x,y,z), Blocks.AIR.defaultBlockState(),3);
        }
        a.setGameMode(GameType.SURVIVAL); b.setGameMode(GameType.SURVIVAL);
        a.teleportTo(14.5,-60,2.5); b.teleportTo(14.5,-60,14.5);
        emit("ready_uuid_and_fresh_dedicated_map", true);
        command(a,"mf companion create MF_M2PeerA"); command(b,"mf companion create MF_M2PeerB"); next(1);
    }
    private void created() {
        if (!sent()) return;
        var ea = CompanionRuntime.data(server).find(a.getUUID()).orElse(null);
        var eb = CompanionRuntime.data(server).find(b.getUUID()).orElse(null);
        if (ea == null || eb == null) return;
        botA = server.getPlayerList().getPlayer(ea.identity().companionId());
        botB = server.getPlayerList().getPlayer(eb.identity().companionId());
        if (botA == null || botB == null) return;
        if (botA == botB || !owned(a,botA) || !owned(b,botB)
                || !ea.identity().name().equals("MF_M2PeerA") || !eb.identity().name().equals("MF_M2PeerB")) {
            fail("companions_not_independently_owned"); return;
        }
        botA.teleportTo(2.5,-60,2.5); botB.teleportTo(2.5,-60,14.5); // Last bot fixture teleports.
        originA = lastA = botA.position(); originB = lastB = botB.position();
        System.out.println("MF_M2_PEER_IDENTITY role=a owner=" + a.getUUID() + " companion=" + botA.getUUID());
        System.out.println("MF_M2_PEER_IDENTITY role=b owner=" + b.getUUID() + " companion=" + botB.getUUID());
        emit("two_owned_companions_created_by_packets",true);
        command(a,"mf task follow"); command(b,"mf task follow"); next(2);
    }
    private void bothFollow() {
        if (!sent() || !running(a) || !running(b)) return;
        if (botA.position().distanceTo(originA) < 3 || botB.position().distanceTo(originB) < 3) return;
        taskA = status(a).taskId(); taskB = status(b).taskId();
        if (taskA.equals(taskB)) { fail("task_ids_not_distinct"); return; }
        emit("both_follow_physical_displacement",true);
        a.teleportTo(24.5,-60,2.5); command(b,"mf task stop"); next(3);
    }
    private void bStopped() {
        if (!sameRunning(a,taskA)) { fail("b_stop_changed_a_task"); return; }
        if (!sent() || status(b).state() != TaskState.CANCELLED) return;
        if (!status(b).taskId().equals(taskB)) { fail("b_stop_changed_own_task_id"); return; }
        if (observed++ == 0) { sampleA = botA.position(); sampleB = botB.position(); }
        if (botB.position().distanceTo(sampleB) > .05) { fail("b_stop_did_not_hold"); return; }
        if (observed < 40) return;
        if (botA.position().distanceTo(sampleA) < 2) { fail("a_did_not_keep_following_after_b_stop"); return; }
        emit("b_stop_isolated_a_keeps_moving",true);
        b.teleportTo(24.5,-60,14.5); command(a,"mf task stop"); command(b,"mf task follow"); next(4);
    }
    private void aStopped() {
        if (!sent() || status(a).state() != TaskState.CANCELLED || !running(b)) return;
        if (!status(a).taskId().equals(taskA)) { fail("a_stop_changed_own_task_id"); return; }
        if (observed++ == 0) { sampleA = botA.position(); sampleB = botB.position(); taskB = status(b).taskId(); }
        if (!sameRunning(b,taskB) || botA.position().distanceTo(sampleA) > .05) { fail("a_stop_changed_b_or_a_moves"); return; }
        if (observed < 40) return;
        if (botB.position().distanceTo(sampleB) < 2) { fail("b_did_not_keep_following_after_a_stop"); return; }
        emit("a_stop_isolated_b_keeps_moving",true);
        a.teleportTo(52.5,-60,2.5); command(a,"mf task follow"); next(5);
    }
    private void aRestarted() {
        if (!sent() || !running(a)) return;
        taskA = status(a).taskId(); sampleA = botA.position();
        command(b,"mf task stop " + a.getUUID()); next(6);
    }
    private void invalidOwnerStop() {
        if (!sameRunning(a,taskA) || !sameRunning(b,taskB)) { fail("extra_owner_argument_changed_task"); return; }
        if (!sent()) return;
        if (++observed < 40) return;
        if (botA.position().distanceTo(sampleA) < 2) { fail("invalid_stop_prevented_a_movement"); return; }
        emit("extra_owner_stop_rejected_without_cross_cancel",true);
        command(a,"mf task stop"); command(b,"mf task stop"); next(7);
    }
    private void bothStopped() {
        if (!sent() || status(a).state() != TaskState.CANCELLED || status(b).state() != TaskState.CANCELLED) return;
        if (!status(a).taskId().equals(taskA) || !status(b).taskId().equals(taskB)) { fail("final_stop_changed_ids"); return; }
        if (observed++ == 0) { sampleA = botA.position(); sampleB = botB.position(); }
        if (botA.position().distanceTo(sampleA) > .05 || botB.position().distanceTo(sampleB) > .05) {
            fail("final_stop_moved"); return;
        }
        if (observed < 40) return;
        emit("both_stopped_still_40_ticks",true); emit("result",true); finished = true;
        tell(a,"PASS"); tell(b,"PASS");
    }
    private boolean owned(ServerPlayer owner, ServerPlayer bot) {
        var entry = CompanionRuntime.data(server).find(owner.getUUID()).orElse(null);
        return bot != null && bot.isAlive() && bot.serverLevel() == owner.serverLevel()
                && CompanionRuntime.isCompanion(bot) && server.getPlayerList().getPlayer(bot.getUUID()) == bot
                && entry != null && entry.active() && entry.identity().ownerId().equals(owner.getUUID())
                && entry.identity().companionId().equals(bot.getUUID());
    }
    private ActionStatus status(ServerPlayer p) { return ActionRuntime.status(server,p.getUUID()); }
    private boolean running(ServerPlayer p) { return status(p).state() == TaskState.RUNNING && status(p).type().name().equals("FOLLOW"); }
    private boolean sameRunning(ServerPlayer p, UUID id) { return running(p) && status(p).taskId().equals(id); }
    private boolean sent() { return expected.isEmpty(); }
    private void command(ServerPlayer p, String command) {
        UUID nonce = UUID.randomUUID(); expected.put(nonce,p.getUUID()); tell(p,"COMMAND " + nonce + " " + command);
    }
    private void next(int value) { phase = value; age = 0; observed = 0; }
    private void tell(ServerPlayer p, String text) { if (p != null) p.sendSystemMessage(Component.literal("MF_M2_PEER " + text)); }
    private void emit(String check, boolean pass) {
        System.out.println("MF_M2_PEER_SERVER " + server.getClass().getName() + " " + check + " " + (pass ? "PASS" : "FAIL"));
    }
    private void fail(String reason) {
        if (finished) return; finished = true;
        System.out.println("MF_M2_PEER_SERVER " + server.getClass().getName() + " result FAIL " + reason);
        tell(a,"FAIL " + reason); tell(b,"FAIL " + reason);
    }
}
