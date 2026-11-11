package jp.rai42.umetate;
import org.bukkit.configuration.ConfigurationSection;
/** Public dimensions are interior diameters; Box retains its on-disk radius geometry. */
public record SizeRules(int minDiameter,int maxDiameter,int minHeight,int maxHeight,int defaultDiameter,int defaultHeight) {
    /** Hard safety limits; config.yml may lower them but never raise them. */
    static final int MAX_DIAMETER=53, MAX_HEIGHT=36;
    private static final int LEGACY_MAX_DIAMETER=99, LEGACY_MAX_HEIGHT=40;
    private static final SizeRules STANDARD=new SizeRules(3,MAX_DIAMETER,3,MAX_HEIGHT,11,9);
    static SizeRules standard(){return STANDARD;}
    static void validateSavedGeometry(int radius,int height) {
        long diameter=(long)radius*2-1;
        if(diameter<STANDARD.minDiameter() || diameter>LEGACY_MAX_DIAMETER || height<1 || height>LEGACY_MAX_HEIGHT)
            throw new IllegalArgumentException("保存されたサイズが不正です。直径3～99、高さ1～40の旧形式に対応しています。");
    }
    public SizeRules {
        if(minDiameter<3 || maxDiameter>MAX_DIAMETER || minDiameter>maxDiameter || minDiameter%2==0 || maxDiameter%2==0 ||
           minHeight<3 || maxHeight>MAX_HEIGHT || minHeight>maxHeight || defaultDiameter%2==0 ||
           defaultDiameter<minDiameter || defaultDiameter>maxDiameter || defaultHeight<minHeight || defaultHeight>maxHeight)
            throw new IllegalArgumentException("サイズ設定: 直径は3～"+MAX_DIAMETER+"の奇数、高さは3～"+MAX_HEIGHT+"で指定してください。");
    }
    public static void migrate(ConfigurationSection c) {
        if(!c.contains("default-diameter",true) && c.contains("default-radius",true))
            c.set("default-diameter",(int)Math.clamp(c.getLong("default-radius")*2-1,3,MAX_DIAMETER));
        if(!c.contains("max-diameter",true) && c.contains("max-radius",true))
            c.set("max-diameter",(int)Math.clamp(c.getLong("max-radius")*2-1,3,MAX_DIAMETER));
        // Old installations shipped height 40. Apply the new hard safety limit during migration.
        if(c.contains("max-radius",true) && c.getInt("max-height",MAX_HEIGHT)>MAX_HEIGHT)c.set("max-height",MAX_HEIGHT);
        c.set("default-radius",null);c.set("max-radius",null);c.set("max-volume",null);
    }
    public static SizeRules read(ConfigurationSection c) {
        return new SizeRules(c.getInt("min-diameter",3),c.getInt("max-diameter",MAX_DIAMETER),c.getInt("min-height",3),
            c.getInt("max-height",MAX_HEIGHT),c.getInt("default-diameter",11),c.getInt("default-height",9));
    }
    public int capDiameter(java.math.BigInteger requested) {
        if(requested.compareTo(java.math.BigInteger.valueOf(minDiameter))<0)throw new IllegalArgumentException("直径の最小は"+minDiameter+"です。");
        int d=requested.min(java.math.BigInteger.valueOf(maxDiameter)).intValue();validate(d,defaultHeight);return d;
    }
    public int capHeight(java.math.BigInteger requested) {
        if(requested.compareTo(java.math.BigInteger.valueOf(minHeight))<0)throw new IllegalArgumentException("高さの最小は"+minHeight+"です。");
        return requested.min(java.math.BigInteger.valueOf(maxHeight)).intValue();
    }
    public int radius(int diameter) { validate(diameter,defaultHeight);return (diameter+1)/2; }
    public void validate(int diameter,int height) {
        if(diameter<minDiameter || diameter>maxDiameter || diameter%2==0)throw new IllegalArgumentException("直径は"+minDiameter+"～"+maxDiameter+"の奇数で指定してください。");
        if(height<minHeight || height>maxHeight)throw new IllegalArgumentException("高さは"+minHeight+"～"+maxHeight+"で指定してください。");
    }
    public static int delta(int n) { if(n<2 || n%2!=0)throw new IllegalArgumentException("直径の増減は正の偶数で指定してください。");return n; }
    private static String label(int n,int min,int max) {return n+(n==max?"(最大)":n==min?"(最小)":"");}
    public String label(int diameter,int height) {return "直径"+label(diameter,minDiameter,maxDiameter)+" 高さ"+label(height,minHeight,maxHeight);}
}
