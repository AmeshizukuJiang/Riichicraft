package dev.riichicraft.rules;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import static dev.riichicraft.rules.FourPlayerRound.Status;

/** Single-threaded match owner. Only the currently owned, actually completed round can advance the match. */
public final class FourPlayerMatch {
    public enum Phase { PLAYING, ROUND_RESULT, MATCH_RESULT }
    /**
     * Trusted server factory. Ownership transfers to this match; do not retain or mutate the returned round.
     * Generate public round UUIDs independently from secret shuffle seeds.
     */
    @FunctionalInterface public interface RoundFactory { FourPlayerRound create(RoundSetup setup); }
    public record RoundSetup(UUID matchId,List<UUID> players,int roundIndex,int dealerSeat,int roundWind,
                             int honba,List<Integer> points,int deposits) {
        public RoundSetup { players=List.copyOf(players); points=List.copyOf(points); }
    }
    public record Command(UUID matchId,UUID roundId,long revision) { }
    /** Stable across other players' votes; invalidated on a new absence or the next round. */
    public record ContinueId(UUID matchId,UUID roundId,long epoch) { }
    public record View(UUID matchId,MatchRules.Format format,Phase phase,int initialDealer,int roundIndex,long revision,
                       FourPlayerRound.View round,long continueEpoch,List<Boolean> confirmations,Optional<MatchRules.FinalResult> result) {
        public View { confirmations=List.copyOf(confirmations); }
        public Command command() { return new Command(matchId,round.roundId(),revision); }
        public ContinueId continueId() { return new ContinueId(matchId,round.roundId(),continueEpoch); }
    }

    private final UUID matchId;
    private final List<UUID> players;
    private final MatchRules.Format format;
    private final int initialDealer;
    private final RoundFactory factory;
    private final Set<UUID> usedRoundIds=new HashSet<>();
    private final Set<Integer> confirmations=new HashSet<>();
    private FourPlayerRound round;
    private int roundIndex;
    private long revision, continueEpoch;
    private Phase phase=Phase.PLAYING;
    private MatchRules.NextRound next;
    private MatchRules.FinalResult result;

    public FourPlayerMatch(UUID matchId,List<UUID> players,MatchRules.Format format,int initialDealer,RoundFactory factory) {
        this.matchId=Objects.requireNonNull(matchId,"matchId"); this.players=List.copyOf(players);
        this.format=Objects.requireNonNull(format,"format"); this.factory=Objects.requireNonNull(factory,"factory");
        if(this.players.size()!=4 || new HashSet<>(players).size()!=4 || initialDealer<0 || initialDealer>3) {
            throw new IllegalArgumentException("Four distinct players and an initial dealer required");
        }
        this.initialDealer=initialDealer;
        round=createRound(new MatchRules.NextRound(0,0,List.of(25_000,25_000,25_000,25_000),0));
        usedRoundIds.add(round.view(null).roundId());
    }

    /** Restore an isolated owner without creating/dealing a replacement round or paying any account twice. */
    private FourPlayerMatch(GameArchiveCodec.MatchArchive archive,RoundFactory factory) {
        matchId=archive.matchId(); format=archive.format(); initialDealer=archive.initialDealer(); this.factory=Objects.requireNonNull(factory,"factory");
        players=archive.round().start().players(); roundIndex=archive.roundIndex(); revision=archive.revision(); continueEpoch=archive.continueEpoch();
        round=FourPlayerRound.restore(archive.round()); var actual=round.view(null); var ledger=round.serverState().ledger();
        // Validate outer metadata independently of the replayed current-round receipt.
        new MatchRules.Context(format,initialDealer,roundIndex,ledger.honba(),ledger.points(),ledger.depositSticks());
        usedRoundIds.addAll(archive.usedRoundIds()); confirmations.addAll(archive.confirmations());
        if(revision<actual.revision() || continueEpoch<0 || continueEpoch>revision || usedRoundIds.isEmpty()
                || usedRoundIds.size()!=archive.usedRoundIds().size() || !usedRoundIds.contains(actual.roundId())
                || (long)usedRoundIds.size()-1>revision || roundIndex>usedRoundIds.size()-1
                || confirmations.size()!=archive.confirmations().size() || confirmations.size()>3
                || confirmations.stream().anyMatch(seat->seat<0 || seat>3)
                || actual.dealerSeat()!=(initialDealer+roundIndex%4)%4 || actual.roundWind()!=27+roundIndex/4
                || archive.round().start().points().stream().anyMatch(point->point<0)) {
            throw new IllegalArgumentException("Invalid saved match metadata");
        }
        if(usedRoundIds.size()==1 && (roundIndex!=0 || archive.round().start().honba()!=0 || archive.round().start().deposits()!=0
                || !archive.round().start().points().equals(List.of(25_000,25_000,25_000,25_000)))) {
            throw new IllegalArgumentException("Initial match round must use initial accounts");
        }
        if(actual.phase()==FourPlayerRound.Phase.FINISHED) {
            var decision=MatchRules.afterRound(new MatchRules.Context(format,initialDealer,roundIndex,ledger.honba(),ledger.points(),ledger.depositSticks()),actual.end().orElseThrow());
            if(decision.finished()) { result=decision.result().orElseThrow(); phase=Phase.MATCH_RESULT; }
            else { next=decision.next().orElseThrow(); phase=Phase.ROUND_RESULT; }
        } else phase=Phase.PLAYING;
        if(phase!=archive.phase() || phase!=Phase.ROUND_RESULT && !confirmations.isEmpty()
                || phase==Phase.ROUND_RESULT && (continueEpoch==0 || revision-actual.revision()<confirmations.size())
                || !java.security.MessageDigest.isEqual(archiveFingerprint(),archive.fingerprint())) {
            throw new IllegalArgumentException("Saved match continuation/result does not match its receipt");
        }
    }

    static FourPlayerMatch restore(GameArchiveCodec.MatchArchive archive,RoundFactory factory) { return new FourPlayerMatch(archive,factory); }

    /** Read-only metadata from the current round's accepted public declarations. */
    public int addedKanTile(int seat, int meldIndex) { return round.addedKanTile(seat, meldIndex); }

    /** Current round only: earlier rounds contribute identity/progression metadata, not unbounded action history. */
    GameArchiveCodec.MatchArchive archive() {
        return new GameArchiveCodec.MatchArchive(matchId,format,initialDealer,roundIndex,revision,continueEpoch,phase,
                usedRoundIds.stream().sorted().toList(),confirmations.stream().sorted().toList(),round.archive(),archiveFingerprint());
    }

    private byte[] archiveFingerprint() {
        return GameArchiveCodec.fingerprint(List.of(matchId.toString(),players.toString(),format.name(),
                initialDealer+":"+roundIndex+":"+revision+":"+continueEpoch+":"+phase,
                usedRoundIds.stream().sorted().toList().toString(),confirmations.stream().sorted().toList().toString(),
                String.valueOf(next),String.valueOf(result)));
    }

    public View view(UUID recipient) {
        List<Boolean> votes=new ArrayList<>(); for(int seat=0;seat<4;seat++) votes.add(confirmations.contains(seat));
        return new View(matchId,format,phase,initialDealer,roundIndex,revision,round.view(recipient),continueEpoch,votes,Optional.ofNullable(result));
    }

    /** Contains all current round secrets; never serialize this as a player view. */
    public FourPlayerRound.ServerState serverState() { return round.serverState(); }

    public Status discard(UUID player,Command command,int tile,boolean riichi) {
        Status check=checkAction(player,command); if(check!=Status.ACCEPTED) return check;
        return afterAction(round.discard(player,round.view(player).command(),tile,riichi));
    }
    public Status tsumo(UUID player,Command command) {
        Status check=checkAction(player,command); if(check!=Status.ACCEPTED) return check;
        return afterAction(round.tsumo(player,round.view(player).command()));
    }
    public Status nineTerminals(UUID player,Command command) {
        Status check=checkAction(player,command); if(check!=Status.ACCEPTED) return check;
        return afterAction(round.nineTerminals(player,round.view(player).command()));
    }
    public Status declareKan(UUID player,Command command,KanActions.Choice choice) {
        Status check=checkAction(player,command); if(check!=Status.ACCEPTED) return check;
        return afterAction(round.declareKan(player,round.view(player).command(),choice));
    }
    public Status respond(UUID player,DiscardResponseWindow.Id id,DiscardResponseWindow.Choice choice) {
        if(seat(player)<0) return Status.NOT_A_PLAYER;
        if(id==null || !round.view(null).roundId().equals(id.roundId())) return Status.WRONG_ROUND;
        if(phase!=Phase.PLAYING) return Status.WRONG_PHASE;
        return afterAction(round.respond(player,id,choice));
    }

    /** Four real participants vote for this exact completed result. Other votes never invalidate its token. */
    public Status confirmNext(UUID player,ContinueId id) {
        int seat=seat(player); if(seat<0) return Status.NOT_A_PLAYER;
        if(id==null || !matchId.equals(id.matchId()) || !round.view(null).roundId().equals(id.roundId())) return Status.WRONG_ROUND;
        if(phase!=Phase.ROUND_RESULT) return Status.WRONG_PHASE;
        if(id.epoch()!=continueEpoch) return Status.WRONG_WINDOW;
        if(confirmations.contains(seat)) return Status.ALREADY_RESPONDED;
        if(confirmations.size()==3) {
            // Prepare and validate before clearing the paid result or changing any accepted votes.
            FourPlayerRound prepared=createRound(next);
            round=prepared; usedRoundIds.add(prepared.view(null).roundId()); roundIndex=next.roundIndex();
            next=null; confirmations.clear(); phase=Phase.PLAYING;
        } else confirmations.add(seat);
        revision=Math.incrementExact(revision);
        return Status.ACCEPTED;
    }

    /** Call for a real newly missing participant during intermission, including when no vote has arrived yet. */
    public boolean clearNextConfirmations() {
        if(phase!=Phase.ROUND_RESULT) return false;
        confirmations.clear(); continueEpoch=Math.incrementExact(continueEpoch); revision=Math.incrementExact(revision);
        return true;
    }

    private Status checkAction(UUID player,Command command) {
        if(seat(player)<0) return Status.NOT_A_PLAYER;
        if(command==null || !matchId.equals(command.matchId()) || !round.view(null).roundId().equals(command.roundId())) return Status.WRONG_ROUND;
        if(command.revision()!=revision) return Status.STALE_REVISION;
        if(phase!=Phase.PLAYING) return Status.WRONG_PHASE;
        return Status.ACCEPTED;
    }
    private Status afterAction(Status status) {
        if(status!=Status.ACCEPTED) return status;
        var view=round.view(null);
        if(view.phase()==FourPlayerRound.Phase.FINISHED) {
            RoundLedger ledger=round.serverState().ledger();
            if(!ledger.finished()) throw new IllegalStateException("Cannot progress an unclosed ledger");
            var context=new MatchRules.Context(format,initialDealer,roundIndex,ledger.honba(),ledger.points(),ledger.depositSticks());
            var decision=MatchRules.afterRound(context,view.end().orElseThrow());
            if(decision.finished()) { result=decision.result().orElseThrow(); phase=Phase.MATCH_RESULT; }
            else { next=decision.next().orElseThrow(); phase=Phase.ROUND_RESULT; continueEpoch=Math.incrementExact(continueEpoch); }
        }
        revision=Math.incrementExact(revision);
        return Status.ACCEPTED;
    }
    private FourPlayerRound createRound(MatchRules.NextRound configuration) {
        int index=configuration.roundIndex(), dealer=(initialDealer+index%4)%4, wind=27+index/4;
        FourPlayerRound prepared=Objects.requireNonNull(factory.create(new RoundSetup(matchId,players,index,dealer,wind,
                configuration.honba(),configuration.points(),configuration.deposits())),"round factory result");
        var actual=prepared.view(null); var ledger=prepared.serverState().ledger();
        if(usedRoundIds.contains(actual.roundId()) || actual.phase()!=FourPlayerRound.Phase.DRAWN_TURN || actual.actorSeat()!=dealer
                || actual.liveTiles()!=69 || actual.dealerSeat()!=dealer || actual.roundWind()!=wind || actual.honba()!=configuration.honba()
                || ledger.finished() || !ledger.points().equals(configuration.points()) || ledger.depositSticks()!=configuration.deposits()
                || !actual.seats().stream().map(FourPlayerRound.PublicSeat::playerId).toList().equals(players)
                || actual.seats().stream().anyMatch(s->!s.river().isEmpty() || !s.melds().isEmpty())) {
            throw new IllegalArgumentException("Factory must create a fresh round with exactly the owned match configuration");
        }
        prepared.assertConservation();
        return prepared;
    }
    private int seat(UUID player) { return player==null?-1:players.indexOf(player); }
}
