package jp.rai42.umetate;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerCommandSendEvent;
import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class V2RulesTest {
    @Test void clockHoldsOnlyTwoForThirtyTicksAndKeepsExactTotal() {
        for(int seconds:new int[]{1,2,3,15}) {
            WinClock clock=new WinClock(seconds);assertEquals(WinClock.Event.START,clock.tick(true));
            Map<Integer,Integer> durations=new HashMap<>();int ticks=0;
            while(true){durations.merge(clock.secondsLeft(),1,Integer::sum);ticks++;
                if(clock.tick(true)==WinClock.Event.WIN)break;assertTrue(ticks<400);}
            assertEquals(seconds*20+(seconds>=2?10:0),ticks);
            for(int i=1;i<=seconds;i++)assertEquals(i==2?30:20,durations.get(i));
        }
    }
    @Test void clockCancellationAndPauseRearmLongTwoCorrectly() {
        WinClock clock=new WinClock(2);clock.tick(true);for(int i=0;i<29;i++)clock.tick(true);
        assertEquals(2,clock.secondsLeft());assertEquals(WinClock.Event.CANCEL,clock.tick(false));assertTrue(clock.interrupted());
        clock.tick(true);assertFalse(clock.interrupted());for(int i=0;i<30;i++)clock.tick(true);assertEquals(1,clock.secondsLeft());
        for(int i=0;i<19;i++)clock.tick(true);assertTrue(clock.willWinNextTick());clock.pause();assertFalse(clock.willWinNextTick());
        clock.resume();assertEquals(WinClock.Event.START,clock.tick(true));assertEquals(2,clock.secondsLeft());
        assertThrows(IllegalArgumentException.class,()->new WinClock(0));assertThrows(IllegalArgumentException.class,()->new WinClock(-1));
    }
    @Test void sizeMigrationUsesExplicitNewKeysBeforeBundledDefaults() {
        var c=new YamlConfiguration();var defaults=new YamlConfiguration();defaults.set("default-diameter",11);defaults.set("max-diameter",53);c.setDefaults(defaults);
        c.set("default-radius",8);c.set("max-radius",50);c.set("max-height",40);c.set("max-volume",1);SizeRules.migrate(c);
        var rules=SizeRules.read(c);assertEquals(15,rules.defaultDiameter());assertEquals(53,rules.maxDiameter());assertEquals(36,rules.maxHeight());
        assertFalse(c.contains("default-radius",true));assertFalse(c.contains("max-volume",true));
        c.set("default-radius",6);c.set("default-diameter",17);SizeRules.migrate(c);assertEquals(17,SizeRules.read(c).defaultDiameter());
    }
    @Test void sizeRulesRejectEvenOutOfBoundsAndLabelExtremes() {
        var r=SizeRules.read(new YamlConfiguration());
        for(int n:new int[]{2,4,54,55})assertThrows(IllegalArgumentException.class,()->r.radius(n));
        assertEquals(2,r.radius(3));assertEquals(27,r.radius(53));
        for(int n:new int[]{-2,0,1,3})assertThrows(IllegalArgumentException.class,()->SizeRules.delta(n));
        assertEquals(2,SizeRules.delta(2));
        assertThrows(IllegalArgumentException.class,()->r.validate(11,2));assertThrows(IllegalArgumentException.class,()->r.validate(11,37));
        assertEquals("直径53(最大) 高さ36(最大)",r.label(53,36));assertEquals("直径3(最小) 高さ3(最小)",r.label(3,3));
    }
    @Test void sizeCommandsMapInteriorDiameterToRadius() {
        var g=mock(UmetateGame.class);when(g.sizes()).thenReturn(SizeRules.read(new YamlConfiguration()));when(g.box()).thenReturn(Box.restored(0,0,0,6,9));
        when(g.defaultRadius()).thenReturn(6);when(g.defaultHeight()).thenReturn(9);
        var s=mock(Player.class);when(s.hasPermission("raiumetate.admin")).thenReturn(true);var command=new UmetateCommand(g);
        String[][] calls={{"diametermax"},{"diametermin"},{"heightmax"},{"heightmin"},{"sizemax"},{"sizemin"},{"sizereset"},{"diameter","13"},{"diameterup","2"},{"diameterdown","2"}};
        int[][] results={{27,9},{2,9},{6,36},{6,3},{27,36},{2,3},{6,9},{7,9},{7,9},{5,9}};
        for(int i=0;i<calls.length;i++){clearInvocations(g);command.onCommand(s,null,"umetate",calls[i]);verify(g).resize(results[i][0],results[i][1]);}
        clearInvocations(g);command.onCommand(s,null,"umetate",new String[]{"diameterup","3"});verify(g,never()).resize(anyInt(),anyInt());
        command.onCommand(s,null,"umetate",new String[]{"create","11","9"});verify(g).create(s,6,9);
    }
    @Test void oversizeCommandsClampInsteadOfRejectingIncludingHugeNumbers() {
        var g=mock(UmetateGame.class);when(g.sizes()).thenReturn(SizeRules.read(new YamlConfiguration()));when(g.box()).thenReturn(Box.restored(0,0,0,6,9));
        var player=mock(Player.class);when(player.hasPermission("raiumetate.admin")).thenReturn(true);var command=new UmetateCommand(g);
        command.onCommand(player,null,"umetate",new String[]{"create","100","999999999999999999999999999999"});verify(g).create(player,27,36);
        command.onCommand(player,null,"umetate",new String[]{"diameter","100"});verify(g).resize(27,9);
        clearInvocations(g);command.onCommand(player,null,"umetate",new String[]{"diameterup","99999999999999999999999999"});verify(g).resize(27,9);
        command.onCommand(player,null,"umetate",new String[]{"heightup","99999999999999999999999999"});verify(g).resize(6,36);
        clearInvocations(g);command.onCommand(player,null,"umetate",new String[]{"diameter","10"});verify(g,never()).resize(anyInt(),anyInt());
    }
    @Test void bossTitleCoversStateAndPercentageFloor() {
        assertEquals("枠HP 8,500 / 10,000 (85%) | 直径11 高さ9 | カウント 12秒",FrameHealth.barTitle(8500,10000,"直径11 高さ9","カウント 12秒",false));
        assertTrue(FrameHealth.barTitle(899,1000,"直径53(最大) 高さ36(最大)","待機中",false).contains("(89%)"));
        assertTrue(FrameHealth.barTitle(1,1000,"直径3(最小) 高さ3(最小)","中断",false).endsWith("中断"));
        assertEquals("枠崩壊 — 再生成中",FrameHealth.barTitle(0,1000,"","",true));
    }
    @Test void columnCacheHandlesEmptyPartialFullRemovalRoofAndWorldCeiling() {
        Box b=Box.restored(0,20,0,6,9);var c=new ColumnHeights(b);assertEquals(23,c.spawnY(null,320));
        c.update(b.index(0,25,0),true);assertEquals(28,c.spawnY(null,320));
        for(int i=0;i<b.capacity();i++)c.update(i,true);assertEquals(32,c.spawnY(null,320));
        for(int i=0;i<b.capacity();i++)c.update(i,false);assertEquals(23,c.spawnY(null,320));
        assertEquals(34,c.spawnY(31d,320));assertEquals(31,c.spawnY(31d,32));
    }
    @Test void bothCompletionEventsHideNamespacedCommandsAndLegacyAlias() {
        var listener=new CommandVisibility();var player=mock(Player.class);
        var names=new ArrayList<>(List.of("umetate","win","hp","rwin","raiumetate:win","raiumetate:hp","help"));
        var e=new PlayerCommandSendEvent(player,names);listener.commands(e);assertEquals(List.of("umetate","win","hp","help"),new ArrayList<>(e.getCommands()));
        var async=mock(AsyncTabCompleteEvent.class);when(async.isCommand()).thenReturn(true);
        var matches=new ArrayList<>(List.of("/umetate","/rwin","/raiumetate:umetate","/win","/hp"));when(async.getCompletions()).thenReturn(matches);
        listener.complete(async);assertEquals(List.of("/umetate","/win","/hp"),matches);
        assertFalse(CommandVisibility.hidden("minecraft:help"));
    }
    @Test void directAndFountainReservationsShareHolesButKeepRetryModes() {
        var fill=new FillState(3);fill.enqueue(2,true);fill.enqueue(10,false);
        int direct=fill.reserve(true),fountain=fill.reserve();assertNotEquals(direct,fountain);
        fill.finish(direct,false);int retry=fill.reserve(true);assertEquals(direct,retry);fill.finish(retry,true);
        fill.finish(fountain,true);fill.finish(fill.reserve(true),true);assertTrue(fill.full());assertEquals(0,fill.pending());
    }
}
