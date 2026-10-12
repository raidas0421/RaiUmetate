package jp.rai42.umetate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.block.TNTPrimeEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AllTntTest {
    @TempDir Path directory;

    @Test void replacesOnlySolidContentWithTntAndKeepsTheBoxFull() throws Exception {
        try (var f = new FrameHealthTest.Fixture(directory, 10000)) {
            Box box = Box.restored(0, 0, 0, 3, 3); var game = game(f, box);
            for (int i = 0; i < box.capacity(); i++) f.types.put(box.cell(i), Material.DIAMOND_BLOCK);
            f.types.put(box.cell(0), Material.AIR); f.types.put(box.cell(1), Material.CHEST);
            invoke(game, "reconcileAll"); int filled = ((FillState) get(game, "fill")).filled();
            game.tntify("太郎"); drain(game);
            assertEquals(Material.AIR, f.types.get(box.cell(0)));
            assertEquals(Material.CHEST, f.types.get(box.cell(1)));
            for (int i = 2; i < box.capacity(); i++) assertEquals(Material.TNT, f.types.get(box.cell(i)));
            assertEquals(filled, ((FillState) get(game, "fill")).filled());
            assertTrue(f.falling.isEmpty());
            game.close();
        }
    }
    @Test void chainIgnitionInsideTheBoxIsCapped() throws Exception {
        try (var f = new FrameHealthTest.Fixture(directory, 10000)) {
            Box box = Box.restored(0, 0, 0, 3, 3); var game = game(f, box);
            f.plugin.getConfig().set("effects.max-chain-tnt", 3); game.reloadSettings();
            var inside = f.block(0, 1, 0); var outside = f.block(50, 1, 50);
            int cancelled = 0;
            for (int i = 0; i < 5; i++) { var e = prime(inside); game.tntPrime(e); if (wasCancelled(e)) cancelled++; }
            assertEquals(2, cancelled);
            var away = prime(outside); game.tntPrime(away); assertFalse(wasCancelled(away));
            set(game, "tick", 80); var later = prime(inside); game.tntPrime(later); assertFalse(wasCancelled(later));
        }
    }
    @Test void commandPassesTheNameAndIsNotDoubled() {
        var game = mock(UmetateGame.class); var sender = mock(Player.class);
        when(sender.hasPermission("raiumetate.admin")).thenReturn(true);
        new UmetateCommand(game).onCommand(sender, null, "umetate", new String[]{"alltnt", "配信", "太郎"});
        verify(game).tntify("配信 太郎"); verify(game, never()).doubleTime();
    }
    private static TNTPrimeEvent prime(org.bukkit.block.Block block) {
        var e = mock(TNTPrimeEvent.class); when(e.getBlock()).thenReturn(block); return e;
    }
    private static boolean wasCancelled(TNTPrimeEvent e) {
        try { verify(e).setCancelled(true); return true; } catch (AssertionError notCancelled) { return false; }
    }
    private static void drain(UmetateGame game) throws Exception {
        for (int i = 0; i < 5000 && game.busy(); i++) { invoke(game, "tick"); Thread.sleep(1); }
        assertFalse(game.busy());
    }
    private static UmetateGame game(FrameHealthTest.Fixture f, Box box) throws Exception {
        UmetateGame game = new UmetateGame((UmetatePlugin) f.plugin);
        set(game, "world", f.world); set(game, "box", box); set(game, "fill", new FillState(box.capacity()));
        return game;
    }
    private static Object get(Object target, String key) throws Exception {
        Field field = UmetateGame.class.getDeclaredField(key); field.setAccessible(true); return field.get(target);
    }
    private static void set(Object target, String key, Object value) throws Exception {
        Field field = UmetateGame.class.getDeclaredField(key); field.setAccessible(true); field.set(target, value);
    }
    private static void invoke(Object target, String name) throws Exception {
        Method method = UmetateGame.class.getDeclaredMethod(name); method.setAccessible(true); method.invoke(target);
    }
}
