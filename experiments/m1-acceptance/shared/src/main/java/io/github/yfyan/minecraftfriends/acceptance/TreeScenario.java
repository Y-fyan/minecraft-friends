package io.github.yfyan.minecraftfriends.acceptance;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.yfyan.minecraftfriends.action.ActionRuntime;
import io.github.yfyan.minecraftfriends.action.TreeTaskStatus;
import io.github.yfyan.minecraftfriends.companion.CompanionRuntime;
import io.github.yfyan.minecraftfriends.task.TaskState;
import io.github.yfyan.minecraftfriends.tree.TreePosition;
import io.github.yfyan.minecraftfriends.tree.TreeSpecies;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Deterministic real-runtime fixture, not model/chat-understanding or naturally generated-tree proof.
 * Run only in the existing isolated ACTIONS harness with acceptanceActionsScope=tree.
 * Setup edits/teleports/stock are performed before measurement, never to complete a task.
 * This new fixture has not yet been compiled or executed in a Minecraft runtime.
 */
public final class TreeScenario {
    private static final String OWNER_NAME = "MF_M2Owner";
    private static final BlockPos BASE = new BlockPos(8, -60, 8);
    private static final int LOG_COUNT = 4;
    private static final int PHASE_TIMEOUT_TICKS = 2400;
    private static final Map<MinecraftServer, TreeScenario> RUNS = new WeakHashMap<>();

    private final MinecraftServer server;
    private final Map<BlockPos, BlockState> protectedSamples = new HashMap<>();
    private ServerPlayer owner;
    private ServerPlayer companion;
    private Phase phase = Phase.WAIT_COMPANION;
    private int age;
    private int ticks;
    private boolean finished;
    private TreeSpecies species = TreeSpecies.OAK;
    private ActionRuntime.TreePreview preview;
    private UUID taskId;
    private Vec3 lastPosition;
    private double maxStep;
    private int saplingsBefore;
    private int axeDamageBefore;

    private TreeScenario(MinecraftServer server) {
        this.server = server;
    }

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        // All commands use the production command tree, relayed by the real acceptance client.
    }

    public static void tick(MinecraftServer server) {
        RUNS.computeIfAbsent(server, TreeScenario::new).step();
    }

    public static void remove(MinecraftServer server) {
        RUNS.remove(server);
    }

    private void step() {
        if (finished) return;
        age++;
        ticks++;
        try {
            if (owner == null) {
                owner = server.getPlayerList().getPlayers().stream()
                        .filter(player -> OWNER_NAME.equals(player.getGameProfile().getName()))
                        .findFirst().orElse(null);
                if (owner == null) {
                    if (ticks > 6000) fail("tree_no_test_owner");
                    return;
                }
                guardFixtureWorld();
                if (Boolean.getBoolean("mf.acceptance.actions.natural")) {
                    fail("tree_fixture_requires_explicit_api_mode");
                    return;
                }
                emit("isolated_survival_map", true);
                emit("explicit_commands_enabled", true);
                emit(server instanceof net.minecraft.server.dedicated.DedicatedServer
                        ? "dedicated_server_instance" : "integrated_server_instance", true);
                command("mf companion create MF_M2Bot");
                age = 0;
                return;
            }
            if (server.getPlayerList().getPlayer(owner.getUUID()) != owner || !owner.isAlive()) {
                fail("tree_owner_lifecycle_changed");
                return;
            }
            if (age > PHASE_TIMEOUT_TICKS) {
                fail("tree_phase_timeout_" + phase.name().toLowerCase(java.util.Locale.ROOT));
                return;
            }
            switch (phase) {
                case WAIT_COMPANION -> {
                    var entry = CompanionRuntime.data(server).find(owner.getUUID()).orElse(null);
                    if (entry == null) return;
                    companion = server.getPlayerList().getPlayer(entry.identity().companionId());
                    if (companion == null) return;
                    check(companion.gameMode.isSurvival() && owner.gameMode.isSurvival(), "tree_survival_mode");
                    setup(TreeSpecies.OAK);
                    authorize();
                    next(Phase.AUTHORIZE);
                }
                case AUTHORIZE -> {
                    if (age < 20) return;
                    preview = ActionRuntime.previewTree(owner, selectedBase());
                    check(preview.plan().logs().size() == LOG_COUNT && preview.plan().species() == species,
                            tag("exact_preview"));
                    check(logs() == LOG_COUNT && inventory(owner, logItem()) == 0,
                            tag("preview_has_no_world_effect"));
                    taskId = UUID.randomUUID();
                    lastPosition = companion.position();
                    maxStep = 0;
                    ActionRuntime.confirmTree(owner, preview.selectionId(), taskId, true);
                    check(rejectConfirmation(preview.selectionId(), taskId), tag("duplicate_confirmation_rejected"));
                    next(Phase.RUNNING);
                }
                case RUNNING -> {
                    measureMovement();
                    TreeTaskStatus status = ActionRuntime.treeStatus(server, owner.getUUID()).orElseThrow();
                    checkCurrentTask(status);
                    if (!status.state().isTerminal()) return;
                    check(status.state() == TaskState.SUCCEEDED, tag("runtime_succeeded"));
                    check(status.planned() == LOG_COUNT && status.harvested() == LOG_COUNT
                            && status.pickedUp() == LOG_COUNT && status.delivered() == LOG_COUNT,
                            tag("harvest_pickup_delivery_are_four"));
                    check(logs() == 0 && inventory(owner, logItem()) == LOG_COUNT
                            && inventory(companion, logItem()) == 0 && nearbyLogDrops() == 0,
                            tag("real_logs_conserved_and_delivered"));
                    check(status.replant() == TreeTaskStatus.Replant.PLANTED
                            && owner.serverLevel().getBlockState(BASE).is(saplingBlock())
                            && inventory(companion, saplingItem()) == saplingsBefore - 1,
                            tag("real_sapling_consumed_and_planted"));
                    check(inventory(companion, Items.IRON_AXE) == 1 && axeDamage() > axeDamageBefore,
                            tag("real_axe_durability_consumed"));
                    check(samplesUnchanged(), tag("house_and_decoration_untouched"));
                    check(maxStep <= 1.5, tag("physical_movement_bounded"));
                    check(rejectConfirmation(preview.selectionId(), UUID.randomUUID()), tag("used_selection_replay_rejected"));
                    next(Phase.AFTER_TERMINAL);
                }
                case AFTER_TERMINAL -> {
                    measureMovement();
                    if (age < 40) return;
                    check(inventory(owner, logItem()) == LOG_COUNT && inventory(companion, logItem()) == 0
                            && logs() == 0 && samplesUnchanged(), tag("terminal_does_not_repeat_delivery"));
                    if (species == TreeSpecies.OAK) {
                        setup(TreeSpecies.BIRCH);
                        authorize();
                        next(Phase.AUTHORIZE);
                    } else {
                        setup(TreeSpecies.OAK);
                        authorize();
                        next(Phase.CANCEL_AUTHORIZE);
                    }
                }
                case CANCEL_AUTHORIZE -> {
                    if (age < 20) return;
                    preview = ActionRuntime.previewTree(owner, selectedBase());
                    command("mf task stop");
                    next(Phase.CANCEL_WAIT);
                }
                case CANCEL_WAIT -> {
                    if (age < 40) return;
                    check(rejectConfirmation(preview.selectionId(), UUID.randomUUID()),
                            "tree_cancelled_preview_cannot_start_or_replay");
                    check(logs() == LOG_COUNT && inventory(owner, logItem()) == 0
                            && inventory(companion, logItem()) == 0 && axeDamage() == axeDamageBefore,
                            "tree_stop_before_start_preserves_tree_and_stock");
                    check(samplesUnchanged(), "tree_cancel_preserves_protected_samples");
                    authorize();
                    next(Phase.CHANGE_AUTHORIZE);
                }
                case CHANGE_AUTHORIZE -> {
                    if (age < 20) return;
                    preview = ActionRuntime.previewTree(owner, selectedBase());
                    // Deliberate fixture-only intervening change BEFORE execution/confirmation.
                    guardFixtureWorld();
                    owner.serverLevel().setBlock(BASE.above(2), Blocks.STONE.defaultBlockState(), 3);
                    check(rejectConfirmation(preview.selectionId(), UUID.randomUUID()),
                            "tree_changed_target_confirmation_rejected");
                    check(rejectConfirmation(preview.selectionId(), UUID.randomUUID()),
                            "tree_changed_selection_replay_rejected");
                    next(Phase.CHANGE_WAIT);
                }
                case CHANGE_WAIT -> {
                    if (age < 40) return;
                    check(logs() == LOG_COUNT - 1 && owner.serverLevel().getBlockState(BASE.above(2)).is(Blocks.STONE)
                            && inventory(owner, logItem()) == 0 && inventory(companion, logItem()) == 0
                            && inventory(companion, saplingItem()) == saplingsBefore
                            && axeDamage() == axeDamageBefore && samplesUnchanged(),
                            "tree_changed_target_causes_no_execution");
                    emit("result", true);
                    owner.sendSystemMessage(Component.literal("MF_M2 PASS"));
                    finished = true;
                }
            }
        } catch (Exception failure) {
            fail("tree_exception_" + failure.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT));
        }
    }

    private void guardFixtureWorld() throws java.io.IOException {
        String world = server.getWorldPath(LevelResource.ROOT).toRealPath().getFileName().toString();
        if (!world.startsWith("mf-m2-acceptance-") || owner == null
                || !OWNER_NAME.equals(owner.getGameProfile().getName())
                || server.getPlayerList().getPlayer(owner.getUUID()) != owner) {
            throw new IllegalStateException("Only isolated mf-m2-acceptance worlds and MF_M2Owner are allowed.");
        }
    }

    private void setup(TreeSpecies selectedSpecies) throws java.io.IOException {
        guardFixtureWorld();
        species = selectedSpecies;
        ServerLevel level = owner.serverLevel();
        // No forced leaf drops or sapling growth. Disable random ticking in this isolated fixture
        // before measurement so natural decay cannot introduce unrelated sapling stock into assertions.
        level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(0, server);
        for (int x = BASE.getX() - 6; x <= BASE.getX() + 7; x++) {
            for (int z = BASE.getZ() - 6; z <= BASE.getZ() + 7; z++) {
                level.setBlock(new BlockPos(x, BASE.getY() - 1, z), Blocks.DIRT.defaultBlockState(), 3);
                for (int y = BASE.getY(); y <= BASE.getY() + 10; y++) {
                    level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        owner.getInventory().clearContent();
        companion.getInventory().clearContent();
        companion.getInventory().setItem(0, new ItemStack(Items.IRON_AXE));
        companion.getInventory().setItem(1, new ItemStack(saplingItem(), 2));
        companion.getInventory().selected = 0;
        owner.teleportTo(BASE.getX() - 4.5, BASE.getY(), BASE.getZ() - 4.5);
        companion.teleportTo(BASE.getX() - 2.5, BASE.getY(), BASE.getZ() + 0.5);
        for (int dy = 0; dy < LOG_COUNT; dy++) {
            level.setBlock(BASE.above(dy), logBlock().defaultBlockState(), 3);
        }
        Block leaves = species == TreeSpecies.OAK ? Blocks.OAK_LEAVES : Blocks.BIRCH_LEAVES;
        // Multi-layer ordinary-shaped canopy, not the M2 fixed log pile. Non-persistent leaves
        // carry a real distance-to-log value and remain ordinary vanilla blocks throughout execution.
        for (int dy = 2; dy <= 4; dy++) {
            int radius = dy == 4 ? 1 : 2;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx == 0 && dz == 0 && dy < LOG_COUNT) continue;
                    if (Math.abs(dx) == radius && Math.abs(dz) == radius) continue;
                    int distance = Math.max(1, Math.abs(dx) + Math.abs(dz) + Math.max(0, dy - 3));
                    level.setBlock(BASE.offset(dx, dy, dz), leaves.defaultBlockState()
                            .setValue(LeavesBlock.PERSISTENT, false)
                            .setValue(LeavesBlock.DISTANCE, Math.min(7, distance)), 3);
                }
            }
        }
        protectedSamples.clear();
        // Deliberately just outside the radius-three observation. These are never plan targets.
        sample(BASE.offset(4, 0, 0), Blocks.OAK_LOG.defaultBlockState());
        sample(BASE.offset(4, 1, 0), Blocks.OAK_LOG.defaultBlockState());
        sample(BASE.offset(5, 0, 0), Blocks.OAK_PLANKS.defaultBlockState());
        sample(BASE.offset(5, 1, 0), Blocks.OAK_PLANKS.defaultBlockState());
        sample(BASE.offset(6, 0, 0), Blocks.BIRCH_LOG.defaultBlockState());
        sample(BASE.offset(6, 1, 0), Blocks.BIRCH_LOG.defaultBlockState());
        saplingsBefore = inventory(companion, saplingItem());
        axeDamageBefore = axeDamage();
    }

    private void sample(BlockPos position, BlockState block) {
        owner.serverLevel().setBlock(position, block, 3);
        protectedSamples.put(position, block);
    }

    private void authorize() {
        command("mf task area " + BASE.getX() + " " + BASE.getY() + " " + BASE.getZ() + " "
                + BASE.getX() + " " + (BASE.getY() + LOG_COUNT - 1) + " " + BASE.getZ());
    }

    private void measureMovement() {
        Vec3 now = companion.position();
        if (lastPosition != null) maxStep = Math.max(maxStep, now.distanceTo(lastPosition));
        lastPosition = now;
        if (maxStep > 1.5) throw new IllegalStateException("tree_navigation_teleport_detected");
    }

    private void checkCurrentTask(TreeTaskStatus status) {
        if (!status.taskId().equals(taskId)) throw new IllegalStateException("tree_task_identity_changed");
    }

    private boolean rejectConfirmation(UUID selectionId, UUID requestedTaskId) {
        try {
            ActionRuntime.confirmTree(owner, selectionId, requestedTaskId, true);
            return false;
        } catch (CommandSyntaxException expected) {
            return true;
        }
    }

    private int logs() {
        int count = 0;
        for (int dy = 0; dy < LOG_COUNT; dy++) {
            if (owner.serverLevel().getBlockState(BASE.above(dy)).is(logBlock())) count++;
        }
        return count;
    }

    private int nearbyLogDrops() {
        return owner.serverLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                        new net.minecraft.world.phys.AABB(BASE).inflate(12),
                        entity -> entity.isAlive() && entity.getItem().is(logItem()))
                .stream().mapToInt(entity -> entity.getItem().getCount()).sum();
    }

    private boolean samplesUnchanged() {
        return protectedSamples.entrySet().stream()
                .allMatch(entry -> owner.serverLevel().getBlockState(entry.getKey()).equals(entry.getValue()));
    }

    private static int inventory(ServerPlayer player, Item item) {
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private int axeDamage() {
        int damage = 0;
        for (int slot = 0; slot < companion.getInventory().getContainerSize(); slot++) {
            ItemStack stack = companion.getInventory().getItem(slot);
            if (stack.is(Items.IRON_AXE)) damage += stack.getDamageValue();
        }
        return damage;
    }

    private TreePosition selectedBase() {
        return new TreePosition(BASE.getX(), BASE.getY(), BASE.getZ());
    }

    private Block logBlock() {
        return species == TreeSpecies.OAK ? Blocks.OAK_LOG : Blocks.BIRCH_LOG;
    }

    private Item logItem() {
        return species == TreeSpecies.OAK ? Items.OAK_LOG : Items.BIRCH_LOG;
    }

    private Item saplingItem() {
        return species == TreeSpecies.OAK ? Items.OAK_SAPLING : Items.BIRCH_SAPLING;
    }

    private Block saplingBlock() {
        return species == TreeSpecies.OAK ? Blocks.OAK_SAPLING : Blocks.BIRCH_SAPLING;
    }

    private String tag(String suffix) {
        return "tree_" + species.name().toLowerCase(java.util.Locale.ROOT) + "_" + suffix;
    }

    private void next(Phase next) {
        phase = next;
        age = 0;
    }

    private void command(String command) {
        owner.sendSystemMessage(Component.literal("MF_M2 COMMAND " + command));
    }

    private void check(boolean condition, String tag) {
        emit(tag, condition);
        if (!condition) throw new IllegalStateException(tag);
    }

    private void emit(String tag, boolean condition) {
        System.out.println("MF_M2_SERVER " + server.getClass().getName() + " " + tag + " "
                + (condition ? "PASS" : "FAIL"));
    }

    private void fail(String reason) {
        finished = true;
        emit(reason, false);
        if (owner != null) owner.sendSystemMessage(Component.literal("MF_M2 FAIL " + reason));
    }

    private enum Phase {
        WAIT_COMPANION, AUTHORIZE, RUNNING, AFTER_TERMINAL, CANCEL_AUTHORIZE, CANCEL_WAIT,
        CHANGE_AUTHORIZE, CHANGE_WAIT
    }
}
