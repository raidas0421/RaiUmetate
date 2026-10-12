package jp.rai42.umetate;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
public final class DoubleTimeStartEvent extends Event {
 private static final HandlerList HANDLERS=new HandlerList();
 private final int multiplier, seconds;
 public DoubleTimeStartEvent(int multiplier, int seconds){this.multiplier=multiplier;this.seconds=seconds;}
 public int getMultiplier(){return multiplier;} public int getSeconds(){return seconds;}
 @Override public HandlerList getHandlers(){return HANDLERS;}
 public static HandlerList getHandlerList(){return HANDLERS;}
}
