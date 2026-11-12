package jp.rai42.umetate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.boss.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GameIntegrationTest {
    @TempDir Path directory;

    @Test void distantAndOtherWorldChunksDoNotLoadEntities() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            UmetateGame game=game(f,Box.restored(0,0,0,2,3));
            var chunk=mock(org.bukkit.Chunk.class);
            when(chunk.getWorld()).thenReturn(f.world);when(chunk.getX()).thenReturn(100);
            when(chunk.getEntities()).thenReturn(new org.bukkit.entity.Entity[0]);
            game.chunkLoad(new org.bukkit.event.world.ChunkLoadEvent(chunk,false));
            verify(chunk,never()).getEntities();
            when(chunk.getX()).thenReturn(0);when(chunk.getWorld()).thenReturn(mock(org.bukkit.World.class));
            game.chunkLoad(new org.bukkit.event.world.ChunkLoadEvent(chunk,false));
            verify(chunk,never()).getEntities();game.close();
        }
    }
    @Test void nearbyChunkRetainsLiveEntitiesAndRemovesOnlyStaleOnes() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            var game=game(f,new Box(0,0,0,2,3));var chunk=mock(org.bukkit.Chunk.class);
            var stale=mock(org.bukkit.entity.BlockDisplay.class);var ordinary=mock(org.bukkit.entity.Entity.class);
            when(stale.getScoreboardTags()).thenReturn(java.util.Set.of("rai_umetate_temporary"));
            when(chunk.getWorld()).thenReturn(f.world);when(chunk.getEntities()).thenReturn(new org.bukkit.entity.Entity[]{stale,ordinary});
            game.chunkLoad(new org.bukkit.event.world.ChunkLoadEvent(chunk,false));
            verify(stale).remove();verify(ordinary,never()).remove();verify(chunk,times(1)).getEntities();game.close();
        }
    }
    @Test void directFillAndGravityDoNotReadConfigOrCopyWorldBlockStates() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            var box=new Box(0,0,0,2,3);var game=game(f,box);
            for(int i=0;i<box.capacity();i++)f.types.put(box.cell(i),Material.AIR);
            game.add(10,true);clearInvocations(f.plugin);
            invoke(game,"directTick");invoke(game,"gravityTick");
            verify(f.plugin,never()).getConfig();for(var block:f.blocks.values())verify(block,never()).getState();
            game.close();
        }
    }
    @Test void gameCreatesOnlyFrameHpBar() throws Exception {
        try (var f = new FrameHealthTest.Fixture(directory,10000)) {
            BossBar progress=mock(BossBar.class), hp=mock(BossBar.class);
            f.bukkit.when(() -> Bukkit.createBossBar(eq("埋め立て率"),any(BarColor.class),any(BarStyle.class))).thenReturn(progress);
            f.bukkit.when(() -> Bukkit.createBossBar(eq("枠HP"),any(BarColor.class),any(BarStyle.class))).thenReturn(hp);
            UmetateGame game=game(f,Box.restored(0,0,0,2,1));
            game.start();
            FillState fill=(FillState)get(game,"fill"); fill.set(0,true); fill.set(1,true); fill.set(2,true);
            invoke(game,"updateBar");
            verifyNoInteractions(progress);
            f.bukkit.verify(() -> Bukkit.createBossBar(eq("埋め立て率"),any(BarColor.class),any(BarStyle.class)),never());
            verify(hp).setTitle("枠HP 10,000 / 10,000 (100%) | 直径3(最小) 高さ1 | 待機中");
            game.close(); verifyNoInteractions(progress); verify(hp).removeAll();
        }
    }
    @Test void normalizationAppliesTopBandsToExistingWorldBlocks() throws Exception {
        try (var f = new FrameHealthTest.Fixture(directory,10000)) {
            Box box=Box.restored(0,0,0,2,21); UmetateGame game=game(f,box);
            for(int i=0;i<box.capacity();i++) f.types.put(box.cell(i),Material.QUARTZ_BLOCK);
            invoke(game,"normalizeLayers");
            assertEquals(Material.DIAMOND_BLOCK,f.types.get(new Box.Cell(0,21,0)));
            assertEquals(Material.GOLD_BLOCK,f.types.get(new Box.Cell(0,18,0)));
            assertEquals(Material.IRON_BLOCK,f.types.get(new Box.Cell(0,15,0)));
            assertEquals(Material.EMERALD_BLOCK,f.types.get(new Box.Cell(0,12,0)));
            assertEquals(Material.AMETHYST_BLOCK,f.types.get(new Box.Cell(0,9,0)));
            assertEquals(Material.COPPER_BLOCK,f.types.get(new Box.Cell(0,6,0)));
            assertEquals(Material.QUARTZ_BLOCK,f.types.get(new Box.Cell(0,3,0)));
            assertEquals(Material.QUARTZ_BLOCK,f.types.get(new Box.Cell(0,1,0)));
        }
    }
    @Test void collapsePreventsNewFillAndResetsWinCountdown() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10)) {
            UmetateGame game=game(f,Box.restored(0,0,0,2,1)); game.start();
            FrameHealth hp=(FrameHealth)get(game,"frameHealth");
            WinClock clock=(WinClock)get(game,"clock"); clock.tick(true);
            hp.customExplosion(f.origin,4);
            assertTrue(game.busy()); assertThrows(IllegalArgumentException.class,()->game.add(1));
            invoke(game,"tick"); assertEquals(-1,clock.secondsLeft());
            game.close();
        }
    }
    @Test void countdownShowsAndSoundsEachSecondOnlyOnce() throws Exception {
        try (var f = new FrameHealthTest.Fixture(directory,10000)) {
            UmetateGame game=game(f,Box.restored(0,0,0,2,1));
            var player=mock(org.bukkit.entity.Player.class);
            when(player.getWorld()).thenReturn(f.world);
            when(player.getLocation()).thenReturn(new org.bukkit.Location(f.world,0,1,0));
            f.bukkit.when(Bukkit::getOnlinePlayers).thenReturn(java.util.List.of(player));
            WinClock clock=(WinClock)get(game,"clock"); clock.duration(3);
            for(int i=0;i<=60;i++) { clock.tick(true); invoke(game,"countdown"); }
            var titles=org.mockito.ArgumentCaptor.forClass(net.kyori.adventure.title.Title.class);
            verify(player,times(3)).showTitle(titles.capture());
            assertEquals(java.util.List.of("3","2","1"),titles.getAllValues().stream()
                    .map(t -> ((net.kyori.adventure.text.TextComponent)t.title()).content()).toList());
            verify(player,times(3)).playSound(any(org.bukkit.Location.class),eq("minecraft:block.note_block.pling"),eq(1f),eq(1.2f));
            verify(player,never()).sendActionBar(any(net.kyori.adventure.text.Component.class));
            clock.tick(false); invoke(game,"countdown"); clock.tick(true); invoke(game,"countdown");
            verify(player,times(4)).showTitle(any(net.kyori.adventure.title.Title.class));
            game.pause(); verify(player).clearTitle();f.bukkit.when(Bukkit::getOnlinePlayers).thenReturn(java.util.List.of());game.close();
        }
    }
    @Test void liveTimerChangesStayOutOfConfigAndReachRaiLive() throws Exception {
        try (var f = new FrameHealthTest.Fixture(directory,10000)) {
            UmetateGame game=game(f,Box.restored(0,0,0,2,1));
            WinClock clock=(WinClock)get(game,"clock");
            int saved=((Settings)get(game,"settings")).winSeconds();
            clearInvocations(f.plugin);
            assertEquals(20,game.timer(20)); assertEquals(20,clock.seconds());
            assertEquals(10,game.timerAdd(-10)); assertEquals(1,game.timerAdd(-100)); assertEquals(3600,game.timerAdd(Integer.MAX_VALUE));
            assertEquals(saved,game.timerReset()); assertEquals(saved,clock.seconds());
            verify(f.plugin,never()).saveConfig(); assertFalse(f.plugin.getConfig().contains("win-seconds"));
            game.timer(5); assertEquals(5,game.raiLiveState().winSeconds());
            clock.tick(true); assertEquals(5,game.raiLiveState().countdown());
            game.close();
        }
    }
    @Test void winSendsOneEventClearsInteriorAndRearmsNextRound() throws Exception {
        try (var f = new FrameHealthTest.Fixture(directory,10000)) {
            Box box=Box.restored(0,0,0,2,1); UmetateGame game=game(f,box);
            f.bukkit.when(() -> Bukkit.dispatchCommand(f.console,"rwin win 1")).thenReturn(true);
            WinClock clock=(WinClock)get(game,"clock"); clock.duration(1);
            for(int i=0;i<box.capacity();i++) f.types.put(box.cell(i),Material.DIAMOND_BLOCK);
            f.types.put(new Box.Cell(2,1,0),Material.BEDROCK);
            for(int i=0;i<60;i++) invoke(game,"tick");drainChanges(game);
            verify((UmetatePlugin)f.plugin,times(1)).requestWin(true,WinService.Reason.FILL_COMPLETE);
            assertEquals(1,get(game,"wins"));
            assertFalse(clock.won()); assertEquals(-1,clock.secondsLeft());
            assertEquals(0,((FillState)get(game,"fill")).filled());
            for(int i=0;i<box.capacity();i++) assertEquals(Material.AIR,f.types.get(box.cell(i)));
            assertEquals(Material.BEDROCK,f.types.get(new Box.Cell(2,1,0)));
            for(int i=0;i<box.capacity();i++) f.types.put(box.cell(i),Material.DIAMOND_BLOCK);
            for(int i=0;i<60;i++) invoke(game,"tick");drainChanges(game);
            verify((UmetatePlugin)f.plugin,times(2)).requestWin(true,WinService.Reason.FILL_COMPLETE);
            assertEquals(2,get(game,"wins"));
        }
    }
    @Test void unavailableWinSinkDoesNotBlockGameCleanup() throws Exception {
        try (var f = new FrameHealthTest.Fixture(directory,10000)) {
            Box box=Box.restored(0,0,0,2,1); UmetateGame game=game(f,box);
            var service=mock(WinService.class);doThrow(new IllegalArgumentException("送信待ちが上限です。")).when(service).win(1,WinService.Reason.FILL_COMPLETE);
            set(f.plugin,"wins",service);doCallRealMethod().when((UmetatePlugin)f.plugin).requestWin(anyBoolean(),any());
            ((WinClock)get(game,"clock")).duration(1);
            for(int i=0;i<box.capacity();i++) f.types.put(box.cell(i),Material.DIAMOND_BLOCK);
            for(int i=0;i<60;i++) invoke(game,"tick");drainChanges(game);
            verify((UmetatePlugin)f.plugin,times(1)).requestWin(true,WinService.Reason.FILL_COMPLETE);
            assertEquals(0,((FillState)get(game,"fill")).filled());
            assertFalse(game.busy());
        }
    }
    @Test void gravityMovesBlockInstantlyAndNormalizesOnLanding() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            Box box=Box.restored(0,0,0,2,4); UmetateGame game=game(f,box); empty(f,box);
            f.types.put(new Box.Cell(0,4,0),Material.DIAMOND_BLOCK);
            invoke(game,"reconcileAll"); invoke(game,"gravityTick");
            assertTrue(f.falling.isEmpty());
            assertEquals(Material.AIR,f.types.get(new Box.Cell(0,4,0)));
            assertEquals(Material.GOLD_BLOCK,f.types.get(new Box.Cell(0,1,0)));
            assertEquals(1,((FillState)get(game,"fill")).filled());
        }
    }
    @Test void gravityCompactsWholeStackAcrossGapsInOneStepWithoutAutoreplace() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            Box box=Box.restored(0,0,0,2,6); UmetateGame game=game(f,box); empty(f,box);
            game.toggle("autoreplace");when(f.world.getMaxHeight()).thenReturn(320);
            f.types.put(new Box.Cell(0,3,0),Material.DIAMOND_BLOCK);f.types.put(new Box.Cell(0,4,0),Material.EMERALD_BLOCK);f.types.put(new Box.Cell(0,6,0),Material.IRON_BLOCK);
            invoke(game,"reconcileAll"); invoke(game,"gravityTick");
            assertEquals(Material.DIAMOND_BLOCK,f.types.get(new Box.Cell(0,1,0)));
            assertEquals(Material.EMERALD_BLOCK,f.types.get(new Box.Cell(0,2,0)));
            assertEquals(Material.IRON_BLOCK,f.types.get(new Box.Cell(0,3,0)));
            for(int y=4;y<=6;y++)assertEquals(Material.AIR,f.types.get(new Box.Cell(0,y,0)));
            assertEquals(3,((FillState)get(game,"fill")).filled());assertEquals(6,game.spawnY());
        }
    }
    @Test void gravityStopsAtChestsAndKeepsTntAsTnt() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            Box box=Box.restored(0,0,0,2,5); UmetateGame game=game(f,box); empty(f,box);
            f.types.put(new Box.Cell(0,2,0),Material.TNT);f.types.put(new Box.Cell(0,3,0),Material.CHEST);f.types.put(new Box.Cell(0,4,0),Material.DIAMOND_BLOCK);
            invoke(game,"reconcileAll"); gravitySettle(game);
            assertEquals(Material.TNT,f.types.get(new Box.Cell(0,1,0)));
            assertEquals(Material.AIR,f.types.get(new Box.Cell(0,2,0)));
            assertEquals(Material.CHEST,f.types.get(new Box.Cell(0,3,0)));
            assertEquals(Material.DIAMOND_BLOCK,f.types.get(new Box.Cell(0,4,0)));
        }
    }
    @Test void gravityNeverLandsOnAStaleOccupiedCell() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            Box box=Box.restored(0,0,0,2,4); UmetateGame game=game(f,box); empty(f,box);
            game.toggle("autoreplace");
            f.types.put(new Box.Cell(0,4,0),Material.DIAMOND_BLOCK);invoke(game,"reconcileAll");
            // The cache still says this cell is free when the column drops.
            f.types.put(new Box.Cell(0,1,0),Material.EMERALD_BLOCK);
            invoke(game,"gravityTick");
            assertEquals(Material.EMERALD_BLOCK,f.types.get(new Box.Cell(0,1,0)));
            assertEquals(Material.DIAMOND_BLOCK,f.types.get(new Box.Cell(0,2,0)));
            assertEquals(2,((FillState)get(game,"fill")).filled());
        }
    }
    @Test void gravityWaitsForFountainSupportAndPreservesContainers() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            Box box=Box.restored(0,0,0,2,2); UmetateGame game=game(f,box); empty(f,box);
            f.types.put(box.cell(9),Material.DIAMOND_BLOCK); f.types.put(box.cell(10),Material.CHEST);
            var cell=box.cell(10); var chest=f.block(cell.x(),cell.y(),cell.z()); f.types.put(new Box.Cell(chest.getX(),chest.getY(),chest.getZ()),Material.CHEST);
            FillState fill=(FillState)get(game,"fill"); invoke(game,"reconcileAll");fill.enqueue(1);assertEquals(0,fill.reserve());
            invoke(game,"gravityTick"); assertEquals(Material.DIAMOND_BLOCK,f.types.get(box.cell(9)));
            fill.cancelPending(); gravitySettle(game); assertEquals(Material.AIR,f.types.get(box.cell(9)));
            assertNotEquals(Material.AIR,f.types.get(box.cell(0)));
            assertEquals(Material.CHEST,f.types.get(box.cell(10)));assertTrue(f.falling.isEmpty());
        }
    }
    @Test void gravityRestoresAirborneBlocksBeforeShutdownOrResizeCleanup() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            Box box=Box.restored(0,0,0,2,3); UmetateGame game=game(f,box);empty(f,box);
            var first=recovered(game,f,box.cell(18),Material.DIAMOND_BLOCK);var second=recovered(game,f,box.cell(19),Material.DIAMOND_BLOCK);
            invoke(game,"cleanupEffects");
            assertEquals(Material.DIAMOND_BLOCK,f.types.get(box.cell(18)));
            assertEquals(Material.DIAMOND_BLOCK,f.types.get(box.cell(19)));
            assertEquals(2,((FillState)get(game,"fill")).filled());
            verify(first).remove();verify(second).remove();
        }
    }
    @Test void nativeGravityLandingNeverOverwritesAnOccupiedTarget() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            Box box=Box.restored(0,0,0,2,2); UmetateGame game=game(f,box);empty(f,box);
            var entity=recovered(game,f,box.cell(9),Material.DIAMOND_BLOCK);var target=f.block(-1,1,-1);f.types.put(box.cell(0),Material.CHEST);
            var landing=mock(org.bukkit.event.entity.EntityChangeBlockEvent.class);when(landing.getEntity()).thenReturn(entity);when(landing.getBlock()).thenReturn(target);
            game.entityChange(landing);
            assertEquals(Material.CHEST,f.types.get(box.cell(0)));assertEquals(Material.DIAMOND_BLOCK,f.types.get(box.cell(9)));
        }
    }
    @Test void gravityIsBoundedAndCanBeDisabled() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            Box box=Box.restored(0,0,0,2,3);UmetateGame game=game(f,box);empty(f,box);
            for(int i=18;i<27;i++)f.types.put(box.cell(i),Material.DIAMOND_BLOCK);invoke(game,"reconcileAll");
            f.plugin.getConfig().set("gravity.enabled",false);game.reloadSettings();invoke(game,"gravityTick");assertEquals(9,floating(f,box,18,27));
            f.plugin.getConfig().set("gravity.enabled",true);f.plugin.getConfig().set("gravity.blocks-per-step",2);game.reloadSettings();
            int left=9;for(int i=0;i<300 && left>0;i++){invoke(game,"gravityTick");int now=floating(f,box,18,27);assertTrue(left-now<=2);left=now;}assertEquals(0,left);
            assertTrue(f.falling.isEmpty());
        }
    }
    @Test void fillAcceptsExcessLayersQuietlyAndAllDoesNotDuplicateQueue() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            Box box=Box.restored(0,0,0,2,2);UmetateGame game=game(f,box);empty(f,box);
            var player=mock(org.bukkit.entity.Player.class);when(player.hasPermission("raiumetate.admin")).thenReturn(true);
            var command=new UmetateCommand(game);command.onCommand(player,null,"bedrock",new String[]{"fill","100"});
            FillState fill=(FillState)get(game,"fill");assertEquals(900,fill.pending());
            command.onCommand(player,null,"bedrock",new String[]{"fill"});assertEquals(900,fill.pending());
            verify(player,never()).sendMessage(any(net.kyori.adventure.text.Component.class));
        }
    }
    @Test void resizingCompletesWithoutTeleportingMainPlayer() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            Box box=Box.restored(0,0,0,2,2);UmetateGame game=spy(game(f,box));empty(f,box);
            when(f.world.getMinHeight()).thenReturn(-64);when(f.world.getMaxHeight()).thenReturn(320);
            var border=mock(org.bukkit.WorldBorder.class);when(f.world.getWorldBorder()).thenReturn(border);
            when(border.isInside(any(org.bukkit.Location.class))).thenReturn(true);
            var chunk=mock(org.bukkit.Chunk.class);when(f.world.getChunkAt(anyInt(),anyInt())).thenReturn(chunk);
            game.start(); game.resize(3,3);assertTrue(game.busy());drainChanges(game);
            assertEquals(Box.restored(0,0,0,3,3),game.box());verify(game,never()).teleportMain();game.close();
        }
    }
    @Test void savedNativeFallingEntityIsRecoveredAndSettled() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            Box box=Box.restored(0,0,0,2,3);UmetateGame game=game(f,box);empty(f,box);
            var entity=mock(org.bukkit.entity.FallingBlock.class);
            when(entity.getWorld()).thenReturn(f.world);when(entity.getSourceLoc()).thenReturn(new org.bukkit.Location(f.world,0,3,0));
            var data=f.data(Material.DIAMOND_BLOCK);when(entity.getBlockData()).thenReturn(data);
            when(entity.getScoreboardTags()).thenReturn(java.util.Set.of("rai_umetate_gravity"));
            var event=mock(org.bukkit.event.entity.EntityChangeBlockEvent.class);
            when(event.getEntity()).thenReturn(entity);var target=f.block(0,1,0);when(event.getBlock()).thenReturn(target);
            game.entityChange(event);verify(event).setCancelled(true);verify(entity).remove();
            assertEquals(Material.DIAMOND_BLOCK,f.types.get(new Box.Cell(0,1,0)));
        }
    }
    @Test void doubleTimeUsesRealTntEntryWithoutRecursionAndRespectsCapacity() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            var game=spy(game(f,Box.restored(0,0,0,2,3)));var display=mock(org.bukkit.entity.TNTPrimed.class);
            doReturn(display).when(game).primed(any(org.bukkit.Location.class),anyInt());
            game.doubleTime().start(30,3);game.normalTnt(5,"太郎");
            assertEquals(5,((java.util.List<?>)get(game,"bombs")).size());
            f.plugin.getConfig().set("effects.max-active",12);game.reloadSettings();
            for(int i=0;i<10;i++)game.doubleTime().tick();
            assertEquals(12,((java.util.List<?>)get(game,"bombs")).size());
            verify(display,times(12)).customName(net.kyori.adventure.text.Component.text("太郎",net.kyori.adventure.text.format.NamedTextColor.GOLD));
            assertThrows(IllegalArgumentException.class,()->game.normalTnt(1,""));
            invoke(game,"cleanupEffects");assertTrue(game.doubleTime().isActive());
            game.randomTnt();for(int i=0;i<20;i++)game.doubleTime().tick();
            assertEquals(3,((java.util.List<?>)get(game,"bombs")).size());
        }
    }
    @Test void doubleTimeContinuesWhileArenaIsBusyAndDeleteStopsIt() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            var game=game(f,Box.restored(0,0,0,2,3));game.doubleTime().start(30,2);
            set(game,"ioFailed",true);invoke(game,"tick");assertEquals(599,game.doubleTime().getRemainingTicks());
            set(game,"ioFailed",false);game.delete();assertFalse(game.doubleTime().isActive());
            var events=org.mockito.ArgumentCaptor.forClass(org.bukkit.event.Event.class);
            verify(Bukkit.getPluginManager(),atLeastOnce()).callEvent(events.capture());
            assertEquals(DoubleTimeManager.Reason.ARENA_DELETED,((DoubleTimeEndEvent)events.getValue()).getReason());
        }
    }
    @Test void countedTntSpawnsEveryBombWithNicknameAndNoChatSuccess() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            Box box=Box.restored(0,0,0,2,2);UmetateGame game=spy(game(f,box));empty(f,box);
            var display=mock(org.bukkit.entity.TNTPrimed.class);
            doReturn(display).when(game).primed(any(org.bukkit.Location.class),anyInt());
            var player=mock(org.bukkit.entity.Player.class);when(player.hasPermission("raiumetate.admin")).thenReturn(true);
            new UmetateCommand(game).onCommand(player,null,"bedrock",new String[]{"tnt","5","配信","太郎"});
            // Like the original plugin: real lit TNT whose vanilla fuse never wins over the 30-tick schedule.
            verify(game,times(5)).primed(any(org.bukkit.Location.class),eq(70));
            assertEquals(5,((java.util.List<?>)get(game,"bombs")).size());
            for(Object bomb:(java.util.List<?>)get(game,"bombs")) {
                assertEquals(30,get(bomb,"duration")); assertEquals(0,get(bomb,"age"));
            }
            verify(display,times(5)).customName(net.kyori.adventure.text.Component.text("配信 太郎",net.kyori.adventure.text.format.NamedTextColor.GOLD));
            verify(player,never()).sendMessage(any(net.kyori.adventure.text.Component.class));
        }
    }
    @Test void tntSupportsLegacyAndCountOnlyFormsAndRejectsInvalidCounts() {
        UmetateGame game=mock(UmetateGame.class);var sender=mock(org.bukkit.entity.Player.class);
        when(sender.hasPermission("raiumetate.admin")).thenReturn(true);var command=new UmetateCommand(game);
        command.onCommand(sender,null,"bedrock",new String[]{"tnt"});verify(game).normalTnt(1,"");
        command.onCommand(sender,null,"bedrock",new String[]{"tnt","太郎"});verify(game).normalTnt(1,"太郎");
        command.onCommand(sender,null,"bedrock",new String[]{"tnt","10"});verify(game).normalTnt(10,"");
        command.onCommand(sender,null,"bedrock",new String[]{"tnt","1","123"});verify(game).normalTnt(1,"123");
        clearInvocations(game,sender);
        for(String invalid:new String[]{"0","-1","101","999999999999999999","1.5"})
            command.onCommand(sender,null,"bedrock",new String[]{"tnt",invalid,"太郎"});
        verify(game,never()).normalTnt(anyInt(),anyString());
        verify(sender,times(5)).sendMessage(any(net.kyori.adventure.text.Component.class));
    }
    @Test void fullBoxEndsFountainImmediatelyAndCountdownIgnoresSupport() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            Box box=Box.restored(0,0,0,2,1);UmetateGame game=spy(game(f,box));empty(f,box);
            var display=mock(org.bukkit.entity.BlockDisplay.class);
            doReturn(display).when(game).display(any(org.bukkit.Location.class),any(Material.class),anyFloat());
            game.add(1000);for(int i=0;i<40;i++)invoke(game,"fountainTick");
            FillState fill=(FillState)get(game,"fill");assertTrue(fill.full());assertEquals(0,fill.pending());
            assertTrue(((java.util.List<?>)get(game,"flights")).isEmpty());
            ((WinClock)get(game,"clock")).tick(true);
            assertEquals(0,game.add(100));assertEquals(0,game.fillAll());assertEquals(0,fill.pending());
            f.types.put(box.cell(0),Material.AIR);invoke(game,"reconcileAll");
            for(int i=0;i<40;i++)invoke(game,"fountainTick");
            assertEquals(8,fill.filled());assertEquals(0,fill.pending());
        }
    }
    @Test void salmonCallsHaveIndependentTimersAndCleanupRemovesSurvivors() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            Box box=Box.restored(0,0,0,2,3);UmetateGame game=game(f,box);empty(f,box);
            when(f.world.getMaxHeight()).thenReturn(320);
            var fishes=new java.util.ArrayList<org.bukkit.entity.Salmon>();
            when(f.world.spawn(any(org.bukkit.Location.class),eq(org.bukkit.entity.Salmon.class))).thenAnswer(call -> {
                var fish=mock(org.bukkit.entity.Salmon.class);when(fish.isValid()).thenReturn(true);
                when(fish.getLocation()).thenReturn(new org.bukkit.Location(f.world,0,1,0));fishes.add(fish);return fish;
            });
            game.salmon(2,1,3,"太郎");set(game,"tick",10);game.salmon(1,1,3,"太郎");
            set(game,"tick",19);invoke(game,"salmonTick");verify(f.world,never()).createExplosion(any(org.bukkit.Location.class),anyFloat(),anyBoolean(),anyBoolean());
            set(game,"tick",20);invoke(game,"salmonTick");
            verify(f.world,times(2)).createExplosion(any(org.bukkit.Location.class),eq(3f),eq(false),eq(false));
            verify(fishes.get(0)).remove();verify(fishes.get(1)).remove();verify(fishes.get(2),never()).remove();
            assertEquals(2,((java.util.Deque<?>)get(game,"blasts")).size());
            invoke(game,"cleanupEffects");verify(fishes.get(2)).remove();
            assertTrue(((java.util.List<?>)get(game,"salmons")).isEmpty());
        }
    }
    @Test void salmonCommandUsesRaibrArgumentOrderAndDefaults() {
        var game=mock(UmetateGame.class);var sender=mock(org.bukkit.entity.Player.class);
        when(sender.hasPermission("raiumetate.admin")).thenReturn(true);var command=new UmetateCommand(game);
        command.onCommand(sender,null,"bedrock",new String[]{"salmon","3"});verify(game).salmon(3,5,3,"");
        command.onCommand(sender,null,"bedrock",new String[]{"salmon","2","10","4.5","太郎"});verify(game).salmon(2,10,4.5,"太郎");
        verify(sender,never()).sendMessage(any(net.kyori.adventure.text.Component.class));
    }
    @Test void directFillIsBudgetedFrozenAndStopsExcessWithoutDisplays() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            Box box=Box.restored(0,0,0,2,3);var game=spy(game(f,box));empty(f,box);
            f.plugin.getConfig().set("performance.instant-box-changes",false);
            f.plugin.getConfig().set("performance.changes-per-tick",2);game.reloadSettings();game.add(1000,true);set(game,"frozenUntil",10);
            invoke(game,"directTick");assertEquals(0,((FillState)get(game,"fill")).filled());
            set(game,"tick",10);invoke(game,"directTick");assertEquals(2,((FillState)get(game,"fill")).filled());
            for(int i=0;i<20;i++)invoke(game,"directTick");
            var state=(FillState)get(game,"fill");assertTrue(state.full());assertEquals(0,state.pending());
            assertEquals(0,game.add(100,true));assertEquals(0,game.add(100,false));
            verify(game,never()).display(any(org.bukkit.Location.class),any(Material.class),anyFloat());
            f.types.put(box.cell(0),Material.AIR);invoke(game,"reconcileAll");invoke(game,"directTick");assertEquals(26,state.filled());
        }
    }
    @Test void simultaneousDirectAndFountainCannotDoubleBookAndCapacityCancelsFlights() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            Box box=Box.restored(0,0,0,2,3);var game=spy(game(f,box));empty(f,box);
            doReturn(mock(org.bukkit.entity.BlockDisplay.class)).when(game).display(any(org.bukkit.Location.class),any(Material.class),anyFloat());
            // The count below must not depend on wall-clock speed: slow mocks on a busy PC can use up the default 20 ms tick budget.
            f.plugin.getConfig().set("performance.instant-max-ms-per-tick",1000);game.reloadSettings();
            game.add(100,false);invoke(game,"fountainTick");game.add(100,true);invoke(game,"directTick");
            assertEquals(23,((FillState)get(game,"fill")).filled());assertEquals(4,((java.util.List<?>)get(game,"flights")).size());
            for(int i=0;i<31;i++)invoke(game,"fountainTick");
            assertTrue(((FillState)get(game,"fill")).full());assertEquals(0,((FillState)get(game,"fill")).pending());
            assertTrue(((java.util.List<?>)get(game,"flights")).isEmpty());
        }
    }
    @Test void tntDropsFromJustAboveTheBoxAndAllExplodeTogether() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            Box box=Box.restored(0,0,0,2,6);var game=spy(game(f,box));empty(f,box);
            var tnt=mock(org.bukkit.entity.TNTPrimed.class);when(tnt.isValid()).thenReturn(true);when(tnt.getLocation()).thenReturn(new org.bukkit.Location(f.world,0.5,1,0.5));
            doReturn(tnt).when(game).primed(any(org.bukkit.Location.class),anyInt());
            game.tnt(20,4,"","強化TNT");var starts=org.mockito.ArgumentCaptor.forClass(org.bukkit.Location.class);
            verify(game,times(20)).primed(starts.capture(),anyInt());for(var at:starts.getAllValues())assertEquals(7,at.getY());
            for(Object bomb:(java.util.List<?>)get(game,"bombs"))assertEquals(30,get(bomb,"duration"));
            for(int i=0;i<29;i++)invoke(game,"effectsTick");
            assertEquals(20,((java.util.List<?>)get(game,"bombs")).size());verify(tnt,never()).remove();
            invoke(game,"effectsTick");assertTrue(((java.util.List<?>)get(game,"bombs")).isEmpty());verify(tnt,times(20)).remove();
            var roof=new java.util.LinkedHashMap<Box.Cell,org.bukkit.block.data.BlockData>();for(var c:BedrockLid.cells(box))roof.put(c,f.data(Material.AIR));
            set(game,"lid",new BedrockLid<org.bukkit.block.data.BlockData>(box,10,roof));clearInvocations(game);
            game.normalTnt(1,"");verify(game).primed(argThat((org.bukkit.Location at)->at.getY()==8),anyInt());
        }
    }
    @Test void spawnHeightCacheTracksFillGravityAndTntSalmonUseSameGlobalTop() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            Box box=Box.restored(0,0,0,2,6);var game=spy(game(f,box));empty(f,box);when(f.world.getMaxHeight()).thenReturn(320);
            invoke(game,"reconcileAll");assertEquals(3,game.spawnY());
            f.types.put(new Box.Cell(0,6,0),Material.DIAMOND_BLOCK);invoke(game,"reconcileAll");assertEquals(9,game.spawnY());
            when(f.world.spawn(any(org.bukkit.Location.class),eq(org.bukkit.entity.Salmon.class))).thenReturn(mock(org.bukkit.entity.Salmon.class));
            game.salmon(1,5,3,"");verify(f.world).spawn(argThat((org.bukkit.Location at)->at.getY()==9),eq(org.bukkit.entity.Salmon.class));
            invoke(game,"gravityTick");assertEquals(4,game.spawnY());
            var roof=new java.util.LinkedHashMap<Box.Cell,org.bukkit.block.data.BlockData>();for(var c:BedrockLid.cells(box))roof.put(c,f.data(Material.AIR));
            set(game,"lid",new BedrockLid<org.bukkit.block.data.BlockData>(box,10,roof));assertEquals(11,game.spawnY());
        }
    }
    @Test void resizeRejectsContainersBeforeChangingAnything() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            Box box=Box.restored(0,0,0,2,3);var game=game(f,box);empty(f,box);
            when(f.world.getMinHeight()).thenReturn(-64);when(f.world.getMaxHeight()).thenReturn(320);
            var border=mock(org.bukkit.WorldBorder.class);when(f.world.getWorldBorder()).thenReturn(border);when(border.isInside(any(org.bukkit.Location.class))).thenReturn(true);
            f.plugin.getConfig().set("performance.reconcile-per-tick",1);game.reloadSettings();
            var chest=f.block(2,2,0);f.types.put(new Box.Cell(chest.getX(),chest.getY(),chest.getZ()),Material.CHEST);
            game.resize(3,3);drainChanges(game);assertFalse(game.busy());assertEquals(box,game.box());game.close();
            verify(chest,never()).setType(any(Material.class),anyBoolean());verify(chest,never()).setBlockData(any(org.bukkit.block.data.BlockData.class),anyBoolean());
        }
    }
    @Test void effectiveLimitsRespectWorldBorderAndHeightAndNoopAtMaximum() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            var game=game(f,Box.restored(0,300,0,4,7));
            when(f.world.getMinHeight()).thenReturn(-64);when(f.world.getMaxHeight()).thenReturn(320);
            var border=mock(org.bukkit.WorldBorder.class);when(f.world.getWorldBorder()).thenReturn(border);
            when(border.isInside(any(org.bukkit.Location.class))).thenAnswer(call->{var at=(org.bukkit.Location)call.getArgument(0);return Math.abs(at.getX())<=4 && Math.abs(at.getZ())<=4;});
            game.resize(100,1000);assertEquals(Box.restored(0,300,0,4,7),game.box());assertFalse(game.busy());
            verify(f.world,never()).getChunkAtAsync(anyInt(),anyInt(),anyBoolean());
        }
    }
    @Test void failedStructuralCheckpointLeavesWorldUntouchedAndStops() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            var game=game(f,Box.restored(0,0,0,2,3));
            when(f.world.getMinHeight()).thenReturn(-64);when(f.world.getMaxHeight()).thenReturn(320);
            var border=mock(org.bukkit.WorldBorder.class);when(f.world.getWorldBorder()).thenReturn(border);when(border.isInside(any(org.bukkit.Location.class))).thenReturn(true);
            var store=mock(ArenaStore.class);var future=java.util.concurrent.CompletableFuture.<Void>failedFuture(new java.io.IOException("simulated disk failure"));when(store.write(anyMap(),any(),anyBoolean())).thenReturn(future);set(game,"arenaStore",store);
            game.resize(3,3);for(int i=0;i<1000 && !(boolean)get(game,"ioFailed");i++)invoke(game,"tick");verify(store).write(anyMap(),any(),anyBoolean());assertTrue(game.busy());
            for(var block:f.blocks.values())verify(block,never()).setType(any(Material.class),anyBoolean());for(var block:f.blocks.values())verify(block,never()).setBlockData(any(org.bukkit.block.data.BlockData.class),anyBoolean());
            assertTrue((boolean)get(game,"ioFailed"));assertThrows(IllegalArgumentException.class,()->game.add(1));
            for(var block:f.blocks.values())verify(block,never()).setType(any(Material.class),anyBoolean());for(var block:f.blocks.values())verify(block,never()).setBlockData(any(org.bukkit.block.data.BlockData.class),anyBoolean());
            game.close();
        }
    }
    @Test void resizeChangesPhysicalWallsWithinBudgetAndRestoresShrunkTerrain() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            Box initial=Box.restored(0,0,0,2,3);var game=game(f,initial);empty(f,initial);
            when(f.world.getMinHeight()).thenReturn(-64);when(f.world.getMaxHeight()).thenReturn(320);
            var border=mock(org.bukkit.WorldBorder.class);when(f.world.getWorldBorder()).thenReturn(border);when(border.isInside(any(org.bukkit.Location.class))).thenReturn(true);
            // Make all original terrain explicit so shrinking can prove restoration.
            var originals=(java.util.Map<Box.Cell,org.bukkit.block.data.BlockData>)get(game,"originals");
            for(int x=-2;x<=2;x++)for(int y=0;y<=3;y++)for(int z=-2;z<=2;z++)originals.put(new Box.Cell(x,y,z),f.data(Material.AIR));
            f.types.put(new Box.Cell(3,2,0),Material.GOLD_BLOCK);
            f.plugin.getConfig().set("performance.changes-per-tick",1);game.reloadSettings();f.plugin.getConfig().set("performance.reconcile-per-tick",1);game.reloadSettings();
            game.start();game.resize(3,3);assertTrue(game.busy());drainChanges(game);
            assertFalse(game.busy());assertEquals(Material.BEDROCK,f.types.get(new Box.Cell(3,2,0)));
            assertEquals(Material.AIR,f.types.get(new Box.Cell(2,2,0)));assertEquals(0,((FillState)get(game,"fill")).filled());
            game.resize(2,3);drainChanges(game);assertFalse(game.busy());assertEquals(initial,game.box());
            assertEquals(Material.GOLD_BLOCK,f.types.get(new Box.Cell(3,2,0)));
            assertEquals(Material.BEDROCK,f.types.get(new Box.Cell(2,2,0)));
            game.close();
            var saved=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(directory.resolve("arena.yml").toFile());
            assertFalse(saved.getBoolean("transitioning"));assertEquals(2,saved.getInt("radius"));
        }
    }
    @Test void removedSupportStartsFallingWithoutWaitingForSweepInterval() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            Box box=Box.restored(0,0,0,4,10);var game=game(f,box);
            var fill=(FillState)get(game,"fill");
            for(int i=0;i<box.capacity();i++)fill.set(i,true);
            int support=box.capacity()-box.width()*box.width()-1;
            var cell=box.cell(support);f.types.put(cell,Material.AIR);
            var update=UmetateGame.class.getDeclaredMethod("update",org.bukkit.block.Block.class);
            update.setAccessible(true);update.invoke(game,f.block(cell.x(),cell.y(),cell.z()));
            set(game,"tick",1);f.plugin.getConfig().set("gravity.interval-ticks",20);game.reloadSettings();
            invoke(game,"gravityTick");
            assertTrue(f.falling.isEmpty());
            assertEquals(Material.AIR,f.types.get(box.cell(support+box.width()*box.width())));
            assertNotEquals(Material.AIR,f.types.get(cell));
        }
    }
    @Test void supportedBlocksDoNotAllocateBlockStateSnapshots() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            Box box=Box.restored(0,0,0,2,3);var game=game(f,box);
            invoke(game,"gravityTick");
            for(var block:f.blocks.values())verify(block,never()).getState();
            assertTrue(f.falling.isEmpty());
        }
    }
    @Test void expensiveGravityMovesYieldAndEventuallyResume() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            Box box=Box.restored(0,0,0,4,3);var game=game(f,box);empty(f,box);
            for(int i=98;i<147;i++)f.types.put(box.cell(i),Material.DIAMOND_BLOCK);invoke(game,"reconcileAll");
            when(f.world.getBlockAt(anyInt(),anyInt(),anyInt())).thenAnswer(call->{
                Thread.sleep(3);return f.block(call.getArgument(0),call.getArgument(1),call.getArgument(2));
            });
            int left=49;
            for(int i=0;i<300 && left>0;i++){
                invoke(game,"gravityTick");int now=floating(f,box,98,147);
                assertTrue(left-now<=10,"1回の重力処理で移動が集中: "+(left-now));left=now;
            }
            assertEquals(0,left);
        }
    }
    @Test void crowdedGravityLandingDoesNotScanEntireArena() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            Box box=Box.restored(0,0,0,2,3);var game=game(f,box);invoke(game,"reconcileAll");
            var entity=mock(org.bukkit.entity.FallingBlock.class);
            when(entity.getWorld()).thenReturn(f.world);when(entity.getSourceLoc()).thenReturn(new org.bukkit.Location(f.world,0,3,0));
            var fallingData=f.data(Material.DIAMOND_BLOCK);when(entity.getBlockData()).thenReturn(fallingData);when(entity.getScoreboardTags()).thenReturn(java.util.Set.of("rai_umetate_gravity"));
            var event=mock(org.bukkit.event.entity.EntityChangeBlockEvent.class);when(event.getEntity()).thenReturn(entity);when(event.getBlock()).thenReturn(f.block(0,1,0));
            clearInvocations(f.world);game.entityChange(event);
            verify(f.world,atMost(4)).getBlockAt(anyInt(),anyInt(),anyInt());verify(entity).remove();
            assertTrue(((FillState)get(game,"fill")).full());
        }
    }
    private static void gravitySettle(UmetateGame game)throws Exception {
        for(int i=0;i<300;i++)invoke(game,"gravityTick");
    }
    private static int floating(FrameHealthTest.Fixture f,Box box,int from,int to) {
        return (int)java.util.stream.IntStream.range(from,to).filter(i->f.types.get(box.cell(i))==Material.DIAMOND_BLOCK).count();
    }
    /** A falling entity saved by a version before instant gravity, picked up again from its source cell. */
    private static org.bukkit.entity.FallingBlock recovered(UmetateGame game,FrameHealthTest.Fixture f,Box.Cell source,Material material)throws Exception {
        var entity=mock(org.bukkit.entity.FallingBlock.class);var data=f.data(material);
        when(entity.getWorld()).thenReturn(f.world);when(entity.getBlockData()).thenReturn(data);
        when(entity.getSourceLoc()).thenReturn(new org.bukkit.Location(f.world,source.x(),source.y(),source.z()));
        when(entity.getScoreboardTags()).thenReturn(java.util.Set.of("rai_umetate_gravity"));
        var recover=UmetateGame.class.getDeclaredMethod("recoverGravity",org.bukkit.entity.Entity.class);recover.setAccessible(true);recover.invoke(game,entity);
        return entity;
    }
    @Test void winningKeepsTntBlocksAndLitTntWhileClearingTheRest() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            Box box=Box.restored(0,0,0,2,3);var game=spy(game(f,box));empty(f,box);
            var lit=mock(org.bukkit.entity.TNTPrimed.class);when(lit.isValid()).thenReturn(true);when(lit.getLocation()).thenReturn(new org.bukkit.Location(f.world,.5,3,.5));
            doReturn(lit).when(game).primed(any(org.bukkit.Location.class),anyInt());
            f.types.put(box.cell(0),Material.DIAMOND_BLOCK);f.types.put(box.cell(1),Material.TNT);f.types.put(box.cell(10),Material.TNT);
            invoke(game,"reconcileAll");game.tnt(1,4,"","強化TNT");
            invoke(game,"completeRound");
            // Checked before draining: the kept bomb goes off on schedule once the round restarts.
            verify(lit,never()).remove();assertEquals(1,((java.util.List<?>)get(game,"bombs")).size());
            set(game,"bombs",new java.util.ArrayList<>());drainChanges(game);
            assertEquals(Material.AIR,f.types.get(box.cell(0)));
            assertEquals(Material.TNT,f.types.get(box.cell(1)));assertEquals(Material.TNT,f.types.get(box.cell(10)));
            assertEquals(2,((FillState)get(game,"fill")).filled());
        }
    }
    @Test void instantClearStillRemovesTntBlocksAndLitTnt() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            Box box=Box.restored(0,0,0,2,3);var game=spy(game(f,box));empty(f,box);
            var lit=mock(org.bukkit.entity.TNTPrimed.class);when(lit.isValid()).thenReturn(true);when(lit.getLocation()).thenReturn(new org.bukkit.Location(f.world,.5,3,.5));
            doReturn(lit).when(game).primed(any(org.bukkit.Location.class),anyInt());
            f.types.put(box.cell(1),Material.TNT);invoke(game,"reconcileAll");game.tnt(1,4,"","強化TNT");
            game.clearNow();drainChanges(game);
            assertEquals(Material.AIR,f.types.get(box.cell(1)));verify(lit).remove();
            assertTrue(((java.util.List<?>)get(game,"bombs")).isEmpty());
        }
    }
    @Test void dragonClearRemovesColumnsAsItPassesWithoutBlockingOtherCommands() throws Exception {
        try (var f = new FrameHealthTest.Fixture(directory,10000)) {
            Box box=Box.restored(0,0,0,3,2); UmetateGame game=game(f,box);
            for(int i=0;i<box.capacity();i++) f.types.put(box.cell(i),Material.DIAMOND_BLOCK);
            invoke(game,"reconcileAll");drainChanges(game);
            assertEquals(box.capacity(),((FillState)get(game,"fill")).filled());
            game.clear(true,"太郎");
            assertTrue(game.dragonClearing()); assertFalse(game.busy());
            verify(f.world).spawn(any(org.bukkit.Location.class),eq(org.bukkit.entity.EnderDragon.class),any(java.util.function.Consumer.class));
            // Default pass: clear-dragon-seconds (9) of flight from DRAGON_LEAD blocks before the box to DRAGON_LEAD past it.
            int duration=8*20,first=(int)Math.ceil((UmetateGame.DRAGON_LEAD-UmetateGame.DRAGON_HEAD)/(box.width()+2*UmetateGame.DRAGON_LEAD)*duration);
            for(int i=0;i<first-2;i++) invoke(game,"tick");
            assertEquals(box.capacity(),((FillState)get(game,"fill")).filled(),"nothing vanishes before the head arrives");
            // Columns go one at a time: part-way through the pass the box is neither full nor empty.
            for(int i=first-2;i<first+3;i++) invoke(game,"tick");
            int part=((FillState)get(game,"fill")).filled();assertTrue(part>0 && part<box.capacity(),"partly cleared: "+part);
            for(int i=first+3;i<duration;i++) invoke(game,"tick");
            assertEquals(0,((FillState)get(game,"fill")).filled());
            for(int i=0;i<box.capacity();i++) assertEquals(Material.AIR,f.types.get(box.cell(i)));
            assertTrue(game.dragonClearing());
            for(int i=0;i<UmetateGame.DRAGON_TAIL+2;i++) invoke(game,"tick");
            assertFalse(game.dragonClearing());
        }
    }
    private static java.util.List<org.bukkit.entity.Allay> allays(FrameHealthTest.Fixture f) {
        var list=new java.util.ArrayList<org.bukkit.entity.Allay>();
        when(f.world.spawn(any(org.bukkit.Location.class),eq(org.bukkit.entity.Allay.class),any(java.util.function.Consumer.class))).thenAnswer(call -> {
            var a=mock(org.bukkit.entity.Allay.class);when(a.isValid()).thenReturn(true);
            when(a.getLocation()).thenReturn(new org.bukkit.Location(f.world,0,5,0));list.add(a);return a;
        });
        return list;
    }
    @Test void alleyFillsLowestFreeCellsFirstAtFourPerSecondAndVanishesAtZero() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            Box box=Box.restored(0,0,0,2,3);UmetateGame game=game(f,box);empty(f,box);var list=allays(f);
            game.alley(5,"太郎");assertEquals(1,list.size());
            var fill=(FillState)get(game,"fill");
            for(int i=0;i<19;i++)invoke(game,"alleyTick");
            assertEquals(3,fill.filled(),"4 per second = one block per 5 ticks");
            for(int i=0;i<3;i++){assertTrue(fill.occupied(i));assertNotEquals(Material.AIR,f.types.get(box.cell(i)));}
            assertFalse(fill.occupied(3));
            verify(list.get(0),never()).remove();
            for(int i=0;i<10;i++)invoke(game,"alleyTick");
            assertEquals(5,fill.filled());for(int i=0;i<5;i++)assertTrue(fill.occupied(i));assertFalse(fill.occupied(5));
            verify(list.get(0)).remove();assertTrue(((java.util.List<?>)get(game,"allays")).isEmpty());
            for(int i=0;i<30;i++)invoke(game,"alleyTick");assertEquals(5,fill.filled());
        }
    }
    @Test void alleyHoversWhenFullThenResumesWhenSpaceOpens() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            Box box=Box.restored(0,0,0,2,1);UmetateGame game=game(f,box);
            for(int i=0;i<box.capacity();i++)f.types.put(box.cell(i),Material.DIAMOND_BLOCK);
            invoke(game,"reconcileAll");var list=allays(f);var fill=(FillState)get(game,"fill");
            assertTrue(fill.full());game.alley(2,"");
            for(int i=0;i<40;i++)invoke(game,"alleyTick");
            verify(list.get(0),never()).remove();verify(list.get(0),atLeastOnce()).teleport(any(org.bukkit.Location.class));
            f.types.put(box.cell(4),Material.AIR);invoke(game,"reconcileAll");
            for(int i=0;i<10;i++)invoke(game,"alleyTick");
            assertTrue(fill.occupied(4));verify(list.get(0),never()).remove();
            f.types.put(box.cell(2),Material.AIR);invoke(game,"reconcileAll");
            for(int i=0;i<10;i++)invoke(game,"alleyTick");
            assertTrue(fill.full());verify(list.get(0)).remove();
        }
    }
    @Test void alleyHasConcurrencyCapAndCleanupRemovesIt() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            Box box=Box.restored(0,0,0,2,3);UmetateGame game=game(f,box);empty(f,box);var list=allays(f);
            for(int i=0;i<20;i++)game.alley(1000000,"");
            assertThrows(IllegalArgumentException.class,()->game.alley(1,""));
            assertThrows(IllegalArgumentException.class,()->game.alley(0,""));
            invoke(game,"cleanupEffects");for(var a:list)verify(a).remove();
            assertTrue(((java.util.List<?>)get(game,"allays")).isEmpty());
        }
    }
    @Test void alleyCommandTakesCountAndOptionalName() {
        var game=mock(UmetateGame.class);var sender=mock(org.bukkit.entity.Player.class);
        when(sender.hasPermission("raiumetate.admin")).thenReturn(true);var command=new UmetateCommand(game);
        command.onCommand(sender,null,"umetate",new String[]{"alley","50"});verify(game).alley(50,"");
        command.onCommand(sender,null,"umetate",new String[]{"alley","12345","太郎"});verify(game).alley(12345,"太郎");
        clearInvocations(game);command.onCommand(sender,null,"umetate",new String[]{"alley","0"});verify(game,never()).alley(anyInt(),any());
    }
    @Test void instantClearStillAvailable() throws Exception {
        try (var f = new FrameHealthTest.Fixture(directory,10000)) {
            Box box=Box.restored(0,0,0,2,1); UmetateGame game=game(f,box);
            for(int i=0;i<box.capacity();i++) f.types.put(box.cell(i),Material.DIAMOND_BLOCK);
            game.clear(false,""); assertFalse(game.dragonClearing()); drainChanges(game);
            for(int i=0;i<box.capacity();i++) assertEquals(Material.AIR,f.types.get(box.cell(i)));
        }
    }
    @Test void winLaunchesFireworksAroundTheBox() throws Exception {
        try (var f = new FrameHealthTest.Fixture(directory,10000)) {
            Box box=Box.restored(0,0,0,2,1); UmetateGame game=game(f,box);
            ((WinClock)get(game,"clock")).duration(1);
            for(int i=0;i<box.capacity();i++) f.types.put(box.cell(i),Material.DIAMOND_BLOCK);
            for(int i=0;i<60+UmetateGame.SHOW_TICKS;i++) invoke(game,"tick");drainChanges(game);
            verify((UmetatePlugin)f.plugin,times(1)).requestWin(true,WinService.Reason.FILL_COMPLETE);
            verify(f.world,atLeast(10)).spawn(any(org.bukkit.Location.class),eq(org.bukkit.entity.Firework.class),any(java.util.function.Consumer.class));
        }
    }
    private static void drainChanges(UmetateGame game)throws Exception {
        for(int i=0;i<5000 && game.busy();i++){invoke(game,"tick");Thread.sleep(1);}
        assertFalse(game.busy(),"構造変更が完了しませんでした。");
    }
    private static void empty(FrameHealthTest.Fixture f,Box box) {
        for(int i=0;i<box.capacity();i++) f.types.put(box.cell(i),Material.AIR);
    }
    @Test void topLockSilentlyRejectsPlacement() throws Exception {
        try (var f = new FrameHealthTest.Fixture(directory,10000)) {
            UmetateGame game=game(f,Box.restored(0,0,0,2,2));
            var event=mock(org.bukkit.event.block.BlockPlaceEvent.class);
            var player=mock(org.bukkit.entity.Player.class);
            var placed=f.block(0,3,0);
            when(event.getPlayer()).thenReturn(player); when(event.getBlockPlaced()).thenReturn(placed);
            game.place(event);
            verify(event).setCancelled(true);
            verifyNoInteractions(player);
        }
    }
    @Test void fountainKeepsLandingAcrossLayerBoundariesWithoutGaps() throws Exception {
        try(var f=new FrameHealthTest.Fixture(directory,10000)) {
            Box box=Box.restored(0,0,0,6,9); UmetateGame game=spy(game(f,box));
            for(int i=0;i<box.capacity();i++) f.types.put(box.cell(i),Material.AIR);
            doReturn(mock(org.bukkit.entity.BlockDisplay.class)).when(game).display(any(org.bukkit.Location.class),any(Material.class),anyFloat());
            game.add(box.capacity());
            for(int tick=1;tick<=100;tick++) {
                set(game,"tick",tick); invoke(game,"fountainTick");
                assertEquals(Math.max(0,(tick-30)*4),((FillState)get(game,"fill")).filled(),"landing gap at tick " + tick);
            }
        }
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
