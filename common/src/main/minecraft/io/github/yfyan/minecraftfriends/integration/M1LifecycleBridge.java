package io.github.yfyan.minecraftfriends.integration;

import io.github.yfyan.minecraftfriends.chat.ChatRuntime;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Confirms actual health loss at tick end, before advancing chat maintenance. */
public final class M1LifecycleBridge {
    // Inner maps belong to their server thread; distinct integrated servers remain isolated.
    private static final Map<MinecraftServer, Map<UUID, DamageSnapshot>> PENDING_DAMAGE =
            new ConcurrentHashMap<>();

    private M1LifecycleBridge() {
    }

    /** Server-thread only. Observing damage here never authorizes or cancels it. */
    public static void beforeDamage(ServerPlayer player) {
        MinecraftServer server = player.server;
        requireServerThread(server);
        if (!ChatRuntime.isEnabled(server) || !player.isAlive()
                || server.getPlayerList().getPlayer(player.getUUID()) != player) {
            return;
        }
        float health = player.getHealth();
        if (!Float.isFinite(health)) {
            return;
        }
        int tick = server.getTickCount();
        Map<UUID, DamageSnapshot> pending = PENDING_DAMAGE.computeIfAbsent(server, ignored -> new HashMap<>());
        pending.compute(player.getUUID(), (id, previous) -> {
            float before = previous != null && previous.tick() == tick && previous.player() == player
                    ? Math.max(previous.health(), health) : health;
            return new DamageSnapshot(player, tick, before);
        });
    }

    /** Server-thread only. Consumes every sample once, including canceled or absorbed hits. */
    public static void afterTick(MinecraftServer server) {
        requireServerThread(server);
        Map<UUID, DamageSnapshot> pending = PENDING_DAMAGE.remove(server);
        if (pending != null && ChatRuntime.isEnabled(server)) {
            int tick = server.getTickCount();
            for (Map.Entry<UUID, DamageSnapshot> entry : pending.entrySet()) {
                DamageSnapshot before = entry.getValue();
                ServerPlayer current = server.getPlayerList().getPlayer(entry.getKey());
                int elapsedTicks = tick - before.tick();
                // A respawn/reconnect with the same UUID is a different entity, not this hit.
                // Network tasks and later END-tick listeners can damage a player after
                // this observer ran; the next tick must still consume that real hit.
                if (elapsedTicks >= 0 && elapsedTicks <= 1 && current == before.player()
                        && current.isAlive() && !current.isRemoved() && current.getHealth() < before.health()) {
                    ChatRuntime.onCompanionInjured(current);
                }
            }
        }
        ChatRuntime.onTick(server);
    }

    /** Releases references when a server stops; safe to repeat after it has stopped. */
    public static void stop(MinecraftServer server) {
        PENDING_DAMAGE.remove(server);
    }

    private static void requireServerThread(MinecraftServer server) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("M1 lifecycle callbacks require the server thread");
        }
    }

    private record DamageSnapshot(ServerPlayer player, int tick, float health) {
    }
}
