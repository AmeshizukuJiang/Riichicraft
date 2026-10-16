package dev.riichicraft.rules;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import static dev.riichicraft.rules.WinContext.*;

/** Independent exhaustive-draw accounts, actual eligibility hands and declared-set pao provenance. */
public final class RoundDrawTests {
    private static final UUID ROUND = UUID.fromString("cbbd1628-de8a-40b7-a34c-a0303b6839b4");
    private static final List<List<Integer>> BANKS = List.of(
            List.of(25_000,25_000,25_000,25_000),
            List.of(24_000,24_000,24_000,25_000),
            List.of(-5_000,33_000,34_000,35_000));
    private static int assertions;
    private RoundDrawTests() { }

    public static void main(String[] args) {
        long start = System.nanoTime();
        run("all tenpai/nagashi sets, dealer rotations, negative scores and carried sticks",RoundDrawTests::drawAccounts);
        run("abortive draws, invalid accounts, terminal guards and immutable receipts",RoundDrawTests::accountGuards);
        run("formal tenpai ignores yaku, furiten and outside availability but excludes own fifth copies",RoundDrawTests::formalTenpai);
        run("nine terminals and uninterrupted first-river wind eligibility",RoundDrawTests::abortiveEligibility);
        run("menzen nagashi, closed kans, nonempty rivers and called-away tiles",RoundDrawTests::nagashiEligibility);
        run("all declared dragon/wind set kinds and final open-call responsibility",RoundDrawTests::responsibility);
        run("concealed progress, wrong honors and self-kan responsibility boundaries",RoundDrawTests::responsibilityBoundaries);
        System.out.printf("PASS round draw: %d assertions; %.3f s%n",assertions,(System.nanoTime()-start)/1_000_000_000.0);
    }

    private static void drawAccounts() {
        // All sixteen tenpai sets crossed with all sixteen nagashi sets. This also verifies
        // that nagashi overrides noten payments, including when its winners are not tenpai.
        for (int dealer=0;dealer<4;dealer++) for (int tenpaiMask=0;tenpaiMask<16;tenpaiMask++) {
            for (int nagashiMask=0;nagashiMask<16;nagashiMask++) for (List<Integer> bank : BANKS) {
                int sticks=bank==BANKS.getFirst()?0:3;
                Set<Integer> tenpai=seats(tenpaiMask), nagashi=seats(nagashiMask);
                var before=RoundLedger.begin(ROUND,dealer,7,bank,sticks);
                var result=before.settleExhaustive(tenpai,nagashi);
                int[] expected=bank.stream().mapToInt(Integer::intValue).toArray();
                if (nagashiMask!=0) {
                    // Independent fixed mangan table, deliberately not PointCalculator.
                    for (int winner=0;winner<4;winner++) if (nagashi.contains(winner)) {
                        for (int payer=0;payer<4;payer++) if (payer!=winner) {
                            int amount=winner==dealer || payer==dealer?4_000:2_000;
                            expected[payer]-=amount; expected[winner]+=amount;
                        }
                    }
                } else if (tenpai.size()>0 && tenpai.size()<4) {
                    for (int seat=0;seat<4;seat++) expected[seat]+=tenpai.contains(seat)
                            ?3_000/tenpai.size():-3_000/(4-tenpai.size());
                }
                check(result.ledger().points().equals(ints(expected)),"independent draw payer table for dealer="+dealer+", tenpai="+tenpaiMask+", nagashi="+nagashiMask);
                check(result.winners().equals(nagashi.stream().sorted().toList()),"only nagashi winners are listed, in stable seat order");
                check(result.ledger().finished(),"every exhaustive draw closes its current account");
                check(result.ledger().depositSticks()==sticks,"draw never collects existing deposits");
                check(result.ledger().honba()==7 && result.ledger().dealerSeat()==dealer
                        && result.ledger().roundId().equals(ROUND),"settlement retains round metadata for the match owner");
                check(result.transfers().stream().noneMatch(t->t.kind()==RoundLedger.Kind.HONBA || t.kind()==RoundLedger.Kind.DEPOSIT),"draw has no honba/deposit transfer");
                RoundLedger.Kind kind=nagashi.isEmpty()?RoundLedger.Kind.NOTEN:RoundLedger.Kind.NAGASHI;
                check(result.transfers().stream().allMatch(t->t.kind()==kind),"nagashi and noten transfers are never mixed");
                if (!nagashi.isEmpty()) {
                    check(result.transfers().size()==nagashi.size()*3,"each nagashi winner receives an independent three-payer mangan");
                    for (int winner : nagashi) {
                        check(result.transfers().stream().filter(t->t.toSeat()==winner).count()==3,"another nagashi winner still pays this winner");
                    }
                } else {
                    int expectedNoten=tenpai.isEmpty() || tenpai.size()==4?0:3_000;
                    check(result.transfers().stream().mapToInt(RoundLedger.Transfer::points).sum()==expectedNoten,"only a mixed tenpai/noten table redistributes 3000");
                    check(result.transfers().stream().allMatch(t->!tenpai.contains(t.fromSeat()) && tenpai.contains(t.toSeat())),"noten flows only from noten seats to tenpai seats");
                }
                verifyReceipt(before,result);
                check(before.points().equals(bank) && before.depositSticks()==sticks && !before.finished(),"immutable pre-draw account remains untouched");
                expect(IllegalStateException.class,()->result.ledger().settleExhaustive(tenpai,nagashi),"cannot pay the same completed account twice");
                expect(IllegalStateException.class,()->result.ledger().settleAbortive(),"exhaustive receipt cannot later become an abortive receipt");
            }
        }
    }

    private static void accountGuards() {
        for (int dealer=0;dealer<4;dealer++) for (List<Integer> bank : BANKS) {
            int sticks=bank==BANKS.getFirst()?0:3;
            var before=RoundLedger.begin(ROUND,dealer,11,bank,sticks);
            var result=before.settleAbortive();
            check(result.ledger().points().equals(bank) && result.ledger().depositSticks()==sticks,"abortive draw carries all points and deposits, including negative scores");
            check(result.winners().isEmpty() && result.transfers().isEmpty(),"abortive draw has neither winners nor point transfers");
            check(result.ledger().honba()==11,"round settlement leaves the next-round honba decision to match progression");
            verifyReceipt(before,result);
            expect(IllegalStateException.class,()->result.ledger().settleAbortive(),"abortive account cannot settle twice");
            expect(IllegalStateException.class,()->result.ledger().settleExhaustive(Set.of(0),Set.of(1)),"finished abortive account cannot receive later draw payments");
        }
        var ledger=RoundLedger.initial(ROUND,0);
        for (Set<Integer> invalid : List.of(Set.of(-1),Set.of(4),Set.of(0,4))) {
            expect(IllegalArgumentException.class,()->ledger.settleExhaustive(invalid,Set.of()),"invalid tenpai seat rejected");
            expect(IllegalArgumentException.class,()->ledger.settleExhaustive(Set.of(),invalid),"invalid nagashi seat rejected");
        }
        expect(NullPointerException.class,()->ledger.settleExhaustive(null,Set.of()),"null tenpai set rejected");
        expect(NullPointerException.class,()->ledger.settleExhaustive(Set.of(),null),"null nagashi set rejected");
        var withNull=new HashSet<Integer>(); withNull.add(null);
        expect(NullPointerException.class,()->ledger.settleExhaustive(withNull,Set.of()),"null tenpai seat rejected");
        expect(NullPointerException.class,()->ledger.settleExhaustive(Set.of(),withNull),"null nagashi seat rejected");
        check(!ledger.finished() && ledger.points().equals(List.of(25_000,25_000,25_000,25_000)),"invalid draw requests do not mutate the owner account");
        var mutableTenpai=new HashSet<>(Set.of(0)); var mutableNagashi=new HashSet<>(Set.of(1));
        var receipt=ledger.settleExhaustive(mutableTenpai,mutableNagashi);
        mutableTenpai.clear(); mutableNagashi.clear();
        check(receipt.winners().equals(List.of(1)),"receipt does not retain a mutable caller-owned winner set");
        expect(UnsupportedOperationException.class,()->receipt.winners().clear(),"winner receipt immutable");
        expect(UnsupportedOperationException.class,()->receipt.transfers().clear(),"transfer receipt immutable");
        expect(UnsupportedOperationException.class,()->receipt.ledger().points().clear(),"settled point snapshot immutable");
        expect(IllegalArgumentException.class,()->RoundLedger.begin(ROUND,0,0,List.of(25_000,25_000,25_000,25_000),1),"existing deposit sticks cannot be manufactured without deducting player points");
        expect(IllegalArgumentException.class,()->RoundLedger.begin(ROUND,0,0,List.of(25_050,24_950,25_000,25_000),0),"point-stick denominations must remain hundreds");
    }

    private static void verifyReceipt(RoundLedger before,RoundLedger.Settlement receipt) {
        long[] reconstructed=before.points().stream().mapToLong(Integer::longValue).toArray();
        for (var transfer : receipt.transfers()) {
            check(transfer.fromSeat()>=0 && transfer.fromSeat()<4 && transfer.toSeat()>=0 && transfer.toSeat()<4
                    && transfer.fromSeat()!=transfer.toSeat(),"draw receipt names two real different player seats");
            check(transfer.points()>0 && transfer.points()%100==0,"draw obligations use positive whole hundred-point sticks");
            reconstructed[transfer.fromSeat()]-=transfer.points(); reconstructed[transfer.toSeat()]+=transfer.points();
        }
        for (int seat=0;seat<4;seat++) check(reconstructed[seat]==receipt.ledger().points().get(seat),"receipt transfers reconstruct each final balance");
        check(receipt.ledger().points().stream().mapToLong(Integer::longValue).sum()+receipt.ledger().depositSticks()*1_000L==100_000L,"players and carried pool conserve exactly 100000 points");
    }

    private static void formalTenpai() {
        HandInput open=hand("456p789s55p67s",spec(Meld.Kind.CHI,"123m"));
        check(RoundDrawRules.tenpai(open),"open hand with no ordinary yaku is still formally tenpai");
        Set<Integer> waits=HandAnalyzer.waits(open);
        check(waits.equals(Set.of(22,25)),"actual no-yaku fixture waits on 5s/8s");
        for (int type : waits) {
            int winning=free(open,type); HandInput completed=plus(open,winning);
            var context=new WinContext(WinMethod.RON,winning,28,27,Riichi.NONE,false,WinEvent.NORMAL,FirstTurn.NONE,List.of(),List.of());
            var noYaku=WinEvaluator.evaluate(completed,context,FuritenState.initial(),WinEvaluator.Source.DISCARD);
            check(!noYaku.allowed() && noYaku.rejections().contains(WinEvaluator.Rejection.NO_YAKU),"formal wait is demonstrated with an actual rejected no-yaku ron");
            var discarded=FuritenState.initial().afterDiscard(winning);
            check(FuritenAnalyzer.evaluate(open,discarded).discardFuriten(),"fixture is also actually discard-furiten");
            check(RoundDrawRules.tenpai(open),"own discarded wait does not remove formal tenpai");
            List<Integer> outside=List.of(type*4,type*4+1,type*4+2,type*4+3).stream().filter(t->!open.concealedTiles().contains(t)).toList();
            check(outside.size()+open.tileCounts()[type]==4 && !outside.isEmpty(),"every physical copy not held by this waiting hand can be exhausted outside it");
            check(RoundDrawRules.tenpai(open),"a wait fully exhausted outside the hand still counts for draw tenpai");
        }
        check(RoundDrawRules.tenpai(hand("1122334455667m")),"seven-pairs structural wait counts");
        check(RoundDrawRules.tenpai(hand("19m19p19s1234567z")),"thirteen-sided kokushi wait counts");
        check(!RoundDrawRules.tenpai(hand("1111m111222333z")),"self-held four-copy single wait cannot ask for a nonexistent fifth tile");
        check(!RoundDrawRules.tenpai(hand("1111m456p789s111z")),"self-four fifth-copy rejection is independent of honors triplet composition");
        check(RoundDrawRules.tenpai(hand("5z",spec(Meld.Kind.CLOSED_KAN,"1111z"),spec(Meld.Kind.CLOSED_KAN,"2222z"),
                spec(Meld.Kind.CLOSED_KAN,"3333z"),spec(Meld.Kind.CLOSED_KAN,"4444z"))),"four declared kans still permit a real tanki on a different honor");
    }

    private static void abortiveEligibility() {
        var thirteen=hand("19m19p19s1234567z1m");
        var nine=hand("1119m119p19s11233z");
        var eight=hand("1119m119p119s1122z");
        var eightAndSimples=hand("1119m119p19s112z23m");
        check(RoundDrawRules.nineTerminals(thirteen,0,false),"all thirteen unique terminals/honors qualify on uninterrupted first draw");
        check(RoundDrawRules.nineTerminals(nine,0,false),"exactly nine distinct terminals/honors qualify");
        check(!RoundDrawRules.nineTerminals(eight,0,false),"copies do not turn eight terminal/honor kinds into nine");
        check(!RoundDrawRules.nineTerminals(eightAndSimples,0,false),"simple tile kinds do not count toward nine terminals");
        for (int discards : List.of(1,2,30)) check(!RoundDrawRules.nineTerminals(thirteen,discards,false),"nine terminals cannot be declared after own preceding discard");
        check(!RoundDrawRules.nineTerminals(thirteen,0,true),"an established call before the first draw prevents nine terminals");
        check(!RoundDrawRules.nineTerminals(hand("19m19p19s12344z",spec(Meld.Kind.CHI,"345m")),0,false),"a declared open group cannot masquerade as untouched first draw");
        check(!RoundDrawRules.nineTerminals(hand("19m19p19s12344z",spec(Meld.Kind.CLOSED_KAN,"2222m")),0,true),"a prior concealed kan also interrupts first-turn eligibility");
        expect(IllegalArgumentException.class,()->RoundDrawRules.nineTerminals(hand("19m19p19s1234567z"),0,false),"nine terminals requires actual drawn structural fourteen");
        expect(IllegalArgumentException.class,()->RoundDrawRules.nineTerminals(thirteen,-1,false),"negative preceding-discard count rejected");
        expect(IllegalArgumentException.class,()->RoundDrawRules.tenpai(thirteen),"formal tenpai requires post-discard structural thirteen");

        for (int type=27;type<=30;type++) {
            List<List<Integer>> rivers=new ArrayList<>();
            for (int seat=0;seat<4;seat++) rivers.add(List.of(type*4+seat));
            check(RoundDrawRules.fourWinds(rivers,false),"each of the four identical first-discard winds can abort");
            check(!RoundDrawRules.fourWinds(rivers,true),"an intervening established call interrupts four winds");
            for (int seat=0;seat<4;seat++) {
                var wrong=new ArrayList<>(rivers); wrong.set(seat,List.of(((type-27+1)%4+27)*4));
                check(!RoundDrawRules.fourWinds(wrong,false),"all four first discards must be the same wind");
                var pending=new ArrayList<>(rivers); pending.set(seat,List.of());
                check(!RoundDrawRules.fourWinds(pending,false),"three first winds are not yet four winds");
                var later=new ArrayList<>(rivers); later.set(seat,List.of(type*4+seat,0));
                check(!RoundDrawRules.fourWinds(later,false),"four winds cannot be inferred from a later river entry");
            }
        }
        for (int type : List.of(0,8,9,18,31,32,33)) {
            check(!RoundDrawRules.fourWinds(List.of(List.of(type*4),List.of(type*4+1),List.of(type*4+2),List.of(type*4+3)),false),"same terminal or dragon is not four winds");
        }
        expect(IllegalArgumentException.class,()->RoundDrawRules.fourWinds(List.of(List.of(),List.of(),List.of()),false),"four-wind eligibility requires four rivers");
        expect(IllegalArgumentException.class,()->RoundDrawRules.fourWinds(List.of(List.of(-1),List.of(108),List.of(109),List.of(110)),false),"invalid physical wind tile rejected");
    }

    private static void nagashiEligibility() {
        HandInput closed=hand("123m456p789s1123z");
        List<Integer> river=List.of(0,32,36,68,72,104,108,112,116,120,124,128,132);
        check(RoundDrawRules.nagashi(closed,river,false),"nonempty all-terminal/honor river qualifies for a closed hand");
        check(!RoundDrawRules.nagashi(closed,List.of(),false),"empty river cannot receive nagashi mangan");
        check(!RoundDrawRules.nagashi(closed,river,true),"one called-away discard invalidates an otherwise eligible river");
        for (int type=0;type<34;type++) {
            boolean terminal=type>=27 || type%9==0 || type%9==8;
            check(RoundDrawRules.nagashi(closed,List.of(type*4),false)==terminal,"nagashi classifies every one of the 34 tile kinds independently");
        }
        for (Meld.Kind kind : Meld.Kind.values()) {
            String declared=kind==Meld.Kind.CHI?"123m":kind==Meld.Kind.PON?"111m":"1111m";
            var declaredHand=hand("456p789s1123z",spec(kind,declared));
            check(RoundDrawRules.nagashi(declaredHand,river,false)==(kind==Meld.Kind.CLOSED_KAN),"only declared closed kans retain the required menzen status");
        }
        var fourClosed=hand("5z",spec(Meld.Kind.CLOSED_KAN,"1111m"),spec(Meld.Kind.CLOSED_KAN,"2222m"),
                spec(Meld.Kind.CLOSED_KAN,"3333m"),spec(Meld.Kind.CLOSED_KAN,"4444m"));
        check(RoundDrawRules.nagashi(fourClosed,List.of(132),false),"four concealed kans do not themselves invalidate menzen nagashi");
        expect(IllegalArgumentException.class,()->RoundDrawRules.nagashi(closed,List.of(136),false),"invalid river physical ID rejected");
    }

    private static void responsibility() {
        // Distinct declared progress kinds: prior concealed kans count, undeclared triplets do not.
        List<Meld.Kind> progress=List.of(Meld.Kind.PON,Meld.Kind.OPEN_KAN,Meld.Kind.CLOSED_KAN);
        for (boolean winds : List.of(false,true)) {
            int first=winds?27:31, count=winds?4:3;
            int combinations=winds?27:9;
            for (int target=first;target<first+count;target++) for (int encoding=0;encoding<combinations;encoding++) {
                var prior=new ArrayList<Meld>(); var pool=new Pool(); int digits=encoding;
                for (int type=first;type<first+count;type++) if (type!=target) {
                    Meld.Kind kind=progress.get(digits%3); digits/=3;
                    prior.add(new Meld(kind,pool.same(type,kind==Meld.Kind.PON?3:4)));
                }
                for (Meld.Kind finalKind : List.of(Meld.Kind.PON,Meld.Kind.OPEN_KAN)) {
                    for (int caller=0;caller<4;caller++) for (int distance=1;distance<4;distance++) {
                        int provider=(caller+distance)%4;
                        var f=callFixture(prior,target,finalKind,caller,provider);
                        Optional<RoundLedger.Pao> actual=RoundDrawRules.responsibility(f.before(),f.call());
                        check(actual.equals(Optional.of(new RoundLedger.Pao(provider,winds?Yaku.DAISUUSHII:Yaku.DAISANGEN))),"last supplied honor establishes responsibility with all prior declared set-kind combinations");
                        check(f.call().callerSeat()==caller && f.call().providerSeat()==provider,"responsibility fixture is an actual legal call with exact provider provenance");
                        check(f.before().melds().size()==count-1,"responsibility begins from prior declared progress, before the final call");
                    }
                }
            }
        }
    }

    private static void responsibilityBoundaries() {
        // The other two dragon triplets are physically present but undeclared.
        var pool=new Pool(); var held=new ArrayList<Integer>();
        held.addAll(pool.same(31,3)); held.addAll(pool.same(32,3)); held.addAll(pool.same(33,2)); held.addAll(pool.fill(5));
        var concealed=new HandInput(held,List.of());
        var call=applyCall(concealed,pool.take(33),Meld.Kind.PON,1,0);
        check(RoundDrawRules.responsibility(concealed,call).isEmpty(),"two undeclared concealed dragon triplets do not establish visible progress");

        pool=new Pool(); Meld declared=new Meld(Meld.Kind.CLOSED_KAN,pool.same(31,4)); held=new ArrayList<>();
        held.addAll(pool.same(32,3)); held.addAll(pool.same(33,2)); held.addAll(pool.fill(5));
        var partial=new HandInput(held,List.of(declared));
        check(RoundDrawRules.responsibility(partial,applyCall(partial,pool.take(33),Meld.Kind.PON,1,0)).isEmpty(),"one declared dragon plus one concealed undeclared dragon is insufficient");

        pool=new Pool(); var prior=List.of(new Meld(Meld.Kind.PON,pool.same(31,3)),new Meld(Meld.Kind.CLOSED_KAN,pool.same(32,4)));
        var wrongHonor=callFixture(prior,27,Meld.Kind.PON,2,0);
        check(RoundDrawRules.responsibility(wrongHonor.before(),wrongHonor.call()).isEmpty(),"two dragons cannot establish wind responsibility");
        var suited=callFixture(prior,4,Meld.Kind.PON,2,0);
        check(RoundDrawRules.responsibility(suited.before(),suited.call()).isEmpty(),"a suited triplet never establishes honor responsibility");

        pool=new Pool(); held=new ArrayList<>(); held.addAll(pool.same(0,1)); held.addAll(pool.same(1,1)); held.addAll(pool.fill(5));
        var chiHand=new HandInput(held,prior); int chiTile=pool.take(2);
        var chi=CallActions.apply(chiHand,chiTile,new CallActions.Context(0,1,20,1,Riichi.NONE),
                new DiscardResponseWindow.Call(Meld.Kind.CHI,List.copyOf(held.subList(0,2))));
        check(RoundDrawRules.responsibility(chiHand,chi).isEmpty(),"chi after two dragon declarations cannot establish responsibility");

        // A last self-ankan has no external supplying player. A manually constructed
        // Application must not turn this into a pao trigger merely because it is public.
        pool=new Pool(); for (Meld meld : prior) pool.used.addAll(meld.tiles());
        var selfTiles=pool.same(33,4); held=new ArrayList<>(selfTiles); held.addAll(pool.fill(4));
        var beforeSelf=new HandInput(held,prior); int draw=selfTiles.getLast();
        var proposal=KanActions.propose(beforeSelf,draw,new KanActions.Context(1,20,1,Riichi.NONE),
                new KanActions.Choice(KanActions.Kind.CLOSED,-1,selfTiles));
        var selfApplication=new CallActions.Application(proposal.handIfEstablished(),proposal.meldIfEstablished(),1,0,draw,Set.of(),Set.of());
        check(RoundDrawRules.responsibility(beforeSelf,selfApplication).isEmpty(),"the last self-declared concealed honor kan does not create a supplying responsible player");

        // Existing final dragon pon was supplied earlier. Upgrading it later is not a
        // second or retroactive pao trigger; the owner retains the original receipt.
        pool=new Pool(); var existing=new ArrayList<>(prior); for (Meld meld : prior) pool.used.addAll(meld.tiles());
        existing.add(new Meld(Meld.Kind.PON,pool.same(33,3)));
        held=new ArrayList<>(pool.fill(4)); int added=pool.take(33); held.add(added);
        var beforeAdded=new HandInput(held,existing);
        var upgrade=KanActions.propose(beforeAdded,added,new KanActions.Context(1,20,1,Riichi.NONE),
                new KanActions.Choice(KanActions.Kind.ADDED,2,List.of(added)));
        var upgradeApplication=new CallActions.Application(upgrade.handIfEstablished(),upgrade.meldIfEstablished(),1,0,added,Set.of(),Set.of());
        check(RoundDrawRules.responsibility(beforeAdded,upgradeApplication).isEmpty(),"added kan does not assign a new responsible player over the existing pon history");
    }

    private record CallFixture(HandInput before,CallActions.Application call) { }
    private static CallFixture callFixture(List<Meld> prior,int type,Meld.Kind kind,int caller,int provider) {
        var pool=new Pool(); prior.forEach(meld->pool.used.addAll(meld.tiles()));
        var held=new ArrayList<>(pool.same(type,kind==Meld.Kind.PON?2:3));
        held.addAll(pool.fill(13-3*prior.size()-held.size()));
        var before=new HandInput(held,prior); int tile=pool.take(type);
        return new CallFixture(before,applyCall(before,tile,kind,caller,provider));
    }
    private static CallActions.Application applyCall(HandInput before,int tile,Meld.Kind kind,int caller,int provider) {
        var chosen=new DiscardResponseWindow.Call(kind,before.concealedTiles().stream().filter(t->t/4==tile/4).toList());
        int kans=(int)before.melds().stream().filter(m->m.tiles().size()==4).count();
        return CallActions.apply(before,tile,new CallActions.Context(provider,caller,20,kans,Riichi.NONE),chosen);
    }
    private record Spec(Meld.Kind kind,String notation) { }
    private static Spec spec(Meld.Kind kind,String notation) { return new Spec(kind,notation); }
    private static HandInput hand(String concealed,Spec... declared) {
        var pool=new Pool(); var melds=new ArrayList<Meld>();
        for (Spec spec : declared) melds.add(new Meld(spec.kind(),pool.tiles(spec.notation())));
        return new HandInput(pool.tiles(concealed),melds);
    }
    private static HandInput plus(HandInput hand,int tile) {
        var held=new ArrayList<>(hand.concealedTiles()); held.add(tile); return new HandInput(held,hand.melds());
    }
    private static int free(HandInput hand,int type) {
        var used=new HashSet<>(hand.concealedTiles()); hand.melds().forEach(m->used.addAll(m.tiles()));
        for (int tile=type*4;tile<type*4+4;tile++) if (!used.contains(tile)) return tile;
        throw new AssertionError("No fixture copy available");
    }
    private static Set<Integer> seats(int mask) {
        var result=new HashSet<Integer>(); for (int seat=0;seat<4;seat++) if ((mask&(1<<seat))!=0) result.add(seat); return result;
    }
    private static List<Integer> ints(int[] values) { var result=new ArrayList<Integer>(); for (int value : values) result.add(value); return result; }
    private static final class Pool {
        final Set<Integer> used=new HashSet<>();
        int take(int type) {
            for (int tile=type*4;tile<type*4+4;tile++) if (used.add(tile)) return tile;
            throw new AssertionError("Fixture requests fifth copy of tile kind "+type);
        }
        List<Integer> same(int type,int count) { var result=new ArrayList<Integer>(); for (int i=0;i<count;i++) result.add(take(type)); return result; }
        List<Integer> fill(int count) {
            var result=new ArrayList<Integer>();
            for (int tile=0;result.size()<count && tile<108;tile++) if (used.add(tile)) result.add(tile);
            if (result.size()!=count) throw new AssertionError("Fixture simple-tile pool exhausted");
            return result;
        }
        List<Integer> tiles(String notation) {
            var result=new ArrayList<Integer>(); StringBuilder digits=new StringBuilder();
            for (char ch : notation.toCharArray()) {
                if (Character.isDigit(ch)) { digits.append(ch); continue; }
                int base=switch(ch) { case 'm'->0;case 'p'->9;case 's'->18;case 'z'->27;default->throw new AssertionError("Bad fixture suit"); };
                for (char rank : digits.toString().toCharArray()) result.add(take(base+rank-'1'));
                digits.setLength(0);
            }
            if (!digits.isEmpty()) throw new AssertionError("Fixture notation missing suit");
            return result;
        }
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
        try { action.run(); } catch (Throwable failure) { throw new AssertionError("Round draw group failed: "+name,failure); }
    }
}
