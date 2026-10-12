package jp.rai42.umetate;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.bukkit.plugin.java.JavaPlugin;

final class FrameOverlayServer {
   private final JavaPlugin plugin;
   private final FrameHealth health;
   private final DoubleTimeManager doubleTime;
   private HttpServer server;
   private byte[] page;

   FrameOverlayServer(JavaPlugin plugin, FrameHealth health) { this(plugin,health,null); }
   FrameOverlayServer(JavaPlugin plugin, FrameHealth health, DoubleTimeManager doubleTime) {
      this.doubleTime=doubleTime;
      this.plugin=plugin;this.health=health;
   }
   void start() {
      if(!plugin.getConfig().getBoolean("obs-overlay.enabled",false))return;
      try(var input=plugin.getResource("frame-overlay.html")) {
         if(input==null)throw new IOException("frame-overlay.htmlがJARにありません");
         page=input.readAllBytes();
         String address=plugin.getConfig().getString("obs-overlay.bind-address","127.0.0.1");
         int port=plugin.getConfig().getInt("obs-overlay.port",17967);
         server=HttpServer.create(new InetSocketAddress(address,port),0);
         server.createContext("/",exchange->respond(exchange,"text/html; charset=utf-8",page));
         server.createContext("/state",this::state);
         server.setExecutor(null);server.start();
         plugin.getLogger().info("OBS 耐久値表示: http://"+address+":"+port+"/");
      }catch(IOException e){plugin.getLogger().warning("OBS耐久値表示を起動できません: "+e.getMessage());}
   }
   void stop(){if(server!=null){server.stop(0);server=null;}}
   private void state(HttpExchange exchange)throws IOException {
      FrameHealthSnapshot s=health.snapshot();
      String json="{\"health\":"+s.health()+",\"maximum\":"+s.maximum()+",\"lastDamage\":"+s.lastDamage()+",\"revision\":"+s.revision()+",\"status\":\""+s.status()+"\"}";
      if(doubleTime!=null){var d=doubleTime.snapshot();json=json.substring(0,json.length()-1)+",\"doubleTime\":{\"active\":"+d.active()+",\"multiplier\":"+d.multiplier()+",\"remainingSeconds\":"+d.remainingSeconds()+",\"totalSeconds\":"+d.totalSeconds()+"}}";}
      respond(exchange,"application/json; charset=utf-8",json.getBytes(StandardCharsets.UTF_8));
   }
   private void respond(HttpExchange exchange,String type,byte[] body)throws IOException {
      exchange.getResponseHeaders().set("Content-Type",type);
      exchange.getResponseHeaders().set("Cache-Control","no-store");
      exchange.getResponseHeaders().set("Access-Control-Allow-Origin","*");
      exchange.sendResponseHeaders(200,body.length);
      try(var output=exchange.getResponseBody()){output.write(body);}
   }
}
