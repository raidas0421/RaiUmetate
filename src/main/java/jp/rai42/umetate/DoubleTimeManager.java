package jp.rai42.umetate;

import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.boss.*;
import org.bukkit.plugin.java.JavaPlugin;

/** Runtime-only service. Mutations and launches must run on the server thread. */
public final class DoubleTimeManager {
    public enum Reason { EXPIRED, STOPPED, ARENA_DELETED, SHUTDOWN }
    public record Snapshot(boolean active,int multiplier,int remainingSeconds,int totalSeconds) {}
    private final JavaPlugin plugin;
    private Settings.DoubleTime settings;
    void reloadSettings(Settings updated){settings=updated.doubleTime();}
    void reloadSettings(){reloadSettings(Settings.read(plugin.getConfig()));}
    private final Supplier<World> arenaWorld;
    private volatile int remaining,total;
    private int multiplier=2;
    private long tick,sequence;
    private boolean additionalLaunch;
    boolean isAdditionalLaunch(){return additionalLaunch;}
    private BossBar bar;
    private volatile Snapshot snapshot=new Snapshot(false,2,0,0);
    private record Pending(long due,long sequence,String type,Runnable launch) {}
    private final PriorityQueue<Pending> pending=new PriorityQueue<>(Comparator.comparingLong(Pending::due).thenComparingLong(Pending::sequence));
    DoubleTimeManager(JavaPlugin plugin,Supplier<World> arenaWorld){this.plugin=plugin;this.arenaWorld=arenaWorld;reloadSettings();}
    public boolean isActive(){return snapshot.active();}
    public int getMultiplier(){return snapshot.multiplier();}
    public int getRemainingTicks(){return remaining;}
    public int getTotalTicks(){return total;}
    public Snapshot snapshot(){return snapshot;}
    public int maxSeconds(){return settings.maxSeconds();}
    public int maxMultiplier(){return settings.maxMultiplier();}
    public int defaultSeconds(){return settings.defaultSeconds();}
    public int defaultMultiplier(){return settings.defaultMultiplier();}
    private void mainThread(){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("DoubleTimeManagerはメインスレッドから呼び出してください。");}
    public void start(int seconds,int factor){
        mainThread();
        if(arenaWorld.get()==null)throw new IllegalArgumentException("先に箱を作成してください。");
        if(seconds<1 || seconds>maxSeconds())throw new IllegalArgumentException("秒数は1～"+maxSeconds()+"です。");
        if(factor<2 || factor>maxMultiplier())throw new IllegalArgumentException("倍率は2～"+maxMultiplier()+"です。");
        remaining=total=seconds*20;multiplier=factor;publish();refresh();
        announce(true);Bukkit.getPluginManager().callEvent(new DoubleTimeStartEvent(factor,seconds));
    }
    /** 未発動なら開始、発動中なら残り時間を追加（倍率は現在のものを維持）。開始したらtrue。 */
    public boolean startOrExtend(int seconds,int factor){
        mainThread();
        if(isActive()){
            if(seconds<1 || seconds>maxSeconds())throw new IllegalArgumentException("秒数は1～"+maxSeconds()+"です。");
            extend(seconds);return false;
        }
        start(seconds,factor);return true;
    }
    public void stop(Reason reason){
        mainThread();Objects.requireNonNull(reason);
        if(reason!=Reason.EXPIRED)pending.clear();
        if(remaining==0)return;
        remaining=total=0;publish();
        if(bar!=null){bar.removeAll();bar=null;}
        if(reason!=Reason.SHUTDOWN)announce(false);
        Bukkit.getPluginManager().callEvent(new DoubleTimeEndEvent(multiplier,reason));
    }
    public void extend(int seconds){
        mainThread();requireActive();
        long next=Math.min((long)maxSeconds()*20,(long)remaining+(long)seconds*20);
        if(next<=0){stop(Reason.EXPIRED);return;}
        remaining=total=(int)next;publish();refresh();
    }
    public void requireActive(){if(!isActive())throw new IllegalArgumentException("妨害2倍タイムは発動していません");}
    void tick(){
        tick++;
        if(remaining>0){if(remaining==1)stop(Reason.EXPIRED);else{remaining--;publish();refresh();}}
        int budget=100;
        while(budget-->0 && !pending.isEmpty() && pending.peek().due()<=tick){
            Pending p=pending.remove();launch(p.type(),p.launch(),true);
        }
    }
    void cancelPending(){pending.clear();}
    void launch(String type,Runnable action){mainThread();launch(type,action,false);}
    private void launch(String type,Runnable action,boolean additional){
        if(additional){
            boolean previous=additionalLaunch;additionalLaunch=true;
            try{action.run();}catch(IllegalArgumentException e){plugin.getLogger().fine("妨害追加分をスキップ: "+type+" / "+e.getMessage());}
            finally{additionalLaunch=previous;}
            return;
        }
        int copies=isActive()?multiplier:1;
        var excluded=settings.excluded();
        if(type.equals("shield") || type.equals("repair") || excluded.stream().anyMatch(type::equalsIgnoreCase))copies=1;
        action.run();
        int stagger=settings.stagger();
        for(int i=1;i<copies;i++){
            if(pending.size()>=10000){plugin.getLogger().fine("妨害追加待ちの上限: "+type);break;}
            if(stagger==0)launch(type,action,true);
            else pending.add(new Pending(tick+(long)stagger*i,sequence++,type,action));
        }
    }
    private void publish(){snapshot=new Snapshot(remaining>0,multiplier,(int)(((long)remaining+19)/20),(int)(((long)total+19)/20));}
    String message(String key,String fallback){return settings.messages().getOrDefault(key,fallback).replace("{倍率}",Integer.toString(multiplier)).replace("{秒}",Integer.toString(snapshot.remainingSeconds()));}
    private void refresh(){
        if(bar==null)bar=Bukkit.createBossBar("",BarColor.PURPLE,BarStyle.SOLID);
        bar.setTitle(message("bossbar","妨害{倍率}倍タイム 残り {秒}秒"));bar.setProgress((double)remaining/total);
        World world=arenaWorld.get();
        for(var player:new ArrayList<>(bar.getPlayers()))if(!player.isOnline() || !player.getWorld().equals(world))bar.removePlayer(player);
        for(var player:Bukkit.getOnlinePlayers())if(player.getWorld().equals(world) && !bar.getPlayers().contains(player))bar.addPlayer(player);
    }
    private void announce(boolean start){
        String key=start?"start":"end";
        Title title=Title.title(Component.text(message(key+"-title",start?"妨害{倍率}倍タイム！":"妨害{倍率}倍タイム終了")),
            Component.text(message(key+"-subtitle",start?"{秒}秒間、妨害が{倍率}倍になる":"元に戻りました")),
            Title.Times.times(Duration.ofMillis(250),Duration.ofMillis(2000),Duration.ofMillis(500)));
        String sound=settings.sounds().getOrDefault(key,"");
        org.bukkit.Sound parsed=null;
        if(!sound.isBlank())try{parsed=org.bukkit.Sound.valueOf(sound);}catch(IllegalArgumentException e){plugin.getLogger().fine("不明なサウンド: "+sound);}
        for(var player:Bukkit.getOnlinePlayers())if(player.getWorld().equals(arenaWorld.get())){
            player.showTitle(title);if(parsed!=null)player.playSound(player.getLocation(),parsed,1,1);
        }
    }
}
