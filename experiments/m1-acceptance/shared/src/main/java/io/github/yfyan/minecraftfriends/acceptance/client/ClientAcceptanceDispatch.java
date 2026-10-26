package io.github.yfyan.minecraftfriends.acceptance.client;

import io.github.yfyan.minecraftfriends.acceptance.AcceptanceMode;
import io.github.yfyan.minecraftfriends.acceptance.negative.NegativeClient;
import net.minecraft.client.Minecraft;

public final class ClientAcceptanceDispatch {
    private ClientAcceptanceDispatch() { }
    public static void tick(Minecraft client) {
        switch (AcceptanceMode.CURRENT) {
            case STANDARD -> AcceptanceClient.tick(client);
            case PEER -> PeerClient.tick(client);
            case EVENTS -> EventsClient.tick(client);
            case NEGATIVE -> NegativeClient.tick(client);
        }
    }
    public static void message(String text) {
        switch (AcceptanceMode.CURRENT) {
            case STANDARD -> AcceptanceClient.message(text);
            case PEER -> PeerClient.message(text);
            case EVENTS -> EventsClient.message(text);
            case NEGATIVE -> NegativeClient.message(text);
        }
    }
}
