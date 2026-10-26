package io.github.yfyan.minecraftfriends.test.forge;

import io.github.yfyan.minecraftfriends.test.CompanionLifecycleScenario;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.gametest.GameTestHolder;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@GameTestHolder(value = "minecraft_friends", namespace = "minecraft_friends")
public final class CompanionLifecycleGameTest {
    private static final AtomicReference<UUID> CANCEL_NEXT_DEATH = new AtomicReference<>();

    static {
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST,
                CompanionLifecycleGameTest::cancelMarkedDeath);
    }

    @GameTest(template = "minecraft_friends:empty", timeoutTicks = 300)
    public static void companionLifecycle(GameTestHelper helper) {
        CompanionLifecycleScenario.run(helper, CompanionLifecycleGameTest::cancelNextDeathFor);
    }

    private static void cancelNextDeathFor(ServerPlayer player) {
        CANCEL_NEXT_DEATH.set(player.getUUID());
        System.out.println("MF_LIFECYCLE {\"loader\":\"forge\",\"server_class\":\"net.minecraft.gametest.framework.GameTestServer\",\"check\":\"forge_cancel_armed\",\"value\":\"PASS\"}");
    }

    private static void cancelMarkedDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player
                && player.getUUID().equals(CANCEL_NEXT_DEATH.get())
                && CANCEL_NEXT_DEATH.compareAndSet(player.getUUID(), null)) {
            event.setCanceled(true);
            System.out.println("MF_LIFECYCLE {\"loader\":\"forge\",\"server_class\":\"net.minecraft.gametest.framework.GameTestServer\",\"check\":\"forge_cancel_listener_called\",\"value\":\"PASS\"}");
        }
    }
}
