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
            case ACTIONS -> { if ("peer".equals(System.getProperty("mf.acceptance.actions.scope"))) ActionsPeerScenario.registerCommands(dispatcher); else if ("performance".equals(System.getProperty("mf.acceptance.actions.scope"))) PerformanceScenario.registerCommands(dispatcher); else if ("tree".equals(System.getProperty("mf.acceptance.actions.scope"))) TreeScenario.registerCommands(dispatcher); else ActionsScenario.registerCommands(dispatcher); }
            case STANDARD -> AcceptanceScenario.registerCommands(dispatcher);
            case PEER -> PeerScenario.registerCommands(dispatcher);
            case EVENTS -> EventsScenario.registerCommands(dispatcher);
            case NEGATIVE -> NegativeScenario.registerCommands(dispatcher);
        }
    }
    public static void tick(MinecraftServer server) {
        switch (AcceptanceMode.CURRENT) {
            case ACTIONS -> { if ("peer".equals(System.getProperty("mf.acceptance.actions.scope"))) ActionsPeerScenario.tick(server); else if ("performance".equals(System.getProperty("mf.acceptance.actions.scope"))) PerformanceScenario.tick(server); else if ("tree".equals(System.getProperty("mf.acceptance.actions.scope"))) TreeScenario.tick(server); else ActionsScenario.tick(server); }
            case STANDARD -> AcceptanceScenario.tick(server);
            case PEER -> PeerScenario.tick(server);
            case EVENTS -> EventsScenario.tick(server);
            case NEGATIVE -> NegativeScenario.tick(server);
        }
    }
    public static void stopped(MinecraftServer server) {
        switch (AcceptanceMode.CURRENT) {
            case ACTIONS -> { if ("peer".equals(System.getProperty("mf.acceptance.actions.scope"))) ActionsPeerScenario.remove(server); else if ("performance".equals(System.getProperty("mf.acceptance.actions.scope"))) PerformanceScenario.remove(server); else if ("tree".equals(System.getProperty("mf.acceptance.actions.scope"))) TreeScenario.remove(server); else ActionsScenario.remove(server); }
            case STANDARD -> AcceptanceScenario.remove(server);
            case PEER -> PeerScenario.remove(server);
            case EVENTS -> EventsScenario.remove(server);
            case NEGATIVE -> NegativeScenario.remove(server);
        }
    }
}
