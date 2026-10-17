package dev.riichicraft.rules;

import dev.riichicraft.game.TileCatalog;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Bounded, versioned SERVER-ONLY core archives. These bytes contain the entire secret wall and all
 * private actions. They must never be included in a player payload. Capture on the owner thread;
 * the returned byte array is independent of subsequent game mutations.
 *
 * The SHA-256 checksum detects corruption, not hostile rewriting by someone who can replace files.
 * Restore also executes every accepted intention through the real rule engine and checks its final
 * private-state fingerprint. No arbitrary object deserialization or mutable-state injection occurs.
 */
public final class GameArchiveCodec {
    public static final int MAX_BYTES=2*1024*1024;
    public static final int MAX_ACTIONS=1024;
    public static final int SCHEMA_VERSION=1;
    /** Bump when accepted-action semantics, deterministic resolution or fingerprint layout changes. */
    public static final int REPLAY_VERSION=1;
    public static final int MAGIC=0x52434152; // RCAR
    private static final int ROUND=1, MATCH=2, HASH_BYTES=32;

    enum ActionKind { DISCARD, RIICHI, TSUMO, NINE_TERMINALS, CLOSED_KAN, ADDED_KAN, PASS, RON, CHI, PON, OPEN_KAN }

    /** Canonical physical selections; only genuine ACCEPTED user intentions enter this journal. */
    record Action(ActionKind kind,int seat,int tile,long windowSerial,int meldIndex,List<Integer> tiles) {
        Action {
            Objects.requireNonNull(kind,"kind");
            if(seat<0 || seat>3) throw invalid("Invalid action seat");
            tiles=List.copyOf(tiles);
            if(!tiles.equals(tiles.stream().sorted().toList()) || new HashSet<>(tiles).size()!=tiles.size()) throw invalid("Noncanonical action selection");
            tiles.forEach(TileCatalog::typeOf);
            boolean discard=kind==ActionKind.DISCARD || kind==ActionKind.RIICHI;
            boolean response=kind.ordinal()>=ActionKind.PASS.ordinal();
            if(discard) TileCatalog.typeOf(tile); else if(tile!=-1) throw invalid("Unexpected action tile");
            if(response?windowSerial<0:windowSerial!=-1) throw invalid("Invalid action window");
            if(kind==ActionKind.ADDED_KAN?meldIndex<0 || meldIndex>3:meldIndex!=-1) throw invalid("Invalid action meld index");
            int count=switch(kind) { case CLOSED_KAN->4; case ADDED_KAN->1; case CHI,PON->2; case OPEN_KAN->3; default->0; };
            if(tiles.size()!=count) throw invalid("Invalid action selection size");
            if(kind==ActionKind.CLOSED_KAN) new KanActions.Choice(KanActions.Kind.CLOSED,-1,tiles);
        }
        static Action turn(ActionKind kind,int seat) { return new Action(kind,seat,-1,-1,-1,List.of()); }
        static Action response(int seat,long serial,DiscardResponseWindow.Choice choice) {
            if(choice instanceof DiscardResponseWindow.Simple simple) return new Action(simple==DiscardResponseWindow.Simple.PASS?ActionKind.PASS:ActionKind.RON,seat,-1,serial,-1,List.of());
            var call=(DiscardResponseWindow.Call)choice;
            ActionKind kind=switch(call.kind()) { case CHI->ActionKind.CHI; case PON->ActionKind.PON; case OPEN_KAN->ActionKind.OPEN_KAN; default->throw invalid("Invalid response call"); };
            return new Action(kind,seat,-1,serial,-1,call.handTiles());
        }
        DiscardResponseWindow.Choice responseChoice() {
            return switch(kind) {
                case PASS -> DiscardResponseWindow.Simple.PASS;
                case RON -> DiscardResponseWindow.Simple.RON;
                case CHI,PON,OPEN_KAN -> new DiscardResponseWindow.Call(switch(kind) { case CHI->Meld.Kind.CHI; case PON->Meld.Kind.PON; default->Meld.Kind.OPEN_KAN; },tiles);
                default -> throw invalid("Not a response action");
            };
        }
    }

    record RoundStart(UUID roundId,List<UUID> players,int dealerSeat,int roundWind,int honba,List<Integer> points,int deposits,List<Integer> order) {
        RoundStart {
            Objects.requireNonNull(roundId,"roundId"); players=List.copyOf(players); points=List.copyOf(points); order=List.copyOf(order);
            if(players.size()!=4 || new HashSet<>(players).size()!=4 || roundWind<27 || roundWind>30) throw invalid("Invalid round setup");
            RoundLedger.begin(roundId,dealerSeat,honba,points,deposits);
            RoundWall.deal(order);
        }
    }
    record RoundArchive(RoundStart start,List<Action> actions,byte[] fingerprint) {
        RoundArchive { Objects.requireNonNull(start,"start"); actions=List.copyOf(actions); fingerprint=fingerprint.clone();
            if(actions.size()>MAX_ACTIONS || fingerprint.length!=HASH_BYTES) throw invalid("Invalid round archive size"); }
        @Override public byte[] fingerprint() { return fingerprint.clone(); }
    }
    record MatchArchive(UUID matchId,MatchRules.Format format,int initialDealer,int roundIndex,long revision,long continueEpoch,
                        FourPlayerMatch.Phase phase,List<UUID> usedRoundIds,List<Integer> confirmations,RoundArchive round,byte[] fingerprint) {
        MatchArchive { Objects.requireNonNull(matchId,"matchId"); Objects.requireNonNull(format,"format"); Objects.requireNonNull(phase,"phase");
            usedRoundIds=List.copyOf(usedRoundIds); confirmations=List.copyOf(confirmations); Objects.requireNonNull(round,"round"); fingerprint=fingerprint.clone();
            if(fingerprint.length!=HASH_BYTES) throw invalid("Invalid match fingerprint"); }
        @Override public byte[] fingerprint() { return fingerprint.clone(); }
    }

    private GameArchiveCodec() { }

    public static byte[] encodeRound(FourPlayerRound round) {
        if(round==null) throw invalid("Round required");
        return frame(ROUND,writeRound(round.archive()));
    }
    public static FourPlayerRound decodeRound(byte[] bytes) {
        try {
            var input=unframe(bytes,ROUND); RoundArchive archive=readRound(input); requireEnd(input);
            return FourPlayerRound.restore(archive);
        } catch(IOException | RuntimeException failure) { throw decodeFailure(failure); }
    }
    public static byte[] encodeMatch(FourPlayerMatch match) {
        if(match==null) throw invalid("Match required");
        var archive=match.archive();
        return frame(MATCH,write(out->{
            writeUuid(out,archive.matchId()); out.writeInt(archive.format().ordinal()); out.writeInt(archive.initialDealer()); out.writeInt(archive.roundIndex());
            out.writeLong(archive.revision()); out.writeLong(archive.continueEpoch()); out.writeInt(archive.phase().ordinal());
            if(archive.usedRoundIds().size()>MAX_BYTES/16) throw invalid("Too many historical round identities");
            out.writeInt(archive.usedRoundIds().size()); for(UUID id:archive.usedRoundIds()) writeUuid(out,id);
            int votes=0; for(int seat:archive.confirmations()) votes|=1<<seat; out.writeInt(votes);
            writeBytes(out,writeRound(archive.round())); out.write(archive.fingerprint());
        }));
    }
    /** The supplied factory is retained for a future fourth confirmation, and is never invoked here. */
    public static FourPlayerMatch decodeMatch(byte[] bytes,FourPlayerMatch.RoundFactory factory) {
        if(factory==null) throw invalid("Round factory required");
        try {
            var in=unframe(bytes,MATCH); UUID matchId=readUuid(in);
            var format=readEnum(in,MatchRules.Format.values()); int dealer=in.readInt(),index=in.readInt();
            long revision=in.readLong(),epoch=in.readLong(); var phase=readEnum(in,FourPlayerMatch.Phase.values());
            int usedCount=readCount(in,MAX_BYTES/16,16); List<UUID> used=new ArrayList<>(usedCount);
            for(int count=0;count<usedCount;count++) used.add(readUuid(in));
            int mask=in.readInt(); if(mask<0 || mask>15) throw invalid("Invalid next-round votes");
            List<Integer> votes=new ArrayList<>(); for(int seat=0;seat<4;seat++) if((mask&(1<<seat))!=0) votes.add(seat);
            var roundIn=new DataInputStream(new ByteArrayInputStream(readBytes(in,MAX_BYTES)));
            RoundArchive round=readRound(roundIn); requireEnd(roundIn); byte[] fingerprint=readExact(in,HASH_BYTES); requireEnd(in);
            return FourPlayerMatch.restore(new MatchArchive(matchId,format,dealer,index,revision,epoch,phase,used,votes,round,fingerprint),factory);
        } catch(IOException | RuntimeException failure) { throw decodeFailure(failure); }
    }

    private static byte[] writeRound(RoundArchive archive) {
        return write(out->{
            var start=archive.start(); writeUuid(out,start.roundId()); for(UUID player:start.players()) writeUuid(out,player);
            out.writeInt(start.dealerSeat()); out.writeInt(start.roundWind()); out.writeInt(start.honba());
            for(int point:start.points()) out.writeInt(point); out.writeInt(start.deposits()); writeInts(out,start.order());
            out.writeInt(archive.actions().size());
            for(var action:archive.actions()) {
                out.writeInt(action.kind().ordinal()); out.writeInt(action.seat()); out.writeInt(action.tile());
                out.writeLong(action.windowSerial()); out.writeInt(action.meldIndex()); writeInts(out,action.tiles());
            }
            out.write(archive.fingerprint());
        });
    }
    private static RoundArchive readRound(DataInputStream in) throws IOException {
        UUID id=readUuid(in); List<UUID> players=new ArrayList<>(); for(int seat=0;seat<4;seat++) players.add(readUuid(in));
        int dealer=in.readInt(),wind=in.readInt(),honba=in.readInt(); List<Integer> points=new ArrayList<>();
        for(int seat=0;seat<4;seat++) points.add(in.readInt()); int deposits=in.readInt(); List<Integer> order=readInts(in,136);
        if(order.size()!=136) throw invalid("Archive wall must contain 136 physical tiles");
        var start=new RoundStart(id,players,dealer,wind,honba,points,deposits,order);
        int count=readCount(in,MAX_ACTIONS,28); List<Action> actions=new ArrayList<>(count);
        for(int index=0;index<count;index++) {
            var kind=readEnum(in,ActionKind.values()); int seat=in.readInt(),tile=in.readInt(); long serial=in.readLong(); int meld=in.readInt();
            actions.add(new Action(kind,seat,tile,serial,meld,readInts(in,4)));
        }
        return new RoundArchive(start,actions,readExact(in,HASH_BYTES));
    }

    private static byte[] frame(int kind,byte[] body) {
        byte[] payload=write(out->{
            out.writeInt(MAGIC); out.writeInt(SCHEMA_VERSION); out.writeInt(REPLAY_VERSION); out.writeInt(kind); out.writeInt(FourPlayerRules.REVISION);
            writeBytes(out,FourPlayerRules.ID.getBytes(StandardCharsets.UTF_8)); writeBytes(out,body);
        });
        if(payload.length>MAX_BYTES-HASH_BYTES) throw invalid("Core archive exceeds size limit");
        byte[] bytes=Arrays.copyOf(payload,payload.length+HASH_BYTES);
        System.arraycopy(sha256(payload),0,bytes,payload.length,HASH_BYTES); return bytes;
    }
    private static DataInputStream unframe(byte[] supplied,int expectedKind) throws IOException {
        if(supplied==null || supplied.length<7*4+HASH_BYTES || supplied.length>MAX_BYTES) throw invalid("Invalid core archive length");
        byte[] bytes=supplied.clone(); int payloadLength=bytes.length-HASH_BYTES;
        byte[] payload=Arrays.copyOf(bytes,payloadLength),checksum=Arrays.copyOfRange(bytes,payloadLength,bytes.length);
        if(!MessageDigest.isEqual(checksum,sha256(payload))) throw invalid("Core archive checksum mismatch");
        var in=new DataInputStream(new ByteArrayInputStream(payload));
        if(in.readInt()!=MAGIC || in.readInt()!=SCHEMA_VERSION || in.readInt()!=REPLAY_VERSION || in.readInt()!=expectedKind
                || in.readInt()!=FourPlayerRules.REVISION) throw invalid("Unsupported core archive or replay/rule version");
        byte[] ruleId=readBytes(in,128);
        if(!Arrays.equals(ruleId,FourPlayerRules.ID.getBytes(StandardCharsets.UTF_8))) throw invalid("Unsupported archive rules");
        byte[] body=readBytes(in,MAX_BYTES); requireEnd(in); return new DataInputStream(new ByteArrayInputStream(body));
    }

    /** Record/list text here contains no unordered maps/sets or object identities. Length-prefix each token. */
    static byte[] fingerprint(List<String> tokens) {
        return sha256(write(out->{ for(String token:tokens) writeBytes(out,token.getBytes(StandardCharsets.UTF_8)); }));
    }
    private static byte[] sha256(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable",impossible); }
    }
    @FunctionalInterface private interface Writer { void write(DataOutputStream out) throws IOException; }
    private static byte[] write(Writer writer) {
        try { var bytes=new ByteArrayOutputStream(); var out=new DataOutputStream(bytes); writer.write(out); out.flush();
            if(bytes.size()>MAX_BYTES) throw invalid("Core archive exceeds size limit"); return bytes.toByteArray();
        } catch(IOException impossible) { throw new IllegalStateException("In-memory archive write failed",impossible); }
    }
    private static void writeUuid(DataOutputStream out,UUID id) throws IOException { out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits()); }
    private static UUID readUuid(DataInputStream in) throws IOException { return new UUID(in.readLong(),in.readLong()); }
    private static void writeBytes(DataOutputStream out,byte[] bytes) throws IOException { out.writeInt(bytes.length); out.write(bytes); }
    private static byte[] readBytes(DataInputStream in,int maximum) throws IOException { return readExact(in,readCount(in,maximum,1)); }
    private static byte[] readExact(DataInputStream in,int count) throws IOException {
        if(count<0 || count>in.available()) throw invalid("Truncated archive value");
        byte[] bytes=new byte[count]; in.readFully(bytes); return bytes;
    }
    private static void writeInts(DataOutputStream out,List<Integer> values) throws IOException { out.writeInt(values.size()); for(int value:values) out.writeInt(value); }
    private static List<Integer> readInts(DataInputStream in,int maximum) throws IOException {
        int count=readCount(in,maximum,4); List<Integer> values=new ArrayList<>(count); for(int index=0;index<count;index++) values.add(in.readInt()); return values;
    }
    private static int readCount(DataInputStream in,int maximum,int minimumBytesPerValue) throws IOException {
        int count=in.readInt();
        if(count<0 || count>maximum || (long)count*minimumBytesPerValue>in.available()) throw invalid("Invalid or truncated archive count");
        return count;
    }
    private static <T> T readEnum(DataInputStream in,T[] values) throws IOException {
        int index=in.readInt(); if(index<0 || index>=values.length) throw invalid("Invalid archive enum"); return values[index];
    }
    private static void requireEnd(DataInputStream in) throws IOException { if(in.available()!=0) throw invalid("Trailing archive bytes"); }
    private static IllegalArgumentException invalid(String reason) { return new IllegalArgumentException(reason); }
    private static IllegalArgumentException decodeFailure(Exception failure) {
        return failure instanceof IllegalArgumentException invalid?invalid:new IllegalArgumentException("Invalid core archive",failure);
    }
}
