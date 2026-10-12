package jp.rai42.umetate;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.ArrayList;
import java.lang.reflect.Proxy;
import org.bukkit.command.CommandSender;
import net.kyori.adventure.text.Component;
import static org.junit.jupiter.api.Assertions.*;

class GameRulesTest {
    @Test void geometryRoundTripsEveryCellAndExcludesShell() {
        Box box = Box.restored(-73, -20, 129, 6, 9);
        var seen = new HashSet<Box.Cell>();
        for (int i = 0; i < box.capacity(); i++) {
            var c = box.cell(i); assertTrue(seen.add(c));
            assertEquals(i, box.index(c.x(), c.y(), c.z())); assertFalse(box.shell(c.x(), c.y(), c.z()));
        }
        assertEquals(1089, seen.size());
        assertTrue(box.shell(-79, -19, 129)); assertTrue(box.shell(-73, -20, 129));
        assertFalse(box.contains(-73, -10, 129)); assertEquals(-1, box.index(-79, -19, 129));
    }
    @Test void concurrentFountainRequestsNeverOverbookHoles() {
        FillState state = new FillState(10); state.set(0, true); state.set(2, true);
        assertEquals(6, state.enqueue(6)); assertEquals(100, state.enqueue(100));
        var reservations = new HashSet<Integer>();
        for (int i = 0; i < 8; i++) assertTrue(reservations.add(state.reserve()));
        assertEquals(-1, state.reserve()); assertEquals(10, state.enqueue(10));
        assertEquals(2, state.filled()); // visuals are not counted until landing
        for (int i : reservations) state.finish(i, true);
        assertTrue(state.full()); assertEquals(0, state.pending());
    }
    @Test void occupiedFlightTargetIsRetriedAtAnotherHole() {
        FillState state = new FillState(3); state.enqueue(1);
        int first = state.reserve(); state.set(first, true); state.finish(first, false);
        assertEquals(1, state.pending()); int other = state.reserve(); assertNotEquals(first, other);
        state.finish(other, true); assertEquals(2, state.filled()); assertEquals(0, state.pending());
    }
    @Test void reachingCapacityDiscardsRemainderAndIgnoresNewRequests() {
        FillState state = new FillState(2); state.enqueue(2); int i = state.reserve();
        state.set(0, true); state.set(1, true); state.finish(i, false);
        assertEquals(-1, state.reserve()); assertEquals(0, state.pending());
        assertEquals(0,state.enqueue(100));
        state.set(1,false); assertEquals(-1,state.reserve()); assertEquals(0,state.pending());
    }
    @Test void cancelledQueueCannotLandStaleBlocks() {
        FillState state = new FillState(3); state.enqueue(3); int i = state.reserve(); state.cancelPending();
        assertEquals(0, state.pending()); assertEquals(-1, state.reserve());
        assertThrows(IllegalStateException.class, () -> state.finish(i, true));
    }
    @Test void damageOpensSpaceWithoutDuplicatingCounts() {
        FillState state = new FillState(2); state.set(0, true); state.set(0, true); assertEquals(1, state.filled());
        state.set(0, false); state.set(0, false); assertEquals(0, state.filled()); assertEquals(100, state.enqueue(100));
    }
    @Test void winningRequiresUninterruptedPhysicalFillAndFiresOnce() {
        WinClock clock = new WinClock(1); assertEquals(WinClock.Event.START, clock.tick(true));
        for (int i = 0; i < 19; i++) assertEquals(WinClock.Event.NONE, clock.tick(true));
        assertEquals(WinClock.Event.CANCEL, clock.tick(false));
        assertEquals(WinClock.Event.START, clock.tick(true));
        for (int i = 0; i < 19; i++) assertEquals(WinClock.Event.NONE, clock.tick(true));
        assertEquals(WinClock.Event.WIN, clock.tick(true));
        for (int i = 0; i < 60; i++) assertEquals(WinClock.Event.NONE, clock.tick(true));
        clock.tick(false); assertEquals(WinClock.Event.START, clock.tick(true));
    }
    @Test void stoppedClockDoesNotRestartWhileFull() {
        WinClock clock = new WinClock(1); clock.tick(true); clock.pause();
        for (int i = 0; i < 100; i++) assertEquals(WinClock.Event.NONE, clock.tick(true));
        clock.resume(); assertEquals(WinClock.Event.START, clock.tick(true));
    }
    @Test void invalidNumbersAreRejectedIncludingNonFiniteStrength() {
        assertThrows(IllegalArgumentException.class, () -> UmetateCommand.decimal("NaN", 1, 20));
        assertThrows(IllegalArgumentException.class, () -> UmetateCommand.decimal("Infinity", 1, 20));
        assertThrows(IllegalArgumentException.class, () -> UmetateCommand.integer(new String[]{"-1"}, 0, 1, 100));
        assertThrows(IllegalArgumentException.class, () -> Box.restored(0, 0, 0, 100000, 9));
        assertThrows(IllegalArgumentException.class, () -> new WinClock(0));
    }
    @Test void largeBlastNeverTouchesWallsFloorOrOutsideWorld() {
        Box box = Box.restored(-30, -20, 12, 6, 9);
        Blast blast = new Blast(box, -30, -15, 12, 100);
        var cells = new HashSet<Box.Cell>();
        while (blast.hasNext()) {
            var c = blast.next(); if (c == null) continue;
            assertTrue(box.contains(c.x(), c.y(), c.z())); assertTrue(cells.add(c));
        }
        assertEquals(box.capacity(), cells.size());
    }
    @Test void blastOutsideArenaHasNoTargets() {
        Blast blast = new Blast(Box.restored(0, 0, 0, 3, 9), 100, 100, 100, 2);
        assertFalse(blast.hasNext());
    }
    @Test void blastUsesSphericalDistance() {
        Box box = Box.restored(0, 0, 0, 5, 9);
        Blast blast = new Blast(box, .5, 4.5, .5, 1);
        var cells = new HashSet<Box.Cell>();
        while (blast.hasNext()) { var c = blast.next(); if (c != null) cells.add(c); }
        assertEquals(7, cells.size()); assertFalse(cells.contains(new Box.Cell(1, 5, 0)));
    }
    @Test void fountainRisesFromCentreAndLandsExactlyInHole() {
        assertEquals(10, FountainPath.height(10, 2, 0, 6));
        assertEquals(2, FountainPath.height(10, 2, 1, 6));
        assertEquals(12, FountainPath.height(10, 2, .5, 6));
        assertEquals(4, FountainPath.coordinate(0, 8, .5));
    }
    @Test void noArgumentCommandShowsHelpBeforeAnArenaExists() {
        var messages = new ArrayList<Component>();
        CommandSender sender = (CommandSender) Proxy.newProxyInstance(CommandSender.class.getClassLoader(), new Class<?>[]{CommandSender.class}, (proxy, method, args) -> {
            if (method.getName().equals("hasPermission")) return true;
            if (method.getName().equals("sendMessage") && args[0] instanceof Component c) messages.add(c);
            return null;
        });
        assertTrue(new UmetateCommand(null).onCommand(sender, null, "bedrock", new String[0]));
        assertEquals(12, messages.size()); // header, name hint, 10 commands
        assertTrue(messages.getFirst().toString().contains("操作一覧"));
    }
    @Test void commandsRejectUnprivilegedSenderBeforeMutatingGame() {
        var messages = new ArrayList<Component>();
        CommandSender sender = (CommandSender) Proxy.newProxyInstance(CommandSender.class.getClassLoader(), new Class<?>[]{CommandSender.class}, (proxy, method, args) -> {
            if (method.getName().equals("hasPermission")) return false;
            if (method.getName().equals("sendMessage") && args[0] instanceof Component c) messages.add(c);
            return null;
        });
        assertTrue(new UmetateCommand(null).onCommand(sender, null, "bedrock", new String[]{"delete"}));
        assertEquals(1, messages.size()); assertTrue(messages.getFirst().toString().contains("管理者権限"));
    }
    @Test void timerChangeRestartsARunningCountAndAppliesToTheNextOne() {
        WinClock clock = new WinClock(15); assertEquals(15, clock.seconds());
        clock.duration(10); assertEquals(-1, clock.secondsLeft()); assertEquals(WinClock.Event.START, clock.tick(true));
        assertEquals(10, clock.secondsLeft());
        for (int i = 0; i < 100; i++) assertEquals(WinClock.Event.NONE, clock.tick(true));
        assertEquals(5, clock.secondsLeft());
        clock.duration(20); assertEquals(20, clock.secondsLeft()); assertEquals(20, clock.seconds());
        // Counting goes on without a second START; 20 s keeps the slow "2" (30 ticks) before the win.
        int ticks = 0; WinClock.Event e;
        do { e = clock.tick(true); ticks++; assertNotEquals(WinClock.Event.START, e); } while (e != WinClock.Event.WIN);
        assertEquals(20 * 20 + 10, ticks);
        clock.duration(5); assertTrue(clock.won()); assertEquals(WinClock.Event.NONE, clock.tick(true));
        assertEquals(WinClock.Event.NONE, clock.tick(false)); assertEquals(WinClock.Event.START, clock.tick(true)); assertEquals(5, clock.secondsLeft());
        clock.pause(); clock.duration(3); assertEquals(WinClock.Event.NONE, clock.tick(true)); assertTrue(clock.paused());
        assertThrows(IllegalArgumentException.class, () -> clock.duration(0));
        assertThrows(IllegalArgumentException.class, () -> clock.duration(3601));
    }
}
