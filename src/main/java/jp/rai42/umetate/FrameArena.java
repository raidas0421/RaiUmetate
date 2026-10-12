package jp.rai42.umetate;

import java.util.Optional;
import org.bukkit.Location;

/** Connects the RaiBR frame-health system to the arena managed by this plugin. */
interface FrameArena {
    default void refresh() {}
    default void persistenceFailed(RuntimeException error) {}
    default String countLabel() {return "待機中";}
    default String sizeLabel() {return "";}
    Optional<ArenaBounds> current();
    void stopGimmicksForCollapse();
    void frameRestored();
    boolean shieldsExplosion(Location origin);
    /** Axis-aligned regions {x0,y0,z0,x1,y1,z1} made only of full opaque cubes; any ray crossing one is blocked. */
    default java.util.List<double[]> occluders() { return java.util.List.of(); }
}
