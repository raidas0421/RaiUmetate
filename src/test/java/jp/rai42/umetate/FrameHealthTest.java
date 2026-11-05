package jp.rai42.umetate;

import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.boss.*;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.entity.*;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.*;
import org.bukkit.util.RayTraceResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises the ported RaiBR damage and lifecycle code with a simulated world and scheduler. */
class FrameHealthTest {
    @TempDir Path directory;

    @Test void countsOneStrongestHitInsteadOfAddingAllFrameFaces() {
        try (Fixture f = new Fixture(directory, 10000)) {
            assertEquals(30, f.health.damageAt(f.bounds, f.origin, 4));
            f.health.customExplosion(f.origin, 4);
            assertEquals(9970, f.health.snapshot().health());
            assertEquals(30, f.health.snapshot().lastDamage());
        }
    }
    @Test void fullOcclusionAndOutOfRangeCauseNoDamage() {
        try (Fixture f = new Fixture(directory, 10000)) {
            RayTraceResult occlusion = mock(RayTraceResult.class);
            Block obstruction = f.block(0, 1, 0);
            when(occlusion.getHitBlock()).thenReturn(obstruction);
            when(f.world.rayTraceBlocks(any(Location.class), any(), anyDouble(), eq(FluidCollisionMode.NEVER), eq(true))).thenReturn(occlusion);
            assertEquals(0, f.health.damageAt(f.bounds, new Location(f.world, 0.5, 1.5, .5), 4));
            assertEquals(0, f.health.damageAt(f.bounds, new Location(f.world, 100, 100, 100), 4));
            f.health.customExplosion(new Location(f.world, .5, 1.5, .5), 4);
            assertEquals(10000, f.health.snapshot().health());
        }
    }
    @Test void same64SampleExposureAsRaibrReducesDamage() {
        try (Fixture f = new Fixture(directory, 10000)) {
            // Keep just the nearest frame block so the exposure of this one target is unambiguous.
            for (int x = -1; x <= 1; x++) for (int y = 0; y <= 1; y++) for (int z = -1; z <= 1; z++)
                if (!(x == -1 && y == 1 && z == 0)) f.types.put(new Box.Cell(x,y,z), Material.AIR);
            RayTraceResult covered = mock(RayTraceResult.class);
            Block obstruction = f.block(0,1,0);
            when(covered.getHitBlock()).thenReturn(obstruction);
            int[] samples = {0};
            when(f.world.rayTraceBlocks(any(Location.class), any(), anyDouble(), eq(FluidCollisionMode.NEVER), eq(true)))
                    .thenAnswer(i -> samples[0]++ < 32 ? null : covered);
            assertEquals(11, f.health.damageAt(f.bounds, f.origin, 4));
            assertEquals(64, samples[0]);
        }
    }
    @Test void bedrockLidSuppressesFrameDamageWithoutConsumingHits() {
        try (Fixture f = new Fixture(directory, 10000)) {
            when(f.arena.shieldsExplosion(any())).thenReturn(true);
            for (int i = 0; i < 20; i++) f.health.customExplosion(f.origin, 20);
            assertEquals(10000, f.health.snapshot().health());
            when(f.arena.shieldsExplosion(any())).thenReturn(false);
            f.health.customExplosion(f.origin, 4); assertEquals(9970, f.health.snapshot().health());
        }
    }
    @Test void zeroHpSendsOneLossThrowsContentsOutAndRestoresAfterFiveSeconds() {
        try (Fixture f = new Fixture(directory, 10)) {
            f.health.customExplosion(f.origin, 4);
            f.health.customExplosion(f.origin, 4);
            assertTrue(f.health.isCollapsing()); assertEquals(0, f.health.snapshot().health());
            verify((UmetatePlugin)f.plugin,times(1)).requestWin(false,WinService.Reason.FRAME_BROKEN);
            assertEquals(Material.BEDROCK, f.types.getOrDefault(new Box.Cell(-1,1,0), Material.BEDROCK));
            f.awaitCheckpoint();Scheduled begin = f.delayed.removeFirst(); assertEquals(1, begin.delay()); begin.action().run();
            verify(f.arena).stopGimmicksForCollapse();
            assertEquals(Material.AIR, f.types.get(new Box.Cell(-1,1,0)));
            Scheduled release = f.timers.stream().filter(t -> t.period() == 1).toList().getLast();
            release.action().run();
            assertEquals(Material.AIR, f.types.get(new Box.Cell(0,1,0)));
            // Contents drop straight down as tagged falling blocks that are left wherever they land.
            assertEquals(1, f.falling.size());
            FallingBlock thrown = f.falling.getFirst();
            verify(thrown).addScoreboardTag(FrameHealth.FALLING_TAG);
            verify(thrown).setDropItem(false);
            verify(thrown,never()).setVelocity(any());
            Scheduled rebuild = f.delayed.removeFirst(); assertEquals(100, rebuild.delay());
            assertTrue(f.health.isCollapsing()); rebuild.action().run();
            assertFalse(f.health.isCollapsing()); assertEquals(10, f.health.snapshot().health());
            assertEquals(Material.BEDROCK, f.types.get(new Box.Cell(-1,1,0)));
            verify(f.arena).frameRestored();
        }
    }
    @Test void collapseDropsTheBottomLayerFirstSoUpperBlocksHaveRoomToFall() {
        try (Fixture f = new Fixture(directory, 10)) {
            var tall = new ArenaBounds(f.world,-1,1,0,2,-1,1,"RADIUS",1,2);
            when(f.arena.current()).thenReturn(java.util.Optional.of(tall));
            f.health.refresh();
            f.types.put(new Box.Cell(0,1,0),Material.DIAMOND_BLOCK);f.types.put(new Box.Cell(0,2,0),Material.EMERALD_BLOCK);
            f.health.customExplosion(f.origin, 4); f.awaitCheckpoint();f.delayed.removeFirst().action().run();
            var release = f.timers.stream().filter(t -> t.period() == 1).toList().getLast();
            for (int i = 0; i < 20 && f.falling.size() < 2; i++) release.action().run();
            assertEquals(2, f.falling.size());
            assertEquals(Material.DIAMOND_BLOCK, f.falling.get(0).getBlockData().getMaterial());
            assertEquals(Material.EMERALD_BLOCK, f.falling.get(1).getBlockData().getMaterial());
        }
    }
    @Test void containersAreExcludedFromFallingConversion() {
        try (Fixture f = new Fixture(directory, 10)) {
            f.types.put(new Box.Cell(0,1,0),Material.CHEST);
            f.health.customExplosion(f.origin, 4); f.awaitCheckpoint();f.delayed.removeFirst().action().run();
            f.timers.stream().filter(t -> t.period() == 1).toList().getLast().action().run();
            assertTrue(f.falling.isEmpty());
            verify(f.block(0,1,0), never()).setType(Material.AIR, false);
        }
    }
    @Test void healthPersistsAcrossRestart() {
        try (Fixture f = new Fixture(directory, 10000)) {
            f.health.customExplosion(f.origin, 4);
            f.health.stop();FrameHealth restarted = new FrameHealth(f.plugin, f.arena); restarted.start();
            assertEquals(9970, restarted.snapshot().health()); restarted.stop();
        }
    }
    @Test void resizingTheSameArenaKeepsHealth() {
        try (Fixture f = new Fixture(directory, 10000)) {
            f.health.customExplosion(f.origin, 4); assertEquals(9970, f.health.snapshot().health());
            when(f.arena.current()).thenReturn(Optional.of(new ArenaBounds(f.world,-2,2,0,4,-2,2,"RADIUS",3,3))); f.health.refresh();
            assertEquals(9970, f.health.snapshot().health());
            when(f.arena.current()).thenReturn(Optional.of(new ArenaBounds(f.world,-1,1,0,1,-1,1,"RADIUS",1,1))); f.health.refresh();
            assertEquals(9970, f.health.snapshot().health());
            when(f.arena.current()).thenReturn(Optional.of(new ArenaBounds(f.world,9,11,5,6,9,11,"RADIUS",1,1))); f.health.refresh();
            assertEquals(10000, f.health.snapshot().health());
        }
    }
    @Test void crashDuringCollapseRestoresSavedFrameOnNextStart() {
        try (Fixture f = new Fixture(directory, 10)) {
            f.health.customExplosion(f.origin, 4); f.awaitCheckpoint();f.delayed.removeFirst().action().run();
            assertEquals(Material.AIR, f.types.get(new Box.Cell(-1,1,0)));
            FrameHealth restarted = new FrameHealth(f.plugin, f.arena); restarted.start();
            assertEquals(Material.BEDROCK, f.types.get(new Box.Cell(-1,1,0)));
            assertFalse(restarted.isCollapsing()); assertEquals(10, restarted.snapshot().health());
            verify((UmetatePlugin)f.plugin,times(1)).requestWin(false,WinService.Reason.FRAME_BROKEN);
            restarted.stop();
        }
    }
    @Test void nativeTntUsesPrimeRadiusAndCachesPreExplosionDamage() {
        try (Fixture f = new Fixture(directory, 10000)) {
            TNTPrimed tnt = mock(TNTPrimed.class); when(tnt.getUniqueId()).thenReturn(UUID.randomUUID());
            when(tnt.getLocation()).thenReturn(f.origin);
            ExplosionPrimeEvent prime = mock(ExplosionPrimeEvent.class);
            when(prime.getEntity()).thenReturn(tnt); when(prime.getRadius()).thenReturn(4f);
            f.health.prime(prime);
            // The state changes after priming; damage must still use the captured radius/exposure.
            when(f.arena.shieldsExplosion(any())).thenReturn(true);
            EntityExplodeEvent explode = mock(EntityExplodeEvent.class);
            when(explode.getEntity()).thenReturn(tnt); when(explode.getLocation()).thenReturn(f.origin);
            f.health.exploded(explode); assertEquals(9970, f.health.snapshot().health());
        }
    }
    @Test void tntCannotDirectlyDeleteFrameWhileHpIsPositive() {
        try (Fixture f = new Fixture(directory, 10000)) {
            EntityExplodeEvent e = mock(EntityExplodeEvent.class);
            when(e.getEntity()).thenReturn(mock(TNTPrimed.class)); when(e.getLocation()).thenReturn(f.origin);
            var blocks = new ArrayList<>(List.of(f.block(-1,1,0), f.block(0,1,0)));
            when(e.blockList()).thenReturn(blocks); f.health.protectFrame(e);
            assertEquals(List.of(f.block(0,1,0)), blocks);
        }
    }
    @Test void hpBarKeepsRaibrThresholdColors() {
        try (Fixture f = new Fixture(directory, 100)) {
            f.health.customExplosion(f.origin,4); f.health.refresh(); verify(f.bar, atLeastOnce()).setColor(BarColor.GREEN);
            f.health.customExplosion(f.origin,4); f.health.refresh(); verify(f.bar).setColor(BarColor.YELLOW);
            f.health.customExplosion(f.origin,4); f.health.refresh(); verify(f.bar).setColor(BarColor.RED);
            verify(f.bar).setTitle("枠HP 10 / 100 (10%) | 直径3(最小) 高さ3(最小) | 待機中");
        }
    }
    @Test void normalStopRestoresCollapsingFrameAndCancelsReleaseTask() {
        try (Fixture f = new Fixture(directory, 10)) {
            f.health.customExplosion(f.origin,4); f.awaitCheckpoint();f.delayed.removeFirst().action().run();
            f.health.stop(); assertFalse(f.health.isCollapsing());
            assertEquals(Material.BEDROCK, f.types.get(new Box.Cell(-1,1,0)));
            assertEquals(10, f.health.snapshot().health());
        }
    }

    record Scheduled(Runnable action, long delay, long period) {}
    static final class Fixture implements AutoCloseable {
        final JavaPlugin plugin = mock(UmetatePlugin.class);
        final FrameArena arena = mock(FrameArena.class);
        final World world = mock(World.class);
        final ArenaBounds bounds = new ArenaBounds(world,-1,1,0,1,-1,1,"RADIUS",1,1);
        final Location origin = new Location(world,-.5,1.5,.5);
        final BossBar bar = mock(BossBar.class);
        final ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        final Map<Box.Cell, Block> blocks = new HashMap<>();
        final Map<Box.Cell, Material> types = new HashMap<>();
        final Map<Material, Material> materialViews = new HashMap<>();
        final List<Scheduled> delayed = new ArrayList<>(), timers = new ArrayList<>();
        final List<FallingBlock> falling = new ArrayList<>();
        final MockedStatic<Bukkit> bukkit;
        final FrameHealth health;
        Fixture(Path directory, int maximum) {
            YamlConfiguration config = new YamlConfiguration();
            config.set("frame-health.max-health",maximum); config.set("frame-health.enabled",true);
            config.set("bedrock-integration.valid-boundary-materials",List.of("BEDROCK","GLASS","OAK_PLANKS"));
            when(plugin.getConfig()).thenReturn(config); when(plugin.getDataFolder()).thenReturn(directory.toFile());
            when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
            when(world.getChunkAt(anyInt(),anyInt())).thenReturn(mock(Chunk.class));
            when(world.getChunkAtAsync(anyInt(),anyInt(),eq(true))).thenAnswer(call->java.util.concurrent.CompletableFuture.completedFuture(mock(Chunk.class)));
            when(world.getUID()).thenReturn(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
            when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenAnswer(i -> block(i.getArgument(0),i.getArgument(1),i.getArgument(2)));
            when(world.spawnFallingBlock(any(Location.class),any(BlockData.class))).thenAnswer(i -> {
                FallingBlock entity=mock(FallingBlock.class);
                when(entity.getLocation()).thenReturn(i.getArgument(0)); when(entity.getWorld()).thenReturn(world);
                when(entity.isValid()).thenReturn(true); when(entity.getBlockData()).thenReturn(i.getArgument(1));
                falling.add(entity);return entity;
            });
            when(arena.current()).thenReturn(Optional.of(bounds));
            when(arena.sizeLabel()).thenReturn("直径3(最小) 高さ3(最小)");when(arena.countLabel()).thenReturn("待機中");
            when(console.hasPermission("raiumetate.admin")).thenReturn(true);
            bukkit = mockStatic(Bukkit.class);
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            BukkitScheduler scheduler=mock(BukkitScheduler.class);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
            bukkit.when(Bukkit::getConsoleSender).thenReturn(console);
            bukkit.when(() -> Bukkit.dispatchCommand(console,"rwin lose 1")).thenReturn(true);
            bukkit.when(() -> Bukkit.createBossBar(anyString(),any(BarColor.class),any(BarStyle.class))).thenReturn(bar);
            bukkit.when(() -> Bukkit.createBlockData(anyString())).thenAnswer(i -> data(Material.valueOf(((String)i.getArgument(0)).substring(10).toUpperCase(Locale.ROOT))));
            bukkit.when(() -> Bukkit.createBlockData(any(Material.class))).thenAnswer(i -> data(i.getArgument(0)));
            when(scheduler.runTaskTimer(eq(plugin),any(Runnable.class),anyLong(),anyLong())).thenAnswer(i -> {
                timers.add(new Scheduled(i.getArgument(1),i.getArgument(2),i.getArgument(3)));return mock(BukkitTask.class);
            });
            when(scheduler.runTaskLater(eq(plugin),any(Runnable.class),anyLong())).thenAnswer(i -> {
                delayed.add(new Scheduled(i.getArgument(1),i.getArgument(2),0));return mock(BukkitTask.class);
            });
            health=new FrameHealth(plugin,arena);health.start();
        }
        void awaitCheckpoint() {
            try{var field=FrameHealth.class.getDeclaredField("collapseCheckpoint");field.setAccessible(true);
                var future=(java.util.concurrent.CompletableFuture<?>)field.get(health);if(future!=null)future.get(3,java.util.concurrent.TimeUnit.SECONDS);
            }catch(Exception e){throw new AssertionError(e);}
        }
        Block block(int x,int y,int z) {
            Box.Cell key=new Box.Cell(x,y,z); if(blocks.containsKey(key))return blocks.get(key);
            Block b=mock(Block.class);blocks.put(key,b);
            Material initial=(y==0||x==-1||x==1||z==-1||z==1)?Material.BEDROCK:Material.QUARTZ_BLOCK;
            types.putIfAbsent(key,initial);
            when(b.getX()).thenReturn(x);when(b.getY()).thenReturn(y);when(b.getZ()).thenReturn(z);when(b.getWorld()).thenReturn(world);
            when(b.getLocation()).thenAnswer(i -> new Location(world,x,y,z));
            when(b.getType()).thenAnswer(i -> view(types.get(key)));
            when(b.getBlockData()).thenAnswer(i -> data(types.get(key)));
            doAnswer(i -> {types.put(key,i.getArgument(0));return null;}).when(b).setType(any(Material.class),anyBoolean());
            doAnswer(i -> {types.put(key,((BlockData)i.getArgument(0)).getMaterial());return null;}).when(b).setBlockData(any(BlockData.class),anyBoolean());
            return b;
        }
        Material view(Material type) {
            return materialViews.computeIfAbsent(type,t -> {
                Material m=mock(Material.class);when(m.name()).thenReturn(t.name());when(m.ordinal()).thenReturn(t.ordinal());when(m.createBlockData()).thenAnswer(i -> data(t));when(m.isAir()).thenReturn(t==Material.AIR);when(m.isSolid()).thenReturn(t!=Material.AIR);return m;
            });
        }
        BlockData data(Material type) {
            BlockData d=mock(BlockData.class);when(d.getAsString()).thenReturn("minecraft:"+type.name().toLowerCase(Locale.ROOT));when(d.getMaterial()).thenReturn(type);when(d.clone()).thenReturn(d);
            org.bukkit.block.BlockState state=switch(type) {
                case CHEST,BARREL,HOPPER -> mock(Container.class);
                case OAK_SIGN,SPAWNER,PLAYER_HEAD,WHITE_BED -> mock(org.bukkit.block.TileState.class);
                default -> mock(org.bukkit.block.BlockState.class);
            };
            when(d.createBlockState()).thenReturn(state);return d;
        }
        public void close() {try{health.stop();}finally{bukkit.close();}}
    }
}
