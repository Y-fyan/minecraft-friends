package io.github.yfyan.minecraftfriends.acceptance;

import com.mojang.brigadier.CommandDispatcher;
import io.github.yfyan.minecraftfriends.companion.CompanionRuntime;
import io.github.yfyan.minecraftfriends.action.ActionRuntime;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Fixed fresh-world checks; setup teleports are separate from measured task execution. */
public final class ActionsScenario {
    private static final Map<MinecraftServer, ActionsScenario> RUNS = new WeakHashMap<>();
    private final MinecraftServer server;
    private ServerPlayer owner, bot;
    private int phase, age, ticks;
    private Vec3 last, stopped;
    private boolean finished;
    private double maxStep;
    private final List<Long> baseline = new ArrayList<>(), active = new ArrayList<>();
    private ActionsScenario(MinecraftServer server) { this.server = server; }
    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) { }
    public static void tick(MinecraftServer server) { RUNS.computeIfAbsent(server, ActionsScenario::new).step(); }
    public static void remove(MinecraftServer server) { RUNS.remove(server); }
    private void step() {
        if (finished) return;
        ticks++; age++;
        try {
            if (owner == null) {
                owner = server.getPlayerList().getPlayers().stream()
                        .filter(p -> p.getGameProfile().getName().equals("MF_M2Owner")).findFirst().orElse(null);
                if (owner == null) { if (ticks > 6000) fail("no_test_owner"); return; }
                String world = server.getWorldPath(LevelResource.ROOT).toRealPath().getFileName().toString();
                if (!world.startsWith("mf-m2-acceptance-")) { fail("fresh_world_required"); return; }
                for (int x=-5;x<=26;x++) for (int z=-5;z<=16;z++) {
                    owner.serverLevel().setBlock(new BlockPos(x,-61,z), Blocks.STONE.defaultBlockState(),3);
                    for (int y=-60;y<=-57;y++) owner.serverLevel().setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),3);
                }
                owner.getInventory().clearContent(); owner.teleportTo(15.5,-60,2.5);
                age=0; emit("isolated_survival_map",true);
                emit(server instanceof net.minecraft.server.dedicated.DedicatedServer ? "dedicated_server_instance" : "integrated_server_instance",true); return;
            }
            if (phase==0) {
                baseline.add(server.getAverageTickTimeNanos());
                if(age>=100) { command("mf companion create MF_M2Bot"); next(1); } return;
            }
            active.add(server.getAverageTickTimeNanos());
            if(age>2400) { fail("phase_timeout_"+phase); return; }
            if(bot==null) {
                var entry=CompanionRuntime.data(server).find(owner.getUUID()).orElse(null);
                if(entry==null) return;
                bot=server.getPlayerList().getPlayer(entry.identity().companionId());
                if(bot==null)return;
            }
            switch(phase) {
                case 1 -> {
                    // One-time fixture placement before any navigation input.
                    bot.teleportTo(2.5,-60,2.5);
                    if ("unreachable".equals(System.getProperty("mf.acceptance.actions.scope"))) {
                        for (int x=1;x<=3;x++) for(int z=1;z<=3;z++) if(x!=2 || z!=2)
                            for(int y=-60;y<=-59;y++) owner.serverLevel().setBlock(new BlockPos(x,y,z),Blocks.STONE.defaultBlockState(),3);
                        stopped=bot.position();command("mf task follow");next(11);return;
                    }
                    for(int z=0;z<=5;z++) owner.serverLevel().setBlock(new BlockPos(8,-60,z),Blocks.STONE.defaultBlockState(),3);
                    last=bot.position(); maxStep=0; command("mf task follow"); next(2);
                }
                case 2 -> {
                    if (age % 20 == 0) {
                        var state = ActionRuntime.status(server,owner.getUUID()).state();
                        System.out.println("MF_M2_METRIC phase=2 x_milli="+Math.round(bot.getX()*1000)+" z_milli="+Math.round(bot.getZ()*1000)+" state_"+state.name().toLowerCase(Locale.ROOT)+"=1");
                        if (Set.of("FAILED","TIMED_OUT","CANCELLED").contains(state.name())) { fail("navigation_"+state.name().toLowerCase(Locale.ROOT)); return; }
                    }
                    double step=bot.position().distanceTo(last); maxStep=Math.max(maxStep,step);last=bot.position();
                    if(maxStep>1.5) {fail("navigation_teleport_detected");return;}
                    if(age>30 && bot.distanceTo(owner)<4.5) {
                        emit("physical_follow_over_obstacle",true); command("mf task wait"); stopped=bot.position();
                        owner.teleportTo(23.5,-60,2.5); next(3);
                    }
                }
                case 3 -> {
                    if(age==4) stopped=bot.position();
                    if(age>=44) {check(bot.position().distanceTo(stopped)<0.4,"wait_does_not_pursue");command("mf task follow");next(4);}
                }
                case 4 -> { if(age>=20) {command("mf task stop");next(5);} }
                case 5 -> {
                    if(age==4) stopped=bot.position();
                    if(age>=44) {
                        check(bot.position().distanceTo(stopped)<0.4,"stop_clears_movement");
                        if ("navigation".equals(System.getProperty("mf.acceptance.actions.scope"))) { complete(); return; }
                        bot.teleportTo(2.5,-60,6.5);owner.teleportTo(2.5,-60,3.5);
                        for(int x=7;x<=10;x++) for(int y=-60;y<=-59;y++) owner.serverLevel().setBlock(new BlockPos(x,y,6),Blocks.OAK_LOG.defaultBlockState(),3);
                        owner.serverLevel().setBlock(new BlockPos(12,-60,6),Blocks.OAK_LOG.defaultBlockState(),3);
                        command("mf task collect oak_log 8");next(6);
                    }
                }
                case 6 -> {
                    if(age>=30) {
                        check(logs()==8 && inventory(owner)==0,"collection_requires_explicit_area");
                        command("mf task area 7 -60 6 10 -59 6");next(7);
                    }
                }
                case 7 -> {if(age>=20){command("mf task collect oak_log 8");next(8);}}
                case 8 -> {
                    if(inventory(owner)>=8) {
                        check(inventory(owner)==8 && logs()==0 && inventory(bot)==0,"eight_real_logs_delivered_without_duplication");
                        check(owner.serverLevel().getBlockState(new BlockPos(12,-60,6)).is(Blocks.OAK_LOG),"outside_area_resource_untouched");
                        command("mf task collect oak_log 8");next(9);
                    }
                }
                case 9 -> {if(age>=100){check(inventory(owner)==8,"repeat_request_does_not_create_items");command("mf task stop");next(10);}}
                case 10 -> {if(age>=20){
                    complete();
                }}
                case 11 -> {
                    String state=ActionRuntime.status(server,owner.getUUID()).state().name();
                    if ("FAILED".equals(state) || "TIMED_OUT".equals(state)) {
                        check(age<=200,"unreachable_fails_within_budget");stopped=bot.position();next(12);
                    } else if(age>200) {fail("unreachable_kept_running");}
                }
                case 12 -> {if(age>=40){check(bot.position().distanceTo(stopped)<0.4,"failed_path_leaves_bot_still");complete();}}
                default -> fail("invalid_phase");
            }
        } catch(Exception failure) { fail("exception_"+failure.getClass().getSimpleName()); }
    }
    private void complete(){
        System.out.println("MF_M2_METRIC baseline_mean_tick_ns="+mean(baseline)+" active_mean_tick_ns="+mean(active)+" samples="+active.size());
        emit("result",true);owner.sendSystemMessage(Component.literal("MF_M2 PASS"));finished=true;
    }
    private int logs(){int n=0;for(int x=7;x<=10;x++)for(int y=-60;y<=-59;y++)if(owner.serverLevel().getBlockState(new BlockPos(x,y,6)).is(Blocks.OAK_LOG))n++;return n;}
    private static int inventory(ServerPlayer p){int n=0;for(int i=0;i<p.getInventory().getContainerSize();i++){var s=p.getInventory().getItem(i);if(s.is(Items.OAK_LOG))n+=s.getCount();}return n;}
    private static long mean(List<Long> samples){return samples.isEmpty()?0:(long)samples.stream().mapToLong(Long::longValue).average().orElse(0);}
    private void next(int p){phase=p;age=0;}
    private void command(String command){owner.sendSystemMessage(Component.literal("MF_M2 COMMAND "+command));}
    private void check(boolean ok,String tag){if(!ok){emit(tag,false);throw new IllegalStateException(tag);}emit(tag,true);}
    private void emit(String tag,boolean ok){System.out.println("MF_M2_SERVER "+server.getClass().getName()+" "+tag+" "+(ok?"PASS":"FAIL"));}
    private void fail(String reason){finished=true;emit(reason,false);if(owner!=null)owner.sendSystemMessage(Component.literal("MF_M2 FAIL "+reason));}
}
