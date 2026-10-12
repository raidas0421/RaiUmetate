package jp.rai42.umetate;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
/** Versioned, palette/RLE compressed restoration snapshot; contains no Bukkit objects. */
record RestorationData(String revision,String world,Box box,Map<Box.Cell,String> originals,Map<Box.Cell,String> roof) {
    private static final int MAGIC=0x52414955,VERSION=1,MAX_CELLS=500000;
    RestorationData {originals=Collections.unmodifiableMap(new LinkedHashMap<>(originals));roof=Collections.unmodifiableMap(new LinkedHashMap<>(roof));}
    void write(Path path)throws IOException {
        try(var out=new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(path))))) {
            out.writeInt(MAGIC);out.writeInt(VERSION);out.writeUTF(revision);out.writeUTF(world);
            out.writeInt(box.x());out.writeInt(box.y());out.writeInt(box.z());out.writeInt(box.radius());out.writeInt(box.height());
            writeMap(out,originals);writeMap(out,roof);
        }
    }
    private static void writeMap(DataOutputStream out,Map<Box.Cell,String> map)throws IOException {
        var entries=new ArrayList<>(map.entrySet());entries.sort(Comparator.comparingInt((Map.Entry<Box.Cell,String> e)->e.getKey().y()).thenComparingInt(e->e.getKey().z()).thenComparingInt(e->e.getKey().x()));
        var palette=new LinkedHashMap<String,Integer>();for(var e:entries)palette.computeIfAbsent(e.getValue(),s->palette.size());
        out.writeInt(palette.size());for(String value:palette.keySet())out.writeUTF(value);out.writeInt(entries.size());
        for(int start=0;start<entries.size();) {
            int end=start+1;String value=entries.get(start).getValue();while(end<entries.size() && value.equals(entries.get(end).getValue()))end++;
            out.writeInt(end-start);out.writeInt(palette.get(value));
            for(int i=start;i<end;i++){var c=entries.get(i).getKey();out.writeInt(c.x());out.writeInt(c.y());out.writeInt(c.z());}start=end;
        }
    }
    static RestorationData read(Path path)throws IOException {
        try(var in=new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(path))))) {
            if(in.readInt()!=MAGIC || in.readInt()!=VERSION)throw new IOException("Unknown restoration data version");
            String revision=in.readUTF(),world=in.readUTF();Box box=Box.restored(in.readInt(),in.readInt(),in.readInt(),in.readInt(),in.readInt());
            var originals=readMap(in);var roof=readMap(in);if(in.read()!=-1)throw new IOException("Trailing restoration data");
            return new RestorationData(revision,world,box,originals,roof);
        }
    }
    private static int size(int n)throws IOException {if(n<0 || n>MAX_CELLS)throw new IOException("Invalid restoration size");return n;}
    private static Map<Box.Cell,String> readMap(DataInputStream in)throws IOException {
        int count=size(in.readInt());var palette=new ArrayList<String>(count);for(int i=0;i<count;i++)palette.add(in.readUTF());
        int cells=size(in.readInt());var map=new LinkedHashMap<Box.Cell,String>();
        for(int read=0;read<cells;) {
            int run=in.readInt(),id=in.readInt();if(run<1 || run>cells-read || id<0 || id>=palette.size())throw new IOException("Invalid restoration run");
            for(int i=0;i<run;i++){var c=new Box.Cell(in.readInt(),in.readInt(),in.readInt());if(map.put(c,palette.get(id))!=null)throw new IOException("Duplicate restoration coordinate");}read+=run;
        }return map;
    }
}
