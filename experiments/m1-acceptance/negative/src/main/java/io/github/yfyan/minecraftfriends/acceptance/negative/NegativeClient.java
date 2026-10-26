package io.github.yfyan.minecraftfriends.acceptance.negative;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Drives a fresh offline dev-client world and ACKs only observed system feedback. */
public final class NegativeClient {
    private static final ConcurrentLinkedQueue<String> MESSAGES = new ConcurrentLinkedQueue<>();
    private static final String WORLD = "mf-negative-" + UUID.randomUUID();
    private static final String SCENARIO = System.getProperty("mf.acceptance.negative.scenario", "")
            .toUpperCase(Locale.ROOT);
    private static String expectedNonce;
    private static String expectedOperation;
    private static Set<String> expectedMessages = Set.of();
    private static final Set<String> receivedMessages = new HashSet<>();
    private static boolean worldRequested;
    private static boolean finished;

    private NegativeClient() { }

    /** Called by Fabric GAME and Forge SystemMessageReceivedEvent for real system messages. */
    public static void message(String text) {
        if (text == null || text.length() > 512) {
            return;
        }
        if (text.startsWith("MF_NEGATIVE ") || isRelevantFeedback(text)) {
            while (MESSAGES.size() >= 64) {
                MESSAGES.poll();
            }
            MESSAGES.add(text);
        }
    }

    /** Called from the actual client tick hook installed by the test-only loader adapter. */
    public static void tick(Minecraft client) {
        if (finished) {
            return;
        }
        String text;
        while ((text = MESSAGES.poll()) != null) {
            if (text.startsWith("MF_NEGATIVE COMMAND ")) {
                if (!receiveCommand(client, text.substring("MF_NEGATIVE COMMAND ".length()))) {
                    return;
                }
            } else if (text.startsWith("MF_NEGATIVE COMPLETE ")) {
                String completedScenario = text.substring("MF_NEGATIVE COMPLETE ".length()).trim();
                if (!SCENARIO.equals(completedScenario)) {
                    fail(client, "completion_scenario_mismatch");
                    return;
                }
                finish(client, completedScenario);
                return;
            } else if (text.startsWith("MF_NEGATIVE FAIL ")) {
                fail(client, "server_assertion_failed");
                return;
            } else if (expectedNonce != null && isExpectedMessage(text)) {
                receivedMessages.add(matchedExpectation(text));
                if (receivedMessages.containsAll(expectedMessages)) {
                    if (client.player == null) {
                        fail(client, "feedback_without_player");
                        return;
                    }
                    client.player.connection.sendCommand("mfnegative ack " + expectedNonce);
                    expectedNonce = null;
                    expectedOperation = null;
                    expectedMessages = Set.of();
                    receivedMessages.clear();
                }
            }
        }

        if (worldRequested || client.getOverlay() != null || !(client.screen instanceof TitleScreen)) {
            return;
        }
        if (!usesExpectedGameDirectory(client)) {
            fail(client, "isolated_run_dir_mismatch");
            return;
        }
        worldRequested = true;
        var settings = new LevelSettings("Minecraft Friends Negative Acceptance", GameType.SURVIVAL,
                false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
        client.createWorldOpenFlows().createFreshLevel(WORLD, settings,
                new WorldOptions(97531L, false, false),
                registry -> registry.registryOrThrow(Registries.WORLD_PRESET)
                        .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                client.screen);
    }

    private static boolean receiveCommand(Minecraft client, String payload) {
        if (expectedNonce != null || client.player == null || client.level == null) {
            fail(client, "command_without_ready_player_or_previous_ack");
            return false;
        }
        String[] fields = payload.split(" ", 2);
        if (fields.length != 2) {
            fail(client, "malformed_command_request");
            return false;
        }
        try {
            expectedNonce = UUID.fromString(fields[0]).toString();
        } catch (IllegalArgumentException invalidNonce) {
            fail(client, "invalid_nonce");
            return false;
        }
        expectedOperation = fields[1];
        String command;
        switch (expectedOperation) {
            case "CREATE_DISABLED" -> {
                if (!"DISABLED".equals(SCENARIO)) { return failOperation(client, "scenario_operation_mismatch"); }
                command = "mf companion create";
                expectedMessages = Set.of("Minecraft Friends 已在配置中关闭");
            }
            case "CHAT_DISABLED" -> {
                if (!"DISABLED".equals(SCENARIO)) { return failOperation(client, "scenario_operation_mismatch"); }
                command = "mf chat MF_NEGATIVE_CHAT_PROBE";
                expectedMessages = Set.of("聊天功能当前已关闭：模组功能已关闭");
            }
            case "CREATE_CORRUPT" -> {
                if (!"CORRUPT".equals(SCENARIO)) { return failOperation(client, "scenario_operation_mismatch"); }
                command = "mf companion create";
                expectedMessages = Set.of("Existing companion data is unreadable or invalid; refusing to replace it.");
            }
            case "STATUS_CORRUPT" -> {
                if (!"CORRUPT".equals(SCENARIO)) { return failOperation(client, "scenario_operation_mismatch"); }
                command = "mf chat status";
                expectedMessages = Set.of("聊天 已关闭 / 全局功能 已启用", "关闭原因：伙伴数据文件不可用，聊天与记忆已关闭");
            }
            case "CHAT_CORRUPT" -> {
                if (!"CORRUPT".equals(SCENARIO)) { return failOperation(client, "scenario_operation_mismatch"); }
                command = "mf chat MF_NEGATIVE_CHAT_PROBE";
                expectedMessages = Set.of("聊天功能当前已关闭：伙伴数据文件不可用，聊天与记忆已关闭");
            }
            default -> { return failOperation(client, "unknown_operation"); }
        }
        receivedMessages.clear();
        client.player.connection.sendCommand(command);
        return true;
    }

    private static boolean isExpectedMessage(String text) {
        for (String expected : expectedMessages) {
            if (text.contains(expected)) {
                return true;
            }
        }
        return false;
    }

    private static String matchedExpectation(String text) {
        for (String expected : expectedMessages) {
            if (text.contains(expected)) {
                return expected;
            }
        }
        return "";
    }

    private static boolean isRelevantFeedback(String text) {
        return text.contains("Minecraft Friends 已在配置中关闭")
                || text.contains("聊天功能当前已关闭：")
                || text.contains("Existing companion data is unreadable or invalid; refusing to replace it.")
                || text.contains("聊天 已关闭 / 全局功能 已启用")
                || text.contains("关闭原因：伙伴数据文件不可用，聊天与记忆已关闭");
    }

    private static boolean usesExpectedGameDirectory(Minecraft client) {
        String configured = System.getProperty("mf.acceptance.negative.runDir", "");
        if (configured.isBlank()) {
            return false;
        }
        try {
            Path expected = Path.of(configured).toRealPath();
            Path actual = client.gameDirectory.toPath().toRealPath();
            return actual.equals(expected);
        } catch (IOException | RuntimeException invalidPath) {
            return false;
        }
    }

    private static boolean failOperation(Minecraft client, String reason) {
        fail(client, reason);
        return false;
    }

    private static void finish(Minecraft client, String scenario) {
        finished = true;
        System.out.println("MF_NEGATIVE_CLIENT PASS " + scenario);
        client.stop();
    }

    private static void fail(Minecraft client, String reason) {
        if (finished) {
            return;
        }
        finished = true;
        System.out.println("MF_NEGATIVE_CLIENT FAIL reason=" + reason);
        client.stop();
    }
}
