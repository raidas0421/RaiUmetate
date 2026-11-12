package jp.rai42.umetate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DopaTest {
    private static Dopa.Config shipped() throws Exception {
        return new Dopa.Config(YamlConfiguration.loadConfiguration(Path.of("src/main/resources/config.yml").toFile()));
    }

    @Test void shippedConfigIsOneInHundredAndMinusSeven() throws Exception {
        var c = shipped();
        assertEquals(0.01, c.winChance, 1e-12);
        assertEquals(7, c.winDelta);
        assertEquals(0.975, c.smokeRate, 1e-12);
        assertEquals(0, c.loseColor[3]);
        assertTrue(c.reaches.containsAll(List.of("wither", "tnt", "ghast", "ore", "reel", "creeper", "warden")));
        assertFalse(c.reaches.contains("dragon"));
    }

    @Test void emptyConfigFallsBackToTheSameDefaults() {
        var c = new Dopa.Config(new YamlConfiguration());
        assertEquals(0.01, c.winChance, 1e-12);
        assertEquals(7, c.winDelta);
    }

    @Test void chanceAcceptsFractions() {
        assertEquals(0.01, Dopa.parseChance("1/100"), 1e-12);
        assertEquals(0.5, Dopa.parseChance(" 0.5 "), 1e-12);
        assertNull(Dopa.parseChance("1/0"));
        assertNull(Dopa.parseChance("abc"));
    }

    @Test void winCarriesTheDeltaAndNeverComesFromSmoke() throws Exception {
        var c = shipped();
        var r = new Random(1);
        for (int i = 0; i < 2000; i++) {
            var res = Dopa.draw(c, 1.0, r);
            assertTrue(res.win());
            assertEquals(7, res.n());
            assertTrue(Dopa.REACH_IDS.contains(res.reach()));
            assertTrue(res.from() <= res.stage());
            if (res.kind().equals("sakibare") || res.kind().equals("freeze")) assertEquals(3, res.stage());
        }
    }

    @Test void loseNeverCostsWinAndNeverShowsRainbow() throws Exception {
        var c = shipped();
        var r = new Random(2);
        for (int i = 0; i < 20000; i++) {
            var res = Dopa.draw(c, 0.0, r);
            assertFalse(res.win());
            assertEquals(0, res.n());
            assertTrue(res.stage() < 3);
        }
    }

    @Test void winRateIsAboutOnePercent() throws Exception {
        var c = shipped();
        var r = new Random(3);
        int wins = 0, n = 400000;
        for (int i = 0; i < n; i++) if (Dopa.draw(c, c.winChance, r).win()) wins++;
        assertEquals(0.01, wins / (double) n, 0.001);
    }

    @Test void timelineMatchesTheOtherSets() {
        // wither, normal, blue->blue (from=stage=0): 450 + 2400 + 550
        assertEquals(450 + 2400 + 550, Dopa.hitMs("wither", 0, 0, "normal"));
        // ghast rainbow climbing from blue, freeze adds 1000: 450 + 2400 + 3000 + 1000 + 500 + 400*3
        assertEquals(450 + 2400 + 3000 + 1000 + 500 + 1200, Dopa.hitMs("ghast", 3, 0, "freeze"));
        assertEquals(2000 + 500 + 450 + 2400 + 550, Dopa.hitMs("wither", 0, 0, "revive"));
        assertEquals(Dopa.SMOKE_MS, Dopa.totalMs(new Dopa.Result("smoke", 0, false, 0, 0, 0, "normal")));
        assertEquals(Dopa.hitMs("tnt", 1, 1, "normal") + Dopa.HIT_AFTER_MS, Dopa.totalMs(new Dopa.Result("tnt", 1, true, 0, 7, 1, "normal")));
    }

    @Test void jsonEscapesQuotesBackslashesAndControls() {
        assertEquals("\"a\\\"b\\\\c\"", Dopa.json("a\"b\\c"));
        assertEquals("\"ab\"", Dopa.json("a\nb"));
        assertEquals("\"太郎\"", Dopa.json("太郎"));
    }

    @Test void infoAndSimMentionTheDelta() throws Exception {
        var plugin = mock(org.bukkit.plugin.java.JavaPlugin.class);
        var yaml = YamlConfiguration.loadConfiguration(Path.of("src/main/resources/config.yml").toFile());
        when(plugin.getConfig()).thenReturn(yaml);
        var dopa = new Dopa(plugin, null, () -> null);
        assertTrue(dopa.info().stream().anyMatch(l -> l.contains("−7")));
        assertTrue(dopa.sim(1000, 1.0).get(1).contains("&f1000回"));
        assertTrue(dopa.enabled());
    }

    @Test void commandRoutesDopaWithoutABoxAndKeepsTheName() {
        var game = mock(UmetateGame.class);
        var sender = mock(Player.class);
        when(sender.hasPermission("raiumetate.admin")).thenReturn(true);
        var command = new UmetateCommand(game);
        command.onCommand(sender, null, "umetate", new String[] {"dopa", "配信", "太郎"});
        verify(game).dopa("配信 太郎");
        verify(game).note("dopa", "配信 太郎");
        verify(game, never()).require();
        command.onCommand(sender, null, "umetate", new String[] {"dopa"});
        verify(game).dopa("");
    }

    @Test void dopaIsInHelpAndTabComplete() throws Exception {
        var game = mock(UmetateGame.class);
        var sender = mock(Player.class);
        when(sender.hasPermission("raiumetate.admin")).thenReturn(true);
        var command = new UmetateCommand(game);
        assertTrue(command.onTabComplete(sender, null, "umetate", new String[] {"do"}).contains("dopa"));
        assertEquals(List.of("info", "sim"), command.onTabComplete(sender, null, "umetate", new String[] {"dopa", ""}));
        String source = Files.readString(Path.of("src/main/java/jp/rai42/umetate/UmetateCommand.java"));
        assertTrue(source.contains("HELP.put(\"dopa "));
    }
}
