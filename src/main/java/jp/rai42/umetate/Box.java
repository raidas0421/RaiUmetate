package jp.rai42.umetate;

/** Coordinates use the centre of the floor; height counts interior layers. */
public final class Box {
    private final int x,y,z,radius,height;
    public Box(int x,int y,int z,int radius,int height) {this(x,y,z,radius,height,false);}
    private Box(int x,int y,int z,int radius,int height,boolean legacy) {
        if(legacy)SizeRules.validateSavedGeometry(radius,height);
        else SizeRules.standard().validate((int)Math.clamp((long)radius*2-1,Integer.MIN_VALUE,Integer.MAX_VALUE),height);
        this.x=x;this.y=y;this.z=z;this.radius=radius;this.height=height;
    }
    /** Read-only compatibility boundary for existing arenas, never used by size commands. */
    static Box restored(int x,int y,int z,int radius,int height){return new Box(x,y,z,radius,height,true);}
    public int x(){return x;} public int y(){return y;} public int z(){return z;}
    public int radius(){return radius;} public int height(){return height;}
    @Override public boolean equals(Object other){return other instanceof Box b && x==b.x && y==b.y && z==b.z && radius==b.radius && height==b.height;}
    @Override public int hashCode(){return java.util.Objects.hash(x,y,z,radius,height);}
    @Override public String toString(){return "Box[x="+x+", y="+y+", z="+z+", radius="+radius+", height="+height+"]";}
    public int width() { return radius * 2 - 1; }
    public int capacity() { return width() * width() * height; }
    public int volume() { return (radius * 2 + 1) * (radius * 2 + 1) * (height + 1); }
    public boolean contains(int bx, int by, int bz) {
        return Math.abs((long) bx - x) < radius && Math.abs((long) bz - z) < radius && by > y && by <= y + height;
    }
    public boolean envelope(int bx, int by, int bz) {
        return Math.abs((long) bx - x) <= radius && Math.abs((long) bz - z) <= radius && by >= y && by <= y + height;
    }
    public boolean shell(int bx, int by, int bz) { return envelope(bx, by, bz) && !contains(bx, by, bz); }
    public int index(int bx, int by, int bz) {
        if (!contains(bx, by, bz)) return -1;
        return (by - y - 1) * width() * width() + (bz - z + radius - 1) * width() + bx - x + radius - 1;
    }
    public Cell cell(int index) {
        if (index < 0 || index >= capacity()) throw new IndexOutOfBoundsException(index);
        return new Cell(cellX(index), cellY(index), cellZ(index));
    }
    /** Allocation-free coordinates of an interior index; callers keep the index within capacity. */
    public int cellX(int index) { return x - radius + 1 + index % width(); }
    public int cellY(int index) { int w = width(); return y + 1 + index / (w * w); }
    public int cellZ(int index) { int w = width(); return z - radius + 1 + index / w % w; }
    /** Lazily enumerates interior cells [from, to) in index order without materializing a list. */
    public Iterable<Cell> interior(int from, int to) {
        int end = Math.min(to, capacity());
        return () -> new java.util.Iterator<>() {
            int next = Math.max(0, from);
            public boolean hasNext() { return next < end; }
            public Cell next() { if (next >= end) throw new java.util.NoSuchElementException(); return cell(next++); }
        };
    }
    /** Lazily enumerates the walls and floor, skipping the interior. */
    public Iterable<Cell> shellCells() {
        return () -> new java.util.Iterator<>() {
            int bx = x - radius, by = y, bz = z - radius;
            { skip(); }
            private void skip() {
                while (by <= y + height && !shell(bx, by, bz)) advance();
            }
            private void advance() {
                if (++bx > x + radius) { bx = x - radius; if (++bz > z + radius) { bz = z - radius; by++; } }
            }
            public boolean hasNext() { return by <= y + height; }
            public Cell next() {
                if (!hasNext()) throw new java.util.NoSuchElementException();
                Cell result = new Cell(bx, by, bz); advance(); skip(); return result;
            }
        };
    }
    /** Lazily enumerates the whole envelope (walls, floor and interior). */
    public Iterable<Cell> envelopeCells() {
        return () -> new java.util.Iterator<>() {
            int bx = x - radius, by = y, bz = z - radius;
            public boolean hasNext() { return by <= y + height; }
            public Cell next() {
                if (!hasNext()) throw new java.util.NoSuchElementException();
                Cell result = new Cell(bx, by, bz);
                if (++bx > x + radius) { bx = x - radius; if (++bz > z + radius) { bz = z - radius; by++; } }
                return result;
            }
        };
    }
    public record Cell(int x, int y, int z) {}
}
