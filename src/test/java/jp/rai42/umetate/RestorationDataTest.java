package jp.rai42.umetate;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class RestorationDataTest {
 @TempDir Path directory;
 private RestorationData sample(String revision){return new RestorationData(revision,"world-id",Box.restored(3,60,-5,25,30),Map.of(new Box.Cell(3,60,-5),"minecraft:stone",new Box.Cell(-22,61,-5),"minecraft:oak_log[axis=y]"),Map.of(new Box.Cell(3,91,-5),"minecraft:air"));}
 @Test void paletteCoordinatesAndRoofRoundTrip()throws Exception {
  var value=sample("one");Path file=directory.resolve("data");value.write(file);assertEquals(value,RestorationData.read(file));
  byte[] bytes=Files.readAllBytes(file);Files.write(file,Arrays.copyOf(bytes,bytes.length/2));assertThrows(java.io.IOException.class,()->RestorationData.read(file));
 }
 @Test void maximumArenaCompressesAndKeepsOriginalsOutOfYaml()throws Exception {
  Box box=Box.restored(0,0,0,25,30);Map<Box.Cell,String> cells=new LinkedHashMap<>();
  for(int y=0;y<=30;y++)for(int z=-25;z<=25;z++)for(int x=-25;x<=25;x++)cells.put(new Box.Cell(x,y,z),"minecraft:air");
  var data=new RestorationData("one","world-id",box,cells,Map.of());
  try(var store=new ArenaStore(directory.resolve("arena.yml"))) {
   store.write(Map.of("originals-revision","one","wins",0),data,false).get(5,TimeUnit.SECONDS);
   assertEquals(data,store.readOriginals("one"));assertTrue(Files.size(store.binary())<500000);
   assertTrue(Files.size(directory.resolve("arena.yml"))<200);
   byte[] before=Files.readAllBytes(store.binary());store.write(Map.of("originals-revision","one","wins",1)).get(3,TimeUnit.SECONDS);
   assertArrayEquals(before,Files.readAllBytes(store.binary()));
  }
 }
 @Test void crashBetweenBinaryAndMetadataUsesPreviousGeneration()throws Exception {
  try(var store=new ArenaStore(directory.resolve("arena.yml"))) {
   var old=sample("old");store.write(Map.of("originals-revision","old"),old,false).get(3,TimeUnit.SECONDS);
   Files.move(store.binary(),directory.resolve("arena-originals.dat.previous"));sample("new").write(store.binary());
   assertEquals(old,store.readOriginals("old"));assertEquals("old",YamlConfiguration.loadConfiguration(directory.resolve("arena.yml").toFile()).getString("originals-revision"));
   assertThrows(java.io.IOException.class,()->store.readOriginals("missing"));
  }
 }
 @Test void legacyBackupIsPreservedAndDeleteIsOrdered()throws Exception {
  Path yaml=directory.resolve("arena.yml");String legacy="originals:\n  1,2,3: minecraft:stone\n";Files.writeString(yaml,legacy);
  try(var store=new ArenaStore(yaml)) {
   store.write(Map.of("originals-revision","one"),sample("one"),true).get(3,TimeUnit.SECONDS);
   assertEquals(legacy,Files.readString(directory.resolve("arena.yml.bak")));
   store.write(Map.of("originals-revision","two"),sample("two"),true).get(3,TimeUnit.SECONDS);
   assertEquals(legacy,Files.readString(directory.resolve("arena.yml.bak")));
   store.delete().get(3,TimeUnit.SECONDS);assertFalse(Files.exists(yaml));assertFalse(Files.exists(store.binary()));assertTrue(Files.exists(directory.resolve("arena.yml.bak")));
  }
 }
 @Test void resizeIteratorSkipsUnchangedInteriorAndContainsEveryChangedBoundary() {
  Box before=Box.restored(0,0,0,2,3),after=Box.restored(0,0,0,3,3);var changes=new ResizeCells(before,after);
  Set<Box.Cell> found=new HashSet<>();changes.forEach(c->assertTrue(found.add(c)));
  assertFalse(found.contains(new Box.Cell(0,2,0)));
  for(int y=0;y<=3;y++)for(int z=-3;z<=3;z++)for(int x=-3;x<=3;x++)if(before.shell(x,y,z)||after.shell(x,y,z)||before.envelope(x,y,z)!=after.envelope(x,y,z))assertTrue(found.contains(new Box.Cell(x,y,z)));
 }
}
