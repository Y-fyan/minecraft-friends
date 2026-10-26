package io.github.yfyan.minecraftfriends.acceptance.forge;

import io.github.yfyan.minecraftfriends.acceptance.AcceptanceScenario;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod("mf_m1_acceptance")
public final class ForgeAcceptance {
    public ForgeAcceptance() {
        System.out.println("MF_ACCEPTANCE_FORGE_INIT enabled=" + Boolean.getBoolean("mf.acceptance"));
        if (Boolean.getBoolean("mf.acceptance")) { MinecraftForge.EVENT_BUS.register(this); }
    }
    @SubscribeEvent
    public void commands(RegisterCommandsEvent event) { AcceptanceScenario.registerCommands(event.getDispatcher()); }
    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent.Post event) { AcceptanceScenario.tick(event.getServer()); }
    @SubscribeEvent
    public void stopped(ServerStoppedEvent event) { AcceptanceScenario.remove(event.getServer()); }
}
