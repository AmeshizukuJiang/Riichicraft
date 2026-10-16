package dev.riichicraft.rules;

import dev.riichicraft.game.TileCatalog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import static dev.riichicraft.rules.DiscardResponseWindow.*;

/** Single-threaded, server-only robbery collection; no tiles, indicators or replacement draws move while pending. */
public final class KanResponseWindow {
    public record Participant(int seat,HandInput hand,WinContext context,FuritenState history) {
        public Participant {
            requireSeat(seat);
            Objects.requireNonNull(hand,"hand"); Objects.requireNonNull(context,"context"); Objects.requireNonNull(history,"history");
            if (!hand.isWaiting()) throw new IllegalArgumentException("A kan responder needs structural thirteen");
        }
    }
    public record Resolution(Id id,List<Integer> ronSeats,Map<Integer,ScoreResult> ronValues,
                             Map<Integer,FuritenState> historiesAfter,HandInput declarerHand) {
        public Resolution {
            ronSeats=List.copyOf(ronSeats); ronValues=Map.copyOf(ronValues); historiesAfter=Map.copyOf(historiesAfter);
            Objects.requireNonNull(id,"id"); Objects.requireNonNull(declarerHand,"declarerHand");
        }
        public boolean robbed() { return !ronSeats.isEmpty(); }
    }

    private final Id id;
    private final KanActions.Proposal proposal;
    private final List<Participant> participants;
    private final Map<Integer,Set<Simple>> offers=new HashMap<>();
    private final Map<Integer,WinEvaluator.Decision> decisions=new HashMap<>();
    private final Map<Integer,Simple> responses=new HashMap<>();
    private Resolution resolution;

    public KanResponseWindow(Id id,KanActions.Proposal proposal,List<Participant> participants) {
        this.id=Objects.requireNonNull(id,"id"); this.proposal=Objects.requireNonNull(proposal,"proposal");
        this.participants=List.copyOf(participants).stream().sorted(Comparator.comparingInt(p->distance(proposal.declarerSeat(),p.seat()))).toList();
        validate();
        for (Participant p:this.participants) {
            var held=new ArrayList<>(p.hand().concealedTiles()); held.add(proposal.claimableTileId());
            var completed=new HandInput(held,p.hand().melds());
            var decision=WinEvaluator.evaluate(completed,p.context(),p.history(),proposal.source());
            decisions.put(p.seat(),decision);
            Set<Simple> choices=decision.allowed()?Set.of(Simple.PASS,Simple.RON):Set.of(Simple.PASS);
            offers.put(p.seat(),choices);
            if (!decision.allowed()) responses.put(p.seat(),Simple.PASS);
        }
        resolveWhenComplete();
    }

    public Id id() { return id; }
    public KanActions.Proposal proposal() { return proposal; }
    public Set<Simple> offers(int seat) { requireResponder(seat); return offers.get(seat); }
    public WinEvaluator.Decision ronDecision(int seat) { requireResponder(seat); return decisions.get(seat); }
    public Map<Integer,Simple> responses() { return Map.copyOf(responses); }
    public Optional<Resolution> resolution() { return Optional.ofNullable(resolution); }
    public Set<Integer> pendingSeats() { var result=new HashSet<>(offers.keySet()); result.removeAll(responses.keySet()); return Set.copyOf(result); }

    public Submission submit(Id expectedId,int seat,Choice choice) {
        if (!id.equals(expectedId)) return Submission.WRONG_WINDOW;
        if (resolution!=null) return Submission.CLOSED;
        if (!offers.containsKey(seat)) return Submission.NOT_A_RESPONDER;
        if (responses.containsKey(seat)) return Submission.ALREADY_RESPONDED;
        if (!(choice instanceof Simple simple) || !offers.get(seat).contains(simple)) return Submission.NOT_OFFERED;
        responses.put(seat,simple); resolveWhenComplete(); return Submission.ACCEPTED;
    }

    private void validate() {
        if (participants.size()!=3) throw new IllegalArgumentException("Three non-declarer snapshots are required");
        boolean[] occupied=new boolean[136];
        addHand(occupied,proposal.handBefore());
        WinContext common=participants.getFirst().context();
        common.doraIndicators().forEach(tile->addTile(occupied,tile)); common.uraIndicators().forEach(tile->addTile(occupied,tile));
        Set<Integer> seats=new HashSet<>(),winds=new HashSet<>();
        int kanType=proposal.claimableTileId()/4;
        long kans=proposal.handBefore().melds().stream().filter(m->m.tiles().size()==4).count();
        for (Participant p:participants) {
            var c=p.context();
            if (p.seat()==proposal.declarerSeat() || !seats.add(p.seat()) || !winds.add(c.seatWind())
                    || c.tsumo() || c.winningTileId()!=proposal.claimableTileId() || c.event()!=WinContext.WinEvent.CHANKAN
                    || c.roundWind()!=common.roundWind() || !c.doraIndicators().equals(common.doraIndicators())
                    || !c.uraIndicators().equals(common.uraIndicators())) {
                throw new IllegalArgumentException("Responders must describe this same kan opportunity from three different seats/winds");
            }
            if (p.hand().tileCounts()[kanType]!=0) throw new IllegalArgumentException("The declarer owns all four copies of the kan type");
            addHand(occupied,p.hand());
            kans+=p.hand().melds().stream().filter(m->m.tiles().size()==4).count();
        }
        if (kans!=proposal.context().establishedKans()) throw new IllegalArgumentException("The four snapshots do not match the global established-kan count");
    }

    private void resolveWhenComplete() {
        if (responses.size()!=3) return;
        List<Integer> winners=new ArrayList<>(); Map<Integer,ScoreResult> values=new LinkedHashMap<>(); Map<Integer,FuritenState> histories=new LinkedHashMap<>();
        for (Participant p:participants) {
            if (responses.get(p.seat())==Simple.RON) {
                winners.add(p.seat()); values.put(p.seat(),decisions.get(p.seat()).handValue().orElseThrow()); histories.put(p.seat(),p.history());
            } else histories.put(p.seat(),FuritenTracker.afterKanWithoutRon(p.hand(),proposal.claimableTileId(),p.history(),p.context().riichi(),proposal.source()));
        }
        resolution=new Resolution(id,winners,values,histories,winners.isEmpty()?proposal.handIfEstablished():proposal.handIfRobbed());
    }

    private void requireResponder(int seat) { if (!offers.containsKey(seat)) throw new IllegalArgumentException("Not a responder"); }
    private static int distance(int from,int to) { return (to-from+4)%4; }
    private static void requireSeat(int seat) { if (seat<0 || seat>3) throw new IllegalArgumentException("Seat must be in 0..3"); }
    private static void addHand(boolean[] occupied,HandInput hand) { hand.concealedTiles().forEach(t->addTile(occupied,t)); hand.melds().forEach(m->m.tiles().forEach(t->addTile(occupied,t))); }
    private static void addTile(boolean[] occupied,int tile) { TileCatalog.typeOf(tile); if (occupied[tile]) throw new IllegalArgumentException("A physical tile has two owners in this kan snapshot"); occupied[tile]=true; }
}
