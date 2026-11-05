package jp.rai42.umetate;

import java.io.File;
import java.io.IOException;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.boss.*;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/** Ported from raibr 1.2.1 FrameHealth; distance, 64-sample exposure and collapse timing are preserved. */
public final class FrameHealth implements Listener {
   static final String FALLING_TAG = "raiumetate_frame_falling";
   private final JavaPlugin plugin;
   private Settings.Frame settings;
   private long releaseNanos;
   private final TileGuard tileGuard=new TileGuard();
   private final FrameArena monitor;
   private final File stateFile;
   private final ArenaStore store;
   private final EnumSet<Material> boundaryMaterials=EnumSet.noneOf(Material.class);
   private final Deque<java.util.concurrent.CompletableFuture<Void>> writes=new ArrayDeque<>();
   private BukkitTask saveTask;
   private boolean dirty,saveFailed,stopped;
   private int saveTicks,saveInterval;
   private java.util.concurrent.CompletableFuture<Void> collapseCheckpoint;
   private final Map<UUID, Double> pendingTnt = new HashMap<>();
   private final Map<String, String> savedFrame = new LinkedHashMap<>();
   private BossBar bar;
   private BukkitTask displayTask, releaseTask, rebuildTask;
   private ArenaBounds collapseBounds;
   private String signature = "";
   private double hp;
   private volatile boolean collapsing;
   private volatile boolean arenaAvailable;
   private volatile double overlayHealth;
   private volatile double overlayMaximum;
   private volatile double lastDamage;
   private volatile long revision;
   private double maxHealth;
   private String lastTitle="";
   private double lastProgress=-1;
   private BarColor lastColor;
   /** Crack overlay ids sit far below real entity ids; each frame block needs its own id or the client shows only one. */
   static final int CRACK_ID_BASE=-0x40000000;
   /** The client forgets a crack after 400 ticks; resend well before that. */
   static final int CRACK_RESEND_TICKS=200;
   private List<Block> crackBlocks=List.of();
   private int[] crackStages=new int[0];
   private String crackSignature="";
   private int crackAge;
   private final Set<UUID> crackViewers=new HashSet<>();

   FrameHealth(JavaPlugin plugin, FrameArena monitor) {
      this.plugin = plugin; this.monitor = monitor;
      this.stateFile = new File(plugin.getDataFolder(), "frame-health-state.yml");
      store=new ArenaStore(stateFile.toPath());
      reloadSettings(Settings.read(plugin.getConfig()));
      maxHealth=settings.maximum();
   }
   void reloadSettings(Settings updated) {
      settings=updated.frame();releaseNanos=updated.instantWorkNanos();saveInterval=settings.saveInterval();boundaryMaterials.clear();
      for(String name:settings.materials()) {
         try{boundaryMaterials.add(Material.valueOf(name));}catch(IllegalArgumentException e){plugin.getLogger().warning("不正な枠素材を無視: "+name);}
      }
   }
   private double maximum() { return maxHealth; }
   public boolean isCollapsing() { return collapsing; }
   private static String signature(ArenaBounds b) { return b.world().getUID()+":"+b.minX()+":"+b.maxX()+":"+b.floorY()+":"+b.topY()+":"+b.minZ()+":"+b.maxZ(); }
   private static String hpArenaKey(ArenaBounds b) { return b.world().getUID()+":"+b.floorY(); }
   public void start() {
      YamlConfiguration state = YamlConfiguration.loadConfiguration(stateFile);
      maxHealth=Math.clamp(state.getDouble("max-health",maxHealth),1,1000000000);
      hp = Math.max(0, Math.min(maximum(), state.getDouble("health", maximum())));
      publish();
      signature = state.getString("arena", "");
      if (state.isConfigurationSection("frame")) {
         for (String key : state.getConfigurationSection("frame").getKeys(false)) savedFrame.put(key, state.getString("frame."+key));
      }
      bar = Bukkit.createBossBar("枠HP", BarColor.GREEN, BarStyle.SEGMENTED_20);
      Bukkit.getPluginManager().registerEvents(this, plugin);
      displayTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 1, 10);
      refresh();
      saveTask=Bukkit.getScheduler().runTaskTimer(plugin,this::saveTick,1,1);
   }
   void refresh() {
      monitor.refresh();
      Optional<ArenaBounds> current = monitor.current();
      arenaAvailable = current.isPresent();
      if (current.isEmpty()) { publish(); bar.setVisible(false); return; }
      ArenaBounds b = current.get();
      if (!signature(b).equals(signature)) {
         if (collapsing) { abortCollapse(); }
         savedFrame.clear(); adoptArena(b);
         save();
      } else if (!collapsing && !savedFrame.isEmpty()) {
         collapseBounds = b; restoreFrame();
      }
      hp = Math.min(hp, maximum());
      publish();
      updateDisplay();
      bar.setVisible(settings.enabled());
      for (Player p : new ArrayList<>(bar.getPlayers())) if (!p.isOnline() || !p.getWorld().equals(b.world())) bar.removePlayer(p);
      for (Player p : b.world().getPlayers()) if (!bar.getPlayers().contains(p)) bar.addPlayer(p);
      updateCracks(b,10);
   }
   /** Switches to b; HP carries over only when the same box was resized, any other arena starts full. */
   private void adoptArena(ArenaBounds b) {
      boolean resizedSameArena=!signature.isEmpty() && stateArenaKey(signature).equals(hpArenaKey(b));
      signature=signature(b);
      if(!resizedSameArena) hp=maximum();
   }
   private static String stateArenaKey(String value) {
      if(value==null || value.isEmpty()) return "";
      String[] parts=value.split(":");
      if(parts.length!=7) return "";
      return parts[0]+":"+parts[3];
   }
   static String barTitle(double hp,double max,String size,String count,boolean collapsing) {
      if(collapsing)return "枠崩壊 — 再生成中";
      return String.format(Locale.US,"枠HP %,d / %,d (%d%%) | %s | %s",(long)Math.ceil(hp),(long)max,(int)Math.floor(hp/max*100),size,count);
   }
   void updateDisplay() {
      if(bar==null)return;
      String title=barTitle(hp,maximum(),monitor.sizeLabel(),monitor.countLabel(),collapsing);
      double progress=Math.max(0,Math.min(1,hp/maximum()));
      BarColor color=hp>maximum()*.5?BarColor.GREEN:hp>maximum()*.2?BarColor.YELLOW:BarColor.RED;
      if(!title.equals(lastTitle)){bar.setTitle(title);lastTitle=title;}
      if(progress!=lastProgress){bar.setProgress(progress);lastProgress=progress;}
      if(color!=lastColor){bar.setColor(color);lastColor=color;}
   }
   /** Crack stage 0-9 for one frame block, or -1 for none. Each block starts cracking at its own point so the frame does not crack uniformly. */
   static int crackStage(double hp,double max,int x,int y,int z) {
      double lost=1-Math.max(0,Math.min(1,hp/max));
      if(lost<=0)return -1;
      double p=lost*1.15-crackNoise(x,y,z)*.15;
      if(p<=0)return -1;
      return Math.min(9,(int)(p*10));
   }
   static double crackNoise(int x,int y,int z) {
      long h=x*73856093L^y*19349663L^z*83492791L;
      h^=h>>>13;h*=0x5bd1e995L;h^=h>>>15;
      return (h&0xffff)/65536.0;
   }
   /** Paper maps progress to stage with (int)(9*progress) and clears at exactly 0. */
   static float crackProgress(int stage) { return stage<0?0f:Math.min(1f,(stage+.5f)/9f); }
   /** Sends the crack overlay only for blocks whose stage changed, plus a periodic resend so the client keeps it. */
   private void updateCracks(ArenaBounds b,int elapsed) {
      boolean enabled=settings.enabled() && settings.cracks() && !collapsing;
      String sig=signature(b);
      crackAge+=elapsed;
      boolean resend=crackAge>=CRACK_RESEND_TICKS;
      if(!enabled) { clearCracks(); return; }
      if(!sig.equals(crackSignature) || resend) {
         List<Block> found=boundaryFrameBlocks(b);
         if(!sig.equals(crackSignature) || !found.equals(crackBlocks)) {
            clearCracks();crackBlocks=found;crackStages=new int[found.size()];Arrays.fill(crackStages,-1);crackSignature=sig;
         }
      }
      if(resend)crackAge=0;
      List<Player> viewers=b.world().getPlayers();
      Set<UUID> now=new HashSet<>();
      for(Player p:viewers)now.add(p.getUniqueId());
      crackViewers.retainAll(now);
      for(int i=0;i<crackBlocks.size();i++) {
         Block block=crackBlocks.get(i);
         int stage=crackStage(hp,maximum(),block.getX(),block.getY(),block.getZ());
         boolean changed=stage!=crackStages[i];
         if(!changed && stage<0)continue;
         Location at=block.getLocation();
         for(Player p:viewers) {
            if(changed || resend || !crackViewers.contains(p.getUniqueId())) p.sendBlockDamage(at,crackProgress(stage),CRACK_ID_BASE+i);
         }
         crackStages[i]=stage;
      }
      crackViewers.addAll(now);
   }
   /** Removes every crack this plugin is showing (collapse, rebuild, arena change). */
   private void clearCracks() {
      if(crackBlocks.isEmpty())return;
      World w=crackBlocks.getFirst().getWorld();
      List<Player> viewers=w==null?List.of():w.getPlayers();
      for(int i=0;i<crackBlocks.size();i++) {
         if(crackStages[i]<0)continue;
         Location at=crackBlocks.get(i).getLocation();
         for(Player p:viewers)p.sendBlockDamage(at,0f,CRACK_ID_BASE+i);
         crackStages[i]=-1;
      }
   }
   /** Frame blocks on the floor and the four walls only, without walking the inside of the box. */
   private List<Block> boundaryFrameBlocks(ArenaBounds b) {
      List<Block> found=new ArrayList<>();
      for(int x=b.minX();x<=b.maxX();x++) for(int z=b.minZ();z<=b.maxZ();z++) {
         boolean wall=x==b.minX() || x==b.maxX() || z==b.minZ() || z==b.maxZ();
         for(int y=b.floorY();y<=(wall?b.topY():b.floorY());y++) {
            Block block=b.world().getBlockAt(x,y,z);
            if(frameMaterial(block))found.add(block);
         }
      }
      return found;
   }
   private boolean boundary(ArenaBounds b, int x, int y, int z) {
      return x>=b.minX() && x<=b.maxX() && z>=b.minZ() && z<=b.maxZ() && y>=b.floorY() && y<=b.topY()
         && (y==b.floorY() || x==b.minX() || x==b.maxX() || z==b.minZ() || z==b.maxZ());
   }
   private boolean frameMaterial(Block block) {
      Material type=block.getType();
      // Test doubles stand in for Material constants; real servers hit the direct lookup.
      return boundaryMaterials.contains(type.getClass()==Material.class?type:Material.valueOf(type.name()));
   }
   /** Sample the same 1x1x1 target volume at each extant frame block; count one strongest hit per explosion. */
   double damageAt(ArenaBounds b, Location origin, double power) {
      if (origin.getWorld()!=b.world() || !Double.isFinite(power) || power<=0) return 0;
      if (monitor.shieldsExplosion(origin)) return 0;
      double reach=power*2,ox=origin.getX(),oy=origin.getY(),oz=origin.getZ();
      int r=(int)Math.ceil(reach),x0=Math.max(b.minX(),origin.getBlockX()-r),x1=Math.min(b.maxX(),origin.getBlockX()+r);
      int y0=Math.max(b.floorY(),origin.getBlockY()-r),y1=Math.min(b.topY(),origin.getBlockY()+r);
      int z0=Math.max(b.minZ(),origin.getBlockZ()-r),z1=Math.min(b.maxZ(),origin.getBlockZ()+r);
      // Only the floor and walls can be frame blocks: walk those columns instead of the whole cube.
      // Frame blocks hidden behind a solid region take exactly 0 damage, so they are dropped before sorting.
      List<double[]> occluders=monitor.occluders();
      List<Block> candidates=new ArrayList<>();List<Double> distances=new ArrayList<>();
      for(int x=x0;x<=x1;x++) for(int z=z0;z<=z1;z++) {
         boolean wall=x==b.minX() || x==b.maxX() || z==b.minZ() || z==b.maxZ();
         for(int y=wall?y0:b.floorY();y<=(wall?y1:Math.min(y1,b.floorY()));y++) {
            if(y<y0)continue;
            double dx=x+.5-ox,dy=y+.5-oy,dz=z+.5-oz,d2=dx*dx+dy*dy+dz*dz;
            if(d2>=reach*reach)continue;
            if(hidden(occluders,ox,oy,oz,x,y,z))continue;
            Block block=b.world().getBlockAt(x,y,z);
            if(frameMaterial(block)){candidates.add(block);distances.add(Math.sqrt(d2));}
         }
      }
      Integer[] order=new Integer[candidates.size()];
      for(int i=0;i<order.length;i++)order[i]=i;
      java.util.Arrays.sort(order,Comparator.comparingDouble(distances::get));
      double strongest=0;
      for(int index:order) {
         Block target=candidates.get(index);
         double distance=distances.get(index);
         if(BlastDamage.calculate(power,distance,1)<=strongest) break;
         final int total=64;int visible=0;boolean beaten=false;
         for(int ray=0;ray<total;ray++) {
            // Stop once even all remaining rays visible could not beat the strongest hit so far.
            if(BlastDamage.calculate(power,distance,(double)(visible+total-ray)/total)<=strongest){beaten=true;break;}
            int x=ray/16,y=ray/4%4,z=ray%4;
            Location sample=target.getLocation().add(.001+x*.998/3,.001+y*.998/3,.001+z*.998/3);
            Vector direction=sample.toVector().subtract(origin.toVector()); double length=direction.length();
            if(length<.001) {visible++;continue;}
            // A ray through a solid region hits a cube there before reaching the frame: no world trace needed.
            if(blocked(occluders,ox,oy,oz,sample.getX(),sample.getY(),sample.getZ()))continue;
            RayTraceResult hit=b.world().rayTraceBlocks(origin,direction.normalize(),length,FluidCollisionMode.NEVER,true);
            if(hit==null || target.equals(hit.getHitBlock())) visible++;
         }
         if(!beaten)strongest=Math.max(strongest,BlastDamage.calculate(power,distance,(double)visible/total));
      }
      return strongest;
   }
   /**
    * True when all eight extreme sample rays to the target pass strictly through the same occluder.
    * The targets whose ray crosses a convex region form a convex set, so every inner sample ray is blocked too.
    */
   static boolean hidden(List<double[]> occluders,double ox,double oy,double oz,int tx,int ty,int tz) {
      final double lo=.001,hi=.999,shrink=1e-3;
      for(double[] r:occluders) {
         boolean all=true;
         for(int corner=0;corner<8 && all;corner++)
            all=crosses(ox,oy,oz,tx+((corner&1)==0?lo:hi),ty+((corner&2)==0?lo:hi),tz+((corner&4)==0?lo:hi),
               r[0]+shrink,r[1]+shrink,r[2]+shrink,r[3]-shrink,r[4]-shrink,r[5]-shrink);
         if(all)return true;
      }
      return false;
   }
   static boolean blocked(List<double[]> occluders,double ox,double oy,double oz,double sx,double sy,double sz) {
      final double shrink=1e-3;
      for(double[] r:occluders)
         if(crosses(ox,oy,oz,sx,sy,sz,r[0]+shrink,r[1]+shrink,r[2]+shrink,r[3]-shrink,r[4]-shrink,r[5]-shrink))return true;
      return false;
   }
   /** Segment (a→b) against a closed box, slab method. */
   static boolean crosses(double ax,double ay,double az,double bx,double by,double bz,
                          double x0,double y0,double z0,double x1,double y1,double z1) {
      double[] t={0,1};
      return slab(ax,bx-ax,x0,x1,t) && slab(ay,by-ay,y0,y1,t) && slab(az,bz-az,z0,z1,t);
   }
   /** Narrows t to where start+t*delta lies within [min,max] on one axis; false once empty. */
   private static boolean slab(double start,double delta,double min,double max,double[] t) {
      if(Math.abs(delta)<1e-12)return start>=min && start<=max;
      double u=(min-start)/delta,v=(max-start)/delta;
      if(u>v){double w=u;u=v;v=w;}
      if(u>t[0])t[0]=u;if(v<t[1])t[1]=v;
      return t[0]<=t[1];
   }
   @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
   public void prime(ExplosionPrimeEvent event) {
      if (!(event.getEntity() instanceof TNTPrimed) || collapsing) return;
      monitor.refresh();
      monitor.current().ifPresent(b->{
         UUID id=event.getEntity().getUniqueId();
         pendingTnt.put(id,damageAt(b,event.getEntity().getLocation(),event.getRadius()));
         Bukkit.getScheduler().runTaskLater(plugin,()->pendingTnt.remove(id),2);
      });
   }
   @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
   public void exploded(EntityExplodeEvent event) {
      if (!(event.getEntity() instanceof TNTPrimed tnt) || collapsing) return;
      monitor.refresh();
      monitor.current().ifPresent(b->{
         Double damage=pendingTnt.remove(tnt.getUniqueId());
         hit(b,damage!=null?damage:damageAt(b,event.getLocation(),tnt.getYield()));
      });
   }
   @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
   public void protectFrame(EntityExplodeEvent event) {
      if(collapsing || !(event.getEntity() instanceof TNTPrimed) || !settings.enabled()) return;
      monitor.refresh();
      monitor.current().ifPresent(b->{
         if(event.getLocation().getWorld().equals(b.world())) event.blockList().removeIf(block->boundary(b,block.getX(),block.getY(),block.getZ()));
      });
   }
   void salmonExplosion(ArenaBounds b,Location at,double power) { hit(b,damageAt(b,at,power)); }
   void customExplosion(Location at,double power) {
      if(collapsing)return;
      monitor.current().ifPresent(b -> hit(b,damageAt(b,at,power)));
   }
   private void hit(ArenaBounds b,double damage) {
      if(saveFailed || collapsing || damage<=0 || !settings.enabled()) return;
      if(!signature(b).equals(signature)) adoptArena(b);
      hp=Math.max(0,hp-damage);
      lastDamage=damage;revision++;publish();
      if(hp==0) beginCollapse(b); else { save(); updateCracks(b,0); }
      updateDisplay();
   }
   private List<Block> frameBlocks(ArenaBounds b) {
      List<Block> blocks=new ArrayList<>();
      for(int x=b.minX();x<=b.maxX();x++) for(int y=b.floorY();y<=b.topY();y++) for(int z=b.minZ();z<=b.maxZ();z++)
         if(boundary(b,x,y,z)) { Block block=b.world().getBlockAt(x,y,z); if(frameMaterial(block)) blocks.add(block); }
      return blocks;
   }
   private void beginCollapse(ArenaBounds b) {
      collapsing=true;collapseBounds=b;clearCracks();
      publish();
      for(Block block:frameBlocks(b)) savedFrame.put(block.getX()+","+block.getY()+","+block.getZ(),block.getBlockData().getAsString());
      collapseCheckpoint=saveNow();
      var title=net.kyori.adventure.title.Title.title(
          net.kyori.adventure.text.Component.text("-1 Win",net.kyori.adventure.text.format.NamedTextColor.RED),
          net.kyori.adventure.text.Component.text("枠が壊れた！"),
          net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(250),java.time.Duration.ofMillis(2000),java.time.Duration.ofMillis(500)));
      for(Player player:b.world().getPlayers())player.showTitle(title);
      ((UmetatePlugin)plugin).requestWin(false,WinService.Reason.FRAME_BROKEN);
      updateDisplay();
      // Start next tick, after the originating explosion finishes iterating its block list.
      rebuildTask=Bukkit.getScheduler().runTaskLater(plugin,()->releaseContents(b),1);
   }
   private boolean sameArena(ArenaBounds b) { monitor.refresh(); return monitor.current().map(v->signature(v).equals(signature(b))).orElse(false); }
   private void releaseContents(ArenaBounds b) {
      if(saveFailed)return;
      if(collapseCheckpoint!=null) {
         if(!collapseCheckpoint.isDone()){rebuildTask=Bukkit.getScheduler().runTaskLater(plugin,()->releaseContents(b),1);return;}
         try{collapseCheckpoint.getNow(null);}catch(RuntimeException e){persistenceFailed(e);return;}
         collapseCheckpoint=null;
      }
      if(!sameArena(b)){abortCollapse();return;}
      monitor.stopGimmicksForCollapse();
      for(String key:savedFrame.keySet()) {
         String[] p=key.split(","); Block block=b.world().getBlockAt(Integer.parseInt(p[0]),Integer.parseInt(p[1]),Integer.parseInt(p[2]));
         b.world().spawnParticle(Particle.BLOCK,block.getLocation().add(.5,.5,.5),3,.25,.25,.25,block.getBlockData());
         block.setType(Material.AIR,true);
      }
      b.world().playSound(b.topCenter(),"minecraft:block.glass.break",3,1);
      // Only a collapse uses sand-like falling blocks: all contents drop straight down and stay wherever they land.
      // Bottom layer first: a falling block with a solid block under it re-lands in the cell it just left.
      // Launches are spread over ticks and capped in flight: the whole box at once kept the server at ~15 TPS for ~15 s.
      int[] cursor={b.innerMinX(),b.innerMinY(),b.innerMinZ()};int[] settling={0};
      List<FallingBlock> flying=new ArrayList<>();
      var air=Bukkit.createBlockData(Material.AIR);
      releaseTask=Bukkit.getScheduler().runTaskTimer(plugin,()->{
         if(!sameArena(b)){abortCollapse();return;}
         flying.removeIf(e->!e.isValid());
         long deadline=System.nanoTime()+releaseNanos;int now=0;
         while(cursor[1]<=b.innerMaxY() && now<settings.launchPerTick()
               && flying.size()<settings.maxFlying() && System.nanoTime()<deadline) {
            Block block=b.world().getBlockAt(cursor[0],cursor[1],cursor[2]);
            Material type=block.getType();
            if(!type.isAir() && !block.isLiquid() && !(tileGuard.isTile(type))) {
               flying.add(drop(block));block.setBlockData(air,false);now++;
            }
            if(++cursor[0]>b.innerMaxX()){cursor[0]=b.innerMinX();if(++cursor[2]>b.innerMaxZ()){cursor[2]=b.innerMinZ();cursor[1]++;}}
         }
         // Everything is dropped (no cap since 2.6.0); rebuild once it has landed, or after 10 s if something never lands.
         if(cursor[1]>b.innerMaxY() && (flying.isEmpty() || ++settling[0]>COLLAPSE_SETTLE_TICKS)) {
            releaseTask.cancel();releaseTask=null;
            long delay=settings.rebuildDelay();
            rebuildTask=Bukkit.getScheduler().runTaskLater(plugin,this::restoreFrame,delay);
         }
      },1,1);
   }
   static final int COLLAPSE_SETTLE_TICKS=200;
   /** Lets one block fall straight down; it lands as a real block and is never cleaned up. */
   private FallingBlock drop(Block block) {
      BlockData data=block.getBlockData();
      FallingBlock entity=block.getWorld().spawnFallingBlock(block.getLocation().add(.5,0,.5),data);
      entity.addScoreboardTag(FALLING_TAG);entity.setDropItem(false);entity.setHurtEntities(false);
      if(java.util.concurrent.ThreadLocalRandom.current().nextInt(8)==0)block.getWorld().spawnParticle(Particle.BLOCK,block.getLocation().add(.5,.5,.5),4,.4,.4,.4,data);
      return entity;
   }
   private void restoreFrame() {
      if(collapseBounds==null || !sameArena(collapseBounds)){abortCollapse();return;}
      for(var entry:savedFrame.entrySet()) {
         String[] p=entry.getKey().split(",");
         collapseBounds.world().getBlockAt(Integer.parseInt(p[0]),Integer.parseInt(p[1]),Integer.parseInt(p[2])).setBlockData(Bukkit.createBlockData(entry.getValue()),true);
      }
      savedFrame.clear();collapsing=false;hp=maximum();lastDamage=0;revision++;publish();saveNow();
      crackSignature="";
      monitor.frameRestored();
   }
   private void abortCollapse() {
      if(releaseTask!=null)releaseTask.cancel();if(rebuildTask!=null)rebuildTask.cancel();
      releaseTask=null;rebuildTask=null;collapsing=false;savedFrame.clear();clearCracks();hp=maximum();lastDamage=0;revision++;publish();saveNow();
   }
   private void save(){dirty=true;}
   private java.util.concurrent.CompletableFuture<Void> saveNow() {
      Map<String,Object> values=new LinkedHashMap<>();values.put("arena",signature);values.put("health",hp);values.put("max-health",maximum());
      // Copy only immutable strings; YAML generation and filesystem I/O belong to the worker.
      values.put("frame",Map.copyOf(savedFrame));dirty=false;saveTicks=0;
      var write=store.write(Collections.unmodifiableMap(values));writes.add(write);return write;
   }
   private void persistenceFailed(RuntimeException error){saveFailed=true;monitor.persistenceFailed(error);plugin.getLogger().severe("枠HP保存失敗。枠処理を停止: "+error);}
   private void saveTick(){
      try{while(!writes.isEmpty() && writes.peek().isDone())writes.remove().getNow(null);}
      catch(RuntimeException e){persistenceFailed(e);}
      if(!saveFailed && ++saveTicks>=saveInterval){saveTicks=0;if(dirty)saveNow();}
   }
   public boolean command(CommandSender sender,String[] args) {
      if(!sender.hasPermission("raiumetate.admin"))throw new IllegalArgumentException("管理者権限が必要です。");
      refresh();
      if(args.length>1) {
         if(saveFailed || collapsing)throw new IllegalArgumentException("枠の再生成中、または保存エラーで停止中です。");
         String action=args[1].toLowerCase(Locale.ROOT);
         switch(action) {
            case "reset" -> {if(args.length!=2)throw hpUsage();hp=maximum();}
            case "heal" -> {if(args.length!=3)throw hpUsage();hp=Math.clamp(hp+hpNumber(args[2]),0,maximum());}
            case "limit" -> {
               if(args.length!=4)throw hpUsage();long n=hpNumber(args[3]);
               double next=switch(args[2].toLowerCase(Locale.ROOT)){case "set" -> n;case "add" -> maximum()+n;default -> throw hpUsage();};
               if(next<1 || next>1000000000)throw hpUsage();maxHealth=next;hp=Math.min(hp,maximum());
            }
            default -> throw hpUsage();
         }
         lastDamage=0;revision++;publish();
         if(hp==0) {var bounds=monitor.current().orElseThrow(()->new IllegalArgumentException("枠がありません。"));beginCollapse(bounds);}
         else save();
         updateDisplay();
      }
      sender.sendMessage("§6[埋め立て] §f枠HP: "+(long)Math.ceil(hp)+" / "+(long)maximum()+(collapsing?"（再生成中）":""));return true;
   }
   private static IllegalArgumentException hpUsage(){return new IllegalArgumentException("使い方: /hp [reset|heal <整数>|limit <set|add> <整数>]（絶対値1～1,000,000,000、最大HPは1以上）");}
   private static long hpNumber(String text){
      try {long n=Long.parseLong(text);if(n==0 || n < -1000000000 || n>1000000000)throw hpUsage();return n;}
      catch(NumberFormatException e){throw hpUsage();}
   }
   void clearArena() {
      if(collapsing)throw new IllegalStateException("枠の再生成中です。");
      clearCracks();signature="";savedFrame.clear();pendingTnt.clear();hp=maximum();lastDamage=0;arenaAvailable=false;revision++;publish();save();
      if(bar!=null){bar.removeAll();bar.setVisible(false);}
   }
   public void stop() {
      if(stopped)return;stopped=true;
      if(displayTask!=null)displayTask.cancel();if(releaseTask!=null)releaseTask.cancel();if(rebuildTask!=null)rebuildTask.cancel();
      if(saveTask!=null)saveTask.cancel();
      clearCracks();if(collapsing && !saveFailed)restoreFrame();saveNow();store.close();saveTick();if(bar!=null)bar.removeAll();
   }
   private void publish() {
      overlayHealth=hp;overlayMaximum=maximum();
      updateDisplay();
   }
   public FrameHealthSnapshot snapshot() {
      String status=!arenaAvailable ? "waiting" : collapsing ? "rebuilding" : "active";
      return new FrameHealthSnapshot(overlayHealth,overlayMaximum,lastDamage,revision,status);
   }
}
