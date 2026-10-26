package io.github.yfyan.minecraftfriends.acceptance.fabric;

import io.github.yfyan.minecraftfriends.acceptance.client.AcceptanceClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

public final class FabricAcceptanceClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("mf.acceptance")) { return; }
        ClientTickEvents.END_CLIENT_TICK.register(AcceptanceClient::tick);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> AcceptanceClient.message(message.getString()));
    }
}
