package io.github.yfyan.probe.forge;

import io.github.yfyan.probe.PlayerScenario;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.gametest.GameTestHolder;

@Mod("mf_player_probe")
@GameTestHolder(value = "mf_player_probe", namespace = "mf_player_probe")
public final class ForgePlayerTest {
    @GameTest(template = "mf_player_probe:empty", timeoutTicks = 300)
    public static void playerLifecycle(GameTestHelper helper) {
        PlayerScenario.run(helper, "forge");
    }
}
