package jp.rai42.umetate;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
public final class DoubleTimeEndEvent extends Event {
 private static final HandlerList HANDLERS=new HandlerList();
 private final int multiplier; private final DoubleTimeManager.Reason reason;
 public DoubleTimeEndEvent(int multiplier, DoubleTimeManager.Reason reason){this.multiplier=multiplier;this.reason=reason;}
 public int getMultiplier(){return multiplier;} public DoubleTimeManager.Reason getReason(){return reason;}
 @Override public HandlerList getHandlers(){return HANDLERS;}
 public static HandlerList getHandlerList(){return HANDLERS;}
}
