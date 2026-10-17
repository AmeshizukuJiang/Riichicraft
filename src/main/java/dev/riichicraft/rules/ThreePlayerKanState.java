package dev.riichicraft.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Kan ownership, deferred indicators and the exact fourth-kan discard gate; not a complete round engine. */
public final class ThreePlayerKanState {
    public enum FourthStage { NONE, NEEDS_DISCARD, RESPONSES, ABORTED, WON }
    private final UUID roundId;
    private final List<Integer> kansBySeat;
    private final int revealedIndicators;
    private final int delayedSeat;
    private final int replacementSeat;
    private final int fourthSeat;
    private final FourthStage fourthStage;
    private final DiscardResponseWindow.Id fourthWindow;

    private ThreePlayerKanState(UUID roundId,List<Integer> kans,int revealed,int delayedSeat,int replacementSeat,int fourthSeat,
                          FourthStage stage,DiscardResponseWindow.Id fourthWindow) {
        this.roundId=roundId; kansBySeat=List.copyOf(kans); revealedIndicators=revealed;
        this.delayedSeat=delayedSeat; this.replacementSeat=replacementSeat; this.fourthSeat=fourthSeat; fourthStage=stage; this.fourthWindow=fourthWindow;
    }

    public static ThreePlayerKanState initial(UUID roundId) {
        return new ThreePlayerKanState(Objects.requireNonNull(roundId,"roundId"),List.of(0,0,0),1,-1,-1,-1,FourthStage.NONE,null);
    }
    public List<Integer> kansBySeat() { return kansBySeat; }
    public int establishedKans() { return kansBySeat.stream().mapToInt(Integer::intValue).sum(); }
    public int revealedIndicators() { return revealedIndicators; }
    public boolean delayedIndicatorPending() { return delayedSeat>=0; }
    public FourthStage fourthStage() { return fourthStage; }
    public Optional<DiscardResponseWindow.Id> fourthWindow() { return Optional.ofNullable(fourthWindow); }

    /** Validate the actual new kan choice first; then reveal the previous deferred indicator before declaring it. */
    public ThreePlayerKanState beforeSelfKanDeclaration(int seat) {
        requireSeat(seat); requireActive();
        if (establishedKans()==4 || fourthStage!=FourthStage.NONE) throw new IllegalStateException("No further kan declaration is available");
        if (replacementSeat>=0 && replacementSeat!=seat) throw new IllegalArgumentException("Only the replacement-draw actor can declare a consecutive kan");
        return delayedSeat<0?this:new ThreePlayerKanState(roundId,kansBySeat,revealedIndicators+1,-1,replacementSeat,-1,FourthStage.NONE,null);
    }

    /** Actual survived kan only. OPEN_KAN includes kakan; do not call this for a robbed proposal. */
    public ThreePlayerKanState afterEstablished(int seat,Meld.Kind kind) {
        requireSeat(seat); requireActive();
        if (kind!=Meld.Kind.CLOSED_KAN && kind!=Meld.Kind.OPEN_KAN) throw new IllegalArgumentException("Not an established kan");
        if (establishedKans()==4 || fourthStage!=FourthStage.NONE || delayedSeat>=0) {
            throw new IllegalStateException("Resolve a prior deferred indicator and fourth-kan gate before another kan");
        }
        if (replacementSeat>=0 && replacementSeat!=seat) throw new IllegalArgumentException("The previous kan actor has not finished its replacement turn");
        var counts=new ArrayList<>(kansBySeat); counts.set(seat,counts.get(seat)+1);
        boolean fourth=establishedKans()==3 && counts.stream().filter(count->count>0).count()>1;
        return new ThreePlayerKanState(roundId,counts,revealedIndicators+(kind==Meld.Kind.CLOSED_KAN?1:0),
                kind==Meld.Kind.OPEN_KAN?seat:-1,seat,fourth?seat:-1,fourth?FourthStage.NEEDS_DISCARD:FourthStage.NONE,null);
    }

    /** After the real discard, before its ron contexts/scoring. The fourth discard is bound once. */
    public ThreePlayerKanState onDiscardOpening(int seat,DiscardResponseWindow.Id id) {
        requireSeat(seat); requireActive(); requireRound(id);
        if (fourthStage==FourthStage.RESPONSES) throw new IllegalStateException("Fourth-kan discard responses are pending");
        if (replacementSeat>=0 && replacementSeat!=seat || fourthStage==FourthStage.NEEDS_DISCARD && fourthSeat!=seat) {
            throw new IllegalArgumentException("The kan declarer must make this discard");
        }
        boolean fourth=fourthStage==FourthStage.NEEDS_DISCARD;
        return new ThreePlayerKanState(roundId,kansBySeat,revealedIndicators+(delayedSeat>=0?1:0),-1,-1,fourthSeat,
                fourth?FourthStage.RESPONSES:FourthStage.NONE,fourth?id:null);
    }

    /** Rinshan self-win has priority even for the fourth kan; this kan's deferred indicator stays hidden. */
    public ThreePlayerKanState afterRinshanWin() {
        requireActive();
        if (replacementSeat<0 || fourthStage==FourthStage.RESPONSES) throw new IllegalStateException("Not a replacement-draw win stage");
        return new ThreePlayerKanState(roundId,kansBySeat,revealedIndicators,delayedSeat,-1,fourthSeat,FourthStage.WON,null);
    }

    /** Only ron beats fourth-kan abort. Do not offer chi/pon/kan on this final response window. */
    public ThreePlayerKanState resolveFourthDiscard(ThreePlayerDiscardWindow window) {
        requireActive(); Objects.requireNonNull(window,"window");
        if (fourthStage!=FourthStage.RESPONSES) throw new IllegalStateException("No fourth-kan discard is awaiting responses");
        if (!fourthWindow.equals(window.id()) || window.discarderSeat()!=fourthSeat) throw new IllegalArgumentException("Wrong fourth-kan discard window");
        var result=window.resolution().orElseThrow(()->new IllegalStateException("Fourth-kan responses are incomplete"));
        return new ThreePlayerKanState(roundId,kansBySeat,revealedIndicators,-1,-1,fourthSeat,
                result.outcome()==DiscardResponseWindow.Outcome.RON?FourthStage.WON:FourthStage.ABORTED,fourthWindow);
    }

    private void requireActive() { if (fourthStage==FourthStage.ABORTED || fourthStage==FourthStage.WON) throw new IllegalStateException("The kan round gate is closed"); }
    private void requireRound(DiscardResponseWindow.Id id) { if (!roundId.equals(Objects.requireNonNull(id,"id").roundId())) throw new IllegalArgumentException("Wrong round"); }
    private static void requireSeat(int seat) { if (seat<0 || seat>2) throw new IllegalArgumentException("Seat must be in 0..2"); }
}
