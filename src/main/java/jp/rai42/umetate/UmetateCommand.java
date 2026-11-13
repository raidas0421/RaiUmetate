package jp.rai42.umetate;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;

public final class UmetateCommand implements TabExecutor {
    private final UmetateGame game;
    private static final LinkedHashMap<String, String> HELP = new LinkedHashMap<>();
    static {
        HELP.put("doubletime <start [秒] [倍率]|<秒> [倍率]|stop|add <秒>|status>", "妨害倍率タイマーを開始（発動中は秒を追加）/操作");
        HELP.put("create [直径] [高さ]", "現在位置に箱を作成（既定11・9）");
        HELP.put("fill [層数]", "全体／指定層数分を直接配置");
        HELP.put("fillblock <個数>", "指定個数を直接配置");
        HELP.put("fillfoun [層数]", "全体／指定層数分を噴水で追加");
        HELP.put("fillblockfoun <個数>", "指定個数を噴水で追加");
        HELP.put("clear [1/2]", "箱の中を空にする（既定はエンダードラゴンが通過して消す。1: 即時、2: ドラゴン）");
        HELP.put("delete", "箱を削除し作成前の地形に戻す");
        HELP.put("stop", "勝利タイマーの自動開始を停止");
        HELP.put("start", "勝利タイマーを再開");
        HELP.put("timer <秒|add <秒>|reset>", "勝利カウントの秒数を変更・増減・設定値に戻す（1～3600）");
        HELP.put("tnt [個数] [名前]", "指定数の名前付きTNTを落とす（1～100、省略時1）");
        HELP.put("tntrocket [回数] [名前]", "足元の連続TNTで打ち上げ（既定20回）");
        HELP.put("randomtnt [名前]", "ランダムな強さのTNT");
        HELP.put("supertnt <個数> <強さ> [名前]", "強化TNT（強さ1～20）");
        HELP.put("meteor [名前]", "巨大隕石を落とす");
        HELP.put("comets <秒> <間隔tick> [名前]", "彗星の雨（20tick=1秒）");
        HELP.put("zeustnt [名前]", "雷神TNT");
        HELP.put("tntring [名前]", "外周のTNTが同時爆発");
        HELP.put("alltnt [名前]", "箱の中身をすべてTNTブロックに置き換える（点火はしない）");
        HELP.put("blackhole <秒>", "周囲のプレイヤーを引き寄せて爆発");
        HELP.put("enderman <体数> [名前]", "15秒間ブロックを盗む");
        HELP.put("glass_prison <秒>", "メインプレイヤーをガラスの牢獄に拘束");
        HELP.put("freeze <秒>", "氷結：手置き・噴水を一時停止");
        HELP.put("repair <個数>", "修復波：空きマスへ噴水で追加");
        HELP.put("shield <秒>", "指定秒数、岩盤のふたで上からのTNTを防ぐ");
        HELP.put("glass", "外壁・床をガラスにする");
        HELP.put("wood", "外壁・床を木材にする");
        HELP.put("rock", "外壁・床を岩盤にする");
        HELP.put("edit", "外壁・床の手動編集を切替");
        HELP.put("autoreplace", "設置ブロックを層の材質へ変換するか切替");
        HELP.put("fireworks", "爆発時の花火演出を切替");
        HELP.put("toplock", "箱より上への設置制限を切替");
        HELP.put("tp", "箱の縁に移動");
        HELP.put("layer <層数> <素材>", "下から指定層数の素材を変更");
        HELP.put("color <cancel|win> <色>", "中断・勝利の表示色を変更");
        HELP.put("heightup <段数>", "高さを増やす");
        HELP.put("heightdown <段数>", "高さを減らす");
        HELP.put("diameter <直径>", "直径を奇数で指定");
        HELP.put("diameterup <偶数>", "直径を増やす");
        HELP.put("diameterdown <偶数>", "直径を減らす");
        HELP.put("diametermax", "直径を最大にする");
        HELP.put("diametermin", "直径を最小にする");
        HELP.put("heightmax", "高さを最大にする");
        HELP.put("heightmin", "高さを最小にする");
        HELP.put("sizemax", "直径・高さを最大にする");
        HELP.put("sizemin", "直径・高さを最小にする");
        HELP.put("salmon <数> [秒数] [威力] [名前]", "爆発シャケ（既定5秒・威力3）");
        HELP.put("alley <ブロック数> [名前]", "お助けアレイ（空きマスを下から埋める。残り0で消える）");
        HELP.put("dopa [名前|info|sim [回数] [当たり率]]", "ドパガチャ（Rai Live の演出）。当たると命中の瞬間に Win −7（config の dopa.win-delta）。infoは確率の表示、simは抽選だけの試し（Winは減らさない）");
        HELP.put("sizereset", "枠を設定上の初期サイズに戻す（既定11・9）");
        HELP.put("set_main_user <プレイヤー名>", "コンソールからの操作対象を設定");
        HELP.put("remove_main_user", "操作対象を解除");
        HELP.put("status", "埋め立て数・追加待ち・勝利数などを表示");
        HELP.put("reach <距離>", "届く距離を0～64で指定（0は通常）");
        HELP.put("hp [reset|heal <数>|limit <set|add> <数>]", "枠HPを表示・変更");
    }
    UmetateCommand(UmetateGame game) { this.game = game; }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("raiumetate.admin")) { error(sender, "この操作には管理者権限が必要です。"); return true; }
        if (command != null && command.getName().equalsIgnoreCase("hp")) {
            String[] nested=new String[args.length+1];nested[0]="hp";System.arraycopy(args,0,nested,1,args.length);args=nested;
        }
        try {
            String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("help")) { help(sender, args.length == 0 ? new String[]{"help"} : args); return true; }
            if (sub.equals("hp")) {
                // The frame-health parser is strict about argument counts: pass it the command without the name.
                int fixed = args.length < 2 ? 1 : switch (args[1].toLowerCase(Locale.ROOT)) { case "reset" -> 2; case "heal" -> 3; case "limit" -> 4; default -> args.length; };
                game.hp(sender, Arrays.copyOf(args, Math.min(fixed, args.length))); game.note("hp", name(args, fixed)); return true;
            }
            if (sub.equals("doubletime")) { doubleTime(sender,args); return true; }
            if (sub.equals("dopa")) { dopa(sender, args); return true; }
            if (game.busy()) throw new IllegalArgumentException("箱の更新・枠の再生成中、または保存エラーで停止中です。しばらく待つかサーバーログを確認してください。");
            if (!sub.equals("create")) game.require();
            switch (sub) {
                case "create" -> {
                    int n = numbers(args, 1, 2); String who = name(args, 1 + n);
                    game.create(player(sender), n >= 1 ? (game.sizes().capDiameter(sizeNumber(args[1]))+1)/2 : game.defaultRadius(), n == 2 ? game.sizes().capHeight(sizeNumber(args[2])) : game.defaultHeight());
                    game.note("create", who); ok(sender, "箱の作成を開始しました。");
                }
                case "fill", "fillfoun" -> {
                    int n = numbers(args, 1, 1); String who = name(args, 1 + n);
                    if (n == 0) game.fillAll(sub.equals("fill"));
                    else game.add((int)Math.min(Integer.MAX_VALUE, (long)integer(args, 1, 1, 1000000) * game.box().width() * game.box().width()),sub.equals("fill"));
                    game.note(sub, who);
                }
                case "fillblock", "fillblockfoun" -> { atLeast(args,2);game.add(integer(args,1,1,1000000),sub.equals("fillblock"));game.note(sub, name(args, 2)); }
                case "repair" -> { atLeast(args, 2); int added = game.repair(integer(args, 1, 1, 1000000)); game.note("repair", name(args, 2)); ok(sender, "修復波に " + added + " ブロックを追加しました。"); }
                case "clear" -> {
                    int n = numbers(args, 1, 1); String who = name(args, 1 + n);
                    if (n == 0) game.clear(); else game.clear(integer(args, 1, 1, 2) == 2, who);
                    game.note("clear", who); ok(sender, "箱を空にします。");
                }
                case "delete" -> { game.delete(); game.note("delete", name(args, 1)); ok(sender, "箱を削除し地形を復元します。"); }
                case "stop" -> { game.pause(); game.note("stop", name(args, 1)); ok(sender, "勝利タイマーを停止しました。startで再開します。"); }
                case "start" -> { game.resume(); game.note("start", name(args, 1)); ok(sender, "勝利タイマーを再開しました。"); }
                case "timer" -> {
                    atLeast(args, 2); int seconds;
                    switch (args[1].toLowerCase(Locale.ROOT)) {
                        case "add" -> { atLeast(args, 3); seconds = game.timerAdd(integer(args, 2, Integer.MIN_VALUE, Integer.MAX_VALUE)); game.note("timer add", name(args, 3)); }
                        case "reset" -> { seconds = game.timerReset(); game.note("timer reset", name(args, 2)); }
                        default -> { seconds = game.timer(integer(args, 1, 1, 3600)); game.note("timer", name(args, 2)); }
                    }
                    ok(sender, "勝利カウントを " + seconds + " 秒にしました。");
                }
                case "tnt" -> {
                    int n = numbers(args, 1, 1);
                    int count = n == 1 ? integer(args, 1, 1, 100) : 1;
                    game.normalTnt(count, name(args, 1 + n));
                    ok(sender, "TNTの処理を受け付けました。");
                }
                case "salmon" -> {
                    atLeast(args, 2); int n = numbers(args, 2, 2);
                    game.salmon(integer(args,1,1,50),n>=1?integer(args,2,1,300):5,
                            n>=2?decimal(args[3],.1,20):3,name(args,2+n));
                    ok(sender,"爆発シャケを召喚しました。");
                }
                case "alley" -> { atLeast(args, 2); game.alley(integer(args, 1, 1, UmetateGame.ALLEY_MAX_BLOCKS), name(args, 2)); ok(sender, "お助けアレイの処理を受け付けました。"); }
                case "tntrocket" -> { int n = numbers(args, 1, 1); game.tntRocket(n==1?integer(args,1,1,100):20, name(args, 1 + n)); }
                case "randomtnt" -> { game.randomTnt(name(args, 1)); ok(sender, "ランダムTNTの処理を受け付けました。"); }
                case "supertnt" -> { atLeast(args, 3); game.tnt(integer(args, 1, 1, 100), decimal(args[2], 1, 20), name(args, 3), "強化TNT"); ok(sender, "強化TNTの処理を受け付けました。"); }
                case "meteor" -> { game.meteor(name(args, 1)); ok(sender, "隕石の処理を受け付けました。"); }
                case "comets" -> { atLeast(args, 3); game.comets(integer(args, 1, 1, 300), integer(args, 2, 2, 1200), name(args, 3)); ok(sender, "彗星の処理を受け付けました。"); }
                case "zeustnt" -> { game.zeus(name(args, 1)); ok(sender, "雷神TNTの処理を受け付けました。"); }
                case "tntring" -> { game.ring(name(args, 1)); ok(sender, "TNTリングの処理を受け付けました。"); }
                case "alltnt" -> { game.tntify(name(args, 1)); ok(sender, "箱の中身をTNTに置き換えます。"); }
                case "blackhole" -> { atLeast(args, 2); game.blackhole(integer(args, 1, 1, 120)); game.note("blackhole", name(args, 2)); ok(sender, "ブラックホールの処理を受け付けました。"); }
                case "enderman" -> { atLeast(args, 2); game.endermen(integer(args, 1, 1, 50), name(args, 2)); ok(sender, "エンダーマンの処理を受け付けました。"); }
                case "glass_prison" -> { atLeast(args, 2); game.prison(integer(args, 1, 1, 300)); game.note("glass_prison", name(args, 2)); ok(sender, "牢獄の処理を受け付けました。"); }
                case "freeze" -> { atLeast(args, 2); game.freeze(integer(args, 1, 1, 300)); game.note("freeze", name(args, 2)); ok(sender, "氷結の処理を受け付けました。"); }
                case "shield" -> { atLeast(args, 2); game.shield(integer(args, 1, 1, 3600)); game.note("shield", name(args, 2)); ok(sender, "岩盤のふたの効果時間を追加しました。"); }
                case "glass", "wood", "rock" -> {
                    game.wall(switch (sub) { case "glass" -> Material.GLASS; case "wood" -> Material.OAK_PLANKS; default -> Material.BEDROCK; });
                    game.note(sub, name(args, 1)); ok(sender, "外壁と床を変更します。");
                }
                case "edit", "autoreplace", "fireworks", "toplock" -> { boolean on = game.toggle(sub); game.note(sub, name(args, 1)); ok(sender, switch (sub) { case "edit" -> "外壁と床の編集"; case "autoreplace" -> "層素材への自動変換"; case "fireworks" -> "花火演出"; default -> "上部設置ロック"; } + "：" + (on ? "有効" : "無効")); }
                case "tp" -> { game.teleport(player(sender)); game.note("tp", name(args, 1)); ok(sender, "箱の縁へ移動しました。"); }
                case "layer" -> {
                    atLeast(args, 3); Material material = Material.matchMaterial(args[2]);
                    if (material == null) throw new IllegalArgumentException("素材が見つかりません。例: QUARTZ_BLOCK");
                    game.layer(integer(args, 1, 1, game.box().height()), material); game.note("layer", name(args, 3)); ok(sender, "層の材質を変更します。");
                }
                case "color" -> { atLeast(args, 3); game.color(args[1].toLowerCase(Locale.ROOT), args[2]); game.note("color", name(args, 3)); ok(sender, "表示色を変更しました。"); }
                case "sizereset" -> {
                    game.resize(game.defaultRadius(), game.defaultHeight());
                    game.note("sizereset", name(args, 1)); ok(sender, "枠を初期サイズへ戻します。");
                }
                case "diameter" -> { atLeast(args,2);game.resize((game.sizes().capDiameter(sizeNumber(args[1]))+1)/2,game.box().height());game.note("diameter", name(args, 2)); }
                case "heightup", "heightdown", "diameterup", "diameterdown" -> {
                    atLeast(args,2);var n=sizeNumber(args[1]);if(n.signum()<=0)throw new IllegalArgumentException("増減は正の整数で指定してください。");
                    Box b=game.box();SizeRules limits=game.sizes();
                    var d=java.math.BigInteger.valueOf(b.width());var h=java.math.BigInteger.valueOf(b.height());
                    switch(sub) {
                        case "diameterup" -> d=d.add(n);
                        case "diameterdown" -> d=d.subtract(n);
                        case "heightup" -> h=h.add(n);
                        case "heightdown" -> h=h.subtract(n);
                    }
                    // Values beyond the limit saturate first, even if the requested width is even.
                    int width=limits.capDiameter(d),height=limits.capHeight(h);
                    game.resize((width+1)/2,height);game.note(sub, name(args, 2));
                }
                case "diametermax", "diametermin", "heightmax", "heightmin", "sizemax", "sizemin" -> {
                    SizeRules limits=game.sizes();Box b=game.box();
                    boolean max=sub.endsWith("max");
                    int d=sub.startsWith("height")?b.width():max?limits.maxDiameter():limits.minDiameter();
                    int h=sub.startsWith("diameter")?b.height():max?limits.maxHeight():limits.minHeight();
                    game.resize((d+1)/2,h);game.note(sub, name(args, 1));
                }
                case "set_main_user" -> {
                    atLeast(args, 2); Player target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) throw new IllegalArgumentException("オンラインのプレイヤー名を指定してください。");
                    game.mainUser(target); game.note("set_main_user", name(args, 2)); ok(sender, "操作対象を " + target.getName() + " に変更しました。");
                }
                case "remove_main_user" -> { game.mainUser(null); game.note("remove_main_user", name(args, 1)); ok(sender, "操作対象を解除しました。"); }
                case "reach" -> { atLeast(args, 2); game.reach(decimal(args[1], 0, 64)); game.note("reach", name(args, 2)); ok(sender, "設置距離を変更しました（0は通常）。"); }
                case "status" -> reply(sender, game.status());
                default -> throw new IllegalArgumentException("不明な操作です。 /" + label + " help で確認してください。");
            }
        } catch (IllegalArgumentException e) { error(sender, e.getMessage()); }
        return true;
    }
    private void doubleTime(CommandSender sender,String[] args) {
        atLeast(args,2);DoubleTimeManager manager=game.doubleTime();
        if(numbers(args,1,1)==1){startOrExtend(args,1);return;}
        switch(args[1].toLowerCase(Locale.ROOT)) {
            case "start" -> startOrExtend(args,2);
            case "stop" -> {manager.requireActive();manager.stop(DoubleTimeManager.Reason.STOPPED);game.note("doubletime stop",name(args,2));}
            case "add" -> {atLeast(args,3);manager.extend(integer(args,2,Integer.MIN_VALUE,Integer.MAX_VALUE));game.note("doubletime add",name(args,3));}
            case "status" -> reply(sender,manager.isActive()?"妨害"+manager.getMultiplier()+"倍タイム 残り "+manager.snapshot().remainingSeconds()+"秒":"妨害2倍タイムは発動していません");
            default -> throw new IllegalArgumentException("doubletime start [秒] [倍率] / <秒> / stop / add / status を指定してください。");
        }
    }
    private void startOrExtend(String[] args,int from) {
        DoubleTimeManager manager=game.doubleTime();
        int n=numbers(args,from,2);
        int seconds=n>=1?integer(args,from,1,manager.maxSeconds()):manager.defaultSeconds();
        int factor=n==2?integer(args,from+1,2,manager.maxMultiplier()):manager.defaultMultiplier();
        boolean started=manager.startOrExtend(seconds,factor);
        game.note(started?"doubletime start":"doubletime start(add)",name(args,from+n));
    }
    private Player player(CommandSender sender) {
        if (sender instanceof Player p) return p;
        Player p = game.main();
        if (p == null) throw new IllegalArgumentException("プレイヤーから実行するか、set_main_user で対象を設定してください。");
        return p;
    }
    private static void range(String[] args, int min, int max) {
        if (args.length < min || args.length > max) throw new IllegalArgumentException("引数の数が違います。 /umetate help で使い方を確認してください。");
    }
    /** Required arguments only; anything after them is the triggering name (STE {nickname}). */
    private static void atLeast(String[] args, int min) {
        if (args.length < min) throw new IllegalArgumentException("引数の数が違います。 /umetate help で使い方を確認してください。");
    }
    /** How many of the optional arguments from index from are numbers; the name starts after them. */
    static int numbers(String[] args, int from, int max) {
        int n = 0;
        while (n < max && from + n < args.length && args[from + n].matches("[+-]?[0-9]+(?:\\.[0-9]+)?")) n++;
        return n;
    }
    private static java.math.BigInteger sizeNumber(String text) {
        if(!text.matches("[+]?[0-9]+"))throw new IllegalArgumentException("サイズは正の整数で指定してください。");
        return new java.math.BigInteger(text);
    }
    static int integer(String[] args, int index, int min, int max) {
        try { int n = Integer.parseInt(args[index]); if (n < min || n > max) throw new NumberFormatException(); return n; }
        catch (NumberFormatException | ArrayIndexOutOfBoundsException e) { throw new IllegalArgumentException("数値は " + min + "～" + max + " の整数で指定してください。"); }
    }
    static double decimal(String text, double min, double max) {
        try { double n = Double.parseDouble(text); if (!Double.isFinite(n) || n < min || n > max) throw new NumberFormatException(); return n; }
        catch (NumberFormatException e) { throw new IllegalArgumentException("強さは " + min + "～" + max + " で指定してください。"); }
    }
    /** 箱が無くても動く（Win は WinService、演出は Rai Live）。 */
    private void dopa(CommandSender sender, String[] args) {
        String first = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        if (first.equals("info")) { for (String l : game.dopaService().info()) replyLegacy(sender, l); return; }
        if (first.equals("sim")) {
            int n = args.length > 2 ? integer(args, 2, 1, 10_000_000) : 10000;
            Double chance = null;
            if (args.length > 3) {
                chance = Dopa.parseChance(args[3]);
                if (chance == null || chance < 0 || chance > 1) throw new IllegalArgumentException("当たり率は 0～1 の数字か 1/10 の形で指定してください: " + args[3]);
            }
            for (String l : game.dopaService().sim(n, chance)) replyLegacy(sender, l);
            return;
        }
        String who = name(args, 1);
        game.dopa(who);
        game.note("dopa", who);
    }
    private String name(String[] args, int from) {
        String s = String.join(" ", Arrays.copyOfRange(args, Math.min(from, args.length), args.length)).replaceAll("[\\p{Cntrl}§]", "");
        return s.substring(0, Math.min(48, s.length()));
    }
    private void help(CommandSender sender, String[] args) {
        range(args, 1, 2);
        int pages = (HELP.size() + 9) / 10;
        int page = args.length == 2 ? integer(args, 1, 1, pages) : 1;
        reply(sender, "操作一覧 " + page + "/" + pages + "（/umetate help <ページ>）");
        reply(sender, "どの操作も最後に名前（STEの{nickname}など）を付けられます。召喚する妨害はその名前を表示し、それ以外はログに記録します。名前が数字だけのときは、省略できる数値も書いてください。");
        HELP.entrySet().stream().skip((long) (page - 1) * 10).limit(10)
                .forEach(e -> sender.sendMessage(Component.text("/umetate " + e.getKey() + " — " + e.getValue(), NamedTextColor.YELLOW)));
    }
    private void replyLegacy(CommandSender s, String text) {
        s.sendMessage(Component.text("[埋め立て] ", NamedTextColor.AQUA).append(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().deserialize(text)));
    }
    private void ok(CommandSender s, String text) { if (!(s instanceof Player)) reply(s, text); }
    private void reply(CommandSender s, String text) { s.sendMessage(Component.text("[埋め立て] " + text, NamedTextColor.AQUA)); }
    private void error(CommandSender s, String text) { s.sendMessage(Component.text("[埋め立て] " + text, NamedTextColor.RED)); }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("raiumetate.admin")) return List.of();
        if(command!=null && command.getName().equalsIgnoreCase("hp")) {
            String[] nested=new String[args.length+1];nested[0]="hp";System.arraycopy(args,0,nested,1,args.length);args=nested;
        }
        List<String> choices = new ArrayList<>();
        if (args.length == 1) { choices.add("help"); HELP.keySet().forEach(k -> choices.add(k.split(" ")[0])); }
        else if (args.length == 2 && args[0].equalsIgnoreCase("dopa")) choices.addAll(List.of("info", "sim"));
        else if(args.length==2 && args[0].equalsIgnoreCase("doubletime"))choices.addAll(List.of("start","stop","add","status"));
        else if(args.length==3 && args[0].equalsIgnoreCase("doubletime") && (args[1].equalsIgnoreCase("start") || args[1].equalsIgnoreCase("add")))choices.addAll(List.of("30","60","120"));
        else if (args.length == 2 && args[0].equalsIgnoreCase("timer")) choices.addAll(List.of("add", "reset", "5", "10", "15", "20"));
        else if (args.length == 3 && args[0].equalsIgnoreCase("timer") && args[1].equalsIgnoreCase("add")) choices.addAll(List.of("5", "10", "-5", "-10"));
        else if (args.length == 2 && args[0].equalsIgnoreCase("set_main_user")) Bukkit.getOnlinePlayers().forEach(p -> choices.add(p.getName()));
        else if (args.length == 2 && args[0].equalsIgnoreCase("color")) choices.addAll(List.of("win", "cancel"));
        else if (args.length == 2 && args[0].equalsIgnoreCase("hp")) choices.addAll(List.of("heal","reset","limit"));
        else if (args.length == 3 && args[0].equalsIgnoreCase("hp") && args[1].equalsIgnoreCase("limit")) choices.addAll(List.of("set","add"));
        else if (args.length == 3 && args[0].equalsIgnoreCase("color")) choices.addAll(NamedTextColor.NAMES.keys());
        else if (args.length == 3 && args[0].equalsIgnoreCase("layer")) for (Material m : Material.values()) if (UmetateGame.fillMaterial(m)) choices.add(m.name());
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return choices.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
