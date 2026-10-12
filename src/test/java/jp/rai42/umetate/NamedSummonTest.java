package jp.rai42.umetate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.mockito.Mockito.*;

class NamedSummonTest {
    @TempDir Path directory;

    @Test void displaysAndTntShowTheGiftersName() throws Exception {
        try (var f = new FrameHealthTest.Fixture(directory, 10000)) {
            Box box = Box.restored(0, 0, 0, 3, 4); var game = spy(game(f, box));
            for (int i = 0; i < box.capacity(); i++) f.types.put(box.cell(i), Material.AIR);
            var display = mock(BlockDisplay.class); doReturn(display).when(game).display(any(Location.class), any(Material.class), anyFloat());
            var tnt = mock(TNTPrimed.class); doReturn(tnt).when(game).primed(any(Location.class), anyInt());
            game.meteor("太郎"); verify(display).customName(Component.text("太郎", NamedTextColor.GOLD));
            game.zeus("花子"); verify(display).customName(Component.text("花子", NamedTextColor.GOLD));
            game.meteor(""); verify(display).customName(Component.text("巨大隕石", NamedTextColor.GOLD));
            game.ring("次郎"); verify(tnt, times(12)).customName(Component.text("次郎", NamedTextColor.GOLD));
            game.randomTnt("三郎"); verify(tnt).customName(Component.text("三郎", NamedTextColor.GOLD));
            game.comets(5, 20, "四郎");
            set(game, "tick", 20); invoke(game, "effectsTick");
            verify(display).customName(Component.text("四郎", NamedTextColor.GOLD));
        }
    }
    @Test void rocketTntCarriesTheName() {
        var rocket = new TntRocket(p -> null);
        var player = mock(Player.class); var world = mock(org.bukkit.World.class); var tnt = mock(TNTPrimed.class);
        when(player.isOnline()).thenReturn(true); when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new Location(world, 0, 64, 0)); when(player.getGameMode()).thenReturn(org.bukkit.GameMode.SURVIVAL);
        when(world.spawn(any(Location.class), eq(TNTPrimed.class))).thenReturn(tnt);
        rocket.start(List.of(player), 1, 0, "太郎"); rocket.tick(1);
        verify(tnt).customName(Component.text("太郎", NamedTextColor.GOLD)); verify(tnt).setCustomNameVisible(true);
    }
    private static UmetateGame game(FrameHealthTest.Fixture f, Box box) throws Exception {
        UmetateGame game = new UmetateGame((UmetatePlugin) f.plugin);
        set(game, "world", f.world); set(game, "box", box); set(game, "fill", new FillState(box.capacity()));
        return game;
    }
    private static void set(Object target, String key, Object value) throws Exception {
        Field field = UmetateGame.class.getDeclaredField(key); field.setAccessible(true); field.set(target, value);
    }
    private static void invoke(Object target, String name) throws Exception {
        Method method = UmetateGame.class.getDeclaredMethod(name); method.setAccessible(true); method.invoke(target);
    }
}
