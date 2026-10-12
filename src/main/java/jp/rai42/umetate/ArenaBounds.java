package jp.rai42.umetate;

import org.bukkit.Location;
import org.bukkit.World;

public record ArenaBounds(World world, int minX, int maxX, int floorY, int topY, int minZ, int maxZ, String mode, int sourceRadius, int sourceHeight) {
   public int innerMinX() {
      return this.minX + 1;
   }

   public int innerMaxX() {
      return this.maxX - 1;
   }

   public int innerMinZ() {
      return this.minZ + 1;
   }

   public int innerMaxZ() {
      return this.maxZ - 1;
   }

   public int innerMinY() {
      return this.floorY + 1;
   }

   public int innerMaxY() {
      return this.topY;
   }

   public boolean containsInterior(int x, int y, int z) {
      return x >= this.innerMinX() && x <= this.innerMaxX() && z >= this.innerMinZ() && z <= this.innerMaxZ() && y >= this.innerMinY() && y <= this.innerMaxY();
   }

   public Location topCenter() {
      return new Location(this.world, (double)(this.minX + this.maxX) / 2.0 + 0.5, (double)this.topY + 1.0, (double)(this.minZ + this.maxZ) / 2.0 + 0.5);
   }

   public String summary() {
      return this.world.getName()
         + " ["
         + this.innerMinX()
         + ".."
         + this.innerMaxX()
         + ", "
         + this.innerMinY()
         + ".."
         + this.innerMaxY()
         + ", "
         + this.innerMinZ()
         + ".."
         + this.innerMaxZ()
         + "] mode="
         + this.mode;
   }
}
