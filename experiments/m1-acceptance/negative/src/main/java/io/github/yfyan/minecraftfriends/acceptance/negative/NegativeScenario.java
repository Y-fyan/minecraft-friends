package io.github.yfyan.minecraftfriends.acceptance.negative;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.CommandDispatcher;
import io.github.yfyan.minecraftfriends.companion.CompanionRuntime;
import net.minecraft.SharedConstants;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Real-network negative checks for a fresh, disposable IntegratedServer world. */
public final class NegativeScenario {
    private static final String DATA_FILE = "minecraft_friends_companions.dat";
    private static final String COMPANION_DATA_REASON = "伙伴数据文件不可用，聊天与记忆已关闭";
    private static final String DISABLED_REASON = "模组功能已关闭";
    private static final String OWNER_NAME = System.getProperty("mf.acceptance.negative.username", "MF_NegativeOwner");
    private static final Map<MinecraftServer, NegativeScenario> RUNS = new IdentityHashMap<>();

    private enum Scenario { DISABLED, CORRUPT }

    private final MinecraftServer server;
    private final String configuredScenario;
    private Scenario scenario;
    private ServerPlayer owner;
    private UUID ownerId;
    private Path companionDataFile;
    private String initialDataHash;
    private List<String> operations = List.of();
    private int operationIndex;
    private int age;
    private int operationStartedAt;
    private UUID expectedNonce;
    private UUID acknowledgedNonce;
    private String failureReason;
    private boolean failureSent;
    private boolean prepared;
    private boolean finished;

    private record ChatSnapshot(boolean globalEnabled, boolean enabled, String disabledReason,
                                UUID companionId, boolean companionDataAvailable, String providerId) { }

    private NegativeScenario(MinecraftServer server, String configuredScenario) {
        this.server = server;
        this.configuredScenario = configuredScenario;
    }

    /** Must run at SERVER_STARTING, before the production ChatRuntime server-start callback. */
    public static void prepare(MinecraftServer server) {
        if (!Boolean.getBoolean("mf.acceptance.negative")) {
            return;
        }
        NegativeScenario run = new NegativeScenario(server,
                System.getProperty("mf.acceptance.negative.scenario", ""));
        RUNS.put(server, run);
        try {
            run.prepareWorld();
        } catch (IOException | RuntimeException failure) {
            run.fail("prepare_refused_or_io_error");
        }
    }

    /** Registers an ACK that is accepted only from the selected real player and current nonce. */
    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        if (!Boolean.getBoolean("mf.acceptance.negative")) {
            return;
        }
        dispatcher.register(Commands.literal("mfnegative")
                .then(Commands.literal("ack")
                        .then(Commands.argument("nonce", StringArgumentType.word())
                                .executes(context -> {
                                    ServerPlayer sender = context.getSource().getPlayerOrException();
                                    NegativeScenario run = RUNS.get(sender.server);
                                    if (run == null || run.ownerId == null || run.expectedNonce == null
                                            || run.failureReason != null || run.finished
                                            || !sender.getUUID().equals(run.ownerId)) {
                                        return 0;
                                    }
                                    String nonce = StringArgumentType.getString(context, "nonce");
                                    if (!run.expectedNonce.toString().equals(nonce)) {
                                        return 0;
                                    }
                                    run.acknowledgedNonce = run.expectedNonce;
                                    run.emit("MF_NEGATIVE_SERVER ACK PASS op=" + run.currentOperation()
                                            + " sourceUuidVerified=true nonceVerified=true");
                                    return 1;
                                }))));
    }

    /** Advances checks and emits actual client commands over the IntegratedServer connection. */
    public static void tick(MinecraftServer server) {
        NegativeScenario run = RUNS.get(server);
        if (run != null) {
            run.step();
        }
    }

    /** Runs after the world has stopped; verifies the corrupt sample survived shutdown byte-for-byte. */
    public static void remove(MinecraftServer server) {
        NegativeScenario run = RUNS.remove(server);
        if (run == null) {
            return;
        }
        if (run.scenario == Scenario.CORRUPT && run.initialDataHash != null) {
            if (!run.checkDataHash("corrupt_data_unchanged_after_stop")) {
                return;
            }
        }
        if (!run.finished || run.failureReason != null) {
            run.emit("MF_NEGATIVE_SERVER STOP FAIL scenario=" + run.safeScenarioName()
                    + " reason=stopped_before_completion");
            return;
        }
        run.emit("MF_NEGATIVE_SERVER STOP PASS scenario=" + run.safeScenarioName()
                + " dataSha256=" + (run.initialDataHash == null ? "none" : run.initialDataHash));
    }

    private void prepareWorld() throws IOException {
        try {
            scenario = Scenario.valueOf(configuredScenario.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            fail("invalid_scenario");
            return;
        }
        boolean integrated = server instanceof IntegratedServer;
        if (!check("integrated_server_instance", integrated)) {
            return;
        }

        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        Path expectedRunDirectory = Path.of(System.getProperty("mf.acceptance.negative.runDir", ""));
        if (expectedRunDirectory.toString().isBlank()) {
            fail("isolated_run_dir_property_missing");
            return;
        }
        Path expectedSaves = expectedRunDirectory.toRealPath().resolve("saves");
        if (!check("world_path_isolated", worldRoot.toRealPath().startsWith(expectedSaves))) {
            return;
        }
        Path worldFolder = worldRoot.getFileName();
        String worldName = worldFolder == null ? "" : worldFolder.toString();
        if (!check("world_name_safe", worldName.startsWith("mf-negative-"))) {
            return;
        }

        companionDataFile = worldRoot.resolve("data").resolve(DATA_FILE);
        if (!check("target_was_absent", Files.notExists(companionDataFile, LinkOption.NOFOLLOW_LINKS))) {
            return;
        }

        if (scenario == Scenario.CORRUPT) {
            writeUnknownSchemaSample(worldRoot.resolve("data"));
            initialDataHash = hash(Files.readAllBytes(companionDataFile));
            if (!check("corrupt_data_sample_written", true)) {
                return;
            }
        }
        prepared = failureReason == null;
        if (prepared) {
            emit("MF_NEGATIVE_SERVER PREPARE PASS scenario=" + scenario.name()
                    + " world=" + worldName
                    + " role=integrated serverClass=" + server.getClass().getName()
                    + " targetInitiallyAbsent=true dataSha256="
                    + (initialDataHash == null ? "none" : initialDataHash));
        }
    }

    private void writeUnknownSchemaSample(Path dataDirectory) throws IOException {
        Files.createDirectories(dataDirectory);
        if (Files.exists(companionDataFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("target appeared before safe sample creation");
        }

        CompoundTag payload = new CompoundTag();
        payload.putInt("schemaVersion", 999);
        payload.put("companions", new ListTag());
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        root.put("data", payload);

        Path temporary = Files.createTempFile(dataDirectory, ".mf-negative-", ".dat.tmp");
        try {
            NbtIo.writeCompressed(root, temporary);
            // Files.copy without REPLACE_EXISTING preserves CREATE_NEW semantics for the target.
            Files.copy(temporary, companionDataFile);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void step() {
        age++;
        if (failureReason != null) {
            sendFailureWhenOwnerAppears();
            return;
        }
        if (finished) {
            return;
        }
        if (!prepared) {
            fail("scenario_not_prepared");
            return;
        }
        if (age > 1_200) {
            fail("scenario_timeout");
            return;
        }

        if (owner == null) {
            owner = server.getPlayerList().getPlayers().stream()
                    .filter(player -> OWNER_NAME.equalsIgnoreCase(player.getGameProfile().getName()))
                    .filter(player -> !CompanionRuntime.isCompanion(player))
                    .findFirst().orElse(null);
            if (owner == null) {
                return;
            }
            ownerId = owner.getUUID();
            check("owner_login", true);
        }
        if (!server.isRunning()) {
            fail("server_not_running");
            return;
        }

        ChatSnapshot status;
        try {
            status = readStatus();
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            fail("public_status_api_unavailable");
            return;
        }
        if (status.disabledReason().contains("服务端聊天运行时尚未启动")) {
            return;
        }

        if (operations.isEmpty()) {
            if (!verifyInitialStatus(status)) {
                return;
            }
            operations = scenario == Scenario.DISABLED
                    ? List.of("CREATE_DISABLED", "CHAT_DISABLED")
                    : List.of("CREATE_CORRUPT", "STATUS_CORRUPT", "CHAT_CORRUPT");
            issueNextOperation();
            return;
        }

        if (expectedNonce != null && expectedNonce.equals(acknowledgedNonce)) {
            if (!verifyAfterCommand(status)) {
                return;
            }
            operationIndex++;
            expectedNonce = null;
            acknowledgedNonce = null;
            issueNextOperation();
        } else if (expectedNonce != null && age - operationStartedAt > 200) {
            fail("client_feedback_or_nonce_ack_timeout");
        }
    }

    private boolean verifyInitialStatus(ChatSnapshot status) {
        if (!check("status_chat_disabled", !status.enabled())) {
            return false;
        }
        if (!check("provider_not_initialized", "disabled".equals(status.providerId()))) {
            return false;
        }
        if (!check("status_no_companion", status.companionId() == null && hasNoCompanionEntity())) {
            return false;
        }
        if (scenario == Scenario.DISABLED) {
            if (!check("status_global_disabled", !status.globalEnabled())) {
                return false;
            }
            if (!check("companion_data_available", status.companionDataAvailable())) {
                return false;
            }
            if (!check("status_disabled_reason", status.disabledReason().contains(DISABLED_REASON))) {
                return false;
            }
        } else {
            if (!check("status_global_enabled_for_data_failure", status.globalEnabled())) {
                return false;
            }
            if (!check("status_data_unavailable", !status.companionDataAvailable())) {
                return false;
            }
            if (!check("status_disabled_reason", status.disabledReason().contains(COMPANION_DATA_REASON))) {
                return false;
            }
            if (!checkDataHash("corrupt_data_unchanged_after_start")) {
                return false;
            }
        }
        return true;
    }

    private boolean verifyAfterCommand(ChatSnapshot status) {
        if (!check("player_remained_connected", isOwnerConnected())) {
            return false;
        }
        if (!check("no_companion_after_command", !status.enabled() && status.companionId() == null
                && hasNoCompanionEntity())) {
            return false;
        }
        if (scenario == Scenario.DISABLED) {
            if (!check("disabled_state_preserved", !status.globalEnabled()
                    && status.disabledReason().contains(DISABLED_REASON))) {
                return false;
            }
        } else {
            if (!check("corrupt_data_safe_state_preserved", status.globalEnabled()
                    && !status.companionDataAvailable()
                    && status.disabledReason().contains(COMPANION_DATA_REASON))) {
                return false;
            }
            if (!checkDataHash("corrupt_data_unchanged_during_run")) {
                return false;
            }
        }
        return true;
    }

    private void issueNextOperation() {
        if (operationIndex >= operations.size()) {
            finished = true;
            emit("MF_NEGATIVE_SERVER RESULT PASS scenario=" + scenario.name());
            owner.sendSystemMessage(Component.literal("MF_NEGATIVE COMPLETE " + scenario.name()));
            return;
        }
        expectedNonce = UUID.randomUUID();
        acknowledgedNonce = null;
        operationStartedAt = age;
        owner.sendSystemMessage(Component.literal("MF_NEGATIVE COMMAND " + expectedNonce + " "
                + operations.get(operationIndex)));
    }

    private String currentOperation() {
        return operationIndex < operations.size() ? operations.get(operationIndex) : "UNKNOWN";
    }

    private boolean isOwnerConnected() {
        return owner != null && ownerId != null && server.getPlayerList().getPlayer(ownerId) == owner;
    }

    private boolean hasNoCompanionEntity() {
        return server.getPlayerList().getPlayers().stream().noneMatch(CompanionRuntime::isCompanion);
    }

    private ChatSnapshot readStatus() throws ReflectiveOperationException {
        Class<?> runtime = Class.forName("io.github.yfyan.minecraftfriends.chat.ChatRuntime");
        Object status = runtime.getMethod("status", MinecraftServer.class, UUID.class)
                .invoke(null, server, ownerId);
        Class<?> type = status.getClass();
        return new ChatSnapshot((boolean) method(type, status, "globalEnabled"),
                (boolean) method(type, status, "enabled"),
                (String) method(type, status, "disabledReason"),
                (UUID) method(type, status, "companionId"),
                (boolean) method(type, status, "companionDataAvailable"),
                (String) method(type, status, "providerId"));
    }

    private static Object method(Class<?> type, Object target, String name) throws ReflectiveOperationException {
        Method method = type.getMethod(name);
        return method.invoke(target);
    }

    private boolean checkDataHash(String name) {
        try {
            boolean matches = Files.isRegularFile(companionDataFile)
                    && initialDataHash.equals(hash(Files.readAllBytes(companionDataFile)));
            return check(name, matches);
        } catch (IOException failure) {
            return check(name, false);
        }
    }

    private boolean check(String name, boolean condition) {
        if (!condition) {
            fail("assertion_" + name);
            return false;
        }
        emit("MF_NEGATIVE_SERVER CHECK " + name + " PASS");
        return true;
    }

    private void fail(String reason) {
        if (failureReason != null) {
            return;
        }
        failureReason = reason;
        emit("MF_NEGATIVE_SERVER FAIL reason=" + reason);
        sendFailureWhenOwnerAppears();
    }

    private void sendFailureWhenOwnerAppears() {
        if (failureSent) {
            return;
        }
        if (owner == null) {
            owner = server.getPlayerList().getPlayers().stream()
                    .filter(player -> OWNER_NAME.equalsIgnoreCase(player.getGameProfile().getName()))
                    .filter(player -> !CompanionRuntime.isCompanion(player))
                    .findFirst().orElse(null);
            if (owner != null) {
                ownerId = owner.getUUID();
            }
        }
        if (owner != null) {
            owner.sendSystemMessage(Component.literal("MF_NEGATIVE FAIL " + failureReason));
            failureSent = true;
        }
    }

    private String safeScenarioName() {
        return scenario == null ? "UNKNOWN" : scenario.name();
    }

    private void emit(String line) {
        System.out.println(line);
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
