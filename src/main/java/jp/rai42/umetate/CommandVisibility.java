package jp.rai42.umetate;
import java.util.Locale;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerCommandSendEvent;
import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;
public final class CommandVisibility implements Listener {
    static boolean hidden(String name) {
        String n=name.toLowerCase(Locale.ROOT);if(n.startsWith("/")) n=n.substring(1);
        n=n.split(" ",2)[0]; return n.startsWith("raiumetate:") || n.equals("rwin");
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void commands(PlayerCommandSendEvent e){e.getCommands().removeIf(CommandVisibility::hidden);}
    @EventHandler(priority=EventPriority.HIGHEST)
    public void complete(AsyncTabCompleteEvent e){if(e.isCommand())e.getCompletions().removeIf(CommandVisibility::hidden);}
}
