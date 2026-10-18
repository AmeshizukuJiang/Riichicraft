package dev.riichicraft.rules;

import dev.riichicraft.persistence.TableArchiveStore;
import dev.riichicraft.persistence.TableArchiveStore.Seat;
import dev.riichicraft.persistence.TableArchiveStore.Table;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Outer archive integrity and actual project-local file replacement; core replay has its own suite. */
public final class TableArchiveStoreTests {
    private static int assertions;
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private TableArchiveStoreTests() { }
    public static void main(String[] args) {
        long start=System.nanoTime();
        try { roundTrips(); legacyAndFixedChairSides(); metadata(); malformedFrames(); realFiles(); }
        catch(Exception failure) { throw new AssertionError("Table archive verification failed",failure); }
        System.out.printf("PASS table archive store: %d assertions; %.3f s%n",assertions,(System.nanoTime()-start)/1_000_000_000.0);
    }

    private static void roundTrips() throws Exception {
        check(TableArchiveStore.decode(TableArchiveStore.encode(List.of())).isEmpty(),"empty saved world round trip");
        var first=table(1,"minecraft:overworld",1,2,3); var second=table(2,"minecraft:the_nether",1,2,3);
        var data=List.of(first,second);
        byte[] encoded=TableArchiveStore.encode(data);
        check(TableArchiveStore.decode(encoded).equals(data),"multiple dimensions may share coordinates with distinct participants");
        check(Arrays.equals(encoded,TableArchiveStore.encode(data)),"stable binary output for an unchanged table list");
        check(TableArchiveStore.decode(encoded).getFirst().seats().getFirst().name().equals("玩家東🀄"),"strict Unicode names round trip");
        byte[] source={1,2,3}; var seats=new ArrayList<>(first.seats());
        var copied=new Table(first.tableId(),first.dimension(),first.x(),first.y(),first.z(),0,1,2,seats,source);
        source[0]=9; seats.clear(); byte[] exposed=copied.gameData(); exposed[1]=9;
        check(Arrays.equals(copied.gameData(),new byte[]{1,2,3}) && copied.seats().size()==4,"saved metadata and secret core bytes are defensive snapshots");
        var decoded=TableArchiveStore.decode(TableArchiveStore.encode(List.of(copied))).getFirst();
        check(copied.equals(decoded) && copied.hashCode()==decoded.hashCode(),"table value equality includes core bytes by contents");
        expect(UnsupportedOperationException.class,()->decoded.seats().clear(),"restored seats immutable");
        expect(UnsupportedOperationException.class,()->TableArchiveStore.decode(encoded).clear(),"restored table list immutable");
    }

    private static void metadata() throws Exception {
        var first=table(1,"minecraft:overworld",1,2,3);
        expect(IllegalArgumentException.class,()->new Seat(UUID.randomUUID(),""),"empty names refused");
        expect(IllegalArgumentException.class,()->new Seat(UUID.randomUUID(),"x".repeat(65)),"oversized names refused");
        expect(IllegalArgumentException.class,()->new Seat(UUID.randomUUID(),"\uD800"),"malformed UTF-16 is not silently replaced");
        expect(IllegalArgumentException.class,()->copy(first,first.tableId(),"Bad Namespace:x",1,2,3,0,0,0,first.seats(),new byte[]{1}),"invalid dimension refused");
        for(int mode:new int[]{-1,6,Integer.MAX_VALUE}) expect(IllegalArgumentException.class,()->copy(first,first.tableId(),first.dimension(),1,2,3,mode,0,0,first.seats(),new byte[]{1}),"unsupported modes refused");
        expect(IllegalArgumentException.class,()->copy(first,first.tableId(),first.dimension(),1,2,3,0,-1,0,first.seats(),new byte[]{1}),"negative lobby revision refused");
        expect(IllegalArgumentException.class,()->copy(first,first.tableId(),first.dimension(),1,2,3,0,0,-1,first.seats(),new byte[]{1}),"negative game revision refused");
        expect(IllegalArgumentException.class,()->copy(first,first.tableId(),first.dimension(),1,2,3,0,0,0,first.seats().subList(0,3),new byte[]{1}),"four fixed seats required");
        expect(IllegalArgumentException.class,()->copy(first,first.tableId(),first.dimension(),1,2,3,0,0,0,List.of(first.seats().getFirst(),first.seats().getFirst(),first.seats().get(2),first.seats().get(3)),new byte[]{1}),"duplicate participants refused");
        expect(IllegalArgumentException.class,()->copy(first,first.tableId(),first.dimension(),1,2,3,0,0,0,first.seats(),new byte[0]),"missing core bytes refused");
        expect(IllegalArgumentException.class,()->copy(first,first.tableId(),first.dimension(),1,2,3,0,0,0,first.seats(),new byte[TableArchiveStore.MAX_GAME_BYTES+1]),"oversized core bytes refused before cloning");
        var second=table(2,"minecraft:the_end",4,5,6);
        expect(IOException.class,()->TableArchiveStore.encode(List.of(first,copy(second,first.tableId(),second.dimension(),4,5,6,0,0,0,second.seats(),second.gameData()))),"global duplicate table identity refused");
        expect(IOException.class,()->TableArchiveStore.encode(List.of(first,copy(second,second.tableId(),first.dimension(),1,2,3,0,0,0,second.seats(),second.gameData()))),"global duplicate table position refused");
        expect(IOException.class,()->TableArchiveStore.encode(List.of(first,copy(second,second.tableId(),second.dimension(),4,5,6,0,0,0,first.seats(),second.gameData()))),"players cannot be reserved by two saved tables");
        expect(IOException.class,()->TableArchiveStore.encode(java.util.Collections.nCopies(TableArchiveStore.MAX_TABLES+1,first)),"table count bounded before writing");
    }

    private static void legacyAndFixedChairSides() throws Exception {
        var four=table(3,"minecraft:overworld",-16,63,31);
        var three=new Table(new UUID(10,4),"minecraft:the_nether",4,60,9,3,19,41,
                table(4,"minecraft:the_nether",4,60,9).seats().subList(0,3),new byte[]{7,0,9});
        var oldTables=List.of(four,three);
        // Independent old-format writer: no VERSION2 encoder or byte-offset stripping participates.
        byte[] historical=legacyVersionOne(oldTables);
        check(ByteBuffer.wrap(historical).getInt(4)==1,"fixture is an actual checksum-valid version-one frame");
        var loaded=TableArchiveStore.decode(historical);
        check(loaded.equals(oldTables) && loaded.stream().allMatch(table->table.chairSides().isEmpty()),
                "historical three/four-player games retain legacy entry and exact participant order");
        check(TableArchiveStore.decode(TableArchiveStore.encode(loaded)).equals(oldTables),
                "saving a legacy game as version two does not invent a physical chair mapping");
        for(var entry:List.of(four,three)) {
            var sides=new ArrayList<>(entry.seats().size()==4?List.of(2,3,0,1):List.of(3,0,2));
            var fixed=withSides(entry,sides);
            sides.clear();
            check(fixed.chairSides().equals(entry.seats().size()==4?List.of(2,3,0,1):List.of(3,0,2)),
                    "physical chair mapping is frozen independently of caller lists");
            expect(UnsupportedOperationException.class,()->fixed.chairSides().clear(),"exposed chair mapping is immutable");
            byte[] encoded=TableArchiveStore.encode(List.of(fixed));
            check(ByteBuffer.wrap(encoded).getInt(4)==2 && TableArchiveStore.decode(encoded).equals(List.of(fixed)),
                    "version-two games retain rotated physical sides including a skipped sanma side");
            check(!fixed.equals(entry),"physical seat mapping participates in archive equality");
            for(var bad:List.of(List.of(0,0,1),List.of(-1,1,2),List.of(0,1,4),List.of(0,1)))
                expect(IllegalArgumentException.class,()->withSides(entry,bad),"invalid side set refused before writing");
            int sideOffset=chairOffset(encoded);
            byte[] duplicate=encoded.clone();duplicate[sideOffset+2]=duplicate[sideOffset+1];sign(duplicate);
            expect(IOException.class,()->TableArchiveStore.decode(duplicate),"checksum-valid duplicate chair mapping refused");
            byte[] outside=encoded.clone();outside[sideOffset+1]=4;sign(outside);
            expect(IOException.class,()->TableArchiveStore.decode(outside),"checksum-valid out-of-range physical side refused");
            for(int count:new int[]{5,255}) {
                byte[] excessive=encoded.clone();excessive[sideOffset]=(byte)count;sign(excessive);
                expect(IOException.class,()->TableArchiveStore.decode(excessive),"side allocation count bounded before decoding roster");
            }
        }
    }

    private static void malformedFrames() throws Exception {
        byte[] good=TableArchiveStore.encode(List.of(table(1,"minecraft:overworld",1,2,3)));
        for(int length=0;length<good.length;length++) {
            byte[] prefix=Arrays.copyOf(good,length);
            expect(IOException.class,()->TableArchiveStore.decode(prefix),"every truncated prefix fails closed");
        }
        for(int offset=0;offset<good.length;offset++) {
            byte[] changed=good.clone(); changed[offset]^=1;
            expect(IOException.class,()->TableArchiveStore.decode(changed),"each byte is framed or checksummed");
        }
        expect(IOException.class,()->TableArchiveStore.decode(Arrays.copyOf(good,good.length+1)),"tail bytes refused");
        byte[] version=good.clone(); ByteBuffer.wrap(version).putInt(4,TableArchiveStore.VERSION+1); sign(version);
        expect(IOException.class,()->TableArchiveStore.decode(version),"future format refused even with a valid checksum");
        for(int count:new int[]{-1,TableArchiveStore.MAX_TABLES+1,Integer.MAX_VALUE}) {
            byte[] changed=good.clone(); ByteBuffer.wrap(changed).putInt(12,count); sign(changed);
            expect(IOException.class,()->TableArchiveStore.decode(changed),"malicious table counts refused before allocation");
        }
        int dimensionLength=32, dimensionBytes=36;
        byte[] badUtf=good.clone();badUtf[dimensionBytes]=(byte)0xff;sign(badUtf);
        expect(IOException.class,()->TableArchiveStore.decode(badUtf),"invalid UTF-8 is rejected with a valid frame hash");
        byte[] badLength=good.clone();ByteBuffer.wrap(badLength).putInt(dimensionLength,Integer.MAX_VALUE);sign(badLength);
        expect(IOException.class,()->TableArchiveStore.decode(badLength),"oversized string lengths refused before allocation");
        int mode=dimensionBytes+"minecraft:overworld".length()+12;
        byte[] badMode=good.clone();badMode[mode]=3;sign(badMode);
        expect(IOException.class,()->TableArchiveStore.decode(badMode),"invalid metadata cannot hide behind a valid hash");
        int coreLength=findCoreLength(good);
        for(int length:new int[]{-1,0,TableArchiveStore.MAX_GAME_BYTES+1,Integer.MAX_VALUE}) {
            byte[] changed=good.clone();ByteBuffer.wrap(changed).putInt(coreLength,length);sign(changed);
            expect(IOException.class,()->TableArchiveStore.decode(changed),"invalid core lengths refused before allocation");
        }
        byte[] two=TableArchiveStore.encode(List.of(table(1,"minecraft:overworld",1,2,3),table(2,"minecraft:the_end",4,5,6)));
        int nextTable=findCoreLength(two)+4+3;
        System.arraycopy(two,16,two,nextTable,16);sign(two);
        expect(IOException.class,()->TableArchiveStore.decode(two),"decoder independently rejects duplicate table identities");
    }

    private static void realFiles() throws Exception {
        Path temporaryRoot=Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        Path directory=Files.createTempDirectory(temporaryRoot,"riichicraft-archive-store-");
        check(directory.startsWith(temporaryRoot),"all file fixtures stay in configured project temporary directory");
        try {
            Path file=directory.resolve("world/data/riichicraft/tables.dat");
            check(TableArchiveStore.read(file).isEmpty(),"new world has no archive");
            var first=List.of(table(1,"minecraft:overworld",1,2,3));
            TableArchiveStore.write(file,first);
            check(TableArchiveStore.read(file).equals(first),"actual first atomic file save and fresh read");
            var second=List.of(table(2,"minecraft:the_nether",4,5,6));
            TableArchiveStore.write(file,second);
            check(TableArchiveStore.read(file).equals(second),"atomic replacement updates an existing successful save");
            byte[] previous=Files.readAllBytes(file);
            expect(IOException.class,()->TableArchiveStore.write(file,List.of(second.getFirst(),second.getFirst())),"invalid new state cannot replace old save");
            check(Arrays.equals(previous,Files.readAllBytes(file)),"old valid bytes retained after rejected new state");
            byte[] damaged=previous.clone();damaged[damaged.length-1]^=1;Files.write(file,damaged);
            expect(IOException.class,()->TableArchiveStore.read(file),"existing damaged file is not treated as missing");
            expect(IOException.class,()->TableArchiveStore.write(file,first),"a later save refuses to overwrite an existing damaged file");
            check(Arrays.equals(damaged,Files.readAllBytes(file)),"damaged original record retained byte for byte");
            byte[] unsupported=TableArchiveStore.encode(second);ByteBuffer.wrap(unsupported).putInt(4,99);sign(unsupported);Files.write(file,unsupported);
            expect(IOException.class,()->TableArchiveStore.write(file,List.of()),"unknown future files cannot be replaced with an empty world");
            check(Arrays.equals(unsupported,Files.readAllBytes(file)),"unknown version bytes retained");
            // Explicit fixture repair stands for an administrator restoring a known-good backup, never the product's error path.
            Files.write(file,previous);TableArchiveStore.write(file,List.of());
            check(TableArchiveStore.read(file).isEmpty(),"explicitly cleared games save as a valid empty archive");
            try(var entries=Files.list(file.getParent())) {
                check(entries.noneMatch(p->p.getFileName().toString().contains(".pending-")),"temporary writes do not accumulate after successful replacements");
            }
            Path notDirectory=directory.resolve("not-a-directory");Files.write(notDirectory,new byte[]{7});
            expect(IOException.class,()->TableArchiveStore.write(notDirectory.resolve("tables.dat"),first),"file creation failure reports instead of pretending success");
            check(Arrays.equals(Files.readAllBytes(notDirectory),new byte[]{7}),"unrelated existing file survives a failed save");
        } finally {
            // Only delete this fixture's own checked, freshly created project-local directory.
            try(var paths=Files.walk(directory)) {
                for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static Table table(long id,String dimension,int x,int y,int z) {
        var seats=new ArrayList<Seat>();
        for(int seat=0;seat<4;seat++) seats.add(new Seat(new UUID(id,seat+1),seat==0?"玩家東🀄":"Player"+seat));
        return new Table(new UUID(10,id),dimension,x,y,z,(int)(id%3),3,7,seats,new byte[]{1,2,3});
    }
    private static Table copy(Table unused,UUID id,String dimension,int x,int y,int z,int mode,long lobby,long game,List<Seat> seats,byte[] bytes) {
        return new Table(id,dimension,x,y,z,mode,lobby,game,seats,bytes);
    }
    private static int findCoreLength(byte[] bytes) {
        var cursor=ByteBuffer.wrap(bytes);cursor.position(16+16);skipString(cursor);cursor.position(cursor.position()+12);
        int mode=Byte.toUnsignedInt(cursor.get());cursor.position(cursor.position()+8+8);
        if(ByteBuffer.wrap(bytes).getInt(4)>=2){int count=Byte.toUnsignedInt(cursor.get());cursor.position(cursor.position()+count);}
        for(int seat=0;seat<(mode>=3?3:4);seat++){cursor.position(cursor.position()+16);skipString(cursor);}return cursor.position();
    }
    private static int chairOffset(byte[] bytes) {
        var cursor=ByteBuffer.wrap(bytes);cursor.position(16+16);skipString(cursor);
        return cursor.position()+12+1+8+8;
    }
    private static Table withSides(Table source,List<Integer> sides) {
        return new Table(source.tableId(),source.dimension(),source.x(),source.y(),source.z(),source.mode(),
                source.lobbyRevision(),source.gameRevision(),source.seats(),source.gameData(),sides);
    }
    private static byte[] legacyVersionOne(List<Table> tables) throws Exception {
        var rawBody=new ByteArrayOutputStream();var body=new DataOutputStream(rawBody);body.writeInt(tables.size());
        for(var table:tables) {
            writeId(body,table.tableId());writeOldString(body,table.dimension());
            body.writeInt(table.x());body.writeInt(table.y());body.writeInt(table.z());body.writeByte(table.mode());
            body.writeLong(table.lobbyRevision());body.writeLong(table.gameRevision());
            for(var seat:table.seats()){writeId(body,seat.playerId());writeOldString(body,seat.name());}
            byte[] core=table.gameData();body.writeInt(core.length);body.write(core);
        }
        var rawFrame=new ByteArrayOutputStream();var frame=new DataOutputStream(rawFrame);
        frame.writeInt(0x52435442);frame.writeInt(1);frame.writeInt(rawBody.size());frame.write(rawBody.toByteArray());
        frame.write(new byte[32]);byte[] result=rawFrame.toByteArray();sign(result);return result;
    }
    private static void writeId(DataOutputStream out,UUID value) throws IOException {out.writeLong(value.getMostSignificantBits());out.writeLong(value.getLeastSignificantBits());}
    private static void writeOldString(DataOutputStream out,String value) throws IOException {
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);out.writeInt(bytes.length);out.write(bytes);
    }
    private static void skipString(ByteBuffer cursor) {int size=cursor.getInt();cursor.position(cursor.position()+size);}
    private static void sign(byte[] bytes) throws Exception {
        var hash=MessageDigest.getInstance("SHA-256");hash.update(bytes,0,bytes.length-32);
        System.arraycopy(hash.digest(),0,bytes,bytes.length-32,32);
    }
    private static void check(boolean condition,String message) {assertions++;if(!condition)throw new AssertionError(message);}
    private static void expect(Class<? extends Throwable> expected,Checked operation,String message) throws Exception {
        assertions++;
        try{operation.run();}catch(Throwable failure){if(expected.isInstance(failure))return;throw new AssertionError(message+": wrong failure",failure);}
        throw new AssertionError(message+": no failure");
    }
}
