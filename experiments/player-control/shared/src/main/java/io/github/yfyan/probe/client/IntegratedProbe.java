package io.github.yfyan.probe.client;

import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestRegistry;
import net.minecraft.gametest.framework.GlobalTestReporter;
import net.minecraft.gametest.framework.TestReporter;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/** Only loaded by experimental client entrypoints in the isolated build/run directory. */
public final class IntegratedProbe {
    private static boolean creating;
    private static boolean running;
    private static volatile String result;

    public static void tick(Minecraft client) {
        if (result != null) {
            System.out.println("MF_CLIENT_RESULT " + result);
            client.stop();
            return;
        }
        if (!creating && client.screen instanceof TitleScreen && client.getOverlay() == null) {
            creating = true;
            var settings = new LevelSettings("Minecraft Friends Probe", GameType.SURVIVAL, false,
                    Difficulty.NORMAL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
            client.createWorldOpenFlows().createFreshLevel("mf-probe-" + UUID.randomUUID(), settings,
                    new WorldOptions(1234L, false, false), registry -> registry
                            .registryOrThrow(Registries.WORLD_PRESET)
                            .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), client.screen);
        }
        if (creating && !running && client.level != null && client.hasSingleplayerServer()) {
            running = true;
            var server = client.getSingleplayerServer();
            server.execute(() -> {
                try {
                    if (server.isDedicatedServer()) { throw new IllegalStateException("not integrated"); }
                    GlobalTestReporter.replaceWith(new TestReporter() {
                        @Override
                        public void onTestFailed(GameTestInfo test) {
                            result = "FAIL " + test.getError();
                        }

                        @Override
                        public void onTestSuccess(GameTestInfo test) {
                            result = "PASS " + server.getClass().getName();
                        }
                    });
                    String test = GameTestRegistry.getAllTestFunctions().stream()
                            .filter(f -> f.testName().endsWith("playerlifecycle"))
                            .findFirst().orElseThrow().testName();
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "test run " + test);
                } catch (Throwable failure) {
                    result = "FAIL " + failure;
                }
            });
        }
    }
}
