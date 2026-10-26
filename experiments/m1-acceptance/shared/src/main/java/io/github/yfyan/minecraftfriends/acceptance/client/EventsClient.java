package io.github.yfyan.minecraftfriends.acceptance.client;

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

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Client-side evidence collector for real server messages and commands. */
public final class EventsClient {
    private static final ConcurrentLinkedQueue<Action> ACTIONS = new ConcurrentLinkedQueue<>();
    private static final int ACTION_LIMIT = 128;
    private static final String WORLD = "mf-m1-events-" + UUID.randomUUID();
    private static final Pattern MEMORY_HEADER = Pattern.compile("伙伴长期记忆（([0-9]{1,2}) 条）：");
    private static final Pattern MEMORY_ROW = Pattern.compile(
            "^([0-9a-fA-F-]{36}) / (PREFERENCE|EVENT) / (.{1,600})$");
    private static final String BOT_PREFIX = "MF_EventsBot：";
    private static final String REPLY_SUFFIX = " | 协议模拟，非真实模型。你好，我是 AI 伙伴。";
    private static final Object LIST_LOCK = new Object();

    private static volatile boolean listing;
    private static UUID listNonce;
    private static int expectedRows = -1;
    private static int parsedRows;
    private static int memoryMask;
    private static UUID deleteId;
    private static UUID keepId;
    private static boolean reportQueued;
    private static String expectedEvent;
    private static UUID expectedNonce;
    private static boolean started;
    private static boolean reopening;
    private static boolean reopenQueued;
    private static boolean finished;
    private static int waiting;

    private EventsClient() { }

    /** Receives only short harness controls, tagged replies, and parsed memory-list evidence. */
    public static void message(String text) {
        if (text == null || text.length() > 2_048) {
            enqueue(new Action("FAIL", "message_bound", ""));
            return;
        }
        if (text.startsWith("MF_EVENTS ")) {
            parseControl(text.substring("MF_EVENTS ".length()));
            return;
        }
        if (text.startsWith(BOT_PREFIX + "MF_EVENTS_REPLY ")) {
            parseReply(text.substring(BOT_PREFIX.length()));
            return;
        }
        if (listing) {
            parseMemoryLine(text);
        }
    }

    public static void tick(Minecraft client) {
        if (finished) {
            return;
        }
        if (!started && client.getOverlay() == null && client.screen instanceof TitleScreen) {
            started = true;
            client.createWorldOpenFlows().createFreshLevel(WORLD,
                    new LevelSettings("Minecraft Friends M1 Events Acceptance", GameType.SURVIVAL, false,
                            Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                    new WorldOptions(4321L, false, false),
                    registry -> registry.registryOrThrow(Registries.WORLD_PRESET)
                            .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                    client.screen);
            return;
        }

        Action action;
        while ((action = ACTIONS.poll()) != null && !finished) {
            switch (action.kind()) {
                case "EXPECT" -> setExpected(action.first(), action.second());
                case "COMMAND" -> sendTestCommand(client, action.first(), action.second());
                case "LIST" -> beginList(client, action.first());
                case "REOPEN" -> reopen(client, action.first(), action.second());
                case "ACK" -> acknowledge(client, action.first(), action.second());
                case "FAIL" -> fail(client, action.first());
                case "PASS" -> finish(client);
                default -> fail(client, "invalid_control");
            }
        }
        sendListReportIfReady(client);

        if (client.getOverlay() != null || !(client.screen instanceof TitleScreen)) {
            return;
        }
        if (reopening && client.level == null) {
            if (++waiting < 40) {
                return;
            }
            reopening = false;
            client.createWorldOpenFlows().openWorld(WORLD, () -> fail(client, "world_reopen_cancelled"));
        }
    }

    private static void parseControl(String control) {
        if (control.equals("PASS EVENTS_PASS")) {
            enqueue(new Action("PASS", "", ""));
            return;
        }
        if (control.startsWith("FAIL ")) {
            enqueue(new Action("FAIL", "server_failure", ""));
            return;
        }
        if (control.startsWith("EXPECT ")) {
            String[] fields = control.split(" ", 3);
            if (fields.length == 3 && validEvent(fields[1]) && validUuid(fields[2])) {
                enqueue(new Action("EXPECT", fields[1], fields[2]));
            } else {
                enqueue(new Action("FAIL", "invalid_expectation", ""));
            }
            return;
        }
        if (control.startsWith("REOPEN ")) {
            String[] fields = control.split(" ", 3);
            if (fields.length == 3 && "REJOIN".equals(fields[1]) && validUuid(fields[2])) {
                enqueue(new Action("REOPEN", fields[1], fields[2]));
            } else {
                enqueue(new Action("FAIL", "invalid_reopen", ""));
            }
            return;
        }
        if (control.startsWith("COMMAND ")) {
            int separator = control.indexOf(' ', "COMMAND ".length());
            if (separator < 0) {
                enqueue(new Action("FAIL", "invalid_command", ""));
                return;
            }
            String command = control.substring(separator + 1);
            if (!validUuid(control.substring("COMMAND ".length(), separator))) {
                enqueue(new Action("FAIL", "invalid_command", ""));
                return;
            }
            CommandAction parsed = parseAllowedCommand(command);
            if (parsed == null) {
                enqueue(new Action("FAIL", "command_not_allowlisted", ""));
            } else {
                enqueue(new Action("COMMAND", parsed.kind(), parsed.argument()));
            }
            return;
        }
        if (control.startsWith("LIST ")) {
            String nonce = control.substring("LIST ".length());
            enqueue(validUuid(nonce) ? new Action("LIST", nonce, "")
                    : new Action("FAIL", "invalid_list_nonce", ""));
            return;
        }
        enqueue(new Action("FAIL", "unknown_control", ""));
    }

    private static void parseReply(String body) {
        int delimiter = body.indexOf(" |");
        if (delimiter < 0) {
            enqueue(new Action("FAIL", "malformed_reply", ""));
            return;
        }
        String event = body.substring("MF_EVENTS_REPLY ".length(), delimiter);
        String suffix = body.substring(delimiter);
        if (!validEvent(event) || !REPLY_SUFFIX.equals(suffix)) {
            enqueue(new Action("FAIL", "unexpected_reply_content", ""));
            return;
        }
        enqueue(new Action("ACK", event, ""));
    }

    private static CommandAction parseAllowedCommand(String command) {
        return switch (command) {
            case "mf companion create MF_EventsBot" -> new CommandAction("CREATE", "");
            case "mf memory proactive false" -> new CommandAction("PROACTIVE_OFF", "");
            case "mf memory proactive true" -> new CommandAction("PROACTIVE_ON", "");
            case "mf memory remember MF_KEEP_FACT" -> new CommandAction("REMEMBER_KEEP", "");
            case "mf memory remember MF_DELETE_FACT" -> new CommandAction("REMEMBER_DELETE", "");
            default -> parseDynamicCommand(command);
        };
    }

    private static CommandAction parseDynamicCommand(String command) {
        String prefix = "mf memory delete ";
        if (command.startsWith(prefix) && validUuid(command.substring(prefix.length()))) {
            return new CommandAction("DELETE", command.substring(prefix.length()));
        }
        prefix = "mf chat MF_EVENTS_DELETE_PROBE:";
        if (command.startsWith(prefix)) {
            String tail = command.substring(prefix.length());
            int space = tail.indexOf(' ');
            String nonce = space < 0 ? tail : tail.substring(0, space);
            if (validUuid(nonce) && (space < 0 || tail.substring(space).equals(" 请用中文简短回复。"))) {
                return new CommandAction("DELETE_PROBE", nonce);
            }
        }
        return null;
    }

    private static void parseMemoryLine(String text) {
        synchronized (LIST_LOCK) {
            if (!listing || reportQueued) {
                return;
            }
            Matcher header = MEMORY_HEADER.matcher(text);
            if (header.matches()) {
                expectedRows = Integer.parseInt(header.group(1));
                if (expectedRows > 32) {
                    listing = false;
                    enqueue(new Action("FAIL", "memory_list_count_limit", ""));
                }
                return;
            }
            if (text.equals("长期记忆为空。")) {
                expectedRows = 0;
                return;
            }
            Matcher row = MEMORY_ROW.matcher(text);
            if (!row.matches()) {
                return;
            }
            UUID id;
            try {
                id = UUID.fromString(row.group(1));
            } catch (IllegalArgumentException invalid) {
                listing = false;
                enqueue(new Action("FAIL", "invalid_memory_uuid", ""));
                return;
            }
            String kind = row.group(2);
            String fact = row.group(3);
            if ("PREFERENCE".equals(kind) && "MF_DELETE_FACT".equals(fact)) {
                deleteId = id;
                memoryMask |= 1;
            } else if ("PREFERENCE".equals(kind) && "MF_KEEP_FACT".equals(fact)) {
                keepId = id;
                memoryMask |= 2;
            } else if ("EVENT".equals(kind) && "主人第一次加入并与伙伴相遇".equals(fact)) {
                memoryMask |= 4;
            } else if ("EVENT".equals(kind) && "伙伴刚刚受到伤害".equals(fact)) {
                memoryMask |= 8;
            } else if ("EVENT".equals(kind) && "主人重新加入服务器".equals(fact)) {
                memoryMask |= 16;
            }
            parsedRows++;
            if (expectedRows >= 0 && parsedRows > expectedRows) {
                listing = false;
                enqueue(new Action("FAIL", "memory_list_row_count", ""));
            }
        }
    }

    private static void setExpected(String event, String nonceText) {
        UUID nonce = UUID.fromString(nonceText);
        if (expectedEvent != null && (!expectedEvent.equals(event) || !nonce.equals(expectedNonce))) {
            enqueue(new Action("FAIL", "overlapping_expectations", ""));
            return;
        }
        expectedEvent = event;
        expectedNonce = nonce;
    }

    private static void sendTestCommand(Minecraft client, String kind, String argument) {
        if (client.player == null || client.player.connection == null) {
            fail(client, "command_without_player");
            return;
        }
        String command = switch (kind) {
            case "CREATE" -> "mf companion create MF_EventsBot";
            case "PROACTIVE_OFF" -> "mf memory proactive false";
            case "PROACTIVE_ON" -> "mf memory proactive true";
            case "REMEMBER_KEEP" -> "mf memory remember MF_KEEP_FACT";
            case "REMEMBER_DELETE" -> "mf memory remember MF_DELETE_FACT";
            case "DELETE" -> "mf memory delete " + argument;
            case "DELETE_PROBE" -> "mf chat MF_EVENTS_DELETE_PROBE:" + argument + " 请用中文简短回复。";
            default -> null;
        };
        if (command == null) {
            fail(client, "invalid_command_kind");
            return;
        }
        client.player.connection.sendCommand(command);
    }

    private static void beginList(Minecraft client, String nonceText) {
        if (client.player == null || client.player.connection == null) {
            fail(client, "list_without_player");
            return;
        }
        synchronized (LIST_LOCK) {
            listing = true;
            listNonce = UUID.fromString(nonceText);
            expectedRows = -1;
            parsedRows = 0;
            memoryMask = 0;
            deleteId = new UUID(0L, 0L);
            keepId = new UUID(0L, 0L);
            reportQueued = false;
        }
        client.player.connection.sendCommand("mf memory list");
    }

    private static void sendListReportIfReady(Minecraft client) {
        UUID nonce;
        UUID delete;
        UUID keep;
        int mask;
        int count;
        synchronized (LIST_LOCK) {
            if (!listing || reportQueued || expectedRows < 0 || parsedRows != expectedRows) {
                return;
            }
            reportQueued = true;
            listing = false;
            nonce = listNonce;
            delete = deleteId;
            keep = keepId;
            mask = memoryMask;
            count = parsedRows;
        }
        if (client.player == null || client.player.connection == null) {
            fail(client, "list_report_without_player");
            return;
        }
        client.player.connection.sendCommand("mfevents report " + nonce + " " + delete + " " + keep
                + " " + mask + " " + count);
    }

    private static void reopen(Minecraft client, String event, String nonceText) {
        if (reopenQueued || client.level == null) {
            fail(client, "invalid_world_reopen");
            return;
        }
        setExpected(event, nonceText);
        reopenQueued = true;
        client.level.disconnect();
        client.disconnect(new TitleScreen());
        reopening = true;
        waiting = 0;
    }

    private static void acknowledge(Minecraft client, String event, String ignored) {
        if (expectedEvent == null || expectedNonce == null || !expectedEvent.equals(event)) {
            fail(client, "unexpected_event_reply");
            return;
        }
        if (client.player == null || client.player.connection == null) {
            fail(client, "reply_without_player");
            return;
        }
        client.player.connection.sendCommand("mfevents ack " + event + " " + expectedNonce);
        expectedEvent = null;
        expectedNonce = null;
    }

    private static void enqueue(Action action) {
        if (ACTIONS.size() >= ACTION_LIMIT) {
            ACTIONS.clear();
            ACTIONS.add(new Action("FAIL", "action_queue_limit", ""));
            return;
        }
        ACTIONS.add(action);
    }

    private static boolean validEvent(String value) {
        return value.equals("FIRST_MEETING") || value.equals("INJURED") || value.equals("REJOIN")
                || value.equals("DELETE_PROBE");
    }

    private static boolean validUuid(String value) {
        try {
            return UUID.fromString(value).toString().equals(value.toLowerCase());
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static void finish(Minecraft client) {
        finished = true;
        System.out.println("MF_EVENTS_CLIENT PASS EVENTS_PASS");
        client.stop();
    }

    private static void fail(Minecraft client, String reason) {
        finished = true;
        System.out.println("MF_EVENTS_CLIENT FAIL " + reason);
        client.stop();
    }

    private record Action(String kind, String first, String second) { }
    private record CommandAction(String kind, String argument) { }
}
