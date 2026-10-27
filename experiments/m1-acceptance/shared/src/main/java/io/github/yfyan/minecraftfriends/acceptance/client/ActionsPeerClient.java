package io.github.yfyan.minecraftfriends.acceptance.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Explicit loopback only; retain control markers, never ordinary chat. */
public final class ActionsPeerClient {
    private static final String SERVER = System.getProperty("mf.acceptance.server", "");
    private static final ConcurrentLinkedQueue<String> IN = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<String> OUT = new ConcurrentLinkedQueue<>();
    private static boolean connecting, ready, finished, overflow;
    private static String role = "unknown", username = "unknown";
    private static int cooldown, ticks;
    private ActionsPeerClient() { }
    public static void message(String text) {
        if (text == null || !text.startsWith("MF_M2_PEER ") || text.length() > 256) return;
        if (IN.size() >= 32) { overflow = true; return; }
        IN.add(text.substring("MF_M2_PEER ".length()));
    }
    public static void message(String text, boolean systemMessage) { if (systemMessage) message(text); }
    public static void tick(Minecraft client) {
        if (finished) return;
        client.options.pauseOnLostFocus = false;
        if (client.level != null && client.screen instanceof net.minecraft.client.gui.screens.PauseScreen) client.setScreen(null);
        if (overflow || ++ticks > 24_000) { fail(client,"queue_or_time_limit"); return; }
        if (client.player != null && client.level != null && !ready) {
            username = client.player.getGameProfile().getName();
            role = username.equals("MF_M2_A") ? "a" : username.equals("MF_M2_B") ? "b" : "unknown";
            if (role.equals("unknown") || !loopback(SERVER) || client.hasSingleplayerServer()) {
                fail(client,"identity_or_dedicated_loopback_required"); return;
            }
            enqueue("mfacceptance actionspeer ready"); ready = true;
            System.out.println("MF_M2_PEER_CLIENT READY role=" + role + " username=" + username);
        }
        String control;
        while ((control = IN.poll()) != null) {
            if (!ready || client.player == null) { fail(client,"control_before_ready"); return; }
            if (control.equals("PASS")) {
                finished = true;
                System.out.println("MF_M2_PEER_CLIENT PASS role=" + role + " username=" + username);
                client.stop(); return;
            }
            if (control.startsWith("FAIL ")) { fail(client,control.substring(5)); return; }
            if (!control.startsWith("COMMAND ")) { fail(client,"unknown_control"); return; }
            String[] parts = control.split(" ",3);
            try {
                if (parts.length != 3 || !UUID.fromString(parts[1]).toString().equals(parts[1])
                        || !allowed(parts[2])) { fail(client,"invalid_control_command"); return; }
            } catch (IllegalArgumentException invalid) { fail(client,"invalid_nonce"); return; }
            enqueue(parts[2]);
            // A second, throttled packet proves the real client sent the command first.
            enqueue("mfacceptance actionspeer sent " + parts[1]);
        }
        if (cooldown > 0) cooldown--;
        else if (client.player != null && client.player.connection != null && !OUT.isEmpty()) {
            client.player.connection.sendCommand(OUT.remove()); cooldown = 20;
            System.out.println("MF_M2_PEER_CLIENT SENT role=" + role);
        }
        if (connecting || client.getOverlay() != null || !(client.screen instanceof TitleScreen)) return;
        if (!loopback(SERVER)) { fail(client,"explicit_loopback_required"); return; }
        connecting = true;
        ConnectScreen.startConnecting(new TitleScreen(),client,ServerAddress.parseString(SERVER),
                new ServerData("Isolated M2 two-client acceptance",SERVER,ServerData.Type.OTHER),false,null);
    }
    private static boolean allowed(String text) {
        return text.equals("mf companion create " + (role.equals("a") ? "MF_M2PeerA" : "MF_M2PeerB"))
                || text.equals("mf task follow") || text.equals("mf task stop")
                || role.equals("b") && text.matches("mf task stop [0-9a-f-]{36}");
    }
    private static boolean loopback(String text) {
        return text.matches("127\\.0\\.0\\.1:[1-9][0-9]{0,4}") && Integer.parseInt(text.substring(10)) <= 65535;
    }
    private static void enqueue(String text) { if (OUT.size() >= 16) overflow = true; else OUT.add(text); }
    private static void fail(Minecraft client, String reason) {
        finished = true;
        System.out.println("MF_M2_PEER_CLIENT FAIL role=" + role + " code=" + reason.replaceAll("[^A-Za-z0-9_-]","_"));
        client.stop();
    }
}
