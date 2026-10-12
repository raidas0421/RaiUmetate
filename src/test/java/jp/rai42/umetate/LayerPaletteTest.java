package jp.rai42.umetate;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LayerPaletteTest {
    @Test void eachTopDownBandHasThreeBlocksAndAllDeeperLayersAreQuartz() {
        Material[] expected = {Material.DIAMOND_BLOCK, Material.GOLD_BLOCK, Material.IRON_BLOCK,
                Material.EMERALD_BLOCK, Material.AMETHYST_BLOCK, Material.COPPER_BLOCK};
        for (int depth = 0; depth < 40; depth++)
            assertEquals(depth < 18 ? expected[depth / 3] : Material.QUARTZ_BLOCK, LayerPalette.at(40, 39 - depth), "depth=" + depth);
    }
    @Test void shortArenaStartsAtDiamondRatherThanQuartz() {
        for (int y = 6; y < 9; y++) assertEquals(Material.DIAMOND_BLOCK, LayerPalette.at(9, y));
        for (int y = 3; y < 6; y++) assertEquals(Material.GOLD_BLOCK, LayerPalette.at(9, y));
        for (int y = 0; y < 3; y++) assertEquals(Material.IRON_BLOCK, LayerPalette.at(9, y));
        assertEquals(Material.DIAMOND_BLOCK, LayerPalette.at(1, 0));
    }
    @Test void changingHeightReanchorsExistingPositionToNewTop() {
        assertEquals(Material.DIAMOND_BLOCK, LayerPalette.at(9, 8));
        assertEquals(Material.GOLD_BLOCK, LayerPalette.at(12, 8));
        assertEquals(Material.COPPER_BLOCK, LayerPalette.at(18, 0));
        assertEquals(Material.QUARTZ_BLOCK, LayerPalette.at(19, 0));
    }
    @Test void rejectsOutOfBoundsLayers() {
        assertThrows(IllegalArgumentException.class, () -> LayerPalette.at(0, 0));
        assertThrows(IllegalArgumentException.class, () -> LayerPalette.at(9, -1));
        assertThrows(IllegalArgumentException.class, () -> LayerPalette.at(9, 9));
    }
}
