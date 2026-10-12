package jp.rai42.umetate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Sends Win/Lose to Rai Live: POST {base}/api/wins with {"delta":±n,"source":"RaiUmetate"} (win.sink: rlc). */
public final class RaiLiveWinSink implements WinSink {
    private final URI endpoint;
    private final Duration timeout;
    private final HttpClient client;
    private volatile boolean stopped;

    public RaiLiveWinSink(URI base, int timeoutMs) {
        this.endpoint = RaiLiveLink.resolve(base, "/api/wins");
        if (timeoutMs < 1) throw new IllegalArgumentException("タイムアウトが不正です。");
        this.timeout = Duration.ofMillis(timeoutMs);
        this.client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public URI endpoint() { return endpoint; }

    /** One request for the whole count; Rai Live accepts -1000..1000 per request, which bounds win.ste.max-count-per-command anyway. */
    @Override public Result send(WinRequest request) {
        if (stopped) return new Result(false, 0, request.count(), "送信は停止中です。");
        int delta = request.action().equals("win") ? request.count() : -request.count();
        String body = "{\"delta\":" + delta + ",\"source\":\"RaiUmetate\"}";
        var http = HttpRequest.newBuilder(endpoint).timeout(timeout).header("Content-Type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        try {
            var response = client.send(http, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 == 2) return new Result(true, request.count(), request.count(), "");
            return new Result(false, 0, request.count(), "Rai Live が HTTP " + response.statusCode() + " を返しました: " + RaiLiveLink.shorten(response.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, 0, request.count(), "中断しました。");
        } catch (java.io.IOException e) {
            return new Result(false, 0, request.count(), "Rai Live に送れませんでした（" + endpoint + "）: " + e.getMessage());
        }
    }

    @Override public void close() { stopped = true; }
}
