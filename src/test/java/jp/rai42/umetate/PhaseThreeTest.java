package jp.rai42.umetate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PhaseThreeTest {
    @TempDir Path directory;

    @Test void boxIndexCoordinatesMatchCellsAndIteratorsCoverExactRanges() {
        Box box=Box.restored(10,5,-7,4,6);
        for(int i=0;i<box.capacity();i++){var c=box.cell(i);assertEquals(c,new Box.Cell(box.cellX(i),box.cellY(i),box.cellZ(i)));assertEquals(i,box.index(c.x(),c.y(),c.z()));}
        Set<Box.Cell> shell=new HashSet<>(),all=new HashSet<>();int n=0;
        for(var c:box.shellCells()){assertTrue(box.shell(c.x(),c.y(),c.z()));shell.add(c);n++;}
        assertEquals(n,shell.size());assertEquals(box.volume()-box.capacity(),shell.size());
        for(var c:box.envelopeCells())all.add(c);assertEquals(box.volume(),all.size());
        int layer=box.width()*box.width(),count=0;
        for(var c:box.interior(0,2*layer)){assertTrue(box.contains(c.x(),c.y(),c.z()));assertTrue(c.y()<=box.y()+2);count++;}
        assertEquals(2*layer,count);
    }
    @Test void lidContainmentIsArithmeticAndMatchesItsSnapshot() {
        Box box=Box.restored(3,10,-2,3,4);Map<Box.Cell,String> roof=new HashMap<>();
        for(var c:BedrockLid.cells(box))roof.put(c,"x");
        var lid=new BedrockLid<>(box,5,roof);
        for(int x=-3;x<=9;x++)for(int y=13;y<=17;y++)for(int z=-8;z<=4;z++)
            assertEquals(roof.containsKey(new Box.Cell(x,y,z)),lid.contains(x,y,z),x+","+y+","+z);
    }
    @Test void fallCandidatesComeFromOccupancyBitsOnly() {
        var fill=new FillState(27);int layer=9;
        for(int i=0;i<27;i++)fill.set(i,true);
        assertEquals(-1,fill.nextFallCandidate(0,layer));
        fill.set(4,false);assertEquals(13,fill.nextFallCandidate(0,layer));assertEquals(-1,fill.nextFallCandidate(14,layer));
        // A fountain reservation on the free cell blocks the block above from falling into it.
        fill.enqueue(1);assertEquals(4,fill.reserve());assertEquals(-1,fill.nextFallCandidate(0,layer));
    }
    @Test void supportedFullArenaSweepDoesNotReadTheWorld() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            Box box=Box.restored(0,0,0,4,10);var game=game(f,box);
            for(int i=0;i<box.capacity();i++)f.types.put(box.cell(i),Material.DIAMOND_BLOCK);
            invoke(game,"reconcileAll");set(game,"tick",2);
            clearInvocations(f.world);invoke(game,"gravityTick");
            verify(f.world,never()).getBlockAt(anyInt(),anyInt(),anyInt());assertTrue(f.falling.isEmpty());
        }
    }
    @Test void staleCandidateIsCorrectedInsteadOfRetriedForever() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            Box box=Box.restored(0,0,0,2,3);var game=game(f,box);
            for(int i=0;i<box.capacity();i++)f.types.put(box.cell(i),Material.DIAMOND_BLOCK);
            var fill=(FillState)get(game,"fill");invoke(game,"reconcileAll");
            fill.set(0,false); // cache says empty, world is solid
            set(game,"tick",2);invoke(game,"gravityTick");
            assertTrue(f.falling.isEmpty());assertTrue(fill.occupied(0));assertEquals(-1,fill.nextFallCandidate(0,box.width()*box.width()));
        }
    }
    @Test void frameRestoreReconcilesAcrossTicksWithinBudgetAndStaysBusy() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            Box box=Box.restored(0,0,0,4,3);var game=game(f,box);
            for(int i=0;i<box.capacity();i++)f.types.put(box.cell(i),i%2==0?Material.DIAMOND_BLOCK:Material.AIR);
            f.plugin.getConfig().set("performance.instant-box-changes",false);
            f.plugin.getConfig().set("performance.max-work-ms-per-tick",.5);game.reloadSettings();
            for(int i=0;i<box.capacity();i++){var c=box.cell(i);var b=f.block(c.x(),c.y(),c.z());
                when(b.getType()).thenAnswer(call->{Thread.sleep(1);return f.view(f.types.get(c));});}
            game.frameRestored();assertTrue(game.busy());
            invoke(game,"tick");assertTrue(game.busy(),"全セルが1tickで処理された");
            int ticks=1;for(;ticks<1000 && game.busy();ticks++)invoke(game,"tick");
            assertFalse(game.busy());assertTrue(ticks>5);
            assertEquals((box.capacity()+1)/2,((FillState)get(game,"fill")).filled());
        }
    }
    @Test void reconcileScanDefaultIsASafetyNet() {
        assertEquals(64,Settings.read(new YamlConfiguration()).performanceReconcilePerTick());
        var c=new YamlConfiguration();c.set("performance.reconcile-per-tick",500);
        assertEquals(500,Settings.read(c).performanceReconcilePerTick());
    }
    @Test void wallChangesOnlyTheShell() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            Box box=Box.restored(0,0,0,3,4);var game=game(f,box);
            for(int i=0;i<box.capacity();i++)f.types.put(box.cell(i),Material.DIAMOND_BLOCK);
            for(var c:box.shellCells())f.types.put(c,Material.BEDROCK);
            invoke(game,"reconcileAll");
            game.wall(Material.GLASS);drain(game);
            for(var c:box.shellCells())assertEquals(Material.GLASS,f.types.get(c));
            for(int i=0;i<box.capacity();i++)assertEquals(Material.DIAMOND_BLOCK,f.types.get(box.cell(i)));
            game.close();
        }
    }
    @Test void instantModeFinishesBoxWideEditsInOneTickAfterTheCheckpoint() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            Box box=Box.restored(0,0,0,10,20);var game=game(f,box);f.plugin.getConfig().set("performance.instant-max-ms-per-tick",1000);game.reloadSettings();
            for(int i=0;i<box.capacity();i++)f.types.put(box.cell(i),Material.DIAMOND_BLOCK);
            for(var c:box.shellCells())f.types.put(c,Material.BEDROCK);
            invoke(game,"reconcileAll");
            game.clear();
            var structure=UmetateGame.class.getDeclaredField("structure");structure.setAccessible(true);
            // After the checkpoint is written, no tick may leave the edit half-applied.
            int partialTicks=0;
            for(int i=0;i<5000 && game.busy();i++){invoke(game,"tick");if(structure.get(game)!=null && get(game,"saving")==null)partialTicks++;Thread.sleep(1);}
            assertFalse(game.busy());assertEquals(0,partialTicks,"構造変更が複数tickに分かれた");
            for(int i=0;i<box.capacity();i++)assertEquals(Material.AIR,f.types.get(box.cell(i)));
            assertEquals(0,((FillState)get(game,"fill")).filled());
            game.close();
        }
    }
    @Test void instantFillCompletesInOneTickAndQueuesNoGravityChecks() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            Box box=Box.restored(0,0,0,10,20);var game=game(f,box);f.plugin.getConfig().set("performance.instant-max-ms-per-tick",1000);game.reloadSettings();
            for(int i=0;i<box.capacity();i++)f.types.put(box.cell(i),Material.AIR);
            invoke(game,"reconcileAll");game.fillAll(true);invoke(game,"directTick");
            var fill=(FillState)get(game,"fill");assertTrue(fill.full());assertEquals(0,fill.pending());
            // Bottom-up placement is always supported: nothing should wait for a fall check.
            assertTrue(((java.util.ArrayDeque<?>)get(game,"gravityQueue")).isEmpty());
        }
    }
    @Test void reserveResumesAfterTheLastReservationAndRewindsOnGaps() {
        var fill=new FillState(10);fill.enqueue(10,true);
        for(int i=0;i<5;i++)assertEquals(i,fill.reserve(true));
        fill.finish(2,false);assertEquals(2,fill.reserve(true));
        fill.finish(0,true);fill.set(0,false);fill.enqueue(1,true);assertEquals(0,fill.reserve(true));
        assertEquals(5,fill.reserve(true));
    }
    @Test void instantFillSkipsABlockEntityInsteadOfRetryingItForever() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            Box box=Box.restored(0,0,0,3,3);var game=game(f,box);f.plugin.getConfig().set("performance.instant-max-ms-per-tick",1000);game.reloadSettings();
            for(int i=0;i<box.capacity();i++)f.types.put(box.cell(i),Material.AIR);
            // A sign is a block entity that is not solid: never fillable and never counted as filled.
            f.types.put(box.cell(0),Material.OAK_SIGN);when(f.view(Material.OAK_SIGN).isSolid()).thenReturn(false);
            invoke(game,"reconcileAll");game.fillAll(true);
            assertTimeout(java.time.Duration.ofSeconds(5),()->invoke(game,"directTick"));
            var fill=(FillState)get(game,"fill");assertEquals(box.capacity()-1,fill.filled());
            assertEquals(Material.OAK_SIGN,f.types.get(box.cell(0)));assertTrue(fill.pending()>0);
        }
    }
    @Test void fastModeSplitsAHeavyFillAcrossTicksAndStillFinishes() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            Box box=Box.restored(0,0,0,4,3);var game=game(f,box);
            for(int i=0;i<box.capacity();i++)f.types.put(box.cell(i),Material.AIR);
            invoke(game,"reconcileAll");
            f.plugin.getConfig().set("performance.instant-max-ms-per-tick",.5);game.reloadSettings();
            for(int i=0;i<box.capacity();i++){var c=box.cell(i);var b=f.block(c.x(),c.y(),c.z());
                doAnswer(call->{Thread.sleep(1);f.types.put(c,((org.bukkit.block.data.BlockData)call.getArgument(0)).getMaterial());return null;}).when(b).setBlockData(any(org.bukkit.block.data.BlockData.class),anyBoolean());}
            game.fillAll(true);var fill=(FillState)get(game,"fill");
            invoke(game,"directTick");assertFalse(fill.full(),"1tickに詰め込まれた");
            int ticks=1;for(;ticks<1000 && !fill.full();ticks++)invoke(game,"directTick");
            assertTrue(fill.full());assertTrue(ticks>3);assertEquals(0,fill.pending());
        }
    }
    @Test void messagesStayInTheServerLog() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            var game=game(f,Box.restored(0,0,0,2,3));
            var a=mock(org.bukkit.entity.Player.class);var b=mock(org.bukkit.entity.Player.class);
            f.bukkit.when(org.bukkit.Bukkit::getOnlinePlayers).thenReturn((java.util.Collection)java.util.List.of(a,b));
            var tell=UmetateGame.class.getDeclaredMethod("tell",String.class);tell.setAccessible(true);tell.invoke(game,"箱の中を空にしました。");
            verify(a,never()).sendMessage(any(net.kyori.adventure.text.Component.class));verify(b,never()).sendMessage(any(net.kyori.adventure.text.Component.class));
        }
    }
    private static void drain(UmetateGame game)throws Exception {
        for(int i=0;i<5000 && game.busy();i++){invoke(game,"tick");Thread.sleep(1);}
        assertFalse(game.busy());
    }
    private static UmetateGame game(FrameHealthTest.Fixture f,Box box)throws Exception {
        UmetateGame game=new UmetateGame((UmetatePlugin)f.plugin);
        set(game,"world",f.world);set(game,"box",box);set(game,"fill",new FillState(box.capacity()));
        return game;
    }
    private static void set(Object target,String key,Object value)throws Exception {
        Field field=target.getClass().getDeclaredField(key);field.setAccessible(true);field.set(target,value);
    }
    private static Object get(Object target,String key)throws Exception {
        Field field=target.getClass().getDeclaredField(key);field.setAccessible(true);return field.get(target);
    }
    private static void invoke(Object target,String name)throws Exception {
        Method method=target.getClass().getDeclaredMethod(name);method.setAccessible(true);method.invoke(target);
    }
}
