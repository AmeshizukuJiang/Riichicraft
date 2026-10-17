package dev.riichicraft.rules;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import static dev.riichicraft.rules.MatchRules.*;

/** Independent fixed payer/ranking tables and match-policy boundaries; no Minecraft or test library. */
public final class MatchRulesTests {
    private static final int[][] INITIAL_ORDER={
            {0,1,2,3},{1,2,3,0},{2,3,0,1},{3,0,1,2}};
    private static final int[] UMA={150,50,-50,-150};
    private static final int[][] RANK_POINTS={
            {40_100,28_900,19_000,12_000},
            {60_100,28_900,19_000,-8_000}};
    // Hand-calculated score tenths for the two corresponding 25000-return tables.
    private static final int[][] RANK_RAW={
            {151,39,-60,-130},
            {351,39,-60,-330}};
    // All fifteen set partitions of four seats. Equal numbers denote tied groups;
    // smaller group numbers have strictly higher scores in the matching fixed table.
    private static final int[][] TIE_GROUPS={
            {0,0,0,0},{0,0,0,1},{0,0,1,0},{0,0,1,1},{0,0,1,2},
            {0,1,0,0},{0,1,0,1},{0,1,0,2},{0,1,1,0},{0,1,1,1},
            {0,1,1,2},{0,1,2,0},{0,1,2,1},{0,1,2,2},{0,1,2,3}};
    private static final int[][] TIE_POINTS={
            {25_000,25_000,25_000,25_000},
            {25_100,25_100,25_100,24_700},
            {25_100,25_100,24_700,25_100},
            {25_200,25_200,24_800,24_800},
            {25_300,25_300,24_900,24_500},
            {25_100,24_700,25_100,25_100},
            {25_200,24_800,25_200,24_800},
            {25_300,24_900,25_300,24_500},
            {25_200,24_800,24_800,25_200},
            {25_300,24_900,24_900,24_900},
            {25_400,25_000,25_000,24_600},
            {25_300,24_900,24_500,25_300},
            {25_400,25_000,24_600,25_000},
            {25_500,25_100,24_700,24_700},
            {25_600,25_200,24_800,24_400}};
    private static int assertions;
    private MatchRulesTests() { }

    public static void main(String[] args) {
        long start=System.nanoTime();
        run("all twenty-four strict rankings, initial seats, deposits and negative scores",MatchRulesTests::strictStandings);
        run("all fifteen tie partitions, initial-seat priority and final-only deposits",MatchRulesTests::tiedStandings);
        run("precise tenths, preserved negative points and defensive final receipts",MatchRulesTests::exactAndImmutable);
        run("format positions, dealer-relative winds and immutable next-round accounts",MatchRulesTests::positions);
        run("invalid inputs, arithmetic limits and exclusive decision results",MatchRulesTests::invalid);
        System.out.printf("PASS match rules: %d assertions; %.3f s%n",assertions,(System.nanoTime()-start)/1_000_000_000.0);
    }

    private static void strictStandings() {
        List<int[]> permutations=permutations(); check(permutations.size()==24,"reference enumerates each strict order once");
        for (int[] order : permutations) for (int initial=0;initial<4;initial++) for (int deposits : List.of(0,1,4)) {
            for (int table=0;table<RANK_POINTS.length;table++) {
                var input=new ArrayList<>(List.of(0,0,0,0));
                for (int rank=0;rank<4;rank++) input.set(order[rank],RANK_POINTS[table][rank]-(rank==3?deposits*1_000:0));
                var original=List.copyOf(input);
                var result=MatchRules.finalStandings(input,deposits,initial,Reason.TARGET_REACHED);
                check(result.reason()==Reason.TARGET_REACHED,"final reason retained without being inferred again from scores");
                check(result.depositRecipient()==(deposits==0?-1:order[0]) && result.depositPoints()==deposits*1_000,"only the already-ranked leader receives the whole existing pool");
                for (int rank=0;rank<4;rank++) {
                    var standing=result.standings().get(rank);
                    int delta=rank==0?deposits*1_000:rank==3?-deposits*1_000:0;
                    int rawDelta=rank==0?deposits*10:rank==3?-deposits*10:0;
                    check(standing.seat()==order[rank] && standing.rank()==rank+1,"strict score order overrides every initial-seat order");
                    check(standing.points()==RANK_POINTS[table][rank]+delta,"each ranked player keeps its points and only the leader receives final deposits");
                    check(standing.rawTenths()==RANK_RAW[table][rank]+rawDelta,"hard-coded 25000-return raw score table matches exactly");
                    check(standing.umaTenths()==UMA[rank] && standing.totalTenths()==RANK_RAW[table][rank]+rawDelta+UMA[rank],"hard-coded 15/5/-5/-15 uma table is added in exact tenths");
                }
                check(input.equals(original),"ranking does not mutate the supplied player ledger");
                auditFinal(result);
            }
        }
    }

    private static void tiedStandings() {
        Set<String> unique=new HashSet<>();
        for (int partition=0;partition<TIE_GROUPS.length;partition++) {
            check(unique.add(java.util.Arrays.toString(TIE_GROUPS[partition])),"fifteen distinct tie partitions");
            for (int initial=0;initial<4;initial++) for (int deposits : List.of(0,4,8)) {
                // Equal subtraction preserves all ties while charging the complete pool.
                int perSeat=deposits*250;
                var input=new ArrayList<Integer>(); for (int point : TIE_POINTS[partition]) input.add(point-perSeat);
                List<Integer> expectedOrder=new ArrayList<>();
                for (int group=0;group<4;group++) for (int seat : INITIAL_ORDER[initial]) {
                    if (TIE_GROUPS[partition][seat]==group) expectedOrder.add(seat);
                }
                check(expectedOrder.size()==4,"independent grouped order includes each seat");
                var result=MatchRules.finalStandings(input,deposits,initial,Reason.EXTENSION_LIMIT);
                check(result.depositRecipient()==(deposits==0?-1:expectedOrder.getFirst()),"top-score tie is broken by original east/south/west/north before awarding deposits");
                for (int rank=0;rank<4;rank++) {
                    int seat=expectedOrder.get(rank), expectedPoint=TIE_POINTS[partition][seat]-perSeat+(rank==0?deposits*1_000:0);
                    var standing=result.standings().get(rank);
                    check(standing.seat()==seat && standing.rank()==rank+1,"ties at every rank follow fixed initial-seat order, never current dealer");
                    check(standing.points()==expectedPoint,"final deposits cannot be split between tied leaders");
                    // Inputs and expected table are whole hundreds. This uses decimal scaling,
                    // rather than the implementation's point-minus-return expression.
                    int expectedRaw=expectedPoint/100-250;
                    check(standing.rawTenths()==expectedRaw && standing.totalTenths()==expectedRaw+UMA[rank],"tie settlement retains exact one-tenth precision and rank-specific uma");
                }
                auditFinal(result);
            }
        }
    }

    private static void exactAndImmutable() {
        var exact=MatchRules.finalStandings(List.of(40_100,28_900,19_000,12_000),0,0,Reason.TARGET_REACHED);
        check(exact.standings().stream().map(Standing::rawTenths).toList().equals(List.of(151,39,-60,-130)),"raw results are exactly 15.1, 3.9, -6.0 and -13.0");
        check(exact.standings().stream().map(Standing::totalTenths).toList().equals(List.of(301,89,-110,-280)),"final results are exactly 30.1, 8.9, -11.0 and -28.0");
        var negative=MatchRules.finalStandings(List.of(60_100,28_900,19_000,-8_000),0,3,Reason.NEGATIVE_SCORE);
        check(negative.standings().getLast().points()==-8_000 && negative.standings().getLast().totalTenths()==-480,"bankruptcy retains the actual negative score without floor or truncation");
        var tied=MatchRules.finalStandings(List.of(24_000,24_000,24_000,24_000),4,2,Reason.EXTENSION_LIMIT);
        check(tied.standings().stream().map(Standing::seat).toList().equals(List.of(2,3,0,1)),"four-way tie retains original seat priority after final leader receives sticks");
        check(tied.standings().stream().map(Standing::points).toList().equals(List.of(28_000,24_000,24_000,24_000)),"first place receives all four sticks exactly once");
        check(tied.standings().stream().map(Standing::totalTenths).toList().equals(List.of(180,40,-60,-160)),"four-way tied original scores still produce distinct uma by original priority");

        var mutable=new ArrayList<>(List.of(25_000,25_000,25_000,25_000));
        var copied=MatchRules.finalStandings(mutable,0,1,Reason.DEALER_TENPAI_STOP);
        mutable.set(1,0);
        check(copied.standings().getFirst().points()==25_000,"final standings never retain mutable caller point storage");
        expect(UnsupportedOperationException.class,()->copied.standings().clear(),"final ranking list immutable");
        for (Reason reason : Reason.values()) check(MatchRules.finalStandings(List.of(25_000,25_000,25_000,25_000),0,0,reason).reason()==reason,"all explicit end reasons are retained");

        var largest=MatchRules.finalStandings(List.of(2_147_483_600,-2_147_483_600,50_000,50_000),0,0,Reason.NEGATIVE_SCORE);
        check(largest.standings().getFirst().rawTenths()==21_474_586 && largest.standings().getLast().rawTenths()==-21_475_086,"subtracting return points is widened before arithmetic at integer extremes");
        auditFinal(largest);
    }

    private static void positions() {
        check(Format.EAST.scheduledLast()==3 && Format.EAST.maximumLast()==7,"east match schedules east four and caps its extension at south four");
        check(Format.SOUTH.scheduledLast()==7 && Format.SOUTH.maximumLast()==11,"south match schedules south four and caps its extension at west four");
        for (Format format : Format.values()) for (int initial=0;initial<4;initial++) {
            for (int index=0;index<=format.maximumLast();index++) {
                var context=new Context(format,initial,index,3,List.of(24_000,25_000,25_000,25_000),1);
                check(context.dealerSeat()==INITIAL_ORDER[initial][index%4],"dealer advances relative to initial east rather than assuming physical seat zero");
                int wind=index<4?27:index<8?28:29;
                check(context.roundWind()==wind,"round wind follows hand index, including after physical seat wraparound");
                check(context.points().equals(List.of(24_000,25_000,25_000,25_000)) && context.deposits()==1,"context carries already-settled player money and separate pool");
            }
        }
        var points=new ArrayList<>(List.of(24_000,25_000,25_000,25_000));
        var context=new Context(Format.EAST,2,1,3,points,1); var next=new NextRound(2,4,points,1);
        points.set(0,0);
        check(context.points().getFirst()==24_000 && next.points().getFirst()==24_000,"current and next-round configuration copy their input accounts");
        expect(UnsupportedOperationException.class,()->context.points().clear(),"current context points immutable");
        expect(UnsupportedOperationException.class,()->next.points().clear(),"next-round points immutable");
        var nextDecision=new Decision(Optional.of(next),Optional.empty());
        var finalResult=MatchRules.finalStandings(List.of(25_000,25_000,25_000,25_000),0,0,Reason.EXTENSION_LIMIT);
        var finalDecision=new Decision(Optional.empty(),Optional.of(finalResult));
        check(!nextDecision.finished() && nextDecision.next().orElseThrow().equals(next),"continuing decision exposes exactly next-round metadata");
        check(finalDecision.finished() && finalDecision.result().orElseThrow().equals(finalResult),"finished decision exposes exactly one immutable final result");
    }

    private static void invalid() {
        List<Integer> normal=List.of(25_000,25_000,25_000,25_000);
        for (List<Integer> bad : List.of(List.<Integer>of(),List.of(25_000,25_000,25_000),List.of(25_000,25_000,25_000,25_000,0),
                List.of(25_000,25_000,25_000,24_900),List.of(25_050,24_950,25_000,25_000))) {
            expect(IllegalArgumentException.class,()->MatchRules.finalStandings(bad,0,0,Reason.TARGET_REACHED),"invalid final accounts rejected");
            expect(IllegalArgumentException.class,()->new Context(Format.EAST,0,0,0,bad,0),"invalid current accounts rejected");
            expect(IllegalArgumentException.class,()->new NextRound(0,0,bad,0),"invalid next accounts rejected");
        }
        for (int initial : List.of(-1,4)) {
            expect(IllegalArgumentException.class,()->MatchRules.finalStandings(normal,0,initial,Reason.TARGET_REACHED),"invalid initial seat rejected at final settlement");
            expect(IllegalArgumentException.class,()->new Context(Format.EAST,initial,0,0,normal,0),"invalid initial seat rejected in context");
        }
        for (int deposits : List.of(-1,1,Integer.MAX_VALUE)) {
            expect(IllegalArgumentException.class,()->MatchRules.finalStandings(normal,deposits,0,Reason.TARGET_REACHED),"negative or unbacked deposit pool rejected");
            expect(IllegalArgumentException.class,()->new Context(Format.EAST,0,0,0,normal,deposits),"context rejects a created or negative pool");
            expect(IllegalArgumentException.class,()->new NextRound(0,0,normal,deposits),"next-round configuration rejects unbacked pool");
        }
        expect(NullPointerException.class,()->MatchRules.finalStandings(null,0,0,Reason.TARGET_REACHED),"null final accounts rejected");
        expect(NullPointerException.class,()->MatchRules.finalStandings(normal,0,0,null),"null finish reason rejected");
        expect(NullPointerException.class,()->new Context(null,0,0,0,normal,0),"null match format rejected");
        expect(NullPointerException.class,()->new Context(Format.EAST,0,0,0,null,0),"null current point list rejected");
        expect(NullPointerException.class,()->new NextRound(0,0,null,0),"null next point list rejected");
        var nullElement=new ArrayList<>(normal); nullElement.set(2,null);
        expect(NullPointerException.class,()->MatchRules.finalStandings(nullElement,0,0,Reason.TARGET_REACHED),"null final score rejected");
        for (Format format : Format.values()) for (int index : List.of(-1,format.maximumLast()+1)) {
            expect(IllegalArgumentException.class,()->new Context(format,0,index,0,normal,0),"current hand cannot precede start or exceed that format's final extension hand");
        }
        expect(IllegalArgumentException.class,()->new Context(Format.EAST,0,0,-1,normal,0),"negative current honba rejected");
        expect(IllegalArgumentException.class,()->new NextRound(0,-1,normal,0),"negative next honba rejected");
        expect(IllegalArgumentException.class,()->new NextRound(-1,0,normal,0),"negative next index rejected");
        expect(IllegalArgumentException.class,()->new NextRound(12,0,normal,0),"next hand cannot enter north round");
        var next=new NextRound(0,0,normal,0);
        var result=MatchRules.finalStandings(normal,0,0,Reason.EXTENSION_LIMIT);
        expect(IllegalArgumentException.class,()->new Decision(Optional.empty(),Optional.empty()),"decision cannot omit both next-round and final result");
        expect(IllegalArgumentException.class,()->new Decision(Optional.of(next),Optional.of(result)),"decision cannot both continue and finish");
        expect(NullPointerException.class,()->new Decision(null,Optional.empty()),"null continuation optional rejected");
        expect(NullPointerException.class,()->new Decision(Optional.empty(),null),"null result optional rejected");
        var overflow=new ArrayList<>(List.of(2_147_483_600,-2_147_483_600,48_000,49_000)); var saved=List.copyOf(overflow);
        expect(ArithmeticException.class,()->MatchRules.finalStandings(overflow,3,0,Reason.NEGATIVE_SCORE),"final deposit award overflow is rejected rather than wrapping the first-place balance");
        check(overflow.equals(saved),"overflow never partially updates caller accounts");
        expect(ArithmeticException.class,()->MatchRules.finalStandings(List.of(-536_846_000,-536_846_000,-536_846_000,-536_846_000),2_147_484,0,Reason.NEGATIVE_SCORE),"deposit cash multiplication cannot overflow silently");
    }

    private static void auditFinal(FinalResult result) {
        check(result.standings().size()==4 && result.standings().stream().map(Standing::seat).distinct().count()==4,"final result contains each original player once");
        check(result.standings().stream().mapToLong(Standing::points).sum()==100_000L,"final award leaves exactly 100000 player points and no forgotten pool");
        check(result.standings().stream().mapToLong(Standing::rawTenths).sum()==0L,"25000-return raw tenths sum exactly to zero");
        check(result.standings().stream().mapToLong(Standing::umaTenths).sum()==0L,"uma neither creates nor loses final score");
        check(result.standings().stream().mapToLong(Standing::totalTenths).sum()==0L,"ranked final scores sum exactly to zero without floating point");
    }
    private static List<int[]> permutations() {
        var result=new ArrayList<int[]>();
        for (int a=0;a<4;a++) for (int b=0;b<4;b++) for (int c=0;c<4;c++) for (int d=0;d<4;d++) {
            if (Set.of(a).contains(b) || a==c || a==d || b==c || b==d || c==d) continue;
            result.add(new int[]{a,b,c,d});
        }
        return result;
    }
    private static void check(boolean condition,String message) { assertions++; if (!condition) throw new AssertionError(message); }
    private static void expect(Class<? extends Throwable> expected,Runnable action,String message) {
        assertions++;
        try { action.run(); } catch (Throwable failure) {
            if (expected.isInstance(failure)) return;
            throw new AssertionError(message+"; expected "+expected.getSimpleName()+" but got "+failure,failure);
        }
        throw new AssertionError(message+"; expected "+expected.getSimpleName());
    }
    private static void run(String name,Runnable action) {
        try { action.run(); } catch (Throwable failure) { throw new AssertionError("Match rules group failed: "+name,failure); }
    }
}
