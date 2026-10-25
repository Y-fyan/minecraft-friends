package io.github.yfyan.probe.fabric;

import io.github.yfyan.probe.PlayerScenario;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public final class FabricPlayerTest implements FabricGameTest {
    @GameTest(template = "mf_player_probe:empty", timeoutTicks = 300)
    public void playerLifecycle(GameTestHelper helper) {
        PlayerScenario.run(helper, "fabric");
    }
}
