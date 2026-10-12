package jp.rai42.umetate;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A local ephemeral HTTP endpoint stands in for Rai Live; never the real app or VPS. */
class RaiLiveLinkTest {
    @Test void winSinkPostsDeltaAndReportsFailures() throws Exception {
        try (var endpoint = new Endpoint(200)) {
            var sink = new RaiLiveWinSink(endpoint.base(), 2000);
            var win = sink.send(new WinRequest("win", 3));
            var lose = sink.send(new WinRequest("lose", 1));
            assertTrue(win.completed(), win.error()); assertEquals(3, win.sent());
            assertTrue(lose.completed(), lose.error());
            assertEquals(List.of("/api/wins {\"delta\":3,\"source\":\"RaiUmetate\"}", "/api/wins {\"delta\":-1,\"source\":\"RaiUmetate\"}"), endpoint.requests);
        }
        try (var endpoint = new Endpoint(400)) {
            var result = new RaiLiveWinSink(endpoint.base(), 2000).send(new WinRequest("win", 1));
            assertFalse(result.completed()); assertEquals(0, result.sent()); assertTrue(result.error().contains("400"));
        }
        var closedPort = new RaiLiveWinSink(URI.create("http://127.0.0.1:1"), 500).send(new WinRequest("win", 1));
        assertFalse(closedPort.completed()); assertEquals(0, closedPort.sent());
        var stopped = new RaiLiveWinSink(URI.create("http://127.0.0.1:1"), 500); stopped.close();
        assertFalse(stopped.send(new WinRequest("win", 1)).completed());
    }

    @Test void onlyPlainHttpBaseAddressesAreAccepted() {
        assertEquals(URI.create("http://127.0.0.1:4180/api/wins"), RaiLiveLink.resolve(URI.create("http://127.0.0.1:4180"), "/api/wins"));
        assertEquals(URI.create("http://127.0.0.1:4180/x/api/signals"), RaiLiveLink.resolve(URI.create("http://127.0.0.1:4180/x/"), "/api/signals"));
        for (String bad : List.of("ws://127.0.0.1:4180", "http://u:p@127.0.0.1:4180", "http://127.0.0.1:4180/?a=1", "file:///x"))
            assertThrows(IllegalArgumentException.class, () -> RaiLiveLink.resolve(URI.create(bad), "/api/wins"), bad);
    }

    @Test void stateCarriesHealthFillSizeAndDoubleTime() {
        String json = RaiLiveLink.stateJson(new RaiLiveLink.State(8200.4, 10000, 120, 630, 1000, 11, 9, -1, 15, true, 2, 245));
        assertEquals("{\"hp\":8200,\"maxHp\":10000,\"lastDamage\":120,\"filled\":630,\"capacity\":1000,\"fillPercent\":63.0,\"diameter\":11,\"height\":9,"
            + "\"countdown\":null,\"winSeconds\":15,\"doubleTime\":{\"active\":true,\"multiplier\":2,\"secondsLeft\":245}}", json);
        assertTrue(RaiLiveLink.stateJson(new RaiLiveLink.State(0, 1, 0, 1, 3, 3, 3, 5, 10, false, 2, 0)).contains("\"fillPercent\":33.3,\"diameter\":3,\"height\":3,\"countdown\":5,\"winSeconds\":10"));
    }

    @Test void signalsAreSentInOrderOnTheirOwnThread() throws Exception {
        try (var endpoint = new Endpoint(200)) {
            var link = new RaiLiveLink(endpoint.base(), message -> { });
            link.signal("umetate.fill_complete", "{\"delta\":1}");
            link.signal("umetate.doubletime.end", null);
            for (int i = 0; i < 100 && endpoint.requests.size() < 2; i++) TimeUnit.MILLISECONDS.sleep(20);
            link.close();
            assertEquals(List.of("/api/signals {\"name\":\"umetate.fill_complete\",\"source\":\"RaiUmetate\",\"data\":{\"delta\":1}}",
                "/api/signals {\"name\":\"umetate.doubletime.end\",\"source\":\"RaiUmetate\"}"), endpoint.requests);
        }
        var logged = new CopyOnWriteArrayList<String>();
        var unreachable = new RaiLiveLink(URI.create("http://127.0.0.1:1"), logged::add);
        unreachable.signal("a", null); unreachable.signal("b", null);
        for (int i = 0; i < 200 && logged.isEmpty(); i++) TimeUnit.MILLISECONDS.sleep(20);
        TimeUnit.MILLISECONDS.sleep(300);
        unreachable.close();
        assertEquals(1, logged.size(), "an unreachable app is reported once");
    }

    static final class Endpoint implements AutoCloseable {
        final HttpServer server;
        final List<String> requests = new CopyOnWriteArrayList<>();
        Endpoint(int status) throws Exception {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> {
                requests.add(exchange.getRequestURI().getPath() + " " + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, body.length);
                try (var out = exchange.getResponseBody()) { out.write(body); }
            });
            server.start();
        }
        URI base() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }
        @Override public void close() { server.stop(0); }
    }
}
