package dev.riichicraft.rules;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import static dev.riichicraft.rules.DiscardResponseWindow.*;
import static dev.riichicraft.rules.WinContext.*;

/** Independent payer tables, whole-bank conservation, actual scored hands and replay rejection. */
public final class LedgerTests {
    private static int assertions;
    private static final UUID ROUND = UUID.fromString("6b49331f-99fc-4e90-a117-aaeabf020037");
    private static final List<Integer> CARRIED = List.of(24_000,24_000,24_000,25_000);
    private static final int[] ORPHANS = {0,8,9,17,18,26,27,28,29,30,31,32,33};
    private LedgerTests() { }

    public static void main(String[] args) {
        long start=System.nanoTime();
        run("declaration debits, cancellation, funding and duplicate receipts",LedgerTests::declarations);
        run("all dealer/discarder/ron subsets and closest-only extras",LedgerTests::multiRon);
        run("actual open one-han thirty-fu tsumo tables",LedgerTests::ordinaryTsumo);
        run("responsibility components, double yakuman and shared payer",LedgerTests::pao);
        run("multi-ron with liability and nearest-winner extras",LedgerTests::paoMultiRon);
        run("negative input, wrong rounds/methods, closed accounts and immutability",LedgerTests::invalid);
        System.out.printf("PASS ledger: %d assertions; %.3f s%n",assertions,(System.nanoTime()-start)/1_000_000_000.0);
    }

    private static void declarations() {
        for(int seat=0;seat<4;seat++) for(boolean called:List.of(false,true)) {
            var f=RiichiTests.fixture(seat,new Id(ROUND,seat*2+(called?1:0)),called,1,false);
            for(int responder:f.window().pendingSeats()) {
                Choice choice=called && responder==(seat+1)%4 ? f.windowPlayers().getFirst().calls().stream().filter(c->c.kind()==Meld.Kind.PON).findFirst().orElseThrow():Simple.PASS;
                check(f.window().submit(f.window().id(),responder,choice)==Submission.ACCEPTED,"complete declaration response");
            }
            var ledger=RoundLedger.initial(ROUND,0);
            var booked=ledger.establishRiichi(f.declaration().state(),f.window());
            var expected=new ArrayList<>(List.of(25_000,25_000,25_000,25_000)); expected.set(seat,24_000);
            check(booked.ledger().points().equals(expected) && booked.ledger().depositSticks()==1,"move exactly 1000 from declarer to pool");
            check(booked.state().establishedRiichi()==Riichi.RIICHI && booked.state().ippatsu()!=called,"ledger and riichi update agree on called declaration");
            audit(booked.ledger());
            expectState(()->booked.ledger().establishRiichi(f.declaration().state(),f.window()));
            check(ledger.points().equals(List.of(25_000,25_000,25_000,25_000)) && ledger.depositSticks()==0,"original account snapshot unchanged");
        }
        var f=RiichiTests.fixture(0,new Id(ROUND,20),false,1,false); finish(f.window(),Set.of(1));
        var cancelled=RoundLedger.initial(ROUND,0).establishRiichi(f.declaration().state(),f.window());
        check(cancelled.ledger().depositSticks()==0 && cancelled.ledger().points().getFirst()==25_000
                && cancelled.state().establishedRiichi()==Riichi.NONE,"ron cancels declaration without a lost or newly created stick");
        audit(cancelled.ledger());
        var settled=cancelled.ledger().settleRon(f.window(),Map.of()); audit(settled.ledger());
        check(settled.ledger().depositSticks()==0,"cancelled new stick cannot be awarded to winner");

        var low=RiichiTests.fixture(0,new Id(ROUND,21),false,1,false); finish(low.window(),Set.of());
        var exactly=RoundLedger.begin(ROUND,0,0,List.of(1_000,33_000,33_000,33_000),0);
        var zero=exactly.establishRiichi(low.declaration().state(),low.window());
        check(zero.ledger().points().getFirst()==0 && zero.ledger().depositSticks()==1,"exactly 1000 may declare and remain at zero"); audit(zero.ledger());
        var unfunded=RoundLedger.begin(ROUND,0,0,List.of(900,33_100,33_000,33_000),0);
        expectState(()->unfunded.establishRiichi(low.declaration().state(),low.window()));
        check(unfunded.points().getFirst()==900 && unfunded.depositSticks()==0,"ledger rechecks actual funding instead of trusting old eligibility context");
    }

    private static void multiRon() {
        for(int dealer=0;dealer<4;dealer++) for(int discarder=0;discarder<4;discarder++) for(int mask=1;mask<8;mask++) {
            var window=kokushiWindow(dealer,discarder,new Id(ROUND,100+dealer*40+discarder*8+mask));
            Set<Integer> winners=new HashSet<>();
            for(int relative=1;relative<4;relative++) if((mask & 1 << (relative-1))!=0) winners.add((discarder+relative)%4);
            finish(window,winners);
            var ledger=RoundLedger.begin(ROUND,dealer,2,CARRIED,3);
            var result=ledger.settleRon(window,Map.of());
            var expected=new ArrayList<>(CARRIED); int closest=-1;
            for(int relative=1;relative<4;relative++) {
                int winner=(discarder+relative)%4; if(!winners.contains(winner)) continue;
                if(closest<0) closest=winner;
                int payment=winner==dealer?96_000:64_000; // Genuine double kokushi, from independent yakuman table.
                expected.set(winner,expected.get(winner)+payment);
                expected.set(discarder,expected.get(discarder)-payment);
            }
            expected.set(discarder,expected.get(discarder)-600); expected.set(closest,expected.get(closest)+3_600);
            check(result.ledger().points().equals(expected),"each real winner paid in full; only closest receives honba and old deposits");
            check(result.winners().equals(window.resolution().orElseThrow().ronSeats()),"account receipt keeps exact multi-ron ordering");
            check(result.transfers().stream().filter(t->t.kind()==RoundLedger.Kind.HONBA).count()==1
                    && result.transfers().stream().filter(t->t.kind()==RoundLedger.Kind.DEPOSIT).count()==1,"extras never repeated per winner");
            check(result.ledger().points().get(discarder)<0 && result.ledger().finished(),"full payment can bankrupt discarder; no payment truncation");
            audit(result.ledger());
            expectState(()->result.ledger().settleRon(window,Map.of()));
        }
    }

    private static void ordinaryTsumo() {
        for(int dealer=0;dealer<4;dealer++) for(int winner=0;winner<4;winner++) {
            var decision=ordinary(winner,dealer);
            var value=decision.handValue().orElseThrow();
            check(decision.allowed() && value.han()==1 && value.fu()==30,"actual open tanyao hand has exactly one han thirty fu");
            var ledger=RoundLedger.begin(ROUND,dealer,2,CARRIED,3);
            var result=ledger.settleTsumo(winner,decision,Optional.empty());
            var expected=new ArrayList<>(CARRIED); int won=3_000;
            for(int payer=0;payer<4;payer++) if(payer!=winner) {
                int base=winner==dealer?500:payer==dealer?500:300;
                expected.set(payer,expected.get(payer)-base-200); won+=base+200;
            }
            expected.set(winner,expected.get(winner)+won);
            check(result.ledger().points().equals(expected),"each rounded payer amount receives exactly two honba shares");
            audit(result.ledger());
            expectState(()->result.ledger().settleTsumo(0,decision,Optional.empty()));
        }
    }

    private static void pao() {
        for(Yaku component:List.of(Yaku.DAISANGEN,Yaku.DAISUUSHII)) {
            for(int dealer=0;dealer<4;dealer++) for(int winner=0;winner<4;winner++) for(int liable=0;liable<4;liable++) {
                if(liable==winner) continue;
                var value=paoHand(new Pool(),component,winner,dealer,true).decision();
                var result=RoundLedger.begin(ROUND,dealer,2,CARRIED,3).settleTsumo(winner,value,Optional.of(new RoundLedger.Pao(liable,component)));
                var expected=new ArrayList<>(CARRIED); int won=3_000;
                for(int payer=0;payer<4;payer++) if(payer!=winner) {
                    int ordinary=component==Yaku.DAISUUSHII?0:winner==dealer?16_000:payer==dealer?16_000:8_000;
                    int responsible=payer==liable?(winner==dealer?48_000:32_000)*(component==Yaku.DAISUUSHII?2:1)+600:0;
                    expected.set(payer,expected.get(payer)-ordinary-responsible); won+=ordinary+responsible;
                }
                expected.set(winner,expected.get(winner)+won);
                check(result.ledger().points().equals(expected),"responsibility covers exactly its component, including double daisuushii; other components normal"); audit(result.ledger());
            }
            for(int dealer=0;dealer<4;dealer++) for(int discarder=0;discarder<4;discarder++) for(int winner=0;winner<4;winner++) {
                if(winner==discarder) continue;
                for(int liable=0;liable<4;liable++) {
                    if(liable==winner) continue;
                    var window=paoWindow(component,dealer,discarder,winner,new Id(ROUND,500+assertions)); finish(window,Set.of(winner));
                    var result=RoundLedger.begin(ROUND,dealer,2,CARRIED,3).settleRon(window,Map.of(winner,new RoundLedger.Pao(liable,component)));
                    int full=winner==dealer?96_000:64_000;
                    int responsible=component==Yaku.DAISUUSHII?full:full/2;
                    var expected=new ArrayList<>(CARRIED);
                    expected.set(discarder,expected.get(discarder)-(full-responsible));
                    if(liable==discarder) expected.set(liable,expected.get(liable)-responsible-600);
                    else { expected.set(discarder,expected.get(discarder)-responsible/2); expected.set(liable,expected.get(liable)-responsible/2-600); }
                    expected.set(winner,expected.get(winner)+full+3_600);
                    check(result.ledger().points().equals(expected),"ron splits liable component only; same payer pays full once; honba goes to liable payer"); audit(result.ledger());
                }
            }
        }
    }

    private static void invalid() {
        expectInvalid(()->RoundLedger.begin(ROUND,4,0,CARRIED,3));
        expectInvalid(()->RoundLedger.begin(ROUND,0,-1,CARRIED,3));
        expectInvalid(()->RoundLedger.begin(ROUND,0,0,CARRIED,-1));
        expectInvalid(()->RoundLedger.begin(ROUND,0,0,CARRIED,0));
        expectInvalid(()->RoundLedger.begin(ROUND,0,0,List.of(25_000,25_000,50_000),0));
        expectInvalid(()->RoundLedger.begin(ROUND,0,0,List.of(24_999,25_001,25_000,25_000),0));
        expectInvalid(()->new RoundLedger.Pao(4,Yaku.DAISANGEN)); expectInvalid(()->new RoundLedger.Pao(1,Yaku.SUUKANTSU));
        var f=RiichiTests.fixture(0,new Id(new UUID(0,9),1),false,1,false); finish(f.window(),Set.of());
        var ledger=RoundLedger.initial(ROUND,0);
        expectInvalid(()->ledger.establishRiichi(f.declaration().state(),f.window()));
        var wrongRound=kokushiWindow(0,0,new Id(new UUID(0,10),1)); finish(wrongRound,Set.of(1));
        expectInvalid(()->ledger.settleRon(wrongRound,Map.of()));
        var incomplete=kokushiWindow(0,0,new Id(ROUND,900)); expectState(()->ledger.settleRon(incomplete,Map.of()));
        finish(incomplete,Set.of()); expectInvalid(()->ledger.settleRon(incomplete,Map.of()));
        var ron=kokushiWindow(0,0,new Id(ROUND,901)); finish(ron,Set.of(1));
        expectInvalid(()->ledger.settleRon(ron,Map.of(2,new RoundLedger.Pao(0,Yaku.DAISANGEN))));
        expectInvalid(()->ledger.settleRon(ron,Map.of(1,new RoundLedger.Pao(0,Yaku.DAISANGEN))));
        expectInvalid(()->ledger.settleRon(ron,Map.of(1,new RoundLedger.Pao(1,Yaku.DAISANGEN))));
        var ronValue=ron.resolution().orElseThrow().ronValues().get(1);
        expectInvalid(()->ledger.settleTsumo(1,new WinEvaluator.Decision(Optional.of(ronValue),Set.of()),Optional.empty()));
        expectInvalid(()->ledger.settleTsumo(1,new WinEvaluator.Decision(Optional.of(ronValue),Set.of(WinEvaluator.Rejection.DISCARD_FURITEN)),Optional.empty()));
        expectInvalid(()->ledger.settleTsumo(4,ordinary(1,0),Optional.empty()));
        expectInvalid(()->ledger.settleTsumo(0,ordinary(1,0),Optional.empty()));
        var overflow=RoundLedger.begin(ROUND,0,Integer.MAX_VALUE,CARRIED,3);
        expectOverflow(()->overflow.settleTsumo(1,ordinary(1,0),Optional.empty()));
        check(!overflow.finished() && overflow.points().equals(CARRIED),"overflow rejection cannot partially pay");
        expectUnsupported(()->ledger.points().clear());
        var paid=ledger.settleRon(ron,Map.of()); expectUnsupported(()->paid.winners().clear()); expectUnsupported(()->paid.transfers().clear());
        expectState(()->paid.ledger().establishRiichi(f.declaration().state(),f.window()));
        check(ledger.points().equals(List.of(25_000,25_000,25_000,25_000)) && !ledger.finished(),"settlement preserves old snapshot");
    }

    private static void paoMultiRon() {
        for(int dealer=0;dealer<4;dealer++) for(int dragonWinner:List.of(1,2)) for(int liable=0;liable<4;liable++) {
            if(liable==dragonWinner) continue;
            int otherWinner=dragonWinner==1?2:1;
            Pool p=new Pool(); var dragon=paoHand(p,Yaku.DAISANGEN,dragonWinner,dealer,false);
            var held=new ArrayList<>(dragon.hand().concealedTiles()); held.remove(Integer.valueOf(dragon.tile()));
            HandInput waiting=new HandInput(held,dragon.hand().melds());
            List<Integer> orphanTiles=new ArrayList<>(); for(int type:ORPHANS) orphanTiles.add(p.take(type));
            HandInput kokushi=new HandInput(orphanTiles,List.of());
            List<Participant> players=new ArrayList<>();
            for(int seat=1;seat<4;seat++) {
                HandInput hand=seat==dragonWinner?waiting:seat==otherWinner?kokushi:new HandInput(p.fill(13),List.of());
                players.add(new Participant(seat,hand,context(dragon.tile(),seat,dealer,false),FuritenState.initial(),Set.of()));
            }
            var window=new DiscardResponseWindow(new Id(ROUND,1_000+assertions),0,dragon.tile(),players);
            finish(window,Set.of(dragonWinner,otherWinner));
            var result=RoundLedger.begin(ROUND,dealer,2,CARRIED,3).settleRon(window,Map.of(dragonWinner,new RoundLedger.Pao(liable,Yaku.DAISANGEN)));
            int dragonFull=dragonWinner==dealer?96_000:64_000, otherFull=otherWinner==dealer?96_000:64_000;
            var expected=new ArrayList<>(CARRIED);
            expected.set(0,expected.getFirst()-dragonFull/2-otherFull);
            if(liable==0) expected.set(0,expected.getFirst()-dragonFull/2);
            else { expected.set(0,expected.getFirst()-dragonFull/4); expected.set(liable,expected.get(liable)-dragonFull/4); }
            expected.set(dragonWinner,expected.get(dragonWinner)+dragonFull);
            expected.set(otherWinner,expected.get(otherWinner)+otherFull);
            int honbaPayer=dragonWinner==1?liable:0;
            expected.set(honbaPayer,expected.get(honbaPayer)-600); expected.set(1,expected.get(1)+3_600);
            check(result.ledger().points().equals(expected),"only closest gets extras; its own liability determines honba payer even if another winner also owes money");
            audit(result.ledger());
        }
        // Closed daisangen carries no public liability; its yaku name must not invent a payer.
        Pool p=new Pool(); List<Integer> held=new ArrayList<>();
        for(int type:new int[]{27,31,32,33}) for(int copy=0;copy<3;copy++) held.add(p.take(type));
        held.add(p.take(30)); int tile=p.take(30); held.add(tile);
        var hand=new HandInput(held,List.of());
        var decision=WinEvaluator.evaluate(hand,context(tile,1,0,true),FuritenState.initial(),WinEvaluator.Source.SELF_DRAW);
        check(decision.allowed() && decision.handValue().orElseThrow().yakumanUnits()==4,"closed hand has daisangen/all-honors/double suuankou tanki");
        var result=RoundLedger.initial(ROUND,0).settleTsumo(1,decision,Optional.empty());
        check(result.ledger().points().equals(List.of(-39_000,153_000,-7_000,-7_000)),"closed yakuman remain ordinary three-payer tsumo without supplied public liability");
        audit(result.ledger());
    }

    private static DiscardResponseWindow kokushiWindow(int dealer,int discarder,Id id) {
        Pool p=new Pool(); int tile=p.take(27); List<Participant> players=new ArrayList<>();
        for(int relative=1;relative<4;relative++) {
            int seat=(discarder+relative)%4; List<Integer> held=new ArrayList<>(); for(int type:ORPHANS) held.add(p.take(type));
            players.add(new Participant(seat,new HandInput(held,List.of()),context(tile,seat,dealer,false),FuritenState.initial(),Set.of()));
        }
        return new DiscardResponseWindow(id,discarder,tile,players);
    }
    private static DiscardResponseWindow paoWindow(Yaku component,int dealer,int discarder,int winner,Id id) {
        Pool p=new Pool(); var hand=paoHand(p,component,winner,dealer,false);
        var before=new ArrayList<>(hand.hand().concealedTiles()); before.remove(Integer.valueOf(hand.tile()));
        HandInput waiting=new HandInput(before,hand.hand().melds()); List<Participant> players=new ArrayList<>();
        for(int relative=1;relative<4;relative++) {
            int seat=(discarder+relative)%4;
            HandInput held=seat==winner?waiting:new HandInput(p.fill(13),List.of());
            players.add(new Participant(seat,held,context(hand.tile(),seat,dealer,false),FuritenState.initial(),Set.of()));
        }
        return new DiscardResponseWindow(id,discarder,hand.tile(),players);
    }
    private record Scored(HandInput hand,int tile,WinEvaluator.Decision decision) { }
    private static Scored paoHand(Pool p,Yaku component,int winner,int dealer,boolean tsumo) {
        List<Meld> melds=new ArrayList<>();
        int low=component==Yaku.DAISANGEN?31:27,high=component==Yaku.DAISANGEN?33:30;
        for(int type=low;type<=high;type++) melds.add(new Meld(Meld.Kind.PON,List.of(p.take(type),p.take(type),p.take(type))));
        List<Integer> concealed=new ArrayList<>();
        if(component==Yaku.DAISANGEN) concealed.addAll(List.of(p.take(27),p.take(27),p.take(27),p.take(30)));
        else concealed.add(p.take(5));
        int tile=p.take(component==Yaku.DAISANGEN?30:5); concealed.add(tile);
        HandInput hand=new HandInput(concealed,melds);
        var decision=WinEvaluator.evaluate(hand,context(tile,winner,dealer,tsumo),FuritenState.initial(),tsumo?WinEvaluator.Source.SELF_DRAW:WinEvaluator.Source.DISCARD);
        check(decision.allowed() && decision.handValue().orElseThrow().yakumanUnits()==2,"actual open liability fixture is two true yakuman units");
        return new Scored(hand,tile,decision);
    }
    private static WinEvaluator.Decision ordinary(int winner,int dealer) {
        Pool p=new Pool(); Meld chi=new Meld(Meld.Kind.CHI,List.of(p.take(1),p.take(2),p.take(3)));
        List<Integer> held=new ArrayList<>(); for(int type:new int[]{11,12,13,21,22,23,5,5,14,15}) held.add(p.take(type));
        int tile=p.take(13); held.add(tile); HandInput hand=new HandInput(held,List.of(chi));
        return WinEvaluator.evaluate(hand,context(tile,winner,dealer,true),FuritenState.initial(),WinEvaluator.Source.SELF_DRAW);
    }
    private static WinContext context(int tile,int seat,int dealer,boolean tsumo) {
        return new WinContext(tsumo?WinMethod.TSUMO:WinMethod.RON,tile,27+(seat-dealer+4)%4,27,Riichi.NONE,false,WinEvent.NORMAL,FirstTurn.NONE,List.of(),List.of());
    }
    private static void finish(DiscardResponseWindow window,Set<Integer> winners) {
        for(int seat:window.pendingSeats()) check(window.submit(window.id(),seat,winners.contains(seat)?Simple.RON:Simple.PASS)==Submission.ACCEPTED,"complete actual ron window");
        check(window.resolution().isPresent(),"all responses collected");
    }
    private static final class Pool {
        final Set<Integer> used=new HashSet<>();
        int take(int type) { for(int index=0;index<4;index++) { int tile=type*4+((type==4||type==13||type==22)?(index+1)%4:index); if(used.add(tile)) return tile; } throw new AssertionError("type exhausted "+type); }
        List<Integer> fill(int count) { List<Integer> held=new ArrayList<>(); for(int tile=135;held.size()<count;tile--) if(used.add(tile)) held.add(tile); return held; }
    }
    private static void audit(RoundLedger ledger) { check(ledger.points().stream().mapToLong(Integer::longValue).sum()+ledger.depositSticks()*1_000L==100_000,"four accounts plus pool conserve exactly 100000"); }
    private static void check(boolean ok,String message) { assertions++; if(!ok) throw new AssertionError(message); }
    private static void expectInvalid(Runnable action) { assertions++; try { action.run(); } catch(IllegalArgumentException expected) { return; } throw new AssertionError("Expected invalid accounts/payment"); }
    private static void expectState(Runnable action) { assertions++; try { action.run(); } catch(IllegalStateException expected) { return; } throw new AssertionError("Expected duplicate/incomplete/closed accounts"); }
    private static void expectOverflow(Runnable action) { assertions++; try { action.run(); } catch(ArithmeticException expected) { return; } throw new AssertionError("Expected checked overflow"); }
    private static void expectUnsupported(Runnable action) { assertions++; try { action.run(); } catch(UnsupportedOperationException expected) { return; } throw new AssertionError("Expected immutable receipt"); }
    private static void run(String label,Runnable test) { try { test.run(); } catch(Throwable failure) { throw new AssertionError(label,failure); } }
}
