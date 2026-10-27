package io.github.yfyan.minecraftfriends.acceptance;

import com.mojang.brigadier.CommandDispatcher;
import io.github.yfyan.minecraftfriends.companion.CompanionRuntime;
import io.github.yfyan.minecraftfriends.action.ActionRuntime;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Same seed/map, separate JVMs, completed tick samples; not a long-duration leak test. */
public final class PerformanceScenario {
    private static final Map<MinecraftServer, PerformanceScenario> RUNS = new WeakHashMap<>();
    private final MinecraftServer server;
    private final String variant = System.getProperty("mf.acceptance.performance.variant", "");
    private final List<Long> samples = new ArrayList<>();
    private ServerPlayer owner, bot;
    private int age, ticks, phase;
    private boolean finished;
    private Vec3 last;
    private double displacement, maxStep;
    private long heapStart;
    private PerformanceScenario(MinecraftServer server) { this.server=server; }
    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) { }
    public static void tick(MinecraftServer server) { RUNS.computeIfAbsent(server, PerformanceScenario::new).step(); }
    public static void remove(MinecraftServer server) { RUNS.remove(server); }
    private void step() {
        if(finished)return;
        try {
            if(!server.isSameThread())throw new IllegalStateException("wrong_thread");
            if(++ticks>2400)throw new IllegalStateException("performance_timeout");
            if(owner==null) {
                owner=server.getPlayerList().getPlayers().stream().filter(p->p.getGameProfile().getName().equals("MF_M2Owner")).findFirst().orElse(null);
                if(owner==null)return;
                if(!server.getWorldPath(LevelResource.ROOT).toRealPath().getFileName().toString().startsWith("mf-m2-acceptance-"))throw new IllegalStateException("fresh_world_required");
                if(!Set.of("disabled","idle","active").contains(variant))throw new IllegalStateException("invalid_variant");
                if(CompanionRuntime.isEnabled()==variant.equals("disabled"))throw new IllegalStateException("configuration_mismatch");
                for(int x=-5;x<=26;x++)for(int z=-5;z<=16;z++) {
                    owner.serverLevel().setBlock(new BlockPos(x,-61,z),Blocks.STONE.defaultBlockState(),3);
                    for(int y=-60;y<=-57;y++)owner.serverLevel().setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),3);
                }
                owner.teleportTo(18.5,-60,2.5);
                emit("isolated_survival_map",true);emit("explicit_commands_enabled",true);
                emit(server instanceof net.minecraft.server.dedicated.DedicatedServer?"dedicated_server_instance":"integrated_server_instance",true);
                if(!variant.equals("disabled"))command("mf companion create MF_M2Bot");
                return;
            }
            if(phase==0) {
                if(!variant.equals("disabled")) {
                    var entry=CompanionRuntime.data(server).find(owner.getUUID()).orElse(null);
                    if(entry==null)return;
                    bot=server.getPlayerList().getPlayer(entry.identity().companionId());if(bot==null)return;
                    bot.teleportTo(2.5,-60,2.5);last=bot.position();
                    command(variant.equals("active")?"mf task follow":"mf task wait");
                }
                phase=1;age=0;return;
            }
            age++;
            if(bot!=null) {
                double step=bot.position().distanceTo(last);maxStep=Math.max(maxStep,step);displacement+=step;last=bot.position();
                if(maxStep>1.5)throw new IllegalStateException("companion_teleported");
                if(variant.equals("active") && age%100==0)owner.teleportTo((age/100)%2==0?18.5:2.5,-60,2.5);
                if(variant.equals("active") && age>20 && ActionRuntime.status(server,owner.getUUID()).state().isTerminal())throw new IllegalStateException("follow_terminated");
            }
            if(age==160)heapStart=usedHeap();
            if(age>160) {
                long elapsed=server.getTickTimesNanos()[Math.floorMod(server.getTickCount()-1,100)];
                if(elapsed<=0)throw new IllegalStateException("invalid_tick_sample");samples.add(elapsed);
            }
            if(samples.size()==600) {
                boolean conditions=variant.equals("disabled")?server.getPlayerList().getPlayers().size()==1:
                    bot!=null && (variant.equals("active")?displacement>12:displacement<1);
                check(conditions,"performance_variant_conditions");check(samples.size()==600,"performance_samples");
                long p95=percentile(.95),p99=percentile(.99);
                System.out.println("MF_M2_METRIC variant="+(variant.equals("disabled")?0:variant.equals("idle")?1:2)
                    +" count="+samples.size()+" p50_us="+percentile(.5)/1000+" p95_us="+p95/1000+" p99_us="+p99/1000
                    +" max_us="+Collections.max(samples)/1000+" heap_start_bytes="+heapStart+" heap_used_bytes="+usedHeap()
                    +" max_heap_bytes="+Runtime.getRuntime().maxMemory()+" distance_milli="+Math.round(displacement*1000));
                check(p95<50_000_000L && p99<100_000_000L,"performance_local_tick_budget");
                if(bot!=null)command("mf task stop");
                emit("result",true);owner.sendSystemMessage(Component.literal("MF_M2 PASS"));finished=true;
            }
        } catch(Exception failure) {
            finished=true;emit("performance_"+failure.getClass().getSimpleName(),false);
            if(owner!=null)owner.sendSystemMessage(Component.literal("MF_M2 FAIL performance_failed"));
        }
    }
    private static long usedHeap(){return Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();}
    private long percentile(double q){var sorted=new ArrayList<>(samples);sorted.sort(Long::compare);return sorted.get((int)Math.ceil(q*sorted.size())-1);}
    private void command(String c){owner.sendSystemMessage(Component.literal("MF_M2 COMMAND "+c));}
    private void check(boolean ok,String tag){emit(tag,ok);if(!ok)throw new IllegalStateException(tag);}
    private void emit(String tag,boolean ok){System.out.println("MF_M2_SERVER "+server.getClass().getName()+" "+tag+" "+(ok?"PASS":"FAIL"));}
}
