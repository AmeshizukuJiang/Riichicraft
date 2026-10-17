package dev.riichicraft.persistence;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Server-only, versioned world container. Core game bytes never belong in a client payload. */
public final class TableArchiveStore {
    public static final int VERSION=1;
    public static final int MAX_TABLES=4_096, MAX_GAME_BYTES=2*1_024*1_024, MAX_FILE_BYTES=64*1_024*1_024;
    private static final int MAGIC=0x52435442, HEADER_BYTES=12, HASH_BYTES=32;

    public record Seat(UUID playerId,String name) {
        public Seat {
            Objects.requireNonNull(playerId,"playerId"); Objects.requireNonNull(name,"name");
            if(name.isEmpty() || name.length()>64) throw new IllegalArgumentException("Invalid player name length");
            utf8(name,256);
        }
    }
    public record Table(UUID tableId,String dimension,int x,int y,int z,int mode,long lobbyRevision,
                        long gameRevision,List<Seat> seats,byte[] gameData) {
        public Table {
            Objects.requireNonNull(tableId,"tableId"); Objects.requireNonNull(dimension,"dimension");
            if(dimension.length()>256 || !dimension.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) {
                throw new IllegalArgumentException("Invalid dimension identifier");
            }
            Objects.requireNonNull(seats,"seats"); Objects.requireNonNull(gameData,"gameData");
            if(mode<0 || mode>2 || lobbyRevision<0 || gameRevision<0 || seats.size()!=4
                    || new HashSet<>(seats.stream().map(Seat::playerId).toList()).size()!=4
                    || gameData.length<1 || gameData.length>MAX_GAME_BYTES) throw new IllegalArgumentException("Invalid saved table");
            seats=List.copyOf(seats); gameData=gameData.clone();
        }
        @Override public byte[] gameData() { return gameData.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof Table table && tableId.equals(table.tableId) && dimension.equals(table.dimension)
                    && x==table.x && y==table.y && z==table.z && mode==table.mode && lobbyRevision==table.lobbyRevision
                    && gameRevision==table.gameRevision && seats.equals(table.seats) && Arrays.equals(gameData,table.gameData);
        }
        @Override public int hashCode() {
            return 31*Objects.hash(tableId,dimension,x,y,z,mode,lobbyRevision,gameRevision,seats)+Arrays.hashCode(gameData);
        }
    }
    private record Location(String dimension,int x,int y,int z) { }
    private TableArchiveStore() { }

    public static byte[] encode(List<Table> tables) throws IOException {
        tables=List.copyOf(tables); validateTables(tables);
        long bodySize=4;
        for(Table table:tables) {
            bodySize+=16+4+utf8(table.dimension,256).length+12+1+8+8+4+table.gameData.length;
            for(Seat seat:table.seats) bodySize+=16+4+utf8(seat.name,256).length;
            if(bodySize>MAX_FILE_BYTES-HEADER_BYTES-HASH_BYTES) throw new IOException("World archive exceeds size limit");
        }
        var frame=new ByteArrayOutputStream((int)bodySize+HEADER_BYTES+HASH_BYTES);
        var output=new DataOutputStream(frame);
        output.writeInt(MAGIC); output.writeInt(VERSION); output.writeInt((int)bodySize); output.writeInt(tables.size());
        for(Table table:tables) {
            writeId(output,table.tableId); writeString(output,table.dimension);
            output.writeInt(table.x); output.writeInt(table.y); output.writeInt(table.z); output.writeByte(table.mode);
            output.writeLong(table.lobbyRevision); output.writeLong(table.gameRevision);
            for(Seat seat:table.seats) { writeId(output,seat.playerId); writeString(output,seat.name); }
            output.writeInt(table.gameData.length); output.write(table.gameData);
        }
        byte[] unsigned=frame.toByteArray(); output.write(hash(unsigned,unsigned.length));
        return frame.toByteArray();
    }

    public static List<Table> decode(byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes,"bytes");
        if(bytes.length<HEADER_BYTES+4+HASH_BYTES || bytes.length>MAX_FILE_BYTES) throw new IOException("Invalid world archive size");
        var header=new DataInputStream(new ByteArrayInputStream(bytes));
        if(header.readInt()!=MAGIC || header.readInt()!=VERSION) throw new IOException("Unsupported world archive format");
        int bodySize=header.readInt();
        if(bodySize<4 || (long)bodySize+HEADER_BYTES+HASH_BYTES!=bytes.length) throw new IOException("Invalid world archive frame");
        int signedSize=bytes.length-HASH_BYTES;
        if(!MessageDigest.isEqual(hash(bytes,signedSize),Arrays.copyOfRange(bytes,signedSize,bytes.length))) {
            throw new IOException("World archive checksum mismatch");
        }
        var input=new DataInputStream(new ByteArrayInputStream(bytes,HEADER_BYTES,bodySize));
        int count=bounded(input.readInt(),0,MAX_TABLES,"table count"); var tables=new ArrayList<Table>(count);
        try {
            for(int index=0;index<count;index++) {
                UUID id=readId(input); String dimension=readString(input);
                int x=input.readInt(),y=input.readInt(),z=input.readInt(),mode=input.readUnsignedByte();
                long lobby=input.readLong(),game=input.readLong(); var seats=new ArrayList<Seat>(4);
                for(int seat=0;seat<4;seat++) seats.add(new Seat(readId(input),readString(input)));
                int length=bounded(input.readInt(),1,MAX_GAME_BYTES,"game byte count");
                if(length>input.available()) throw new IOException("Truncated game bytes");
                byte[] core=new byte[length]; input.readFully(core);
                tables.add(new Table(id,dimension,x,y,z,mode,lobby,game,seats,core));
            }
            if(input.available()!=0) throw new IOException("Unexpected world archive tail");
            validateTables(tables); return List.copyOf(tables);
        } catch(IllegalArgumentException invalid) { throw new IOException("Invalid saved table metadata",invalid); }
    }

    /** A missing file starts empty. An unreadable or invalid existing file always fails closed. */
    public static List<Table> read(Path path) throws IOException {
        if(Files.notExists(path)) return List.of();
        if(Files.size(path)>MAX_FILE_BYTES) throw new IOException("World archive exceeds size limit");
        try(var input=Files.newInputStream(path)) {
            byte[] bytes=input.readNBytes(MAX_FILE_BYTES+1);
            if(bytes.length>MAX_FILE_BYTES) throw new IOException("World archive exceeds size limit");
            return decode(bytes);
        }
    }

    /** Force a validated temporary file, then atomically replace; never truncate the last good file. */
    public static void write(Path path,List<Table> tables) throws IOException {
        Path target=path.toAbsolutePath().normalize();
        // Also protects a file that became corrupted after this process originally loaded it.
        if(!Files.notExists(target)) read(target);
        byte[] bytes=encode(tables); Path directory=target.getParent();
        if(directory==null) throw new IOException("Archive requires a parent directory");
        Files.createDirectories(directory);
        Path temporary=Files.createTempFile(directory,target.getFileName()+".pending-",".tmp");
        try {
            try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer=ByteBuffer.wrap(bytes); while(buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
            if(!decode(Files.readAllBytes(temporary)).equals(tables)) throw new IOException("Archive write verification failed");
            try { Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch(AtomicMoveNotSupportedException unsupported) { throw new IOException("Atomic archive replacement unavailable; original retained",unsupported); }
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void validateTables(List<Table> tables) throws IOException {
        if(tables.size()>MAX_TABLES) throw new IOException("Too many saved tables");
        var ids=new HashSet<UUID>(); var locations=new HashSet<Location>(); var players=new HashSet<UUID>();
        for(Table table:tables) {
            if(!ids.add(table.tableId) || !locations.add(new Location(table.dimension,table.x,table.y,table.z))) {
                throw new IOException("Duplicate saved table identity or location");
            }
            for(Seat seat:table.seats) if(!players.add(seat.playerId)) throw new IOException("Player reserved by multiple saved tables");
        }
    }
    private static void writeId(DataOutputStream output,UUID id) throws IOException { output.writeLong(id.getMostSignificantBits()); output.writeLong(id.getLeastSignificantBits()); }
    private static UUID readId(DataInputStream input) throws IOException { return new UUID(input.readLong(),input.readLong()); }
    private static void writeString(DataOutputStream output,String text) throws IOException { byte[] bytes=utf8(text,256); output.writeInt(bytes.length); output.write(bytes); }
    private static String readString(DataInputStream input) throws IOException {
        int length=bounded(input.readInt(),1,256,"UTF-8 byte count");
        if(length>input.available()) throw new IOException("Truncated UTF-8 value");
        byte[] bytes=new byte[length]; input.readFully(bytes);
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch(CharacterCodingException invalid) { throw new IOException("Invalid UTF-8 value",invalid); }
    }
    private static byte[] utf8(String text,int limit) {
        try {
            ByteBuffer encoded=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));
            if(encoded.remaining()>limit) throw new IllegalArgumentException("UTF-8 value exceeds size limit");
            byte[] bytes=new byte[encoded.remaining()]; encoded.get(bytes); return bytes;
        } catch(CharacterCodingException invalid) { throw new IllegalArgumentException("Invalid UTF-8 value",invalid); }
    }
    private static int bounded(int value,int min,int max,String name) throws IOException {
        if(value<min || value>max) throw new IOException("Invalid "+name); return value;
    }
    private static byte[] hash(byte[] bytes,int length) {
        try { var digest=MessageDigest.getInstance("SHA-256"); digest.update(bytes,0,length); return digest.digest(); }
        catch(NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
