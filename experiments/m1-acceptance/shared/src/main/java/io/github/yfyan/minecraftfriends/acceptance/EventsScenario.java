package io.github.yfyan.minecraftfriends.acceptance;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.yfyan.minecraftfriends.chat.ChatRuntime;
import io.github.yfyan.minecraftfriends.chat.ChatRuntime.ChatStatus;
import io.github.yfyan.minecraftfriends.companion.CompanionData;
import io.github.yfyan.minecraftfriends.companion.CompanionRuntime;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

/** Test-only observer that drives real game events through the production integrations. */
public final class EventsScenario {
    private static final Map<MinecraftServer, EventsScenario> RUNS = new IdentityHashMap<>();
    private static final Map<UUID, Seed> SEEDS = new HashMap<>();
    private static final UUID ABSENT = new UUID(0L, 0L);
    private static final int DELETE_FACT = 1;
    private static final int KEEP_FACT = 2;
    private static final int FIRST_EVENT = 4;
    private static final int INJURED_EVENT = 8;
    private static final int REJOIN_EVENT = 16;

    private final MinecraftServer server;
    private ServerPlayer owner;
    private Seed seed;
    private int phase;
    private int age;
    private int phaseAge;
    private boolean finished;
    private int firstHurtAge;
    private boolean secondCooldownHurtDone;
    private float secondInjuryHealth;
    private long memoryGenerationBeforeDelete;
    private int memoryCountBeforeDelete;
    private UUID deleteMemoryId;
    private UUID keepMemoryId;
    private UUID listNonce;
    private ListReport listReport;

    private EventsScenario(MinecraftServer server) {
        this.server = server;
    }

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("mfevents")
                .then(Commands.literal("ack")
                        .then(Commands.argument("event", StringArgumentType.word())
                                .then(Commands.argument("nonce", StringArgumentType.word())
                                        .executes(context -> {
                                            ServerPlayer sender = context.getSource().getPlayerOrException();
                                            EventsScenario run = RUNS.get(sender.server);
                                            if (run == null || run.owner == null || run.seed == null
                                                    || !sender.getUUID().equals(run.owner.getUUID())) {
                                                return 0;
                                            }
                                            String event = StringArgumentType.getString(context, "event");
                                            String nonce = StringArgumentType.getString(context, "nonce");
                                            if (!run.seed.expectedEvent.equals(event)
                                                    || !run.seed.expectedNonce.toString().equals(nonce)) {
                                                return 0;
                                            }
                                            run.seed.acknowledged = true;
                                            return 1;
                                        }))))
                .then(Commands.literal("report")
                        .then(Commands.argument("nonce", StringArgumentType.word())
                                .then(Commands.argument("deleteId", StringArgumentType.word())
                                        .then(Commands.argument("keepId", StringArgumentType.word())
                                                .then(Commands.argument("mask", IntegerArgumentType.integer(0, 31))
                                                        .then(Commands.argument("count", IntegerArgumentType.integer(0, 32))
                                                                .executes(context -> {
                                                                    ServerPlayer sender = context.getSource().getPlayerOrException();
                                                                    EventsScenario run = RUNS.get(sender.server);
                                                                    if (run == null || run.owner == null || run.listNonce == null
                                                                            || !sender.getUUID().equals(run.owner.getUUID())
                                                                            || !run.listNonce.toString().equals(StringArgumentType.getString(context, "nonce"))) {
                                                                        return 0;
                                                                    }
                                                                    try {
                                                                        UUID deleteId = UUID.fromString(StringArgumentType.getString(context, "deleteId"));
                                                                        UUID keepId = UUID.fromString(StringArgumentType.getString(context, "keepId"));
                                                                        int mask = IntegerArgumentType.getInteger(context, "mask");
                                                                        int count = IntegerArgumentType.getInteger(context, "count");
                                                                        run.listReport = new ListReport(deleteId, keepId, mask, count);
                                                                        return 1;
                                                                    } catch (IllegalArgumentException invalid) {
                                                                        return 0;
                                                                    }
                                                                }))))))));
    }

    public static void tick(MinecraftServer server) {
        EventsScenario run = RUNS.computeIfAbsent(server, EventsScenario::new);
        run.step();
    }

    public static void remove(MinecraftServer server) {
        RUNS.remove(server);
    }

    private void step() {
        if (finished) {
            return;
        }
        age++;
        phaseAge++;
        if (owner == null) {
            owner = findOwner();
            if (owner == null) {
                return;
            }
            seed = SEEDS.computeIfAbsent(owner.getUUID(), ignored -> new Seed());
            if (seed.reopenPending) {
                phase = 20;
                phaseAge = 0;
            }
        }
        if (phaseAge > 6_000) {
            fail("phase_timeout_" + phase);
            return;
        }
        try {
            switch (phase) {
                case 0 -> startFreshWorld();
                case 1 -> awaitFirstMeeting();
                case 2 -> hurtOnce("INJURED");
                case 3 -> awaitFirstInjury();
                case 4 -> hurtDuringCooldown();
                case 5 -> disableProactive();
                case 6 -> hurtWhileDisabled();
                case 7 -> awaitDisabledCooldown();
                case 8 -> enableBeforeRejoin();
                case 9 -> prepareRejoin();
                case 20 -> awaitRejoin();
                case 21 -> awaitKeepFact();
                case 22 -> awaitDeleteFact();
                case 23 -> awaitInitialList();
                case 24 -> awaitDelete();
                case 25 -> awaitPostDeleteList();
                case 26 -> awaitDeleteContextProbe();
                default -> throw new IllegalStateException("invalid_phase");
            }
        } catch (RuntimeException failure) {
            fail("scenario_error_" + phase);
        }
    }

    private ServerPlayer findOwner() {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Seed existing = SEEDS.get(player.getUUID());
            if (existing != null && existing.reopenPending) {
                return player;
            }
            if (!CompanionRuntime.isCompanion(player)
                    && CompanionRuntime.data(server).find(player.getUUID()).isEmpty()) {
                return player;
            }
        }
        return null;
    }

    private void startFreshWorld() {
        ChatStatus status = status();
        check(status.globalEnabled() && status.enabled(), "chat_runtime_enabled");
        check("chat-completions".equals(status.providerId()), "loopback_provider_configured");
        check(status.proactiveEnabled(), "proactive_default_enabled");
        expect("FIRST_MEETING");
        command("mf companion create MF_EventsBot");
        phase(1);
    }

    private void awaitFirstMeeting() {
        CompanionData.Entry entry = entry();
        if (entry == null || !status().memoryLoaded()) {
            return;
        }
        if (seed.companionId == null) {
            seed.companionId = entry.identity().companionId();
            seed.worldId = status().worldId();
            check(seed.worldId != null, "first_companion_identity");
        } else if (!seed.companionId.equals(entry.identity().companionId())) {
            throw new IllegalStateException("first_companion_identity");
        }
        if (live() == null) {
            return;
        }
        if (!acknowledged()) {
            return;
        }
        if (!idle() || status().memoryCount() < 1) {
            return;
        }
        check(live().isAlive() && live().getHealth() > 0.0F, "first_meeting_live_companion");
        check(true, "first_meeting_client_reply_ack");
        phase(2);
    }

    private void hurtOnce(String event) {
        requireCompanion();
        expect(event);
        realHurt("first_injury_health_reduced");
        firstHurtAge = age;
        phase(3);
    }

    private void awaitFirstInjury() {
        if (!acknowledged() || !idle() || status().memoryCount() < 2) {
            return;
        }
        check(true, "injured_client_reply_ack");
        phase(4);
    }

    private void hurtDuringCooldown() {
        if (!secondCooldownHurtDone) {
            if (age - firstHurtAge < 12) {
                return;
            }
            secondInjuryHealth = live().getHealth();
            realHurt("cooldown_injury_health_reduced");
            secondCooldownHurtDone = true;
            phaseAge = 0;
            return;
        }
        if (phaseAge < 100 || !idle()) {
            return;
        }
        check(live() != null && live().getHealth() < secondInjuryHealth,
                "second_real_hurt_without_death");
        check(status().memoryCount() >= 2, "injury_cooldown_memory_stable");
        check(true, "injury_cooldown_window_observed");
        phase(5);
    }

    private void disableProactive() {
        command("mf memory proactive false");
        phase(6);
    }

    private void hurtWhileDisabled() {
        if (!status().memoryLoaded() || status().proactiveEnabled()) {
            return;
        }
        check(true, "proactive_disabled");
        secondInjuryHealth = live().getHealth();
        realHurt("disabled_injury_health_reduced");
        phase(7);
    }

    private void awaitDisabledCooldown() {
        if (phaseAge < 100 || !idle()) {
            return;
        }
        check(live() != null && live().getHealth() < secondInjuryHealth,
                "disabled_real_hurt_without_death");
        check(true, "disabled_injury_quiet_window");
        command("mf memory proactive true");
        phase(8);
    }

    private void enableBeforeRejoin() {
        if (!status().proactiveEnabled() || !idle()) {
            return;
        }
        check(true, "proactive_restored_before_rejoin");
        phase(9);
    }

    private void prepareRejoin() {
        seed.expectedEvent = "REJOIN";
        seed.expectedNonce = UUID.randomUUID();
        seed.acknowledged = false;
        seed.reopenPending = true;
        owner.sendSystemMessage(Component.literal("MF_EVENTS REOPEN REJOIN " + seed.expectedNonce));
        emit("integrated_world_reopen_requested", "PASS");
        finished = true;
    }

    private void awaitRejoin() {
        CompanionData.Entry entry = entry();
        if (entry == null || live() == null || !status().memoryLoaded() || !status().proactiveEnabled()) {
            return;
        }
        if (!seed.companionId.equals(entry.identity().companionId())
                || !seed.worldId.equals(status().worldId())) {
            fail("rejoin_identity_mismatch");
            return;
        }
        if (!acknowledged() || !idle() || status().memoryCount() < 3) {
            return;
        }
        check(true, "rejoin_same_world_and_companion");
        check(true, "rejoin_client_reply_ack");
        seed.reopenPending = false;
        memoryCountBeforeDelete = status().memoryCount();
        command("mf memory remember MF_KEEP_FACT");
        phase(21);
    }

    private void awaitKeepFact() {
        if (!idle() || status().memoryCount() < memoryCountBeforeDelete + 1) {
            return;
        }
        command("mf memory remember MF_DELETE_FACT");
        phase(22);
    }

    private void awaitDeleteFact() {
        if (!idle() || status().memoryCount() < memoryCountBeforeDelete + 2) {
            return;
        }
        listNonce = UUID.randomUUID();
        listReport = null;
        owner.sendSystemMessage(Component.literal("MF_EVENTS LIST " + listNonce));
        phase(23);
    }

    private void awaitInitialList() {
        if (listReport == null) {
            return;
        }
        check(listReport.mask() == 31 && listReport.count() == status().memoryCount(),
                "memory_list_contains_target_and_verified_events");
        check(!listReport.deleteId().equals(ABSENT) && !listReport.keepId().equals(ABSENT),
                "memory_list_parsed_real_uuids");
        deleteMemoryId = listReport.deleteId();
        keepMemoryId = listReport.keepId();
        memoryGenerationBeforeDelete = status().memoryGeneration();
        memoryCountBeforeDelete = status().memoryCount();
        command("mf memory delete " + deleteMemoryId);
        phase(24);
    }

    private void awaitDelete() {
        ChatStatus status = status();
        if (!status.memoryLoaded() || status.memoryGeneration() <= memoryGenerationBeforeDelete
                || !idle() || status.memoryCount() != memoryCountBeforeDelete - 1) {
            return;
        }
        check(true, "memory_delete_generation_advanced");
        check(true, "memory_delete_count_decreased");
        listNonce = UUID.randomUUID();
        listReport = null;
        owner.sendSystemMessage(Component.literal("MF_EVENTS LIST " + listNonce));
        phase(25);
    }

    private void awaitPostDeleteList() {
        if (listReport == null) {
            return;
        }
        int expectedMask = KEEP_FACT | FIRST_EVENT | INJURED_EVENT | REJOIN_EVENT;
        check(listReport.mask() == expectedMask && listReport.count() == status().memoryCount(),
                "memory_list_delete_absent_events_retained");
        check(listReport.deleteId().equals(ABSENT) && listReport.keepId().equals(keepMemoryId),
                "memory_list_delete_uuid_absent_keep_uuid_stable");
        expect("DELETE_PROBE");
        command("mf chat MF_EVENTS_DELETE_PROBE:" + seed.expectedNonce + " 请用中文简短回复。");
        phase(26);
    }

    private void awaitDeleteContextProbe() {
        if (!acknowledged() || !idle()) {
            return;
        }
        check(true, "memory_delete_context_probe_ack");
        check(status().memoryCount() == memoryCountBeforeDelete - 1,
                "memory_delete_context_probe_count_stable");
        seed.reopenPending = false;
        finished = true;
        emit("result", "EVENTS_PASS");
        owner.sendSystemMessage(Component.literal("MF_EVENTS PASS EVENTS_PASS"));
    }

    private void realHurt(String assertion) {
        ServerPlayer companion = live();
        if (companion == null || !companion.isAlive() || companion.getHealth() <= 1.0F) {
            throw new IllegalStateException("companion_not_safe_to_hurt");
        }
        float before = companion.getHealth();
        boolean accepted = companion.hurt(companion.damageSources().genericKill(), 1.0F);
        check(accepted && companion.isAlive() && companion.getHealth() > 0.0F
                        && companion.getHealth() < before,
                assertion);
    }

    private void requireCompanion() {
        if (live() == null || !live().isAlive() || live().getHealth() <= 1.0F) {
            throw new IllegalStateException("companion_not_safe_to_hurt");
        }
    }

    private CompanionData.Entry entry() {
        return CompanionRuntime.data(server).find(owner.getUUID()).orElse(null);
    }

    private ServerPlayer live() {
        return seed == null || seed.companionId == null
                ? null : server.getPlayerList().getPlayer(seed.companionId);
    }

    private ChatStatus status() {
        return ChatRuntime.status(server, owner.getUUID());
    }

    private boolean idle() {
        ChatStatus status = status();
        return status.inFlightCount() == 0 && status.queuedCount() == 0;
    }

    private boolean acknowledged() {
        return seed.expectedNonce != null && seed.acknowledged;
    }

    private void expect(String event) {
        seed.expectedEvent = event;
        seed.expectedNonce = UUID.randomUUID();
        seed.acknowledged = false;
        owner.sendSystemMessage(Component.literal("MF_EVENTS EXPECT " + event + " " + seed.expectedNonce));
    }

    private void command(String command) {
        owner.sendSystemMessage(Component.literal("MF_EVENTS COMMAND " + UUID.randomUUID() + " " + command));
    }

    private void phase(int next) {
        phase = next;
        phaseAge = 0;
    }

    private void check(boolean passed, String name) {
        if (!passed) {
            throw new IllegalStateException(name);
        }
        emit(name, "PASS");
    }

    private void fail(String reason) {
        finished = true;
        emit("result", "FAIL " + reason);
        if (owner != null) {
            owner.sendSystemMessage(Component.literal("MF_EVENTS FAIL " + reason));
        }
    }

    private void emit(String name, String value) {
        System.out.println("MF_EVENTS_SERVER " + server.getClass().getName() + " " + name + " " + value);
    }

    private record ListReport(UUID deleteId, UUID keepId, int mask, int count) { }

    private static final class Seed {
        private UUID companionId;
        private UUID worldId;
        private UUID expectedNonce;
        private String expectedEvent = "";
        private boolean acknowledged;
        private boolean reopenPending;

        private Seed() { }
    }
}
