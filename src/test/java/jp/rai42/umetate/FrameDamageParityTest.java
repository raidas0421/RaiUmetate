package jp.rai42.umetate;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** The occluder shortcut must return exactly what the full 64-ray computation returns. */
class FrameDamageParityTest {
    @TempDir Path directory;
    static final int R = 4, H = 6; // walls at x/z = ±4, floor y = 0, interior y 1..6
    final Set<Box.Cell> solid = new HashSet<>();
    final AtomicInteger rays = new AtomicInteger();

    @Test void shortcutMatchesFullComputationAndSkipsHiddenFrameBlocks() {
        try (var f = new FrameHealthTest.Fixture(directory, 10000)) {
            ArenaBounds b = new ArenaBounds(f.world, -R, R, 0, H, -R, R, "DIAMETER", 7, H);
            when(f.world.rayTraceBlocks(any(Location.class), any(Vector.class), anyDouble(), eq(FluidCollisionMode.NEVER), eq(true)))
                .thenAnswer(call -> { rays.incrementAndGet(); return trace(f, call.getArgument(0), call.getArgument(1), call.getArgument(2)); });
            Random random = new Random(42);
            int compared = 0;
            for (int scenario = 0; scenario < 24; scenario++) {
                double fullness = switch (scenario % 4) { case 0 -> 0; case 1 -> .5; case 2 -> 1; default -> .97; };
                layout(f, random, fullness);
                var occluders = occluders(b);
                when(f.arena.occluders()).thenReturn(occluders);
                for (int shot = 0; shot < 4; shot++) {
                    double power = 2 + random.nextInt(5);
                    Location origin = new Location(f.world, -R + 1 + random.nextDouble() * (2 * R - 2), 1 + random.nextDouble() * (H + 1.5), -R + 1 + random.nextDouble() * (2 * R - 2));
                    rays.set(0); double expected = reference(f, b, origin, power); int full = rays.get();
                    rays.set(0); double actual = f.health.damageAt(b, origin, power); int fast = rays.get();
                    assertEquals(expected, actual, "scenario " + scenario + " origin " + origin + " power " + power);
                    assertTrue(fast <= full);
                    compared++;
                }
            }
            assertEquals(96, compared);
            // A full box with the blast resting on top: the walls and floor below are hidden without casting rays.
            layout(f, random, 1);
            when(f.arena.occluders()).thenReturn(occluders(b));
            Location top = new Location(f.world, .5, H + 1.01, .5);
            rays.set(0); double expected = reference(f, b, top, 6); int full = rays.get();
            rays.set(0); double actual = f.health.damageAt(b, top, 6); int fast = rays.get();
            assertEquals(expected, actual);
            assertTrue(fast * 4 < full, "rays " + fast + " vs " + full);
        }
    }

    private void layout(FrameHealthTest.Fixture f, Random random, double fullness) {
        solid.clear();
        for (int x = -R; x <= R; x++) for (int y = 0; y <= H; y++) for (int z = -R; z <= R; z++) {
            boolean frame = y == 0 || Math.abs(x) == R || Math.abs(z) == R;
            boolean filled = frame || random.nextDouble() < fullness;
            f.types.put(new Box.Cell(x, y, z), frame ? Material.BEDROCK : filled ? Material.STONE : Material.AIR);
            if (filled) solid.add(new Box.Cell(x, y, z));
        }
    }
    private List<double[]> occluders(ArenaBounds b) {
        List<double[]> result = new ArrayList<>();
        for (int y = 1; y <= H; ) {
            if (!fullLayer(y)) { y++; continue; }
            int start = y; while (y <= H && fullLayer(y)) y++;
            result.add(new double[]{-R + 1, start, -R + 1, R, y, R});
        }
        return result;
    }
    private boolean fullLayer(int y) {
        for (int x = -R + 1; x < R; x++) for (int z = -R + 1; z < R; z++) if (!solid.contains(new Box.Cell(x, y, z))) return false;
        return true;
    }
    /** Full cubes: the hit is the solid cell with the smallest entry along the segment. */
    private RayTraceResult trace(FrameHealthTest.Fixture f, Location o, Vector dir, double length) {
        double best = Double.MAX_VALUE; Box.Cell hit = null;
        for (var c : solid) {
            double t = entry(o.getX(), o.getY(), o.getZ(), dir.getX() * length, dir.getY() * length, dir.getZ() * length, c);
            if (t >= 0 && t < best) { best = t; hit = c; }
        }
        if (hit == null) return null;
        return new RayTraceResult(new Vector(o.getX(), o.getY(), o.getZ()).add(dir.clone().multiply(best * length)), f.block(hit.x(), hit.y(), hit.z()), BlockFace.UP);
    }
    private static double entry(double ax, double ay, double az, double dx, double dy, double dz, Box.Cell c) {
        double t0 = 0, t1 = 1; double[] s = {ax, ay, az}, d = {dx, dy, dz}, lo = {c.x(), c.y(), c.z()};
        for (int k = 0; k < 3; k++) {
            if (Math.abs(d[k]) < 1e-12) { if (s[k] < lo[k] || s[k] > lo[k] + 1) return -1; continue; }
            double u = (lo[k] - s[k]) / d[k], v = (lo[k] + 1 - s[k]) / d[k];
            if (u > v) { double w = u; u = v; v = w; }
            t0 = Math.max(t0, u); t1 = Math.min(t1, v); if (t0 > t1) return -1;
        }
        return t0;
    }
    /** The original computation: every frame candidate, 64 rays each, strongest hit wins. */
    private double reference(FrameHealthTest.Fixture f, ArenaBounds b, Location origin, double power) {
        double reach = power * 2; List<Block> candidates = new ArrayList<>();
        for (int x = b.minX(); x <= b.maxX(); x++) for (int y = b.floorY(); y <= b.topY(); y++) for (int z = b.minZ(); z <= b.maxZ(); z++) {
            boolean boundary = y == b.floorY() || x == b.minX() || x == b.maxX() || z == b.minZ() || z == b.maxZ();
            if (!boundary) continue;
            Block block = f.world.getBlockAt(x, y, z);
            if (block.getLocation().add(.5, .5, .5).distanceSquared(origin) < reach * reach) candidates.add(block);
        }
        candidates.sort(Comparator.comparingDouble(v -> v.getLocation().add(.5, .5, .5).distanceSquared(origin)));
        double strongest = 0;
        for (Block target : candidates) {
            double distance = target.getLocation().add(.5, .5, .5).distance(origin);
            if (BlastDamage.calculate(power, distance, 1) <= strongest) break;
            int visible = 0, total = 0;
            for (int x = 0; x <= 3; x++) for (int y = 0; y <= 3; y++) for (int z = 0; z <= 3; z++) {
                total++;
                Location sample = target.getLocation().add(.001 + x * .998 / 3, .001 + y * .998 / 3, .001 + z * .998 / 3);
                Vector direction = sample.toVector().subtract(origin.toVector()); double length = direction.length();
                if (length < .001) { visible++; continue; }
                RayTraceResult ray = f.world.rayTraceBlocks(origin, direction.normalize(), length, FluidCollisionMode.NEVER, true);
                if (ray == null || target.equals(ray.getHitBlock())) visible++;
            }
            strongest = Math.max(strongest, BlastDamage.calculate(power, distance, (double) visible / total));
        }
        return strongest;
    }
}
