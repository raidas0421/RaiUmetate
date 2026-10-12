package jp.rai42.umetate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocket.Listener;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

public final class SteWinSink implements WinSink {
   private final URI endpoint;
   private final int intervalMs;
   private final int timeoutMs;
   private final HttpClient client;
   private volatile WebSocket active;
   private volatile boolean stopped;

   public SteWinSink(URI endpoint, int intervalMs, int timeoutMs) {
      if (("ws".equals(endpoint.getScheme()) || "wss".equals(endpoint.getScheme()))
         && endpoint.getHost() != null
         && endpoint.getUserInfo() == null
         && endpoint.getFragment() == null) {
         if (intervalMs >= 0 && timeoutMs >= 1) {
            this.endpoint = endpoint;
            this.intervalMs = intervalMs;
            this.timeoutMs = timeoutMs;
            this.client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis((long)timeoutMs)).build();
         } else {
            throw new IllegalArgumentException("送信間隔は0以上、タイムアウトは正の数が必要です。");
         }
      } else {
         throw new IllegalArgumentException("websocket-url は ws:// または wss:// の接続先を指定してください。");
      }
   }

   public SteWinSink.Result send(WinRequest request) {
      int sent = 0;
      WebSocket socket = null;
      CompletableFuture<WebSocket> connecting = null;
      SteWinSink.Peer peer = new SteWinSink.Peer();

      SteWinSink.Result e;
      try {
         if (this.stopped) {
            throw new InterruptedException();
         }

         connecting = this.client.newWebSocketBuilder().connectTimeout(Duration.ofMillis((long)this.timeoutMs)).buildAsync(this.endpoint, peer);
         socket = connecting.get((long)this.timeoutMs, TimeUnit.MILLISECONDS);
         this.active = socket;
         if (this.stopped) {
            throw new InterruptedException();
         }

         Thread.sleep(300L);

         for (int i = 0; i < request.count(); i++) {
            this.checkOpen(socket, peer);
            socket.sendText(request.payload(), true).get((long)this.timeoutMs, TimeUnit.MILLISECONDS);
            sent++;
            if (this.intervalMs > 0) {
               Thread.sleep((long)this.intervalMs);
            }
         }

         if (this.intervalMs == 0) {
            Thread.sleep(150L);
         }

         this.checkOpen(socket, peer);
         return new SteWinSink.Result(true, sent, request.count(), "");
      } catch (InterruptedException var24) {
         Thread.currentThread().interrupt();
         return new SteWinSink.Result(false, sent, request.count(), "サーバー停止により送信を中止しました。");
      } catch (Exception var25) {
         e = new SteWinSink.Result(false, sent, request.count(), "STEとの接続・送信に失敗しました。起動状態、接続先、他のLauncher接続を確認してください。");
      } finally {
         if (connecting != null && !connecting.isDone()) {
            connecting.cancel(true);
         }

         if (socket != null) {
            if (!this.stopped && !Thread.currentThread().isInterrupted() && !socket.isOutputClosed()) {
               try {
                  socket.sendClose(1000, "Done").get(1000L, TimeUnit.MILLISECONDS);
                  peer.closed.get(1000L, TimeUnit.MILLISECONDS);
               } catch (InterruptedException var22) {
                  Thread.currentThread().interrupt();
               } catch (Exception var23) {
               }
            }

            socket.abort();
         }

         this.active = null;
      }

      return e;
   }

   private void checkOpen(WebSocket socket, SteWinSink.Peer peer) throws InterruptedException {
      if (this.stopped || Thread.currentThread().isInterrupted()) {
         throw new InterruptedException();
      } else if (socket.isInputClosed() || socket.isOutputClosed() || peer.failed || peer.closed.isDone()) {
         throw new IllegalStateException("STE bridge disconnected");
      }
   }

   @Override
   public void close() {
      this.stopped = true;
      WebSocket socket = this.active;
      if (socket != null) {
         socket.abort();
      }

      this.client.shutdownNow();
   }

   private static final class Peer implements Listener {
      final CompletableFuture<Void> closed = new CompletableFuture<>();
      volatile boolean failed;

      @Override
      public void onOpen(WebSocket socket) {
         socket.request(1L);
      }

      @Override
      public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
         socket.request(1L);
         return null;
      }

      @Override
      public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {
         this.closed.complete(null);
         return null;
      }

      @Override
      public void onError(WebSocket socket, Throwable error) {
         this.failed = true;
         this.closed.complete(null);
      }
   }


}
