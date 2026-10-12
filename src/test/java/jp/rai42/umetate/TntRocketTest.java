package jp.rai42.umetate;
import java.util.*;
import org.bukkit.*;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
class TntRocketTest {
    @Test void referenceCadenceAndImmediateFuseRestoreResistanceAfterTenTicks(){
        World world=mock(World.class);Player player=mock(Player.class);AttributeInstance attribute=mock(AttributeInstance.class);
        double[] base={1};when(attribute.getBaseValue()).thenAnswer(i->base[0]);
        doAnswer(i->{base[0]=i.getArgument(0);return null;}).when(attribute).setBaseValue(anyDouble());
        when(player.isOnline()).thenReturn(true);when(player.isOnGround()).thenReturn(true);
        when(player.getWorld()).thenReturn(world);when(player.getLocation()).thenReturn(new Location(world,4,80,9));
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);when(player.isFlying()).thenReturn(true);
        List<TNTPrimed> spawned=new ArrayList<>();
        when(world.spawn(any(Location.class),eq(TNTPrimed.class))).thenAnswer(i->{TNTPrimed t=mock(TNTPrimed.class);when(t.isValid()).thenReturn(true);spawned.add(t);return t;});
        TntRocket rocket=new TntRocket(p->attribute);rocket.start(List.of(player),3,0);
        for(int tick=0;tick<=5;tick++){rocket.tick(tick);assertEquals((tick+1)/2,spawned.size());}
        assertEquals(0,rocket.pending());assertEquals(0,base[0]);
        for(var t:spawned){verify(t).setFuseTicks(0);verify(t).addScoreboardTag(TntRocket.TAG);}
        verify(player,times(3)).setFlying(false);
        rocket.tick(14);assertEquals(0,base[0]);rocket.tick(15);assertEquals(1,base[0]);
        rocket.stop();spawned.forEach(t->verify(t).remove());
    }
    @Test void overlappingLaunchesAndCancellationDoNotLoseOriginalAttribute(){
        World world=mock(World.class);Player p=mock(Player.class);AttributeInstance attribute=mock(AttributeInstance.class);
        double[] base={.75};when(attribute.getBaseValue()).thenAnswer(i->base[0]);
        doAnswer(i->{base[0]=i.getArgument(0);return null;}).when(attribute).setBaseValue(anyDouble());
        when(p.isOnline()).thenReturn(true);when(p.isOnGround()).thenReturn(true);when(p.getWorld()).thenReturn(world);
        when(p.getLocation()).thenReturn(new Location(world,0,80,0));when(world.spawn(any(Location.class),eq(TNTPrimed.class))).thenReturn(mock(TNTPrimed.class));
        TntRocket rocket=new TntRocket(x->attribute);rocket.start(List.of(p),20,0);rocket.tick(1);
        rocket.start(List.of(p),2,1);rocket.tick(2);assertEquals(0,base[0]);rocket.stop();assertEquals(.75,base[0]);assertEquals(0,rocket.pending());
        verify(p).clearTitle();
    }
    @Test void commandDefaultsToTwentyAndRejectsInvalidCounts(){
        UmetateGame game=mock(UmetateGame.class);Player p=mock(Player.class);when(p.hasPermission("raiumetate.admin")).thenReturn(true);
        UmetateCommand command=new UmetateCommand(game);
        command.onCommand(p,null,"bedrock",new String[]{"tntrocket"});verify(game).tntRocket(20,"");
        command.onCommand(p,null,"bedrock",new String[]{"tntrocket","5"});verify(game).tntRocket(5,"");
        clearInvocations(game);command.onCommand(p,null,"bedrock",new String[]{"tntrocket","0"});verify(game,never()).tntRocket(anyInt(),anyString());
    }
}
