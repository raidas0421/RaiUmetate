package jp.rai42.umetate;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class ArenaStoreTest {
    @TempDir Path directory;
    @Test void serializationAndDiskWritingRunOffServerThreadInOrder() throws Exception {
        Thread main=Thread.currentThread();Path target=directory.resolve("arena.yml");
        try(var store=new ArenaStore(target)) {
            var first=new LinkedHashMap<String,Object>() {
                @Override public void forEach(BiConsumer<? super String,? super Object> action){assertNotEquals(main,Thread.currentThread());super.forEach(action);}
            };
            first.put("transitioning",true);first.put("originals.1,2,3","minecraft:stone");store.write(first).get(3,TimeUnit.SECONDS);
            assertTrue(YamlConfiguration.loadConfiguration(target.toFile()).getBoolean("transitioning"));
            List<CompletableFuture<Void>> saves=new ArrayList<>();
            for(int i=0;i<5;i++)saves.add(store.write(Map.of("wins",i,"transitioning",false,"originals.1,2,3","minecraft:air")));
            CompletableFuture.allOf(saves.toArray(CompletableFuture[]::new)).get(3,TimeUnit.SECONDS);
            var result=YamlConfiguration.loadConfiguration(target.toFile());assertEquals(4,result.getInt("wins"));assertFalse(result.getBoolean("transitioning"));
            assertEquals("minecraft:air",result.getString("originals.1,2,3"));assertFalse(Files.exists(directory.resolve("arena.yml.tmp")));
        }
    }
    @Test void diskFailurePropagatesThroughFuture() throws Exception {
        Path parent=directory.resolve("file");Files.writeString(parent,"not a directory");
        try(var store=new ArenaStore(parent.resolve("arena.yml"))) {assertThrows(ExecutionException.class,()->store.write(Map.of("x",1)).get(3,TimeUnit.SECONDS));}
    }
    @org.junit.jupiter.api.Test void replaceWaitsOutABriefLockOnTheDestination() throws Exception {
        java.nio.file.Path dir=java.nio.file.Files.createTempDirectory("arena-store");
        var destination=dir.resolve("state.yml");java.nio.file.Files.writeString(destination,"old");
        var source=dir.resolve("state.yml.tmp");java.nio.file.Files.writeString(source,"new");
        // On Windows an open reader blocks replacing the file; elsewhere this simply succeeds at once.
        var reader=new java.io.FileInputStream(destination.toFile());
        var release=new Thread(()->{try{Thread.sleep(200);reader.close();}catch(Exception ignored){}});release.start();
        try{ArenaStore.replace(source,destination);}finally{release.join();reader.close();}
        org.junit.jupiter.api.Assertions.assertEquals("new",java.nio.file.Files.readString(destination));
    }
}
