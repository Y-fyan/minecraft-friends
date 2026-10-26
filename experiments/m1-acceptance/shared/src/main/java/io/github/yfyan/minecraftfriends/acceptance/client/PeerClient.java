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
    private static final Pattern MEMORY_LIST_HEADER = Pattern.compile("伙伴长期记忆（([0-9]{1,3}) 条）：");
    private static final Pattern MEMORY_LIST_ENTRY = Pattern.compile("([0-9a-fA-F-]{36}) / (?:PREFERENCE|EVENT) / .+");
    private static final String SERVER = System.getProperty("mf.acceptance.server", "");
    private static final String ROLE = System.getProperty("mf.acceptance.peer.role", "");
    private static final String USERNAME = "a".equals(ROLE) ? "MFAcceptA" : "MFAcceptB";
    private static final ConcurrentLinkedQueue<IncomingMessage> MESSAGES = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<Outbound> OUTBOUND = new ConcurrentLinkedQueue<>();
    private static final int OUTBOUND_INTERVAL_TICKS = 20;
    private static final int OUTBOUND_LIMIT = 128;
    private static final Set<String> EXPECTED_NONCES = ConcurrentHashMap.newKeySet();
    private static final Map<String, Watch> WATCHES = new ConcurrentHashMap<>();
    private static final Set<String> MEMORY_LIST_ENTRY_IDS = ConcurrentHashMap.newKeySet();
    private static boolean started;
    private static boolean readySent;
    private static boolean finished;
    private static boolean outboundOverflow;
    private static int outboundTicks;
    private static String sessionId;
    private static boolean memoryListRequested;
    private static boolean memoryListCompleted;
    private static int memoryListExpected = -1;
    private static String pendingMemoryListReport;

    private record Outbound(boolean chat, String text) { }
    private record IncomingMessage(String text, boolean systemMessage) { }

    private static final class Watch {
        private final String needle;
        private final boolean systemOnly;
        private int count;
        private Watch(String needle, boolean systemOnly) {
            this.needle = needle;
            this.systemOnly = systemOnly;
        }
    }

    private PeerClient() { }

    public static void message(String text) {
        message(text, false);
    }

    public static void message(String text, boolean systemMessage) {
        if (MESSAGES.size() < 512) { MESSAGES.add(new IncomingMessage(text, systemMessage)); }
    }

    public static void tick(Minecraft client) {
        if (finished) { return; }
        IncomingMessage incoming;
        while ((incoming = MESSAGES.poll()) != null) {
            String text = incoming.text();
            if (text.startsWith("MF_PEER ")) {
                if (handleControl(client, text.substring("MF_PEER ".length()))) { return; }
            } else {
                observe(text, incoming.systemMessage(), client);
                if (finished) { return; }
            }
        }
        if (client.player != null && client.level != null && !readySent) {
            String actual = client.player.getGameProfile().getName();
            if (!USERNAME.equals(actual)) { fail(client, "offline_username_mismatch"); return; }
            if (!"a".equals(ROLE) && !"b".equals(ROLE)) { fail(client, "peer_role_missing"); return; }
            enqueueCommand("mfacceptance peer ready");
            readySent = true;
            System.out.println("MF_PEER_CLIENT READY role=" + ROLE + " username=" + USERNAME);
        }
        sendNextOutbound(client);
        if (started || client.getOverlay() != null || !(client.screen instanceof TitleScreen)) { return; }
        if (!loopback(SERVER)) { fail(client, "only_explicit_loopback_server_is_allowed"); return; }
        started = true;
        ConnectScreen.startConnecting(new TitleScreen(), client, ServerAddress.parseString(SERVER),
                new ServerData("Isolated M1 peer acceptance", SERVER, ServerData.Type.OTHER), false, null);
    }

    private static boolean handleControl(Minecraft client, String control) {
        if (control.startsWith("COMMAND ")) {
            if (client.player == null) { fail(client, "command_before_connection"); return true; }
            String command = control.substring("COMMAND ".length());
            if ("mf memory list".equals(command)) {
                if (memoryListRequested) { fail(client, "memory_list_requested_more_than_once"); return true; }
                memoryListRequested = true;
                memoryListExpected = -1;
                memoryListCompleted = false;
                MEMORY_LIST_ENTRY_IDS.clear();
            }
            enqueueCommand(command);
        } else if (control.startsWith("CHAT ")) {
            if (client.player == null) { fail(client, "chat_before_connection"); return true; }
            enqueueChat(control.substring("CHAT ".length()));
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
            WATCHES.put(parts[1], new Watch(parts[2], "fact".equals(parts[1])));
            report(client, "watch_ready_" + parts[1], "true");
        } else if (control.startsWith("REPORT_WATCH ")) {
            String id = control.substring("REPORT_WATCH ".length()).strip();
            Watch watch = WATCHES.get(id);
            if (watch == null) { fail(client, "unknown_watch_id"); return true; }
            if ("fact".equals(id)) {
                if (!memoryListRequested || pendingMemoryListReport != null) {
                    fail(client, "memory_list_report_out_of_sequence"); return true;
                }
                if (!memoryListCompleted) { pendingMemoryListReport = id; }
                else { report(client, "watch_" + id, Integer.toString(watch.count)); }
            } else {
                report(client, "watch_" + id, Integer.toString(watch.count));
            }
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

    private static void enqueueCommand(String command) { enqueueOutbound(new Outbound(false, command)); }

    private static void enqueueChat(String message) { enqueueOutbound(new Outbound(true, message)); }

    private static void enqueueOutbound(Outbound outbound) {
        if (OUTBOUND.size() >= OUTBOUND_LIMIT) {
            outboundOverflow = true;
            return;
        }
        OUTBOUND.add(outbound);
    }

    private static void sendNextOutbound(Minecraft client) {
        if (outboundOverflow) { fail(client, "outbound_queue_limit"); return; }
        if (client.player == null || client.player.connection == null || OUTBOUND.isEmpty()) { return; }
        if (outboundTicks > 0) {
            outboundTicks--;
            return;
        }
        Outbound outbound = OUTBOUND.poll();
        if (outbound == null) { return; }
        if (outbound.chat()) { client.player.connection.sendChat(outbound.text()); }
        else { client.player.connection.sendCommand(outbound.text()); }
        // The vanilla server rate-limits command and chat packets; pace this harness below that guard.
        outboundTicks = OUTBOUND_INTERVAL_TICKS;
    }

    private static void observe(String text, boolean systemMessage, Minecraft client) {
        // Never persist chat content. Watchers retain only bounded integer counts in memory.
        for (Watch watch : WATCHES.values()) {
            if ((!watch.systemOnly || systemMessage) && !watch.needle.isEmpty()
                    && text.contains(watch.needle) && watch.count < 999) { watch.count++; }
        }
        if (systemMessage) { observeMemoryList(text, client); }
        Matcher reply = PRIVATE_REPLY.matcher(text);
        if (reply.find()) {
            String nonce = reply.group(1).toLowerCase();
            if (EXPECTED_NONCES.remove(nonce)) {
                if (client.player != null) { enqueueCommand("mfacceptance peer ack " + nonce); }
                else { fail(client, "reply_without_player"); }
            } else {
                if (client.player != null) { enqueueCommand("mfacceptance peer unexpected " + nonce); }
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

    private static void observeMemoryList(String text, Minecraft client) {
        if (!memoryListRequested || memoryListCompleted) { return; }
        Matcher header = MEMORY_LIST_HEADER.matcher(text);
        if (header.matches()) {
            if (memoryListExpected >= 0) { fail(client, "duplicate_memory_list_header"); return; }
            memoryListExpected = Integer.parseInt(header.group(1));
            completeMemoryListIfReady(client);
            return;
        }
        if ("长期记忆为空。".equals(text)) {
            if (memoryListExpected >= 0) { fail(client, "duplicate_memory_list_header"); return; }
            memoryListExpected = 0;
            completeMemoryListIfReady(client);
            return;
        }
        Matcher entry = MEMORY_LIST_ENTRY.matcher(text);
        if (!entry.matches()) { return; }
        if (memoryListExpected < 0) { fail(client, "memory_list_entry_before_header"); return; }
        String id = entry.group(1).toLowerCase();
        if (!validUuid(id)) { fail(client, "invalid_memory_list_entry_id"); return; }
        MEMORY_LIST_ENTRY_IDS.add(id);
        if (MEMORY_LIST_ENTRY_IDS.size() > memoryListExpected) {
            fail(client, "memory_list_entry_count_exceeded_header"); return;
        }
        completeMemoryListIfReady(client);
    }

    private static void completeMemoryListIfReady(Minecraft client) {
        if (memoryListExpected < 0 || MEMORY_LIST_ENTRY_IDS.size() != memoryListExpected) { return; }
        memoryListCompleted = true;
        System.out.println("MF_PEER_CLIENT ASSERT role=" + ROLE + " check=memory_list_completed");
        if (pendingMemoryListReport != null) {
            String id = pendingMemoryListReport;
            pendingMemoryListReport = null;
            Watch watch = WATCHES.get(id);
            if (watch == null) { fail(client, "unknown_watch_id_after_memory_list"); return; }
            report(client, "watch_" + id, Integer.toString(watch.count));
        }
    }

    private static void report(Minecraft client, String key, String value) {
        if (client.player == null || key.length() > 32 || value.length() > 96
                || !key.matches("[A-Za-z0-9_-]+") || !value.matches("[A-Za-z0-9_.-]+")) {
            fail(client, "invalid_report_or_missing_player");
            return;
        }
        enqueueCommand("mfacceptance peer report " + key + " " + value);
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
