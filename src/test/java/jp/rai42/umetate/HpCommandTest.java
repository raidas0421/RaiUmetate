package jp.rai42.umetate;
import java.nio.file.Path;
import java.util.List;
import org.bukkit.entity.Player;
import org.bukkit.command.Command;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.text.TextComponent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class HpCommandTest {
    @TempDir Path directory;
    @Test void signedHealClampsAndResetReplies() {
        try(var f=new FrameHealthTest.Fixture(directory,100)) {
            f.health.command(f.console,new String[]{"hp","heal","-40"});assertEquals(60,f.health.snapshot().health());
            f.health.command(f.console,new String[]{"hp","heal","1000"});assertEquals(100,f.health.snapshot().health());
            f.health.command(f.console,new String[]{"hp","heal","-20"});
            f.health.command(f.console,new String[]{"hp","reset"});assertEquals(100,f.health.snapshot().health());verify(f.console,times(2)).sendMessage("§6[埋め立て] §f枠HP: 100 / 100");
        }
    }
    @Test void limitPersistsAcrossRestartWithoutHealingCurrentHp() {
        try(var f=new FrameHealthTest.Fixture(directory,100)) {
            f.health.command(f.console,new String[]{"hp","heal","-20"});
            f.health.command(f.console,new String[]{"hp","limit","set","200"});assertEquals(80,f.health.snapshot().health());
            f.health.command(f.console,new String[]{"hp","limit","add","-150"});assertEquals(50,f.health.snapshot().health());assertEquals(50,f.health.snapshot().maximum());
            f.plugin.getConfig().set("frame-health.max-health",9999);
            f.health.stop();var restarted=new FrameHealth(f.plugin,f.arena);restarted.start();assertEquals(50,restarted.snapshot().maximum());assertEquals(50,restarted.snapshot().health());restarted.stop();
        }
    }
    @Test void invalidHpOperationsDoNotChangeState() {
        try(var f=new FrameHealthTest.Fixture(directory,100)) {
            for(String[] a:List.of(new String[]{"hp","heal"},new String[]{"hp","heal","0"},new String[]{"hp","heal","1.5"},new String[]{"hp","heal","1000000001"},new String[]{"hp","limit","set","0"},new String[]{"hp","limit","add","-100"},new String[]{"hp","limit","other","1"},new String[]{"hp","reset","extra"}))
                assertThrows(IllegalArgumentException.class,()->f.health.command(f.console,a));
            assertEquals(100,f.health.snapshot().health());assertEquals(100,f.health.snapshot().maximum());
        }
    }
    @Test void zeroHpTriggersExactlyOneLossAndWorldTitle() {
        try(var f=new FrameHealthTest.Fixture(directory,100)) {
            var player=mock(Player.class);when(f.world.getPlayers()).thenReturn(List.of(player));
            f.health.command(f.console,new String[]{"hp","heal","-1000"});assertTrue(f.health.isCollapsing());
            assertThrows(IllegalArgumentException.class,()->f.health.command(f.console,new String[]{"hp","heal","-1"}));
            verify((UmetatePlugin)f.plugin).requestWin(false,WinService.Reason.FRAME_BROKEN);
            var title=org.mockito.ArgumentCaptor.forClass(Title.class);verify(player).showTitle(title.capture());
            assertEquals("-1 Win",((TextComponent)title.getValue().title()).content());assertEquals("枠が壊れた！",((TextComponent)title.getValue().subtitle()).content());
            assertEquals(250,title.getValue().times().fadeIn().toMillis());assertEquals(2000,title.getValue().times().stay().toMillis());assertEquals(500,title.getValue().times().fadeOut().toMillis());
        }
    }
    @Test void topLevelAndNestedHpUseSameHandlerAndCompletion() {
        var game=mock(UmetateGame.class);var sender=mock(Player.class);when(sender.hasPermission("raiumetate.admin")).thenReturn(true);
        var hp=mock(Command.class);when(hp.getName()).thenReturn("hp");var command=new UmetateCommand(game);
        command.onCommand(sender,hp,"hp",new String[]{"heal","-5"});command.onCommand(sender,null,"umetate",new String[]{"hp","heal","-5"});
        verify(game,times(2)).hp(eq(sender),org.mockito.AdditionalMatchers.aryEq(new String[]{"hp","heal","-5"}));
        assertEquals(List.of("heal","reset","limit"),command.onTabComplete(sender,hp,"hp",new String[]{""}));
        assertEquals(List.of("set","add"),command.onTabComplete(sender,hp,"hp",new String[]{"limit",""}));
    }
}
