package jp.rai42.umetate;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;

public final class UmetateGame implements Listener, FrameArena {
    private static final String TAG = "rai_umetate_temporary";
    private final UmetatePlugin plugin;
    private Settings settings;
    private final TileGuard tileGuard=new TileGuard();
    private final Random random = new Random();
    private final RestorationMap originals = new RestorationMap();
    private final List<Flight> flights = new ArrayList<>();
    private final List<Bomb> bombs = new ArrayList<>();
    private final Deque<Blast> blasts = new ArrayDeque<>();
    private final List<Thief> thieves = new ArrayList<>();
    private final List<BlockDisplay> prisonDisplays = new ArrayList<>();
    private final List<Chunk> tickets = new ArrayList<>();
    private final Map<Integer, Material> layerOverrides = new HashMap<>();
    private final FrameHealth frameHealth;
    private final FrameOverlayServer frameOverlay;
    private final DoubleTimeManager doubleTime;
    DoubleTimeManager doubleTime(){return doubleTime;}
    /** Values for Rai Live's OBS display, or null without an arena. Main thread only. */
    RaiLiveLink.State raiLiveState() {
        if (box == null || fill == null) return null;
        var hp = frameHealth.snapshot(); var dt = doubleTime.snapshot();
        return new RaiLiveLink.State(hp.health(), hp.maximum(), hp.lastDamage(), fill.filled(), fill.capacity(), box.width(), box.height(),
            clock.secondsLeft(), clock.seconds(), dt.active(), dt.multiplier(), dt.remainingSeconds());
    }

    private World world;
    private Box box;
    private FillState fill;
    private ColumnHeights heights;
    private Box heightBox;
    private WinClock clock;
    private UUID mainUser;
    private Material wall = Material.BEDROCK;
    private boolean edit, autoreplace, fireworks, toplock;
    private Iterator<Box.Cell> structure;
    private Runnable structureDone;
    private java.util.function.Consumer<Box.Cell> structureChange;
    private boolean transitioning;
    private final ArenaStore arenaStore;
    private SaveCapture saving;
    private final Deque<java.util.concurrent.CompletableFuture<Void>> stateWrites=new ArrayDeque<>();
    private String originalGeneration;
    private long savedOriginalRevision=-1;
    private boolean roofDirty,migrateStore;
    private Map<Box.Cell,String> roofStrings=Map.of();
    private static final class SaveCapture {
        final java.util.concurrent.CompletableFuture<Void> write;
        Runnable afterWrite;
        SaveCapture(java.util.concurrent.CompletableFuture<Void> write){this.write=write;}
    }
    private Preparation preparation;
    private record Preparation(World world, java.util.function.Predicate<Box.Cell> protectedCells, Iterator<Box.Cell> cells, java.util.function.Predicate<Box.Cell> capture, Map<Box.Cell,BlockData> added, java.util.function.Consumer<Map<Box.Cell,BlockData>> ready, ChunkPreparation chunks) {}
    private static final class ChunkPreparation {
        final Set<Long> ready=new HashSet<>();final List<Chunk> acquired=new ArrayList<>();
        Box.Cell pending;java.util.concurrent.CompletableFuture<Chunk> loading;
    }
    private final Map<Material,BlockData> blockDataCache=new EnumMap<>(Material.class);
    private final Map<Material,Boolean> occludingCache=new EnumMap<>(Material.class);
    private boolean structureUpdates=true;
    private String cometName="";
    private final ArrayDeque<Integer> chainPrimes=new ArrayDeque<>();
    private int reconciling=-1;
    private Runnable reconcileDone;
    private int tick, scan, frozenUntil, prisonUntil, cometUntil, cometInterval, holeUntil;
    private BedrockLid<BlockData> lid;
    private UUID prisoner;
    private Location prisonOrigin;
    private Location holeCenter;
    private int wins;
    private boolean ioFailed;
    private int lastCountdown = -1;
    private int gravityScan;
    private Box gravityQueueBox;
    private final ArrayDeque<Integer> gravityQueue = new ArrayDeque<>();
    private final BitSet gravityQueued = new BitSet();
    private void ensureGravityQueue() {
        if(gravityQueueBox!=box){gravityQueue.clear();gravityQueued.clear();gravityQueueBox=box;}
    }
    private void queueGravity(int index, boolean urgent) {
        ensureGravityQueue();
        if(index<box.width()*box.width() || index>=fill.capacity() || gravityQueued.get(index))return;
        gravityQueued.set(index);
        if(urgent)gravityQueue.addFirst(index);else gravityQueue.addLast(index);
    }
    private final TntRocket rocket = new TntRocket(p -> p.getAttribute(org.bukkit.attribute.Attribute.EXPLOSION_KNOCKBACK_RESISTANCE));
    private final List<ExplodingSalmon> salmons = new ArrayList<>();
    private record ExplodingSalmon(Salmon entity, int expires, double power, String nickname) {}
    private static final String GRAVITY_TAG = "rai_umetate_gravity";
    private final Map<FallingBlock, GravityFall> falling = new LinkedHashMap<>();
    private record GravityFall(Box.Cell source, BlockData data, int started) {}
    private double blockReach;

    UmetateGame(UmetatePlugin plugin) {
        this.plugin = plugin;
        settings=Settings.read(plugin.getConfig());
        arenaStore=new ArenaStore(new File(plugin.getDataFolder(),"arena.yml").toPath());
        blockReach = settings.blockReach();
        if (!Double.isFinite(blockReach) || blockReach < 0 || blockReach > 64) blockReach = 20;
        autoreplace = settings.autoreplace();
        fireworks = settings.fireworks();
        toplock = settings.toplock();
        clock = new WinClock(settings.winSeconds());
        frameHealth = new FrameHealth(plugin, this);
        doubleTime = new DoubleTimeManager(plugin, () -> box==null?null:world);
        frameOverlay = new FrameOverlayServer(plugin, frameHealth, doubleTime);
    }
    void start() {
        for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) if (e.getScoreboardTags().contains(TAG) || e.getScoreboardTags().contains(TntRocket.TAG)) e.remove();
        load();
        if (box != null && !ioFailed) for (Entity e : world.getEntities()) recoverGravity(e);
        frameHealth.start();
        frameOverlay.start();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
    }
    void close() {
        doubleTime.stop(DoubleTimeManager.Reason.SHUTDOWN);
        if(saving!=null)saving.afterWrite=null;
        resetReach(); clearCountdown();
        frameOverlay.stop(); frameHealth.stop();
        cleanupEffects();
        // An interrupted structural operation keeps transitioning=true for recovery on next boot.
        if (box != null && !ioFailed) save(transitioning);
        arenaStore.close();
        saveTick();
        releaseTickets();
    }
    boolean exists() { return box != null; }
    boolean busy() { return saving != null || preparation != null || structure != null || reconciling >= 0 || ioFailed || frameHealth.isCollapsing(); }
    @Override public Optional<ArenaBounds> current() {
        if (box == null || ioFailed || structure != null || preparation != null || saving != null) return Optional.empty();
        return Optional.of(new ArenaBounds(world, box.x() - box.radius(), box.x() + box.radius(),
                box.y(), box.y() + box.height(), box.z() - box.radius(), box.z() + box.radius(), "DIAMETER", box.width(), box.height()));
    }
    @Override public void stopGimmicksForCollapse() {
        cleanupEffects(); clock.tick(false); save(transitioning);
    }
    @Override public String countLabel() { return clock.secondsLeft()>0?"カウント "+clock.secondsLeft()+"秒":clock.interrupted()?"中断":"待機中"; }
    @Override public String sizeLabel() { return box==null?"":sizes().label(box.width(),box.height()); }
    @Override public void frameRestored() { if (box != null && fill != null) beginReconcile(null); }
    /** Full opaque cube per material; soul sand and similar (opaque but lower collision) do not qualify. */
    private boolean occludes(Block b) {
        Material type=b.getType();Boolean known=occludingCache.get(type);
        if(known==null){
            boolean full=false;
            if(type.isOccluding()){var shape=b.getCollisionShape();
                if(shape!=null){var boxes=shape.getBoundingBoxes();
                    full=boxes.size()==1 && boxes.iterator().next().getVolume()>=1-1e-9;}}
            occludingCache.put(type,full);known=full;
        }
        return known;
    }
    @Override public List<double[]> occluders() {
        if(box==null || fill==null || busy())return List.of();
        int area=box.width()*box.width();List<double[]> result=new ArrayList<>();
        for(int layer=0;layer<box.height();) {
            if(!fill.allOccluding(layer*area,(layer+1)*area)){layer++;continue;}
            int start=layer;while(layer<box.height() && fill.allOccluding(layer*area,(layer+1)*area))layer++;
            result.add(new double[]{box.x()-box.radius()+1,box.y()+1+start,box.z()-box.radius()+1,box.x()+box.radius(),box.y()+1+layer,box.z()+box.radius()});
        }
        return result;
    }
    @Override public boolean shieldsExplosion(Location origin) {
        return lid != null && origin.getWorld().equals(world) && origin.getY() >= lid.topY();
    }
    void hp(org.bukkit.command.CommandSender sender, String[] args) {
        if (box == null) throw new IllegalArgumentException("先に箱を作成してください。");
        if(args.length>1 && busy())throw new IllegalArgumentException("箱の更新・枠の再生成中です。");
        frameHealth.command(sender, args);
    }
    Box box() { require(); return box; }
    SizeRules sizes() { return settings.sizes(); }
    int defaultRadius() { return (sizes().defaultDiameter()+1)/2; }
    int defaultHeight() { return sizes().defaultHeight(); }
    void reloadSettings() {
        settings=Settings.read(plugin.getConfig());
        frameHealth.reloadSettings(settings);doubleTime.reloadSettings(settings);
    }
    @Override public void persistenceFailed(RuntimeException error){saveFailed(error);}
    void require() {
        if (ioFailed) throw new IllegalArgumentException("保存ファイルを確認してください。安全のため操作を停止しています。");
        if (box == null) throw new IllegalArgumentException("先に /umetate create で箱を作成してください。");
        if (busy()) throw new IllegalArgumentException("箱を更新中です。完了するまでお待ちください。");
    }
    private Box fitSize(World w,int x,int y,int z,int radius,int height) {
        SizeRules limits=sizes();int d=limits.capDiameter(java.math.BigInteger.valueOf((long)radius*2-1));
        int h=Math.min(limits.capHeight(java.math.BigInteger.valueOf(height)),w.getMaxHeight()-y-13);
        int r=(d+1)/2;var border=w.getWorldBorder();
        while(r>=2) {
            boolean fits=true;
            for(int sx:new int[]{-1,1})for(int sz:new int[]{-1,1})if(!border.isInside(new Location(w,x+sx*r,y,z+sz*r)))fits=false;
            if(fits)break;r--;
        }
        if(r*2-1<limits.minDiameter() || h<limits.minHeight())throw new IllegalArgumentException("この場所には最小サイズの箱と演出スペースを確保できません。");
        Box fitted=new Box(x,y,z,r,h);validate(fitted,w);return fitted;
    }
    private void validate(Box candidate, World w) {
        sizes().validate(candidate.width(),candidate.height());
        validateLocation(candidate,w);
    }
    private void validateLocation(Box candidate, World w) {
        if (candidate.y() < w.getMinHeight() || candidate.y() + candidate.height() + 12 >= w.getMaxHeight())
            throw new IllegalArgumentException("この高さには箱と演出スペースを確保できません。");
        for (int sx : new int[]{-1, 1}) for (int sz : new int[]{-1, 1})
            if (!w.getWorldBorder().isInside(new Location(w, candidate.x() + sx * candidate.radius(), candidate.y(), candidate.z() + sz * candidate.radius())))
                throw new IllegalArgumentException("箱がワールド境界を超えています。");
    }
    /** The whole envelope of a box, enumerated lazily; protection checks use the box bounds. */
    private record EnvelopeCells(Box box) implements Iterable<Box.Cell> {
        public Iterator<Box.Cell> iterator() { return box.envelopeCells().iterator(); }
    }
    private EnvelopeCells cells(Box b) { return new EnvelopeCells(b); }
    private void prepare(World w, Iterable<Box.Cell> coords, java.util.function.Predicate<Box.Cell> capture,
                         java.util.function.Consumer<Map<Box.Cell,BlockData>> ready) {
        java.util.function.Predicate<Box.Cell> protection;
        if(coords instanceof ResizeCells resize)protection=c->resize.contains(c.x(),c.y(),c.z());
        else if(coords instanceof EnvelopeCells e)protection=c->e.box().envelope(c.x(),c.y(),c.z());
        else {Set<Box.Cell> set=new HashSet<>();coords.forEach(set::add);protection=set::contains;}
        preparation=new Preparation(w,protection,coords.iterator(),capture,new LinkedHashMap<>(),ready,new ChunkPreparation());
    }
    private void preparationTick() {
        Preparation p=preparation;
        try {
            int budget=boxBudget();long deadline=boxDeadline();
            while(budget-->0 && (p.chunks().pending!=null || p.cells().hasNext())) {
                ChunkPreparation chunks=p.chunks();if(chunks.pending==null)chunks.pending=p.cells().next();Box.Cell c=chunks.pending;
                long key=((long)(c.x()>>4)<<32)^((c.z()>>4)&0xffffffffL);
                if(!chunks.ready.contains(key)) {
                    // An already loaded chunk (always the case for an existing box) needs no one-tick async round trip.
                    if(chunks.loading==null && p.world().isChunkLoaded(c.x()>>4,c.z()>>4))chunks.loading=java.util.concurrent.CompletableFuture.completedFuture(p.world().getChunkAt(c.x()>>4,c.z()>>4));
                    if(chunks.loading==null){chunks.loading=p.world().getChunkAtAsync(c.x()>>4,c.z()>>4,true);return;}
                    if(!chunks.loading.isDone())return;
                    Chunk loaded=chunks.loading.getNow(null);chunks.loading=null;
                    if(!tickets.contains(loaded)){loaded.addPluginChunkTicket(plugin);tickets.add(loaded);chunks.acquired.add(loaded);}
                    chunks.ready.add(key);
                }
                chunks.pending=null;Block block=p.world().getBlockAt(c.x(),c.y(),c.z());
                if(tileGuard.isTile(block.getType()))throw new IllegalArgumentException("範囲内のチェストなどを先に移動してください。");
                if(p.capture().test(c) && !originals.containsKey(c)) {
                    BlockData prior=lid==null?null:lid.originals().get(c);
                    p.added().put(c,prior==null?block.getBlockData().clone():prior.clone());
                }
                if(System.nanoTime()>=deadline)break;
            }
            if(p.chunks().pending==null && !p.cells().hasNext()){preparation=null;p.ready().accept(p.added());}
        } catch(RuntimeException error) {
            if(preparation==null)saveFailed(error);
            for(Chunk acquired:p.chunks().acquired){acquired.removePluginChunkTicket(plugin);tickets.remove(acquired);}
            preparation=null;plugin.getLogger().warning("箱の変更を中止: "+error.getMessage());
            if(p.world()!=null)for(Player player:p.world().getPlayers())if(player.hasPermission("raiumetate.admin"))player.sendMessage("箱の変更を中止: "+error.getMessage());
        }
    }
    void create(Player player, int radius, int height) {
        if (exists() || busy()) throw new IllegalArgumentException("箱は既にあります。削除するかサイズ変更してください。");
        World w = player.getWorld();
        Box candidate = fitSize(w,player.getLocation().getBlockX(),player.getLocation().getBlockY()-1,player.getLocation().getBlockZ(),radius,height);
        Material initialWall=Material.matchMaterial(settings.boundaryMaterial());
        if(initialWall==null || !initialWall.isBlock() || !initialWall.isSolid() || initialWall.hasGravity())throw new IllegalArgumentException("外壁の初期素材が不正です。");
        var coords = cells(candidate);
        prepare(w,coords,c->true,snapshot -> {
        world = w; box = candidate; originals.putAll(snapshot); mainUser = player.getUniqueId();
        wall = initialWall;
        edit = false; layerOverrides.clear(); wins = 0;
        fill = new FillState(box.capacity()); heightBox=null; clock = new WinClock(clock.seconds());
        begin(coords, c -> block(c).setBlockData(data(box.shell(c.x(), c.y(), c.z()) ? wall : Material.AIR), false), () -> {
            frameHealth.clearArena(); frameHealth.refresh();
            teleportMain(); tell("箱を作成しました。ブロックを埋めてクリアを目指しましょう！");
        });
        });
    }
    /** Inspect, checkpoint, then apply only affected cells within the per-tick budget. */
    void resize(int radius,int height) {
        require();Box previous=box,next=fitSize(world,box.x(),box.y(),box.z(),radius,height);
        if(next.equals(previous))return;
        var affected=new ResizeCells(previous,next);
        prepare(world,affected,c->next.envelope(c.x(),c.y(),c.z()),added->{
            cleanupEffects();originals.putAll(added);
            FillState priorFill=fill;ColumnHeights priorHeights=heights;ensureHeights();priorHeights=heights;
            box=next;fill=new FillState(next.capacity());heights=new ColumnHeights(next);heightBox=next;scan=0;clock.resume();
            // Transfer cached shared cells without reading the world or allocating Cell objects.
            int width=previous.width();
            for(int i=0;i<priorFill.capacity();i++) {
                int x=previous.x()-previous.radius()+1+i%width,y=previous.y()+1+i/(width*width),z=previous.z()-previous.radius()+1+i/width%width;
                int index=next.index(x,y,z);if(index>=0){fill.set(index,priorFill.occupied(i));fill.setOccluding(index,priorFill.occluding(i));heights.update(index,priorHeights.nonAir(i));}
            }
            begin(affected,c->{
                Block target=block(c);
                if(!next.envelope(c.x(),c.y(),c.z())) {
                    BlockData original=originals.get(c);if(original!=null)target.setBlockData(original,false);
                    originals.remove(c);
                } else if(next.shell(c.x(),c.y(),c.z()))setMaterial(target,wall);
                else if(!previous.contains(c.x(),c.y(),c.z()))setMaterial(target,Material.AIR);
                else if(previous.height()!=next.height() && counts(target))setMaterial(target,material(next.index(c.x(),c.y(),c.z())));
            },()->{
                layerOverrides.keySet().removeIf(n->n>=next.height());frameHealth.refresh();
            });
        });
    }
    void delete() {
        require();
        doubleTime.stop(DoubleTimeManager.Reason.ARENA_DELETED);
        prepare(world,cells(box),c->false,unused -> {
        cleanupEffects();
        // Restore in world order for locality; anything recorded outside the envelope follows.
        List<Box.Cell> order = new ArrayList<>(originals.size());
        for (var c : box.envelopeCells()) if (originals.containsKey(c)) order.add(c);
        for (var c : originals.keySet()) if (!box.envelope(c.x(), c.y(), c.z())) order.add(c);
        begin(order, c -> block(c).setBlockData(originals.get(c), false), () -> {
            box = null; fill = null; originals.clear(); releaseTickets(); resetReach();
            frameHealth.clearArena();
            stateWrites.add(arenaStore.delete());
            tell("箱を削除し、作成前のブロックに戻しました。");
        });
        // The box disappears when done, so per-block occupancy updates are wasted work.
        structureUpdates = false;
        });
    }
    void clear() {
        require(); checkNoContainers(box.envelopeCells()); cleanupEffects();
        BlockData air = data(Material.AIR);
        begin(box.interior(0, box.capacity()), c -> { Block b = block(c); if (!b.getType().isAir()) b.setBlockData(air, false); }, () -> {
            resetOccupancy(); clock.tick(false); clock.resume(); tell("箱の中を空にしました。");
        });
        // Occupancy is rebuilt wholesale when done, so skip per-block bookkeeping.
        structureUpdates = false;
    }
    private void checkNoContainers(Iterable<Box.Cell> coords) {
        for (var c : coords) if (tileGuard.isTile(block(c).getType()))
            throw new IllegalArgumentException("範囲内のチェストなどを先に移動してください。");
    }
    void wall(Material material) {
        require();
        checkNoContainers(box.shellCells());
        wall = material;
        begin(box.shellCells(), c -> block(c).setBlockData(data(wall), false), () -> tell("外壁と床の材質を変更しました。"));
    }
    private void begin(Iterable<Box.Cell> cells, java.util.function.Consumer<Box.Cell> change, Runnable done) {
        transitioning = true;
        structure = cells.iterator(); structureChange = change; structureDone = done; structureUpdates = true;
        startSave(true);
    }
    private void pinChunks() {
        for (int x = (box.x() - box.radius()) >> 4; x <= (box.x() + box.radius()) >> 4; x++)
            for (int z = (box.z() - box.radius()) >> 4; z <= (box.z() + box.radius()) >> 4; z++) {
                Chunk c = world.getChunkAt(x, z);
                if (!tickets.contains(c)) { c.addPluginChunkTicket(plugin); tickets.add(c); }
            }
    }
    private void releaseTickets() { for (Chunk c : tickets) c.removePluginChunkTicket(plugin); tickets.clear(); }
    private File stateFile() { return new File(plugin.getDataFolder(), "arena.yml"); }
    private Map<String,Object> snapshot(boolean incomplete) {
        Map<String,Object> values=new LinkedHashMap<>();
        values.put("world",world.getUID().toString());values.put("x",box.x());values.put("y",box.y());values.put("z",box.z());
        values.put("radius",box.radius());values.put("height",box.height());values.put("transitioning",incomplete);
        values.put("main-user",mainUser==null?null:mainUser.toString());values.put("wall",wall.name());values.put("wins",wins);
        values.put("edit",edit);values.put("paused",clock.paused());
        for(var entry:layerOverrides.entrySet())values.put("layer-overrides."+entry.getKey(),entry.getValue().name());
        values.put("lid-active",lid!=null);return values;
    }
    private long workDeadline() {
        return System.nanoTime()+settings.workNanos();
    }
    /** Box-wide edits: a large per-tick budget so the whole box finishes in a few ticks without one long freeze. */
    private long boxDeadline() { return System.nanoTime()+(settings.instantBoxChanges()?settings.instantWorkNanos():settings.workNanos()); }
    private int boxBudget() { return settings.instantBoxChanges()?Integer.MAX_VALUE:settings.performanceChangesPerTick(); }
    private java.util.concurrent.CompletableFuture<Void> queueSave(boolean incomplete) {
        var values=snapshot(incomplete);RestorationData data=null;
        if(originalGeneration==null || savedOriginalRevision!=originals.revision() || roofDirty) {
            originalGeneration=UUID.randomUUID().toString();savedOriginalRevision=originals.revision();roofDirty=false;
            data=new RestorationData(originalGeneration,world.getUID().toString(),box,originals.snapshot(),roofStrings);
        }
        values.put("originals-revision",originalGeneration);
        var write=arenaStore.write(Collections.unmodifiableMap(values),data,migrateStore);migrateStore=false;return write;
    }
    private void startSave(boolean incomplete) {
        if(box==null)return;
        var write=queueSave(incomplete);
        if(incomplete)saving=new SaveCapture(write);else stateWrites.add(write);
    }
    private void saveTick() {
        try {
            while(!stateWrites.isEmpty() && stateWrites.peek().isDone())stateWrites.remove().getNow(null);
            if(saving!=null && saving.write.isDone()) {
                saving.write.getNow(null);Runnable done=saving.afterWrite;saving=null;if(done!=null)done.run();
            }
        }catch(RuntimeException e){saveFailed(e);saving=null;}
    }
    private void saveFailed(Exception e) {ioFailed=true;plugin.getLogger().severe("アリーナ保存に失敗。操作停止: "+e);}
    private boolean save(boolean incomplete) {
        if(box==null)return true;
        try{stateWrites.add(queueSave(incomplete));return true;}catch(RuntimeException e){saveFailed(e);return false;}
    }
    private void load() {
        if (!stateFile().exists()) return;
        try {
            var yaml = new YamlConfiguration(); yaml.load(stateFile());
            world = Bukkit.getWorld(UUID.fromString(yaml.getString("world", "")));
            if (world == null) throw new IllegalStateException("保存されたワールドが読み込まれていません。");
            box = Box.restored(yaml.getInt("x"), yaml.getInt("y"), yaml.getInt("z"), yaml.getInt("radius"), yaml.getInt("height"));
            validateLocation(box, world);
            wall = Material.valueOf(yaml.getString("wall", "BEDROCK"));
            if (yaml.contains("main-user")) mainUser = UUID.fromString(yaml.getString("main-user"));
            wins = yaml.getInt("wins"); edit = yaml.getBoolean("edit");
            if (yaml.getBoolean("paused")) clock.pause();
            var overrides = yaml.getConfigurationSection("layer-overrides");
            if (overrides != null) for (String key : overrides.getKeys(false)) layerOverrides.put(Integer.parseInt(key), Material.valueOf(overrides.getString(key)));
            var blockDataCache=new HashMap<String,BlockData>();
            if(yaml.contains("originals-revision")) {
                originalGeneration=yaml.getString("originals-revision");var stored=arenaStore.readOriginals(originalGeneration);
                if(!stored.world().equals(world.getUID().toString()) || !stored.box().equals(box))throw new IllegalStateException("復元データの箱情報が一致しません。");
                stored.originals().forEach((c,v)->yaml.set("originals."+c.x()+","+c.y()+","+c.z(),v));
                if(yaml.getBoolean("lid-active"))stored.roof().forEach((c,v)->yaml.set("lid-originals."+c.x()+","+c.y()+","+c.z(),v));
                roofStrings=stored.roof();
            } else migrateStore=true;
            var section = yaml.getConfigurationSection("originals");
            if (section == null) throw new IllegalStateException("復元用データがありません。");
            for (String key : section.getKeys(false)) {
                String[] xyz = key.split(",");
                originals.put(new Box.Cell(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2])), blockDataCache.computeIfAbsent(section.getString(key),Bukkit::createBlockData));
            }
            if(!migrateStore)savedOriginalRevision=originals.revision();
            pinChunks(); fill = new FillState(box.capacity()); heightBox=null;
            var savedLid = yaml.getConfigurationSection("lid-originals");
            if (savedLid != null) {
                Map<Box.Cell, BlockData> snapshot = new LinkedHashMap<>();
                for (String key : savedLid.getKeys(false)) {
                    String[] xyz = key.split(",");
                    snapshot.put(new Box.Cell(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2])), blockDataCache.computeIfAbsent(savedLid.getString(key),Bukkit::createBlockData));
                }
                // Recover a roof left behind by an interrupted server, rather than retaining it forever.
                lid = new BedrockLid<>(box, 1, snapshot);
                removeLid();
                if (!save(yaml.getBoolean("transitioning"))) return;
            }
            if (yaml.getBoolean("transitioning")) {
                transitioning = true;
                structure = new ArrayList<>(originals.keySet()).iterator();
                structureChange = c -> block(c).setBlockData(originals.get(c), false);
                structureDone = () -> {
                    box = null; fill = null; originals.clear(); releaseTickets();
                    stateWrites.add(arenaStore.delete());
                    tell("前回中断された箱の変更を検出し、作成前の地形を復元しました。");
                };
            } else { normalizeLayers(); reconcileAll(); }
            if(migrateStore)save(yaml.getBoolean("transitioning"));
        } catch (Exception e) { ioFailed = true; plugin.getLogger().severe("アリーナを復元できません。arena.ymlを保持して操作停止: " + e); }
    }
    private void setMaterial(Block b,Material material) {if(b.getType()!=material)b.setBlockData(data(material), false);}
    private Block block(Box.Cell c) { return world.getBlockAt(c.x(), c.y(), c.z()); }
    /** Shared default states: bulk writes skip creating a BlockData per block. */
    private BlockData data(Material material) {
        BlockData cached = blockDataCache.get(material);
        if (cached == null) { cached = Bukkit.createBlockData(material); blockDataCache.put(material, cached); }
        return cached;
    }
    private Block blockAt(int index) { return world.getBlockAt(box.cellX(index), box.cellY(index), box.cellZ(index)); }
    private Location location(Box.Cell c) { return new Location(world, c.x() + .5, c.y() + .5, c.z() + .5); }
    private Location centre() { return new Location(world, box.x() + .5, box.y() + box.height() + 1.5, box.z() + .5); }
    private boolean same(Block b) { return world != null && b.getWorld().equals(world); }
    private boolean inside(Block b) { return box != null && same(b) && box.contains(b.getX(), b.getY(), b.getZ()); }
    private boolean preparing(Block b) {return preparation!=null && preparation.world().equals(b.getWorld()) && preparation.protectedCells().test(new Box.Cell(b.getX(),b.getY(),b.getZ()));}
    private boolean envelope(Block b) { return preparing(b) || (busy() && same(b) && originals.containsKey(new Box.Cell(b.getX(),b.getY(),b.getZ()))) || box != null && same(b) && (box.envelope(b.getX(), b.getY(), b.getZ()) || lidBlock(b)); }
    private boolean shell(Block b) { return box != null && same(b) && box.shell(b.getX(), b.getY(), b.getZ()); }
    private boolean lidBlock(Block b) { return lid != null && same(b) && lid.contains(b.getX(), b.getY(), b.getZ()); }
    private int lidSeconds() { return lid == null ? 0 : lid.secondsLeft(); }
    private boolean counts(Block b) { return b.getType().isSolid() && !b.isLiquid(); }
    static boolean fillMaterial(Material m) { return m.isBlock() && m.isSolid() && !m.hasGravity() && !m.isInteractable(); }
    private Material material(int index) {
        int layer = index / (box.width() * box.width());
        return layerOverrides.getOrDefault(layer, LayerPalette.at(box.height(), layer));
    }
    private void normalizeLayers() {
        for (int i = 0; i < fill.capacity(); i++) {
            Block b = blockAt(i);
            if (counts(b) && !(tileGuard.isTile(b.getType())) && b.getType() != material(i)) b.setBlockData(data(material(i)), false);
        }
    }
    /** Synchronous full pass; only for startup load, before the server ticks. */
    private void reconcileAll() {
        ensureHeights();
        for (int i = 0; i < fill.capacity(); i++) update(i, blockAt(i));
    }
    /** Time-sliced full pass during play; the game stays busy until done runs. */
    private void beginReconcile(Runnable done) {
        Runnable prior=reconcileDone;
        reconcileDone=prior==null?done:done==null?prior:()->{prior.run();done.run();};
        reconciling=0;
    }
    private void reconcileTick() {
        ensureHeights();long deadline=boxDeadline();
        while(reconciling<fill.capacity()){update(reconciling,blockAt(reconciling));reconciling++;if(System.nanoTime()>=deadline)break;}
        if(reconciling<fill.capacity())return;
        reconciling=-1;Runnable done=reconcileDone;reconcileDone=null;if(done!=null)done.run();
    }
    private void resetOccupancy() {
        fill = new FillState(box.capacity()); heightBox = null; scan = 0; gravityQueue.clear(); gravityQueued.clear();
    }
    private void ensureHeights() {if(heightBox!=box){heights=new ColumnHeights(box);heightBox=box;}}
    /** Bookkeeping for a block this plugin just placed, without re-reading the world. */
    private void placed(int i, Block b) {
        fill.setOccluding(i, occludes(b));
        int area=box.width()*box.width();
        if(i>=area && !fill.occupied(i-area))queueGravity(i,false);
        ensureHeights();heights.update(i,true);
    }
    private void update(Block b) { if (inside(b)) update(box.index(b.getX(), b.getY(), b.getZ()), b); }
    /** Caller guarantees b is the arena block at index. */
    private void update(int i, Block b) {boolean occupied=counts(b), changed=fill.occupied(i)!=occupied;fill.set(i, occupied);fill.setOccluding(i,occupied && occludes(b));
        // Queue only a cell that may now fall: a new block over a gap, or the block over a new gap.
        if(changed){int area=box.width()*box.width();
            if(occupied){if(i>=area && !fill.occupied(i-area))queueGravity(i,false);}
            else if(i+area<fill.capacity() && fill.occupied(i+area))queueGravity(i+area,true);}ensureHeights();heights.update(i,!b.getType().isAir());}
    double spawnY() { ensureHeights();return heights.spawnY(lid==null?null:(double)lid.topY(),world.getMaxHeight()); }
    int add(int count) { return add(count,false); }
    int add(int count, boolean direct) { require(); if (fill.full()) { cancelFountain(); return 0; } return fill.enqueue(count,direct); }
    int fillAll() { return fillAll(false); }
    int fillAll(boolean direct) { require(); return add((int)Math.max(0L, (long)fill.capacity() - fill.filled() - fill.pending() - falling.size()),direct); }
    void layer(int count, Material material) {
        require(); if (count < 1 || count > box.height()) throw new IllegalArgumentException("層数は1～箱の高さで指定してください。");
        if (!fillMaterial(material)) throw new IllegalArgumentException("重力や操作機能のない固体ブロックを指定してください。");
        checkNoContainers(box.envelopeCells()); cancelFountain();
        for (int y = 0; y < count; y++) layerOverrides.put(y, material);
        begin(box.interior(0, count * box.width() * box.width()), c -> {
            if (counts(block(c))) block(c).setBlockData(data(material), false);
        }, () -> beginReconcile(() -> tell("下から " + count + " 層の材質を変更しました。")));
    }
    /** Changes the win count for this run only (config.yml keeps win-seconds); a running count restarts from it. */
    int timer(int seconds) { require(); clearCountdown(); clock.duration(seconds); return seconds; }
    int timerAdd(int delta) { require(); return timer((int)Math.max(1, Math.min(3600, (long)clock.seconds() + delta))); }
    int timerReset() { require(); return timer(settings.winSeconds()); }
    void pause() { require(); clearCountdown(); clock.pause(); frameHealth.updateDisplay(); save(false); }
    void resume() { require(); clearCountdown(); clock.resume(); frameHealth.updateDisplay(); save(false); }
    boolean toggle(String key) {
        require();
        boolean value = switch (key) {
            case "edit" -> edit = !edit;
            case "autoreplace" -> autoreplace = !autoreplace;
            case "fireworks" -> fireworks = !fireworks;
            case "toplock" -> toplock = !toplock;
            default -> throw new IllegalArgumentException("不明な設定です。");
        };
        plugin.getConfig().set(key, value); plugin.saveConfig(); reloadSettings(); save(false); return value;
    }
    void color(String kind, String name) {
        if (!kind.equals("win") && !kind.equals("cancel")) throw new IllegalArgumentException("color win または color cancel を指定してください。");
        String normalized = name.toLowerCase(Locale.ROOT);
        if (NamedTextColor.NAMES.value(normalized) == null) throw new IllegalArgumentException("色名が不正です。例: GOLD / RED / AQUA");
        plugin.getConfig().set(kind + "-color", normalized); plugin.saveConfig(); reloadSettings();
    }
    void mainUser(Player p) { require(); mainUser = p == null ? null : p.getUniqueId(); save(false); }
    Player main() { return mainUser == null ? null : Bukkit.getPlayer(mainUser); }
    void teleportMain() { Player p = main(); if (p != null) teleport(p); }
    void teleport(Player p) {
        if (box == null) return;
        p.teleport(new Location(world, box.x() + box.radius() + .5, lid == null ? box.y() + box.height() + 1 : lid.topY(), box.z() + .5, 90, 0));
    }
    String status() {
        require(); return sizes().label(box.width(),box.height())+" / 埋め立て " + fill.filled() + "/" + fill.capacity() + "（追加待ち " + fill.pending() + "） 勝利 " + wins
                + " 回 / 岩盤のふた " + lidSeconds() + " 秒 / 氷結 " + Math.max(0, (frozenUntil - tick + 19) / 20) + " 秒"
                + " / タイマー " + (clock.paused() ? "停止" : clock.won() ? "クリア済み" : "有効") + "（勝利カウント " + clock.seconds() + " 秒）";
    }
    private void tick() {
        tick++;
        doubleTime.tick();
        if(ioFailed)return;
        saveTick();if(ioFailed)return;
        if(preparation!=null){preparationTick();return;}
        if (box == null || ioFailed) return;
        if (lid != null && saving==null && lid.tick()) {
            removeLid();
            if (!save(transitioning)) return;
            tell("岩盤のふたの効果が終了しました。");
        }
        if (structure != null && saving==null) {
            try {
            long deadline=boxDeadline();
            for (int i = 0, max = boxBudget(); i < max && structure.hasNext(); i++) {
                Box.Cell cell=structure.next();structureChange.accept(cell);if(fill!=null && structureUpdates)update(block(cell));
                if(System.nanoTime()>=deadline)break;
            }
            if (!structure.hasNext()) {
                structure = null; structureDone.run(); transitioning = false;
                if (box != null) startSave(false);
            }
            }catch(RuntimeException error){saveFailed(error);}
            return;
        }
        if (reconciling >= 0) {
            try { reconcileTick(); } catch (RuntimeException error) { saveFailed(error); }
            return;
        }
        // Safety net for external edits; game events already update occupancy immediately.
        int budget = Math.min(fill.capacity(), settings.performanceReconcilePerTick());
        long deadline = workDeadline();
        for (int i = 0; i < budget; i++) {
            if (scan >= fill.capacity()) scan = 0;
            update(scan, blockAt(scan)); scan++;
            if (System.nanoTime() >= deadline) break;
        }
        if (frameHealth.isCollapsing()) {
            clock.tick(false);
            if (tick % 10 == 0) updateBar();
            return;
        }
        rocket.tick(tick);
        directTick(); fountainTick(); effectsTick(); salmonTick();
        if (frameHealth.isCollapsing()) { clock.tick(false); return; }
        blastTick();
        gravityTick();
        // Occupancy is reconciled continuously within the scan budget; event-driven edits update it immediately.
        switch (clock.tick(fill.full() && blasts.isEmpty() && falling.isEmpty())) {
            case START -> tell("埋め立て完了！勝利カウント開始！");
            case CANCEL -> title("cancel", "妨害発生！カウント中断");
            case WIN -> completeRound();
            default -> {}
        }
        countdown();
        frameHealth.updateDisplay();
        if (tick % 10 == 0) updateBar();
    }
    private void gravityTick() {
        if (busy() || box == null) return;
        long deadline=System.nanoTime()+settings.gravityWorkNanos();
        ensureGravityQueue();
        int spawned=0,limit=settings.gravityBlocksPerStep();
        int max=settings.gravityMaxFallingBlocks();
        if(settings.gravityEnabled()) {
            boolean first=true;
            while(!gravityQueue.isEmpty() && spawned<limit && falling.size()<max && (first || System.nanoTime()<deadline)) {
                first=false;int index=gravityQueue.removeFirst();gravityQueued.clear(index);
                if(startGravity(index))spawned++;
            }
        }
        for (var entry : new ArrayList<>(falling.entrySet())) {
            FallingBlock entity = entry.getKey(); Location at = entity.getLocation();
            if (!entity.isValid() || tick - entry.getValue().started() > 600 || !at.getWorld().equals(world)
                    || !box.contains(at.getBlockX(), Math.max(box.y()+1,at.getBlockY()), at.getBlockZ())) {
                settleGravity(entity, null);
            } else if (at.getY() < box.y()+1) {
                settleGravity(entity, world.getBlockAt(at.getBlockX(),box.y()+1,at.getBlockZ()));
            }
            if(System.nanoTime()>=deadline)return;
        }
        if (!settings.gravityEnabled()) return;
        if (tick % settings.gravityIntervalTicks() != 0) return;
        // Only cached occupied cells above a free cell are candidates; the world is read just to confirm them.
        int area = box.width() * box.width();
        if (gravityScan < area || gravityScan >= fill.capacity()) gravityScan = area;
        for (int checked = 0; checked < settings.gravityScanPerStep() && spawned < limit && falling.size() < max && System.nanoTime()<deadline; checked++) {
            int candidate = fill.nextFallCandidate(gravityScan, area);
            if (candidate < 0) { gravityScan = area; break; }
            gravityScan = candidate + 1;
            if(startGravity(candidate))spawned++;
        }
    }
    private boolean startGravity(int sourceIndex) {
        int below=sourceIndex-box.width()*box.width();
        // A cached support needs no world read; if it is stale, the scan's update re-queues this cell.
        if(fill.reserved(below) || fill.occupied(below))return false;
        Block target=blockAt(below);
        // A stale cache entry is corrected so the sweep stops proposing it.
        if(!(target.getType().isAir() || target.isLiquid())){update(below,target);return false;}
        Block source=blockAt(sourceIndex);
        if(!counts(source)){update(sourceIndex,source);return false;}
        if(tileGuard.isTile(source.getType()))return false;
        BlockData data=source.getBlockData();
        FallingBlock entity=world.spawnFallingBlock(source.getLocation().add(.5,0,.5),data);
        entity.addScoreboardTag(GRAVITY_TAG);entity.setPersistent(true);
        entity.setGravity(true);entity.setDropItem(false);entity.setHurtEntities(false);
        entity.shouldAutoExpire(false);entity.setInvulnerable(true);
        falling.put(entity,new GravityFall(box.cell(sourceIndex),data,tick));
        source.setBlockData(data(Material.AIR), false);update(sourceIndex,source);
        // Also propagate when the background sweep discovered a block absent from the cache.
        queueGravity(sourceIndex+box.width()*box.width(),true);
        return true;
    }
    private void recoverGravity(Entity entity) {
        if (!(entity instanceof FallingBlock fb) || !entity.getScoreboardTags().contains(GRAVITY_TAG)
                || falling.containsKey(fb) || box == null || !entity.getWorld().equals(world)) return;
        Location source = fb.getSourceLoc();
        if (source == null) source = fb.getLocation();
        falling.put(fb, new GravityFall(new Box.Cell(source.getBlockX(), source.getBlockY(), source.getBlockZ()), fb.getBlockData(), tick));
    }
    private boolean gravitySpace(Block b) {
        return b != null && inside(b) && !(tileGuard.isTile(b.getType())) && (b.getType().isAir() || b.isLiquid());
    }
    private void settleGravity(FallingBlock entity, Block preferred) {
        GravityFall fall = falling.remove(entity); if (fall == null) return;
        Block target = gravitySpace(preferred) ? preferred : null;
        if (target == null && box.contains(fall.source().x(), fall.source().y(), fall.source().z())) {
            Block source = block(fall.source()); if (gravitySpace(source)) target = source;
        }
        // Use cached empty candidates instead of rescanning the whole arena for every collision.
        // Revalidate world blocks; stale cache entries are corrected, and excess work becomes support queue work.
        if(target==null) {
            int from=0;
            for(int tries=0;tries<32;tries++) {
                int i=fill.nextUnoccupied(from);if(i<0)break;from=i+1;
                Block candidate=blockAt(i);
                if(gravitySpace(candidate)){target=candidate;break;}
                update(i,candidate);
            }
        }
        if (target != null) {
            int index = box.index(target.getX(),target.getY(),target.getZ());
            if (autoreplace) target.setBlockData(data(material(index)), false); else target.setBlockData(fall.data(), false);
            update(target);
        } else fill.enqueue(1);
        entity.remove();
    }
    private void settleAllGravity() {
        if (box != null) for (FallingBlock entity : new ArrayList<>(falling.keySet())) settleGravity(entity, null);
    }
    private void updateBar() {
        for (Player p : Bukkit.getOnlinePlayers()) updateReach(p);
    }
    void reach(double distance) {
        if (!Double.isFinite(distance) || distance < 0 || distance > 64)
            throw new IllegalArgumentException("設置距離は0～64です（0で通常に戻す）。");
        blockReach = distance;
        plugin.getConfig().set("block-reach", distance); plugin.saveConfig(); reloadSettings();
        for (Player p : Bukkit.getOnlinePlayers()) updateReach(p);
    }
    private void updateReach(Player p) {
        BlockReach.apply(p.getAttribute(org.bukkit.attribute.Attribute.BLOCK_INTERACTION_RANGE), near(p) ? blockReach : 0);
    }
    private void resetReach() {
        for (Player p : Bukkit.getOnlinePlayers())
            BlockReach.apply(p.getAttribute(org.bukkit.attribute.Attribute.BLOCK_INTERACTION_RANGE), 0);
    }
    private void clearCountdown() {
        if (lastCountdown >= 0)
            for (Player p : Bukkit.getOnlinePlayers()) if (near(p)) p.clearTitle();
        lastCountdown = -1;
    }
    private void countdown() {
        int seconds = clock.secondsLeft();
        if (seconds == lastCountdown) return;
        lastCountdown = seconds;
        if (seconds < 1) return;
        Title title = Title.title(Component.text(seconds, NamedTextColor.GOLD), Component.empty(),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(seconds==2?1600:1100), Duration.ZERO));
        for (Player p : Bukkit.getOnlinePlayers()) if (near(p)) {
            p.showTitle(title);
            p.playSound(p.getLocation(), "minecraft:block.note_block.pling", 1f, 1.2f);
        }
    }
    private void completeRound() {
        wins++;
        lastCountdown = -1;
        title("win", "埋め立て成功！"); celebratory(centre());
        // Begin and persist cleanup before dispatch: no second notification on subsequent ticks/restart.
        cleanupEffects();
        boolean[] kept = {false}; BlockData air = data(Material.AIR);
        begin(box.interior(0, box.capacity()), c -> {
            Block b = block(c); Material type = b.getType();
            if (tileGuard.isTile(type)) kept[0] = true; else if (!type.isAir()) b.setBlockData(air, false);
        }, () -> {
            // Clock state is reset here so the checkpoint saved right after this callback matches runtime.
            resetOccupancy(); clock.tick(false); clock.resume();
            // Only containers left behind need a full re-read.
            if (kept[0]) beginReconcile(() -> tell("クリア！箱の中を空にしました。")); else tell("クリア！箱の中を空にしました。");
        });
        structureUpdates = false;
        saving.afterWrite=()->plugin.requestWin(true, WinService.Reason.FILL_COMPLETE);
    }
    private boolean near(Player p) { return box != null && p.getWorld().equals(world) && p.getLocation().distanceSquared(centre()) < Math.pow(box.radius() + 64, 2); }
    /** Log suffix naming who triggered an effect (e.g. a gifter passed by STE as {nickname}). */
    static String named(String name) { return name == null || name.isBlank() ? "" : "（" + name + "）"; }
    void note(String action, String name) { if (name != null && !name.isBlank()) plugin.getLogger().info(action + named(name)); }
    private void tell(String message) {
        // Console only: operators drive the game from a terminal and the stream chat stays clean.
        plugin.getLogger().info(message);
    }
    private void title(String kind, String fallback) {
        NamedTextColor color = NamedTextColor.NAMES.value(settings.titles().getOrDefault(kind + "-color", "WHITE").toLowerCase(Locale.ROOT));
        Title title = Title.title(Component.text(settings.titles().getOrDefault(kind + "-text", fallback), color == null ? NamedTextColor.WHITE : color), Component.empty(), Title.Times.times(Duration.ofMillis(100), Duration.ofSeconds(2), Duration.ofMillis(400)));
        for (Player p : Bukkit.getOnlinePlayers()) if (near(p)) p.showTitle(title);
    }
    BlockDisplay display(Location at, Material material, float scale) {
        return world.spawn(at, BlockDisplay.class, d -> {
            d.setBlock(material.createBlockData()); d.setPersistent(false); d.addScoreboardTag(TAG);
            d.setTeleportDuration(1); d.setInvulnerable(true);
            d.setTransformation(new Transformation(new Vector3f(-scale / 2, -scale / 2, -scale / 2), new Quaternionf(), new Vector3f(scale), new Quaternionf()));
        });
    }
    private void directTick() {
        if(fill.full()) {cancelFountain();return;}
        if(tick<frozenUntil)return;
        int budget=boxBudget();
        // Each cell is tried at most once per tick: a chest or other blocker is skipped, not retried forever.
        // Sliced mode keeps its count-only limit; fast mode also caps the time spent per tick.
        int area=box.width()*box.width(),layer=-1;BlockData layerData=null;long deadline=settings.instantBoxChanges()?boxDeadline():Long.MAX_VALUE;
        for(int from=fill.searchHint();budget-->0 && fill.directWaiting()>0 && System.nanoTime()<deadline;) {
            int index=fill.nextFree(from);if(index<0)break;from=index+1;
            Block target=blockAt(index);Material type=target.getType();
            if(type.isSolid() && !target.isLiquid() || tileGuard.isTile(type)){update(index,target);continue;}
            if(index/area!=layer){layer=index/area;layerData=data(material(index));}
            target.setBlockData(layerData, false);
            fill.placeDirect(index);placed(index,target);
            if(fill.full()){cancelFountain();return;}
        }
    }
    private void fountainTick() {
        if (fill.full()) { cancelFountain(); return; }
        // Freeze pauses both manual placement and automatic block delivery.
        if (tick < frozenUntil) return;
        Iterator<Flight> iterator = flights.iterator();
        while (iterator.hasNext()) {
            Flight f = iterator.next(); double t = (double) ++f.age / f.duration;
            if (t >= 1) {
                Block target = blockAt(f.index); boolean placed = !counts(target) && target.getType().isAir();
                if (placed) target.setBlockData(data(material(f.index)), false);
                update(f.index,target);
                fill.finish(f.index, placed); f.display.remove(); iterator.remove();
                if (fill.full()) { cancelFountain(); return; }
                if (placed && tick % 4 == 0) world.playSound(target.getLocation(), "minecraft:block.amethyst_block.place", .3f, 1.4f);
            } else {
                Vector pos = new Vector(FountainPath.coordinate(f.start.getX(), f.end.getX(), t),
                        FountainPath.height(f.start.getY(), f.end.getY(), t, f.arc),
                        FountainPath.coordinate(f.start.getZ(), f.end.getZ(), t));
                f.display.teleport(pos.toLocation(world));
            }
        }
        int rate = settings.fountainBlocksPerTick();
        int duration = settings.fountainFlightTicks();
        int max = Math.max(settings.fountainMaxDisplays(), rate * duration);
        for (int n = 0; n < rate && flights.size() < max; n++) {
            int index = fill.reserve(); if (index < 0) break;
            Block target = blockAt(index);
            if (!target.getType().isAir()) {
                // Non-solid decorative/liquid blocks inside the arena are replaced as part of filling.
                if (!counts(target) && !(tileGuard.isTile(target.getType()))) target.setBlockData(data(Material.AIR), false);
                else { update(index,target); fill.finish(index, false); continue; }
            }
            Location start = centre(); Location end = location(box.cell(index));
            double arc = settings.fountainArc();
            if (!Double.isFinite(arc)) arc = 6;
            flights.add(new Flight(index, display(start, material(index), .65f), start.toVector(), end.toVector(), duration, Math.clamp(arc, 1, 9)));
        }
    }
    private static final class Flight {
        final int index, duration; final BlockDisplay display; final Vector start, end; final double arc; int age;
        Flight(int index, BlockDisplay display, Vector start, Vector end, int duration, double arc) {
            this.index = index; this.display = display; this.start = start; this.end = end; this.duration = duration; this.arc = arc;
        }
    }
    private void cancelFountain() { for (Flight f : flights) f.display.remove(); flights.clear(); if (fill != null) fill.cancelPending(); }
    void shield(int seconds) {
        require();
        if (lid != null) lid.extend(seconds);
        else {
            Map<Box.Cell, BlockData> snapshot = new LinkedHashMap<>();
            for (var c : BedrockLid.cells(box)) {
                Block b = block(c);
                if (tileGuard.isTile(b.getType())) throw new IllegalArgumentException("ふたの位置にチェストなどがあります。移動してから発動してください。");
                snapshot.put(c, b.getBlockData().clone());
            }
            lid = new BedrockLid<>(box, seconds, snapshot);
            // Write restoration data before placing even the first bedrock block.
            var serialized=new RestorationMap();serialized.putAll(snapshot);roofStrings=serialized.snapshot();roofDirty=true;
            saving=new SaveCapture(queueSave(transitioning));
            saving.afterWrite=()->{movePlayersAboveLid();lid.install(c -> block(c).setBlockData(data(Material.BEDROCK), false));};
        }
        tell("岩盤のふたを展開！残り " + lid.secondsLeft() + " 秒。上からのTNTを防ぎます。");
        world.playSound(centre(), Sound.ITEM_SHIELD_BLOCK, 1, 1);
    }
    private void movePlayersAboveLid() {
        for (Player p : world.getPlayers()) {
            var bounds = p.getBoundingBox();
            if (bounds.getMaxX() > box.x() - box.radius() && bounds.getMinX() < box.x() + box.radius() + 1
                    && bounds.getMaxZ() > box.z() - box.radius() && bounds.getMinZ() < box.z() + box.radius() + 1
                    && bounds.getMaxY() > lid.blockY() && bounds.getMinY() < lid.topY()) {
                Location safe = p.getLocation(); safe.setY(lid.topY()); p.teleport(safe); p.setFallDistance(0);
            }
        }
    }
    private void removeLid() {
        if (lid == null) return;
        lid.restore((c, data) -> block(c).setBlockData(data, false));
        lid = null;
    }
    void freeze(int seconds) { doubleTime.launch("freeze", () -> freezeOnce(seconds)); }
    private void freezeOnce(int seconds) { require(); frozenUntil = Math.max(tick, frozenUntil) + seconds * 20; frozenUntil = Math.min(frozenUntil, tick + 72000); tell("氷結！ " + seconds + " 秒間、手置きと噴水が停止します。"); }
    int repair(int count) { require(); int added = add(count); tell("修復波！中央の噴水から " + added + " ブロックを補修します。"); world.spawnParticle(Particle.HAPPY_VILLAGER, centre(), 60, 2, 1, 2, .1); return added; }
    void tnt(int count, double radius, String name, String kind) { doubleTime.launch("supertnt", () -> tntOnce(count, radius, name, kind)); }
    private void tntOnce(int count, double radius, String name, String kind) {
        tnt(count, radius, name, kind, radius, 0);
    }
    private void tnt(int count, double radius, String name, String kind, double framePower, int staggerTicks) {
        require(); count=additionalEffectCount(count);if(count==0)return;ensureEffectCapacity(count);
        for (int i = 0; i < count; i++) spawnBomb(randomTarget(), radius, 30 + i % 5 * staggerTicks, Material.TNT, 1, name, false, framePower);
        tell(kind + " × " + count + (name.isBlank() ? "" : "（" + name + "）"));
    }
    void salmon(int count, int seconds, double power, String nickname) { doubleTime.launch("salmon", () -> salmonOnce(count, seconds, power, nickname)); }
    private void salmonOnce(int count, int seconds, double power, String nickname) {
        require();
        if (count < 1 || count > 50 || seconds < 1 || seconds > 300 || !Double.isFinite(power) || power < .1 || power > 20)
            throw new IllegalArgumentException("鮭は1～50体、時間は1～300秒、威力は0.1～20です。");
        count=additionalEffectCount(count);if(count==0)return;ensureEffectCapacity(count);
        for (int i=0;i<count;i++) {
            Location at = randomTarget(); at.setY(spawnY());
            Salmon entity = world.spawn(at, Salmon.class);
            entity.addScoreboardTag(TAG); entity.setPersistent(false); entity.setInvulnerable(true);
            entity.setCustomNameVisible(true);
            ExplodingSalmon fish = new ExplodingSalmon(entity,tick+seconds*20,power,nickname);
            salmonName(fish,seconds); salmons.add(fish);
        }
    }
    private void salmonName(ExplodingSalmon salmon, int seconds) {
        salmon.entity().customName(Component.text((salmon.nickname().isBlank()?"爆発シャケ":salmon.nickname())+" 残り "+seconds+"秒",NamedTextColor.GOLD));
    }
    private void salmonTick() {
        for (ExplodingSalmon salmon : new ArrayList<>(salmons)) {
            if (frameHealth.isCollapsing()) return;
            if (!salmon.entity().isValid()) { salmons.remove(salmon); continue; }
            int remaining = salmon.expires()-tick;
            if (remaining <= 0) {
                Location at = salmon.entity().getLocation(); salmon.entity().remove(); salmons.remove(salmon);
                if (!at.getWorld().equals(world)) continue;
                if (shieldsExplosion(at)) { explosionVisual(at); continue; }
                // RaiBR: HP power and interior break radius (power * 1.5) are separate.
                frameHealth.customExplosion(at,salmon.power());
                if (settings.salmonDamageEntities()) world.createExplosion(at,(float)salmon.power(),false,false);
                else explosionVisual(at);
                if (!frameHealth.isCollapsing()) blasts.addLast(new Blast(box,at.getX(),at.getY(),at.getZ(),Math.max(1,salmon.power()*1.5)));
            } else {
                if (remaining % 20 == 0) salmonName(salmon,(remaining+19)/20);
                if (remaining % 10 == 0) {
                    Location at=salmon.entity().getLocation();
                    world.spawnParticle(Particle.FLAME,at,4,.15,.15,.15,0);
                    world.playSound(at,"minecraft:block.note_block.hat",.5f,1.8f);
                }
            }
        }
    }
    void tntRocket(int count) { tntRocket(count, ""); }
    void tntRocket(int count, String name) { doubleTime.launch("tntrocket", () -> tntRocketOnce(count, name)); }
    private void tntRocketOnce(int count, String name) {
        require(); count=additionalEffectCount(count);if(count==0)return;ensureEffectCapacity(count);
        Player target=main();
        if(mainUser!=null && target==null)throw new IllegalArgumentException("メインプレイヤーがオンラインではありません。");
        rocket.start(target==null?new ArrayList<>(Bukkit.getOnlinePlayers()):List.of(target),count,tick,name);
        tell("TNTロケット × " + count + named(name));
    }
    void normalTnt(int count, String name) { doubleTime.launch("tnt", () -> normalTntOnce(count, name)); }
    private void normalTntOnce(int count, String name) {
        if (count < 1 || count > 100) throw new IllegalArgumentException("TNTの個数は1～100です。");
        tnt(count, settings.tntPower(), name, "TNT", 4, 0);
    }
    void randomTnt() { randomTnt(""); }
    void randomTnt(String name) { doubleTime.launch("randomtnt", () -> randomTntOnce(name)); }
    private void randomTntOnce(String name) { tntOnce(1, 1 + random.nextInt(8), name, "ランダムTNT"); }
    void meteor() { meteor(""); }
    void meteor(String name) { doubleTime.launch("meteor", () -> meteorOnce(name)); }
    private void meteorOnce(String name) {
        require(); ensureEffectCapacity(1);
        spawnBomb(randomTarget(), settings.meteorPower(), 55, Material.MAGMA_BLOCK, 3, name.isBlank() ? "巨大隕石" : name, false); tell("巨大隕石が接近！" + named(name));
    }
    void zeus() { zeus(""); }
    void zeus(String name) { doubleTime.launch("zeustnt", () -> zeusOnce(name)); }
    private void zeusOnce(String name) {
        require(); ensureEffectCapacity(1);
        spawnBomb(randomTarget(), settings.zeusPower(), 20, Material.TNT, 1.4f, name.isBlank() ? "雷神TNT" : name, true); tell("雷神TNT！" + named(name));
    }
    void ring() { ring(""); }
    void ring(String name) { doubleTime.launch("tntring", () -> ringOnce(name)); }
    private void ringOnce(String name) {
        require(); ensureEffectCapacity(12);
        Location centre = centre(); double r = Math.max(1, box.radius() - 1.5);
        double phase=doubleTime.isAdditionalLaunch()?random.nextDouble()*Math.PI*2:0;
        for (int i = 0; i < 12; i++) {
            double angle = i * Math.PI / 6 + phase;
            Location target = centre.clone().add(Math.cos(angle) * r, -2, Math.sin(angle) * r);
            target.setY(topY(target.getBlockX(), target.getBlockZ()));
            spawnBomb(target, settings.tntPower(), 40, Material.TNT, 1, name, false, 4);
        }
        tell("TNTリング！外周から同時爆発！" + named(name));
    }
    void comets(int seconds, int interval) { comets(seconds, interval, ""); }
    void comets(int seconds, int interval, String name) { doubleTime.launch("comets", () -> cometsOnce(seconds, interval, name)); }
    private void cometsOnce(int seconds, int interval, String name) {
        require(); if (cometUntil > tick) throw new IllegalArgumentException("彗星の雨は既に発動中です。");
        cometUntil = tick + seconds * 20; cometInterval = interval; cometName = name; tell("彗星の雨！ " + seconds + " 秒" + named(name));
    }
    void blackhole(int seconds) { doubleTime.launch("blackhole", () -> blackholeOnce(seconds)); }
    private void blackholeOnce(int seconds) {
        require(); if (holeUntil > tick) throw new IllegalArgumentException("ブラックホールは既に発動中です。");
        holeCenter = centre(); holeUntil = tick + seconds * 20; tell("ブラックホール発生！ " + seconds + " 秒");
    }
    private int additionalEffectCount(int requested) {
        if(!doubleTime.isAdditionalLaunch())return requested;
        int available=Math.max(0,settings.effectsMaxActive()-bombs.size()-blasts.size()-salmons.size()-rocket.pending());
        if(requested>available)plugin.getLogger().fine("追加妨害の上限超過分をスキップ");
        return Math.min(requested,available);
    }
    private void ensureEffectCapacity(int added) {
        if (bombs.size() + blasts.size() + salmons.size() + rocket.pending() + added > settings.effectsMaxActive()) throw new IllegalArgumentException("妨害の同時実行上限です。少し待って再実行してください。");
    }
    private Location randomTarget() {
        int x = box.x() - box.radius() + 1 + random.nextInt(box.width());
        int z = box.z() - box.radius() + 1 + random.nextInt(box.width());
        return new Location(world, x + .5, topY(x, z), z + .5);
    }
    private double topY(int x, int z) {
        for (int y = box.y() + box.height(); y > box.y(); y--) if (counts(world.getBlockAt(x, y, z))) return y + 1.01;
        return box.y() + 1.01;
    }
    private void spawnBomb(Location target, double radius, int duration, Material material, float scale, String name, boolean lightning) {
        spawnBomb(target, radius, duration, material, scale, name, lightning, radius);
    }
    private void spawnBomb(Location target, double radius, int duration, Material material, float scale, String name, boolean lightning, double framePower) {
        if (material == Material.TNT && scale == 1 && !lightning) { spawnPrimed(target, radius, duration, name, framePower); return; }
        Location start = target.clone(); start.setY(material==Material.TNT?spawnY():box.y() + box.height() + 9);
        BlockDisplay display = display(start, material, scale);
        if (!name.isBlank()) { display.customName(Component.text(name, NamedTextColor.GOLD)); display.setCustomNameVisible(true); }
        bombs.add(new Bomb(display, start.toVector(), target.toVector(), radius, duration, lightning, scale / 2.0, framePower));
    }
    /** Real lit TNT dropped from just above the box; it falls by vanilla physics and this plugin detonates it on schedule. */
    private void spawnPrimed(Location target, double radius, int duration, String name, double framePower) {
        Location start = target.clone(); start.setY(lid == null ? box.y() + box.height() + 1 : lid.topY());
        // The vanilla fuse outlasts the schedule, so only explode() ever detonates it.
        TNTPrimed entity = primed(start, duration + 40);
        if (!name.isBlank()) { entity.customName(Component.text(name, NamedTextColor.GOLD)); entity.setCustomNameVisible(true); }
        bombs.add(new Bomb(entity, start.toVector(), start.toVector(), radius, duration, false, .5, framePower, true));
    }
    TNTPrimed primed(Location at, int fuse) {
        return world.spawn(at, TNTPrimed.class, t -> {
            t.setFuseTicks(fuse); t.setPersistent(false); t.addScoreboardTag(TAG); t.setVelocity(new Vector());
        });
    }
    private static final class Bomb {
        final Entity display; final Vector start, end; final double radius, halfSize, framePower; final int duration; final boolean lightning, physical; int age;
        Bomb(Entity display, Vector start, Vector end, double radius, int duration, boolean lightning, double halfSize, double framePower) {
            this(display, start, end, radius, duration, lightning, halfSize, framePower, false);
        }
        Bomb(Entity display, Vector start, Vector end, double radius, int duration, boolean lightning, double halfSize, double framePower, boolean physical) {
            this.display = display; this.start = start; this.end = end; this.radius = radius; this.duration = duration; this.lightning = lightning; this.halfSize = halfSize; this.framePower = framePower; this.physical = physical;
        }
    }
    private void explode(Location at, double radius) {
        explode(at, radius, radius);
    }
    private void explode(Location at, double radius, double framePower) {
        // Sample shielding before the destructive block queue changes the world.
        frameHealth.customExplosion(at, framePower);
        if (frameHealth.isCollapsing()) { explosionVisual(at); return; }
        blasts.addLast(new Blast(box, at.getX(), at.getY(), at.getZ(), radius));
        explosionVisual(at);
    }
    private void explosionVisual(Location at) {
        world.spawnParticle(Particle.EXPLOSION_EMITTER, at, 1);
        world.playSound(at, "minecraft:entity.generic.explode", 1, .85f);
        if (fireworks) celebratory(at);
    }
    private void blastTick() {
        int budget = settings.performanceChangesPerTick();
        while (budget-- > 0 && !blasts.isEmpty()) {
            Blast blast = blasts.peekFirst();
            if (!blast.hasNext()) { blasts.removeFirst(); continue; }
            Box.Cell c = blast.next(); if (c == null) continue;
            Block b = block(c);
            if (!b.getType().isAir() && !(tileGuard.isTile(b.getType()))) { b.setBlockData(data(Material.AIR), false); update(b); }
        }
    }
    private void celebratory(Location at) {
        world.spawnParticle(Particle.FIREWORK, at, 60, 1.5, 1.5, 1.5, .12);
        world.playSound(at, "minecraft:entity.firework_rocket.blast", .6f, 1.2f);
    }
    /** Every solid content block becomes a plain TNT block; nothing is ignited. One replacement even in double time. */
    void tntify(String name) {
        require(); BlockData tnt = data(Material.TNT);
        begin(box.interior(0, box.capacity()), c -> {
            Block b = block(c); Material type = b.getType();
            if (type != Material.TNT && counts(b) && !tileGuard.isTile(type)) b.setBlockData(tnt, false);
        }, () -> tell("箱の中身をTNTに置き換えました。" + named(name)));
    }
    /** A box full of TNT would otherwise chain-ignite every block at once; past the cap the block stays unlit (verified on Paper). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void tntPrime(org.bukkit.event.block.TNTPrimeEvent e) {
        if (!inside(e.getBlock())) return;
        while (!chainPrimes.isEmpty() && tick - chainPrimes.peekFirst() >= 80) chainPrimes.removeFirst();
        if (chainPrimes.size() >= settings.effectsMaxChainTnt()) { e.setCancelled(true); return; }
        chainPrimes.addLast(tick);
    }
    void endermen(int count, String name) { doubleTime.launch("enderman", () -> endermenOnce(count, name)); }
    private void endermenOnce(int count, String name) {
        require(); int max = settings.effectsMaxEndermen();
        if(doubleTime.isAdditionalLaunch()){int requested=count;count=Math.min(count,Math.max(0,max-thieves.size()));if(count<requested)plugin.getLogger().fine("追加エンダーマンの上限超過分をスキップ");}
        if (thieves.size() + count > max) throw new IllegalArgumentException("エンダーマンは同時に " + max + " 体までです。");
        for (int i = 0; i < count; i++) {
            Enderman e = world.spawn(doubleTime.isAdditionalLaunch()?randomTarget():centre(), Enderman.class, mob -> {
                mob.setAI(false); mob.setGravity(false); mob.setSilent(true); mob.setInvulnerable(true);
                mob.setPersistent(false); mob.addScoreboardTag(TAG);
                mob.customName(Component.text(name.isBlank() ? "ブロック泥棒" : name, NamedTextColor.LIGHT_PURPLE)); mob.setCustomNameVisible(true);
            });
            thieves.add(new Thief(e, tick + 20 * 15));
        }
        tell("ブロック泥棒が " + count + " 体出現！15秒間ブロックを盗みます。" + named(name));
    }
    private record Thief(Enderman entity, int expires) {}
    void prison(int seconds) { doubleTime.launch("prison", () -> prisonOnce(seconds)); }
    private void prisonOnce(int seconds) {
        require(); Player p = main();
        if (p == null || !p.getWorld().equals(world)) throw new IllegalArgumentException("同じワールドのメインプレイヤーを set_main_user で設定してください。");
        if (prisoner != null) throw new IllegalArgumentException("ガラスの牢獄は既に発動中です。");
        prisonOrigin = centre().add(0, 3, 0); prisoner = p.getUniqueId(); prisonUntil = tick + seconds * 20;
        for (int y = -1; y <= 2; y++) for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            if (x == 0 && z == 0 && y >= 0 && y <= 1) continue;
            prisonDisplays.add(display(prisonOrigin.clone().add(x, y, z), Material.GLASS, 1));
        }
        p.teleport(prisonOrigin.clone().add(0, -.45, 0)); tell("ガラスの牢獄！ " + seconds + " 秒");
    }
    private void releasePrison() {
        UUID id = prisoner; prisoner = null;
        for (var d : prisonDisplays) d.remove(); prisonDisplays.clear();
        Player p = id == null ? null : Bukkit.getPlayer(id);
        if (p != null && box != null) { p.setFallDistance(0); teleport(p); }
    }
    private void effectsTick() {
        var iterator = bombs.iterator();
        while (iterator.hasNext()) {
            Bomb bomb = iterator.next();
            if (bomb.physical) {
                if (!bomb.display.isValid()) { iterator.remove(); continue; }
                if (++bomb.age < bomb.duration) continue;
                Location at = bomb.display.getLocation().add(0, .01, 0); bomb.display.remove(); iterator.remove();
                if (!at.getWorld().equals(world)) continue;
                if (shieldsExplosion(at)) explosionVisual(at); else explode(at, bomb.radius, bomb.framePower);
                // A frame collapse clears the effect list; stop iterating it.
                if (frameHealth.isCollapsing()) return;
                continue;
            }
            double t = Math.min(1, (double) ++bomb.age / bomb.duration);
            Vector pos = bomb.start.clone().multiply(1 - t * t).add(bomb.end.clone().multiply(t * t));
            Location previous = bomb.display.getLocation();
            var hit = lid == null ? null : lid.intercept(new BedrockLid.Point(previous.getX(), previous.getY(), previous.getZ()),
                    new BedrockLid.Point(pos.getX(), pos.getY(), pos.getZ()), bomb.halfSize);
            if (hit != null) {
                Location at = new Location(world, hit.x(), hit.y(), hit.z());
                bomb.display.remove(); iterator.remove();
                if (bomb.lightning) world.strikeLightningEffect(at);
                explosionVisual(at); // Impact stays above the real bedrock; no interior blast is queued.
                continue;
            }
            bomb.display.teleport(pos.toLocation(world));
            if (t >= 1) {
                Location at = bomb.end.toLocation(world); bomb.display.remove(); iterator.remove();
                if (bomb.lightning) world.strikeLightningEffect(at);
                explode(at, bomb.radius, bomb.framePower);
                if (frameHealth.isCollapsing()) return;
            }
        }
        if (cometUntil > tick && tick % cometInterval == 0 && bombs.size() + blasts.size() + salmons.size() < settings.effectsMaxActive())
            spawnBomb(randomTarget(), settings.cometPower(), 25, Material.MAGMA_BLOCK, .7f, cometName, false);
        if (holeUntil > 0) {
            if (tick < holeUntil) {
                if (tick % 3 == 0) world.spawnParticle(Particle.PORTAL, holeCenter, 30, 1, 1, 1, .5);
                for (Player p : Bukkit.getOnlinePlayers()) if (p.getWorld().equals(world) && p.getLocation().distanceSquared(holeCenter) < 100 && !p.getUniqueId().equals(prisoner)) {
                    Vector delta = holeCenter.toVector().subtract(p.getLocation().toVector());
                    if (delta.lengthSquared() > 1) p.setVelocity(delta.normalize().multiply(.3));
                }
            } else { explode(holeCenter.clone().add(0, -2, 0), settings.blackholePower()); holeUntil = 0; }
        }
        var ti = thieves.iterator();
        while (ti.hasNext()) {
            Thief thief = ti.next();
            if (tick >= thief.expires || !thief.entity.isValid()) { thief.entity.remove(); ti.remove(); continue; }
            if (tick % 20 == 0 && fill.filled() > 0) {
                int start = random.nextInt(fill.capacity());
                for (int offset = 0; offset < fill.capacity(); offset++) {
                    int i = (start + offset) % fill.capacity(); if (!fill.occupied(i)) continue;
                    Block b = blockAt(i); if (tileGuard.isTile(b.getType())) continue;
                    thief.entity.teleport(b.getLocation().add(.5, 1, .5)); thief.entity.setCarriedBlock(b.getBlockData());
                    b.setBlockData(data(Material.AIR), false); update(i,b); break;
                }
            }
        }
        if (prisoner != null) {
            Player p = Bukkit.getPlayer(prisoner);
            if (tick >= prisonUntil || p == null || !p.getWorld().equals(world)) releasePrison();
            else {
                p.setFallDistance(0); p.setVelocity(new Vector());

            }
        }
    }
    private void cleanupEffects() {
        doubleTime.cancelPending();
        rocket.stop();
        for (ExplodingSalmon salmon : salmons) salmon.entity().remove(); salmons.clear();
        settleAllGravity();
        cancelFountain(); for (Bomb b : bombs) b.display.remove(); bombs.clear(); blasts.clear();
        for (Thief t : thieves) t.entity.remove(); thieves.clear();
        releasePrison(); removeLid(); cometUntil = 0; holeUntil = 0; frozenUntil = 0;
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void place(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced(); if(preparing(b)){e.setCancelled(true);return;} if (box == null || !same(b)) return;
        if (lidBlock(b) || (busy() && envelope(b)) || (shell(b) && !edit)) { e.setCancelled(true); return; }
        if (toplock && Math.abs(b.getX() - box.x()) < box.radius() && Math.abs(b.getZ() - box.z()) < box.radius() && b.getY() > box.y() + box.height()) {
            e.setCancelled(true); return;
        }
        if (!inside(b)) return;
        if (tick < frozenUntil || e.getPlayer().getUniqueId().equals(prisoner)) {
            e.setCancelled(true); e.getPlayer().sendActionBar(Component.text("今はブロックを設置できません。", NamedTextColor.AQUA)); return;
        }
        if (tileGuard.isTile(b.getType()) || !fillMaterial(b.getType())) {
            e.setCancelled(true); e.getPlayer().sendActionBar(Component.text("通常の固体ブロックを使ってください。")); return;
        }
        int index = box.index(b.getX(), b.getY(), b.getZ());
        if (!autoreplace && b.getType() != material(index)) { e.setCancelled(true); e.getPlayer().sendActionBar(Component.text("この層は " + material(index).name() + " で埋めてください。")); return; }
        // Defer normalization until the placement transaction has finished.
        Box expected = box;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (box != expected || busy() || e.isCancelled()) return;
            if (autoreplace && counts(b)) b.setBlockData(data(material(index)), false);
            update(b);
        });
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent e) {
        if(preparing(e.getBlock())){e.setCancelled(true);return;}
        if (box == null) return;
        if (lidBlock(e.getBlock()) || (busy() && envelope(e.getBlock())) || (shell(e.getBlock()) && !edit)) { e.setCancelled(true); return; }
        if (inside(e.getBlock())) {
            e.setDropItems(false); Box expected = box;
            Bukkit.getScheduler().runTask(plugin, () -> { if (box == expected && !busy()) update(e.getBlock()); });
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void rocketDamage(EntityDamageByEntityEvent e) {
        if(e.getDamager().getScoreboardTags().contains(TntRocket.TAG) && e.getEntity() instanceof Player p
                && inside(p.getLocation().getBlock())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void explosion(EntityExplodeEvent e) {
        if(e.getEntity().getScoreboardTags().contains(TntRocket.TAG))e.blockList().removeIf(b->!inside(b));
        protectExplosion(e.blockList(), e.getLocation());
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void explosion(BlockExplodeEvent e) { protectExplosion(e.blockList(), e.getBlock().getLocation()); }
    private void protectExplosion(List<Block> blocks, Location source) {
        blocks.removeIf(this::preparing);
        if (box == null) return;
        boolean roofStopsBlast = lid != null && source.getWorld().equals(world) && source.getY() >= lid.topY();
        blocks.removeIf(b -> lidBlock(b) || (roofStopsBlast && inside(b)) || (busy() && envelope(b)) || (shell(b) && !edit));
        Box expected = box;
        var affected = blocks.stream().filter(this::inside).toList();
        Bukkit.getScheduler().runTask(plugin, () -> { if (box == expected && !busy()) affected.forEach(this::update); });
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void piston(BlockPistonExtendEvent e) {
        if (envelope(e.getBlock()) || e.getBlocks().stream().anyMatch(b -> envelope(b) || envelope(b.getRelative(e.getDirection())))) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void piston(BlockPistonRetractEvent e) {
        if (envelope(e.getBlock()) || e.getBlocks().stream().anyMatch(b -> envelope(b) || envelope(b.getRelative(e.getDirection())))) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void flow(BlockFromToEvent e) { if (envelope(e.getBlock()) || envelope(e.getToBlock())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void burn(BlockBurnEvent e) { if (envelope(e.getBlock())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void entityChange(EntityChangeBlockEvent e) {
        if (e.getEntity() instanceof FallingBlock fb && (falling.containsKey(fb) || fb.getScoreboardTags().contains(GRAVITY_TAG))) {
            e.setCancelled(true); recoverGravity(fb);
            if (box != null) settleGravity(fb, e.getBlock()); else fb.remove();
            return;
        }
        if (e.getEntity().getScoreboardTags().contains(FrameHealth.FALLING_TAG)) return;
        if (envelope(e.getBlock()) || e.getEntity().getScoreboardTags().contains(TAG)) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void move(PlayerMoveEvent e) {
        if (e.getPlayer().getUniqueId().equals(prisoner) && prisonOrigin != null && e.getTo() != null) {
            Location to = e.getTo();
            if (!to.getWorld().equals(world) || Math.abs(to.getX() - prisonOrigin.getX()) > .25 || Math.abs(to.getZ() - prisonOrigin.getZ()) > .25 || Math.abs(to.getY() - (prisonOrigin.getY() - .45)) > .1) {
                Location locked = prisonOrigin.clone().add(0, -.45, 0); locked.setYaw(to.getYaw()); locked.setPitch(to.getPitch()); e.setTo(locked);
            }
        }
    }
    @EventHandler public void quit(PlayerQuitEvent e) { BlockReach.apply(e.getPlayer().getAttribute(org.bukkit.attribute.Attribute.BLOCK_INTERACTION_RANGE), 0); if (e.getPlayer().getUniqueId().equals(prisoner)) releasePrison(); }
    @EventHandler public void death(PlayerDeathEvent e) { if (e.getEntity().getUniqueId().equals(prisoner)) releasePrison(); }
    @EventHandler public void chunkLoad(ChunkLoadEvent e) {
        if(box==null || !e.getWorld().equals(world))return;
        Chunk chunk=e.getChunk();
        if(chunk.getX()<((box.x()-box.radius())>>4)-2 || chunk.getX()>((box.x()+box.radius())>>4)+2
                || chunk.getZ()<((box.z()-box.radius())>>4)-2 || chunk.getZ()>((box.z()+box.radius())>>4)+2)return;
        Entity[] entities=chunk.getEntities();
        for (Entity entity : entities) recoverGravity(entity);
        // Only stale entities: live entities in the lists are retained.
        for (Entity entity : entities) if (entity.getScoreboardTags().contains(TAG)
                && flights.stream().noneMatch(f -> f.display.equals(entity)) && bombs.stream().noneMatch(b -> b.display.equals(entity))
                && thieves.stream().noneMatch(t -> t.entity.equals(entity)) && salmons.stream().noneMatch(f -> f.entity().equals(entity)) && !prisonDisplays.contains(entity)) entity.remove();
    }
}
