package jp.rai42.umetate;

import java.util.*;
import org.bukkit.configuration.ConfigurationSection;

/** Immutable, validated configuration snapshot; rebuilt only on explicit configuration changes. */
public record Settings(SizeRules sizes,
    double blockReach,
    boolean autoreplace,
    boolean fireworks,
    boolean toplock,
    String boundaryMaterial,
    long workNanos,
    boolean instantBoxChanges,
    long instantWorkNanos,
    long gravityWorkNanos,
    boolean gravityEnabled,
    double fountainArc,
    boolean salmonDamageEntities,
    Frame frame,
    DoubleTime doubleTime,
    Map<String,String> titles,
    int winSeconds,
    int performanceReconcilePerTick,
    int performanceChangesPerTick,
    int gravityBlocksPerStep,
    int gravityIntervalTicks,
    int gravityScanPerStep,
    int fountainBlocksPerTick,
    int fountainFlightTicks,
    int fountainMaxDisplays,
    int effectsMaxActive,
    int effectsMaxEndermen,
    int effectsMaxChainTnt,
    double tntPower,
    double meteorPower,
    double zeusPower,
    double cometPower,
    double blackholePower,
    boolean winFireworks,
    boolean clearDragon) {
    public Settings { titles=Map.copyOf(titles); }
    public record Frame(boolean enabled,double maximum,int saveInterval,int releaseBudget,long rebuildDelay,List<String> materials,
                        int launchPerTick,int maxFlying,boolean cracks) {
        public Frame { materials=List.copyOf(materials); }
    }
    public record DoubleTime(int maxSeconds,int maxMultiplier,int defaultSeconds,int defaultMultiplier,int stagger,List<String> excluded,Map<String,String> messages,Map<String,String> sounds) {
        public DoubleTime { excluded=List.copyOf(excluded);messages=Map.copyOf(messages);sounds=Map.copyOf(sounds); }
    }
    public static Settings read(ConfigurationSection c) {
        double reach=c.getDouble("block-reach",20);
        if(!Double.isFinite(reach)||reach<0||reach>64)reach=20;
        var frame=new Frame(c.getBoolean("frame-health.enabled",true),finite(c.getDouble("frame-health.max-health",10000),10000,1,1000000000),
            Math.max(1,c.getInt("frame-health.save-interval-ticks",40)),Math.max(1,c.getInt("frame-health.release-blocks-per-tick",200)),
            Math.max(20,c.getLong("frame-health.rebuild-delay-ticks",100)),c.getStringList("bedrock-integration.valid-boundary-materials"),
            bounded(c,"frame-health.collapse.launch-per-tick",40,1,1000),bounded(c,"frame-health.collapse.max-flying",600,1,10000),
            c.getBoolean("frame-health.cracks",true));
        var doubled=new DoubleTime(bounded(c,"double-time.max-seconds",600,1,Integer.MAX_VALUE/20),bounded(c,"double-time.max-multiplier",10,2,1000),
            c.getInt("double-time.default-seconds",30),c.getInt("double-time.multiplier",2),Math.max(0,c.getInt("double-time.stagger-ticks",5)),
            c.contains("double-time.excluded-effects")?c.getStringList("double-time.excluded-effects"):List.of("prison","freeze"),
            strings(c,"double-time.messages"),strings(c,"double-time.sound"));
        var titles=new HashMap<String,String>();
        for(String kind:List.of("win","cancel"))for(String suffix:List.of("color","text")) {
            String key=kind+"-"+suffix;if(c.isString(key))titles.put(key,c.getString(key));
        }
        return new Settings(SizeRules.read(c),reach,c.getBoolean("autoreplace",true),c.getBoolean("fireworks",true),c.getBoolean("toplock",true),
            c.getString("default-boundary-material","GLASS"),nanos(c,"performance.max-work-ms-per-tick"),c.getBoolean("performance.instant-box-changes",true),(long)(finite(c.getDouble("performance.instant-max-ms-per-tick",20),20,.5,1000)*1000000),nanos(c,"gravity.max-work-ms-per-tick"),
            c.getBoolean("gravity.enabled",true),finite(c.getDouble("fountain.arc-height",6),6,-Double.MAX_VALUE,Double.MAX_VALUE),
            c.getBoolean("salmon.damage-entities",true),frame,doubled,titles,
            bounded(c,"win-seconds",15,1,3600),
            bounded(c,"performance.reconcile-per-tick",64,1,2000),
            bounded(c,"performance.changes-per-tick",1500,1,5000),
            bounded(c,"gravity.blocks-per-step",256,1,1500),
            bounded(c,"gravity.interval-ticks",2,1,20),
            bounded(c,"gravity.scan-per-step",2000,1,10000),
            bounded(c,"fountain.blocks-per-tick",4,1,20),
            bounded(c,"fountain.flight-ticks",30,10,100),
            bounded(c,"fountain.max-displays",80,1,2000),
            bounded(c,"effects.max-active",100,12,200),
            bounded(c,"effects.max-endermen",30,1,50),
            bounded(c,"effects.max-chain-tnt",100,1,1000),
            finite(c.getDouble("effects.tnt-radius",3),3,1,20),finite(c.getDouble("effects.meteor-radius",8),8,1,20),finite(c.getDouble("effects.zeus-radius",10),10,1,20),finite(c.getDouble("effects.comet-radius",2.5),2.5,1,20),finite(c.getDouble("effects.blackhole-radius",12),12,1,20),
            c.getBoolean("effects.win-fireworks",true),c.getBoolean("effects.clear-dragon",true));
    }
    private static int bounded(ConfigurationSection c,String key,int fallback,int min,int max){return Math.clamp(c.getInt(key,fallback),min,max);}
    private static double finite(double value,double fallback,double min,double max){return Double.isFinite(value)?Math.clamp(value,min,max):fallback;}
    private static long nanos(ConfigurationSection c,String key){return (long)(finite(c.getDouble(key,2),2,.5,10)*1000000);}
    private static Map<String,String> strings(ConfigurationSection c,String path){
        var result=new HashMap<String,String>();var section=c.getConfigurationSection(path);
        if(section!=null)for(String key:section.getKeys(false))if(section.isString(key))result.put(key,section.getString(key));
        return result;
    }
}
