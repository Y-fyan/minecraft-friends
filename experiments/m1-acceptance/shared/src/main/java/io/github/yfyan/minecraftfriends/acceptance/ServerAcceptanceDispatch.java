package io.github.yfyan.minecraftfriends.acceptance;

import com.mojang.brigadier.CommandDispatcher;
import io.github.yfyan.minecraftfriends.acceptance.negative.NegativeScenario;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

public final class ServerAcceptanceDispatch {
    private ServerAcceptanceDispatch() { }
    public static void starting(MinecraftServer server) {
        if (AcceptanceMode.CURRENT == AcceptanceMode.NEGATIVE) { NegativeScenario.prepare(server); }
    }
    public static void commands(CommandDispatcher<CommandSourceStack> dispatcher) {
        switch (AcceptanceMode.CURRENT) {
            case ACTIONS -> ActionsScenario.registerCommands(dispatcher);
            case STANDARD -> AcceptanceScenario.registerCommands(dispatcher);
            case PEER -> PeerScenario.registerCommands(dispatcher);
            case EVENTS -> EventsScenario.registerCommands(dispatcher);
            case NEGATIVE -> NegativeScenario.registerCommands(dispatcher);
        }
    }
    public static void tick(MinecraftServer server) {
        switch (AcceptanceMode.CURRENT) {
            case ACTIONS -> ActionsScenario.tick(server);
            case STANDARD -> AcceptanceScenario.tick(server);
            case PEER -> PeerScenario.tick(server);
            case EVENTS -> EventsScenario.tick(server);
            case NEGATIVE -> NegativeScenario.tick(server);
        }
    }
    public static void stopped(MinecraftServer server) {
        switch (AcceptanceMode.CURRENT) {
            case ACTIONS -> ActionsScenario.remove(server);
            case STANDARD -> AcceptanceScenario.remove(server);
            case PEER -> PeerScenario.remove(server);
            case EVENTS -> EventsScenario.remove(server);
            case NEGATIVE -> NegativeScenario.remove(server);
        }
    }
}
