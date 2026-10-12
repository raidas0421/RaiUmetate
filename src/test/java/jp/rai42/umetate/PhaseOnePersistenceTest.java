package jp.rai42.umetate;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Regressions established against the pre-optimization build. */
class PhaseOnePersistenceTest {
    @TempDir Path directory;
    @Test void ordinaryStateSaveMustNotReserializeRestorationBlocks() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            var game=game(f);var data=f.data(Material.AIR);
            @SuppressWarnings("unchecked") var originals=(Map<Box.Cell,BlockData>)get(game,"originals");
            for(int i=0;i<100;i++)originals.put(new Box.Cell(i,0,0),data);
            clearInvocations(data);
            invoke(game,"save",new Class<?>[]{boolean.class},false);
            verify(data,never()).getAsString();game.close();
        }
    }
    @Test void ordinarySaveMustNotPauseTheWinClock() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            var game=game(f);game.start();var fill=(FillState)get(game,"fill");
            for(int i=0;i<fill.capacity();i++)fill.set(i,true);
            var clock=(WinClock)get(game,"clock");clock.tick(true);
            int before=clock.secondsLeft();
            invoke(game,"startSave",new Class<?>[]{boolean.class},false);
            for(int i=0;i<20;i++)invoke(game,"tick",new Class<?>[]{});
            assertTrue(clock.secondsLeft()<before,"保存中も勝利カウントが進む必要があります");
            game.close();
        }
    }
    @Test void repeatedHpChangesMustBeDebouncedInsteadOfWritingEachHit() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            Path path=directory.resolve("frame-health-state.yml");String before=Files.exists(path)?Files.readString(path):"";
            for(int i=0;i<5;i++)f.health.command(f.console,new String[]{"hp","heal","-1"});
            assertEquals(before,Files.exists(path)?Files.readString(path):"","40tickの保存間隔より前に書き込まれています");
        }
    }
    @Test void oldYamlMigratesAndReusesParsedBlockDataAndCanRestoreInterruptedArena()throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            var yaml=new org.bukkit.configuration.file.YamlConfiguration();
            yaml.set("world",f.world.getUID().toString());yaml.set("x",0);yaml.set("y",0);yaml.set("z",0);yaml.set("radius",2);yaml.set("height",3);yaml.set("transitioning",true);
            for(int y=0;y<=3;y++)for(int z=-2;z<=2;z++)for(int x=-2;x<=2;x++)yaml.set("originals."+x+","+y+","+z,"minecraft:air");
            yaml.save(directory.resolve("arena.yml").toFile());
            when(f.world.getMinHeight()).thenReturn(-64);when(f.world.getMaxHeight()).thenReturn(320);
            var border=mock(org.bukkit.WorldBorder.class);when(f.world.getWorldBorder()).thenReturn(border);when(border.isInside(org.mockito.ArgumentMatchers.any(org.bukkit.Location.class))).thenReturn(true);
            f.bukkit.when(()->org.bukkit.Bukkit.getWorld(f.world.getUID())).thenReturn(f.world);
            var game=new UmetateGame((UmetatePlugin)f.plugin);game.start();
            f.bukkit.verify(()->org.bukkit.Bukkit.createBlockData("minecraft:air"),times(1));
            for(int i=0;i<1000 && game.busy();i++){invoke(game,"tick",new Class<?>[]{});Thread.sleep(1);}
            assertFalse(game.exists());game.close();
            assertTrue(Files.exists(directory.resolve("arena.yml.bak")));assertFalse(Files.exists(directory.resolve("arena.yml")));
            for(int y=0;y<=3;y++)for(int z=-2;z<=2;z++)for(int x=-2;x<=2;x++)assertEquals(Material.AIR,f.types.get(new Box.Cell(x,y,z)));
        }
    }
    @Test void boundaryMaterialChecksUseStartupCache() {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            clearInvocations(f.plugin);assertEquals(30,f.health.damageAt(f.bounds,f.origin,4));
            verify(f.plugin,never()).getConfig();
        }
    }
    @Test void pendingMetadataWriteDoesNotMakeGameBusyOrStopCountdown()throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            var game=game(f);game.start();
            var store=mock(ArenaStore.class);var pending=new java.util.concurrent.CompletableFuture<Void>();
            when(store.write(org.mockito.ArgumentMatchers.anyMap(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(pending);set(game,"arenaStore",store);
            var fill=(FillState)get(game,"fill");for(int i=0;i<fill.capacity();i++)fill.set(i,true);
            var clock=(WinClock)get(game,"clock");clock.tick(true);int before=clock.secondsLeft();
            invoke(game,"startSave",new Class<?>[]{boolean.class},false);assertFalse(game.busy());
            for(int i=0;i<20;i++)invoke(game,"tick",new Class<?>[]{});
            assertFalse(pending.isDone());assertTrue(clock.secondsLeft()<before);pending.complete(null);game.close();
        }
    }
    private UmetateGame game(FrameHealthTest.Fixture f)throws Exception {
        var game=new UmetateGame((UmetatePlugin)f.plugin);var box=Box.restored(0,0,0,2,3);
        set(game,"world",f.world);set(game,"box",box);set(game,"fill",new FillState(box.capacity()));return game;
    }
    private static Object get(Object target,String name)throws Exception {var field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
    private static void set(Object target,String name,Object value)throws Exception {var field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);}
    private static Object invoke(Object target,String name,Class<?>[] types,Object...args)throws Exception {var method=target.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(target,args);}
}
