package jp.rai42.umetate;

/** A bounded iterator: large simultaneous blasts cannot consume an unbounded world-edit tick. */
final class Blast {
    private final double x, y, z, radiusSquared;
    private final int minX, maxX, minY, maxY, minZ, maxZ;
    private int bx, by, bz;
    Blast(Box box, double x, double y, double z, double radius) {
        this.x = x; this.y = y; this.z = z; radiusSquared = radius * radius;
        minX = Math.max(box.x() - box.radius() + 1, (int) Math.floor(x - radius));
        maxX = Math.min(box.x() + box.radius() - 1, (int) Math.ceil(x + radius));
        minY = Math.max(box.y() + 1, (int) Math.floor(y - radius));
        maxY = Math.min(box.y() + box.height(), (int) Math.ceil(y + radius));
        minZ = Math.max(box.z() - box.radius() + 1, (int) Math.floor(z - radius));
        maxZ = Math.min(box.z() + box.radius() - 1, (int) Math.ceil(z + radius));
        bx = minX; by = minY; bz = minZ;
    }
    boolean hasNext() { return by <= maxY && minX <= maxX && minZ <= maxZ; }
    /** One unit of work; null is a cell outside the spherical radius. */
    Box.Cell next() {
        if (!hasNext()) throw new java.util.NoSuchElementException();
        double dx = bx + .5 - x, dy = by + .5 - y, dz = bz + .5 - z;
        Box.Cell result = dx * dx + dy * dy + dz * dz <= radiusSquared ? new Box.Cell(bx, by, bz) : null;
        if (++bx > maxX) { bx = minX; if (++bz > maxZ) { bz = minZ; by++; } }
        return result;
    }
}
