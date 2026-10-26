package io.github.yfyan.minecraftfriends.acceptance.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Automation on a real dev client; all game commands/chat use the normal client connection. */
public final class PeerClient {
    private static final Pattern PRIVATE_REPLY = Pattern.compile("MF_CHAT_REPLY:([0-9a-fA-F-]{36})");
    private static final Pattern CREATED = Pattern.compile("共享会话已创建，编号 ([0-9a-fA-F-]{36})");
    private static final Pattern JOIN_COMMAND = Pattern.compile("/mf chat shared join ([0-9a-fA-F-]{36})");
    private static final String SERVER = System.getProperty("mf.acceptance.server", "");
    private static final String ROLE = System.getProperty("mf.acceptance.peer.role", "");
    private static final String USERNAME = "a".equals(ROLE) ? "MFAcceptA" : "MFAcceptB";
    private static final ConcurrentLinkedQueue<String> MESSAGES = new ConcurrentLinkedQueue<>();
    private static final Set<String> EXPECTED_NONCES = ConcurrentHashMap.newKeySet();
    private static final Map<String, Watch> WATCHES = new ConcurrentHashMap<>();
    private static boolean started;
    private static boolean readySent;
    private static boolean finished;
    private static String sessionId;

    private static final class Watch {
        private final String needle;
        private int count;
        private Watch(String needle) { this.needle = needle; }
    }

    private PeerClient() { }

    public static void message(String text) {
        if (MESSAGES.size() < 512) { MESSAGES.add(text); }
    }

    public static void tick(Minecraft client) {
        if (finished) { return; }
        String text;
        while ((text = MESSAGES.poll()) != null) {
            if (text.startsWith("MF_PEER ")) {
                if (handleControl(client, text.substring("MF_PEER ".length()))) { return; }
            } else {
                observe(text, client);
            }
        }
        if (client.player != null && client.level != null && !readySent) {
            String actual = client.player.getGameProfile().getName();
            if (!USERNAME.equals(actual)) { fail(client, "offline_username_mismatch"); return; }
            if (!"a".equals(ROLE) && !"b".equals(ROLE)) { fail(client, "peer_role_missing"); return; }
            client.player.connection.sendCommand("mfacceptance peer ready");
            readySent = true;
            System.out.println("MF_PEER_CLIENT READY role=" + ROLE + " username=" + USERNAME);
        }
        if (started || client.getOverlay() != null || !(client.screen instanceof TitleScreen)) { return; }
        if (!loopback(SERVER)) { fail(client, "only_explicit_loopback_server_is_allowed"); return; }
        started = true;
        ConnectScreen.startConnecting(new TitleScreen(), client, ServerAddress.parseString(SERVER),
                new ServerData("Isolated M1 peer acceptance", SERVER, ServerData.Type.OTHER), false, null);
    }

    private static boolean handleControl(Minecraft client, String control) {
        if (control.startsWith("COMMAND ")) {
            if (client.player == null) { fail(client, "command_before_connection"); return true; }
            client.player.connection.sendCommand(control.substring("COMMAND ".length()));
        } else if (control.startsWith("CHAT ")) {
            if (client.player == null) { fail(client, "chat_before_connection"); return true; }
            client.player.connection.sendChat(control.substring("CHAT ".length()));
        } else if (control.startsWith("EXPECT ")) {
            String nonce = control.substring("EXPECT ".length()).strip();
            if (!validUuid(nonce)) { fail(client, "invalid_expected_nonce"); return true; }
            EXPECTED_NONCES.add(nonce.toLowerCase());
        } else if (control.startsWith("SESSION ")) {
            String value = control.substring("SESSION ".length()).strip();
            if (!validUuid(value)) { fail(client, "invalid_shared_session_uuid"); return true; }
            sessionId = value.toLowerCase();
        } else if (control.startsWith("WATCH ")) {
            String[] parts = control.split(" ", 3);
            if (parts.length != 3 || parts[1].length() > 16 || parts[2].length() > 96) {
                fail(client, "invalid_watch_request"); return true;
            }
            WATCHES.put(parts[1], new Watch(parts[2]));
            report(client, "watch_ready_" + parts[1], "true");
        } else if (control.startsWith("REPORT_WATCH ")) {
            String id = control.substring("REPORT_WATCH ".length()).strip();
            Watch watch = WATCHES.get(id);
            if (watch == null) { fail(client, "unknown_watch_id"); return true; }
            report(client, "watch_" + id, Integer.toString(watch.count));
        } else if (control.startsWith("ASSERT ")) {
            String check = control.substring("ASSERT ".length()).strip();
            if (!check.matches("[A-Za-z0-9_-]{1,64}")) { fail(client, "invalid_server_assertion"); return true; }
            System.out.println("MF_PEER_CLIENT ASSERT role=" + ROLE + " check=" + check);
        } else if (control.equals("PASS")) {
            finished = true;
            System.out.println("MF_PEER_CLIENT PASS role=" + ROLE + " username=" + USERNAME);
            client.stop();
            return true;
        } else if (control.startsWith("FAIL ")) {
            fail(client, control.substring("FAIL ".length()).replaceAll("[^A-Za-z0-9_-]", "_"));
            return true;
        }
        return false;
    }

    private static void observe(String text, Minecraft client) {
        // Never persist chat content. Watchers retain only bounded integer counts in memory.
        for (Watch watch : WATCHES.values()) {
            if (!watch.needle.isEmpty() && text.contains(watch.needle) && watch.count < 999) { watch.count++; }
        }
        Matcher reply = PRIVATE_REPLY.matcher(text);
        if (reply.find()) {
            String nonce = reply.group(1).toLowerCase();
            if (EXPECTED_NONCES.remove(nonce)) {
                if (client.player != null) { client.player.connection.sendCommand("mfacceptance peer ack " + nonce); }
                else { fail(client, "reply_without_player"); }
            } else {
                if (client.player != null) { client.player.connection.sendCommand("mfacceptance peer unexpected " + nonce); }
                else { fail(client, "unexpected_reply_without_player"); }
            }
        }
        Matcher created = CREATED.matcher(text);
        if (created.find()) { report(client, "created", created.group(1).toLowerCase()); }
        Matcher invite = JOIN_COMMAND.matcher(text);
        if (text.contains("邀请你加入伙伴共享会话") && invite.find()) {
            report(client, "invited", invite.group(1).toLowerCase());
        }
        if (text.contains("玩家 UUID 没有收到该会话邀请")) { report(client, "denied_join", "true"); }
        if (text.contains("玩家 UUID 尚未加入这个共享会话")) { report(client, "denied_send", "true"); }
        if (text.contains("你已加入共享会话") && sessionId != null) { report(client, "joined", sessionId); }
        if (text.contains("你已离开共享会话") && sessionId != null) { report(client, "left", "true"); }
    }

    private static void report(Minecraft client, String key, String value) {
        if (client.player == null || key.length() > 32 || value.length() > 96
                || !key.matches("[A-Za-z0-9_-]+") || !value.matches("[A-Za-z0-9_.-]+")) {
            fail(client, "invalid_report_or_missing_player");
            return;
        }
        client.player.connection.sendCommand("mfacceptance peer report " + key + " " + value);
    }

    private static boolean validUuid(String value) {
        try { return UUID.fromString(value).toString().equalsIgnoreCase(value); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    private static boolean loopback(String address) {
        return address.matches("127\\.0\\.0\\.1:[1-9][0-9]{0,4}")
                && Integer.parseInt(address.substring(address.indexOf(':') + 1)) <= 65535;
    }

    private static void fail(Minecraft client, String reason) {
        if (finished) { return; }
        finished = true;
        System.out.println("MF_PEER_CLIENT FAIL role=" + ROLE + " code="
                + reason.replaceAll("[^A-Za-z0-9_-]", "_"));
        client.stop();
    }
}
