package io.github.yfyan.probe.fabric;

import io.github.yfyan.probe.client.IntegratedProbe;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public final class FabricProbeClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        if (Boolean.getBoolean("mf.probe.client")) {
            ClientTickEvents.END_CLIENT_TICK.register(IntegratedProbe::tick);
        }
    }
}
