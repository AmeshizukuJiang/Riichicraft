package dev.riichicraft.network;

import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/** Recipient-only bounded DTO. No wall order, shuffle seed, hidden indicators or other private hands. */
public record GameSnapshot(UUID gameId, long revision, int turnSeat, int remainingTiles,
        List<Seat> seats, List<Integer> ownHand, int drawnTile, boolean paused, String statusKey, RuleState rules, MatchInfo match) {
    public static final int DRAWN_TURN=0, CALL_DISCARD=1, DISCARD_RESPONSES=2, KAN_RESPONSES=3, FINISHED=4, NORTH_RESPONSES=5;
    public static final int MELD_CHI=0, MELD_PON=1, MELD_OPEN_KAN=2, MELD_CLOSED_KAN=3;
    public static final int FORMAT_EAST=0, FORMAT_SOUTH=1;
    public static final int MATCH_PLAYING=0, MATCH_ROUND_RESULT=1, MATCH_RESULT=2;
    private static final int MAX_HAND_SIZE=14, MAX_DISCARDS=136, MAX_STATUS_LENGTH=128;
    public static final StreamCodec<RegistryFriendlyByteBuf,GameSnapshot> CODEC=StreamCodec.of(GameSnapshot::encode,GameSnapshot::decode);

    /** Kept for isolated legacy DTO fixtures. Live service snapshots always contain rules. */
    public GameSnapshot(UUID gameId,long revision,int turnSeat,int remainingTiles,List<Seat> seats,
                        List<Integer> ownHand,int drawnTile,boolean paused,String statusKey) {
        this(gameId,revision,turnSeat,remainingTiles,seats,ownHand,drawnTile,paused,statusKey,null,null);
    }

    public GameSnapshot(UUID gameId,long revision,int turnSeat,int remainingTiles,List<Seat> seats,
                        List<Integer> ownHand,int drawnTile,boolean paused,String statusKey,RuleState rules) {
        this(gameId,revision,turnSeat,remainingTiles,seats,ownHand,drawnTile,paused,statusKey,rules,null);
    }

    public GameSnapshot {
        Objects.requireNonNull(gameId,"gameId"); Objects.requireNonNull(statusKey,"statusKey");
        seats=List.copyOf(seats); ownHand=tiles(ownHand,MAX_HAND_SIZE,"own hand");
        if(revision<0 || !range(turnSeat,0,seats.size()-1) || !range(remainingTiles,0,69) || (seats.size()!=3 && seats.size()!=4)
                || !range(drawnTile,-1,135) || statusKey.length()>MAX_STATUS_LENGTH) fail("game snapshot metadata");
        if(rules!=null) {
            if(drawnTile>=0 && !ownHand.contains(drawnTile)) fail("draw reference outside own hand");
            if(!ownHand.containsAll(rules.legalDiscards()) || !ownHand.containsAll(rules.riichiDiscards())) fail("discard mask ownership");
            for(ActionOption option:rules.kans()) if(!ownHand.containsAll(option.handTiles())) fail("kan selection ownership");
            for(ActionOption option:rules.responses()) if(!ownHand.containsAll(option.handTiles())) fail("response selection ownership");
            if(rules.seats().size()!=seats.size()) fail("seat list agreement");
            if(!ownHand.containsAll(rules.kitas())) fail("kita ownership");
            for(int seat=0;seat<seats.size();seat++) if(rules.seats().get(seat).river().size()!=seats.get(seat).discards().size()) fail("river marks length");
        }
        if(match!=null && (rules==null || (match.phase()==MATCH_PLAYING
                ? rules.phase()==FINISHED : rules.phase()!=FINISHED))) fail("match/round phase agreement");
        if(match!=null && match.confirmations().size()!=seats.size()) fail("match seat count");
        if(seats.size()==3) {
            ownHand.forEach(GameSnapshot::sanmaTile);
            for(var seat:seats) seat.discards().forEach(GameSnapshot::sanmaTile);
            if(remainingTiles>54) fail("sanma live count");
            if(rules!=null) {
                if(rules.dealerSeat()>2 || rules.roundWind()>29) fail("sanma wind");
                rules.doraIndicators().forEach(GameSnapshot::sanmaTile);
                if(rules.result()!=null) rules.result().uraIndicators().forEach(GameSnapshot::sanmaTile);
                if(rules.opportunity()!=null && rules.opportunity().actorSeat()>2) fail("sanma opportunity actor");
                if(rules.result()!=null && (rules.result().winners().stream().anyMatch(seat->seat>2)
                        || rules.result().tenpai().stream().anyMatch(seat->seat>2)
                        || rules.result().values().stream().anyMatch(value->value.seat()>2)
                        || rules.result().transfers().stream().anyMatch(payment->payment.fromSeat()>2 || payment.toSeat()>2))) fail("sanma result seats");
                for(var seat:rules.seats()) for(var meld:seat.melds()) {
                    if(meld.kind()==MELD_CHI || meld.providerSeat()>2) fail("sanma meld");
                    meld.tiles().forEach(GameSnapshot::sanmaTile);
                }
            }
        } else if(rules!=null && (!rules.kitas().isEmpty() || rules.phase()==NORTH_RESPONSES
                || rules.seats().stream().anyMatch(seat->!seat.norths().isEmpty()))) fail("four-player north extraction");
    }

    public record Seat(int handCount,List<Integer> discards,boolean present,boolean confirmed) {
        public Seat { if(!range(handCount,0,14)) fail("concealed count"); discards=tiles(discards,MAX_DISCARDS,"river"); }
    }
    /** Public match progress; single-round tables have no MatchInfo. Epoch revokes old continuation votes. */
    public record MatchInfo(UUID matchId,int format,int phase,int initialDealer,int roundIndex,long continueEpoch,
                            List<Boolean> confirmations,MatchResult result) {
        public MatchInfo {
            Objects.requireNonNull(matchId,"matchId"); confirmations=bounded(confirmations,4,"next-round confirmations");
            if(!range(format,FORMAT_EAST,FORMAT_SOUTH) || !range(phase,MATCH_PLAYING,MATCH_RESULT)
                    || !range(initialDealer,0,confirmations.size()-1) || !range(roundIndex,0,confirmations.size()==3?(format==FORMAT_EAST?5:8):(format==FORMAT_EAST?7:11)) || continueEpoch<0 || (confirmations.size()!=3 && confirmations.size()!=4)
                    || (result!=null)!=(phase==MATCH_RESULT)) fail("match progress");
            if(result!=null && result.standings().size()!=confirmations.size()) fail("match result seat count");
        }
    }
    public record Standing(int seat,int rank,int points,int rawTenths,int umaTenths,int totalTenths) {
        public Standing { if(!range(seat,0,3) || !range(rank,1,4) || points%100!=0) fail("standing"); }
    }
    public record MatchResult(String reason,List<Standing> standings,int depositRecipient,int depositPoints) {
        public MatchResult {
            standings=bounded(standings,4,"standings");
            if(reason==null || reason.isEmpty() || reason.length()>64 || (standings.size()!=3 && standings.size()!=4)
                    || new HashSet<>(standings.stream().map(Standing::seat).toList()).size()!=standings.size()
                    || new HashSet<>(standings.stream().map(Standing::rank).toList()).size()!=standings.size()
                    || !range(depositRecipient,-1,3) || depositPoints<0 || depositPoints%1_000!=0
                    || (depositRecipient==-1)!=(depositPoints==0)) fail("match result");
            int count=standings.size();
            if(depositRecipient>=count || standings.stream().anyMatch(s->s.seat()>=count || s.rank()>count)) fail("standing bounds");
        }
    }
    public record MeldView(int kind,List<Integer> tiles,int providerSeat,int claimedTileId) {
        public MeldView {
            tiles=GameSnapshot.tiles(tiles,4,"meld");
            if(!range(kind,MELD_CHI,MELD_CLOSED_KAN) || tiles.size()!=(kind>=MELD_OPEN_KAN?4:3)
                    || (kind==MELD_CLOSED_KAN ? providerSeat!=-1 || claimedTileId!=-1
                    : !range(providerSeat,0,3) || !tiles.contains(claimedTileId))) fail("meld metadata");
        }
    }
    /** Parallel to Seat.discards, retaining called-away entries for history/display. */
    public record RiverMark(boolean declaration,boolean tsumogiri,int claimedBy) {
        public RiverMark { if(!range(claimedBy,-1,3)) fail("claiming seat"); }
    }
    public record SeatRules(List<MeldView> melds,List<RiverMark> river,int points,int riichiPhase,List<Integer> norths) {
        public SeatRules(List<MeldView> melds,List<RiverMark> river,int points,int riichiPhase) { this(melds,river,points,riichiPhase,List.of()); }
        public SeatRules {
            melds=bounded(melds,4,"melds"); river=bounded(river,MAX_DISCARDS,"river marks");
            norths=tiles(norths,4,"extracted norths"); if(norths.stream().anyMatch(t->t/4!=30)) fail("non-north extraction");
            if(points%100!=0 || !range(riichiPhase,0,2)) fail("seat rules");
        }
    }
    public record ActionOption(int action,int meldIndex,List<Integer> handTiles) {
        public ActionOption {
            handTiles=tiles(handTiles,4,"action selection");
            if(!GameActionPayload.isResponse(action) && action!=GameActionPayload.CLOSED_KAN && action!=GameActionPayload.ADDED_KAN
                    && action!=GameActionPayload.TSUMO && action!=GameActionPayload.NINE_TERMINALS && action!=GameActionPayload.KITA) fail("offered action");
            GameActionPayload.validateChoice(action,meldIndex,handTiles);
            if(action==GameActionPayload.KITA && handTiles.getFirst()/4!=30) fail("kita option tile");
        }
    }
    public record Opportunity(long serial,int actorSeat,int tileId,boolean kan) {
        public Opportunity { if(serial<0 || !range(actorSeat,0,3) || !range(tileId,0,135)) fail("opportunity"); }
    }
    public record Award(String yaku,int han,int yakumanUnits) {
        public Award { if(yaku==null || yaku.isEmpty() || yaku.length()>64 || han<0 || yakumanUnits<0) fail("yaku award"); }
    }
    public record WinValue(int seat,int winningTileId,List<Award> yaku,int han,int fu,int yakumanUnits,
                           int dora,int aka,int ura,int totalPoints,int kita) {
        public WinValue(int seat,int winningTileId,List<Award> yaku,int han,int fu,int yakumanUnits,int dora,int aka,int ura,int totalPoints) { this(seat,winningTileId,yaku,han,fu,yakumanUnits,dora,aka,ura,totalPoints,0); }
        public WinValue {
            yaku=bounded(yaku,45,"yaku awards");
            if(!range(seat,0,3) || !range(winningTileId,0,135) || han<0 || fu<0 || yakumanUnits<0
                    || dora<0 || aka<0 || ura<0 || kita<0 || kita>4 || totalPoints<0) fail("winning value");
        }
    }
    /** Kind follows RoundLedger.Kind: HAND, HONBA, DEPOSIT, NOTEN, NAGASHI. */
    public record Payment(int fromSeat,int toSeat,int points,int kind) {
        public Payment { if(!range(fromSeat,-1,3) || !range(toSeat,0,3) || fromSeat==toSeat || points<=0 || points%100!=0
                || !range(kind,0,4) || (fromSeat==-1)!=(kind==2)) fail("payment"); }
    }
    /** Kind follows FourPlayerRound.EndKind. Winner values reveal no non-winner private tiles. */
    public record Result(int kind,List<Integer> winners,List<Integer> tenpai,List<WinValue> values,
                         List<Payment> transfers,boolean dealerContinues,List<Integer> uraIndicators) {
        public Result(int kind,List<Integer> winners,List<Integer> tenpai,List<WinValue> values,List<Payment> transfers,boolean dealerContinues) {
            this(kind,winners,tenpai,values,transfers,dealerContinues,List.of());
        }
        public Result {
            uraIndicators=tiles(uraIndicators,5,"revealed ura indicators");
            if(!uraIndicators.isEmpty() && kind!=0 && kind!=1) fail("ura without win");
            winners=seatIndices(winners); tenpai=seatIndices(tenpai); values=bounded(values,4,"winner values");
            transfers=bounded(transfers,128,"payments");
            if(!range(kind,0,7) || new HashSet<>(values.stream().map(WinValue::seat).toList()).size()!=values.size()) fail("round result");
            for(WinValue value:values) if(!winners.contains(value.seat())) fail("winner value without winner");
        }
    }
    public record RuleState(int phase,int dealerSeat,int roundWind,int honba,int deposits,List<Integer> doraIndicators,
                            List<SeatRules> seats,List<Integer> legalDiscards,List<Integer> riichiDiscards,
                            List<ActionOption> kans,boolean canTsumo,boolean canNineTerminals,Opportunity opportunity,
                            List<ActionOption> responses,Result result,List<Integer> kitas) {
        public RuleState(int phase,int dealerSeat,int roundWind,int honba,int deposits,List<Integer> doraIndicators,List<SeatRules> seats,List<Integer> legalDiscards,List<Integer> riichiDiscards,List<ActionOption> kans,boolean canTsumo,boolean canNineTerminals,Opportunity opportunity,List<ActionOption> responses,Result result) { this(phase,dealerSeat,roundWind,honba,deposits,doraIndicators,seats,legalDiscards,riichiDiscards,kans,canTsumo,canNineTerminals,opportunity,responses,result,List.of()); }
        public RuleState {
            kitas=tiles(kitas,4,"kita mask"); if(kitas.stream().anyMatch(t->t/4!=30) || !kitas.isEmpty() && phase!=DRAWN_TURN) fail("kita action phase");
            doraIndicators=tiles(doraIndicators,5,"dora indicators"); seats=bounded(seats,4,"rule seats");
            boolean riichiWinner=false;
            if(result!=null) for(int winner:result.winners()) if(winner<seats.size() && seats.get(winner).riichiPhase()==2) riichiWinner=true;
            if(result!=null && !result.uraIndicators().isEmpty()
                    && (result.uraIndicators().size()!=doraIndicators.size() || !riichiWinner)) fail("ura without riichi winner");
            legalDiscards=tiles(legalDiscards,14,"discard mask"); riichiDiscards=tiles(riichiDiscards,14,"riichi mask");
            kans=bounded(kans,16,"kan options"); responses=bounded(responses,64,"responses");
            if(!range(phase,DRAWN_TURN,NORTH_RESPONSES) || !range(dealerSeat,0,3) || !range(roundWind,27,30)
                    || honba<0 || deposits<0 || (seats.size()!=3 && seats.size()!=4) || !legalDiscards.containsAll(riichiDiscards)
                    || kans.stream().anyMatch(k->k.action()!=GameActionPayload.CLOSED_KAN && k.action()!=GameActionPayload.ADDED_KAN)
                    || responses.stream().anyMatch(r->!GameActionPayload.isResponse(r.action()))
                    || (opportunity!=null)!=(phase==DISCARD_RESPONSES || phase==KAN_RESPONSES || phase==NORTH_RESPONSES)
                    || (result!=null)!=(phase==FINISHED)
                    || (phase==FINISHED && (!legalDiscards.isEmpty() || !riichiDiscards.isEmpty() || !kans.isEmpty()
                    || canTsumo || canNineTerminals || !responses.isEmpty()))) fail("rule state");
        }
    }

    private static void encode(RegistryFriendlyByteBuf buf,GameSnapshot value) {
        buf.writeUUID(value.gameId()); buf.writeLong(value.revision()); buf.writeVarInt(value.turnSeat()); buf.writeVarInt(value.remainingTiles());
        buf.writeVarInt(value.seats().size());
        for(Seat seat:value.seats()) {
            buf.writeVarInt(seat.handCount()); writeTiles(buf,seat.discards()); buf.writeBoolean(seat.present()); buf.writeBoolean(seat.confirmed());
        }
        writeTiles(buf,value.ownHand()); buf.writeVarInt(value.drawnTile()); buf.writeBoolean(value.paused());
        buf.writeUtf(value.statusKey(),MAX_STATUS_LENGTH); buf.writeBoolean(value.rules()!=null);
        if(value.rules()!=null) writeRules(buf,value.rules());
        buf.writeBoolean(value.match()!=null); if(value.match()!=null) writeMatch(buf,value.match());
    }
    private static GameSnapshot decode(RegistryFriendlyByteBuf buf) {
        UUID game=buf.readUUID(); long revision=buf.readLong(); int actor=readRange(buf,0,3,"turn seat"), live=readRange(buf,0,69,"live tiles");
        int seatCount=readRange(buf,3,4,"seat count"); List<Seat> seats=new ArrayList<>(seatCount);
        for(int seat=0;seat<seatCount;seat++) seats.add(new Seat(readRange(buf,0,14,"concealed count"),readTiles(buf,MAX_DISCARDS,"river"),buf.readBoolean(),buf.readBoolean()));
        List<Integer> hand=readTiles(buf,14,"own hand"); int drawn=readRange(buf,-1,135,"drawn tile");
        boolean paused=buf.readBoolean(); String status=buf.readUtf(MAX_STATUS_LENGTH);
        RuleState rules=buf.readBoolean()?readRules(buf):null;
        return new GameSnapshot(game,revision,actor,live,seats,hand,drawn,paused,status,rules,buf.readBoolean()?readMatch(buf):null);
    }
    private static void writeMatch(RegistryFriendlyByteBuf b,MatchInfo m) {
        b.writeUUID(m.matchId());b.writeVarInt(m.format());b.writeVarInt(m.phase());b.writeVarInt(m.initialDealer());
        b.writeVarInt(m.roundIndex());b.writeLong(m.continueEpoch());
        b.writeVarInt(m.confirmations().size());
        for(boolean confirmed:m.confirmations()) b.writeBoolean(confirmed);
        b.writeBoolean(m.result()!=null);
        if(m.result()!=null) {
            var result=m.result(); b.writeUtf(result.reason(),64);
            writeList(b,result.standings(),(buf,s)->{
                buf.writeVarInt(s.seat());buf.writeVarInt(s.rank());buf.writeInt(s.points());
                buf.writeInt(s.rawTenths());buf.writeInt(s.umaTenths());buf.writeInt(s.totalTenths());
            });
            b.writeVarInt(result.depositRecipient());b.writeVarInt(result.depositPoints());
        }
    }
    private static MatchInfo readMatch(RegistryFriendlyByteBuf b) {
        UUID matchId=b.readUUID();int format=readRange(b,0,1,"match format"),phase=readRange(b,0,2,"match phase");
        int dealer=readRange(b,0,3,"initial dealer"),round=readRange(b,0,11,"round index");long epoch=b.readLong();
        int count=readRange(b,3,4,"confirmation count"); List<Boolean> confirmed=new ArrayList<>(count);for(int seat=0;seat<count;seat++) confirmed.add(b.readBoolean());
        MatchResult result=null;
        if(b.readBoolean()) {
            String reason=b.readUtf(64);
            List<Standing> standings=readList(b,4,"standings",buf->new Standing(readRange(buf,0,3,"standing seat"),
                    readRange(buf,1,4,"rank"),buf.readInt(),buf.readInt(),buf.readInt(),buf.readInt()));
            result=new MatchResult(reason,standings,readRange(b,-1,3,"deposit recipient"),positive(b,"final deposits"));
        }
        return new MatchInfo(matchId,format,phase,dealer,round,epoch,confirmed,result);
    }
    private static void writeRules(RegistryFriendlyByteBuf b,RuleState r) {
        b.writeVarInt(r.phase()); b.writeVarInt(r.dealerSeat()); b.writeVarInt(r.roundWind()); b.writeVarInt(r.honba()); b.writeVarInt(r.deposits());
        writeTiles(b,r.doraIndicators());
        writeList(b,r.seats(),(buf,s)->{
            writeList(buf,s.melds(),(out,m)->{out.writeVarInt(m.kind()); writeTiles(out,m.tiles()); out.writeVarInt(m.providerSeat()); out.writeVarInt(m.claimedTileId());});
            writeList(buf,s.river(),(out,m)->{out.writeBoolean(m.declaration()); out.writeBoolean(m.tsumogiri()); out.writeVarInt(m.claimedBy());});
            buf.writeInt(s.points()); buf.writeVarInt(s.riichiPhase()); writeTiles(buf,s.norths());
        });
        writeTiles(b,r.legalDiscards()); writeTiles(b,r.riichiDiscards()); writeTiles(b,r.kitas()); writeList(b,r.kans(),GameSnapshot::writeOption);
        b.writeBoolean(r.canTsumo()); b.writeBoolean(r.canNineTerminals()); b.writeBoolean(r.opportunity()!=null);
        if(r.opportunity()!=null) {var o=r.opportunity();b.writeLong(o.serial());b.writeVarInt(o.actorSeat());b.writeVarInt(o.tileId());b.writeBoolean(o.kan());}
        writeList(b,r.responses(),GameSnapshot::writeOption); b.writeBoolean(r.result()!=null);
        if(r.result()!=null) writeResult(b,r.result());
    }
    private static RuleState readRules(RegistryFriendlyByteBuf b) {
        int phase=readRange(b,0,5,"phase"), dealer=readRange(b,0,3,"dealer"), wind=readRange(b,27,30,"round wind");
        int honba=readRange(b,0,Integer.MAX_VALUE,"honba"), deposits=readRange(b,0,Integer.MAX_VALUE,"deposits");
        List<Integer> dora=readTiles(b,5,"dora indicators");
        List<SeatRules> seats=readList(b,4,"rule seats",buf->{
            List<MeldView> melds=readList(buf,4,"melds",in->{
                int kind=readRange(in,0,3,"meld kind"); List<Integer> tiles=readTiles(in,4,"meld tiles");
                return new MeldView(kind,tiles,readRange(in,-1,3,"provider"),readRange(in,-1,135,"claim"));
            });
            List<RiverMark> river=readList(buf,MAX_DISCARDS,"river marks",in->new RiverMark(in.readBoolean(),in.readBoolean(),readRange(in,-1,3,"claiming seat")));
            return new SeatRules(melds,river,buf.readInt(),readRange(buf,0,2,"riichi phase"),readTiles(buf,4,"extracted norths"));
        });
        List<Integer> discards=readTiles(b,14,"discard mask"), riichi=readTiles(b,14,"riichi mask");
        List<Integer> kitas=readTiles(b,4,"kita mask"); List<ActionOption> kans=readList(b,16,"kan options",GameSnapshot::readOption);
        boolean tsumo=b.readBoolean(), nine=b.readBoolean();
        Opportunity o=b.readBoolean()?new Opportunity(b.readLong(),readRange(b,0,3,"opportunity actor"),readRange(b,0,135,"opportunity tile"),b.readBoolean()):null;
        List<ActionOption> responses=readList(b,64,"responses",GameSnapshot::readOption);
        return new RuleState(phase,dealer,wind,honba,deposits,dora,seats,discards,riichi,kans,tsumo,nine,o,responses,b.readBoolean()?readResult(b):null,kitas);
    }
    private static void writeOption(RegistryFriendlyByteBuf b,ActionOption o) {
        b.writeVarInt(o.action()); b.writeVarInt(o.meldIndex()); writeTiles(b,o.handTiles());
    }
    private static ActionOption readOption(RegistryFriendlyByteBuf b) {
        return new ActionOption(readRange(b,GameActionPayload.TSUMO,GameActionPayload.KITA,"option action"),readRange(b,-1,3,"meld index"),readTiles(b,4,"option tiles"));
    }
    private static void writeResult(RegistryFriendlyByteBuf b,Result r) {
        b.writeVarInt(r.kind()); writeTiles(b,r.winners()); writeTiles(b,r.tenpai());
        writeList(b,r.values(),(buf,v)->{
            buf.writeVarInt(v.seat()); buf.writeVarInt(v.winningTileId());
            writeList(buf,v.yaku(),(out,a)->{out.writeUtf(a.yaku(),64);out.writeVarInt(a.han());out.writeVarInt(a.yakumanUnits());});
            buf.writeVarInt(v.han());buf.writeVarInt(v.fu());buf.writeVarInt(v.yakumanUnits());buf.writeVarInt(v.dora());
            buf.writeVarInt(v.aka());buf.writeVarInt(v.ura());buf.writeVarInt(v.totalPoints());buf.writeVarInt(v.kita());
        });
        writeList(b,r.transfers(),(buf,p)->{buf.writeVarInt(p.fromSeat());buf.writeVarInt(p.toSeat());buf.writeVarInt(p.points());buf.writeVarInt(p.kind());});
        b.writeBoolean(r.dealerContinues());
        writeTiles(b,r.uraIndicators());
    }
    private static Result readResult(RegistryFriendlyByteBuf b) {
        int kind=readRange(b,0,7,"end kind"); List<Integer> winners=readTiles(b,4,"winners"), tenpai=readTiles(b,4,"tenpai");
        List<WinValue> values=readList(b,4,"winner values",buf->{
            int seat=readRange(buf,0,3,"winner"), tile=readRange(buf,0,135,"winning tile");
            List<Award> yaku=readList(buf,45,"yaku awards",in->new Award(in.readUtf(64),positive(in,"han"),positive(in,"yakuman")));
            return new WinValue(seat,tile,yaku,positive(buf,"han"),positive(buf,"fu"),positive(buf,"yakuman"),positive(buf,"dora"),
                    positive(buf,"aka"),positive(buf,"ura"),positive(buf,"total points"),readRange(buf,0,4,"kita"));
        });
        List<Payment> payments=readList(b,128,"payments",buf->new Payment(readRange(buf,-1,3,"payer"),readRange(buf,0,3,"payee"),positive(buf,"payment"),readRange(buf,0,4,"payment kind")));
        return new Result(kind,winners,tenpai,values,payments,b.readBoolean(),readTiles(b,5,"revealed ura indicators"));
    }
    private static int positive(RegistryFriendlyByteBuf b,String field) { return readRange(b,0,Integer.MAX_VALUE,field); }
    private static List<Integer> tiles(List<Integer> values,int max,String field) {
        List<Integer> copy=bounded(values,max,field);
        if(copy.stream().anyMatch(t->!range(t,0,135)) || new HashSet<>(copy).size()!=copy.size()) fail(field);
        return copy;
    }
    private static List<Integer> seatIndices(List<Integer> values) {
        List<Integer> copy=bounded(values,4,"seat indices");
        if(copy.stream().anyMatch(s->!range(s,0,3)) || new HashSet<>(copy).size()!=copy.size()) fail("seat indices");
        return copy;
    }
    private static <T> List<T> bounded(List<T> values,int max,String field) {
        List<T> copy=List.copyOf(values); if(copy.size()>max) fail(field); return copy;
    }
    private static <T> void writeList(RegistryFriendlyByteBuf b,List<T> values,BiConsumer<RegistryFriendlyByteBuf,T> write) {
        b.writeVarInt(values.size()); for(T value:values) write.accept(b,value);
    }
    private static <T> List<T> readList(RegistryFriendlyByteBuf b,int max,String field,Function<RegistryFriendlyByteBuf,T> read) {
        int count=readRange(b,0,max,field+" length"); List<T> values=new ArrayList<>(count);
        for(int i=0;i<count;i++) values.add(read.apply(b)); return List.copyOf(values);
    }
    private static void writeTiles(RegistryFriendlyByteBuf b,List<Integer> values) { writeList(b,values,(buf,t)->buf.writeVarInt(t)); }
    private static List<Integer> readTiles(RegistryFriendlyByteBuf b,int max,String field) { return readList(b,max,field,buf->readRange(buf,0,135,field+" tile")); }
    private static int readRange(RegistryFriendlyByteBuf b,int min,int max,String field) {
        int value=b.readVarInt(); if(!range(value,min,max)) throw new DecoderException("Invalid "+field+": "+value); return value;
    }
    private static boolean range(int value,int min,int max) { return value>=min && value<=max; }
    private static void sanmaTile(int tile) { if(!dev.riichicraft.rules.ThreePlayerTiles.contains(tile)) fail("removed sanma tile"); }
    private static void fail(String field) { throw new IllegalArgumentException("Invalid "+field); }
}
