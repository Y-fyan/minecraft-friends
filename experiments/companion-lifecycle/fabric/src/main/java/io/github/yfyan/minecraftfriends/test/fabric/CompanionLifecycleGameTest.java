package io.github.yfyan.minecraftfriends.test.fabric;

import io.github.yfyan.minecraftfriends.test.CompanionLifecycleScenario;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public final class CompanionLifecycleGameTest implements FabricGameTest {
    @GameTest(template = "minecraft_friends:empty", timeoutTicks = 300)
    public void companionLifecycle(GameTestHelper helper) {
        CompanionLifecycleScenario.run(helper);
    }
}
