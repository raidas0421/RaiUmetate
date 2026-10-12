package jp.rai42.umetate;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
public final class WinRequestedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final int delta;
    private final WinService.Reason reason;
    public WinRequestedEvent(int delta, WinService.Reason reason) { this.delta=delta; this.reason=reason; }
    public int getDelta() { return delta; }
    public WinService.Reason getReason() { return reason; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
