package jp.rai42.umetate;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A local ephemeral RFC6455 endpoint, never the user's STE bridge. */
class SteWinSinkTest {
    @Test void burstAndPacedSendExactMessagesAndCloseEachConnection() throws Exception {
        for(int interval:new int[]{0,100})try(var endpoint=new Endpoint();var sink=new SteWinSink(endpoint.uri(),interval,2000)) {
            var win=sink.send(new WinRequest("win",3));var lose=sink.send(new WinRequest("lose",2));
            assertTrue(win.completed(),win.error());assertEquals(3,win.sent());assertEquals(3,win.requested());assertTrue(lose.completed(),lose.error());
            assertEquals(List.of("{\"type\":\"win\"}","{\"type\":\"win\"}","{\"type\":\"win\"}","{\"type\":\"lose\"}","{\"type\":\"lose\"}"),endpoint.messages);
            assertEquals(2,endpoint.connections);assertEquals(2,endpoint.closes);
            long elapsed=TimeUnit.NANOSECONDS.toMillis(endpoint.times.get(2)-endpoint.times.get(0));
            if(interval>0)assertTrue(elapsed>=170,"paced spacing: "+elapsed);else assertTrue(elapsed<170,"burst spacing: "+elapsed);
        }
    }
    @Test void onlyWebsocketUrisAllowedAndClosedSinkNeverConnects() throws Exception {
        for(String url:List.of("http://127.0.0.1:1","ws://u:p@localhost:1","ws://localhost:1/#bad"))
            assertThrows(IllegalArgumentException.class,()->new SteWinSink(URI.create(url),0,100));
        try(var endpoint=new Endpoint()) {
            var sink=new SteWinSink(endpoint.uri(),0,100);sink.close();var result=sink.send(new WinRequest("win",1));
            assertFalse(result.completed());assertEquals(0,result.sent());assertEquals(0,endpoint.connections);
            Thread.interrupted(); // send preserves the stop interruption, as does the original transport.
        }
    }
    static final class Endpoint implements AutoCloseable {
        final ServerSocket server=new ServerSocket(0,10,InetAddress.getLoopbackAddress());
        final List<String> messages=new CopyOnWriteArrayList<>();final List<Long> times=new CopyOnWriteArrayList<>();
        volatile int connections,closes;volatile Throwable failure;volatile boolean stopped;
        final Thread thread;
        Endpoint() throws IOException {thread=new Thread(this::run,"fake-ste-endpoint");thread.setDaemon(true);thread.start();}
        URI uri(){return URI.create("ws://127.0.0.1:"+server.getLocalPort());}
        void run(){try {while(!stopped)try(Socket s=server.accept()) {connections++;s.setSoTimeout(4000);var in=s.getInputStream();var out=s.getOutputStream();
            ByteArrayOutputStream header=new ByteArrayOutputStream();int value;
            while((value=in.read())!=-1){header.write(value);if(header.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n"))break;}
            String key=header.toString(StandardCharsets.US_ASCII).lines().filter(l->l.toLowerCase(Locale.ROOT).startsWith("sec-websocket-key:")).findFirst().orElseThrow().split(":",2)[1].trim();
            String accept=Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "+accept+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.flush();
            while(true){int first=in.read();if(first<0)break;int second=in.read();int length=second&127;if(length==126)length=(in.read()<<8)|in.read();if(length==127)throw new IOException("unexpected large frame");
                byte[] mask=(second&128)!=0?in.readNBytes(4):new byte[0];byte[] body=in.readNBytes(length);for(int i=0;i<body.length && mask.length>0;i++)body[i]^=mask[i%4];
                int op=first&15;if(op==8){closes++;out.write(0x88);out.write(body.length);out.write(body);out.flush();break;}
                if(op==1){messages.add(new String(body,StandardCharsets.UTF_8));times.add(System.nanoTime());}
            }
        }}catch(Throwable e){if(!stopped)failure=e;}}
        public void close() throws Exception {stopped=true;server.close();thread.join(5000);assertNull(failure);assertFalse(thread.isAlive());}
    }
}
