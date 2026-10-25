package io.github.yfyan.minecraftfriends.test.forge;

import io.github.yfyan.minecraftfriends.test.CompanionLifecycleScenario;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;

@GameTestHolder(value = "minecraft_friends", namespace = "minecraft_friends")
public final class CompanionLifecycleGameTest {
    @GameTest(template = "minecraft_friends:empty", timeoutTicks = 300)
    public static void companionLifecycle(GameTestHelper helper) {
        CompanionLifecycleScenario.run(helper);
    }
}
