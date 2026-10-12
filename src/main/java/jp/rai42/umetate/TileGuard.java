package jp.rai42.umetate;

import java.util.EnumMap;
import org.bukkit.Material;
import org.bukkit.block.Container;
import org.bukkit.block.TileState;

/** Server-thread cache. Unplaced states avoid copying a world block and its inventory. */
final class TileGuard {
    private final EnumMap<Material,Boolean> tiles=new EnumMap<>(Material.class);
    private final EnumMap<Material,Boolean> containers=new EnumMap<>(Material.class);
    boolean isTile(Material material){classify(material);return tiles.get(material);}
    boolean isContainer(Material material){classify(material);return containers.get(material);}
    private void classify(Material material){
        if(tiles.containsKey(material))return;
        var state=material.createBlockData().createBlockState();
        tiles.put(material,state instanceof TileState);containers.put(material,state instanceof Container);
    }
}
