package jp.rai42.umetate;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** STE passes the gifter as {nickname} after any command; it must never break the command. */
class NameArgumentTest {
    private final UmetateGame game = mock(UmetateGame.class);
    private final Player sender = mock(Player.class);
    private final UmetateCommand command = new UmetateCommand(game);
    { when(sender.hasPermission("raiumetate.admin")).thenReturn(true); }

    private void run(String... args) { command.onCommand(sender, null, "umetate", args); }

    @Test void summonsCarryTheNameIncludingSpaces() {
        run("meteor", "配信", "太郎"); verify(game).meteor("配信 太郎");
        run("zeustnt", "太郎"); verify(game).zeus("太郎");
        run("tntring", "太郎"); verify(game).ring("太郎");
        run("randomtnt", "太郎"); verify(game).randomTnt("太郎");
        run("supertnt", "5", "4", "太郎"); verify(game).tnt(5, 4.0, "太郎", "強化TNT");
        run("comets", "10", "20", "太郎"); verify(game).comets(10, 20, "太郎");
        run("tntrocket", "太郎"); verify(game).tntRocket(20, "太郎");
        run("tntrocket", "5", "太郎"); verify(game).tntRocket(5, "太郎");
        run("tnt", "3", "太郎"); verify(game).normalTnt(3, "太郎");
        run("salmon", "3", "太郎"); verify(game).salmon(3, 5, 3, "太郎");
        run("salmon", "2", "10", "太郎"); verify(game).salmon(2, 10, 3, "太郎");
        run("enderman", "2", "太郎"); verify(game).endermen(2, "太郎");
        verify(sender, never()).sendMessage(any(net.kyori.adventure.text.Component.class));
    }
    @Test void otherCommandsAcceptAndLogTheName() {
        when(game.repair(anyInt())).thenReturn(5);
        run("freeze", "10", "太郎"); verify(game).freeze(10); verify(game).note("freeze", "太郎");
        run("clear", "太郎"); verify(game).clear(); verify(game).note("clear", "太郎");
        run("clear", "2", "太郎"); verify(game).clear(true, "太郎");
        run("clear", "1", "花子"); verify(game).clear(false, "花子");
        run("shield", "30", "太郎"); verify(game).shield(30);
        run("blackhole", "5", "太郎"); verify(game).blackhole(5);
        run("glass_prison", "5", "太郎"); verify(game).prison(5);
        run("repair", "10", "太郎"); verify(game).repair(10);
        run("fill", "太郎"); verify(game).fillAll(true); verify(game).note("fill", "太郎");
        run("fillblock", "50", "太郎"); verify(game).add(50, true);
        run("stop", "太郎"); verify(game).pause();
        run("start", "太郎"); verify(game).resume();
        run("timer", "30", "太郎"); verify(game).timer(30); verify(game).note("timer", "太郎");
        run("timer", "add", "-5", "花子"); verify(game).timerAdd(-5); verify(game).note("timer add", "花子");
        run("timer", "add", "10"); verify(game).timerAdd(10);
        run("timer", "reset", "次郎"); verify(game).timerReset(); verify(game).note("timer reset", "次郎");
        verify(sender, never()).sendMessage(any(net.kyori.adventure.text.Component.class));
    }
    @Test void timerCompletesItsWordsAndRejectsBadValues() {
        assertTrue(command.onTabComplete(sender, null, "umetate", new String[]{"timer", ""}).containsAll(java.util.List.of("add", "reset")));
        assertTrue(command.onTabComplete(sender, null, "umetate", new String[]{"timer", "add", ""}).contains("-5"));
        run("timer", "0"); run("timer", "3601"); run("timer", "add");
        verify(game, never()).timer(anyInt()); verify(game, never()).timerAdd(anyInt());
        verify(sender, times(3)).sendMessage(any(net.kyori.adventure.text.Component.class));
    }
    @Test void hpAndDoubleTimeIgnoreTheNameWhenParsing() {
        run("hp", "heal", "-10", "太郎");
        verify(game).hp(eq(sender), argThat(a -> java.util.Arrays.equals(a, new String[]{"hp", "heal", "-10"})));
        verify(game).note("hp", "太郎");
        var manager = mock(DoubleTimeManager.class);
        when(game.doubleTime()).thenReturn(manager);when(manager.defaultSeconds()).thenReturn(30);when(manager.defaultMultiplier()).thenReturn(2);
        when(manager.maxSeconds()).thenReturn(600);when(manager.maxMultiplier()).thenReturn(10);
        run("doubletime", "start", "太郎"); verify(manager).start(30, 2);
        run("doubletime", "start", "60", "3", "太郎"); verify(manager).start(60, 3);
    }
    @Test void numericOptionalArgumentsAreReadBeforeTheName() {
        assertEquals(2, UmetateCommand.numbers(new String[]{"salmon", "3", "10", "4.5", "太郎"}, 2, 2));
        assertEquals(0, UmetateCommand.numbers(new String[]{"tnt", "太郎"}, 1, 1));
        assertEquals(1, UmetateCommand.numbers(new String[]{"tnt", "5"}, 1, 1));
    }
}
