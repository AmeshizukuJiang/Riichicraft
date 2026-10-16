package dev.riichicraft.rules;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static dev.riichicraft.rules.DiscardResponseWindow.*;
import static dev.riichicraft.rules.WinContext.*;

public final class KanRoundTests {
    private static final UUID ROUND=KanResponseTests.ROUND;
    private static int assertions;
    private KanRoundTests() { }
    public static void main(String[] args) {
        long start=System.nanoTime();
        run("immediate/deferred/consecutive indicators and robbed next kan",KanRoundTests::indicators);
        run("every four-kan ownership pattern and final response priority",KanRoundTests::fourth);
        run("real four-ankan wall, hand, score and ledger composition",KanRoundTests::physicalChain);
        run("wrong actors/windows, pending and terminal guards",KanRoundTests::invalid);
        System.out.printf("PASS kan round: %d assertions; %.3f s%n",assertions,(System.nanoTime()-start)/1_000_000_000.0);
    }
    private static void indicators() {
        var initial=KanRoundState.initial(ROUND);
        var open=initial.afterEstablished(2,Meld.Kind.OPEN_KAN);
        check(open.establishedKans()==1 && open.revealedIndicators()==1 && open.delayedIndicatorPending(),"open kan does not reveal its new dora before replacement self-win");
        var win=open.afterRinshanWin();
        check(win.revealedIndicators()==1 && win.fourthStage()==KanRoundState.FourthStage.WON,"rinshan win sees no deferred dora of that open kan");
        var discarded=open.onDiscardOpening(2,new Id(ROUND,1));
        check(discarded.revealedIndicators()==2 && !discarded.delayedIndicatorPending(),"discard opens new dora before ron contexts are built");
        var next=open.beforeSelfKanDeclaration(2);
        check(next.revealedIndicators()==2 && next.establishedKans()==1,"consecutive declaration reveals only the preceding deferred indicator");
        check(next.beforeSelfKanDeclaration(2).revealedIndicators()==2,"no repeated flip when there is nothing deferred");
        // If the next proposal is robbed, do not apply afterEstablished: the previous reveal remains.
        check(next.revealedIndicators()==2 && next.kansBySeat().equals(List.of(0,0,1,0)),"robbed following declaration adds neither a kan nor its own indicator");
        var closed=next.afterEstablished(2,Meld.Kind.CLOSED_KAN);
        check(closed.revealedIndicators()==3 && !closed.delayedIndicatorPending(),"survived consecutive ankan reveals itself immediately");
        var another=closed.afterEstablished(2,Meld.Kind.OPEN_KAN);
        check(another.revealedIndicators()==3 && another.onDiscardOpening(2,new Id(ROUND,2)).revealedIndicators()==4,"later kakan again defers exactly its own indicator");
        check(initial.revealedIndicators()==1 && initial.establishedKans()==0,"old snapshots unchanged");
    }
    private static void fourth() {
        // All 4^4 ownership assignments, both closed/open final-kan kinds.
        for(int owners=0;owners<256;owners++) for(boolean lastClosed:List.of(false,true)) {
            var state=KanRoundState.initial(ROUND); Set<Integer> unique=new HashSet<>(); int actor=-1;
            for(int index=0;index<3;index++) {
                actor=(owners>>(index*2))&3; unique.add(actor);
                state=state.afterEstablished(actor,index%2==0?Meld.Kind.CLOSED_KAN:Meld.Kind.OPEN_KAN)
                        .onDiscardOpening(actor,new Id(ROUND,index));
            }
            actor=(owners>>6)&3; unique.add(actor);
            var fourth=state.afterEstablished(actor,lastClosed?Meld.Kind.CLOSED_KAN:Meld.Kind.OPEN_KAN);
            boolean abort=unique.size()>1;
            check(fourth.establishedKans()==4 && fourth.revealedIndicators()==(lastClosed?5:4),"fourth established kan owns exactly one new indicator slot");
            check(fourth.fourthStage()==(abort?KanRoundState.FourthStage.NEEDS_DISCARD:KanRoundState.FourthStage.NONE),"only multiple owners require four-kan abort");
            check(fourth.afterRinshanWin().fourthStage()==KanRoundState.FourthStage.WON,"fourth replacement self-win always has priority");
            Id id=new Id(ROUND,10+owners*2L+(lastClosed?1:0));
            var opening=fourth.onDiscardOpening(actor,id);
            check(opening.revealedIndicators()==5 && !opening.delayedIndicatorPending(),"all four kan dora available for fourth-discard ron");
            check(opening.fourthStage()==(abort?KanRoundState.FourthStage.RESPONSES:KanRoundState.FourthStage.NONE),"one-owner fourth kan can continue normally");
            if(!abort) { check(opening.kansBySeat().get(actor)==4,"all four kans belong to one player"); continue; }
            var pass=RiichiTests.fixture(actor,id,false,2,true).window(); finish(pass,false);
            var drawn=opening.resolveFourthDiscard(pass);
            check(drawn.fourthStage()==KanRoundState.FourthStage.ABORTED,"no fourth-discard ron leads to abort only after complete responses");
            var ron=RiichiTests.fixture(actor,id,false,2,true).window(); finish(ron,true);
            check(opening.resolveFourthDiscard(ron).fourthStage()==KanRoundState.FourthStage.WON,"fourth-discard ron overrides abort");
            expectState(()->drawn.resolveFourthDiscard(pass));
        }
    }
    private static void physicalChain() {
        // Legal initial 13: 1111m222m333m444m; first ordinary draw east. Each next rinshan
        // supplies one fourth tile, finally a second east for four-ankan tanki self-win.
        var order=new ArrayList<Integer>(java.util.Collections.nCopies(136,-1));
        int[] dealer={0,1,2,3,4,5,6,8,9,10,12,13,14};
        Set<Integer> reserved=new HashSet<>();
        for(int index=0;index<13;index++) { order.set(index*4,dealer[index]); reserved.add(dealer[index]); }
        int[] replacement={7,11,15,109};
        for(int index=0;index<4;index++) { order.set(122+index,replacement[index]); reserved.add(replacement[index]); }
        order.set(52,108); reserved.add(108); int next=0;
        for(int index=0;index<136;index++) if(order.get(index)<0) { while(reserved.contains(next)) next++; order.set(index,next++); }
        var deal=RoundWall.deal(order); var normal=deal.wall().drawLive(); RoundWall wall=normal.wall();
        List<HandInput> hands=new ArrayList<>(deal.hands()); hands.set(0,KanTestTiles.plus(hands.getFirst(),normal.tileId()));
        var progress=KanRoundState.initial(ROUND); audit(hands,wall);
        for(int type=0;type<4;type++) {
            int target=type; int drawn=type==0?normal.tileId():replacement[type-1];
            var choice=new KanActions.Choice(KanActions.Kind.CLOSED,-1,hands.getFirst().concealedTiles().stream().filter(t->t/4==target).toList());
            var proposal=KanActions.propose(hands.getFirst(),drawn,new KanActions.Context(0,wall.liveCount(),type,Riichi.NONE),choice);
            progress=progress.beforeSelfKanDeclaration(0);
            List<KanResponseWindow.Participant> participants=new ArrayList<>();
            for(int seat=1;seat<4;seat++) participants.add(new KanResponseWindow.Participant(seat,hands.get(seat),
                    new WinContext(WinMethod.RON,proposal.claimableTileId(),27+seat,27,Riichi.NONE,false,WinEvent.CHANKAN,FirstTurn.NONE,
                            wall.doraIndicators(progress.revealedIndicators()),List.of()),FuritenState.initial()));
            var window=new KanResponseWindow(new Id(ROUND,100+type),proposal,participants);
            for(int seat:window.pendingSeats()) check(window.submit(window.id(),seat,Simple.PASS)==Submission.ACCEPTED,"scripted kan response passed");
            check(!window.resolution().orElseThrow().robbed(),"scripted quad survives before any hand or wall movement");
            hands.set(0,window.resolution().orElseThrow().declarerHand());
            progress=progress.afterEstablished(0,Meld.Kind.CLOSED_KAN); audit(hands,wall);
            var rinshan=wall.drawReplacement(); wall=rinshan.wall();
            check(rinshan.tileId()==replacement[type] && rinshan.source()==RoundWall.Source.RINSHAN,"exact reserved replacement consumed in order");
            hands.set(0,KanTestTiles.plus(hands.getFirst(),rinshan.tileId())); audit(hands,wall);
        }
        check(progress.fourthStage()==KanRoundState.FourthStage.NONE && progress.revealedIndicators()==5,"four kans by same player do not abort and reveal all five dora");
        check(wall.liveCount()==65 && hands.getFirst().melds().size()==4,"one normal draw and four replacements consume five live units");
        var context=new WinContext(WinMethod.TSUMO,109,27,27,Riichi.NONE,false,WinEvent.RINSHAN,FirstTurn.NONE,wall.doraIndicators(5),List.of());
        var win=WinEvaluator.evaluate(hands.getFirst(),context,FuritenState.initial(),WinEvaluator.Source.SELF_DRAW);
        check(win.allowed() && win.handValue().orElseThrow().yakumanUnits()==3,"actual four-ankan pair win scores double suuankou tanki plus suukantsu");
        var ledger=RoundLedger.initial(ROUND,0).settleTsumo(0,win,java.util.Optional.empty());
        check(ledger.ledger().points().equals(List.of(169_000,-23_000,-23_000,-23_000)),"three genuine dealer yakuman units paid in full");
        check(progress.afterRinshanWin().fourthStage()==KanRoundState.FourthStage.WON,"real replacement win closes kan gate");
    }
    private static void invalid() {
        var initial=KanRoundState.initial(ROUND); var open=initial.afterEstablished(1,Meld.Kind.OPEN_KAN);
        expectInvalid(()->initial.afterEstablished(4,Meld.Kind.CLOSED_KAN));
        expectInvalid(()->initial.afterEstablished(0,Meld.Kind.PON));
        expectState(initial::afterRinshanWin);
        expectInvalid(()->open.beforeSelfKanDeclaration(2));
        expectInvalid(()->open.onDiscardOpening(2,new Id(ROUND,0)));
        expectInvalid(()->open.onDiscardOpening(1,new Id(new UUID(0,1),0)));
        expectState(()->open.afterEstablished(1,Meld.Kind.CLOSED_KAN));
        var closed=initial.afterEstablished(0,Meld.Kind.CLOSED_KAN);
        expectInvalid(()->closed.beforeSelfKanDeclaration(1));
        expectInvalid(()->closed.onDiscardOpening(1,new Id(ROUND,1)));
        expectInvalid(()->closed.afterEstablished(1,Meld.Kind.CLOSED_KAN));
        expectState(()->closed.onDiscardOpening(0,new Id(ROUND,1)).afterRinshanWin());
        var fourth=initial.afterEstablished(0,Meld.Kind.CLOSED_KAN).afterEstablished(0,Meld.Kind.CLOSED_KAN)
                .onDiscardOpening(0,new Id(ROUND,2))
                .afterEstablished(1,Meld.Kind.CLOSED_KAN).afterEstablished(1,Meld.Kind.CLOSED_KAN);
        expectInvalid(()->fourth.onDiscardOpening(0,new Id(ROUND,9)));
        expectState(()->fourth.afterEstablished(1,Meld.Kind.CLOSED_KAN));
        expectState(()->fourth.beforeSelfKanDeclaration(1));
        var opening=fourth.onDiscardOpening(1,new Id(ROUND,9));
        var pending=RiichiTests.fixture(1,new Id(ROUND,9),false,1,true).window();
        expectState(()->opening.resolveFourthDiscard(pending));
        expectState(()->opening.onDiscardOpening(1,new Id(ROUND,10)));
        expectState(opening::afterRinshanWin);
        var wrong=RiichiTests.fixture(1,new Id(ROUND,10),false,1,true).window(); finish(wrong,false);
        expectInvalid(()->opening.resolveFourthDiscard(wrong));
        var wrongSeat=RiichiTests.fixture(2,new Id(ROUND,9),false,1,true).window(); finish(wrongSeat,false);
        expectInvalid(()->opening.resolveFourthDiscard(wrongSeat));
        finish(pending,false); var end=opening.resolveFourthDiscard(pending);
        expectState(()->end.onDiscardOpening(1,new Id(ROUND,11))); expectState(()->end.beforeSelfKanDeclaration(1));
        var called=RiichiTests.fixture(1,new Id(ROUND,9),true,1,true);
        int caller=2;
        for(int seat:called.window().pendingSeats()) {
            Choice choice=seat==caller?called.windowPlayers().getFirst().calls().stream().filter(c->c.kind()==Meld.Kind.PON).findFirst().orElseThrow():Simple.PASS;
            check(called.window().submit(called.window().id(),seat,choice)==Submission.ACCEPTED,"unexpected call collected for abort-priority negative test");
        }
        check(called.window().resolution().orElseThrow().outcome()==Outcome.CALL
                && opening.resolveFourthDiscard(called.window()).fourthStage()==KanRoundState.FourthStage.ABORTED,"an accidentally supplied call can never beat fourth-kan abort; owner must not execute it");
        expectUnsupported(()->initial.kansBySeat().clear());
    }
    private static void finish(DiscardResponseWindow w,boolean ron) {
        boolean chosen=false;
        for(int seat:w.pendingSeats()) { Choice choice=ron && !chosen?Simple.RON:Simple.PASS; chosen|=choice==Simple.RON; check(w.submit(w.id(),seat,choice)==Submission.ACCEPTED,"fourth-discard response collected"); }
    }
    private static void audit(List<HandInput> hands,RoundWall wall) {
        List<Integer> all=new ArrayList<>(); hands.forEach(h->{all.addAll(h.concealedTiles());h.melds().forEach(m->all.addAll(m.tiles()));}); all.addAll(wall.liveTiles()); all.addAll(wall.deadTiles());
        check(all.size()==136 && new HashSet<>(all).size()==136 && wall.deadTiles().size()==14,"every kan/replacement step conserves all 136 unique identities and fourteen dead tiles");
    }
    private static void check(boolean ok,String message) { assertions++; if(!ok) throw new AssertionError(message); }
    private static void expectInvalid(Runnable r) { assertions++; try { r.run(); } catch(IllegalArgumentException e) { return; } throw new AssertionError("Expected invalid kan event"); }
    private static void expectState(Runnable r) { assertions++; try { r.run(); } catch(IllegalStateException e) { return; } throw new AssertionError("Expected pending/closed gate"); }
    private static void expectUnsupported(Runnable r) { assertions++; try { r.run(); } catch(UnsupportedOperationException e) { return; } throw new AssertionError("Expected immutable state"); }
    private static void run(String label,Runnable r) { try { r.run(); } catch(Throwable t) { throw new AssertionError(label,t); } }
}
