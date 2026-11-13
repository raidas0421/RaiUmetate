package jp.rai42.umetate;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.boss.*;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DoubleTimeTest {
    @TempDir Path directory;
    @Test void timerReplacementExtensionAndExpirationEvents() {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            var m=new DoubleTimeManager(f.plugin,()->f.world);
            m.start(30,2);assertEquals(600,m.getRemainingTicks());
            for(int i=0;i<20;i++)m.tick();assertEquals(29,m.snapshot().remainingSeconds());
            m.extend(1000);assertEquals(12000,m.getRemainingTicks());assertEquals(12000,m.getTotalTicks());
            m.extend(-599);assertEquals(20,m.getRemainingTicks());
            m.start(2,3);assertEquals(40,m.getRemainingTicks());assertEquals(3,m.getMultiplier());
            for(int i=0;i<40;i++)m.tick();assertFalse(m.isActive());
            var events=ArgumentCaptor.forClass(org.bukkit.event.Event.class);
            verify(Bukkit.getPluginManager(),times(3)).callEvent(events.capture());
            assertEquals(30,((DoubleTimeStartEvent)events.getAllValues().get(0)).getSeconds());
            assertEquals(3,((DoubleTimeStartEvent)events.getAllValues().get(1)).getMultiplier());
            assertEquals(DoubleTimeManager.Reason.EXPIRED,((DoubleTimeEndEvent)events.getValue()).getReason());
        }
    }
    @Test void startOrExtendStartsWhenIdleAndAddsWhenActive() {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            var m=new DoubleTimeManager(f.plugin,()->f.world);
            assertTrue(m.startOrExtend(30,3));assertEquals(600,m.getRemainingTicks());assertEquals(3,m.getMultiplier());
            assertFalse(m.startOrExtend(20,5));assertEquals(1000,m.getRemainingTicks());assertEquals(3,m.getMultiplier());
            assertFalse(m.startOrExtend(600,2));assertEquals(12000,m.getRemainingTicks());
            assertThrows(IllegalArgumentException.class,()->m.startOrExtend(0,2));
            m.stop(DoubleTimeManager.Reason.STOPPED);assertTrue(m.startOrExtend(10,2));assertEquals(200,m.getRemainingTicks());
        }
    }
    @Test void limitsInactiveOperationsAndOverflowAreSafe() {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            var m=new DoubleTimeManager(f.plugin,()->f.world);
            assertThrows(IllegalArgumentException.class,()->m.extend(1));
            for(int n:new int[]{0,601,Integer.MAX_VALUE})assertThrows(IllegalArgumentException.class,()->m.start(n,2));
            for(int n:new int[]{1,11})assertThrows(IllegalArgumentException.class,()->m.start(30,n));
            m.start(30,2);m.extend(Integer.MAX_VALUE);assertEquals(12000,m.getRemainingTicks());
            m.extend(Integer.MIN_VALUE);assertFalse(m.isActive());
            f.bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
            assertThrows(IllegalStateException.class,()->m.start(30,2));
        }
    }
    @Test void copiesAreStaggeredAndNeverMultiplyAgain() {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            var m=new DoubleTimeManager(f.plugin,()->f.world);var calls=new AtomicInteger();
            m.start(30,3);m.launch("tnt",calls::incrementAndGet);assertEquals(1,calls.get());
            for(int i=0;i<4;i++)m.tick();assertEquals(1,calls.get());m.tick();assertEquals(2,calls.get());
            for(int i=0;i<5;i++)m.tick();assertEquals(3,calls.get());
            for(int i=0;i<30;i++)m.tick();assertEquals(3,calls.get());
        }
    }
    @Test void exclusionsAndSupportRemainSingleAndCanBeConfigured() {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            var m=new DoubleTimeManager(f.plugin,()->f.world);var calls=new AtomicInteger();m.start(30,2);
            for(String type:List.of("prison","freeze","repair","shield"))m.launch(type,calls::incrementAndGet);
            for(int i=0;i<10;i++)m.tick();assertEquals(4,calls.get());
            f.plugin.getConfig().set("double-time.excluded-effects",List.of("tnt"));m.reloadSettings();
            m.launch("tnt",calls::incrementAndGet);m.launch("freeze",calls::incrementAndGet);
            for(int i=0;i<5;i++)m.tick();assertEquals(7,calls.get());
        }
    }
    @Test void additionalCapacityFailureIsSilentAndOriginalStillFails() {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            var m=new DoubleTimeManager(f.plugin,()->f.world);var attempts=new AtomicInteger();m.start(30,3);
            m.launch("enderman",()->{if(attempts.incrementAndGet()>1)throw new IllegalArgumentException("capacity");});
            for(int i=0;i<10;i++)assertDoesNotThrow(m::tick);assertEquals(3,attempts.get());
            assertThrows(IllegalArgumentException.class,()->m.launch("enderman",()->{throw new IllegalArgumentException("capacity");}));
        }
    }
    @Test void titlesProgressWorldMembershipAndShutdown() {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            BossBar bar=mock(BossBar.class);var members=new ArrayList<Player>();when(bar.getPlayers()).thenAnswer(i->List.copyOf(members));
            doAnswer(i->{members.add(i.getArgument(0));return null;}).when(bar).addPlayer(any());
            doAnswer(i->{members.remove(i.getArgument(0));return null;}).when(bar).removePlayer(any());
            f.bukkit.when(()->Bukkit.createBossBar(anyString(),eq(BarColor.PURPLE),eq(BarStyle.SOLID))).thenReturn(bar);
            Player player=mock(Player.class),other=mock(Player.class);when(player.isOnline()).thenReturn(true);
            when(player.getWorld()).thenReturn(f.world);when(other.getWorld()).thenReturn(mock(World.class));
            f.bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player,other));
            var m=new DoubleTimeManager(f.plugin,()->f.world);m.start(2,3);
            verify(bar).setTitle("妨害3倍タイム 残り 2秒");verify(bar).setProgress(1);verify(bar).addPlayer(player);verify(bar,never()).addPlayer(other);
            for(int i=0;i<20;i++)m.tick();verify(bar).setProgress(.5);
            m.extend(1);assertEquals(40,m.getTotalTicks());
            World another=other.getWorld();when(player.getWorld()).thenReturn(another);m.tick();verify(bar).removePlayer(player);
            when(player.getWorld()).thenReturn(f.world);m.tick();verify(bar,times(2)).addPlayer(player);
            var titles=ArgumentCaptor.forClass(Title.class);m.stop(DoubleTimeManager.Reason.STOPPED);
            verify(player,times(2)).showTitle(titles.capture());
            assertEquals("妨害3倍タイム！",((TextComponent)titles.getAllValues().get(0).title()).content());
            assertEquals(250,titles.getAllValues().get(0).times().fadeIn().toMillis());
            assertEquals("妨害3倍タイム終了",((TextComponent)titles.getValue().title()).content());
            m.start(2,2);clearInvocations(player);m.stop(DoubleTimeManager.Reason.SHUTDOWN);verify(player,never()).showTitle(any(Title.class));verify(bar,times(2)).removeAll();
        }
    }
    @Test void stopReasonsAndPendingCancellation() {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            var m=new DoubleTimeManager(f.plugin,()->f.world);var calls=new AtomicInteger();
            for(var reason:DoubleTimeManager.Reason.values()){
                m.start(30,2);m.launch("tnt",calls::incrementAndGet);m.stop(reason);
                var event=ArgumentCaptor.forClass(org.bukkit.event.Event.class);verify(Bukkit.getPluginManager(),atLeastOnce()).callEvent(event.capture());
                assertEquals(reason,((DoubleTimeEndEvent)event.getValue()).getReason());
                m.cancelPending();
            }
            for(int i=0;i<10;i++)m.tick();assertEquals(4,calls.get());
        }
    }
    @Test void customMessagesAndOverlayPublishAdditionalStateWithoutChangingHp() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            var m=new DoubleTimeManager(f.plugin,()->f.world);
            f.plugin.getConfig().set("double-time.messages.bossbar","倍率{倍率} / {秒}秒");m.reloadSettings();
            m.start(60,4);verify(f.bar).setTitle("倍率4 / 60秒");
            var overlay=new FrameOverlayServer(f.plugin,f.health,m);
            overlay.start();verify(f.plugin,never()).getResource("frame-overlay.html");
            var exchange=mock(com.sun.net.httpserver.HttpExchange.class);
            when(exchange.getResponseHeaders()).thenReturn(new com.sun.net.httpserver.Headers());
            var output=new java.io.ByteArrayOutputStream();when(exchange.getResponseBody()).thenReturn(output);
            var state=FrameOverlayServer.class.getDeclaredMethod("state",com.sun.net.httpserver.HttpExchange.class);state.setAccessible(true);state.invoke(overlay,exchange);
            Map<?,?> json=new org.yaml.snakeyaml.Yaml().load(output.toString(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(10000d,((Number)json.get("health")).doubleValue());assertTrue(json.containsKey("revision"));
            Map<?,?> dt=(Map<?,?>)json.get("doubleTime");assertEquals(true,dt.get("active"));assertEquals(4,dt.get("multiplier"));assertEquals(60,dt.get("remainingSeconds"));assertEquals(60,dt.get("totalSeconds"));
        }
    }
    @Test void zeroStaggerStillDoesNotRecurseAndAcceptedCopiesSurviveExpiry() {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            var m=new DoubleTimeManager(f.plugin,()->f.world);var calls=new AtomicInteger();
            f.plugin.getConfig().set("double-time.stagger-ticks",0);m.reloadSettings();m.start(1,3);m.launch("tnt",calls::incrementAndGet);assertEquals(3,calls.get());
            f.plugin.getConfig().set("double-time.stagger-ticks",25);m.reloadSettings();m.launch("tnt",calls::incrementAndGet);
            for(int i=0;i<50;i++)m.tick();assertFalse(m.isActive());assertEquals(6,calls.get());
        }
    }
    @Test void commandsValidateAndCompleteForConsole() {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            var m=new DoubleTimeManager(f.plugin,()->f.world);var game=mock(UmetateGame.class);when(game.doubleTime()).thenReturn(m);
            var command=new UmetateCommand(game);
            command.onCommand(f.console,null,"umetate",new String[]{"doubletime","start"});assertEquals(600,m.getRemainingTicks());
            command.onCommand(f.console,null,"umetate",new String[]{"doubletime","start","0","20"});assertEquals(600,m.getRemainingTicks());
            command.onCommand(f.console,null,"umetate",new String[]{"doubletime","start","10","5"});assertEquals(800,m.getRemainingTicks());assertEquals(2,m.getMultiplier());
            command.onCommand(f.console,null,"umetate",new String[]{"doubletime","10"});assertEquals(1000,m.getRemainingTicks());
            command.onCommand(f.console,null,"umetate",new String[]{"doubletime","start"});assertEquals(1600,m.getRemainingTicks());
            command.onCommand(f.console,null,"umetate",new String[]{"doubletime","stop"});assertFalse(m.isActive());
            command.onCommand(f.console,null,"umetate",new String[]{"doubletime","20","4"});assertEquals(400,m.getRemainingTicks());assertEquals(4,m.getMultiplier());
            command.onCommand(f.console,null,"umetate",new String[]{"doubletime","stop"});
            command.onCommand(f.console,null,"umetate",new String[]{"doubletime","start"});
            command.onCommand(f.console,null,"umetate",new String[]{"doubletime","add","-30"});assertFalse(m.isActive());
            clearInvocations(f.console);
            for(String sub:List.of("stop","add"))command.onCommand(f.console,null,"umetate",sub.equals("add")?new String[]{"doubletime",sub,"5"}:new String[]{"doubletime",sub});
            verify(f.console,times(2)).sendMessage(any(net.kyori.adventure.text.Component.class));
            assertEquals(List.of("start","stop","add","status"),command.onTabComplete(f.console,null,"umetate",new String[]{"doubletime",""}));
            assertEquals(List.of("30","60","120"),command.onTabComplete(f.console,null,"umetate",new String[]{"doubletime","start",""}));
        }
    }
}
