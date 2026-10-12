package jp.rai42.umetate;
import java.util.*;
import org.bukkit.block.data.BlockData;
/** Serialize at capture time, never by walking live Bukkit data during a save. */
final class RestorationMap extends LinkedHashMap<Box.Cell,BlockData> {
    private final Map<Box.Cell,String> strings=new LinkedHashMap<>();
    private final Map<BlockData,String> palette=new HashMap<>();
    private long revision;
    long revision(){return revision;}
    @Override public BlockData put(Box.Cell key,BlockData value){strings.put(key,palette.computeIfAbsent(value,BlockData::getAsString));revision++;return super.put(key,value);}
    @Override public void putAll(Map<? extends Box.Cell,? extends BlockData> values){values.forEach(this::put);}
    @Override public BlockData remove(Object key){strings.remove(key);revision++;return super.remove(key);}
    @Override public void clear(){strings.clear();palette.clear();revision++;super.clear();}
    Map<Box.Cell,String> snapshot(){return Collections.unmodifiableMap(new LinkedHashMap<>(strings));}
}
