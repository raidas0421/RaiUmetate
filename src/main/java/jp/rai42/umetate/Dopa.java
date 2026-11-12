package jp.rai42.umetate;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * ドパガチャ（/umetate dopa [名前]）。埋め立て版: 当たると命中の瞬間に Win −win-delta（既定 7）。
 * パルクール・タワーディフェンスの game/Dopa.java と同じ抽選・同じ時間割。演出は Rai Live の /obs/dopa（dia.dopa に unit=win を付けて送る）。
 *
 * 1. win-chance で当たるかを決める。当たりの見せ方（kind）は win-kind-weights（普通 先バレ フリーズ 復活）。
 *    先バレ・フリーズは虹で終わる確定演出。普通・復活の最後の色は win-color-weights（青 緑 赤 虹）。
 *    色は青から上がっていくか、最初からその色（start-final-rate）。虹は先バレ以外、必ず上がっていく。
 * 2. ハズレ: smoke-rate の割合は煙と音だけ。残りは lose-color-weights の色（虹は出さない）。青・緑は短い演出（fizzle）、赤はリーチのハズレ。
 * 3. 結果を合図 dia.dopa（reach stage from kind win lose n nick unit）で Rai Live へ送る。
 * 4. 当たりのときだけ、命中の瞬間（T_hit）に Win 数を −win-delta（WinService の lose。win.sink が rlc なら Rai Live の /api/wins、source は RaiUmetate）。ハズレは何も起きない。
 *
 * 時間割（ミリ秒）は Rai Live の web/src/obs/dopa/dopaTimeline.ts と同じ（Survival の Dopa.java とも同じ）。ここを変えるなら全部そろえる。
 */
public final class Dopa {

    // ---- Rai Live の dopaTimeline.ts と同じ数字 ----
    static final int DEVELOP_MS = 450;
    static final int CHARGE_BASE_MS = 2400;
    static final int CHARGE_PER_STAGE_MS = 1000;
    static final int HIT_AFTER_MS = 2800;
    static final int FREEZE_MS = 1000;
    static final int REVIVE_GAP_MS = 500;
    static final int SMOKE_MS = 2500;
    /** 復活の見せかけの煙（Rai Live の REVIVE_SMOKE_MS）。ふつうのハズレの煙は 2500 */
    static final int REVIVE_SMOKE_MS = 2000;
    static final int REVIVE_MS = REVIVE_SMOKE_MS + REVIVE_GAP_MS;
    static final int FIZZLE_AFTER_MS = 700;
    static final List<String> KINDS = List.of("normal", "sakibare", "freeze", "revive");

    record ReachTime(int fireBaseMs, int firePerStageMs, int[] loseAfterMs) {
    }

    static final Map<String, ReachTime> TIMES = Map.of(
            "wither", new ReachTime(550, 0, new int[] {3300, 2500, 3200}),
            "tnt", new ReachTime(450, 0, new int[] {2300, 3000, 3200}),
            "ghast", new ReachTime(500, 400, new int[] {3600, 3000, 3200}),
            "dragon", new ReachTime(900, 0, new int[] {3000, 3400}),
            "ore", new ReachTime(400, 0, new int[] {2800, 3000}),
            "reel", new ReachTime(600, 0, new int[] {2600, 2800}),
            "creeper", new ReachTime(500, 0, new int[] {2600, 3400, 3000}),
            "warden", new ReachTime(500, 450, new int[] {3000, 3600, 3200}),
            "smoke", new ReachTime(0, 0, new int[] {SMOKE_MS}),
            "fizzle", new ReachTime(0, 0, new int[] {FIZZLE_AFTER_MS}));
    static final List<String> REACH_IDS = List.of("wither", "tnt", "ghast", "dragon", "ore", "reel", "creeper", "warden");
    /** 演出ごとのハズレの数 */
    static final Map<String, Integer> LOSE_COUNTS = Map.of(
            "wither", 3, "tnt", 3, "ghast", 3, "dragon", 2, "ore", 2, "reel", 2, "creeper", 3, "warden", 3);
    private static final Map<String, String> NAMES = Map.of("wither", "ウィザー", "tnt", "TNT", "ghast", "ガスト", "dragon", "エンドラ",
            "ore", "ツルハシ", "reel", "リール", "creeper", "クリーパー", "warden", "ウォーデン");
    /** Rai Live が順番待ちにできる数（再生中は別） */
    static final int RAILIVE_WAIT_MAX = 5;
    private static final String[] COLORS = {"青", "緑", "赤", "虹"};

    /** 抽選の結果。stage は最終の色（0〜3）、from は最初の色（0〜stage）、kind は見せ方 */
    public record Result(String reach, int stage, boolean win, int lose, int n, int from, String kind) {
    }

    /** config.yml の dopa: を読んだもの（毎回 plugin.getConfig() から読む） */
    static final class Config {
        final Set<String> reaches = new HashSet<>();
        final Map<String, Double> reachWeights = new HashMap<>();
        final Map<String, double[]> loseWeights = new HashMap<>();
        final double winChance;
        final int winDelta;
        final double[] winKind;
        final double[] winColor;
        final double[] loseColor;
        final double startFinalRate;
        final double smokeRate;

        Config(FileConfiguration c) {
            for (String r : c.getStringList("dopa.enabled-reaches")) reaches.add(r.trim().toLowerCase(Locale.ROOT));
            if (!c.contains("dopa.enabled-reaches")) reaches.addAll(List.of("wither", "tnt", "ghast", "ore", "reel", "creeper", "warden"));
            winChance = chance(c, "dopa.win-chance", 1.0 / 100);
            winDelta = Math.max(0, c.getInt("dopa.win-delta", 7));
            winKind = weights(c, "dopa.win-kind-weights", new double[] {7, 1, 1, 1});
            winColor = weights(c, "dopa.win-color-weights", new double[] {8, 10, 12, 2});
            double[] lc = weights(c, "dopa.lose-color-weights", new double[] {73, 24, 3, 0});
            lc[3] = 0; // 虹は確定当たり: ハズレには出さない
            if (lc[0] + lc[1] + lc[2] <= 0) lc = new double[] {73, 24, 3, 0};
            loseColor = lc;
            startFinalRate = Math.max(0, Math.min(1, chance(c, "dopa.start-final-rate", 0.5)));
            smokeRate = Math.max(0, Math.min(1, chance(c, "dopa.smoke-rate", 0.975)));
            for (Map.Entry<String, Integer> e : LOSE_COUNTS.entrySet()) {
                reachWeights.put(e.getKey(), Math.max(0, c.getDouble("dopa.reach-weights." + e.getKey(), 1)));
                double[] equal = new double[e.getValue()];
                java.util.Arrays.fill(equal, 1);
                loseWeights.put(e.getKey(), weights(c, "dopa.reach-lose-weights." + e.getKey(), equal));
            }
        }

        double[] loseWeights(String reach) {
            return loseWeights.getOrDefault(reach, loseWeights.get("wither"));
        }
    }

    private static double[] weights(FileConfiguration c, String path, double[] def) {
        List<Double> l = c.getDoubleList(path);
        if (l.size() != def.length) return def.clone();
        double[] out = new double[def.length];
        double sum = 0;
        for (int i = 0; i < out.length; i++) sum += out[i] = Math.max(0, l.get(i));
        return sum > 0 ? out : def.clone();
    }

    /** 確率: 0.0333 のような数字、または "1/30" のような分数 */
    private static double chance(FileConfiguration c, String path, double def) {
        Object o = c.get(path);
        Double v = o instanceof Number n ? Double.valueOf(n.doubleValue()) : o == null ? null : parseChance(o.toString());
        return v == null ? def : Math.max(0, Math.min(1, v));
    }

    /** "0.5" や "1/30" を確率にする（読めなければ null） */
    public static Double parseChance(String text) {
        try {
            String t = text.trim();
            int slash = t.indexOf('/');
            if (slash < 0) return Double.parseDouble(t);
            double den = Double.parseDouble(t.substring(slash + 1).trim());
            return den == 0 ? null : Double.parseDouble(t.substring(0, slash).trim()) / den;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private final JavaPlugin plugin;
    private final WinService wins;
    /** Rai Live への合図の送り先（win.sink が rlc でないと null を返す） */
    private final java.util.function.Supplier<RaiLiveLink> link;
    /** 再生中・順番待ちのものが終わる時刻（ミリ秒、古い順） */
    private final ArrayDeque<Long> ends = new ArrayDeque<>();
    private final List<BukkitTask> pending = new ArrayList<>();

    public Dopa(JavaPlugin plugin, WinService wins, java.util.function.Supplier<RaiLiveLink> link) {
        this.plugin = plugin;
        this.wins = wins;
        this.link = link;
    }

    private Config config() {
        return new Config(plugin.getConfig());
    }

    // ---- 時間割 ----

    /** チャージの長さ: まだ上がる色の数だけ 1000ms ずつ（最初からその色なら 2400）。フリーズはその分が足される */
    static int chargeMs(int stage, int from, String kind) {
        int steps = stage - Math.min(from, stage);
        return CHARGE_BASE_MS + CHARGE_PER_STAGE_MS * steps + (kind.equals("freeze") ? FREEZE_MS : 0);
    }

    /** `T_hit`: 合図から命中までのミリ秒（復活は煙と間のぶん遅れる） */
    public static int hitMs(String reach, int stage, int from, String kind) {
        ReachTime t = TIMES.get(reach);
        return (kind.equals("revive") ? REVIVE_MS : 0) + DEVELOP_MS + chargeMs(stage, from, kind) + t.fireBaseMs() + t.firePerStageMs() * stage;
    }

    public static int hitMs(Result r) {
        return hitMs(r.reach, r.stage, r.from, r.kind);
    }

    /** 再生が終わるまでのミリ秒（当たりは命中の後、はずれは発射の位置から） */
    public static int totalMs(Result r) {
        if (r.reach.equals("smoke")) return SMOKE_MS;
        int shotAt = DEVELOP_MS + chargeMs(r.stage, r.from, r.kind);
        int[] after = TIMES.get(r.reach).loseAfterMs();
        return r.win ? hitMs(r) + HIT_AFTER_MS : shotAt + after[Math.min(r.lose, after.length - 1)];
    }

    // ---- 抽選 ----

    static Result draw(Config s, double winChance, RandomGenerator r) {
        if (r.nextDouble() < winChance) {
            String kind = KINDS.get(pick(s.winKind, r));
            String reach = pickReach(s, r);
            if (kind.equals("sakibare")) return new Result(reach, 3, true, 0, s.winDelta, 3, kind);
            if (kind.equals("freeze")) return new Result(reach, 3, true, 0, s.winDelta, pickFrom(3, s, r, true), kind);
            int stage = pick(s.winColor, r);
            return new Result(reach, stage, true, 0, s.winDelta, pickFrom(stage, s, r, stage == 3), kind);
        }
        if (r.nextDouble() < s.smokeRate) return new Result("smoke", 0, false, 0, 0, 0, "normal");
        int stage = pick(s.loseColor, r);
        if (stage <= 1) return new Result("fizzle", stage, false, 0, 0, pickFrom(stage, s, r, false), "normal");
        String reach = pickReach(s, r);
        return new Result(reach, stage, false, pick(s.loseWeights(reach), r), 0, pickFrom(stage, s, r, false), "normal");
    }

    static int pickFrom(int stage, Config s, RandomGenerator r, boolean mustClimb) {
        if (stage == 0) return 0;
        if (!mustClimb && r.nextDouble() < s.startFinalRate) return stage;
        return r.nextInt(stage);
    }

    static double[] reachWeights(Config s) {
        double[] w = new double[REACH_IDS.size()];
        for (int i = 0; i < w.length; i++) {
            String id = REACH_IDS.get(i);
            w[i] = s.reaches.contains(id) ? s.reachWeights.getOrDefault(id, 1.0) : 0;
        }
        return w;
    }

    static String pickReach(Config s, RandomGenerator r) {
        double[] w = reachWeights(s);
        double total = 0;
        for (double v : w) total += v;
        if (total <= 0) return "wither";
        return REACH_IDS.get(pick(w, r));
    }

    /** 重みで 1 つ選ぶ（重み 0 のものは選ばない） */
    static int pick(double[] weights, RandomGenerator r) {
        double total = 0;
        for (double w : weights) total += w;
        double x = r.nextDouble() * total;
        int last = 0;
        for (int i = 0; i < weights.length; i++) {
            if (weights[i] <= 0) continue;
            last = i;
            x -= weights[i];
            if (x < 0) return i;
        }
        return last;
    }

    public boolean enabled() {
        double total = 0;
        for (double v : reachWeights(config())) total += v;
        return total > 0;
    }

    // ---- 実行 ----

    /** 1 回抽選して Rai Live へ送り、当たりなら命中の時刻に Win 数を減らす。結果を返す（演出が 1 つも無ければ null） */
    public Result roll(String name) {
        Config s = config();
        double total = 0;
        for (double v : reachWeights(s)) total += v;
        if (total <= 0) return null;
        Result res = draw(s, s.winChance, ThreadLocalRandom.current());
        String who = name == null ? "" : name;

        long now = System.currentTimeMillis();
        while (!ends.isEmpty() && ends.peekFirst() <= now) ends.pollFirst();
        if (ends.size() > RAILIVE_WAIT_MAX) {
            plugin.getLogger().warning("ドパガチャが順番待ちの上限（" + RAILIVE_WAIT_MAX + "）を超えました。Rai Live で捨てられるかもしれません");
        }
        long start = ends.isEmpty() ? now : ends.peekLast();
        ends.addLast(start + totalMs(res));

        RaiLiveLink rl = link.get();
        String data = "{\"reach\":\"" + res.reach + "\",\"stage\":" + res.stage + ",\"win\":" + res.win
                + ",\"from\":" + res.from + ",\"kind\":\"" + res.kind + "\",\"unit\":\"win\",\"lose\":" + res.lose + ",\"n\":" + res.n
                + ",\"nick\":" + json(who) + "}";
        if (rl != null) rl.signal("dia.dopa", data);
        else plugin.getLogger().info("[ドパガチャ] Rai Live へは送りません（win.sink が rlc ではありません）: " + data);

        if (res.win) {
            long delayMs = Math.max(0, start + hitMs(res) - now);
            BukkitTask[] self = new BukkitTask[1];
            self[0] = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                pending.remove(self[0]);
                hit(who, res.n);
            }, Math.max(1, Math.round(delayMs / 50.0)));
            pending.add(self[0]);
        }
        return res;
    }

    /** 命中の瞬間: Win 数を −n（Rai Live の /api/wins）と、小さなタイトル */
    private void hit(String who, int n) {
        if (n > 0) {
            try {
                wins.lose(n, WinService.Reason.COMMAND);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("ドパガチャの Win −" + n + " を送れませんでした: " + e.getMessage());
            }
        }
        FileConfiguration c = plugin.getConfig();
        LegacyComponentSerializer legacy = LegacyComponentSerializer.legacyAmpersand();
        Component main = legacy.deserialize(c.getString("dopa.hit-title", "&6&lドパガチャ当たり！"));
        String sub = c.getString("dopa.hit-subtitle", "&cWin −{n}").replace("{n}", String.valueOf(n))
                + (who.isEmpty() ? "" : " &7" + who);
        Title title = Title.title(main, legacy.deserialize(sub), Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(1800), Duration.ofMillis(300)));
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(title);
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.2f);
        }
    }

    /** JSON の文字列リテラル（"…" つき）。制御文字は落とす */
    static String json(String text) {
        StringBuilder sb = new StringBuilder("\"");
        for (char ch : text.toCharArray()) {
            if (ch == '"' || ch == '\\') sb.append('\\').append(ch);
            else if (ch >= 0x20) sb.append(ch);
        }
        return sb.append('"').toString();
    }

    public void shutdown() {
        pending.forEach(BukkitTask::cancel);
        pending.clear();
        ends.clear();
    }

    // ---- info / sim ----

    private static double[] normalized(double[] w) {
        double t = 0;
        for (double v : w) t += v;
        double[] out = new double[w.length];
        for (int i = 0; i < w.length; i++) out[i] = t > 0 ? w[i] / t : 0;
        return out;
    }

    private static double pct(int a, int total) {
        return total > 0 ? a * 100.0 / total : 0;
    }

    private static String colorPercents(double[] ratio) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) sb.append(i > 0 ? " / " : "").append(String.format(Locale.ROOT, "%s %.1f%%", COLORS[i], ratio[i] * 100));
        return sb.toString();
    }

    private static String kindPercents(double[] ratio) {
        String[] names = {"普通", "先バレ", "フリーズ", "復活"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) sb.append(i > 0 ? " / " : "").append(String.format(Locale.ROOT, "%s %.1f%%", names[i], ratio[i] * 100));
        return sb.toString();
    }

    private static String oneIn(double p) {
        return p > 0 ? String.format(Locale.ROOT, "1/%.1f", 1 / p).replace(".0", "") : "0";
    }

    /** 当たりの最後の色の割合（先バレ・フリーズは虹、普通・復活は win-color-weights） */
    static double[] winFinalColors(Config s) {
        double[] k = normalized(s.winKind);
        double[] c = normalized(s.winColor);
        double[] out = new double[4];
        for (int i = 0; i < 4; i++) out[i] = (k[0] + k[3]) * c[i];
        out[3] += k[1] + k[2];
        return out;
    }

    /** /umetate dopa info の表示（config の数字から計算） */
    public List<String> info() {
        Config s = config();
        double pw = s.winChance;
        double[] wc = winFinalColors(s);
        double[] lc = normalized(s.loseColor);
        double showLose = (1 - pw) * (1 - s.smokeRate);
        List<String> out = new ArrayList<>();
        out.add("&aドパガチャ（埋め立て）" + (enabled() ? "" : " &c（無効: dopa.enabled-reaches に出せる演出がありません）"));
        double[] rw = normalized(reachWeights(s));
        StringBuilder rs = new StringBuilder();
        for (int i = 0; i < rw.length; i++) if (rw[i] > 0) rs.append(rs.length() > 0 ? " / " : "").append(String.format(Locale.ROOT, "%s %.1f%%", NAMES.get(REACH_IDS.get(i)), rw[i] * 100));
        out.add("&7出る演出の割合: &f" + (rs.length() > 0 ? rs : "なし"));
        out.add(String.format(Locale.ROOT, "&e当たり率 &f%.2f%%（%s） &7どの色でも Win &c−%d", pw * 100, oneIn(pw), s.winDelta));
        out.add("&7当たりの見せ方: &f" + kindPercents(normalized(s.winKind)) + " &7（先バレ・フリーズは必ず虹）");
        out.add("&7当たりの最後の色: &f" + colorPercents(wc) + String.format(Locale.ROOT, " &7（最初からその色で始まる割合 %.0f%%）", s.startFinalRate * 100));
        out.add(String.format(Locale.ROOT, "&7ハズレ: 煙と音だけ &f%.1f%% &7/ 演出つき &f%.1f%% &7（青・緑は短い演出、赤はリーチ）", s.smokeRate * 100, (1 - s.smokeRate) * 100));
        out.add("&7演出つきハズレの色: &f" + colorPercents(lc) + " &7（虹は出ない）");
        StringBuilder ex = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            double w = pw * wc[i];
            double p = w + showLose * lc[i];
            ex.append(i > 0 ? " / " : "").append(String.format(Locale.ROOT, "%s %.1f%%", COLORS[i], p > 0 ? w / p * 100 : 0));
        }
        out.add("&e各色の期待度（その色の演出が出たときの当たりやすさ）: &f" + ex);
        double shown = pw + showLose;
        out.add(String.format(Locale.ROOT, "&7演出が出る割合: &f%.2f%%（%s）", shown * 100, oneIn(shown)));
        out.add(String.format(Locale.ROOT, "&e1回あたりの期待値 &fWin −%.3f", pw * s.winDelta));
        return out;
    }

    /** 抽選だけを n 回まわして割合を数える（Win は減らさない・Rai Live へも送らない）。winChance が null なら config の値 */
    public List<String> sim(int n, Double winChance) {
        Config s = config();
        double pw = winChance == null ? s.winChance : winChance;
        RandomGenerator r = ThreadLocalRandom.current();
        int wins = 0, smokes = 0, rainbowLoses = 0, fizzles = 0;
        int[] winColor = new int[4], loseColor = new int[4], kinds = new int[4];
        for (int i = 0; i < n; i++) {
            Result res = draw(s, pw, r);
            if (res.reach.equals("smoke")) {
                smokes++;
                continue;
            }
            if (res.reach.equals("fizzle")) fizzles++;
            if (res.win) {
                wins++;
                winColor[res.stage]++;
                kinds[KINDS.indexOf(res.kind)]++;
            } else {
                loseColor[res.stage]++;
                if (res.stage == 3) rainbowLoses++;
            }
        }
        int showLoses = n - wins - smokes;
        int shown = wins + showLoses;
        List<String> out = new ArrayList<>();
        out.add(String.format(Locale.ROOT, "&aドパガチャ sim &f%d 回 &7（当たり率の設定 %.2f%%%s）※Win は減りません", n, pw * 100, winChance == null ? "" : "・指定した値"));
        out.add(String.format(Locale.ROOT, "&e当たり &f%d回 %.2f%% &7（%s） &71回あたり &fWin −%.3f", wins, wins * 100.0 / n, oneIn((double) wins / n), (double) wins * s.winDelta / n));
        out.add("&7当たりの最後の色: &f" + colorCounts(winColor, wins));
        double[] kd = new double[4];
        for (int i = 0; i < 4; i++) kd[i] = wins > 0 ? (double) kinds[i] / wins : 0;
        out.add("&7当たりの見せ方: &f" + kindPercents(kd));
        out.add(String.format(Locale.ROOT, "&7ハズレ: 煙だけ &f%d回 %.1f%% &7/ 演出つき &f%d回（うち短い演出 %d回）", smokes, pct(smokes, n - wins), showLoses, fizzles));
        out.add("&7演出つきハズレの色: &f" + colorCounts(loseColor, showLoses)
                + (rainbowLoses == 0 ? " &a（虹のハズレ 0 件）" : " &c（虹のハズレ " + rainbowLoses + " 件!）"));
        StringBuilder ex = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            int t = winColor[i] + loseColor[i];
            ex.append(i > 0 ? " / " : "").append(String.format(Locale.ROOT, "%s %.1f%%（%d回）", COLORS[i], pct(winColor[i], t), t));
        }
        out.add("&e各色の期待度（その色の演出が出たときの当たりやすさ）: &f" + ex);
        out.add(String.format(Locale.ROOT, "&7演出が出た回数: &f%d回 %.2f%%（%s）", shown, pct(shown, n), oneIn((double) shown / n)));
        return out;
    }

    private static String colorCounts(int[] counts, int total) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) sb.append(i > 0 ? " / " : "").append(String.format(Locale.ROOT, "%s %.1f%%", COLORS[i], pct(counts[i], total)));
        return sb.toString();
    }
}
