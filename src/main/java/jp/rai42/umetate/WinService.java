package jp.rai42.umetate;

import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/** One bounded FIFO worker; all Bukkit callbacks are marshalled to the main thread. */
public final class WinService implements AutoCloseable {
    public enum Reason { FILL_COMPLETE, FRAME_BROKEN, COMMAND }
    private final WinSink sink;
    private final ThreadPoolExecutor worker;
    private final Consumer<Runnable> main;
    private final Consumer<WinRequestedEvent> events;
    private final Consumer<String> warning;
    private final int retries, maximum;
    private final String endpoint;
    private volatile boolean stopped;
    private java.util.function.BooleanSupplier mainThread=()->true;
    private volatile WinSink.Result last = new WinSink.Result(true,0,0,"");
    private volatile long wins, losses;

    public WinService(JavaPlugin plugin, WinSink sink, String endpoint, int retries, int maximum, int queueLimit) {
        this(sink,endpoint,retries,maximum,queueLimit,
            r -> Bukkit.getScheduler().runTask(plugin,r),
            e -> Bukkit.getPluginManager().callEvent(e), s -> plugin.getLogger().warning(s));
        mainThread=Bukkit::isPrimaryThread;
    }
    WinService(WinSink sink,String endpoint,int retries,int maximum,int queueLimit,
               Consumer<Runnable> main,Consumer<WinRequestedEvent> events,Consumer<String> warning) {
        if(retries<0 || maximum<1 || queueLimit<1) throw new IllegalArgumentException("Win送信設定が不正です。");
        this.sink=sink;this.endpoint=endpoint;this.retries=retries;this.maximum=maximum;
        this.main=main;this.events=events;this.warning=warning;
        worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(queueLimit),
            r -> {Thread t=new Thread(r,"RaiUmetate-STE-Sender");t.setDaemon(true);return t;});
    }
    public void win(int n,Reason reason) { submit(new WinRequest("win",n),reason,null); }
    public void lose(int n,Reason reason) { submit(new WinRequest("lose",n),reason,null); }
    public int maximum() { return maximum; }
    public int queued() { return worker.getQueue().size(); }
    public WinSink.Result lastResult() { return last; }
    public String status() { var r=last;return "接続先: "+endpoint+" | 送信待ち "+queued()+"件 | 直近 "+r.sent()+"/"+r.requested()+
        " ("+(r.completed()?"完了":"失敗")+") | 起動中の送信 Win "+wins+" / Lose "+losses; }
    public void submit(WinRequest request,Reason reason,CommandSender sender) {
        if(!mainThread.getAsBoolean())throw new IllegalStateException("WinServiceはメインスレッドから呼び出してください。");
        Objects.requireNonNull(reason);
        if(request.count()>maximum) throw new IllegalArgumentException("数は1～"+maximum+"です。");
        if(stopped) throw new IllegalArgumentException("Win送信は停止中です。");
        // Calls originate on Bukkit's main thread; reserve a queue slot before publishing the event.
        CompletableFuture<Void> ready=new CompletableFuture<>();
        try { worker.execute(() -> { try { ready.get(); deliver(request,sender); }
            catch(InterruptedException e){Thread.currentThread().interrupt();}
            catch(ExecutionException ignored){} }); }
        catch(RejectedExecutionException e) { throw new IllegalArgumentException("送信待ちが上限です。"); }
        try { events.accept(new WinRequestedEvent(request.action().equals("win")?request.count():-request.count(),reason)); }
        finally { ready.complete(null); }
    }
    private void deliver(WinRequest request,CommandSender sender) {
        int sent=0; boolean completed=false; String error="";
        for(int attempt=0;attempt<=retries && !stopped;attempt++) {
            WinSink.Result result;
            try { result=sink.send(new WinRequest(request.action(),request.count()-sent)); }
            catch(RuntimeException e) { result=new WinSink.Result(false,0,request.count()-sent,e.toString()); }
            if(result.sent()<0 || result.sent()>request.count()-sent) { error="送信先から不正な送信件数が返りました。";break; }
            sent+=result.sent();error=result.error();completed=result.completed();
            if(completed || sent==request.count() || Thread.currentThread().isInterrupted()) break;
        }
        if(request.action().equals("win")) wins+=sent;else losses+=sent;
        var result=new WinSink.Result(completed,sent,request.count(),error); last=result;
        if(stopped) return;
        main.accept(() -> {
            if(stopped) return;
            String message="Win送信: "+result.sent()+"/"+result.requested()+"、未送信 "+(result.requested()-result.sent())+"件";
            if(!result.completed()) warning.accept(message+"。"+result.error());
            if(sender!=null) sender.sendMessage(message+(result.completed()?"（完了）":"（失敗）"));
        });
    }
    @Override public void close() { stopped=true;worker.shutdownNow();sink.close(); }
}
