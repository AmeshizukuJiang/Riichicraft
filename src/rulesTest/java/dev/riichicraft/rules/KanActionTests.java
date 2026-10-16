package dev.riichicraft.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static dev.riichicraft.rules.KanActions.*;
import static dev.riichicraft.rules.WinContext.Riichi;

public final class KanActionTests {
    private static int assertions;
    private KanActionTests() { }
    public static void main(String[] args) {
        long start=System.nanoTime();
        run("all types, drawn-copy choices and exact ankan/kakan identities",KanActionTests::physical);
        run("riichi waits, available copies, okurikan and changed decompositions",KanActionTests::riichi);
        run("wall, kan count, meld slots and actual drawn-turn limits",KanActionTests::limits);
        run("invalid choices and immutable proposals",KanActionTests::invalid);
        System.out.printf("PASS kan actions: %d assertions; %.3f s%n",assertions,(System.nanoTime()-start)/1_000_000_000.0);
    }
    private static void physical() {
        for(int type=0;type<34;type++) for(int drawnCopy=0;drawnCopy<4;drawnCopy++) {
            KanTestTiles p=new KanTestTiles(); List<Integer> four=List.of(p.take(type),p.take(type),p.take(type),p.take(type));
            var held=new ArrayList<>(four); held.addAll(p.singles(10)); HandInput hand=new HandInput(held,List.of());
            int drawn=four.get(drawnCopy); Context c=new Context(drawnCopy,1,0,Riichi.NONE);
            Choice closed=new Choice(Kind.CLOSED,-1,four);
            check(KanActions.options(hand,drawn,c).equals(Set.of(closed)),"exactly one ankan from these four physical copies");
            var proposal=KanActions.propose(hand,drawn,c,closed); verify(proposal);
            check(proposal.claimableTileId()==drawn && proposal.source()==WinEvaluator.Source.CLOSED_KAN,"robbery preserves exact last drawn copy where possible");
            check(proposal.handIfEstablished().isClosed() && proposal.handIfEstablished().melds().size()==1,"ankan preserves menzen and adds exactly one group");
            for(int targetCopy=0;targetCopy<4;targetCopy++) {
                int fourth=four.get(targetCopy);
                List<Integer> ponTiles=four.stream().filter(t->t!=fourth).toList();
                Meld oldPon=new Meld(Meld.Kind.PON,ponTiles);
                var concealed=new ArrayList<>(held.subList(4,14)); concealed.add(fourth);
                HandInput before=new HandInput(concealed,List.of(oldPon));
                var kakan=new Choice(Kind.ADDED,0,List.of(fourth));
                check(KanActions.options(before,fourth,c).equals(Set.of(kakan)),"kakan targets the existing pon and exact fourth copy, including red retained outside pon");
                var added=KanActions.propose(before,fourth,c,kakan); verify(added);
                check(added.claimableTileId()==fourth && added.source()==WinEvaluator.Source.ADDED_KAN,"kakan exposes its actual added tile");
                check(added.handIfEstablished().melds().size()==1 && added.meldIfEstablished().kind()==Meld.Kind.OPEN_KAN,"kakan replaces pon rather than adding a fifth group");
                check(added.handIfRobbed().melds().equals(List.of(oldPon)),"robbed kakan retains original pon exactly");
                check(before.concealedTiles().contains(fourth) && before.melds().getFirst().equals(oldPon),"proposal does not mutate old concealed or pon copies");
            }
        }
    }
    private static void riichi() {
        for(Riichi established:List.of(Riichi.RIICHI,Riichi.DOUBLE_RIICHI)) {
            var p=new KanTestTiles(); HandInput before=p.hand("222333444m567pE"); int fourth=p.take(1);
            var hand=KanTestTiles.plus(before,fourth); Context c=new Context(0,20,0,established);
            var choice=new Choice(Kind.CLOSED,-1,hand.concealedTiles().stream().filter(t->t/4==1).toList());
            var proposal=KanActions.propose(hand,fourth,c,choice);
            check(HandAnalyzer.waits(before).equals(Set.of(27)) && HandAnalyzer.waits(proposal.handIfEstablished()).equals(Set.of(27)),"legal riichi ankan preserves all wait types and own-held availability");
            int east=p.take(27);
            check(HandAnalyzer.analyze(KanTestTiles.plus(before,east)).stream().anyMatch(d->d.groups().stream().anyMatch(g->g.kind()==HandDecomposition.Kind.SEQUENCE && g.tileType()==1)),"before kan allows three 234 sequences");
            check(HandAnalyzer.analyze(KanTestTiles.plus(proposal.handIfEstablished(),east)).stream().noneMatch(d->d.groups().stream().anyMatch(g->g.kind()==HandDecomposition.Kind.SEQUENCE && g.tileType()==1)),"changed decompositions are permitted by Mahjong Soul rather than frozen");

            p=new KanTestTiles(); before=p.hand("11123456m789pEE"); fourth=p.take(0); hand=KanTestTiles.plus(before,fourth);
            check(HandAnalyzer.waits(before).equals(Set.of(0,3,6,27)),"reference wait includes 1/4/7m and east via 11+123+456m");
            check(KanActions.options(hand,fourth,c).isEmpty(),"ankan losing 1m wait is forbidden in established riichi");

            p=new KanTestTiles(); before=p.hand("111123456m789pE"); int north=p.take(30); hand=KanTestTiles.plus(before,north);
            check(HandAnalyzer.waits(before).equals(Set.of(27)),"old concealed four fixture is legitimately in tenpai");
            check(KanActions.options(hand,north,c).isEmpty(),"cannot send an old concealed four into kan after drawing another type");
            Context ordinary=new Context(0,20,0,Riichi.NONE);
            var legal=KanActions.options(hand,north,ordinary).iterator().next();
            var notDrawn=KanActions.propose(hand,north,ordinary,legal);
            check(notDrawn.claimableTileId()==3 && notDrawn.handIfRobbed().concealedTiles().contains(north),"non-riichi old-four kan uses a deterministic exact copy while retaining the unrelated draw");
        }
    }
    private static void limits() {
        var p=new KanTestTiles(); var quad=p.tiles("1111m"); var held=new ArrayList<>(quad); held.addAll(p.singles(10));
        HandInput hand=new HandInput(held,List.of()); Choice call=new Choice(Kind.CLOSED,-1,quad);
        check(KanActions.options(hand,3,new Context(0,0,0,Riichi.NONE)).isEmpty(),"empty live wall forbids replacement-backed kan");
        check(KanActions.options(hand,3,new Context(0,1,4,Riichi.NONE)).isEmpty(),"fifth kan forbidden");
        check(KanActions.options(hand,3,new Context(0,1,3,Riichi.NONE)).contains(call),"fourth kan can still be proposed when one live tile remains");
        List<Meld> groups=new ArrayList<>(); p=new KanTestTiles();
        for(int type=27;type<30;type++) groups.add(new Meld(Meld.Kind.CLOSED_KAN,List.of(p.take(type),p.take(type),p.take(type),p.take(type))));
        List<Integer> pon=List.of(p.take(31),p.take(31),p.take(31)); groups.add(new Meld(Meld.Kind.PON,pon));
        int added=p.take(31),other=p.take(1); HandInput fourGroups=new HandInput(List.of(added,other),groups);
        var choice=new Choice(Kind.ADDED,3,List.of(added));
        var proposal=KanActions.propose(fourGroups,other,new Context(0,1,3,Riichi.NONE),choice);
        check(proposal.handIfEstablished().concealedTiles().equals(List.of(other)) && proposal.handIfEstablished().melds().size()==4,"fourth declared group can be upgraded without a new meld slot");
        check(proposal.handIfEstablished().melds().stream().allMatch(m->m.tiles().size()==4),"same player may establish four kans");
    }
    private static void invalid() {
        var p=new KanTestTiles(); HandInput waiting=p.hand("111123456m789pE"); int drawn=p.take(30); HandInput hand=KanTestTiles.plus(waiting,drawn);
        Context c=new Context(0,10,0,Riichi.NONE);
        expectInvalid(()->KanActions.options(waiting,0,c));
        expectInvalid(()->KanActions.options(hand,135,c));
        expectInvalid(()->new Context(4,10,0,Riichi.NONE)); expectInvalid(()->new Context(0,-1,0,Riichi.NONE));
        expectInvalid(()->new Context(0,71,0,Riichi.NONE)); expectInvalid(()->new Context(0,10,5,Riichi.NONE));
        expectInvalid(()->new Choice(Kind.CLOSED,0,List.of(0,1,2,3))); expectInvalid(()->new Choice(Kind.CLOSED,-1,List.of(0,1,2,4)));
        expectInvalid(()->new Choice(Kind.ADDED,-1,List.of(0))); expectInvalid(()->new Choice(Kind.ADDED,4,List.of(0)));
        expectInvalid(()->KanActions.propose(hand,drawn,c,new Choice(Kind.ADDED,0,List.of(drawn))));
        expectInvalid(()->KanActions.propose(hand,drawn,c,new Choice(Kind.CLOSED,-1,List.of(4,5,6,7))));
        var choices=KanActions.options(hand,drawn,c); expectUnsupported(choices::clear);
        var proposal=KanActions.propose(hand,drawn,c,choices.iterator().next());
        expectUnsupported(()->proposal.choice().handTiles().clear()); expectUnsupported(()->proposal.handIfEstablished().concealedTiles().clear());
        expectUnsupported(()->proposal.handIfRobbed().melds().clear());
    }
    private static void verify(Proposal proposal) {
        var all=KanTestTiles.all(proposal.handBefore());
        check(KanTestTiles.all(proposal.handIfEstablished()).equals(all),"survived kan conserves every held physical identity without drawing early");
        var expected=new java.util.HashSet<>(all); expected.remove(proposal.claimableTileId());
        check(KanTestTiles.all(proposal.handIfRobbed()).equals(expected),"robbery removes exactly one physical tile, not all four");
        check(proposal.handBefore().isComplete() && proposal.handIfEstablished().isWaiting() && proposal.handIfRobbed().isWaiting(),"both resolved branches return structural thirteen");
    }
    private static void check(boolean ok,String message) { assertions++; if(!ok) throw new AssertionError(message); }
    private static void expectInvalid(Runnable r) { assertions++; try { r.run(); } catch(IllegalArgumentException e) { return; } throw new AssertionError("Expected invalid kan"); }
    private static void expectUnsupported(Runnable r) { assertions++; try { r.run(); } catch(UnsupportedOperationException e) { return; } throw new AssertionError("Expected immutable kan proposal"); }
    private static void run(String label,Runnable r) { try { r.run(); } catch(Throwable t) { throw new AssertionError(label,t); } }
}
