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
import net.minecraft.world.level.Level;

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
    private static final int MAX_OWNER_DISTANCE = 48;
    private static final int MAX_AREA_DISTANCE = 32;
    private static final int MAX_OWNERS_PER_TICK = 8;
    private static final int MAX_PATH_SEARCHES_PER_TICK = 4;
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
            return;
        }
        List<UUID> ownerIds = new ArrayList<>(state.owners.keySet());
        if (ownerIds.isEmpty()) {
            return;
        }
        int first = Math.floorMod(state.nextOwnerCursor, ownerIds.size());
        int ownerBudget = Math.min(MAX_OWNERS_PER_TICK, ownerIds.size());
        state.pathSearchesRemaining = MAX_PATH_SEARCHES_PER_TICK;
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
                    continue;
                }
                ActionRequest request = state.requests.get(next.taskId());
                if (request == null) {
                    state.scheduler.finish(next.taskId(), TaskState.FAILED, 0, 0, "missing request");
                    continue;
                }
                ownerState.execution = new Execution(request, server.getTickCount(), companion.getX(), companion.getZ());
            }
            tickAction(server, state, ownerState, owner, companion);
        }
        state.nextOwnerCursor = (first + ownerBudget) % ownerIds.size();
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
        throw commandError("区域授权已检查；采集执行器尚未完成，本次未接受任务。", null);
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
            companion.setPlayerInput(0.0F, 0.0F, false, false);
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
            finish(server, state, ownerState, TaskState.FAILED, "采集执行器尚未接入。" );
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
            companion.setPlayerInput(0.0F, 0.0F, false, false);
            return;
        }
        navigate(server, state, ownerState, owner, companion, owner.blockPosition(), 2);
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
            companion.setPlayerInput(0.0F, 0.0F, false, false);
            return;
        }
        if (execution.path.isEmpty() || server.getTickCount() - execution.lastRepathTick >= 10) {
            if (state.pathSearchesRemaining <= 0) {
                companion.setPlayerInput(0.0F, 0.0F, false, false);
                return;
            }
            state.pathSearchesRemaining--;
            execution.lastRepathTick = server.getTickCount();
            execution.path = SurfacePathfinder.find(companion.serverLevel(), companion.blockPosition(), target, goalRadius);
            if (execution.path.isEmpty()) {
                execution.noPathTicks += 10;
                companion.setPlayerInput(0.0F, 0.0F, false, false);
                if (execution.noPathTicks >= 100) {
                    finish(server, state, ownerState, TaskState.FAILED, "附近没有已加载的可行地表路径。" );
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
                companion.setPlayerInput(0.0F, 0.0F, false, false);
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
        companion.setPlayerInput(0.0F, 1.0F, jumping, false);
        if (Math.abs(companion.getX() - execution.lastX) + Math.abs(companion.getZ() - execution.lastZ) < 0.005) {
            execution.stuckTicks++;
        } else {
            execution.stuckTicks = 0;
            execution.lastX = companion.getX();
            execution.lastZ = companion.getZ();
        }
        if (execution.stuckTicks >= 100) {
            finish(server, state, ownerState, TaskState.FAILED, "伙伴移动受阻。" );
        }
    }

    private static void finish(MinecraftServer server, ServerState state, OwnerState ownerState,
                               TaskState result, String message) {
        Execution execution = ownerState.execution;
        if (execution == null) {
            return;
        }
        state.scheduler.finish(execution.request.taskId(), result, 0, 0, message);
        state.requests.remove(execution.request.taskId());
        clearInputs(server, ownerState, execution);
        ownerState.execution = null;
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
        companion.setPlayerInput(0.0F, 0.0F, false, false);
        if (execution != null && execution.miningPos != null) {
            companion.gameMode.handleBlockBreakAction(execution.miningPos,
                    ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                    execution.miningDirection, companion.serverLevel().getMaxBuildHeight(),
                    execution.nextSequence++);
        }
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
        private BlockPos navigationTarget;
        private List<BlockPos> path = List.of();
        private BlockPos miningPos;
        private net.minecraft.core.Direction miningDirection = net.minecraft.core.Direction.UP;

        private Execution(ActionRequest request, int startTick, double initialX, double initialZ) {
            this.request = request;
            this.lastRepathTick = startTick - 10L;
            this.lastX = initialX;
            this.lastZ = initialZ;
        }
    }
}
