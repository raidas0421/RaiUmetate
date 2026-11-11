package jp.rai42.umetate;

import java.nio.file.Path;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PhaseTwoTest {
    @TempDir Path directory;
    @Test void settingsClampBudgetsAndRejectInvalidSizeConfiguration() {
        var c=new YamlConfiguration();c.set("performance.changes-per-tick",999999);c.set("gravity.interval-ticks",0);
        c.set("performance.max-work-ms-per-tick",Double.NaN);c.set("block-reach",Double.POSITIVE_INFINITY);
        c.set("effects.tnt-radius",Double.NaN);c.set("double-time.max-seconds",Integer.MAX_VALUE);
        c.set("double-time.max-multiplier",1);c.set("double-time.stagger-ticks",-100);
        var s=Settings.read(c);assertEquals(5000,s.performanceChangesPerTick());assertEquals(1,s.gravityIntervalTicks());
        assertEquals(2000000,s.workNanos());assertEquals(20,s.blockReach());assertEquals(3,s.tntPower());
        assertEquals(Integer.MAX_VALUE/20,s.doubleTime().maxSeconds());assertEquals(2,s.doubleTime().maxMultiplier());assertEquals(0,s.doubleTime().stagger());
        c.set("max-diameter",55);assertThrows(IllegalArgumentException.class,()->Settings.read(c));
    }
    @Test void snapshotDoesNotFollowMutableConfigOrExposeMutableCollections() {
        var c=new YamlConfiguration();c.set("double-time.messages.bossbar","old");c.set("double-time.sound.start","test");
        c.set("double-time.excluded-effects",List.of("tnt"));var s=Settings.read(c);
        c.set("double-time.messages.bossbar","new");c.set("double-time.stagger-ticks",0);
        assertEquals("old",s.doubleTime().messages().get("bossbar"));assertEquals("test",s.doubleTime().sounds().get("start"));assertEquals(5,s.doubleTime().stagger());
        assertThrows(UnsupportedOperationException.class,()->s.doubleTime().excluded().add("meteor"));
        assertThrows(UnsupportedOperationException.class,()->s.doubleTime().messages().put("bossbar","other"));
        assertEquals("new",Settings.read(c).doubleTime().messages().get("bossbar"));
    }
    @Test void settingsCommandRefreshesDoubleTimeWithoutResettingItsCountdown() {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            var game=new UmetateGame((UmetatePlugin)f.plugin);
            try {
                f.plugin.getConfig().set("double-time.max-seconds",12);
                assertEquals(600,game.doubleTime().maxSeconds());game.reach(30);
                assertEquals(12,game.doubleTime().maxSeconds());
                var m=new DoubleTimeManager(f.plugin,()->f.world);m.start(10,2);m.tick();
                f.plugin.getConfig().set("double-time.max-seconds",20);m.reloadSettings();
                assertEquals(199,m.getRemainingTicks());assertEquals(20,m.maxSeconds());
            }finally{game.close();}
        }
    }
    @Test void doubleTimeTickLaunchAndHpRefreshDoNotReadConfiguration() {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            var m=new DoubleTimeManager(f.plugin,()->f.world);m.start(10,2);
            clearInvocations(f.plugin);
            for(int i=0;i<30;i++){m.tick();m.launch("tnt",()->{});f.health.refresh();}
            verify(f.plugin,never()).getConfig();
        }
    }
    @Test void tileAndContainerClassificationIsCachedOncePerMaterial() {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            var guard=new TileGuard();
            for(var type:List.of(Material.CHEST,Material.BARREL,Material.HOPPER,Material.OAK_SIGN,Material.SPAWNER,Material.PLAYER_HEAD,Material.WHITE_BED,Material.STONE,Material.AIR)) {
                var material=f.view(type);boolean tile=type!=Material.STONE && type!=Material.AIR;
                boolean container=type==Material.CHEST || type==Material.BARREL || type==Material.HOPPER;
                for(int i=0;i<100;i++){assertEquals(tile,guard.isTile(material));assertEquals(container,guard.isContainer(material));}
                verify(material,times(1)).createBlockData();
            }
        }
    }
    @Test void newBoxUsesStandardSizeRulesButSavedLegacyDimensionsRemainReadable() {
        assertEquals(101124,new Box(0,0,0,27,36).capacity());
        for(int[] size:new int[][]{{28,36},{2,2},{2,37},{Integer.MAX_VALUE,3}})
            assertThrows(IllegalArgumentException.class,()->new Box(0,0,0,size[0],size[1]));
        assertEquals(392040,Box.restored(0,0,0,50,40).capacity());
        assertEquals(9,Box.restored(0,0,0,2,1).capacity());
        assertThrows(IllegalArgumentException.class,()->Box.restored(0,0,0,51,40));
        assertEquals(new Box(1,2,3,27,36),Box.restored(1,2,3,27,36));
    }
}
