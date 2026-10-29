package io.github.yfyan.minecraftfriends.action;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.yfyan.minecraftfriends.chat.ChatRuntime;
import net.minecraft.core.registries.BuiltInRegistries;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import io.github.yfyan.minecraftfriends.companion.CompanionData;
import io.github.yfyan.minecraftfriends.companion.CompanionRuntime;
import io.github.yfyan.minecraftfriends.task.TaskState;
import io.github.yfyan.minecraftfriends.tree.TreePlan;
import io.github.yfyan.minecraftfriends.tree.TreePlanner;
import io.github.yfyan.minecraftfriends.tree.TreePosition;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/** Server-thread action entrypoints shared by Fabric and Forge. */
public final class ActionRuntime {
    private static final double FOLLOW_DISTANCE = ActionTuning.followDistance(System.getProperty(ActionTuning.FOLLOW_DISTANCE_PROPERTY));
    private static final UUID IDLE_TASK_ID = new UUID(0L, 0L);
    private static final long FOLLOW_TIMEOUT_TICKS = 20L * 60L * 30L;
    private static final long COLLECTION_TIMEOUT_TICKS = 20L * 60L * 5L;
    private static final int PICKUP_TIMEOUT_TICKS = 200;
    private static final int MAX_OWNER_DISTANCE = 48;
    private static final int MAX_AREA_DISTANCE = 32;
    private static final int MAX_OWNERS_PER_TICK = 8;
    private static final int MAX_PATH_SEARCHES_PER_TICK = 4;
    private static final int MAX_AREA_SCANS_PER_TICK = 2;
    private static final Map<MinecraftServer, ServerState> SERVERS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private ActionRuntime() {
    }

    public static void onServerStarted(MinecraftServer server) {
        synchronized (SERVERS) {
            ServerState previous = SERVERS.put(server, new ServerState(server));
            if (previous != null) previous.planner.close();
        }
    }

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        var preview = literal("preview").then(argument("x", IntegerArgumentType.integer(-29999997, 29999997))
                .then(argument("y", IntegerArgumentType.integer(-2047, 2037))
                        .then(argument("z", IntegerArgumentType.integer(-29999997, 29999997))
                                .executes(context -> previewTreeCommand(context.getSource(), new TreePosition(
                                        IntegerArgumentType.getInteger(context, "x"),
                                        IntegerArgumentType.getInteger(context, "y"),
                                        IntegerArgumentType.getInteger(context, "z")))))));
        var confirm = literal("confirm").then(argument("selection", StringArgumentType.word())
                .executes(context -> confirmTreeCommand(context.getSource(),
                        StringArgumentType.getString(context, "selection"), true))
                .then(argument("replant", BoolArgumentType.bool()).executes(context -> confirmTreeCommand(
                        context.getSource(), StringArgumentType.getString(context, "selection"),
                        BoolArgumentType.getBool(context, "replant")))));
        dispatcher.register(literal("mf").then(literal("task").then(literal("tree").then(preview).then(confirm))));
        dispatcher.register(literal("mf").then(literal("task")
                .then(literal("follow").executes(context -> start(context.getSource(), ActionType.FOLLOW)))
                .then(literal("wait").executes(context -> start(context.getSource(), ActionType.WAIT)))
                .then(literal("stop").executes(context -> stop(context.getSource())))
                .then(literal("status").executes(context -> sendStatus(context.getSource())))
                .then(literal("clear").executes(context -> stop(context.getSource())))
                .then(literal("plan").then(argument("instruction", StringArgumentType.greedyString())
                        .executes(context -> plan(context.getSource(),
                                StringArgumentType.getString(context, "instruction")))))
                .then(literal("area")
                        .then(argument("x1", IntegerArgumentType.integer())
                                .then(argument("y1", IntegerArgumentType.integer())
                                        .then(argument("z1", IntegerArgumentType.integer())
                                                .then(argument("x2", IntegerArgumentType.integer())
                                                        .then(argument("y2", IntegerArgumentType.integer())
                                                                .then(argument("z2", IntegerArgumentType.integer())
                                                                        .executes(context -> setArea(
                                                                                context.getSource(),
                                                                                IntegerArgumentType.getInteger(context, "x1"),
                                                                                IntegerArgumentType.getInteger(context, "y1"),
                                                                                IntegerArgumentType.getInteger(context, "z1"),
                                                                                IntegerArgumentType.getInteger(context, "x2"),
                                                                                IntegerArgumentType.getInteger(context, "y2"),
                                                                                IntegerArgumentType.getInteger(context, "z2"))))))))))
                .then(literal("collect").then(literal("oak_log")
                        .then(argument("count", IntegerArgumentType.integer(1, 64))
                                .executes(context -> collect(context.getSource(),
                                        IntegerArgumentType.getInteger(context, "count"))))))));
    }

    /** Read-only exact target preview. Not an AI capability registry; callers must use explicit owner input.
     * Must run on the server thread. A preview grants no destructive authority until confirmed. */
    public record TreePreview(UUID selectionId, TreePlan plan, long expiresAtTick) { }

    private record PendingTree(TreePreview preview, ServerPlayer owner, ServerPlayer companion,
                               ServerLevel level, Map<BlockPos, BlockState> observed, int createdAtTick) { }

    public static Optional<TreeTaskStatus> treeStatus(MinecraftServer server, UUID ownerId) {
        if (!server.isSameThread()) throw new IllegalStateException("Tree status must be read on the server thread.");
        ServerState state = stateIfStarted(server);
        return state == null ? Optional.empty() : Optional.ofNullable(state.treeOutcomes.get(ownerId));
    }

    public static TreePreview previewTree(ServerPlayer owner, TreePosition base) throws CommandSyntaxException {
        requireServerThread(owner);
        CompanionData.Entry entry = requireLiveCompanion(owner);
        ServerState state = state(owner.server);
        OwnerState os = ownerState(state, owner, entry.identity().companionId());
        os.pendingTree = null;
        if (os.treePreviewed && tickAge(owner.server.getTickCount(), os.lastTreePreviewTick) < 20L) {
            throw commandError("树木观测过于频繁，请稍后重试。", null);
        }
        os.treePreviewed = true;
        os.lastTreePreviewTick = owner.server.getTickCount();
        if (os.area == null || tickAge(owner.server.getTickCount(), os.areaSelectedTick) > 20L * 60L * 5L
                || !owner.serverLevel().dimension().equals(os.areaDimension)) {
            throw commandError("请先用 /mf task area 明确包含整棵树干的区域；选区不是拆除所有原木的授权。", null);
        }
        ServerPlayer companion = owner.server.getPlayerList().getPlayer(entry.identity().companionId());
        if (!validAreaBounds(owner.serverLevel(), os.area) || !loaded(owner.serverLevel(), os.area)
                || !areaNear(owner, os.area, MAX_AREA_DISTANCE)
                || !areaNear(companion, os.area, MAX_AREA_DISTANCE)) {
            throw commandError("选区已卸载、越界或距离过远，请重新选择。", null);
        }
        ServerLevel level = owner.serverLevel();
        Map<BlockPos, BlockState> observed = new HashMap<>();
        TreePlanner.Inspection inspection;
        try {
            inspection = TreePlanner.inspect(os.area, base, position -> {
                BlockPos pos = TreeHarvestSupport.pos(position);
                if (pos.getY() < level.getMinBuildHeight() || pos.getY() >= level.getMaxBuildHeight()
                        || !level.hasChunkAt(pos) || !level.getWorldBorder().isWithinBounds(pos)) {
                    return TreePlanner.Cell.UNLOADED;
                }
                BlockState block = level.getBlockState(pos);
                observed.put(pos, block);
                return TreeHarvestSupport.cell(block);
            });
        } catch (IllegalArgumentException invalid) {
            throw commandError("树木坐标或观测边界无效。", invalid);
        }
        if (inspection.plan().isEmpty()) {
            throw commandError("不能安全确定普通树目标：" + inspection.reason() + " / " + inspection.message()
                    + " 请明确另一棵独立树的树干底部和范围；没有执行破坏。", null);
        }
        TreePreview preview = new TreePreview(UUID.randomUUID(), inspection.plan().orElseThrow(),
                Math.min(os.areaExpiresAt, owner.server.getTickCount() + 20L * 30L));
        os.pendingTree = new PendingTree(preview, owner, companion, level, Map.copyOf(observed), owner.server.getTickCount());
        return preview;
    }

    /** One-shot confirmation of a server-issued exact preview, bound to live player instances and dimension.
     * Stops old work only AFTER all validation. Duplicate/stale requests never replace current work. */
    public static int confirmTree(ServerPlayer owner, UUID selectionId, UUID taskId, boolean replant)
            throws CommandSyntaxException {
        requireServerThread(owner);
        CompanionData.Entry entry = requireLiveCompanion(owner);
        ServerState state = state(owner.server);
        OwnerState os = state.owners.get(owner.getUUID());
        PendingTree pending = os == null ? null : os.pendingTree;
        if (state.scheduler.knownTask(taskId)) throw commandError("任务 ID 重复；未重新执行。", null);
        ServerPlayer companion = owner.server.getPlayerList().getPlayer(entry.identity().companionId());
        if (pending == null || !pending.preview().selectionId().equals(selectionId)
                || pending.owner() != owner || pending.companion() != companion
                || pending.level() != owner.serverLevel() || companion.serverLevel() != pending.level()
                || tickAge(owner.server.getTickCount(), pending.createdAtTick()) > 20L * 30L
                || tickAge(owner.server.getTickCount(), os.areaSelectedTick) > 20L * 60L * 5L
                || !pending.preview().plan().authorizedArea().equals(os.area)
                || !owner.serverLevel().dimension().equals(os.areaDimension)
                || !areaNear(owner, os.area, MAX_AREA_DISTANCE)
                || !areaNear(companion, os.area, MAX_AREA_DISTANCE)) {
            throw commandError("树木预览已过期、归属/维度变化或选择已用过，请重新预览。", null);
        }
        for (Map.Entry<BlockPos, BlockState> block : pending.observed().entrySet()) {
            if (!pending.level().hasChunkAt(block.getKey())
                    || !pending.level().getWorldBorder().isWithinBounds(block.getKey())
                    || !pending.level().getBlockState(block.getKey()).equals(block.getValue())) {
                os.pendingTree = null;
                throw commandError("预览后的树木或周边方块已改变，请重新预览；未砍伐。", null);
            }
        }
        ActionRequest request = ActionRequest.fellTree(taskId, owner.getUUID(), pending.preview().plan(), replant);
        cancelOwner(owner.server, state, owner.getUUID(), os);
        submit(state, os, request);
        state.treeRequests.put(taskId, pending);
        putTreeStatus(state, owner.getUUID(), new TreeTaskStatus(taskId, TaskState.QUEUED,
                request.requestedCount(), 0, 0, 0,
                replant ? TreeTaskStatus.Replant.PENDING : TreeTaskStatus.Replant.DISABLED, "已确认精确树干目标。"));
        owner.sendSystemMessage(Component.literal("已确认 " + request.treePlan().species() + " 的 "
                + request.requestedCount() + " 个精确原木；只处理可达目标，补种=" + replant + "。"));
        return 1;
    }

    private static int previewTreeCommand(CommandSourceStack source, TreePosition base) throws CommandSyntaxException {
        ServerPlayer owner = source.getPlayerOrException();
        TreePreview preview = previewTree(owner, base);
        owner.sendSystemMessage(Component.literal("候选 " + preview.plan().species() + "，精确原木 "
                + preview.plan().logs() + "。这是保守树形判断，不保证天然来源；请核对没有房屋/装饰。"
                + " 可达部分才会执行，高处可能保留。30 秒内确认：/mf task tree confirm "
                + preview.selectionId() + " [true|false]（默认真实树苗补种）。未确认不会砍伐。"));
        return 1;
    }

    private static int confirmTreeCommand(CommandSourceStack source, String selection, boolean replant)
            throws CommandSyntaxException {
        UUID id;
        try {
            id = UUID.fromString(selection);
        } catch (IllegalArgumentException invalid) {
            throw commandError("预览编号无效；请复制本次 preview 返回的编号。", invalid);
        }
        return confirmTree(source.getPlayerOrException(), id, UUID.randomUUID(), replant);
    }

    private static void requireServerThread(ServerPlayer owner) throws CommandSyntaxException {
        if (!owner.server.isSameThread() || owner.server.getPlayerList().getPlayer(owner.getUUID()) != owner
                || !owner.isAlive() || CompanionRuntime.isCompanion(owner)) {
            throw commandError("仅当前在线主人可在服务端线程选择并确认树木。", null);
        }
    }

    // Unsigned tick differences remain correct across Minecraft's signed int tick rollover.
    private static long tickAge(int now, int then) {
        return Integer.toUnsignedLong(now - then);
    }

    private static boolean isCollection(ActionRequest request) {
        return request.type() == ActionType.COLLECT_OAK_LOGS || request.type() == ActionType.FELL_TREE;
    }

    private static Item logItem(Execution execution) {
        return execution.request.treePlan() == null ? Items.OAK_LOG
                : TreeHarvestSupport.logItem(execution.request.treePlan().species());
    }

    private static net.minecraft.world.level.block.Block logBlock(Execution execution) {
        return execution.request.treePlan() == null ? Blocks.OAK_LOG
                : TreeHarvestSupport.log(execution.request.treePlan().species());
    }

    private static AreaScan nextTreeLog(ServerLevel level, Execution execution) {
        List<TreePosition> logs = execution.request.treePlan().logs();
        if (execution.logsDestroyed >= logs.size()) return new AreaScan(null, true);
        BlockPos target = TreeHarvestSupport.pos(logs.get(execution.logsDestroyed));
        return new AreaScan(target, level.hasChunkAt(target));
    }

    private static boolean treeTargetsUnchanged(ServerLevel level, Execution execution) {
        if (execution.tree == null || execution.tree.level() != level) return false;
        List<TreePosition> logs = execution.request.treePlan().logs();
        for (int i = execution.logsDestroyed; i < logs.size(); i++) {
            BlockPos pos = TreeHarvestSupport.pos(logs.get(i));
            if (!level.hasChunkAt(pos) || !level.getWorldBorder().isWithinBounds(pos)
                    || !level.getBlockState(pos).equals(execution.tree.observed().get(pos))) return false;
        }
        return true;
    }

    /** Recheck the bounded original observation before each break, allowing natural leaf decay only. */
    private static boolean treeStructureUnchanged(ServerLevel level, Execution execution) {
        List<TreePosition> logs = execution.request.treePlan().logs();
        for (Map.Entry<BlockPos, BlockState> observed : execution.tree.observed().entrySet()) {
            BlockPos pos = observed.getKey();
            if (!level.hasChunkAt(pos) || !level.getWorldBorder().isWithinBounds(pos)) return false;
            BlockState current = level.getBlockState(pos);
            boolean harvested = false;
            for (int i = 0; i < execution.logsDestroyed; i++) {
                if (pos.equals(TreeHarvestSupport.pos(logs.get(i)))) {
                    harvested = true;
                    break;
                }
            }
            if (harvested) {
                if (!current.isAir()) return false;
            } else {
                TreePlanner.Cell original = TreeHarvestSupport.cell(observed.getValue());
                if (original == TreePlanner.Cell.OAK_LEAVES || original == TreePlanner.Cell.BIRCH_LEAVES) {
                    // Vanilla distance/waterlogging updates need not abort; placed persistent leaves do.
                    if (!current.isAir() && TreeHarvestSupport.cell(current) != original) return false;
                } else if (!current.equals(observed.getValue())) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void tickReplant(MinecraftServer server, ServerState state, OwnerState os,
                                    ServerPlayer owner, ServerPlayer companion) {
        Execution execution = os.execution;
        TreePlan plan = execution.request.treePlan();
        BlockPos base = TreeHarvestSupport.pos(plan.base());
        if (!collectPermissionStillValid(os, owner, companion, execution.request.area())) {
            execution.replant = TreeTaskStatus.Replant.BLOCKED;
        } else if (!TreeHarvestSupport.hasSapling(companion, plan.species())) {
            execution.replant = TreeTaskStatus.Replant.NO_SAPLING;
        } else if (tickAge(server.getTickCount(), execution.replantStartedAtTick) > 200L) {
            execution.replant = TreeTaskStatus.Replant.UNREACHABLE;
        } else {
            BlockPos approach = TreeHarvestSupport.approach(companion, base, base.below(), true);
            if (approach == null) {
                execution.replant = TreeTaskStatus.Replant.UNREACHABLE;
            } else if (!companion.blockPosition().equals(approach)) {
                navigate(server, state, os, owner, companion, approach, 0);
                return;
            } else {
                execution.replant = TreeHarvestSupport.plant(owner, companion, base,
                        execution.tree.observed().get(base.below()), plan.species());
            }
        }
        execution.replanting = false;
        completeCollection(server, state, os, owner, companion, execution.returnState, execution.returnMessage);
    }

    private static void updateTreeStatus(ServerState state, Execution execution, TaskState taskState, String message) {
        if (execution.tree == null) return;
        putTreeStatus(state, execution.request.ownerId(), new TreeTaskStatus(execution.request.taskId(), taskState,
                execution.request.requestedCount(), execution.logsDestroyed, execution.logsPicked,
                execution.delivered, execution.replant, message));
    }

    private static void putTreeStatus(ServerState state, UUID ownerId, TreeTaskStatus status) {
        state.treeOutcomes.put(ownerId, status);
        while (state.treeOutcomes.size() > 256) {
            state.treeOutcomes.remove(state.treeOutcomes.keySet().iterator().next());
        }
    }

    /** Loader dimension/death hooks invalidate even a rapid leave-and-return between action ticks. */
    public static void onPlayerLifecycleChange(ServerPlayer player) {
        ServerState state = stateIfStarted(player.server);
        if (state == null) return;
        CompanionData.Entry entry = CompanionRuntime.data(player.server).findByCompanion(player.getUUID()).orElse(null);
        UUID ownerId = entry == null ? player.getUUID() : entry.identity().ownerId();
        cancelOwner(player.server, state, ownerId, state.owners.get(ownerId));
        state.owners.remove(ownerId);
    }

    /** Read-only snapshot for diagnostics and acceptance polling. Must be called on the server thread. */
    public static ActionStatus status(MinecraftServer server, UUID ownerId) {
        ServerState state = stateIfStarted(server);
        if (state == null) {
            return idleStatus();
        }
        return state.scheduler.status(ownerId).orElseGet(ActionRuntime::idleStatus);
    }

    public static void cancelPlanning(MinecraftServer server, UUID ownerId) {
        ServerState state = stateIfStarted(server);
        if (state != null) state.planner.cancel(ownerId);
    }

    public static void cancelAllPlanning(MinecraftServer server) {
        ServerState state = stateIfStarted(server);
        if (state != null) state.planner.cancelAll();
    }

    public static ActionPlanner.Status planningStatus(MinecraftServer server, UUID ownerId) {
        ServerState state = stateIfStarted(server);
        return state == null ? new ActionPlanner.Status(IDLE_TASK_ID, ActionPlanner.State.IDLE, "")
                : state.planner.status(ownerId);
    }

    /** Server-thread only. Never loads chunks or exposes live objects, NBT, containers or other players. */
    public static java.util.Optional<ActionPerception> perception(MinecraftServer server, UUID ownerId) {
        ServerPlayer owner = server.getPlayerList().getPlayer(ownerId);
        if (owner == null || !owner.isAlive()) return java.util.Optional.empty();
        try {
            CompanionData.Entry entry = requireLiveCompanion(owner);
            ServerState state = stateIfStarted(server);
            if (state == null) return java.util.Optional.empty();
            ServerPlayer companion = server.getPlayerList().getPlayer(entry.identity().companionId());
            OwnerState os = state.owners.get(ownerId);
            BlockArea area = os != null && os.area != null && server.getTickCount() <= os.areaExpiresAt
                    && owner.serverLevel().dimension().equals(os.areaDimension) ? os.area : null;
            List<ActionPerception.Block> blocks = new ArrayList<>();
            BlockPos origin = companion.blockPosition();
            ServerLevel level = companion.serverLevel();
            // Examine at most 245 positions; retain at most 64 non-air observations to bound the prompt.
            for (int dy = -2; dy <= 2; dy++) for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
                BlockPos pos = origin.offset(dx, dy, dz);
                if (blocks.size() >= 64 || !level.hasChunkAt(pos) || level.isOutsideBuildHeight(pos)) continue;
                BlockState block = level.getBlockState(pos);
                if (!block.isAir()) blocks.add(new ActionPerception.Block(pos.getX(), pos.getY(), pos.getZ(),
                        BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString()));
            }
            List<ActionPerception.InventorySlot> inventory = new ArrayList<>();
            for (int slot = 0; slot < Math.min(41, companion.getInventory().getContainerSize()); slot++) {
                ItemStack stack = companion.getInventory().getItem(slot);
                if (!stack.isEmpty()) inventory.add(new ActionPerception.InventorySlot(slot,
                        BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), Math.min(99, stack.getCount())));
            }
            return java.util.Optional.of(new ActionPerception(state.worldSession, ownerId, companion.getUUID(),
                    level.dimension().location().toString(), server.getTickCount(), position(owner), position(companion),
                    owner.getHealth(), companion.getHealth(), blocks, inventory, area));
        } catch (CommandSyntaxException unavailable) {
            return java.util.Optional.empty();
        }
    }

    private static ActionPerception.Position position(ServerPlayer player) {
        return new ActionPerception.Position(player.getX(), player.getY(), player.getZ());
    }

    private static int plan(CommandSourceStack source, String instruction) throws CommandSyntaxException {
        ServerPlayer owner = source.getPlayerOrException();
        CompanionData.Entry entry = requireLiveCompanion(owner);
        if (!owner.isAlive()) throw commandError("主人当前不可执行任务。", null);
        ChatRuntime.PlanningAccess access = ChatRuntime.planningAccess(owner.server);
        if (access == null) throw commandError("任务规划不可用；请检查现有模型配置。", null);
        ServerState state = state(owner.server);
        OwnerState os = ownerState(state, owner, entry.identity().companionId());
        if (owner.server.getTickCount() < os.nextPlanTick) throw commandError("请稍后再请求规划。", null);
        if (instruction.isBlank() || instruction.length() > ActionPlanner.MAX_INSTRUCTION_CHARS)
            throw commandError("任务描述限 1 至 512 字符。", null);
        BlockArea area = os.area;
        var areaDimension = os.areaDimension;
        long expires = os.areaExpiresAt;
        cancelOwner(owner.server, state, owner.getUUID(), os);
        os.area = area;
        os.areaDimension = areaDimension;
        os.areaExpiresAt = expires;
        os.dimension = owner.serverLevel().dimension();
        ActionPerception snapshot = perception(owner.server, owner.getUUID()).orElseThrow(
                () -> commandError("无法获取当前伙伴观测。", null));
        ServerPlayer companion = owner.server.getPlayerList().getPlayer(entry.identity().companionId());
        os.nextPlanTick = owner.server.getTickCount() + 20;
        try {
            state.planner.request(snapshot, instruction, access.provider(), access.worker(), access.timeout(),
                    access.contextBudget(), new ActionPlanner.Handler() {
                        @Override
                        public boolean revalidate(ActionPlanner.Ticket ticket, ActionPerception captured, ActionPlan plan) {
                            if (stateIfStarted(owner.server) != state || !state.worldSession.equals(captured.worldSession())
                                    || owner.server.getPlayerList().getPlayer(owner.getUUID()) != owner
                                    || owner.server.getPlayerList().getPlayer(companion.getUUID()) != companion
                                    || !owner.isAlive() || !companion.isAlive() || !CompanionRuntime.isEnabled()
                                    || !owner.serverLevel().dimension().location().toString().equals(captured.dimension())
                                    || !companion.serverLevel().dimension().equals(owner.serverLevel().dimension())
                                    || owner.server.getTickCount() - captured.capturedTick() > 600
                                    || ChatRuntime.planningAccess(owner.server) == null) return false;
                            try {
                                if (!requireLiveCompanion(owner).identity().companionId().equals(captured.companionId())) return false;
                            } catch (CommandSyntaxException unavailable) { return false; }
                            if (plan.type() != ActionType.COLLECT_OAK_LOGS) return true;
                            return captured.authorizedArea() != null && captured.authorizedArea().equals(os.area)
                                    && owner.server.getTickCount() <= os.areaExpiresAt
                                    && owner.serverLevel().dimension().equals(os.areaDimension)
                                    && validAreaBounds(owner.serverLevel(), os.area) && loaded(owner.serverLevel(), os.area)
                                    && areaNear(owner, os.area, MAX_AREA_DISTANCE) && areaNear(companion, os.area, MAX_AREA_DISTANCE);
                        }
                        @Override
                        public void apply(ActionPlanner.Ticket ticket, ActionPerception captured, ActionPlan result) {
                            try {
                                if(result.type()==ActionType.WAIT && ActionPhraseParser.needsClarification(instruction)) {
                                    owner.sendSystemMessage(Component.literal("采集请求还不明确：你要多少个橡木原木？请先明确数量和区域；本次没有启动采集。"));
                                    return;
                                }
                                switch (result.type()) {
                                    case STOP -> stopFor(owner);
                                    case FOLLOW, WAIT -> startFor(owner, result.type(), ticket.requestId());
                                    case COLLECT_OAK_LOGS -> collectForOwner(owner, result.count(), ticket.requestId());
                                    default -> throw new IllegalArgumentException("Unsupported action");
                                }
                            } catch (CommandSyntaxException refused) { throw new IllegalStateException(refused); }
                        }
                        @Override
                        public void onOutcome(ActionPlanner.Status outcome) {
                            if (stateIfStarted(owner.server) == state
                                    && owner.server.getPlayerList().getPlayer(owner.getUUID()) == owner)
                                owner.sendSystemMessage(Component.literal("任务规划 " + outcome.state() + " / " + outcome.reason()
                                        + "；实际执行结果请查 /mf task status。"));
                        }
                    });
        } catch (IllegalArgumentException | IllegalStateException refused) {
            throw commandError("规划请求被拒绝：指令、上下文预算或队列超限。", null);
        }
        owner.sendSystemMessage(Component.literal("已请求后台结构化规划；可随时 /mf task stop。"));
        return 1;
    }

    public static void onTick(MinecraftServer server) {
        ServerState state = stateIfStarted(server);
        if (state == null) {
            return;
        }
        if (!CompanionRuntime.isEnabled()) {
            for (UUID ownerId : List.copyOf(state.owners.keySet())) {
                cancelOwner(server, state, ownerId, state.owners.get(ownerId));
            }
            state.owners.clear();
            state.requests.clear();
            return;
        }
        state.planner.expire();
        List<UUID> ownerIds = new ArrayList<>(state.owners.keySet());
        if (ownerIds.isEmpty()) {
            return;
        }
        int first = Math.floorMod(state.nextOwnerCursor, ownerIds.size());
        int ownerBudget = Math.min(MAX_OWNERS_PER_TICK, ownerIds.size());
        state.pathSearchesRemaining = MAX_PATH_SEARCHES_PER_TICK;
        state.areaScansRemaining = MAX_AREA_SCANS_PER_TICK;
        for (int offset = 0; offset < ownerBudget; offset++) {
            UUID ownerId = ownerIds.get((first + offset) % ownerIds.size());
            OwnerState ownerState = state.owners.get(ownerId);
            if (ownerState == null) {
                continue;
            }
            ServerPlayer owner = server.getPlayerList().getPlayer(ownerId);
            ServerPlayer companion = server.getPlayerList().getPlayer(ownerState.companionId);
            if (owner == null || owner != ownerState.owner || companion == null
                    || ownerState.execution != null && companion != ownerState.execution.companion
                    || !CompanionRuntime.isCompanion(companion)
                    || !owner.isAlive() || !companion.isAlive()
                    || !owner.serverLevel().dimension().equals(ownerState.dimension)
                    || !companion.serverLevel().dimension().equals(ownerState.dimension)
                    || owner.distanceToSqr(companion) > (double) MAX_OWNER_DISTANCE * MAX_OWNER_DISTANCE) {
                if (owner != null && ownerState.execution != null
                        && isCollection(ownerState.execution.request)
                        && ownerState.execution.logsPicked > 0) {
                    owner.sendSystemMessage(Component.literal("采集任务已取消；伙伴已拾取的任务原木仍留在伙伴背包，未远距离转移。"));
                }
                cancelOwner(server, state, ownerId, ownerState);
                state.owners.remove(ownerId);
                continue;
            }
            if (!(companion instanceof io.github.yfyan.minecraftfriends.companion.CompanionServerPlayer)) {
                io.github.yfyan.minecraftfriends.companion.CompanionServerPlayer.touchLocalItems(companion);
            }
            if (ownerState.execution == null) {
                ActionStatus next = state.scheduler.startNext(ownerId).orElse(null);
                if (next == null) {
                    applyTravel(companion, 0.0F, false);
                    continue;
                }
                ActionRequest request = state.requests.get(next.taskId());
                if (request == null) {
                    state.scheduler.finish(next.taskId(), TaskState.FAILED, 0, 0, "missing request");
                    continue;
                }
                PendingTree tree = state.treeRequests.get(request.taskId());
                if (request.type() == ActionType.FELL_TREE && (tree == null || tree.owner() != owner
                        || tree.companion() != companion || tree.level() != companion.serverLevel())) {
                    cancelOwner(server, state, ownerId, ownerState);
                    continue;
                }
                ownerState.execution = new Execution(request, server.getTickCount(), companion, tree);
            }
            tickAction(server, state, ownerState, owner, companion);
        }
        state.nextOwnerCursor = (first + 1) % ownerIds.size();
    }

    public static void onOwnerLogout(ServerPlayer owner) {
        cancelOwner(owner.server, stateIfStarted(owner.server), owner.getUUID(), null);
    }

    public static void onCompanionDeath(ServerPlayer companion) {
        ServerState state = stateIfStarted(companion.server);
        if (state == null) {
            return;
        }
        CompanionData.Entry entry = CompanionRuntime.data(companion.server)
                .findByCompanion(companion.getUUID()).orElse(null);
        if (entry != null) {
            cancelOwner(companion.server, state, entry.identity().ownerId(),
                    state.owners.get(entry.identity().ownerId()));
            state.owners.remove(entry.identity().ownerId());
        }
    }

    /** Called only by the local companion's vanilla Player.take callback after actual item pickup. */
    public static void onItemPickedUp(ServerPlayer companion, UUID itemEntityId, int amount,
                                      int stackCountBeforePickup) {
        if (amount <= 0 || !CompanionRuntime.isCompanion(companion)) {
            return;
        }
        ServerState state = stateIfStarted(companion.server);
        if (state == null) {
            return;
        }
        CompanionData.Entry owner = CompanionRuntime.data(companion.server)
                .findByCompanion(companion.getUUID()).orElse(null);
        OwnerState ownerState = owner == null ? null : state.owners.get(owner.identity().ownerId());
        Execution execution = ownerState == null ? null : ownerState.execution;
        if (execution == null || !isCollection(execution.request)) {
            return;
        }
        int expected = execution.pendingDrops.getOrDefault(itemEntityId, 0);
        boolean directDrop = expected > 0;
        if (execution.tree != null && (!directDrop || stackCountBeforePickup != expected)) return; // Fail closed on unprovable merged provenance.
        if (!directDrop) {
            int baseline = execution.dropCountsBefore.getOrDefault(itemEntityId, 0);
            if (baseline > 0) {
                int outstanding = execution.pendingDrops.values().stream().mapToInt(Integer::intValue).sum();
                expected = Math.min(outstanding, Math.max(0, stackCountBeforePickup - baseline));
            }
        }
        int eligible = Math.max(0, Math.min(execution.logsDestroyed,
                execution.request.requestedCount()) - execution.logsPicked);
        int credited = execution.tree == null ? Math.min(Math.min(expected, amount), eligible)
                : TreeDropAccounting.credit(expected, stackCountBeforePickup, amount,
                        execution.logsDestroyed, execution.logsPicked, execution.request.requestedCount());
        if (credited <= 0) {
            return;
        }
        execution.logsPicked += credited;
        updateTreeStatus(state, execution, TaskState.RUNNING, "已实际拾取任务掉落物。");
        consumePendingDrop(execution, itemEntityId, directDrop, credited);
        state.scheduler.updateProgress(execution.request.taskId(), execution.logsPicked, 0,
                "伙伴已正常拾取 " + execution.logsPicked + "/" + execution.request.requestedCount());
    }

    private static void consumePendingDrop(Execution execution, UUID itemEntityId, boolean directDrop, int count) {
        int remaining = count;
        if (directDrop) {
            int pending = execution.pendingDrops.getOrDefault(itemEntityId, 0);
            int consumed = Math.min(pending, remaining);
            if (pending == consumed) {
                execution.pendingDrops.remove(itemEntityId);
            } else {
                execution.pendingDrops.put(itemEntityId, pending - consumed);
            }
            remaining -= consumed;
        }
        if (remaining > 0) {
            for (UUID id : List.copyOf(execution.pendingDrops.keySet())) {
                if (remaining == 0) {
                    break;
                }
                int pending = execution.pendingDrops.getOrDefault(id, 0);
                int consumed = Math.min(pending, remaining);
                if (pending == consumed) {
                    execution.pendingDrops.remove(id);
                } else {
                    execution.pendingDrops.put(id, pending - consumed);
                }
                remaining -= consumed;
            }
        }
    }

    public static void onServerStopping(MinecraftServer server) {
        ServerState state = stateIfStarted(server);
        if (state == null) {
            return;
        }
        for (UUID ownerId : List.copyOf(state.owners.keySet())) {
            cancelOwner(server, state, ownerId, state.owners.get(ownerId));
        }
        synchronized (SERVERS) {
            state.planner.close();
            SERVERS.remove(server);
        }
    }

    /** Handles only exact deterministic phrases from the owner's private addressed chat. */
    public static boolean handleDeterministicIntent(ServerPlayer owner, String message) {
        ActionPlan plan = ActionPhraseParser.parse(message).orElse(null);
        if (plan == null) {
            if(ActionPhraseParser.needsClarification(message)) {
                owner.sendSystemMessage(Component.literal("你要收集多少个橡木原木？请明确1至64的数量并先选择区域；本次没有启动新的采集任务。"));
                return true;
            }
            return false;
        }
        try {
            switch (plan.type()) {
                case STOP -> stopFor(owner);
                case COLLECT_OAK_LOGS -> collectForOwner(owner, plan.count());
                case FOLLOW, WAIT -> startFor(owner, plan.type());
                case NONE, FELL_TREE -> { return false; }
            }
        } catch (CommandSyntaxException refused) {
            owner.sendSystemMessage(Component.literal(refused.getMessage()));
        }
        return true;
    }

    private static int start(CommandSourceStack source, ActionType type) throws CommandSyntaxException {
        ServerPlayer owner = source.getPlayerOrException();
        return startFor(owner, type);
    }

    private static int startFor(ServerPlayer owner, ActionType type) throws CommandSyntaxException {
        return startFor(owner, type, UUID.randomUUID());
    }

    private static int startFor(ServerPlayer owner, ActionType type, UUID taskId) throws CommandSyntaxException {
        CompanionData.Entry entry = requireLiveCompanion(owner);
        ServerState serverState = state(owner.server);
        OwnerState ownerState = ownerState(serverState, owner, entry.identity().companionId());
        cancelOwner(owner.server, serverState, owner.getUUID(), ownerState);
        ActionRequest request = type == ActionType.FOLLOW
                ? ActionRequest.follow(taskId, owner.getUUID())
                : ActionRequest.waitForOwner(taskId, owner.getUUID());
        submit(serverState, ownerState, request);
        owner.sendSystemMessage(Component.literal(type == ActionType.FOLLOW
                ? "伙伴开始以正常移动跟随，目标距离 " + FOLLOW_DISTANCE + " 格。" : "伙伴已停止移动并等待。"));
        return 1;
    }

    private static int stop(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer owner = source.getPlayerOrException();
        stopFor(owner);
        return 1;
    }

    private static void stopFor(ServerPlayer owner) {
        ServerState serverState = stateIfStarted(owner.server);
        if (serverState != null) {
            cancelOwner(owner.server, serverState, owner.getUUID(), serverState.owners.get(owner.getUUID()));
        }
        owner.sendSystemMessage(Component.literal("已取消伙伴动作并清除输入。"));
    }

    private static int sendStatus(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer owner = source.getPlayerOrException();
        ActionStatus current = status(owner.server, owner.getUUID());
        owner.sendSystemMessage(Component.literal("规划 " + planningStatus(owner.server, owner.getUUID())));
        owner.sendSystemMessage(Component.literal("任务 " + current.taskId() + " / " + current.type()
                + " / " + current.state() + " / " + current.completed() + "/" + current.requested()
                + " / 已交付 " + current.delivered() + " / " + current.message()));
        treeStatus(owner.server, owner.getUUID()).ifPresent(tree -> owner.sendSystemMessage(Component.literal(
                "树木 " + tree.taskId() + " / " + tree.state() + " / 已砍 " + tree.harvested() + "/" + tree.planned()
                        + " / 已收 " + tree.pickedUp() + " / 已交 " + tree.delivered()
                        + " / 补种 " + tree.replant() + " / " + tree.message())));
        return 1;
    }

    private static int setArea(CommandSourceStack source, int x1, int y1, int z1,
                               int x2, int y2, int z2) throws CommandSyntaxException {
        ServerPlayer owner = source.getPlayerOrException();
        CompanionData.Entry entry = requireLiveCompanion(owner);
        BlockArea area;
        try {
            area = new BlockArea(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                    Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
        } catch (IllegalArgumentException invalidArea) {
            throw commandError("采集区域无效：范围最多 4096 个方块。", invalidArea);
        }
        ServerPlayer companion = owner.server.getPlayerList().getPlayer(entry.identity().companionId());
        if (!areaNear(owner, area, MAX_AREA_DISTANCE) || !areaNear(companion, area, MAX_AREA_DISTANCE)) {
            throw commandError("区域必须在主人和伙伴 32 格范围内。", null);
        }
        ServerLevel level = owner.serverLevel();
        if (!validAreaBounds(level, area) || !loaded(level, area)) {
            throw commandError("区域包含未加载区块；区域未授权。", null);
        }
        ServerState serverState = state(owner.server);
        OwnerState ownerState = ownerState(serverState, owner, entry.identity().companionId());
        serverState.planner.cancel(owner.getUUID());
        ownerState.pendingTree = null;
        ownerState.area = area;
        ownerState.areaDimension = level.dimension();
        ownerState.areaSelectedTick = owner.server.getTickCount();
        ownerState.areaExpiresAt = owner.server.getTickCount() + 20L * 60L * 5L;
        owner.sendSystemMessage(Component.literal("已授权一次性采集区域：" + area.minX() + " " + area.minY()
                + " " + area.minZ() + " 到 " + area.maxX() + " " + area.maxY() + " " + area.maxZ()));
        return 1;
    }

    private static int collect(CommandSourceStack source, int count) throws CommandSyntaxException {
        return collectForOwner(source.getPlayerOrException(), count);
    }

    private static int collectForOwner(ServerPlayer owner, int count) throws CommandSyntaxException {
        return collectForOwner(owner, count, UUID.randomUUID());
    }

    private static int collectForOwner(ServerPlayer owner, int count, UUID taskId) throws CommandSyntaxException {
        CompanionData.Entry entry = requireLiveCompanion(owner);
        ServerState serverState = state(owner.server);
        OwnerState ownerState = ownerState(serverState, owner, entry.identity().companionId());
        if (ownerState.area == null || owner.server.getTickCount() > ownerState.areaExpiresAt
                || !owner.serverLevel().dimension().equals(ownerState.areaDimension)) {
            ownerState.area = null;
            throw commandError("请先在当前维度执行 /mf task area 选择一次性区域。", null);
        }
        if (!validAreaBounds(owner.serverLevel(), ownerState.area)
                || !loaded(owner.serverLevel(), ownerState.area)
                || !areaNear(owner, ownerState.area, MAX_AREA_DISTANCE)
                || !areaNear(owner.server.getPlayerList().getPlayer(entry.identity().companionId()),
                ownerState.area, MAX_AREA_DISTANCE)) {
            ownerState.area = null;
            throw commandError("区域加载状态或距离已变化；请重新授权。", null);
        }
        ActionRequest request = ActionRequest.collectOakLogs(
                taskId, owner.getUUID(), ownerState.area, count);
        ownerState.area = null;
        ownerState.areaDimension = null;
        cancelOwner(owner.server, serverState, owner.getUUID(), ownerState);
        submit(serverState, ownerState, request);
        owner.sendSystemMessage(Component.literal("已接受一次性区域内的橡木原木采集任务。"));
        return 1;
    }

    private static void submit(ServerState state, OwnerState ownerState, ActionRequest request)
            throws CommandSyntaxException {
        ActionScheduler.SubmitResult result = state.scheduler.submit(request);
        if (result != ActionScheduler.SubmitResult.ACCEPTED) {
            throw commandError(result == ActionScheduler.SubmitResult.DUPLICATE
                    ? "任务 ID 重复。" : "伙伴动作队列已满。", null);
        }
        state.requests.put(request.taskId(), request);
        ownerState.dimension = ownerState.owner.serverLevel().dimension();
    }

    private static CompanionData.Entry requireLiveCompanion(ServerPlayer owner) throws CommandSyntaxException {
        if (!CompanionRuntime.isEnabled()) {
            throw commandError("Minecraft Friends 已在配置中关闭。", null);
        }
        CompanionData.Entry entry = CompanionRuntime.data(owner.server).find(owner.getUUID()).orElse(null);
        if (entry == null || !entry.active()) {
            throw commandError("你没有已启用的伙伴。", null);
        }
        ServerPlayer companion = owner.server.getPlayerList().getPlayer(entry.identity().companionId());
        if (companion == null || !CompanionRuntime.isCompanion(companion) || !companion.isAlive()) {
            throw commandError("伙伴当前未在线或不可用。", null);
        }
        if (!owner.serverLevel().dimension().equals(companion.serverLevel().dimension())
                || owner.distanceToSqr(companion) > (double) MAX_OWNER_DISTANCE * MAX_OWNER_DISTANCE) {
            throw commandError("主人和伙伴必须在同一维度且相距不超过 48 格。", null);
        }
        return entry;
    }

    private static OwnerState ownerState(ServerState serverState, ServerPlayer owner, UUID companionId) {
        OwnerState ownerState = serverState.owners.computeIfAbsent(owner.getUUID(), ignored -> new OwnerState());
        ownerState.owner = owner;
        ownerState.companionId = companionId;
        if (ownerState.dimension == null) {
            ownerState.dimension = owner.serverLevel().dimension();
        }
        return ownerState;
    }

    private static void tickAction(MinecraftServer server, ServerState state, OwnerState ownerState,
                                   ServerPlayer owner, ServerPlayer companion) {
        Execution execution = ownerState.execution;
        if (execution == null) {
            return;
        }
        execution.elapsedTicks++;
        if (execution.request.type() == ActionType.WAIT) {
            applyTravel(companion, 0.0F, false);
            return;
        }
        if (execution.request.type() == ActionType.FOLLOW) {
            if (execution.elapsedTicks > FOLLOW_TIMEOUT_TICKS) {
                finish(server, state, ownerState, TaskState.TIMED_OUT, "跟随超时。" );
            } else {
                tickFollow(server, state, ownerState, owner, companion);
            }
            return;
        }
        if (execution.request.type() == ActionType.FELL_TREE && execution.tree == null) {
            finish(server, state, ownerState, TaskState.FAILED, "树木确认上下文已失效，未执行。");
            return;
        }
        if (isCollection(execution.request)) {
            tickCollect(server, state, ownerState, owner, companion);
            return;
        }
        finish(server, state, ownerState, TaskState.FAILED, "该动作当前无法执行。" );
    }

    private static void tickFollow(MinecraftServer server, ServerState state, OwnerState ownerState,
                                   ServerPlayer owner, ServerPlayer companion) {
        Execution execution = ownerState.execution;
        if (owner.distanceToSqr(companion) <= FOLLOW_DISTANCE * FOLLOW_DISTANCE) {
            execution.path = List.of();
            execution.noPathTicks = 0;
            execution.stuckTicks = 0;
            applyTravel(companion, 0.0F, false);
            return;
        }
        navigate(server, state, ownerState, owner, companion, owner.blockPosition(), ActionTuning.followGoalRadius(FOLLOW_DISTANCE));
    }

    private static void tickCollect(MinecraftServer server, ServerState state, OwnerState ownerState,
                                    ServerPlayer owner, ServerPlayer companion) {
        Execution execution = ownerState.execution;
        ActionRequest request = execution.request;
        BlockArea area = request.area();
        ServerLevel level = companion.serverLevel();
        state.scheduler.updateProgress(request.taskId(), execution.logsPicked, 0,
                "已砍 " + execution.logsDestroyed + "，已正常拾取 " + execution.logsPicked + "/" + request.requestedCount());
        updateTreeStatus(state, execution, TaskState.RUNNING, "执行中。");

        if (execution.returning) {
            tickReturnToOwner(server, state, ownerState, owner, companion);
            return;
        }

        if (execution.replanting) {
            tickReplant(server, state, ownerState, owner, companion);
            return;
        }

        if (execution.tree == null ? execution.elapsedTicks > COLLECTION_TIMEOUT_TICKS
                : tickAge(server.getTickCount(), execution.startedAtTick) > COLLECTION_TIMEOUT_TICKS) {
            completeCollection(server, state, ownerState, owner, companion, TaskState.TIMED_OUT,
                    "采集超时，按实际拾取数量结算。");
            return;
        }
        if (!collectPermissionStillValid(ownerState, owner, companion, area)) {
            completeCollection(server, state, ownerState, owner, companion, TaskState.FAILED,
                    "维度、距离或授权状态变化，已停止采集。");
            return;
        }
        if (execution.logsPicked < request.requestedCount() && logCapacity(companion, logItem(execution)) == 0) {
            completeCollection(server, state, ownerState, owner, companion, TaskState.FAILED,
                    "伙伴背包已满，已停止挖掘并按实际数量返回。");
            return;
        }
        if (execution.logsPicked >= request.requestedCount()) {
            completeCollection(server, state, ownerState, owner, companion, TaskState.SUCCEEDED,
                    "已拾取到请求数量，正在交付主人。");
            return;
        }
        if (execution.miningPos != null) {
            tickMining(server, state, ownerState, owner, companion, area);
            return;
        }
        if (execution.pickupAnchor != null && execution.logsPicked < execution.logsDestroyed) {
            if (execution.tree == null ? server.getTickCount() > execution.pickupDeadlineTick
                    : tickAge(server.getTickCount(), execution.pickupStartedAtTick) > PICKUP_TIMEOUT_TICKS) {
                completeCollection(server, state, ownerState, owner, companion, TaskState.FAILED,
                        "掉落物未被伙伴正常拾取；按实际数量结算。");
                return;
            }
            refreshMergedDropAliases(companion.serverLevel(), execution);
            ItemEntity item = nearestTaskDrop(companion, execution.pickupAnchor, execution);
            if (item != null) {
                BlockPos approach = pickupApproach(companion, item);
                if (approach == null) {
                    applyTravel(companion, 0.0F, false);
                } else if (companion.blockPosition().equals(approach)) {
                    // Items may lie under a low overhang: remain in a standable cell and
                    // approach its edge using vanilla collision, never enter the solid cell.
                    double dx = item.getX() - companion.getX(), dz = item.getZ() - companion.getZ();
                    companion.setYRot((float)(Math.atan2(dz, dx) * 180.0 / Math.PI - 90.0));
                    companion.setYHeadRot(companion.getYRot());
                    applyTravel(companion, dx*dx+dz*dz > 0.09 ? 1.0F : 0.0F, false);
                } else {
                    navigate(server, state, ownerState, owner, companion, approach, 0);
                }
            } else {
                applyTravel(companion, 0.0F, false);
            }
            return;
        }
        execution.pickupAnchor = null;
        if (execution.logsDestroyed >= request.requestedCount()) {
            completeCollection(server, state, ownerState, owner, companion, TaskState.FAILED,
                    "所有目标方块已处理，但拾取数量不足；按实际数量结算。");
            return;
        }
        if (execution.targetLog == null) {
            if (state.areaScansRemaining <= 0) {
                return;
            }
            state.areaScansRemaining--;
            AreaScan scan = execution.tree == null ? findOakLog(level, area)
                    : nextTreeLog(level, execution);
            if (!scan.allVisitedChunksLoaded()) {
                completeCollection(server, state, ownerState, owner, companion, TaskState.FAILED,
                        "授权区域出现未加载区块；已停止采集。");
                return;
            }
            execution.targetLog = scan.target();
            if (execution.targetLog == null) {
                completeCollection(server, state, ownerState, owner, companion, TaskState.FAILED,
                        "授权目标内没有更多可处理原木；按实际数量结算。");
                return;
            }
        }
        BlockPos target = execution.targetLog;
        if (!area.contains(target.getX(), target.getY(), target.getZ())
                || !level.hasChunkAt(target)
                || !level.getWorldBorder().isWithinBounds(target)
                || level.getBlockState(target).isAir()) {
            completeCollection(server, state, ownerState, owner, companion, TaskState.FAILED,
                    "目标位置已变化或离开加载区域；已停止采集。");
            return;
        }
        if (!level.getBlockState(target).is(logBlock(execution))) {
            if (execution.tree != null) {
                completeCollection(server, state, ownerState, owner, companion, TaskState.FAILED,
                        "已确认的树木目标已改变；没有扩展到其他原木。");
                return;
            }
            execution.targetLog = null;
            execution.path = List.of();
            return;
        }
        if (!companion.gameMode.isSurvival()) {
            completeCollection(server, state, ownerState, owner, companion, TaskState.FAILED,
                    "采集只允许生存模式伙伴执行。");
            return;
        }
        if (execution.tree != null) {
            if (!treeTargetsUnchanged(level, execution) || !treeStructureUnchanged(level, execution)
                    || !TreeHarvestSupport.equipAxe(companion)) {
                completeCollection(server, state, ownerState, owner, companion, TaskState.FAILED,
                        "目标树已改变或缺少可用斧头；已停止伐木。");
                return;
            }
            if (!TreeHarvestSupport.canMine(owner, companion, target)) {
                BlockPos approach = TreeHarvestSupport.approach(companion,
                        TreeHarvestSupport.pos(request.treePlan().base()), target, false);
                if (approach == null || companion.blockPosition().equals(approach)) {
                    completeCollection(server, state, ownerState, owner, companion, TaskState.FAILED,
                            "剩余原木不可达、被遮挡或权限拒绝；未使用攀升、垫块、飞行或传送。");
                } else {
                    navigate(server, state, ownerState, owner, companion, approach, 0);
                }
                return;
            }
        }
        double distance = companion.distanceToSqr(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
        if (execution.tree == null && distance > 25.0) {
            navigate(server, state, ownerState, owner, companion, target, 2);
            return;
        }
        applyTravel(companion, 0.0F, false);
        BlockState block = level.getBlockState(target);
        float progressPerTick = block.getDestroyProgress(companion, level, target);
        if (progressPerTick <= 0.0F) {
            completeCollection(server, state, ownerState, owner, companion, TaskState.FAILED,
                    "目标原木无法在当前生存规则下挖掘。");
            return;
        }
        execution.miningPos = target.immutable();
        execution.miningDirection = Direction.UP;
        execution.miningProgress = 0.0F;
        execution.miningTicks = 0;
        execution.dropCountsBefore = logDropCounts(level, target, logItem(execution));
        companion.gameMode.handleBlockBreakAction(target, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                execution.miningDirection, level.getMaxBuildHeight(), execution.nextSequence++);
        if (level.getBlockState(target).isAir()) {
            recordMinedLog(server, state, ownerState, level, target);
        }
    }

    private static void tickMining(MinecraftServer server, ServerState state, OwnerState ownerState,
                                   ServerPlayer owner, ServerPlayer companion, BlockArea area) {
        Execution execution = ownerState.execution;
        BlockPos target = execution.miningPos;
        ServerLevel level = companion.serverLevel();
        if (!collectPermissionStillValid(ownerState, owner, companion, area)
                || !area.contains(target.getX(), target.getY(), target.getZ())
                || !level.hasChunkAt(target)
                || !level.getWorldBorder().isWithinBounds(target)
                || companion.distanceToSqr(target.getX() + 0.5, target.getY() + 0.5,
                target.getZ() + 0.5) > 25.0) {
            completeCollection(server, state, ownerState, owner, companion, TaskState.FAILED,
                    "挖掘期间权限、区块或距离校验失败；已中止。");
            return;
        }
        if (level.getBlockState(target).isAir()) {
            completeCollection(server, state, ownerState, owner, companion, TaskState.FAILED,
                    "挖掘结束前目标被其他变化移除；未计入本任务采集。");
            return;
        }
        BlockState block = level.getBlockState(target);
        if (!block.is(logBlock(execution)) || execution.tree != null
                && (!treeTargetsUnchanged(level, execution) || !TreeHarvestSupport.equipAxe(companion)
                || !TreeHarvestSupport.canMine(owner, companion, target) || !companion.gameMode.isSurvival())) {
            completeCollection(server, state, ownerState, owner, companion, TaskState.FAILED,
                    "挖掘目标已变化；未计入本任务采集。");
            return;
        }
        float currentProgress = block.getDestroyProgress(companion, level, target);
        if (currentProgress <= 0.0F) {
            completeCollection(server, state, ownerState, owner, companion, TaskState.FAILED,
                    "当前生存工具无法继续挖掘目标原木。");
            return;
        }
        execution.miningProgress += currentProgress;
        execution.miningTicks++;
        if (execution.miningProgress < 1.0F) {
            return;
        }
        if (execution.tree != null && !treeStructureUnchanged(level, execution)) {
            completeCollection(server, state, ownerState, owner, companion, TaskState.FAILED,
                    "树木周围新增结构、方块改变或区块卸载；已中止且没有扩展目标。");
            return;
        }
        // Snapshot immediately before the destructive call, not seconds earlier at mining start.
        // An unrelated player drop during mining must never become this tree's harvested resource.
        if (execution.tree != null) execution.dropCountsBefore = logDropCounts(level, target, logItem(execution));
        companion.gameMode.handleBlockBreakAction(target, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                execution.miningDirection, level.getMaxBuildHeight(), execution.nextSequence++);
        if (level.getBlockState(target).isAir()) {
            recordMinedLog(server, state, ownerState, level, target);
        } else {
            completeCollection(server, state, ownerState, owner, companion, TaskState.FAILED,
                    "服务端未完成此次生存挖掘；未计入成功数量。");
        }
    }

    private static void recordMinedLog(MinecraftServer server, ServerState state, OwnerState ownerState,
                                       ServerLevel level, BlockPos target) {
        Execution execution = ownerState.execution;
        execution.logsDestroyed++;
        updateTreeStatus(state, execution, TaskState.RUNNING, "已生存挖掘，等待实际拾取。");
        execution.pickupAnchor = target.immutable();
        execution.pickupDeadlineTick = server.getTickCount() + PICKUP_TIMEOUT_TICKS;
        execution.pickupStartedAtTick = server.getTickCount();
        execution.pendingDrops.clear();
        int eligible = Math.max(0, Math.min(execution.logsDestroyed,
                execution.request.requestedCount()) - execution.logsPicked);
        Map<UUID, Integer> afterBreak = logDropCounts(level, target, logItem(execution));
        for (Map.Entry<UUID, Integer> drop : afterBreak.entrySet()) {
            if (eligible <= 0) {
                break;
            }
            int added = Math.max(0, drop.getValue()
                    - execution.dropCountsBefore.getOrDefault(drop.getKey(), 0));
            // Tree drops fail closed when provenance is ambiguous: only a new exact log entity is tracked.
            if (execution.tree != null && !TreeDropAccounting.isNewSingleLog(
                    execution.dropCountsBefore.getOrDefault(drop.getKey(), 0), drop.getValue())) continue;
            int tracked = Math.min(added, eligible);
            if (tracked > 0) {
                execution.pendingDrops.put(drop.getKey(), tracked);
                eligible -= tracked;
            }
        }
        execution.dropCountsBefore = Map.of();
        execution.miningPos = null;
        execution.targetLog = null;
        execution.path = List.of();
        execution.navigationTarget = null;
        execution.miningTicks = 0;
        state.scheduler.updateProgress(execution.request.taskId(), execution.logsPicked, 0,
                "方块已由生存挖掘破坏，等待正常拾取。");
    }

    private static boolean collectPermissionStillValid(OwnerState ownerState, ServerPlayer owner,
                                                       ServerPlayer companion, BlockArea area) {
        if (!CompanionRuntime.isEnabled() || !owner.isAlive() || !companion.isAlive()
                || !CompanionRuntime.isCompanion(companion)
                || !owner.serverLevel().dimension().equals(ownerState.dimension)
                || !companion.serverLevel().dimension().equals(ownerState.dimension)
                || !validAreaBounds(companion.serverLevel(), area)
                || !areaNear(owner, area, MAX_AREA_DISTANCE)
                || !areaNear(companion, area, MAX_AREA_DISTANCE)) {
            return false;
        }
        CompanionData.Entry entry = CompanionRuntime.data(owner.server).find(owner.getUUID()).orElse(null);
        return entry != null && entry.active() && entry.identity().companionId().equals(companion.getUUID());
    }

    private static AreaScan findOakLog(ServerLevel level, BlockArea area) {
        for (int y = area.minY(); y <= area.maxY(); y++) {
            for (int x = area.minX(); x <= area.maxX(); x++) {
                for (int z = area.minZ(); z <= area.maxZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level.hasChunkAt(pos)) {
                        return new AreaScan(null, false);
                    }
                    if (level.getBlockState(pos).is(Blocks.OAK_LOG)) {
                        return new AreaScan(pos, true);
                    }
                }
            }
        }
        return new AreaScan(null, true);
    }

    private static Map<UUID, Integer> logDropCounts(ServerLevel level, BlockPos center, Item material) {
        AABB bounds = new AABB(center.getX() - 2.0, center.getY() - 2.0, center.getZ() - 2.0,
                center.getX() + 3.0, center.getY() + 3.0, center.getZ() + 3.0);
        Map<UUID, Integer> counts = new HashMap<>();
        for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, bounds,
                item -> item.isAlive() && item.getItem().is(material))) {
            counts.put(entity.getUUID(), entity.getItem().getCount());
        }
        return counts;
    }

    private static void refreshMergedDropAliases(ServerLevel level, Execution execution) {
        if (execution.tree != null) return; // Unknown merged UUIDs are not credited as tree drops.
        Map<UUID, Integer> current = logDropCounts(level, execution.pickupAnchor, logItem(execution));
        int missingExpected = 0;
        for (UUID id : List.copyOf(execution.pendingDrops.keySet())) {
            if (!current.containsKey(id)) {
                missingExpected += execution.pendingDrops.remove(id);
            }
        }
        if (missingExpected <= 0) {
            return;
        }
        AABB bounds = new AABB(execution.pickupAnchor.getX() - 2.0, execution.pickupAnchor.getY() - 2.0,
                execution.pickupAnchor.getZ() - 2.0, execution.pickupAnchor.getX() + 3.0,
                execution.pickupAnchor.getY() + 3.0, execution.pickupAnchor.getZ() + 3.0);
        for (ItemEntity candidate : level.getEntitiesOfClass(ItemEntity.class, bounds,
                item -> item.isAlive() && item.getItem().is(logItem(execution)))) {
            int before = execution.dropCountsBefore.getOrDefault(candidate.getUUID(), 0);
            int added = Math.max(0, candidate.getItem().getCount() - before);
            if (before > 0 && added == missingExpected
                    && candidate.blockPosition().distSqr(execution.pickupAnchor) <= 4.0) {
                execution.pendingDrops.put(candidate.getUUID(), missingExpected);
                return;
            }
        }
    }

    private static BlockPos pickupApproach(ServerPlayer companion, ItemEntity item) {
        BlockPos origin = item.blockPosition();
        BlockPos best = null;
        double score = Double.POSITIVE_INFINITY;
        for (int dx=-1;dx<=1;dx++) for(int dz=-1;dz<=1;dz++) for(int dy=-2;dy<=1;dy++) {
            BlockPos pos = origin.offset(dx,dy,dz);
            if (!SurfacePathfinder.canStand(companion.serverLevel(),pos)) continue;
            AABB playerBox = new AABB(pos.getX()+0.2,pos.getY(),pos.getZ()+0.2,
                    pos.getX()+0.8,pos.getY()+1.8,pos.getZ()+0.8).inflate(1.0,0.5,1.0);
            if (!playerBox.intersects(item.getBoundingBox())) continue;
            double distance = companion.distanceToSqr(pos.getX()+0.5,pos.getY(),pos.getZ()+0.5);
            if(distance<score){score=distance;best=pos;}
        }
        return best;
    }

    private static ItemEntity nearestTaskDrop(ServerPlayer companion, BlockPos anchor, Execution execution) {
        return companion.serverLevel().getEntitiesOfClass(ItemEntity.class,
                        companion.getBoundingBox().inflate(12.0), entity -> entity.isAlive()
                                && entity.getItem().is(logItem(execution))
                                && execution.pendingDrops.containsKey(entity.getUUID())
                                && entity.blockPosition().distSqr(anchor) <= 16.0)
                .stream().min((left, right) -> Double.compare(
                        left.distanceToSqr(companion), right.distanceToSqr(companion))).orElse(null);
    }

    private static int countLogs(ServerPlayer player, Item material) {
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(material)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static void completeCollection(MinecraftServer server, ServerState state, OwnerState ownerState,
                                           ServerPlayer owner, ServerPlayer companion, TaskState requestedState,
                                           String message) {
        Execution execution = ownerState.execution;
        if (execution == null) {
            return;
        }
        if (execution.tree != null && !execution.replantAttempted) {
            execution.replantAttempted = true;
            if (!execution.request.replant()) {
                execution.replant = TreeTaskStatus.Replant.DISABLED;
            } else if (execution.logsDestroyed != execution.request.requestedCount()) {
                execution.replant = TreeTaskStatus.Replant.PARTIAL_TREE;
            } else {
                execution.replanting = true;
                execution.returnState = requestedState;
                execution.returnMessage = message;
                execution.replantStartedAtTick = server.getTickCount();
                execution.path = List.of();
                execution.navigationTarget = null;
                clearInputs(server, ownerState, execution);
                return;
            }
        }
        if (execution.logsPicked == 0) {
            TaskState result = requestedState == TaskState.SUCCEEDED ? TaskState.FAILED : requestedState;
            finish(server, state, ownerState, result, 0, 0,
                    message + " 实际拾取 0，交付 0/" + execution.request.requestedCount() + "。");
            return;
        }
        if (!deliveryPairValid(ownerState, owner, companion, false)) {
            finish(server, state, ownerState, TaskState.FAILED, execution.logsPicked, 0,
                    message + " 无法安全返回主人；物品仍留在伙伴背包，交付 0。");
            return;
        }
        if (owner.distanceToSqr(companion) > 9.0) {
            if (execution.miningPos != null) {
                companion.gameMode.handleBlockBreakAction(execution.miningPos,
                        ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                        execution.miningDirection, companion.serverLevel().getMaxBuildHeight(),
                        execution.nextSequence++);
                execution.miningPos = null;
            }
            execution.returning = true;
            execution.returnState = requestedState;
            execution.returnMessage = message;
            execution.returnDeadlineTick = server.getTickCount() + 20L * 60L;
            execution.returnStartedAtTick = server.getTickCount();
            execution.path = List.of();
            execution.navigationTarget = null;
            execution.noPathTicks = 0;
            state.scheduler.updateProgress(execution.request.taskId(), execution.logsPicked, 0,
                    "返回主人附近交付实际拾取的任务原木。");
            return;
        }
        settleCollectionAtOwner(server, state, ownerState, owner, companion, requestedState, message);
    }

    private static void tickReturnToOwner(MinecraftServer server, ServerState state, OwnerState ownerState,
                                          ServerPlayer owner, ServerPlayer companion) {
        Execution execution = ownerState.execution;
        if (execution.tree == null ? server.getTickCount() > execution.returnDeadlineTick
                : tickAge(server.getTickCount(), execution.returnStartedAtTick) > 20L * 60L) {
            finish(server, state, ownerState, TaskState.FAILED, execution.logsPicked, 0,
                    execution.returnMessage + " 返回主人超时；物品仍留在伙伴背包，交付 0。");
            return;
        }
        if (!deliveryPairValid(ownerState, owner, companion, false)) {
            finish(server, state, ownerState, TaskState.FAILED, execution.logsPicked, 0,
                    execution.returnMessage + " 主人或伙伴状态变化，无法交付；物品仍留在伙伴背包，交付 0。");
            return;
        }
        if (owner.distanceToSqr(companion) <= 9.0) {
            settleCollectionAtOwner(server, state, ownerState, owner, companion,
                    execution.returnState, execution.returnMessage);
            return;
        }
        navigate(server, state, ownerState, owner, companion, owner.blockPosition(), 1);
    }

    private static boolean deliveryPairValid(OwnerState ownerState, ServerPlayer owner,
                                             ServerPlayer companion, boolean requireClose) {
        if (!CompanionRuntime.isEnabled() || !owner.isAlive() || !companion.isAlive()
                || !CompanionRuntime.isCompanion(companion)
                || owner.server.getPlayerList().getPlayer(owner.getUUID()) != owner
                || !owner.serverLevel().dimension().equals(ownerState.dimension)
                || !companion.serverLevel().dimension().equals(ownerState.dimension)) {
            return false;
        }
        CompanionData.Entry entry = CompanionRuntime.data(owner.server).find(owner.getUUID()).orElse(null);
        return entry != null && entry.active()
                && entry.identity().companionId().equals(companion.getUUID())
                && (!requireClose || owner.distanceToSqr(companion) <= 9.0);
    }

    private static void settleCollectionAtOwner(MinecraftServer server, ServerState state, OwnerState ownerState,
                                                ServerPlayer owner, ServerPlayer companion,
                                                TaskState requestedState, String message) {
        Execution execution = ownerState.execution;
        if (execution == null) {
            return;
        }
        if (!deliveryPairValid(ownerState, owner, companion, true)) {
            finish(server, state, ownerState, TaskState.FAILED, execution.logsPicked, 0,
                    message + " 交付前距离或归属复核失败；物品仍留在伙伴背包，交付 0。");
            return;
        }
        int desired = Math.min(execution.logsPicked, execution.request.requestedCount());
        int delivered = transferLogs(companion, owner, desired, logItem(execution));
        execution.delivered = delivered;
        TaskState finalState = delivered == execution.request.requestedCount()
                && execution.logsDestroyed == execution.request.requestedCount()
                && requestedState == TaskState.SUCCEEDED
                ? TaskState.SUCCEEDED
                : requestedState == TaskState.TIMED_OUT ? TaskState.TIMED_OUT : TaskState.FAILED;
        String finalMessage = delivered < execution.request.requestedCount()
                ? message + " 实际拾取 " + execution.logsPicked + "，交付 " + delivered + "/"
                + execution.request.requestedCount() + "。"
                : message + " 已交付 " + delivered + " 个任务原木。";
        finish(server, state, ownerState, finalState, execution.logsPicked, delivered, finalMessage);
    }

    private static int transferLogs(ServerPlayer companion, ServerPlayer owner, int requested, Item material) {
        int before = countLogs(owner, material);
        int remaining = requested;
        for (int slot = 0; slot < companion.getInventory().getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = companion.getInventory().getItem(slot);
            if (!stack.is(material)) {
                continue;
            }
            // Inventory.add discards an uninsertable remainder for creative players. Bound the
            // transfer to REAL component-compatible slots before removing anything from the source.
            int movingCount = Math.min(Math.min(remaining, stack.getCount()), stackCapacity(owner, stack));
            if (movingCount == 0) continue;
            ItemStack moving = companion.getInventory().removeItem(slot, movingCount);
            owner.getInventory().add(moving);
            int accepted = movingCount - moving.getCount();
            remaining -= accepted;
            if (!moving.isEmpty()) {
                ItemStack sourceSlot = companion.getInventory().getItem(slot);
                if (sourceSlot.isEmpty()) {
                    companion.getInventory().setItem(slot, moving);
                } else if (ItemStack.isSameItemSameComponents(sourceSlot, moving)
                        && sourceSlot.getCount() + moving.getCount() <= sourceSlot.getMaxStackSize()) {
                    sourceSlot.grow(moving.getCount());
                } else {
                    companion.getInventory().add(moving);
                }
            }
        }
        return Math.max(0, countLogs(owner, material) - before);
    }

    private static int logCapacity(ServerPlayer owner, Item material) {
        return stackCapacity(owner, new ItemStack(material));
    }

    private static int stackCapacity(ServerPlayer player, ItemStack moving) {
        int capacity = 0;
        for (int slot = 0; slot < Math.min(36, player.getInventory().getContainerSize()); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            int maximum = Math.min(64, moving.getMaxStackSize());
            if (stack.isEmpty()) {
                capacity += maximum;
            } else if (ItemStack.isSameItemSameComponents(stack, moving)) {
                capacity += Math.max(0, maximum - stack.getCount());
            }
        }
        return capacity;
    }

    private static void navigate(MinecraftServer server, ServerState state, OwnerState ownerState,
                                 ServerPlayer owner, ServerPlayer companion, BlockPos target, int goalRadius) {
        Execution execution = ownerState.execution;
        if (execution.navigationTarget == null || !execution.navigationTarget.equals(target)) {
            execution.navigationTarget = target.immutable();
            execution.path = List.of();
            execution.noPathTicks = 0;
        }
        if (SurfacePathfinder.isGoal(companion.blockPosition(), target, goalRadius)) {
            execution.path = List.of();
            execution.noPathTicks = 0;
            applyTravel(companion, 0.0F, false);
            return;
        }
        if (execution.path.isEmpty() || server.getTickCount() - execution.lastRepathTick >= 10) {
            if (state.pathSearchesRemaining <= 0) {
                applyTravel(companion, 0.0F, false);
                return;
            }
            state.pathSearchesRemaining--;
            execution.lastRepathTick = server.getTickCount();
            execution.path = SurfacePathfinder.find(companion.serverLevel(), companion.blockPosition(), target, goalRadius);
            if (execution.path.isEmpty()) {
                execution.noPathTicks += 10;
                applyTravel(companion, 0.0F, false);
                if (execution.noPathTicks >= 100) {
                    failCurrentAction(server, state, ownerState, owner, companion,
                            "附近没有已加载的可行地表路径。" );
                }
                return;
            }
            execution.noPathTicks = 0;
        }
        BlockPos waypoint = execution.path.get(0);
        double dx = waypoint.getX() + 0.5 - companion.getX();
        double dz = waypoint.getZ() + 0.5 - companion.getZ();
        if (dx * dx + dz * dz < 0.20 && Math.abs(waypoint.getY() - companion.getY()) < 1.2) {
            execution.path = execution.path.subList(1, execution.path.size());
            if (execution.path.isEmpty()) {
                applyTravel(companion, 0.0F, false);
                return;
            }
            waypoint = execution.path.get(0);
            dx = waypoint.getX() + 0.5 - companion.getX();
            dz = waypoint.getZ() + 0.5 - companion.getZ();
        }
        DoorInteractions.Result door = execution.doors.approach(owner, companion, waypoint, server.getTickCount());
        if (door != DoorInteractions.Result.CLEAR) {
            applyTravel(companion, 0.0F, false);
            if (door == DoorInteractions.Result.DENIED) {
                failCurrentAction(server, state, ownerState, owner, companion,
                        "木门交互被拒绝、不可接近或重试已用尽；未绕过门权限。" );
            }
            return;
        }
        float yaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI) - 90.0);
        companion.setYRot(yaw);
        companion.setYHeadRot(yaw);
        boolean jumping = waypoint.getY() > companion.blockPosition().getY();
        applyTravel(companion, 1.0F, jumping);
        if (Math.abs(companion.getX() - execution.lastX) + Math.abs(companion.getZ() - execution.lastZ) < 0.005) {
            execution.stuckTicks++;
        } else {
            execution.stuckTicks = 0;
            execution.lastX = companion.getX();
            execution.lastZ = companion.getZ();
        }
        if (execution.stuckTicks >= 100) {
            failCurrentAction(server, state, ownerState, owner, companion, "伙伴移动受阻。" );
        }
    }

    private static void failCurrentAction(MinecraftServer server, ServerState state, OwnerState ownerState,
                                          ServerPlayer owner, ServerPlayer companion, String message) {
        if (ownerState.execution != null && ownerState.execution.replanting) {
            Execution execution = ownerState.execution;
            execution.replanting = false;
            execution.replant = TreeTaskStatus.Replant.UNREACHABLE;
            completeCollection(server, state, ownerState, owner, companion, execution.returnState,
                    execution.returnMessage + " 补种路径不可达；" + message);
        } else if (ownerState.execution != null && ownerState.execution.returning) {
            finish(server, state, ownerState, TaskState.FAILED, ownerState.execution.logsPicked, 0,
                    ownerState.execution.returnMessage + " 返回路径受阻；物品仍留在伙伴背包，交付 0。 " + message);
        } else if (ownerState.execution != null
                && isCollection(ownerState.execution.request)) {
            completeCollection(server, state, ownerState, owner, companion, TaskState.FAILED, message);
        } else {
            finish(server, state, ownerState, TaskState.FAILED, message);
        }
    }

    private static void finish(MinecraftServer server, ServerState state, OwnerState ownerState,
                               TaskState result, String message) {
        Execution execution = ownerState.execution;
        if (execution == null) {
            return;
        }
        finish(server, state, ownerState, result, execution.logsPicked, execution.delivered, message);
    }

    private static void finish(MinecraftServer server, ServerState state, OwnerState ownerState,
                               TaskState result, int completed, int delivered, String message) {
        Execution execution = ownerState.execution;
        if (execution == null) {
            return;
        }
        if (execution.tree != null) {
            message += " 已砍 " + execution.logsDestroyed + "/" + execution.request.requestedCount()
                    + "，剩余 " + (execution.request.requestedCount() - execution.logsDestroyed)
                    + "，已收 " + execution.logsPicked + "，已交 " + delivered
                    + "；补种 " + execution.replant + "。";
            updateTreeStatus(state, execution, result, message);
        }
        state.scheduler.finish(execution.request.taskId(), result, completed, delivered, message);
        state.requests.remove(execution.request.taskId());
        state.treeRequests.remove(execution.request.taskId());
        clearInputs(server, ownerState, execution);
        ownerState.execution = null;
        if (result == TaskState.FAILED || result == TaskState.TIMED_OUT || result == TaskState.SUCCEEDED) {
            ServerPlayer owner = server.getPlayerList().getPlayer(execution.request.ownerId());
            if (owner != null) {
                owner.sendSystemMessage(Component.literal("任务 " + execution.request.type() + " " + result
                        + "：" + message));
            }
        }
    }

    private static void cancelOwner(MinecraftServer server, ServerState state, UUID ownerId,
                                    OwnerState knownOwnerState) {
        if (state == null) {
            return;
        }
        OwnerState ownerState = knownOwnerState != null ? knownOwnerState : state.owners.get(ownerId);
        state.planner.cancel(ownerId);
        if (ownerState != null && ownerState.execution != null && ownerState.execution.tree != null) {
            Execution execution = ownerState.execution;
            if (execution.replant == TreeTaskStatus.Replant.PENDING) execution.replant = TreeTaskStatus.Replant.CANCELLED;
            updateTreeStatus(state, execution, TaskState.CANCELLED,
                    "已停止；未交付物品留在伙伴背包或原世界掉落处，未恢复动作。");
        }
        TreeTaskStatus treeStatus = state.treeOutcomes.get(ownerId);
        if (treeStatus != null && !treeStatus.state().isTerminal()) {
            putTreeStatus(state, ownerId, new TreeTaskStatus(treeStatus.taskId(), TaskState.CANCELLED,
                    treeStatus.planned(), treeStatus.harvested(), treeStatus.pickedUp(), treeStatus.delivered(),
                    treeStatus.replant() == TreeTaskStatus.Replant.PENDING ? TreeTaskStatus.Replant.CANCELLED
                            : treeStatus.replant(), "已取消；尚未交付物品没有转移给主人。"));
        }
        state.scheduler.stop(ownerId);
        state.treeRequests.entrySet().removeIf(entry -> entry.getValue().owner().getUUID().equals(ownerId));
        state.requests.entrySet().removeIf(entry -> entry.getValue().ownerId().equals(ownerId));
        if (ownerState != null) {
            clearInputs(server, ownerState, ownerState.execution);
            ownerState.execution = null;
            ownerState.pendingTree = null;
            ownerState.area = null;
            ownerState.areaDimension = null;
        }
    }

    private static void clearInputs(MinecraftServer server, OwnerState ownerState, Execution execution) {
        ServerPlayer companion = server.getPlayerList().getPlayer(ownerState.companionId);
        if (companion == null || !CompanionRuntime.isCompanion(companion)) {
            return;
        }
        Vec3 velocity = companion.getDeltaMovement();
        companion.setDeltaMovement(0.0, velocity.y, 0.0);
        if (execution != null && execution.miningPos != null) {
            companion.gameMode.handleBlockBreakAction(execution.miningPos,
                    ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                    execution.miningDirection, companion.serverLevel().getMaxBuildHeight(),
                    execution.nextSequence++);
        }
    }

    /** Calls normal ServerPlayer/Player/LivingEntity travel once on the server thread. */
    private static void applyTravel(ServerPlayer companion, float forward, boolean jump) {
        if (companion.isPassenger()) {
            companion.setDeltaMovement(Vec3.ZERO);
            return;
        }
        companion.setSpeed((float) companion.getAttributeValue(Attributes.MOVEMENT_SPEED));
        if (jump && companion.onGround()) {
            companion.jumpFromGround();
        }
        companion.travel(forward == 0.0F ? Vec3.ZERO : new Vec3(0.0, 0.0, forward));
    }

    private static boolean loaded(ServerLevel level, BlockArea area) {
        for (int x = area.minX(); x <= area.maxX(); x++) {
            for (int y = area.minY(); y <= area.maxY(); y++) {
                for (int z = area.minZ(); z <= area.maxZ(); z++) {
                    if (!level.hasChunkAt(new BlockPos(x, y, z))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean validAreaBounds(ServerLevel level, BlockArea area) {
        if (area.minY() < level.getMinBuildHeight() || area.maxY() >= level.getMaxBuildHeight()) {
            return false;
        }
        for (int x : new int[]{area.minX(), area.maxX()}) {
            for (int z : new int[]{area.minZ(), area.maxZ()}) {
                if (!level.getWorldBorder().isWithinBounds(new BlockPos(x, area.minY(), z))
                        || !level.getWorldBorder().isWithinBounds(new BlockPos(x, area.maxY(), z))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean areaNear(ServerPlayer player, BlockArea area, int maxDistance) {
        if (player == null) {
            return false;
        }
        double maxSqr = (double) maxDistance * maxDistance;
        for (int x : new int[]{area.minX(), area.maxX()}) {
            for (int y : new int[]{area.minY(), area.maxY()}) {
                for (int z : new int[]{area.minZ(), area.maxZ()}) {
                    if (player.distanceToSqr(x + 0.5, y + 0.5, z + 0.5) > maxSqr) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static CommandSyntaxException commandError(String message, Throwable cause) {
        return new SimpleCommandExceptionType(Component.literal(message)).create();
    }

    private static ActionStatus idleStatus() {
        return new ActionStatus(IDLE_TASK_ID, ActionType.NONE, TaskState.IDLE, 0, 0, 0, "idle");
    }

    private static ServerState state(MinecraftServer server) {
        synchronized (SERVERS) {
            return SERVERS.computeIfAbsent(server, ignored -> new ServerState(server));
        }
    }

    private static ServerState stateIfStarted(MinecraftServer server) {
        synchronized (SERVERS) {
            return SERVERS.get(server);
        }
    }

    private static final class ServerState {
        private final UUID worldSession = UUID.randomUUID();
        private final ActionPlanner planner;
        private ServerState(MinecraftServer server) {
            planner = new ActionPlanner(server::execute, System::nanoTime);
        }
        private final ActionScheduler scheduler = new ActionScheduler();
        private final Map<UUID, ActionRequest> requests = new HashMap<>();
        private final Map<UUID, OwnerState> owners = new HashMap<>();
        private final Map<UUID, PendingTree> treeRequests = new HashMap<>();
        private final LinkedHashMap<UUID, TreeTaskStatus> treeOutcomes = new LinkedHashMap<>();
        private int nextOwnerCursor;
        private int pathSearchesRemaining;
        private int areaScansRemaining;
    }

    private static final class OwnerState {
        private ServerPlayer owner;
        private UUID companionId;
        private net.minecraft.resources.ResourceKey<Level> dimension;
        private BlockArea area;
        private net.minecraft.resources.ResourceKey<Level> areaDimension;
        private long areaExpiresAt;
        private long nextPlanTick;
        private int lastTreePreviewTick;
        private boolean treePreviewed;
        private int areaSelectedTick;
        private PendingTree pendingTree;
        private Execution execution;
    }

    private static final class Execution {
        private final ActionRequest request;
        private final ServerPlayer companion;
        private final PendingTree tree;
        private boolean replantAttempted;
        private boolean replanting;
        private int replantStartedAtTick;
        private int returnStartedAtTick;
        private final int startedAtTick;
        private TreeTaskStatus.Replant replant;
        private double lastX;
        private double lastZ;
        private long elapsedTicks;
        private long lastRepathTick;
        private int noPathTicks;
        private int stuckTicks;
        private int nextSequence;
        private int logsPicked;
        private int logsDestroyed;
        private int delivered;
        private int miningTicks;
        private float miningProgress;
        private final Map<UUID, Integer> pendingDrops = new HashMap<>();
        private Map<UUID, Integer> dropCountsBefore = Map.of();
        private BlockPos navigationTarget;
        private BlockPos targetLog;
        private BlockPos pickupAnchor;
        private long pickupDeadlineTick;
        private int pickupStartedAtTick;
        private boolean returning;
        private TaskState returnState;
        private String returnMessage;
        private long returnDeadlineTick;
        private final DoorInteractions doors = new DoorInteractions();
        private List<BlockPos> path = List.of();
        private BlockPos miningPos;
        private net.minecraft.core.Direction miningDirection = net.minecraft.core.Direction.UP;

        private Execution(ActionRequest request, int startTick, ServerPlayer companion, PendingTree tree) {
            this.request = request;
            this.startedAtTick = startTick;
            this.companion = companion;
            this.tree = tree;
            this.replant = request.replant() ? TreeTaskStatus.Replant.PENDING : TreeTaskStatus.Replant.DISABLED;
            this.lastRepathTick = startTick - 10L;
            this.lastX = companion.getX();
            this.lastZ = companion.getZ();
        }
    }

    private record AreaScan(BlockPos target, boolean allVisitedChunksLoaded) {
    }
}
