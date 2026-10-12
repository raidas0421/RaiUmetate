package jp.rai42.umetate;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class BedrockLidTest {
    private final Box box = Box.restored(-30, 60, 12, 6, 9);
    private Map<Box.Cell, String> snapshot() {
        Map<Box.Cell, String> blocks = new HashMap<>();
        for (var c : BedrockLid.cells(box)) blocks.put(c, c.x() % 2 == 0 ? "air" : "glass");
        return blocks;
    }
    private BedrockLid<String> lid(int seconds) { return new BedrockLid<>(box, seconds, snapshot()); }
    private BedrockLid.Point from() { return new BedrockLid.Point(-29.5, 80, 12.5); }
    private BedrockLid.Point to() { return new BedrockLid.Point(-29.5, 64, 12.5); }

    @Test void lidCoversOpeningAndRimWithoutCountingAsFill() {
        var cells = BedrockLid.cells(box);
        assertEquals(169, cells.size());
        for (var c : cells) {
            assertEquals(70, c.y());
            assertFalse(box.contains(c.x(), c.y(), c.z()));
        }
        assertTrue(cells.contains(new Box.Cell(-36, 70, 6)));
        assertTrue(cells.contains(new Box.Cell(-24, 70, 18)));
    }
    @Test void unlimitedHitsDoNotConsumeDuration() {
        var lid = lid(10);
        for (int i = 0; i < 500; i++) {
            var hit = lid.intercept(from(), to(), .5);
            assertNotNull(hit); assertEquals(71.5, hit.y());
        }
        assertEquals(10, lid.secondsLeft());
    }
    @Test void durationExpiresAtRequestedTickEvenWithoutHits() {
        var lid = lid(1);
        for (int i = 0; i < 19; i++) assertFalse(lid.tick());
        assertTrue(lid.active()); assertNotNull(lid.intercept(from(), to(), .5));
        assertTrue(lid.tick()); assertEquals(0, lid.secondsLeft());
        assertFalse(lid.active()); assertNull(lid.intercept(from(), to(), .5));
        assertFalse(lid.tick());
    }
    @Test void reapplicationExtendsTimeRatherThanStackingCharges() {
        var lid = lid(10);
        for (int i = 0; i < 40; i++) lid.tick();
        assertEquals(8, lid.secondsLeft()); lid.extend(5); assertEquals(13, lid.secondsLeft());
        lid.extend(3600); assertEquals(3600, lid.secondsLeft());
    }
    @Test void fastProjectileCannotTunnelThroughRoof() {
        var lid = lid(10);
        var hit = lid.intercept(new BedrockLid.Point(-30, 1000, 12), new BedrockLid.Point(-30, -1000, 12), .5);
        assertNotNull(hit); assertEquals(71.5, hit.y());
    }
    @Test void projectileOutsideRoofOrAlreadyInsideBoxIsNotBlocked() {
        var lid = lid(10);
        assertNull(lid.intercept(new BedrockLid.Point(100, 80, 12), new BedrockLid.Point(100, 64, 12), .5));
        assertNull(lid.intercept(new BedrockLid.Point(-30, 69, 12), new BedrockLid.Point(-30, 64, 12), .5));
        assertNull(lid.intercept(to(), from(), .5));
    }
    @Test void roofActivatedJustAsTntTouchesSurfaceStillCatchesIt() {
        var lid = lid(10);
        assertNotNull(lid.intercept(new BedrockLid.Point(-30, 71.25, 12), new BedrockLid.Point(-30, 70, 12), .5));
    }
    @Test void diagonalHitUsesPositionAtRoofRatherThanEndPosition() {
        var lid = lid(10);
        var hit = lid.intercept(new BedrockLid.Point(-50, 81, 12), new BedrockLid.Point(-10, 61, 12), 0);
        assertNotNull(hit); assertEquals(-30, hit.x()); assertEquals(71, hit.y());
    }
    @Test void extensionAndExpiryRestoreExactOriginalBlocks() {
        var original = snapshot(); var world = new HashMap<>(original);
        var lid = new BedrockLid<>(box, 1, original);
        lid.install(c -> world.put(c, "bedrock"));
        assertTrue(world.values().stream().allMatch("bedrock"::equals));
        lid.extend(1);
        for (int i = 0; i < 40; i++) lid.tick();
        lid.restore(world::put);
        assertEquals(original, world);
        // Recovery is idempotent; it does not restore a second copy of the temporary bedrock.
        lid.restore(world::put); assertEquals(original, world);
    }
    @Test void interruptedInstallationCanRestoreFromPersistedSnapshot() {
        var original = snapshot(); var world = new HashMap<>(original);
        var beforeCrash = new BedrockLid<>(box, 10, original);
        beforeCrash.originals().keySet().stream().limit(30).forEach(c -> world.put(c, "bedrock"));
        var recovered = new BedrockLid<>(box, 1, beforeCrash.originals());
        recovered.restore(world::put); assertEquals(original, world);
    }
    @Test void growingBoxThroughLidUsesOriginalBlocksRatherThanBedrock() {
        var lid = lid(10); var c = new Box.Cell(-30, 70, 12);
        var largerBox = Box.restored(-30, 60, 12, 6, 11);
        assertTrue(largerBox.contains(c.x(), c.y(), c.z()));
        assertEquals("air", lid.originals().get(c));
    }
    @Test void invalidDurationAndIncompleteRestorationDataAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> lid(0));
        assertThrows(IllegalArgumentException.class, () -> lid(3601));
        var partial = snapshot(); partial.remove(new Box.Cell(-30, 70, 12));
        assertThrows(IllegalArgumentException.class, () -> new BedrockLid<>(box, 10, partial));
    }
}
