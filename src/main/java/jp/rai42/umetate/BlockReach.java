package jp.rai42.umetate;

import org.bukkit.NamespacedKey;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;

/** Only our temporary modifier is removed; other plugins' attributes are preserved. */
final class BlockReach {
    private static final NamespacedKey KEY = new NamespacedKey("raiumetate", "block_reach");

    static void apply(AttributeInstance attribute, double distance) {
        if (attribute == null) return;
        AttributeModifier previous = attribute.getModifier(KEY);
        // Recompute against the current game mode and other modifiers without stacking ours.
        if (previous != null) attribute.removeModifier(previous);
        if (distance <= 0) return;
        double multiplier = 1;
        for (AttributeModifier modifier : attribute.getModifiers())
            if (modifier.getOperation() == AttributeModifier.Operation.MULTIPLY_SCALAR_1)
                multiplier *= 1 + modifier.getAmount();
        if (multiplier <= 0) return;
        double extra = (distance - attribute.getValue()) / multiplier;
        if (Math.abs(extra) > .0001)
            attribute.addTransientModifier(new AttributeModifier(KEY, extra, AttributeModifier.Operation.ADD_NUMBER));
    }
}
