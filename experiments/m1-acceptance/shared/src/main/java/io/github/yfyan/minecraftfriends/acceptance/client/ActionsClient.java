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
public final class ActionsClient {
    private static final ConcurrentLinkedQueue<String> MESSAGES = new ConcurrentLinkedQueue<>();
    private static final String WORLD = "mf-m2-acceptance-" + UUID.randomUUID();
    private static final String SERVER = System.getProperty("mf.acceptance.server", "");
    private static boolean started;
    private static boolean finished;

    public static void message(String text) {
        if (text != null && text.startsWith("MF_M2 ") && text.length() <= 512 && MESSAGES.size() < 128) { MESSAGES.add(text); }
    }
    public static void tick(Minecraft client) {
        client.options.pauseOnLostFocus = false;
        if (client.level != null && client.screen instanceof net.minecraft.client.gui.screens.PauseScreen) { client.setScreen(null); }
        if (finished) { return; }
        String text;
        while ((text = MESSAGES.poll()) != null) {
            if (text.startsWith("MF_M2 CHAT ")) {
                String chat=text.substring("MF_M2 CHAT ".length());
                if(client.player==null || !chat.startsWith("@MF_M2Bot ") || chat.length()>256) {fail(client,"invalid_addressed_chat");return;}
                client.player.connection.sendChat(chat);
                System.out.println("MF_M2_CLIENT COMMAND_SENT");
            } else if (text.startsWith("MF_M2 COMMAND ")) {
                String command = text.substring("MF_M2 COMMAND ".length());
                if (command.length() > 256 || !(command.startsWith("mf task ")
                        || command.equals("mf companion create MF_M2Bot")
                        || command.startsWith("mf chat "))) {
                    fail(client, "command_not_allowlisted"); return;
                }
                if (client.player == null) { fail(client, "command_without_player"); return; }
                client.player.connection.sendCommand(command);
                System.out.println("MF_M2_CLIENT COMMAND_SENT");
            } else if (text.equals("MF_M2 PASS")) {
                finish(client, "M2_PASS"); return;
            } else if (text.startsWith("MF_M2 FAIL ")) {
                fail(client, text.substring("MF_M2 FAIL ".length())); return;
            }
        }
        if (client.getOverlay() != null || !(client.screen instanceof TitleScreen)) { return; }
        if (started) { return; }
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
        var settings = new LevelSettings("Minecraft Friends M2 Acceptance", GameType.SURVIVAL, false,
                Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
        client.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(4321L, false, false),
                registry -> registry.registryOrThrow(Registries.WORLD_PRESET)
                        .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), client.screen);
    }
    private static void finish(Minecraft client, String result) {
        finished = true;
        System.out.println("MF_M2_CLIENT PASS " + result);
        client.stop();
    }
    private static void fail(Minecraft client, String reason) {
        finished = true;
        System.out.println("MF_M2_CLIENT FAIL " + reason);
        client.stop();
    }
}
