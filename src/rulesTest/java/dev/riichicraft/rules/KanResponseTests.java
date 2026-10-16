package dev.riichicraft.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static dev.riichicraft.rules.DiscardResponseWindow.*;
import static dev.riichicraft.rules.WinContext.*;

public final class KanResponseTests {
    static final UUID ROUND=UUID.fromString("dd43ed89-cbe5-4e7b-a302-5fd8998e5036");
    private static final int[] ORPHANS={0,8,9,17,18,26,27,28,29,30,31,32,33};
    private static final int[][] ORDERS={{0,1,2},{0,2,1},{1,0,2},{1,2,0},{2,0,1},{2,1,0}};
    private static int assertions;
    private KanResponseTests() { }
    public static void main(String[] args) {
        long start=System.nanoTime();
        run("closed/added triple-ron subsets, seats and arrival orders",KanResponseTests::orders);
        run("normal chankan, ippatsu and source-specific missed completions",KanResponseTests::regular);
        run("stable identity, replay, malformed snapshots and immutable results",KanResponseTests::validation);
        System.out.printf("PASS kan responses: %d assertions; %.3f s%n",assertions,(System.nanoTime()-start)/1_000_000_000.0);
    }
    private static void orders() {
        for(boolean closed:List.of(false,true)) for(int actor=0;actor<4;actor++) for(int mask=0;mask<8;mask++) for(int[] order:ORDERS) {
            var f=triple(closed,actor,new Id(ROUND,100+assertions),Riichi.RIICHI); var w=f.window();
            check(w.pendingSeats().size()==3 && w.resolution().isEmpty(),"all missing-east kokushi players have a real robbery choice");
            List<Integer> winners=new ArrayList<>();
            for(int step=0;step<3;step++) {
                int relative=order[step],seat=(actor+relative+1)%4;
                check(w.offers(seat).equals(Set.of(Simple.PASS,Simple.RON)),"kan offers only pass/ron, never chi/pon");
                check(w.submit(w.id(),seat,(mask & 1<<relative)!=0?Simple.RON:Simple.PASS)==Submission.ACCEPTED,"one response collected");
                check(w.resolution().isPresent()==(step==2),"no early kan commitment or partial ron settlement");
            }
            var result=w.resolution().orElseThrow();
            for(int relative=0;relative<3;relative++) {
                int seat=(actor+relative+1)%4; boolean won=(mask & 1<<relative)!=0;
                if(won) winners.add(seat);
                check(result.historiesAfter().get(seat).riichiPass()!=won,"every passed eligible kokushi completion records permanent furiten");
                if(won) {
                    var value=result.ronValues().get(seat);
                    check(value.yakumanUnits()==1 && value.yaku().stream().anyMatch(y->y.yaku()==Yaku.KOKUSHI_MUSOU),"four copies at declarer exclude impossible thirteen-sided robbery");
                }
            }
            check(result.ronSeats().equals(winners) && result.robbed()==(mask!=0),"all ron winners remain in turn order, including triple ron");
            check(result.declarerHand().equals(mask==0?f.proposal().handIfEstablished():f.proposal().handIfRobbed()),"only final resolution selects survived or robbed hand");
            check(f.proposal().handBefore().isComplete(),"original declaration hand remains untouched");
            if(mask!=0) {
                var ledger=RoundLedger.begin(ROUND,0,2,List.of(24_000,24_000,24_000,25_000),3);
                var paid=ledger.settleRon(w,Map.of()); var expected=new ArrayList<>(ledger.points());
                for(int winner:winners) { int points=winner==0?48_000:32_000; expected.set(winner,expected.get(winner)+points); expected.set(actor,expected.get(actor)-points); }
                expected.set(actor,expected.get(actor)-600); expected.set(winners.getFirst(),expected.get(winners.getFirst())+3_600);
                check(paid.ledger().points().equals(expected) && paid.ledger().depositSticks()==0,"robbed declarer pays every winner; honba/deposits go to closest only");
                check(paid.ledger().points().stream().mapToLong(Integer::longValue).sum()==100_000,"robbery ledger conserves bank");
                expectState(()->paid.ledger().settleRon(w,Map.of()));
            }
            check(w.submit(w.id(),(actor+1)%4,Simple.RON)==Submission.CLOSED,"closed robbery window rejects replay");
        }
    }
    private static void regular() {
        var closed=regularFixture(true,false,FuritenState.initial());
        check(closed.window().resolution().isPresent() && !closed.window().resolution().orElseThrow().robbed(),"non-kokushi cannot rob ankan and is automatically passed");
        check(closed.window().ronDecision(1).rejections().contains(WinEvaluator.Rejection.CLOSED_KAN_REQUIRES_KOKUSHI),"explicit closed-kan restriction is reported");
        check(!closed.window().resolution().orElseThrow().historiesAfter().get(1).temporary(),"non-kokushi completion on ankan does not newly cause furiten");
        var added=regularFixture(false,false,FuritenState.initial());
        check(added.window().ronDecision(1).allowed(),"kakan itself supplies chankan yaku to otherwise no-yaku hand");
        var value=added.window().ronDecision(1).handValue().orElseThrow();
        check(value.yaku().stream().anyMatch(y->y.yaku()==Yaku.CHANKAN),"real chankan yaku present");
        finishPass(added.window());
        check(added.window().resolution().orElseThrow().historiesAfter().get(1).temporary(),"passing kakan completion causes temporary furiten");
        var ippatsu=regularFixture(false,true,FuritenState.initial());
        var awards=ippatsu.window().ronDecision(1).handValue().orElseThrow().yaku();
        check(awards.stream().anyMatch(y->y.yaku()==Yaku.IPPATSU) && awards.stream().anyMatch(y->y.yaku()==Yaku.CHANKAN),"robbed, unestablished kakan preserves valid ippatsu and combines chankan");
        check(ippatsu.window().submit(ippatsu.window().id(),1,Simple.RON)==Submission.ACCEPTED,"ippatsu chankan chosen");
        finishPass(ippatsu.window()); check(ippatsu.window().resolution().orElseThrow().robbed(),"robbed proposal never becomes kan");
        var already=regularFixture(false,true,FuritenState.initial().afterPassedRon(Riichi.NONE));
        check(already.window().responses().get(1)==Simple.PASS,"already temporary furiten auto-passes");
        finishPass(already.window());
        var history=already.window().resolution().orElseThrow().historiesAfter().get(1);
        check(history.temporary() && history.riichiPass(),"existing temporary furiten does not suppress new permanent kakan miss");
        var notKokushi=regularFixture(true,true,FuritenState.initial().afterPassedRon(Riichi.NONE));
        var unchanged=notKokushi.window().resolution().orElseThrow().historiesAfter().get(1);
        check(unchanged.temporary() && !unchanged.riichiPass(),"non-kokushi ankan completion preserves existing history without upgrading it");
    }
    private static void validation() {
        var f=triple(true,0,new Id(ROUND,900),Riichi.NONE); var w=f.window();
        check(w.submit(new Id(ROUND,901),1,Simple.RON)==Submission.WRONG_WINDOW,"wrong serial rejected");
        check(w.submit(new Id(new UUID(0,1),900),1,Simple.RON)==Submission.WRONG_WINDOW,"wrong round rejected");
        check(w.submit(w.id(),0,Simple.RON)==Submission.NOT_A_RESPONDER,"declarer cannot respond");
        check(w.submit(w.id(),1,null)==Submission.NOT_OFFERED,"null not a response");
        check(w.submit(w.id(),1,new Call(Meld.Kind.PON,List.of(0,1)))==Submission.NOT_OFFERED,"ordinary calls cannot be submitted to robbery");
        check(w.responses().isEmpty() && w.pendingSeats().size()==3,"rejected requests do not advance state");
        check(w.submit(w.id(),2,Simple.PASS)==Submission.ACCEPTED,"first player's answer accepted");
        check(w.submit(w.id(),2,Simple.RON)==Submission.ALREADY_RESPONDED,"cannot alter an accepted answer");
        check(w.submit(w.id(),1,Simple.PASS)==Submission.ACCEPTED,"another response keeps same stable window id");
        expectInvalid(()->w.offers(0)); expectUnsupported(()->w.offers(1).clear()); expectUnsupported(()->w.responses().clear());
        check(w.submit(w.id(),3,Simple.PASS)==Submission.ACCEPTED,"last response closes window");
        expectUnsupported(()->w.resolution().orElseThrow().ronSeats().clear()); expectUnsupported(()->w.resolution().orElseThrow().historiesAfter().clear());
        expectInvalid(()->RoundLedger.initial(ROUND,0).settleRon(w,Map.of()));
        var unfinished=triple(true,0,new Id(ROUND,901),Riichi.NONE);
        expectState(()->RoundLedger.initial(ROUND,0).settleRon(unfinished.window(),Map.of()));
        expectInvalid(()->new KanResponseWindow(w.id(),f.proposal(),f.players().subList(0,2)));
        var duplicates=new ArrayList<>(f.players()); duplicates.set(2,duplicates.getFirst());
        expectInvalid(()->new KanResponseWindow(w.id(),f.proposal(),duplicates));
        var normalEvent=replaceContext(f.players(),0,WinEvent.NORMAL,List.of());
        expectInvalid(()->new KanResponseWindow(w.id(),f.proposal(),normalEvent));
        var duplicateIndicator=replaceAllIndicators(f.players(),List.of(f.proposal().claimableTileId()));
        expectInvalid(()->new KanResponseWindow(w.id(),f.proposal(),duplicateIndicator));
        var wrongKans=KanActions.propose(f.proposal().handBefore(),f.proposal().claimableTileId(),new KanActions.Context(0,20,1,Riichi.NONE),f.proposal().choice());
        expectInvalid(()->new KanResponseWindow(w.id(),wrongKans,f.players()));
        var outsider=RoundLedger.initial(new UUID(0,9),0);
        expectInvalid(()->outsider.settleRon(unfinished.window(),Map.of()));
        var sample=f.players().getFirst();
        expectInvalid(()->FuritenTracker.afterKanWithoutRon(sample.hand(),f.proposal().claimableTileId(),sample.history(),Riichi.NONE,WinEvaluator.Source.DISCARD));
    }

    record Fixture(KanActions.Proposal proposal,List<KanResponseWindow.Participant> players,KanResponseWindow window) { }
    static Fixture triple(boolean closed,int actor,Id id,Riichi riichi) {
        KanTestTiles p=new KanTestTiles(); var proposal=actor(p,closed,actor,27);
        List<KanResponseWindow.Participant> players=new ArrayList<>();
        int[] pairs={0,8,9};
        for(int relative=1;relative<4;relative++) {
            List<Integer> held=new ArrayList<>(); for(int type:ORPHANS) if(type!=27) held.add(p.take(type)); held.add(p.take(pairs[relative-1]));
            int seat=(actor+relative)%4;
            players.add(new KanResponseWindow.Participant(seat,new HandInput(held,List.of()),context(proposal.claimableTileId(),seat,riichi,false,WinEvent.CHANKAN,List.of()),FuritenState.initial()));
        }
        return new Fixture(proposal,List.copyOf(players),new KanResponseWindow(id,proposal,players));
    }
    private static Fixture regularFixture(boolean closed,boolean riichi,FuritenState history) {
        KanTestTiles p=new KanTestTiles(); var proposal=actor(p,closed,0,4); p.take(13); // Leave the red 5p in the unobserved wall.
        HandInput regular=p.hand("123p456p789s34mEE"); List<KanResponseWindow.Participant> players=new ArrayList<>();
        for(int seat=1;seat<4;seat++) players.add(new KanResponseWindow.Participant(seat,seat==1?regular:new HandInput(p.fill(13),List.of()),
                context(proposal.claimableTileId(),seat,seat==1 && riichi?Riichi.RIICHI:Riichi.NONE,seat==1 && riichi,WinEvent.CHANKAN,List.of()),seat==1?history:FuritenState.initial()));
        return new Fixture(proposal,List.copyOf(players),new KanResponseWindow(new Id(ROUND,1_000),proposal,players));
    }
    static KanActions.Proposal actor(KanTestTiles p,boolean closed,int actor,int type) {
        List<Integer> four=List.of(p.take(type),p.take(type),p.take(type),p.take(type)); int drawn=four.getLast();
        List<Integer> held=new ArrayList<>(p.singles(10)); List<Meld> groups;
        KanActions.Choice choice;
        if(closed) { held.addAll(four); groups=List.of(); choice=new KanActions.Choice(KanActions.Kind.CLOSED,-1,four); }
        else { held.add(drawn); groups=List.of(new Meld(Meld.Kind.PON,four.subList(0,3))); choice=new KanActions.Choice(KanActions.Kind.ADDED,0,List.of(drawn)); }
        return KanActions.propose(new HandInput(held,groups),drawn,new KanActions.Context(actor,20,0,Riichi.NONE),choice);
    }
    private static List<KanResponseWindow.Participant> replaceContext(List<KanResponseWindow.Participant> players,int index,WinEvent event,List<Integer> indicators) {
        var changed=new ArrayList<>(players); var p=changed.get(index);
        changed.set(index,new KanResponseWindow.Participant(p.seat(),p.hand(),context(p.context().winningTileId(),p.seat(),p.context().riichi(),p.context().ippatsu(),event,indicators),p.history())); return changed;
    }
    private static List<KanResponseWindow.Participant> replaceAllIndicators(List<KanResponseWindow.Participant> players,List<Integer> indicators) {
        var result=players; for(int index=0;index<3;index++) result=replaceContext(result,index,WinEvent.CHANKAN,indicators); return result;
    }
    private static WinContext context(int tile,int seat,Riichi riichi,boolean ippatsu,WinEvent event,List<Integer> dora) {
        return new WinContext(WinMethod.RON,tile,27+seat,27,riichi,ippatsu,event,FirstTurn.NONE,dora,List.of());
    }
    private static void finishPass(KanResponseWindow window) { for(int seat:window.pendingSeats()) check(window.submit(window.id(),seat,Simple.PASS)==Submission.ACCEPTED,"pass collected"); }
    private static void check(boolean ok,String message) { assertions++; if(!ok) throw new AssertionError(message); }
    private static void expectInvalid(Runnable r) { assertions++; try { r.run(); } catch(IllegalArgumentException e) { return; } throw new AssertionError("Expected invalid kan snapshot"); }
    private static void expectState(Runnable r) { assertions++; try { r.run(); } catch(IllegalStateException e) { return; } throw new AssertionError("Expected incomplete/closed ledger"); }
    private static void expectUnsupported(Runnable r) { assertions++; try { r.run(); } catch(UnsupportedOperationException e) { return; } throw new AssertionError("Expected immutable window"); }
    private static void run(String label,Runnable r) { try { r.run(); } catch(Throwable t) { throw new AssertionError(label,t); } }
}
