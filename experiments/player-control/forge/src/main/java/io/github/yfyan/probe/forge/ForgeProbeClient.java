package io.github.yfyan.probe.forge;

import io.github.yfyan.probe.client.IntegratedProbe;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "mf_player_probe", value = Dist.CLIENT)
public final class ForgeProbeClient {
    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent.Post event) {
        if (Boolean.getBoolean("mf.probe.client")) { IntegratedProbe.tick(Minecraft.getInstance()); }
    }
}
