package jp.rai42.umetate;

/** A win fires once per uninterrupted full box. A hole rearms the next round. */
public final class WinClock {
    public enum Event { NONE, START, CANCEL, WIN }
    private int seconds;
    private int duration;
    private int remaining = -1;
    private boolean won;
    private boolean paused;
    private boolean interrupted;
    public WinClock(int seconds) { duration(seconds); }
    /** A running count starts over from the new length; otherwise the next count uses it. */
    public void duration(int seconds) {
        if (seconds < 1 || seconds > 3600) throw new IllegalArgumentException("勝利時間は1～3600秒です。");
        this.seconds = seconds;
        duration = seconds * 20 + (seconds >= 2 ? 10 : 0);
        if (remaining >= 0) remaining = duration; else interrupted = false;
    }
    public int seconds() { return seconds; }
    public int secondsLeft() { return remaining < 0 ? -1 : remaining <= 20 ? 1 : remaining <= 50 ? 2 : (remaining - 10 + 19) / 20; }
    public boolean interrupted() { return interrupted || paused; }
    public boolean paused() { return paused; }
    public boolean won() { return won; }
    public boolean willWinNextTick() { return !paused && !won && remaining == 1; }
    public void pause() { paused = true; remaining = -1; interrupted = true; }
    public void resume() { paused = false; remaining = -1; interrupted = false; }
    public Event tick(boolean full) {
        if (!full) {
            boolean counting = remaining >= 0;
            remaining = -1; won = false; if (counting) interrupted = true;
            return counting ? Event.CANCEL : Event.NONE;
        }
        if (paused || won) return Event.NONE;
        if (remaining < 0) { remaining = duration; interrupted = false; return Event.START; }
        if (--remaining <= 0) { won = true; remaining = -1; return Event.WIN; }
        return Event.NONE;
    }
}
