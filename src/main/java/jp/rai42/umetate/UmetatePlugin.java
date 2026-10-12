package jp.rai42.umetate;

import org.bukkit.plugin.java.JavaPlugin;
import java.util.Objects;

public final class UmetatePlugin extends JavaPlugin {
    private UmetateGame game;
    private WinService wins;
    private RaiLiveLink raiLive;
    public DoubleTimeManager doubleTimeService() { return game==null?null:game.doubleTime(); }
    public WinService winService() { return wins; }
    void requestWin(boolean win, WinService.Reason reason) {
        try { if(win) wins.win(1,reason);else wins.lose(1,reason); }
        catch(IllegalArgumentException e) { getLogger().warning("Win送信失敗: "+e.getMessage()); }
    }
    @Override public void onEnable() {
        saveDefaultConfig();
        SizeRules.migrate(getConfig());
        getConfig().options().copyDefaults(true);
        SizeRules.read(getConfig());
        getConfig().set("layers", null);
        saveConfig();
        String endpoint=getConfig().getString("win.ste.websocket-url","ws://127.0.0.1:50707");
        String mode=getConfig().getString("win.ste.send-mode","burst");
        if(!mode.equals("burst") && !mode.equals("paced"))throw new IllegalArgumentException("send-mode は burst または paced です。");
        String sinkName=getConfig().getString("win.sink","ste");
        java.net.URI raiLiveUrl=java.net.URI.create(getConfig().getString("rai-live.url","http://127.0.0.1:4180"));
        WinSink sink=switch(sinkName) {
            case "none" -> new NoopWinSink();
            case "ste" -> new SteWinSink(java.net.URI.create(endpoint), mode.equals("paced")?getConfig().getInt("win.ste.send-interval-ms",350):0,getConfig().getInt("win.ste.connect-timeout-ms",5000));
            case "rlc" -> new RaiLiveWinSink(raiLiveUrl,5000);
            default -> throw new IllegalArgumentException("win.sink は ste・rlc・none のどれかです。");
        };
        if(sink instanceof RaiLiveWinSink rlc)endpoint=rlc.endpoint().toString();
        wins=new WinService(this,sink,endpoint,getConfig().getInt("win.retry.max-attempts",3),getConfig().getInt("win.ste.max-count-per-command",1000),getConfig().getInt("win.ste.max-queued-commands",100));
        var winCommand=Objects.requireNonNull(getCommand("win"));var winHandler=new WinCommand(wins);
        winCommand.setExecutor(winHandler);winCommand.setTabCompleter(winHandler);
        getServer().getPluginManager().registerEvents(new CommandVisibility(),this);
        // Deferred detection also catches RWin loading after us now that softdepend is removed.
        getServer().getScheduler().runTask(this, () -> {
            if(getServer().getPluginManager().getPlugin("RWin")!=null)getLogger().warning("RWinは不要です。二重送信防止のため削除してください");
        });
        game = new UmetateGame(this);
        getServer().getServicesManager().register(DoubleTimeManager.class,game.doubleTime(),this,org.bukkit.plugin.ServicePriority.Normal);
        var command = Objects.requireNonNull(getCommand("umetate"));
        var handler = new UmetateCommand(game);
        command.setExecutor(handler); command.setTabCompleter(handler);
        var hp=Objects.requireNonNull(getCommand("hp")); hp.setExecutor(handler);hp.setTabCompleter(handler);
        getServer().getPluginManager().registerEvents(game, this);
        game.start();
        // With Rai Live as the Win destination, the game state and moments go there too (its OBS display).
        if(sinkName.equals("rlc")) {
            raiLive=new RaiLiveLink(raiLiveUrl,getLogger()::warning);
            getServer().getPluginManager().registerEvents(raiLive,this);
            raiLive.start(this,getConfig().getInt("rai-live.state-interval-ticks",5),game::raiLiveState);
            getLogger().info("Win と状態を Rai Live（"+raiLiveUrl+"）へ送ります。");
        }
        getLogger().info("埋め立て妨害を起動しました。 /umetate help");
    }
    @Override public void onDisable() { if(raiLive!=null) raiLive.close(); if(wins!=null) wins.close(); if (game != null) game.close(); }
}
