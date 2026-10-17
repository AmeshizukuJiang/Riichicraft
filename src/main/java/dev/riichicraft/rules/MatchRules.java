package dev.riichicraft.rules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Pure match arithmetic and progression policy. Supplied round outcomes are trusted server receipts, not network authority. */
public final class MatchRules {
    public enum Format {
        EAST(3,7), SOUTH(7,11);
        private final int scheduledLast, maximumLast;
        Format(int scheduledLast,int maximumLast) { this.scheduledLast=scheduledLast; this.maximumLast=maximumLast; }
        public int scheduledLast() { return scheduledLast; }
        public int maximumLast() { return maximumLast; }
    }
    public enum Reason { NEGATIVE_SCORE, DEALER_WIN_STOP, DEALER_TENPAI_STOP, TARGET_REACHED, EXTENSION_LIMIT }
    /** Current round position, with scores/deposits AFTER its complete settlement. */
    public record Context(Format format,int initialDealer,int roundIndex,int honba,List<Integer> points,int deposits) {
        public Context {
            Objects.requireNonNull(format,"format"); requireSeat(initialDealer); points=List.copyOf(points);
            if(roundIndex<0 || roundIndex>format.maximumLast() || honba<0) throw new IllegalArgumentException("Invalid match position");
            requireAccounts(points,deposits);
        }
        public int dealerSeat() { return (initialDealer+roundIndex%4)%4; }
        public int roundWind() { return 27+roundIndex/4; }
    }
    public record NextRound(int roundIndex,int honba,List<Integer> points,int deposits) {
        public NextRound { points=List.copyOf(points); if(roundIndex<0 || roundIndex>11 || honba<0) throw new IllegalArgumentException("Invalid next round"); requireAccounts(points,deposits); }
    }
    /** Scores in tenths of a point: one score point corresponds to 1000 table points. */
    public record Standing(int seat,int rank,int points,int rawTenths,int umaTenths,int totalTenths) { }
    public record FinalResult(Reason reason,List<Standing> standings,int depositRecipient,int depositPoints) {
        public FinalResult { Objects.requireNonNull(reason,"reason"); standings=List.copyOf(standings); }
    }
    public record Decision(Optional<NextRound> next,Optional<FinalResult> result) {
        public Decision {
            Objects.requireNonNull(next,"next"); Objects.requireNonNull(result,"result");
            if(next.isPresent()==result.isPresent()) throw new IllegalArgumentException("Exactly one continuation or final result required");
        }
        public boolean finished() { return result.isPresent(); }
    }
    private MatchRules() { }

    /** Award remaining deposits once to the leader chosen BEFORE the award, then rank by fixed initial seat order. */
    public static FinalResult finalStandings(List<Integer> points,int deposits,int initialDealer,Reason reason) {
        points=List.copyOf(points); requireAccounts(points,deposits); requireSeat(initialDealer);
        List<Integer> order=rankOrder(points,initialDealer); int recipient=order.getFirst();
        int award=Math.multiplyExact(deposits,1_000);
        List<Integer> finalPoints=new ArrayList<>(points); finalPoints.set(recipient,Math.addExact(finalPoints.get(recipient),award));
        int[] uma={150,50,-50,-150}; List<Standing> standings=new ArrayList<>();
        for(int rank=0;rank<4;rank++) {
            int seat=order.get(rank), point=finalPoints.get(seat);
            int raw=Math.toIntExact((point-25_000L)/100);
            standings.add(new Standing(seat,rank+1,point,raw,uma[rank],Math.addExact(raw,uma[rank])));
        }
        if(standings.stream().mapToLong(Standing::points).sum()!=100_000L
                || standings.stream().mapToLong(Standing::totalTenths).sum()!=0L) throw new IllegalStateException("Final accounts do not conserve points");
        return new FinalResult(reason,standings,award==0?-1:recipient,award);
    }

    private static List<Integer> rankOrder(List<Integer> points,int initialDealer) {
        return List.of(0,1,2,3).stream().sorted(Comparator.<Integer>comparingInt(points::get).reversed()
                .thenComparingInt(seat->(seat-initialDealer+4)%4)).toList();
    }
    private static void requireAccounts(List<Integer> points,int deposits) {
        if(deposits<0 || points.size()!=4 || points.stream().anyMatch(p->p%100!=0)
                || points.stream().mapToLong(Integer::longValue).sum()+deposits*1_000L!=100_000L) {
            throw new IllegalArgumentException("Four scores and the deposit pool must conserve 100000 in hundreds");
        }
    }
    private static void requireSeat(int seat) { if(seat<0 || seat>3) throw new IllegalArgumentException("Seat must be in 0..3"); }
}
