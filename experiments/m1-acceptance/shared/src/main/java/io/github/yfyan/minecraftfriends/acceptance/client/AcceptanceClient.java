package io.github.yfyan.minecraftfriends.acceptance.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Automated real client command path in a fresh isolated world or loopback test server. */
public final class AcceptanceClient {
    private static final ConcurrentLinkedQueue<String> MESSAGES = new ConcurrentLinkedQueue<>();
    private static final String WORLD = "mf-m1-acceptance-" + UUID.randomUUID();
    private static final String SERVER = System.getProperty("mf.acceptance.server", "");
    private static String expectedNonce;
    private static String expectedSaveNonce;
    private static boolean started;
    private static boolean reopening;
    private static boolean lifecyclePassed;
    private static boolean finished;
    private static int diagnosticTicks;
    private static int waiting;

    public static void message(String text) {
        if (text.startsWith("MF_ACCEPTANCE ") || text.startsWith("MF_M1Bot：")
                || text.equals("已保存这条明确偏好。")) {
            if (MESSAGES.size() < 128) { MESSAGES.add(text); }
        }
    }
    public static void tick(Minecraft client) {
        if (finished) { return; }
        if (++diagnosticTicks % 100 == 1) {
            System.out.println("MF_ACCEPTANCE_SCREEN "
                    + (client.screen == null ? "none" : client.screen.getClass().getName())
                    + " overlay=" + (client.getOverlay() == null ? "none" : client.getOverlay().getClass().getName()));
        }
        String text;
        while ((text = MESSAGES.poll()) != null) {
            if (text.startsWith("MF_ACCEPTANCE EXPECT_SAVE ")) {
                String nonce = text.substring("MF_ACCEPTANCE EXPECT_SAVE ".length());
                try { expectedSaveNonce = UUID.fromString(nonce).toString(); }
                catch (IllegalArgumentException invalid) { fail(client, "invalid_save_nonce"); return; }
            } else if (expectedSaveNonce != null && text.equals("已保存这条明确偏好。")) {
                if (client.player == null) { fail(client, "save_without_player"); return; }
                client.player.connection.sendCommand("mfacceptance ack " + expectedSaveNonce);
                expectedSaveNonce = null;
            } else if (text.startsWith("MF_ACCEPTANCE EXPECT ")) {
                String nonce = text.substring("MF_ACCEPTANCE EXPECT ".length());
                try { expectedNonce = UUID.fromString(nonce).toString(); }
                catch (IllegalArgumentException invalid) { fail(client, "invalid_expected_nonce"); return; }
            } else if (expectedNonce != null && text.equals("MF_M1Bot：协议模拟，非真实模型。中文伙伴回复 MF_CHAT_REPLY:" + expectedNonce)) {
                if (client.player == null) { fail(client, "reply_without_player"); return; }
                client.player.connection.sendCommand("mfacceptance ack " + expectedNonce);
                expectedNonce = null;
            } else if (text.startsWith("MF_ACCEPTANCE CHAT ")) {
                if (client.player == null) { fail(client, "chat_without_player"); return; }
                client.player.connection.sendChat(text.substring("MF_ACCEPTANCE CHAT ".length()));
            } else if (text.startsWith("MF_ACCEPTANCE COMMAND ")) {
                if (client.player == null) { fail(client, "command_without_player"); return; }
                client.player.connection.sendCommand(text.substring("MF_ACCEPTANCE COMMAND ".length()));
            } else if (text.equals("MF_ACCEPTANCE LIFECYCLE_PASS")) {
                lifecyclePassed = true;
                if (SERVER.isEmpty()) {
                    client.level.disconnect();
                    client.disconnect(new TitleScreen());
                    reopening = true;
                } else {
                    finish(client, "DEDICATED_LIFECYCLE_PASS");
                }
            } else if (text.equals("MF_ACCEPTANCE CHAT_REOPEN_READY")) {
                lifecyclePassed = true;
                client.level.disconnect();
                client.disconnect(new TitleScreen());
                reopening = true;
                waiting = 0;
            } else if (text.equals("MF_ACCEPTANCE CHAT_MEMORY_PASS")) {
                finish(client, SERVER.isEmpty() ? "INTEGRATED_M1_CHAT_MEMORY_PASS" : "DEDICATED_M1_CHAT_MEMORY_PASS");
            } else if (text.equals("MF_ACCEPTANCE RESTORE_PASS")) {
                finish(client, "INTEGRATED_LIFECYCLE_REOPEN_PASS");
            } else if (text.startsWith("MF_ACCEPTANCE FAIL")) {
                fail(client, text);
            }
        }
        if (client.getOverlay() != null || !(client.screen instanceof TitleScreen)) { return; }
        if (reopening && client.level == null) {
            if (++waiting < 40) { return; }
            reopening = false;
            if (SERVER.isEmpty()) {
                client.createWorldOpenFlows().openWorld(WORLD, () -> fail(client, "world_reopen_cancelled"));
            } else {
                ConnectScreen.startConnecting(new TitleScreen(), client, ServerAddress.parseString(SERVER),
                        new ServerData("Isolated M1 acceptance", SERVER, ServerData.Type.OTHER), false, null);
            }
            return;
        }
        if (started || lifecyclePassed) { return; }
        started = true;
        if (!SERVER.isEmpty()) {
            if (!SERVER.startsWith("127.0.0.1:") && !SERVER.startsWith("localhost:")) {
                fail(client, "only_loopback_test_servers_are_allowed");
                return;
            }
            ConnectScreen.startConnecting(new TitleScreen(), client, ServerAddress.parseString(SERVER),
                    new ServerData("Isolated M1 acceptance", SERVER, ServerData.Type.OTHER), false, null);
            return;
        }
        var settings = new LevelSettings("Minecraft Friends M1 Acceptance", GameType.SURVIVAL, false,
                Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
        client.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(4321L, false, false),
                registry -> registry.registryOrThrow(Registries.WORLD_PRESET)
                        .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), client.screen);
    }
    private static void finish(Minecraft client, String result) {
        finished = true;
        System.out.println("MF_ACCEPTANCE_CLIENT PASS " + result);
        client.stop();
    }
    private static void fail(Minecraft client, String reason) {
        finished = true;
        System.out.println("MF_ACCEPTANCE_CLIENT FAIL " + reason);
        client.stop();
    }
}
