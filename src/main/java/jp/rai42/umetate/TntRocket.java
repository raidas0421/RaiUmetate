package jp.rai42.umetate;

import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.GameMode;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;

/** Reference timing: immediate TNT at the player's feet every two ticks. */
final class TntRocket {
    static final String TAG = "rai_umetate_rocket";
    private final Function<Player,AttributeInstance> resistance;
    private final List<Launch> launches = new ArrayList<>();
    private final Map<Player,Pilot> pilots = new HashMap<>();
    private final Set<TNTPrimed> bombs = new HashSet<>();
    TntRocket(Function<Player,AttributeInstance> resistance) { this.resistance=resistance; }
    private static final class Launch {
        final List<Player> players; int left,next;
        final String name;
        Launch(List<Player> players,int count,int next,String name){this.players=List.copyOf(players);this.left=count;this.next=next;this.name=name;}
    }
    private static final class Pilot {
        final AttributeInstance attribute; final double previous;
        int releaseAt, titleUntil; boolean restored;
        Pilot(AttributeInstance attribute){this.attribute=attribute;previous=attribute==null?0:attribute.getBaseValue();}
        void restore(){if(!restored && attribute!=null && attribute.getBaseValue()==0)attribute.setBaseValue(previous);restored=true;}
    }
    int pending(){return launches.stream().mapToInt(l->l.left).sum();}
    void start(List<Player> players,int count,int now){start(players,count,now,"");}
    void start(List<Player> players,int count,int now,String name){
        if(count<1||count>100)throw new IllegalArgumentException("回数は1～100です。");
        if(players.isEmpty())throw new IllegalArgumentException("対象プレイヤーがオンラインではありません。");
        launches.add(new Launch(players,count,now+1,name==null?"":name));
    }
    void tick(int now){
        bombs.removeIf(e->!e.isValid());
        for(var iterator=launches.iterator();iterator.hasNext();){
            Launch launch=iterator.next();if(now<launch.next)continue;
            boolean active=false;
            for(Player player:launch.players){
                if(!player.isOnline()||player.isDead())continue;
                active=true;
                Pilot pilot=pilots.get(player);
                if(pilot==null||pilot.restored){pilot=new Pilot(resistance.apply(player));pilots.put(player,pilot);}
                if(pilot.attribute!=null)pilot.attribute.setBaseValue(0);
                pilot.releaseAt=now+10;pilot.titleUntil=now+1200;
                if(player.getGameMode()==GameMode.CREATIVE&&player.isFlying())player.setFlying(false);
                TNTPrimed tnt=player.getWorld().spawn(player.getLocation(),TNTPrimed.class);
                tnt.addScoreboardTag(TAG);tnt.setPersistent(false);tnt.setFuseTicks(0);bombs.add(tnt);
                if(!launch.name.isBlank()){tnt.customName(Component.text(launch.name,NamedTextColor.GOLD));tnt.setCustomNameVisible(true);}
            }
            if(!active||--launch.left<=0)iterator.remove();else launch.next=now+2;
        }
        for(var iterator=pilots.entrySet().iterator();iterator.hasNext();){
            var entry=iterator.next();Player player=entry.getKey();Pilot pilot=entry.getValue();
            if(!player.isOnline()||player.isDead()){pilot.restore();iterator.remove();continue;}
            if(now>=pilot.releaseAt)pilot.restore();
            if(pilot.restored&&(player.isOnGround()||now>=pilot.titleUntil)){iterator.remove();continue;}
            if(now%2==0&&!player.isOnGround())player.showTitle(Title.title(
                    Component.text("月まで飛んでいけ！",NamedTextColor.WHITE),
                    Component.text("高さ: "+player.getLocation().getBlockY(),NamedTextColor.WHITE),
                    Title.Times.times(Duration.ZERO,Duration.ofMillis(500),Duration.ZERO)));
        }
    }
    void stop(){
        launches.clear();for(var entry:pilots.entrySet()){entry.getValue().restore();entry.getKey().clearTitle();}
        pilots.clear();bombs.forEach(TNTPrimed::remove);bombs.clear();
    }
}
