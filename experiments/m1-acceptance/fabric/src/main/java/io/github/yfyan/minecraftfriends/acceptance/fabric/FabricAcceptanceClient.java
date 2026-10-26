package io.github.yfyan.minecraftfriends.acceptance.fabric;

import io.github.yfyan.minecraftfriends.acceptance.client.AcceptanceClient;
import io.github.yfyan.minecraftfriends.acceptance.client.PeerClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

public final class FabricAcceptanceClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("mf.acceptance")) { return; }
        if (Boolean.getBoolean("mf.acceptance.peer")) {
            ClientTickEvents.END_CLIENT_TICK.register(PeerClient::tick);
            ClientReceiveMessageEvents.GAME.register((message, overlay) -> PeerClient.message(message.getString()));
        } else {
            ClientTickEvents.END_CLIENT_TICK.register(AcceptanceClient::tick);
            ClientReceiveMessageEvents.GAME.register((message, overlay) -> AcceptanceClient.message(message.getString()));
        }
    }
}
