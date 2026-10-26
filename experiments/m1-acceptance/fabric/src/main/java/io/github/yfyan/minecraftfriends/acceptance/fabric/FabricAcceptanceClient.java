package io.github.yfyan.minecraftfriends.acceptance.fabric;

import io.github.yfyan.minecraftfriends.acceptance.client.ClientAcceptanceDispatch;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

public final class FabricAcceptanceClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("mf.acceptance")) { return; }
        ClientTickEvents.END_CLIENT_TICK.register(ClientAcceptanceDispatch::tick);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> ClientAcceptanceDispatch.message(message.getString()));
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, type, time) -> ClientAcceptanceDispatch.message(message.getString()));
    }
}
