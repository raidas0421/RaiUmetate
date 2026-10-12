package jp.rai42.umetate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** A timed physical roof. Hits never consume its remaining duration or original-block snapshot. */
final class BedrockLid<T> {
    private final Box box;
    private final Map<Box.Cell, T> originals;
    private int remainingTicks;

    BedrockLid(Box box, int seconds, Map<Box.Cell, T> originals) {
        if (originals.size() != cells(box).size() || !originals.keySet().containsAll(cells(box)))
            throw new IllegalArgumentException("岩盤のふたの復元データが不足しています。");
        this.box = box;
        this.originals = Map.copyOf(originals);
        extend(seconds);
    }
    static List<Box.Cell> cells(Box box) {
        List<Box.Cell> result = new ArrayList<>();
        for (int z = box.z() - box.radius(); z <= box.z() + box.radius(); z++)
            for (int x = box.x() - box.radius(); x <= box.x() + box.radius(); x++)
                result.add(new Box.Cell(x, box.y() + box.height() + 1, z));
        return result;
    }
    Map<Box.Cell, T> originals() { return originals; }
    int blockY() { return box.y() + box.height() + 1; }
    int topY() { return blockY() + 1; }
    boolean active() { return remainingTicks > 0; }
    int secondsLeft() { return (remainingTicks + 19) / 20; }
    void extend(int seconds) {
        if (seconds < 1 || seconds > 3600) throw new IllegalArgumentException("ふたの時間は1～3600秒で指定してください。");
        remainingTicks = Math.min(72000, remainingTicks + seconds * 20);
    }
    boolean tick() { return remainingTicks > 0 && --remainingTicks == 0; }
    void install(Consumer<Box.Cell> placeBedrock) { originals.keySet().forEach(placeBedrock); }
    void restore(BiConsumer<Box.Cell, T> restoreBlock) { originals.forEach(restoreBlock); }
    boolean contains(int x, int y, int z) {
        return y == blockY() && Math.abs((long) x - box.x()) <= box.radius() && Math.abs((long) z - box.z()) <= box.radius();
    }

    /** Swept top-plane collision catches even a fast projectile that crosses the roof in one tick. */
    Point intercept(Point from, Point to, double halfSize) {
        if (!active()) return null;
        double plane = topY() + halfSize;
        if (from.y() < topY() || to.y() > plane || from.y() <= to.y()) return null;
        // Activation can catch a projectile already touching the top surface.
        double t = Math.max(0, (from.y() - plane) / (from.y() - to.y()));
        double x = from.x() + (to.x() - from.x()) * t;
        double z = from.z() + (to.z() - from.z()) * t;
        if (x + halfSize < box.x() - box.radius() || x - halfSize >= box.x() + box.radius() + 1
                || z + halfSize < box.z() - box.radius() || z - halfSize >= box.z() + box.radius() + 1) return null;
        return new Point(x, plane, z);
    }
    record Point(double x, double y, double z) {}
}
