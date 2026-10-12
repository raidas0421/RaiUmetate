package jp.rai42.umetate;

import org.bukkit.Material;

/** Default layers are anchored to the top, never repeated below the copper band. */
final class LayerPalette {
    private static final Material[] TOP_DOWN = {Material.DIAMOND_BLOCK, Material.GOLD_BLOCK,
            Material.IRON_BLOCK, Material.EMERALD_BLOCK, Material.AMETHYST_BLOCK, Material.COPPER_BLOCK};
    private LayerPalette() {}
    static Material at(int height, int bottomLayer) {
        if (height < 1 || bottomLayer < 0 || bottomLayer >= height) throw new IllegalArgumentException("層が箱の範囲外です。");
        int band = (height - 1 - bottomLayer) / 3;
        return band < TOP_DOWN.length ? TOP_DOWN[band] : Material.QUARTZ_BLOCK;
    }
}
