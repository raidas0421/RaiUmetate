package jp.rai42.umetate;

/** Explosion damage curve used for frame durability. */
public final class BlastDamage {
   private static final double FRAME_DAMAGE_SCALE = 3.625;
   private BlastDamage() {}
   public static double calculate(double power, double distance, double exposure) {
      if (!Double.isFinite(power) || power <= 0 || distance >= power * 2 || exposure <= 0) return 0;
      double impact = (1 - Math.max(0, distance) / (power * 2)) * Math.min(1, exposure);
      return Math.floor((impact * impact + impact) / 2 * FRAME_DAMAGE_SCALE * power * 2 + 1);
   }
}
