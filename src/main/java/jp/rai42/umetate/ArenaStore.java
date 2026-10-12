package jp.rai42.umetate;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.configuration.file.YamlConfiguration;
/** Single ordered writer. A previous binary generation survives a crash between the two atomic replacements. */
final class ArenaStore implements AutoCloseable {
    private final Path target;
    private final ExecutorService writer=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"RaiUmetate-Arena-Save");t.setDaemon(true);return t;});
    private volatile RuntimeException failure;
    ArenaStore(Path target){this.target=target;}
    Path binary(){return target.resolveSibling("arena-originals.dat");}
    CompletableFuture<Void> write(Map<String,Object> snapshot){return write(snapshot,null,false);}
    CompletableFuture<Void> write(Map<String,Object> snapshot,RestorationData data,boolean migrate) {
        return submit(()->{
            Files.createDirectories(target.getParent());
            if(migrate && Files.exists(target) && !Files.exists(target.resolveSibling("arena.yml.bak")))Files.copy(target,target.resolveSibling("arena.yml.bak"));
            if(data!=null){
                Path temp=binary().resolveSibling("arena-originals.dat.tmp");data.write(temp);
                if(Files.exists(binary()))replace(binary(),binary().resolveSibling("arena-originals.dat.previous"));
                replace(temp,binary());
            }
            var yaml=new YamlConfiguration();snapshot.forEach(yaml::set);
            Path temp=target.resolveSibling(target.getFileName()+".tmp");yaml.save(temp.toFile());replace(temp,target);
        });
    }
    RestorationData readOriginals(String revision)throws IOException {
        IOException problem=null;
        for(Path path:List.of(binary(),binary().resolveSibling("arena-originals.dat.previous"))) {
            if(!Files.exists(path))continue;
            try{var data=RestorationData.read(path);if(data.revision().equals(revision))return data;}
            catch(IOException e){problem=e;}
        }
        throw new IOException("Matching restoration generation is missing: "+revision,problem);
    }
    CompletableFuture<Void> delete(){return submit(()->{Files.deleteIfExists(target);Files.deleteIfExists(binary());Files.deleteIfExists(binary().resolveSibling("arena-originals.dat.previous"));});}
    private interface IO {void run()throws IOException;}
    private CompletableFuture<Void> submit(IO action){return CompletableFuture.runAsync(()->{
        if(failure!=null)throw failure;
        try{action.run();}catch(IOException e){failure=new UncheckedIOException(e);throw failure;}catch(RuntimeException e){failure=e;throw e;}
    },writer);}
    /** Windows denies a rename while another program (antivirus, sync, indexer) briefly holds the file; retry on the writer thread. */
    static void replace(Path source,Path destination)throws IOException {
        for(int attempt=1;;attempt++) {
            try{
                try{Files.move(source,destination,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
                catch(AtomicMoveNotSupportedException e){Files.move(source,destination,StandardCopyOption.REPLACE_EXISTING);}
                return;
            }catch(AccessDeniedException e){
                if(attempt>=40)throw e;
                try{Thread.sleep(25);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw e;}
            }
        }
    }
    @Override public void close(){
        writer.shutdown();
        try{if(!writer.awaitTermination(30,TimeUnit.SECONDS))throw new IllegalStateException("保存完了を30秒待機しましたが完了しませんでした。");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("保存終了待機が中断されました。",e);}
    }
}
