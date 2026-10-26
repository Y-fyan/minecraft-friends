package io.github.yfyan.minecraftfriends.action;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import io.github.yfyan.minecraftfriends.companion.CompanionData;
import io.github.yfyan.minecraftfriends.companion.CompanionRuntime;
import io.github.yfyan.minecraftfriends.task.TaskState;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/** Server-thread action entrypoints shared by Fabric and Forge. */
public final class ActionRuntime {
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
            SERVERS.put(server, new ServerState());
        }
    }

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(literal("mf").then(literal("task")
                .then(literal("follow").executes(context -> start(context.getSource(), ActionType.FOLLOW)))
                .then(literal("wait").executes(context -> start(context.getSource(), ActionType.WAIT)))
                .then(literal("stop").executes(context -> stop(context.getSource())))
                .then(literal("status").executes(context -> sendStatus(context.getSource())))
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

    /** Read-only snapshot for diagnostics and acceptance polling. Must be called on the server thread. */
    public static ActionStatus status(MinecraftServer server, UUID ownerId) {
        ServerState state = stateIfStarted(server);
        if (state == null) {
            return idleStatus();
        }
        return state.scheduler.status(ownerId).orElseGet(ActionRuntime::idleStatus);
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
            if (owner == null || companion == null || !CompanionRuntime.isCompanion(companion)
                    || !owner.isAlive() || !companion.isAlive()
                    || !owner.serverLevel().dimension().equals(ownerState.dimension)
                    || !companion.serverLevel().dimension().equals(ownerState.dimension)
                    || owner.distanceToSqr(companion) > (double) MAX_OWNER_DISTANCE * MAX_OWNER_DISTANCE) {
                cancelOwner(server, state, ownerId, ownerState);
                state.owners.remove(ownerId);
                continue;
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
                ownerState.execution = new Execution(request, server.getTickCount(), companion);
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

    public static void onServerStopping(MinecraftServer server) {
        ServerState state = stateIfStarted(server);
        if (state == null) {
            return;
        }
        for (UUID ownerId : List.copyOf(state.owners.keySet())) {
            cancelOwner(server, state, ownerId, state.owners.get(ownerId));
        }
        synchronized (SERVERS) {
            SERVERS.remove(server);
        }
    }

    private static int start(CommandSourceStack source, ActionType type) throws CommandSyntaxException {
        ServerPlayer owner = source.getPlayerOrException();
        CompanionData.Entry entry = requireLiveCompanion(owner);
        ServerState serverState = state(owner.server);
        OwnerState ownerState = ownerState(serverState, owner, entry.identity().companionId());
        cancelOwner(owner.server, serverState, owner.getUUID(), ownerState);
        ActionRequest request = type == ActionType.FOLLOW
                ? ActionRequest.follow(UUID.randomUUID(), owner.getUUID())
                : ActionRequest.waitForOwner(UUID.randomUUID(), owner.getUUID());
        submit(serverState, ownerState, request);
        owner.sendSystemMessage(Component.literal(type == ActionType.FOLLOW
                ? "伙伴开始以正常移动跟随。" : "伙伴已停止移动并等待。"));
        return 1;
    }

    private static int stop(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer owner = source.getPlayerOrException();
        ServerState serverState = stateIfStarted(owner.server);
        if (serverState != null) {
            cancelOwner(owner.server, serverState, owner.getUUID(), serverState.owners.get(owner.getUUID()));
        }
        owner.sendSystemMessage(Component.literal("已取消伙伴动作并清除输入。"));
        return 1;
    }

    private static int sendStatus(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer owner = source.getPlayerOrException();
        ActionStatus current = status(owner.server, owner.getUUID());
        owner.sendSystemMessage(Component.literal("任务 " + current.taskId() + " / " + current.type()
                + " / " + current.state() + " / " + current.completed() + "/" + current.requested()
                + " / 已交付 " + current.delivered() + " / " + current.message()));
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
        if (!loaded(level, area)) {
            throw commandError("区域包含未加载区块；区域未授权。", null);
        }
        ServerState serverState = state(owner.server);
        OwnerState ownerState = ownerState(serverState, owner, entry.identity().companionId());
        ownerState.area = area;
        ownerState.areaDimension = level.dimension();
        ownerState.areaExpiresAt = owner.server.getTickCount() + 20L * 60L * 5L;
        owner.sendSystemMessage(Component.literal("已授权一次性采集区域：" + area.minX() + " " + area.minY()
                + " " + area.minZ() + " 到 " + area.maxX() + " " + area.maxY() + " " + area.maxZ()));
        return 1;
    }

    private static int collect(CommandSourceStack source, int count) throws CommandSyntaxException {
        ServerPlayer owner = source.getPlayerOrException();
        CompanionData.Entry entry = requireLiveCompanion(owner);
        ServerState serverState = state(owner.server);
        OwnerState ownerState = ownerState(serverState, owner, entry.identity().companionId());
        if (ownerState.area == null || owner.server.getTickCount() > ownerState.areaExpiresAt
                || !owner.serverLevel().dimension().equals(ownerState.areaDimension)) {
            ownerState.area = null;
            throw commandError("请先在当前维度执行 /mf task area 选择一次性区域。", null);
        }
        if (!loaded(owner.serverLevel(), ownerState.area)
                || !areaNear(owner, ownerState.area, MAX_AREA_DISTANCE)
                || !areaNear(owner.server.getPlayerList().getPlayer(entry.identity().companionId()),
                ownerState.area, MAX_AREA_DISTANCE)) {
            ownerState.area = null;
            throw commandError("区域加载状态或距离已变化；请重新授权。", null);
        }
        ActionRequest request = ActionRequest.collectOakLogs(
                UUID.randomUUID(), owner.getUUID(), ownerState.area, count);
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
        if (execution.request.type() == ActionType.COLLECT_OAK_LOGS) {
            tickCollect(server, state, ownerState, owner, companion);
            return;
        }
        finish(server, state, ownerState, TaskState.FAILED, "该动作当前无法执行。" );
    }

    private static void tickFollow(MinecraftServer server, ServerState state, OwnerState ownerState,
                                   ServerPlayer owner, ServerPlayer companion) {
        Execution execution = ownerState.execution;
        if (owner.distanceToSqr(companion) <= 9.0) {
            execution.path = List.of();
            execution.noPathTicks = 0;
            execution.stuckTicks = 0;
            applyTravel(companion, 0.0F, false);
            return;
        }
        navigate(server, state, ownerState, owner, companion, owner.blockPosition(), 2);
    }

    private static void tickCollect(MinecraftServer server, ServerState state, OwnerState ownerState,
                                    ServerPlayer owner, ServerPlayer companion) {
        Execution execution = ownerState.execution;
        ActionRequest request = execution.request;
        BlockArea area = request.area();
        ServerLevel level = companion.serverLevel();
        int observed = countOakLogs(companion);
        if (observed > execution.lastObservedOakLogs) {
            execution.logsPicked = Math.min(request.requestedCount(), execution.logsPicked
                    + observed - execution.lastObservedOakLogs);
        }
        execution.lastObservedOakLogs = observed;
        state.scheduler.updateProgress(request.taskId(), execution.logsPicked, 0,
                "已正常拾取 " + execution.logsPicked + "/" + request.requestedCount());

        if (execution.elapsedTicks > COLLECTION_TIMEOUT_TICKS) {
            completeCollection(server, state, ownerState, owner, companion, TaskState.TIMED_OUT,
                    "采集超时，按实际拾取数量结算。");
            return;
        }
        if (!collectPermissionStillValid(ownerState, owner, companion, area)) {
            completeCollection(server, state, ownerState, owner, companion, TaskState.FAILED,
                    "维度、距离或授权状态变化，已停止采集。");
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
            if (server.getTickCount() > execution.pickupDeadlineTick) {
                completeCollection(server, state, ownerState, owner, companion, TaskState.FAILED,
                        "掉落物未被伙伴正常拾取；按实际数量结算。");
                return;
            }
            ItemEntity item = nearestTaskDrop(companion, execution.pickupAnchor);
            if (item != null) {
                navigate(server, state, ownerState, owner, companion, item.blockPosition(), 1);
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
            AreaScan scan = findOakLog(level, area);
            if (!scan.allVisitedChunksLoaded()) {
                completeCollection(server, state, ownerState, owner, companion, TaskState.FAILED,
                        "授权区域出现未加载区块；已停止采集。");
                return;
            }
            execution.targetLog = scan.target();
            if (execution.targetLog == null) {
                completeCollection(server, state, ownerState, owner, companion, TaskState.FAILED,
                        "授权区域内没有更多橡木原木；按实际数量结算。");
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
        if (!level.getBlockState(target).is(Blocks.OAK_LOG)) {
            execution.targetLog = null;
            execution.path = List.of();
            return;
        }
        if (!companion.gameMode.isSurvival()) {
            completeCollection(server, state, ownerState, owner, companion, TaskState.FAILED,
                    "采集只允许生存模式伙伴执行。");
            return;
        }
        double distance = companion.distanceToSqr(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
        if (distance > 25.0) {
            navigate(server, state, ownerState, owner, companion, target, 2);
            return;
        }
        applyTravel(companion, 0.0F, false);
        BlockState block = level.getBlockState(target);
        float progressPerTick = block.getDestroyProgress(companion, level, target);
        if (progressPerTick <= 0.0F) {
            completeCollection(server, state, ownerState, owner, companion, TaskState.FAILED,
                    "橡木原木无法在当前生存规则下挖掘。");
            return;
        }
        execution.miningPos = target.immutable();
        execution.miningDirection = Direction.UP;
        execution.miningProgressPerTick = progressPerTick;
        execution.miningTicks = 0;
        execution.requiredMiningTicks = Math.max(1, (int) Math.ceil(0.8 / progressPerTick));
        companion.gameMode.handleBlockBreakAction(target, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                execution.miningDirection, level.getMaxBuildHeight(), execution.nextSequence++);
        if (level.getBlockState(target).isAir()) {
            recordMinedLog(server, state, ownerState, target);
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
            recordMinedLog(server, state, ownerState, target);
            return;
        }
        execution.miningTicks++;
        if (execution.miningTicks < execution.requiredMiningTicks) {
            return;
        }
        companion.gameMode.handleBlockBreakAction(target, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                execution.miningDirection, level.getMaxBuildHeight(), execution.nextSequence++);
        if (level.getBlockState(target).isAir()) {
            recordMinedLog(server, state, ownerState, target);
        } else {
            completeCollection(server, state, ownerState, owner, companion, TaskState.FAILED,
                    "服务端未完成此次生存挖掘；未计入成功数量。");
        }
    }

    private static void recordMinedLog(MinecraftServer server, ServerState state, OwnerState ownerState,
                                       BlockPos target) {
        Execution execution = ownerState.execution;
        execution.logsDestroyed++;
        execution.pickupAnchor = target.immutable();
        execution.pickupDeadlineTick = server.getTickCount() + PICKUP_TIMEOUT_TICKS;
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

    private static ItemEntity nearestTaskDrop(ServerPlayer companion, BlockPos anchor) {
        return companion.serverLevel().getEntitiesOfClass(ItemEntity.class,
                        companion.getBoundingBox().inflate(12.0), entity -> entity.isAlive()
                                && entity.getItem().is(Items.OAK_LOG)
                                && entity.blockPosition().distSqr(anchor) <= 16.0)
                .stream().min((left, right) -> Double.compare(
                        left.distanceToSqr(companion), right.distanceToSqr(companion))).orElse(null);
    }

    private static int countOakLogs(ServerPlayer player) {
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(Items.OAK_LOG)) {
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
        int desired = Math.min(execution.logsPicked, execution.request.requestedCount());
        int delivered = transferOakLogs(companion, owner, desired);
        execution.delivered = delivered;
        TaskState finalState = delivered == execution.request.requestedCount()
                ? TaskState.SUCCEEDED
                : requestedState == TaskState.TIMED_OUT ? TaskState.TIMED_OUT : TaskState.FAILED;
        String finalMessage = delivered < execution.request.requestedCount()
                ? message + " 实际拾取 " + execution.logsPicked + "，交付 " + delivered + "/"
                + execution.request.requestedCount() + "。"
                : message + " 已交付 " + delivered + " 个橡木原木。";
        finish(server, state, ownerState, finalState, execution.logsPicked, delivered, finalMessage);
    }

    private static int transferOakLogs(ServerPlayer companion, ServerPlayer owner, int requested) {
        int before = countOakLogs(owner);
        int remaining = Math.min(requested, oakLogCapacity(owner));
        for (int slot = 0; slot < companion.getInventory().getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = companion.getInventory().getItem(slot);
            if (!stack.is(Items.OAK_LOG)) {
                continue;
            }
            int movingCount = Math.min(remaining, stack.getCount());
            ItemStack moving = companion.getInventory().removeItem(slot, movingCount);
            owner.getInventory().add(moving);
            int accepted = movingCount - moving.getCount();
            remaining -= accepted;
            if (!moving.isEmpty()) {
                ItemStack sourceSlot = companion.getInventory().getItem(slot);
                if (sourceSlot.isEmpty()) {
                    companion.getInventory().setItem(slot, moving);
                } else if (sourceSlot.is(Items.OAK_LOG)
                        && sourceSlot.getCount() + moving.getCount() <= sourceSlot.getMaxStackSize()) {
                    sourceSlot.grow(moving.getCount());
                } else {
                    companion.getInventory().add(moving);
                }
            }
        }
        return Math.max(0, countOakLogs(owner) - before);
    }

    private static int oakLogCapacity(ServerPlayer owner) {
        int capacity = 0;
        for (int slot = 0; slot < Math.min(36, owner.getInventory().getContainerSize()); slot++) {
            ItemStack stack = owner.getInventory().getItem(slot);
            if (stack.isEmpty()) {
                capacity += 64;
            } else if (stack.is(Items.OAK_LOG)) {
                capacity += Math.max(0, stack.getMaxStackSize() - stack.getCount());
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
        if (ownerState.execution != null
                && ownerState.execution.request.type() == ActionType.COLLECT_OAK_LOGS) {
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
        state.scheduler.finish(execution.request.taskId(), result, completed, delivered, message);
        state.requests.remove(execution.request.taskId());
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
        state.scheduler.stop(ownerId);
        state.requests.entrySet().removeIf(entry -> entry.getValue().ownerId().equals(ownerId));
        if (ownerState != null) {
            clearInputs(server, ownerState, ownerState.execution);
            ownerState.execution = null;
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
            return SERVERS.computeIfAbsent(server, ignored -> new ServerState());
        }
    }

    private static ServerState stateIfStarted(MinecraftServer server) {
        synchronized (SERVERS) {
            return SERVERS.get(server);
        }
    }

    private static final class ServerState {
        private final ActionScheduler scheduler = new ActionScheduler();
        private final Map<UUID, ActionRequest> requests = new HashMap<>();
        private final Map<UUID, OwnerState> owners = new HashMap<>();
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
        private Execution execution;
    }

    private static final class Execution {
        private final ActionRequest request;
        private double lastX;
        private double lastZ;
        private long elapsedTicks;
        private long lastRepathTick;
        private int noPathTicks;
        private int stuckTicks;
        private int nextSequence;
        private int startingOakLogs;
        private int lastObservedOakLogs;
        private int logsPicked;
        private int logsDestroyed;
        private int delivered;
        private int miningTicks;
        private int requiredMiningTicks;
        private float miningProgressPerTick;
        private BlockPos navigationTarget;
        private BlockPos targetLog;
        private BlockPos pickupAnchor;
        private long pickupDeadlineTick;
        private List<BlockPos> path = List.of();
        private BlockPos miningPos;
        private net.minecraft.core.Direction miningDirection = net.minecraft.core.Direction.UP;

        private Execution(ActionRequest request, int startTick, ServerPlayer companion) {
            this.request = request;
            this.lastRepathTick = startTick - 10L;
            this.lastX = companion.getX();
            this.lastZ = companion.getZ();
            this.startingOakLogs = countOakLogs(companion);
            this.lastObservedOakLogs = startingOakLogs;
        }
    }

    private record AreaScan(BlockPos target, boolean allVisitedChunksLoaded) {
    }
}
