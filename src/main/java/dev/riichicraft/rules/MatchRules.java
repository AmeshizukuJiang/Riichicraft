package dev.riichicraft.rules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
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

    /** Apply a complete, already-paid round receipt; never check intermediate multi-winner payments. */
    public static Decision afterRound(Context context,FourPlayerRound.End end) {
        Objects.requireNonNull(context,"context"); Objects.requireNonNull(end,"end");
        validateEnd(context,end);
        if(context.points().stream().anyMatch(point->point<0)) return finish(context,Reason.NEGATIVE_SCORE);
        boolean win=end.kind()==FourPlayerRound.EndKind.RON || end.kind()==FourPlayerRound.EndKind.TSUMO;
        boolean abort=switch(end.kind()) {
            case NINE_TERMINALS,FOUR_WINDS,FOUR_RIICHI,FOUR_KANS->true;
            default->false;
        };
        int leader=rankOrder(context.points(),context.initialDealer()).getFirst();
        boolean target=context.points().get(leader)>=30_000;
        boolean last=context.roundIndex()==context.format().scheduledLast()
                || context.roundIndex()==context.format().maximumLast();
        if(!abort && last && end.dealerContinues() && leader==context.dealerSeat() && target) {
            return finish(context,win?Reason.DEALER_WIN_STOP:Reason.DEALER_TENPAI_STOP);
        }
        boolean dealerMultiRon=end.kind()==FourPlayerRound.EndKind.RON && end.winners().size()>1 && end.dealerContinues();
        if(context.roundIndex()>context.format().scheduledLast() && target && !dealerMultiRon) {
            return finish(context,Reason.TARGET_REACHED);
        }
        // No formal-tenpai stop for aborts. The extension target check above applies to every paid result.
        if(abort) return continueWith(context,context.roundIndex(),Math.incrementExact(context.honba()));
        if(last && !end.dealerContinues()) {
            if(target) return finish(context,Reason.TARGET_REACHED);
            if(context.roundIndex()==context.format().maximumLast()) return finish(context,Reason.EXTENSION_LIMIT);
        }
        int index=end.dealerContinues()?context.roundIndex():context.roundIndex()+1;
        int honba=win && !end.dealerContinues()?0:Math.incrementExact(context.honba());
        return continueWith(context,index,honba);
    }

    private static Decision finish(Context context,Reason reason) {
        return new Decision(Optional.empty(),Optional.of(finalStandings(context.points(),context.deposits(),context.initialDealer(),reason)));
    }
    private static Decision continueWith(Context context,int index,int honba) {
        return new Decision(Optional.of(new NextRound(index,honba,context.points(),context.deposits())),Optional.empty());
    }
    private static void validateEnd(Context context,FourPlayerRound.End end) {
        Objects.requireNonNull(end.kind(),"end kind");
        for(int seat:end.winners()) requireSeat(seat);
        for(int seat:end.tenpai()) requireSeat(seat);
        if(new HashSet<>(end.winners()).size()!=end.winners().size()) throw new IllegalArgumentException("Duplicate winner");
        boolean continues=switch(end.kind()) {
            case RON,TSUMO->{
                int count=end.winners().size();
                if(count<1 || count>(end.kind()==FourPlayerRound.EndKind.TSUMO?1:3) || !end.tenpai().isEmpty()) {
                    throw new IllegalArgumentException("Invalid win receipt");
                }
                yield end.winners().contains(context.dealerSeat());
            }
            case EXHAUSTIVE,NAGASHI->{
                if(end.kind()==FourPlayerRound.EndKind.EXHAUSTIVE?!end.winners().isEmpty():end.winners().isEmpty()) {
                    throw new IllegalArgumentException("Invalid exhaustive/nagashi receipt");
                }
                yield end.tenpai().contains(context.dealerSeat());
            }
            case NINE_TERMINALS,FOUR_WINDS,FOUR_RIICHI,FOUR_KANS->{
                if(!end.winners().isEmpty() || !end.tenpai().isEmpty()) throw new IllegalArgumentException("Invalid abortive receipt");
                yield true;
            }
        };
        if(continues!=end.dealerContinues()) throw new IllegalArgumentException("Dealer continuation contradicts the completed receipt");
    }

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
