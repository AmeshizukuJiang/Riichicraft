package dev.riichicraft.rules;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import static dev.riichicraft.rules.MatchRules.*;
import static dev.riichicraft.rules.FourPlayerRound.*;

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
    private enum Expected { ADVANCE, REPEAT, NEGATIVE, WIN_STOP, TENPAI_STOP, TARGET, LIMIT }
    // Rows: child/dealer ron, child/dealer tsumo, dealer-noten/tenpai exhaustive,
    // dealer-noten/tenpai nagashi, nine terminals, four winds, four riichi,
    // four kans, multiple ron without/with dealer. These fixed policy tables
    // intentionally differ from the production implementation's priority branches.
    private static final Expected[] ORDINARY={
            Expected.ADVANCE,Expected.REPEAT,Expected.ADVANCE,Expected.REPEAT,
            Expected.ADVANCE,Expected.REPEAT,Expected.ADVANCE,Expected.REPEAT,
            Expected.REPEAT,Expected.REPEAT,Expected.REPEAT,Expected.REPEAT,Expected.ADVANCE,Expected.REPEAT};
    private static final Expected[] CAP_BELOW={
            Expected.LIMIT,Expected.REPEAT,Expected.LIMIT,Expected.REPEAT,
            Expected.LIMIT,Expected.REPEAT,Expected.LIMIT,Expected.REPEAT,
            Expected.REPEAT,Expected.REPEAT,Expected.REPEAT,Expected.REPEAT,Expected.LIMIT,Expected.REPEAT};
    private static final Expected[] SCHEDULED_TARGET_OTHER={
            Expected.TARGET,Expected.REPEAT,Expected.TARGET,Expected.REPEAT,
            Expected.TARGET,Expected.REPEAT,Expected.TARGET,Expected.REPEAT,
            Expected.REPEAT,Expected.REPEAT,Expected.REPEAT,Expected.REPEAT,Expected.TARGET,Expected.REPEAT};
    private static final Expected[] SCHEDULED_TARGET_DEALER={
            Expected.TARGET,Expected.WIN_STOP,Expected.TARGET,Expected.WIN_STOP,
            Expected.TARGET,Expected.TENPAI_STOP,Expected.TARGET,Expected.TENPAI_STOP,
            Expected.REPEAT,Expected.REPEAT,Expected.REPEAT,Expected.REPEAT,Expected.TARGET,Expected.WIN_STOP};
    private static final Expected[] EXTENSION_TARGET={
            Expected.TARGET,Expected.TARGET,Expected.TARGET,Expected.TARGET,
            Expected.TARGET,Expected.TARGET,Expected.TARGET,Expected.TARGET,
            Expected.TARGET,Expected.TARGET,Expected.TARGET,Expected.TARGET,Expected.TARGET,Expected.REPEAT};
    private static final Expected[] CAP_TARGET_DEALER={
            Expected.TARGET,Expected.WIN_STOP,Expected.TARGET,Expected.WIN_STOP,
            Expected.TARGET,Expected.TENPAI_STOP,Expected.TARGET,Expected.TENPAI_STOP,
            Expected.TARGET,Expected.TARGET,Expected.TARGET,Expected.TARGET,Expected.TARGET,Expected.WIN_STOP};
    private static int assertions;
    private MatchRulesTests() { }

    public static void main(String[] args) {
        long start=System.nanoTime();
        run("all twenty-four strict rankings, initial seats, deposits and negative scores",MatchRulesTests::strictStandings);
        run("all fifteen tie partitions, initial-seat priority and final-only deposits",MatchRulesTests::tiedStandings);
        run("precise tenths, preserved negative points and defensive final receipts",MatchRulesTests::exactAndImmutable);
        run("format positions, dealer-relative winds and immutable next-round accounts",MatchRulesTests::positions);
        run("all formats/positions/end kinds/dealers/leaders/target boundaries and honba",MatchRulesTests::progressionMatrix);
        run("last-dealer tied leaders use original-seat priority before automatic stop",MatchRulesTests::tiedStopPriorities);
        run("all ron winner subsets and every exhaustive/nagashi tenpai and winner set",MatchRulesTests::allOutcomeSets);
        run("complete multi-winner balances, negative/zero boundary and final-only pool",MatchRulesTests::bankruptcyAndPool);
        run("extension dealer multi-ron followed by an actual target-bearing abort receipt",MatchRulesTests::multiRonThenAbort);
        run("honba integer limits after actual end/continuation classification",MatchRulesTests::honbaLimits);
        run("inconsistent trusted receipts are rejected without account mutations",MatchRulesTests::invalidReceipts);
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

    private static void progressionMatrix() {
        for (Format format : Format.values()) for (int index=0;index<=format.maximumLast();index++) {
            for (int initial=0;initial<4;initial++) for (int leader=0;leader<4;leader++) {
                for (int target : List.of(29_900,30_000,30_100)) for (int row=0;row<14;row++) {
                    boolean win=row<4 || row>=12;
                    for (int deposits : win?List.of(0):List.of(0,2)) for (int honba : List.of(0,3)) {
                        var context=new Context(format,initial,index,honba,leaderBank(leader,target,deposits),deposits);
                        Expected expected=tableExpected(format,index,leader==context.dealerSeat(),target>=30_000,row);
                        verifyDecision(context,outcome(row,context.dealerSeat()),row,expected,leader);
                    }
                }
            }
        }
    }

    private static void allOutcomeSets() {
        for (Format format : Format.values()) for (int index=0;index<=format.maximumLast();index++) {
            for (int initial=0;initial<4;initial++) {
                int dealer=INITIAL_ORDER[initial][index%4];
                for (int mask=1;mask<15;mask++) {
                    List<Integer> winners=seats(mask).stream().sorted().toList(); boolean repeat=winners.contains(dealer);
                    int row=winners.size()>1?(repeat?13:12):(repeat?1:0);
                    for (int leader=0;leader<4;leader++) for (int target : List.of(29_900,30_000,30_100)) {
                        var context=new Context(format,initial,index,2,leaderBank(leader,target,0),0);
                        var end=receipt(EndKind.RON,winners,Set.of(),repeat);
                        verifyDecision(context,end,row,tableExpected(format,index,leader==dealer,target>=30_000,row),leader);
                    }
                }
                for (int tenpaiMask=0;tenpaiMask<16;tenpaiMask++) for (int winnerMask=0;winnerMask<16;winnerMask++) {
                    for (int deposits : List.of(0,2)) {
                        Set<Integer> tenpai=seats(tenpaiMask); boolean repeat=tenpai.contains(dealer);
                        var points=deposits==0?List.of(25_000,25_000,25_000,25_000):List.of(24_500,24_500,24_500,24_500);
                        var context=new Context(format,initial,index,2,points,deposits);
                        EndKind kind=winnerMask==0?EndKind.EXHAUSTIVE:EndKind.NAGASHI;
                        int row=kind==EndKind.EXHAUSTIVE?(repeat?5:4):(repeat?7:6);
                        var end=receipt(kind,seats(winnerMask).stream().sorted().toList(),tenpai,repeat);
                        verifyDecision(context,end,row,tableExpected(format,index,initial==dealer,false,row),initial);
                    }
                }
            }
        }
    }

    private static void tiedStopPriorities() {
        for (Format format : Format.values()) {
            for (int index : List.of(format.scheduledLast(),format.scheduledLast()+1,format.maximumLast())) {
                for (int initial=0;initial<4;initial++) {
                    int dealer=INITIAL_ORDER[initial][index%4];
                    for (int other=0;other<4;other++) if (other!=dealer) {
                        for (int target : List.of(29_900,30_000,30_100)) {
                            var points=new ArrayList<>(List.of(50_000-target,50_000-target,50_000-target,50_000-target));
                            points.set(dealer,target); points.set(other,target);
                            int leader=-1;
                            for (int seat : INITIAL_ORDER[initial]) if (seat==dealer || seat==other) { leader=seat; break; }
                            check(leader>=0,"fixed initial seat table resolves the top-score tie before any final award");
                            for (int row=0;row<14;row++) {
                                var context=new Context(format,initial,index,2,points,0);
                                verifyDecision(context,outcome(row,dealer),row,
                                        tableExpected(format,index,leader==dealer,target>=30_000,row),leader);
                            }
                        }
                    }
                }
            }
        }
    }

    private static void bankruptcyAndPool() {
        for (Format format : Format.values()) for (int index=0;index<=format.maximumLast();index++) {
            for (int initial=0;initial<4;initial++) for (int row=0;row<14;row++) for (int poor=0;poor<4;poor++) {
                boolean win=row<4 || row>=12; int deposits=win?0:2;
                var negative=new ArrayList<>(List.of(0,0,0,0));
                negative.set((poor+1)%4,60_100); negative.set((poor+2)%4,28_900); negative.set((poor+3)%4,19_000);
                negative.set(poor,-8_000-deposits*1_000);
                var bankrupt=new Context(format,initial,index,Integer.MAX_VALUE,negative,deposits);
                verifyDecision(bankrupt,outcome(row,bankrupt.dealerSeat()),row,Expected.NEGATIVE,(poor+1)%4);

                var zero=new ArrayList<>(List.of(0,0,0,0));
                zero.set((poor+1)%4,40_000); zero.set((poor+2)%4,30_000); zero.set((poor+3)%4,30_000-deposits*1_000);
                var notBankrupt=new Context(format,initial,index,0,zero,deposits);
                int leader=(poor+1)%4;
                verifyDecision(notBankrupt,outcome(row,notBankrupt.dealerSeat()),row,
                        tableExpected(format,index,leader==notBankrupt.dealerSeat(),true,row),leader);
            }
        }

        // Complete receipt from a multi-ron where seat 2 first pays responsibility and
        // only later receives its own win. The temporary -6000 is not a match balance.
        var complete=new Context(Format.EAST,0,0,0,List.of(12_000,42_000,26_000,20_000),0);
        List<RoundLedger.Transfer> transfers=List.of(
                new RoundLedger.Transfer(0,1,16_000,RoundLedger.Kind.HAND),
                new RoundLedger.Transfer(2,1,16_000,RoundLedger.Kind.HAND),
                new RoundLedger.Transfer(0,2,32_000,RoundLedger.Kind.HAND));
        var multi=new End(EndKind.RON,List.of(1,2),Set.of(),Map.of(),transfers,false);
        int temporary=10_000-transfers.get(1).points();
        check(temporary==-6_000 && complete.points().get(2)==26_000,"real payer ordering can temporarily bankrupt a later winner before its full receipt");
        int[] reconstructed={60_000,10_000,10_000,20_000}; boolean transientNegative=false;
        for (var payment : transfers) {
            reconstructed[payment.fromSeat()]-=payment.points(); reconstructed[payment.toSeat()]+=payment.points();
            transientNegative|=reconstructed[2]<0;
        }
        check(transientNegative,"the full responsibility receipt genuinely passes through a negative intermediate payer balance");
        for (int seat=0;seat<4;seat++) check(reconstructed[seat]==complete.points().get(seat),"all transfers, including the later winner, reconstruct the supplied final match context");
        verifyDecision(complete,multi,12,Expected.ADVANCE,1);

        for (Format format : Format.values()) {
            // 29900 + two unclaimed sticks must not count as a 31900 target.
            var scheduled=new Context(format,0,format.scheduledLast(),3,List.of(29_900,24_000,23_000,21_100),2);
            var end=receipt(EndKind.EXHAUSTIVE,List.of(),Set.of(0),false); // final scheduled dealer is seat 3
            verifyDecision(scheduled,end,4,Expected.ADVANCE,0);
            var extension=new Context(format,0,format.scheduledLast()+1,4,scheduled.points(),2);
            verifyDecision(extension,receipt(EndKind.EXHAUSTIVE,List.of(),Set.of(1),false),4,Expected.ADVANCE,0);
            var cap=new Context(format,0,format.maximumLast(),5,scheduled.points(),2);
            var stopped=MatchRules.afterRound(cap,end);
            check(stopped.finished() && stopped.result().orElseThrow().reason()==Reason.EXTENSION_LIMIT,"forced final-hand stop remains a cap stop when the later pool award raises the leader above target");
            check(stopped.result().orElseThrow().standings().getFirst().points()==31_900
                    && stopped.result().orElseThrow().depositRecipient()==0,"only after true match completion does the 29900 leader receive 2000");
            auditFinal(stopped.result().orElseThrow());
        }
    }

    private static void multiRonThenAbort() {
        for (Format format : Format.values()) for (int initial=0;initial<4;initial++) {
            for (int index=format.scheduledLast()+1;index<=format.maximumLast();index++) {
                int dealer=INITIAL_ORDER[initial][index%4], leader=(dealer+1)%4;
                var context=new Context(format,initial,index,2,leaderBank(leader,35_000,0),0);
                // The dealer is deliberately not first, so the cap's automatic win-stop
                // does not hide the explicit multi-ron continuation exception.
                var first=MatchRules.afterRound(context,receipt(EndKind.RON,List.of(dealer,leader),Set.of(),true));
                check(!first.finished() && first.next().orElseThrow().roundIndex()==index && first.next().orElseThrow().honba()==3,"dealer-containing multi-ron keeps the extension alive even with another player above 30000");
                var next=first.next().orElseThrow();
                var after=new Context(format,initial,next.roundIndex(),next.honba(),next.points(),next.deposits());
                for (int row=8;row<=11;row++) {
                    var stopped=MatchRules.afterRound(after,outcome(row,dealer));
                    check(stopped.finished() && stopped.result().orElseThrow().reason()==Reason.TARGET_REACHED,"a subsequent completed abort is subject to extension sudden death, although it never triggers tenpai-stop");
                    check(stopped.result().orElseThrow().standings().getFirst().seat()==leader,"the leader after the full earlier multi-ron remains the final leader");
                    auditFinal(stopped.result().orElseThrow());
                }
            }
        }
        // Single dealer ron/tsumo and dealer tenpai are not silently extended into
        // the explicit dealer-multi-ron exception during an intermediate extension hand.
        for (Format format : Format.values()) {
            int index=format.scheduledLast()+1;
            var context=new Context(format,0,index,0,List.of(35_000,24_000,23_000,18_000),0);
            for (int row : List.of(1,3,5,7)) verifyDecision(context,outcome(row,0),row,Expected.TARGET,0);
        }
    }

    private static void honbaLimits() {
        for (Format format : Format.values()) {
            var early=new Context(format,0,0,Integer.MAX_VALUE,List.of(25_000,25_000,25_000,25_000),0);
            for (int row=0;row<14;row++) {
                if (row==0 || row==2 || row==12) verifyDecision(early,outcome(row,0),row,Expected.ADVANCE,0);
                else {
                    int choice=row;
                    expect(ArithmeticException.class,()->MatchRules.afterRound(early,outcome(choice,0)),"a real continuation needing honba+1 cannot wrap at Integer.MAX_VALUE");
                    check(early.honba()==Integer.MAX_VALUE && early.points().equals(List.of(25_000,25_000,25_000,25_000)),"failed honba increment leaves immutable paid context intact");
                }
            }
            int scheduled=format.scheduledLast(), dealer=3;
            var finalWin=new Context(format,0,scheduled,Integer.MAX_VALUE,List.of(24_000,23_000,18_000,35_000),0);
            verifyDecision(finalWin,outcome(1,dealer),1,Expected.WIN_STOP,dealer);
            verifyDecision(finalWin,outcome(5,dealer),5,Expected.TENPAI_STOP,dealer);
            var ordinaryTarget=new Context(format,0,scheduled,Integer.MAX_VALUE,List.of(35_000,24_000,23_000,18_000),0);
            verifyDecision(ordinaryTarget,outcome(0,dealer),0,Expected.TARGET,0);
            var extension=new Context(format,0,scheduled+1,Integer.MAX_VALUE,List.of(35_000,24_000,23_000,18_000),0);
            verifyDecision(extension,outcome(8,0),8,Expected.TARGET,0);
            var belowCap=new Context(format,0,format.maximumLast(),Integer.MAX_VALUE,List.of(25_000,25_000,25_000,25_000),0);
            verifyDecision(belowCap,outcome(4,3),4,Expected.LIMIT,0);
            expect(ArithmeticException.class,()->MatchRules.afterRound(extension,outcome(13,0)),"the explicit dealer-multi-ron exception really continues and therefore must check honba overflow");
        }
    }

    private static void invalidReceipts() {
        var context=new Context(Format.EAST,0,0,0,List.of(25_000,25_000,25_000,25_000),0);
        expect(NullPointerException.class,()->MatchRules.afterRound(null,outcome(0,0)),"missing completed match context rejected");
        expect(NullPointerException.class,()->MatchRules.afterRound(context,null),"missing actual round receipt rejected");
        expect(NullPointerException.class,()->MatchRules.afterRound(context,receipt(null,List.of(),Set.of(),true)),"null receipt kind rejected");
        List<End> invalid=new ArrayList<>(List.of(
                receipt(EndKind.RON,List.of(),Set.of(),false),
                receipt(EndKind.RON,List.of(0,1,2,3),Set.of(),true),
                receipt(EndKind.RON,List.of(1,1),Set.of(),false),
                receipt(EndKind.RON,List.of(-1),Set.of(),false),
                receipt(EndKind.RON,List.of(4),Set.of(),false),
                receipt(EndKind.RON,List.of(0),Set.of(),false),
                receipt(EndKind.RON,List.of(1),Set.of(),true),
                receipt(EndKind.RON,List.of(1),Set.of(1),false),
                receipt(EndKind.TSUMO,List.of(),Set.of(),false),
                receipt(EndKind.TSUMO,List.of(0,1),Set.of(),true),
                receipt(EndKind.TSUMO,List.of(1),Set.of(0),false),
                receipt(EndKind.EXHAUSTIVE,List.of(1),Set.of(),false),
                receipt(EndKind.EXHAUSTIVE,List.of(),Set.of(0),false),
                receipt(EndKind.EXHAUSTIVE,List.of(),Set.of(1),true),
                receipt(EndKind.EXHAUSTIVE,List.of(),Set.of(-1),false),
                receipt(EndKind.EXHAUSTIVE,List.of(),Set.of(4),false),
                receipt(EndKind.NAGASHI,List.of(),Set.of(0),true),
                receipt(EndKind.NAGASHI,List.of(1),Set.of(0),false),
                receipt(EndKind.NAGASHI,List.of(0),Set.of(1),true)));
        for (EndKind kind : List.of(EndKind.NINE_TERMINALS,EndKind.FOUR_WINDS,EndKind.FOUR_RIICHI,EndKind.FOUR_KANS)) {
            invalid.add(receipt(kind,List.of(1),Set.of(),true));
            invalid.add(receipt(kind,List.of(),Set.of(0),true));
            invalid.add(receipt(kind,List.of(),Set.of(),false));
        }
        for (End end : invalid) {
            expect(IllegalArgumentException.class,()->MatchRules.afterRound(context,end),"inconsistent kind/winners/tenpai/dealer continuation receipt rejected: "+end);
            check(context.points().equals(List.of(25_000,25_000,25_000,25_000)) && context.honba()==0 && context.deposits()==0,"bad receipt never changes paid accounts or metadata");
        }
    }

    private static Expected tableExpected(Format format,int index,boolean dealerLeads,boolean atTarget,int row) {
        if (index<format.scheduledLast()) return ORDINARY[row];
        if (!atTarget) return (index==format.maximumLast()?CAP_BELOW:ORDINARY)[row];
        if (index==format.scheduledLast()) return (dealerLeads?SCHEDULED_TARGET_DEALER:SCHEDULED_TARGET_OTHER)[row];
        return index==format.maximumLast() && dealerLeads?CAP_TARGET_DEALER[row]:EXTENSION_TARGET[row];
    }
    private static List<Integer> leaderBank(int leader,int target,int deposits) {
        var points=new ArrayList<>(List.of(0,0,0,0));
        points.set(leader,target); points.set((leader+1)%4,24_000); points.set((leader+2)%4,23_000);
        points.set((leader+3)%4,53_000-target-deposits*1_000);
        return points;
    }
    private static End outcome(int row,int dealer) {
        int child=(dealer+1)%4;
        return switch(row) {
            case 0 -> receipt(EndKind.RON,List.of(child),Set.of(),false);
            case 1 -> receipt(EndKind.RON,List.of(dealer),Set.of(),true);
            case 2 -> receipt(EndKind.TSUMO,List.of(child),Set.of(),false);
            case 3 -> receipt(EndKind.TSUMO,List.of(dealer),Set.of(),true);
            case 4 -> receipt(EndKind.EXHAUSTIVE,List.of(),Set.of(child),false);
            case 5 -> receipt(EndKind.EXHAUSTIVE,List.of(),Set.of(dealer),true);
            case 6 -> receipt(EndKind.NAGASHI,List.of(dealer),Set.of(child),false);
            case 7 -> receipt(EndKind.NAGASHI,List.of(child),Set.of(dealer),true);
            case 8 -> receipt(EndKind.NINE_TERMINALS,List.of(),Set.of(),true);
            case 9 -> receipt(EndKind.FOUR_WINDS,List.of(),Set.of(),true);
            case 10 -> receipt(EndKind.FOUR_RIICHI,List.of(),Set.of(),true);
            case 11 -> receipt(EndKind.FOUR_KANS,List.of(),Set.of(),true);
            case 12 -> receipt(EndKind.RON,List.of(child,(dealer+2)%4),Set.of(),false);
            case 13 -> receipt(EndKind.RON,List.of(child,dealer),Set.of(),true);
            default -> throw new AssertionError("Bad policy fixture row");
        };
    }
    private static End receipt(EndKind kind,List<Integer> winners,Set<Integer> tenpai,boolean repeat) {
        return new End(kind,winners,tenpai,Map.of(),List.of(),repeat);
    }
    private static void verifyDecision(Context context,End end,int row,Expected expected,int leader) {
        var decision=MatchRules.afterRound(context,end);
        String trace=context.format()+" index="+context.roundIndex()+" initial="+context.initialDealer()+" row="+row+" expected="+expected;
        if (expected==Expected.ADVANCE || expected==Expected.REPEAT) {
            check(!decision.finished() && decision.result().isEmpty(),"decision must continue: "+trace);
            var next=decision.next().orElseThrow();
            int index=context.roundIndex()+(expected==Expected.ADVANCE?1:0);
            int honba=row==0 || row==2 || row==12?0:Math.incrementExact(context.honba());
            check(next.roundIndex()==index,"correct hand advance or dealer repeat: "+trace);
            check(next.honba()==honba,"only child normal wins clear honba; all draws/repeats add one: "+trace);
            check(next.points().equals(context.points()) && next.deposits()==context.deposits(),"next round carries exact fully paid points and separate pool: "+trace);
            check(next.points().stream().mapToLong(Integer::longValue).sum()+next.deposits()*1_000L==100_000L,"continuation preserves whole-bank total");
        } else {
            check(decision.finished() && decision.next().isEmpty(),"decision must finish without preparing another round: "+trace);
            var result=decision.result().orElseThrow();
            Reason reason=switch(expected) {
                case NEGATIVE -> Reason.NEGATIVE_SCORE; case WIN_STOP -> Reason.DEALER_WIN_STOP;
                case TENPAI_STOP -> Reason.DEALER_TENPAI_STOP; case TARGET -> Reason.TARGET_REACHED;
                case LIMIT -> Reason.EXTENSION_LIMIT; default -> throw new AssertionError("not a final fixture");
            };
            check(result.reason()==reason,"priority chooses the actual end reason: "+trace);
            check(result.standings().getFirst().seat()==leader,"final leader comes from fully paid scores and fixed original priority: "+trace);
            check(result.depositPoints()==context.deposits()*1_000 && result.depositRecipient()==(context.deposits()==0?-1:leader),"final deposit award occurs once and only after the true stopping decision");
            for (var standing : result.standings()) check(standing.points()==context.points().get(standing.seat())+(standing.seat()==leader?context.deposits()*1_000:0),"final points are the complete round balances plus the single pool award");
            auditFinal(result);
        }
    }
    private static Set<Integer> seats(int mask) {
        var result=new HashSet<Integer>(); for (int seat=0;seat<4;seat++) if ((mask&(1<<seat))!=0) result.add(seat); return result;
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
