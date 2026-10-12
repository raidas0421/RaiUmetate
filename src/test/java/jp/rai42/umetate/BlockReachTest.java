package jp.rai42.umetate;

import java.util.ArrayList;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BlockReachTest {
    @Test void distanceChangesDoNotStackAndDisablingPreservesOtherModifiers() {
        AttributeInstance attribute=mock(AttributeInstance.class);
        var modifiers=new ArrayList<AttributeModifier>();
        var creative=new AttributeModifier(new NamespacedKey("minecraft","creative_range"),.5,AttributeModifier.Operation.ADD_NUMBER);
        modifiers.add(creative);
        when(attribute.getModifiers()).thenAnswer(i -> new ArrayList<>(modifiers));
        when(attribute.getModifier(any(net.kyori.adventure.key.Key.class))).thenAnswer(i ->
                modifiers.stream().filter(m -> m.getKey().equals(i.getArgument(0))).findFirst().orElse(null));
        when(attribute.getValue()).thenAnswer(i -> 4.5+modifiers.stream().mapToDouble(AttributeModifier::getAmount).sum());
        doAnswer(i -> {modifiers.remove(i.getArgument(0));return null;}).when(attribute).removeModifier(any(AttributeModifier.class));
        doAnswer(i -> {modifiers.add(i.getArgument(0));return null;}).when(attribute).addTransientModifier(any(AttributeModifier.class));
        BlockReach.apply(attribute,20); assertEquals(20,attribute.getValue());
        BlockReach.apply(attribute,20); assertEquals(20,attribute.getValue()); assertEquals(2,modifiers.size());
        BlockReach.apply(attribute,32); assertEquals(32,attribute.getValue());
        BlockReach.apply(attribute,0); assertEquals(5,attribute.getValue()); assertEquals(java.util.List.of(creative),modifiers);
        verify(attribute,never()).setBaseValue(anyDouble());
    }
}
