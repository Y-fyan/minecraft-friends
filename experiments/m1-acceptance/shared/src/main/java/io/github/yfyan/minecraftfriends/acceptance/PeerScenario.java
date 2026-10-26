package io.github.yfyan.minecraftfriends.acceptance;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.yfyan.minecraftfriends.companion.CompanionData;
import io.github.yfyan.minecraftfriends.companion.CompanionRuntime;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Server-side coordinator for two real offline development clients. */
public final class PeerScenario {
    private static final int CLIENT_STARTUP_TIMEOUT_TICKS = 6_000;
    private static final String A_NAME = "MFAcceptA";
    private static final String B_NAME = "MFAcceptB";
    private static final String A_ADDRESS = "MF_A_ADDRESS";
    private static final String B_ADDRESS = "MF_B_ADDRESS";
    private static final String A_FACT = "MF_A_PRIVATE_FACT";
    private static final String B_FACT = "MF_B_PRIVATE_FACT";
    private static final Map<MinecraftServer, PeerScenario> RUNS = new IdentityHashMap<>();

    private final MinecraftServer server;
    private final Map<String, String> reports = new HashMap<>();
    private final Map<String, Set<UUID>> expectedAcks = new HashMap<>();
    private final Map<String, Set<UUID>> receivedAcks = new HashMap<>();
    private ServerPlayer a;
    private ServerPlayer b;
    private UUID aCompanion;
    private UUID bCompanion;
    private UUID sharedId;
    private UUID afterLeaveNonce;
    private String afterLeaveText;
    private String aAddress;
    private String bAddress;
    private int aMemoryCount;
    private long bGenerationBeforeClear;
    private int age;
    private int phaseStart;
    private int afterLeaveSentAt;
    private int phase;
    private boolean finished;
    private boolean afterLeaveSent;
    private boolean afterLeaveWatchReportsRequested;
    private boolean aFactWatchRequested;
    private boolean aFactListRequested;
    private boolean aFactWatchReportRequested;
    private int aFactListStart;

    private record Status(String provider, UUID worldId, UUID companionId, boolean companionActive,
                          boolean companionOnline, boolean memoryLoaded, long generation, int memoryCount,
                          String address, boolean proactive, int inFlight, int queued,
                          int sharedSessions, int sharedMembers) {
        boolean idle() { return inFlight == 0 && queued == 0; }
    }

    private PeerScenario(MinecraftServer server) { this.server = server; }

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("mfacceptance")
                .then(Commands.literal("peer")
                        .then(Commands.literal("ready").executes(context -> {
                            PeerScenario run = run(context.getSource().getServer());
                            ServerPlayer sender = context.getSource().getPlayerOrException();
                            run.onReady(sender);
                            return 1;
                        }))
                        .then(Commands.literal("ack").then(Commands.argument("nonce", StringArgumentType.word())
                                .executes(context -> {
                                    PeerScenario run = run(context.getSource().getServer());
                                    run.onAck(context.getSource().getPlayerOrException(),
                                            StringArgumentType.getString(context, "nonce"));
                                    return 1;
                                })))
                        .then(Commands.literal("unexpected").then(Commands.argument("nonce", StringArgumentType.word())
                                .executes(context -> {
                                    PeerScenario run = run(context.getSource().getServer());
                                    run.fail(context.getSource().getPlayerOrException(), "unexpected_reply");
                                    return 0;
                                })))
                        .then(Commands.literal("report")
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .then(Commands.argument("value", StringArgumentType.word())
                                                .executes(context -> {
                                                    PeerScenario run = run(context.getSource().getServer());
                                                    run.onReport(context.getSource().getPlayerOrException(),
                                                            StringArgumentType.getString(context, "key"),
                                                            StringArgumentType.getString(context, "value"));
                                                    return 1;
                                                }))))));
    }

    public static void tick(MinecraftServer server) {
        PeerScenario run = run(server);
        run.step();
    }

    public static void remove(MinecraftServer server) { RUNS.remove(server); }

    private static PeerScenario run(MinecraftServer server) {
        return RUNS.computeIfAbsent(server, PeerScenario::new);
    }

    private void step() {
        if (finished) { return; }
        age++;
        int phaseTimeout = phase == 0 ? CLIENT_STARTUP_TIMEOUT_TICKS : 1_200;
        if (age - phaseStart > phaseTimeout) { fail("phase_timeout_" + phase); return; }
        try {
            switch (phase) {
                case 0 -> connectBoth();
                case 1 -> companionsCreated();
                case 2 -> waitChatReady();
                case 3 -> waitProactiveOff();
                case 4 -> waitAddresses();
                case 5 -> waitFacts();
                case 6 -> waitPrivateReplies();
                case 7 -> waitSharedCreated();
                case 8 -> waitUninvitedJoinRejected();
                case 9 -> waitUninvitedSendRejected();
                case 10 -> waitInvitationObserved();
                case 11 -> waitMemberJoined();
                case 12 -> waitSharedReplies();
                case 13 -> waitMemberLeft();
                case 14 -> observeAfterLeave();
                case 15 -> waitDismissed();
                case 16 -> waitPrivateClear();
                default -> fail("unexpected_phase");
            }
        } catch (Throwable error) {
            fail("scenario_exception_" + error.getClass().getSimpleName());
        }
    }

    private void connectBoth() {
        a = server.getPlayerList().getPlayerByName(A_NAME);
        b = server.getPlayerList().getPlayerByName(B_NAME);
        if (a == null || b == null || !reports.containsKey("ready_a") || !reports.containsKey("ready_b")) { return; }
        if (a == b || a.getUUID().equals(b.getUUID()) || server.getPlayerList().getPlayers().size() != 2) {
            fail("two_distinct_real_clients_required");
            return;
        }
        CompanionData data = CompanionRuntime.data(server);
        if (data.find(a.getUUID()).isPresent() || data.find(b.getUUID()).isPresent()) {
            fail("fresh_dedicated_world_required");
            return;
        }
        check("two_real_offline_clients", true);
        command(a, "mf companion create MF_PeerA");
        command(b, "mf companion create MF_PeerB");
        advance();
    }

    private void companionsCreated() {
        CompanionData data = CompanionRuntime.data(server);
        CompanionData.Entry ae = data.find(a.getUUID()).orElse(null);
        CompanionData.Entry be = data.find(b.getUUID()).orElse(null);
        if (ae == null || be == null || !ae.active() || !be.active()) { return; }
        aCompanion = ae.identity().companionId();
        bCompanion = be.identity().companionId();
        if (aCompanion.equals(bCompanion) || aCompanion.equals(b.getUUID()) || bCompanion.equals(a.getUUID())) {
            fail("companion_identity_isolation");
            return;
        }
        check("two_independent_companions_created_by_clients", true);
        advance();
    }

    private void waitChatReady() throws ReflectiveOperationException {
        Status as = status(a);
        Status bs = status(b);
        if (!as.memoryLoaded() || !bs.memoryLoaded() || !as.idle() || !bs.idle()) { return; }
        if (!"chat-completions".equals(as.provider()) || !as.provider().equals(bs.provider())
                || as.worldId() == null || !as.worldId().equals(bs.worldId())
                || !aCompanion.equals(as.companionId()) || !bCompanion.equals(bs.companionId())) {
            fail("peer_chat_status_mismatch");
            return;
        }
        command(a, "mf memory proactive false");
        command(b, "mf memory proactive false");
        advance();
    }

    private void waitProactiveOff() throws ReflectiveOperationException {
        if (status(a).proactive() || status(b).proactive()) { return; }
        command(a, "mf memory address " + A_ADDRESS);
        command(b, "mf memory address " + B_ADDRESS);
        advance();
    }

    private void waitAddresses() throws ReflectiveOperationException {
        Status as = status(a);
        Status bs = status(b);
        if (!A_ADDRESS.equals(as.address()) || !B_ADDRESS.equals(bs.address())) { return; }
        check("proactive_disabled_and_private_addresses_set", true);
        command(a, "mf memory remember " + A_FACT);
        command(b, "mf memory remember " + B_FACT);
        advance();
    }

    private void waitFacts() throws ReflectiveOperationException {
        Status as = status(a);
        Status bs = status(b);
        if (!as.memoryLoaded() || !bs.memoryLoaded() || as.memoryCount() < 1 || bs.memoryCount() < 1
                || !as.idle() || !bs.idle()) { return; }
        check("distinct_private_facts_saved", true);
        String an = nonce();
        String bn = nonce();
        expect(an, Set.of(a.getUUID()));
        expect(bn, Set.of(b.getUUID()));
        tell(a, "EXPECT " + an);
        tell(b, "EXPECT " + bn);
        command(a, "mf chat MF_PEER_PRIVATE_A:" + an + " MF_A_PRIVATE_HISTORY");
        chat(b, "@MF_PeerB MF_PEER_PRIVATE_B:" + bn + " MF_B_PRIVATE_HISTORY");
        advance();
    }

    private void waitPrivateReplies() throws ReflectiveOperationException {
        if (!allAcked() || !status(a).idle() || !status(b).idle()) { return; }
        check("private_command_reply_ack_by_owner_a", true);
        check("private_ordinary_prefix_reply_ack_by_owner_b", true);
        sharedId = null;
        command(a, "mf chat shared create");
        advance();
    }

    private void waitSharedCreated() throws ReflectiveOperationException {
        Status as = status(a);
        String parsed = reports.get("created");
        if (as.sharedSessions() != 1 || as.sharedMembers() != 1 || parsed == null) { return; }
        try { sharedId = UUID.fromString(parsed); }
        catch (IllegalArgumentException invalid) { fail("client_parsed_invalid_shared_uuid"); return; }
        check("shared_uuid_parsed_from_real_create_message", true);
        tell(b, "SESSION " + sharedId);
        command(b, "mf chat shared join " + sharedId);
        advance();
    }

    private void waitUninvitedJoinRejected() throws ReflectiveOperationException {
        if (!"true".equals(reports.get("denied_join"))) { return; }
        if (status(a).sharedMembers() != 1) { fail("uninvited_join_changed_membership"); return; }
        check("uninvited_join_rejected_for_real_client_uuid", true);
        command(b, "mf chat shared send " + sharedId + " MF_PEER_UNINVITED_B:" + nonce());
        advance();
    }

    private void waitUninvitedSendRejected() throws ReflectiveOperationException {
        if (!"true".equals(reports.get("denied_send"))) { return; }
        if (status(a).sharedMembers() != 1) { fail("uninvited_send_changed_membership"); return; }
        check("uninvited_shared_send_rejected", true);
        command(a, "mf chat shared invite " + B_NAME);
        advance();
    }

    private void waitInvitationObserved() {
        if (!sharedId.toString().equals(reports.get("invited"))) { return; }
        check("invite_uuid_parsed_by_invited_client", true);
        tell(b, "SESSION " + sharedId);
        command(b, "mf chat shared join " + sharedId);
        advance();
    }

    private void waitMemberJoined() {
        if (!sharedId.toString().equals(reports.get("joined"))) { return; }
        check("invitee_explicitly_joined_by_client_command", true);
        String an = nonce();
        String bn = nonce();
        expect(an, Set.of(a.getUUID(), b.getUUID()));
        expect(bn, Set.of(a.getUUID(), b.getUUID()));
        tell(a, "EXPECT " + an);
        tell(b, "EXPECT " + an);
        tell(a, "EXPECT " + bn);
        tell(b, "EXPECT " + bn);
        command(a, "mf chat shared send " + sharedId + " MF_PEER_SHARED_A:" + an);
        command(b, "mf chat shared send " + sharedId + " MF_PEER_SHARED_B:" + bn);
        advance();
    }

    private void waitSharedReplies() throws ReflectiveOperationException {
        if (!allAcked() || !status(a).idle() || !status(b).idle()) { return; }
        check("both_shared_requests_replied_to_both_real_clients", true);
        command(b, "mf chat shared leave " + sharedId);
        advance();
    }

    private void waitMemberLeft() throws ReflectiveOperationException {
        if (!"true".equals(reports.get("left"))) { return; }
        Status as = status(a);
        if (as.sharedSessions() != 1 || as.sharedMembers() != 1 || !as.companionOnline()) { return; }
        afterLeaveNonce = UUID.fromString(nonce());
        afterLeaveText = "MF_PEER_SHARED_AFTER_LEAVE:" + afterLeaveNonce;
        expect(afterLeaveNonce.toString(), Set.of(a.getUUID()));
        tell(a, "EXPECT " + afterLeaveNonce);
        tell(b, "WATCH echo " + "MF_PEER_SHARED_AFTER_LEAVE");
        tell(b, "WATCH reply " + afterLeaveNonce);
        advance();
    }

    private void observeAfterLeave() throws ReflectiveOperationException {
        if (!"true".equals(reports.get("watch_ready_echo"))
                || !"true".equals(reports.get("watch_ready_reply"))) { return; }
        if (!afterLeaveSent) {
            command(a, "mf chat shared send " + sharedId + " " + afterLeaveText);
            afterLeaveSent = true;
            afterLeaveSentAt = age;
        }
        if (!allAcked()) { return; }
        if (age - afterLeaveSentAt < 100) { return; }
        if (!afterLeaveWatchReportsRequested) {
            tell(b, "REPORT_WATCH echo");
            tell(b, "REPORT_WATCH reply");
            afterLeaveWatchReportsRequested = true;
        }
        String echoCount = reports.get("watch_echo");
        String replyCount = reports.get("watch_reply");
        if (echoCount == null || replyCount == null) { return; }
        if (!"0".equals(echoCount) || !"0".equals(replyCount)) {
            fail("left_client_received_shared_echo_or_reply");
            return;
        }
        check("left_client_saw_no_echo_or_reply_during_observation", true);
        Status as = status(a);
        Status bs = status(b);
        if (!A_ADDRESS.equals(as.address()) || as.memoryCount() < 1 || !as.companionActive()
                || !as.companionOnline() || !aCompanion.equals(as.companionId())
                || as.sharedSessions() != 1 || as.sharedMembers() != 1) {
            fail("owner_a_state_changed_before_b_cleanup");
            return;
        }
        aAddress = as.address();
        aMemoryCount = as.memoryCount();
        bGenerationBeforeClear = bs.generation();
        command(b, "mf companion dismiss");
        advance();
    }

    private void waitDismissed() throws ReflectiveOperationException {
        Status bs = status(b);
        if (bs.companionActive() || bs.companionOnline()) { return; }
        check("client_b_dismissed_only_its_companion", true);
        command(b, "mf memory clear");
        advance();
    }

    private void waitPrivateClear() throws ReflectiveOperationException {
        Status as = status(a);
        Status bs = status(b);
        if (!bs.memoryLoaded() || bs.generation() <= bGenerationBeforeClear || !bs.address().isBlank()
                || bs.memoryCount() != 0 || bs.companionActive() || bs.companionOnline() || !bs.idle()) { return; }
        if (!aAddress.equals(as.address()) || aMemoryCount != as.memoryCount() || !as.companionActive()
                || !as.companionOnline() || !aCompanion.equals(as.companionId()) || !as.idle()
                || as.sharedSessions() != 1 || as.sharedMembers() != 1) {
            fail("client_b_clear_changed_client_a_private_state");
            return;
        }
        if (!"true".equals(reports.get("watch_ready_fact"))) {
            if (reports.containsKey("watch_ready_fact")) { return; }
            if (!aFactWatchRequested) {
                tell(a, "WATCH fact MF_A_PRIVATE_FACT");
                aFactWatchRequested = true;
            }
            return;
        }
        if (!aFactListRequested) {
            command(a, "mf memory list");
            aFactListRequested = true;
            aFactListStart = age;
            return;
        }
        if (age - aFactListStart < 20) { return; }
        if (!aFactWatchReportRequested) {
            tell(a, "REPORT_WATCH fact");
            aFactWatchReportRequested = true;
            return;
        }
        String preservedFactCount = reports.get("watch_fact");
        if (preservedFactCount == null) { return; }
        if (Integer.parseInt(preservedFactCount) < 1) { fail("client_a_private_fact_missing_after_client_b_clear"); return; }
        check("client_b_memory_clear_left_client_a_state_unchanged", true);
        finished = true;
        emit("result", "PASS");
        tell(a, "PASS");
        tell(b, "PASS");
    }

    private void onReady(ServerPlayer sender) {
        if (sender.getGameProfile().getName().equals(A_NAME)) { reports.put("ready_a", "true"); }
        else if (sender.getGameProfile().getName().equals(B_NAME)) { reports.put("ready_b", "true"); }
        else { fail(sender, "unexpected_client_identity"); }
    }

    private void onAck(ServerPlayer sender, String nonceText) {
        Set<UUID> expected = expectedAcks.get(nonceText);
        if (expected == null || !expected.contains(sender.getUUID())) {
            fail(sender, "nonce_ack_sender_or_value_mismatch");
            return;
        }
        receivedAcks.computeIfAbsent(nonceText, ignored -> new HashSet<>()).add(sender.getUUID());
    }

    private void onReport(ServerPlayer sender, String key, String value) {
        boolean fromA = sender.getUUID().equals(a == null ? null : a.getUUID());
        boolean fromB = sender.getUUID().equals(b == null ? null : b.getUUID());
        if (!fromA && !fromB) { fail(sender, "report_from_unregistered_sender"); return; }
        boolean valid = switch (key) {
            case "created" -> phase == 7 && fromA && validUuid(value);
            case "invited" -> phase == 10 && fromB && validUuid(value);
            case "denied_join" -> phase == 8 && fromB && "true".equals(value);
            case "denied_send" -> phase == 9 && fromB && "true".equals(value);
            case "joined" -> phase == 11 && fromB && validUuid(value);
            case "left" -> phase == 13 && fromB && "true".equals(value);
            case "watch_ready_echo", "watch_ready_reply" -> phase == 14 && fromB && "true".equals(value);
            case "watch_echo", "watch_reply" -> phase == 14 && fromB && value.matches("[0-9]{1,3}");
            case "watch_ready_fact" -> phase == 16 && fromA && "true".equals(value);
            case "watch_fact" -> phase == 16 && fromA && value.matches("[0-9]{1,3}");
            default -> false;
        };
        if (!valid || reports.putIfAbsent(key, value) != null) { fail(sender, "invalid_or_duplicate_client_report"); }
    }

    private static boolean validUuid(String value) {
        try { UUID.fromString(value); return true; }
        catch (IllegalArgumentException invalid) { return false; }
    }

    private void expect(String nonce, Set<UUID> recipients) {
        expectedAcks.put(nonce, Set.copyOf(recipients));
    }

    private boolean allAcked() {
        return expectedAcks.entrySet().stream().allMatch(entry ->
                receivedAcks.getOrDefault(entry.getKey(), Set.of()).containsAll(entry.getValue()));
    }

    private static Status status(ServerPlayer player) throws ReflectiveOperationException {
        Class<?> runtime = Class.forName("io.github.yfyan.minecraftfriends.chat.ChatRuntime");
        Object value = runtime.getMethod("status", MinecraftServer.class, UUID.class)
                .invoke(null, player.server, player.getUUID());
        Class<?> type = value.getClass();
        return new Status((String) type.getMethod("providerId").invoke(value),
                (UUID) type.getMethod("worldId").invoke(value),
                (UUID) type.getMethod("companionId").invoke(value),
                (boolean) type.getMethod("companionActive").invoke(value),
                (boolean) type.getMethod("companionOnline").invoke(value),
                (boolean) type.getMethod("memoryLoaded").invoke(value),
                (long) type.getMethod("memoryGeneration").invoke(value),
                (int) type.getMethod("memoryCount").invoke(value),
                (String) type.getMethod("preferredAddress").invoke(value),
                (boolean) type.getMethod("proactiveEnabled").invoke(value),
                (int) type.getMethod("inFlightCount").invoke(value),
                (int) type.getMethod("queuedCount").invoke(value),
                (int) type.getMethod("sharedSessionCount").invoke(value),
                (int) type.getMethod("sharedMemberCount").invoke(value));
    }

    private void command(ServerPlayer player, String value) { tell(player, "COMMAND " + value); }
    private void chat(ServerPlayer player, String value) { tell(player, "CHAT " + value); }
    private void tell(ServerPlayer player, String value) {
        if (player != null && player.isAlive()) { player.sendSystemMessage(Component.literal("MF_PEER " + value)); }
    }
    private String nonce() { return UUID.randomUUID().toString(); }

    private void advance() { phase++; phaseStart = age; }

    private void check(String name, boolean condition) {
        if (!condition) { fail(name); return; }
        emit(name, "PASS");
    }

    private void fail(ServerPlayer player, String reason) {
        fail(reason);
        tell(player, "FAIL " + reason);
    }

    private void fail(String reason) {
        if (finished) { return; }
        finished = true;
        emit("result", "FAIL " + reason);
        tell(a, "FAIL " + reason);
        tell(b, "FAIL " + reason);
    }

    private void emit(String check, String result) {
        System.out.println("MF_PEER_ACCEPTANCE_SERVER " + server.getClass().getName() + " " + check + " " + result);
        if ("PASS".equals(result)) {
            tell(a, "ASSERT " + check);
            tell(b, "ASSERT " + check);
        }
    }
}
