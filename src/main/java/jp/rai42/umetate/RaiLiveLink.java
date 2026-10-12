package jp.rai42.umetate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Sends named signals to Rai Live (POST {base}/api/signals): the game state for its OBS display (umetate.state, only when it
 * changed, plus a heartbeat) and the game's moments (double time start/end, fill complete, frame broken).
 * Sending runs on its own thread; a failure never stops the game.
 */
public final class RaiLiveLink implements Listener, AutoCloseable {
    /** Values for Rai Live's display. Built on the main thread. */
    public record State(double hp, double maxHp, double lastDamage, int filled, int capacity, int diameter, int height, int countdown, int winSeconds,
                        boolean doubleTimeActive, int multiplier, int doubleTimeSeconds) {}

    private static final int HEARTBEAT_TICKS = 100;
    private final URI endpoint;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ThreadPoolExecutor sender = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(64),
        r -> { Thread t = new Thread(r, "RaiUmetate-RaiLive-Sender"); t.setDaemon(true); return t; }, new ThreadPoolExecutor.DiscardOldestPolicy());
    private final java.util.function.Consumer<String> log;
    private volatile boolean stopped;
    private volatile boolean failing;
    private String lastState;
    private int sinceSent;
    private BukkitTask task;

    public RaiLiveLink(URI base, java.util.function.Consumer<String> log) {
        this.endpoint = resolve(base, "/api/signals");
        this.log = log;
    }

    /** Polls the state every intervalTicks on the main thread and sends it when it changed. */
    public void start(JavaPlugin plugin, int intervalTicks, Supplier<State> state) {
        int interval = Math.max(1, intervalTicks);
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            State current;
            try { current = state.get(); } catch (RuntimeException e) { return; }
            if (current == null) return;
            String json = stateJson(current);
            sinceSent += interval;
            if (json.equals(lastState) && sinceSent < HEARTBEAT_TICKS) return;
            lastState = json; sinceSent = 0;
            signal("umetate.state", json);
        }, interval, interval);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void doubleTimeStarted(DoubleTimeStartEvent event) {
        signal("umetate.doubletime.start", "{\"multiplier\":" + event.getMultiplier() + ",\"seconds\":" + event.getSeconds() + "}");
        lastState = null;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void doubleTimeEnded(DoubleTimeEndEvent event) {
        signal("umetate.doubletime.end", "{\"multiplier\":" + event.getMultiplier() + ",\"reason\":\"" + event.getReason().name().toLowerCase(java.util.Locale.ROOT) + "\"}");
        lastState = null;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void winRequested(WinRequestedEvent event) {
        switch (event.getReason()) {
            case FILL_COMPLETE -> signal("umetate.fill_complete", "{\"delta\":" + event.getDelta() + "}");
            case FRAME_BROKEN -> signal("umetate.frame_broken", "{\"delta\":" + event.getDelta() + "}");
            default -> { }
        }
    }

    /** Queues one signal. data is a JSON object (or null). Old signals are dropped if Rai Live is unreachable for long. */
    public void signal(String name, String data) {
        if (stopped) return;
        String body = "{\"name\":\"" + name + "\",\"source\":\"RaiUmetate\"" + (data == null ? "" : ",\"data\":" + data) + "}";
        try { sender.execute(() -> post(body)); }
        catch (RejectedExecutionException ignored) { }
    }

    static String stateJson(State s) {
        double percent = s.capacity() > 0 ? Math.round(s.filled() * 1000.0 / s.capacity()) / 10.0 : 0;
        return "{\"hp\":" + Math.round(s.hp()) + ",\"maxHp\":" + Math.round(s.maxHp()) + ",\"lastDamage\":" + Math.round(s.lastDamage())
            + ",\"filled\":" + s.filled() + ",\"capacity\":" + s.capacity() + ",\"fillPercent\":" + percent
            + ",\"diameter\":" + s.diameter() + ",\"height\":" + s.height() + ",\"countdown\":" + (s.countdown() < 0 ? "null" : Integer.toString(s.countdown())) + ",\"winSeconds\":" + s.winSeconds()
            + ",\"doubleTime\":{\"active\":" + s.doubleTimeActive() + ",\"multiplier\":" + s.multiplier() + ",\"secondsLeft\":" + s.doubleTimeSeconds() + "}}";
    }

    static URI resolve(URI base, String path) {
        if (base == null || !("http".equals(base.getScheme()) || "https".equals(base.getScheme())) || base.getHost() == null
            || base.getUserInfo() != null || base.getFragment() != null || base.getQuery() != null)
            throw new IllegalArgumentException("rai-live.url は http://ホスト:ポート の形で指定してください。");
        String prefix = base.getPath() == null ? "" : base.getPath().replaceAll("/+$", "");
        return base.resolve(prefix + path);
    }

    static String shorten(String text) { return text == null ? "" : text.length() > 200 ? text.substring(0, 200) + "…" : text; }

    private void post(String body) {
        if (stopped) return;
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(3)).header("Content-Type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 == 2) { if (failing) log.accept("Rai Live への合図の送信が戻りました。"); failing = false; }
            else report("Rai Live が HTTP " + response.statusCode() + " を返しました。");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.io.IOException e) {
            report("Rai Live（" + endpoint + "）へ合図を送れません: " + e.getMessage());
        }
    }

    /** Logs once until sending works again, so an app that is not running does not flood the log. */
    private void report(String message) {
        if (failing) return;
        failing = true;
        log.accept(message);
    }

    @Override public void close() {
        stopped = true;
        if (task != null) task.cancel();
        sender.shutdownNow();
    }
}
