package io.github.yfyan.minecraftfriends.acceptance.forge;

import io.github.yfyan.minecraftfriends.acceptance.client.AcceptanceClient;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "mf_m1_acceptance", value = Dist.CLIENT)
public final class ForgeAcceptanceClient {
    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent.Post event) {
        if (Boolean.getBoolean("mf.acceptance")) { AcceptanceClient.tick(Minecraft.getInstance()); }
    }
    @SubscribeEvent
    public static void message(ClientChatReceivedEvent event) {
        if (Boolean.getBoolean("mf.acceptance")) { AcceptanceClient.message(event.getMessage().getString()); }
    }
}
