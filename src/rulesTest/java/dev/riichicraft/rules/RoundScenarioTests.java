package dev.riichicraft.rules;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static dev.riichicraft.rules.DiscardResponseWindow.*;
import static dev.riichicraft.rules.FourPlayerRound.*;

/** Complete physical walls and public actions only: no reflection, injected hands or trusted win flags. */
public final class RoundScenarioTests {
    private static final List<UUID> PLAYERS=List.of(new UUID(71,0),new UUID(71,1),new UUID(71,2),new UUID(71,3));
    private static final UUID OBSERVER=new UUID(71,4);
    private static int assertions, scenarioSerial;
    private RoundScenarioTests() { }

    public static void main(String[] args) {
        long start=System.nanoTime();
        run("double ron cancels declaration without taking its stick",()->multipleRon(2));
        run("triple ron remains three wins and cancels declaration",()->multipleRon(3));
        run("chi kuikae and claimed-river provenance",RoundScenarioTests::chiProvenance);
        run("pon bans the remaining red copy",RoundScenarioTests::ponRedKuikae);
        run("kokushi robs an ankan before replacement and dora",RoundScenarioTests::kokushiRobbery);
        run("four actual consecutive closed kans and rinshan settlement",RoundScenarioTests::fourClosedKans);
        run("multiple owners' fourth kan permits rinshan then aborts after unclaimed discard",()->fourOwnersKans(false));
        run("ron on the fourth-kan discard beats the abort",()->fourOwnersKans(true));
        run("four declarations debit before four-riichi draw",RoundScenarioTests::fourRiichi);
        run("fourth declaration's ron beats four-riichi and its debit",RoundScenarioTests::fourthRiichiRon);
        run("called declaration establishes and loses ippatsu",RoundScenarioTests::calledDeclaration);
        run("four first winds resolve only after their response pipeline",RoundScenarioTests::fourWinds);
        run("daisangen pao follows the final dragon provider",RoundScenarioTests::pao);
        run("closed nagashi reaches real wall exhaustion",RoundScenarioTests::nagashi);
        System.out.printf("PASS round scenarios: %d assertions; %.3f s%n",assertions,(System.nanoTime()-start)/1_000_000_000.0);
    }

    private static void multipleRon(int winners) {
        Wall wall=new Wall();
        wall.hand(0,13,13,13,14,14,14,15,15,15,16,16,16,28);
        for(int seat=1;seat<4;seat++) wall.hand(seat,0,1,2,3,4,5,9,10,11,18,19,20,27);
        int east=wall.live(0,27); FourPlayerRound round=wall.round(0);
        var before=round.view(PLAYERS.getFirst());
        check(before.turnOptions().riichiDiscards().contains(east),"actual dealer hand may declare riichi by discarding east");
        check(round.discard(PLAYERS.getFirst(),before.command(),east,true)==Status.ACCEPTED,"declaration enters the ordinary response pipeline");
        var window=round.view(OBSERVER).opportunity().orElseThrow().id();
        check(round.view(OBSERVER).deposits()==0 && round.view(OBSERVER).seats().getFirst().points()==25_000,"declaration has no early charge before all responses");
        for(int seat=3;seat>=1;seat--) {
            var view=round.view(PLAYERS.get(seat));
            check(view.responses().contains(Simple.RON),"all three physical hands genuinely wait on the discarded east with a yaku");
            check(round.respond(PLAYERS.get(seat),window,seat<=winners?Simple.RON:Simple.PASS)==Status.ACCEPTED,"out-of-order independent responses accepted");
            if(seat>1) check(round.view(OBSERVER).phase()==Phase.DISCARD_RESPONSES,"no early winner settlement while another real response remains");
        }
        var end=round.view(OBSERVER).end().orElseThrow();
        check(end.kind()==EndKind.RON && end.winners().equals(winners==2?List.of(1,2):List.of(1,2,3)),"all ron winners retained in order after discarder");
        check(end.values().size()==winners && end.values().values().stream().allMatch(v->v.han()>=2),"each winner has an independently scored real hand");
        check(round.view(OBSERVER).deposits()==0 && round.serverState().riichi().getFirst().phase()==RiichiState.Phase.UNDECLARED,"ron cancels the declaration without establishing riichi or a deposit");
        int paid=end.transfers().stream().filter(t->t.fromSeat()==0).mapToInt(RoundLedger.Transfer::points).sum();
        check(round.view(OBSERVER).seats().getFirst().points()==25_000-paid,"discarder loses exactly ron payments, no extra thousand");
        round.assertConservation();
    }

    private static void chiProvenance() {
        Wall wall=new Wall();
        wall.hand(1,1,2,0,3,9,10,11,13,14,15,18,19,20);
        int called=wall.live(0,0); FourPlayerRound round=wall.round(0);
        discard(round,called,false);
        Call chi=call(round,1,Meld.Kind.CHI,List.of(1,2));
        respond(round,Map.of(1,chi));
        var view=round.view(PLAYERS.get(1));
        check(view.phase()==Phase.CALL_DISCARD && view.actorSeat()==1 && view.ownDraw().isEmpty(),"chi enters a real immediate-discard phase without drawing");
        check(view.liveTiles()==69 && view.seats().getFirst().river().getFirst().claimedBy()==1,"claim transfers ownership but retains its physical river record");
        check(view.seats().get(1).origins().equals(List.of(new MeldOrigin(0,called))),"chi retains exact provider and claimed physical copy");
        check(round.view(PLAYERS.getFirst()).ownHistory().orElseThrow().discardedTypes().contains(0),"a called-away discard stays in its owner's furiten history");
        check(view.turnOptions().discards().stream().noneMatch(t->t/4==0 || t/4==3),"23 chi 1 bans both 1 and alternate completion 4");
        int forbidden=view.ownHand().orElseThrow().concealedTiles().stream().filter(t->t/4==3).findFirst().orElseThrow();
        check(round.discard(PLAYERS.get(1),view.command(),forbidden,false)==Status.NOT_OFFERED,"forbidden alternate completion cannot be submitted");
        check(view.equals(round.view(PLAYERS.get(1))),"illegal kuikae submission leaves the full recipient view unchanged");
        discard(round,view.turnOptions().discards().stream().min(Integer::compare).orElseThrow(),false); respond(round,Map.of());
        check(round.view(OBSERVER).actorSeat()==2 && round.view(OBSERVER).phase()==Phase.DRAWN_TURN,"legal post-chi discard resumes from the caller's next seat");
    }

    private static void ponRedKuikae() {
        Wall wall=new Wall();
        wall.hand(1,4,4,4,0,1,2,9,10,11,18,19,20,27);
        int five=wall.live(0,4); FourPlayerRound round=wall.round(0);
        discard(round,five,false);
        Call pon=round.view(PLAYERS.get(1)).responses().stream().filter(c->c instanceof Call)
                .map(c->(Call)c).filter(c->c.kind()==Meld.Kind.PON && !c.handTiles().contains(16)).findFirst().orElseThrow();
        respond(round,Map.of(1,pon));
        var view=round.view(PLAYERS.get(1));
        check(view.ownHand().orElseThrow().concealedTiles().contains(16),"test deliberately leaves the physical red five in hand");
        check(!view.turnOptions().discards().contains(16),"pon forbids the remaining red copy by tile type");
        check(view.seats().get(1).origins().getFirst().claimedTileId()==five && view.seats().getFirst().river().getFirst().claimedBy()==1,"pon preserves river and meld provenance");
        discard(round,view.turnOptions().discards().stream().min(Integer::compare).orElseThrow(),false); respond(round,Map.of());
        round.assertConservation();
    }

    private static void kokushiRobbery() {
        Wall wall=new Wall();
        wall.hand(0,27,27,27,27,0,0,0,1,1,1,2,2,2);
        wall.hand(1,0,8,9,17,18,26,28,29,30,31,32,33,31);
        wall.live(0,28); FourPlayerRound round=wall.round(0);
        var view=round.view(PLAYERS.getFirst());
        var kan=view.turnOptions().kans().stream().filter(k->k.kind()==KanActions.Kind.CLOSED && k.handTiles().getFirst()/4==27).findFirst().orElseThrow();
        check(round.declareKan(PLAYERS.getFirst(),view.command(),kan)==Status.ACCEPTED,"real four-copy east ankan opens a robbery window");
        check(round.view(OBSERVER).phase()==Phase.KAN_RESPONSES && round.view(PLAYERS.get(1)).responses().contains(Simple.RON),"kokushi missing east is offered its special ankan ron");
        int robbed=round.view(OBSERVER).opportunity().orElseThrow().tileId();
        respond(round,Map.of(1,Simple.RON));
        var end=round.view(OBSERVER).end().orElseThrow();
        check(end.kind()==EndKind.RON && end.winners().equals(List.of(1)),"kokushi actually settles against the declarer");
        check(end.values().get(1).yaku().stream().anyMatch(y->y.yaku()==Yaku.KOKUSHI_MUSOU),"actual robbed hand has kokushi yaku");
        check(round.serverState().kans().establishedKans()==0 && round.serverState().wall().replacementDraws()==0,"robbed ankan establishes no kan and consumes no rinshan");
        check(round.view(OBSERVER).doraIndicators().size()==1 && round.serverState().robbedTile().orElseThrow()==robbed,"robbed physical copy is accounted for without revealing a new kan indicator");
        round.assertConservation();
    }

    private static void fourClosedKans() {
        Wall wall=new Wall();
        int[] dealer={0,1,2,3,4,5,6,8,9,10,12,13,14};
        for(int index=0;index<13;index++) wall.exact(index*4,dealer[index]);
        int[] replacement={7,11,15,109};
        for(int index=0;index<4;index++) wall.exact(122+index,replacement[index]);
        wall.exact(52,108); FourPlayerRound round=wall.round(0);
        for(int type=0;type<4;type++) {
            int target=type; var view=round.view(PLAYERS.getFirst());
            var kan=view.turnOptions().kans().stream().filter(k->k.kind()==KanActions.Kind.CLOSED && k.handTiles().getFirst()/4==target).findFirst().orElseThrow();
            check(round.declareKan(PLAYERS.getFirst(),view.command(),kan)==Status.ACCEPTED,"next physically offered closed kan accepted"); respond(round,Map.of());
            var after=round.view(PLAYERS.getFirst());
            check(after.ownDraw().orElseThrow()==replacement[type] && after.doraIndicators().size()==type+2,"correct rinshan tile and immediate closed-kan indicator consumed");
            check(after.seats().getFirst().melds().size()==type+1 && after.liveTiles()==68-type,"kan owns its four copies and replenishes dead wall from live tail");
        }
        var view=round.view(PLAYERS.getFirst());
        check(view.phase()==Phase.DRAWN_TURN && view.turnOptions().tsumo(),"one player's fourth kan does not abort and offers the real pair win");
        check(round.tsumo(PLAYERS.getFirst(),view.command())==Status.ACCEPTED,"fourth rinshan win settles through unified engine");
        var end=round.view(OBSERVER).end().orElseThrow();
        check(end.kind()==EndKind.TSUMO && end.values().get(0).yakumanUnits()==3,"double suuankou tanki plus suukantsu are three actual yakuman units");
        check(round.view(OBSERVER).seats().stream().map(PublicSeat::points).toList().equals(List.of(169_000,-23_000,-23_000,-23_000)),"dealer three-yakuman tsumo ledger is exact");
    }

    private static void fourRiichi() {
        Wall wall=new Wall();
        for(int seat=0;seat<4;seat++) wall.hand(seat,0,1,2,3,4,5,9,10,11,18,19,20,27+seat);
        for(int seat=0;seat<4;seat++) wall.live(seat,31);
        FourPlayerRound round=wall.round(0);
        for(int seat=0;seat<4;seat++) {
            var view=round.view(PLAYERS.get(seat)); int white=view.ownDraw().orElseThrow();
            check(view.turnOptions().riichiDiscards().contains(white),"each genuine closed wind-tanki hand can declare");
            discard(round,white,true); respond(round,Map.of());
            check(round.view(OBSERVER).deposits()==seat+1,"each survived declaration is charged before testing four-riichi abort");
            check(round.view(OBSERVER).seats().get(seat).points()==24_000,"declaration pays exactly one thousand");
        }
        var end=round.view(OBSERVER).end().orElseThrow();
        check(end.kind()==EndKind.FOUR_RIICHI && end.transfers().isEmpty(),"four-riichi is abortive after all declaration accounting, without noten payments");
        check(round.serverState().riichi().stream().allMatch(r->r.phase()==RiichiState.Phase.ESTABLISHED && r.establishedRiichi()==WinContext.Riichi.DOUBLE_RIICHI),"all four first uncalled declarations really establish double riichi");
        check(round.view(OBSERVER).deposits()==4 && end.dealerContinues(),"all four deposits carry and dealer continues");
    }

    private static void fourOwnersKans(boolean ron) {
        Wall wall=new Wall();
        int[] dealer={0,1,2,3,40,41,42,44,45,46,48,49,108};
        for(int index=0;index<dealer.length;index++) wall.exact(index*4,dealer[index]);
        for(int seat=1;seat<4;seat++) for(int copy=0;copy<4;copy++) wall.exact(copy*4+seat,seat*4+copy);
        int[] live={68,72,76,80}, replacement={50,84,85,109};
        for(int index=0;index<4;index++) {
            wall.exact(52+index,live[index]); wall.exact(122+index,replacement[index]);
        }
        FourPlayerRound round=wall.round(0);
        for(int seat=0;seat<4;seat++) {
            int owner=seat; var before=round.view(PLAYERS.get(seat));
            check(before.phase()==Phase.DRAWN_TURN && before.actorSeat()==seat && before.ownDraw().orElseThrow()==live[seat],
                    "each different kan owner reaches its scripted real live draw");
            var choice=before.turnOptions().kans().stream()
                    .filter(k->k.kind()==KanActions.Kind.CLOSED && k.handTiles().getFirst()/4==owner).findFirst().orElseThrow();
            check(round.declareKan(PLAYERS.get(seat),before.command(),choice)==Status.ACCEPTED,
                    "each owner's physically held four-copy closed kan is genuinely offered and established");
            respond(round,Map.of());
            var drawn=round.view(PLAYERS.get(seat)); var state=round.serverState();
            check(drawn.phase()==Phase.DRAWN_TURN && drawn.actorSeat()==seat && drawn.ownDraw().orElseThrow()==replacement[seat],
                    "even the multi-owner fourth kan receives its exact real replacement tile before aborting");
            check(drawn.doraIndicators().size()==seat+2 && state.wall().replacementDraws()==seat+1
                            && state.kans().establishedKans()==seat+1 && state.wall().deadTiles().size()==14,
                    "actual closed kan progress, visible indicators and the fourteen-tile dead wall agree after every draw");
            check(state.hands().stream().filter(h->h.melds().size()==1).count()==seat+1,
                    "fourth-kan condition derives from quads really declared by different owners");
            round.assertConservation();
            if(seat<3) { discard(round,live[seat],false); respond(round,Map.of()); }
        }
        var finalDraw=round.view(PLAYERS.get(3));
        check(round.serverState().kans().fourthStage()==KanRoundState.FourthStage.NEEDS_DISCARD
                        && finalDraw.liveTiles()==62 && finalDraw.turnOptions().discards().containsAll(List.of(80,109)),
                "fourth kan pauses at its replacement discard opportunity instead of immediately aborting or drawing for east");
        check(finalDraw.turnOptions().kans().isEmpty(),"a fifth kan cannot be declared during the fourth-kan gate");
        discard(round,ron?109:80,false);
        if(ron) {
            var offered=round.view(PLAYERS.getFirst());
            check(offered.phase()==Phase.DISCARD_RESPONSES && offered.responses().contains(Simple.RON)
                            && round.serverState().kans().fourthStage()==KanRoundState.FourthStage.RESPONSES,
                    "dealer's genuine east-tanki ron remains actionable before the multi-owner fourth-kan abort");
            check(offered.responses().stream().noneMatch(c->c instanceof Call),"only ron/pass can participate in the fourth-kan final discard");
            respond(round,Map.of(0,Simple.RON));
        } else respond(round,Map.of());
        var end=round.view(OBSERVER).end().orElseThrow(); var state=round.serverState();
        check(round.view(OBSERVER).phase()==Phase.FINISHED && state.wall().liveCount()==62
                        && state.wall().replacementDraws()==4 && state.kans().kansBySeat().equals(List.of(1,1,1,1))
                        && round.view(OBSERVER).doraIndicators().size()==5,
                "both fourth-kan outcomes retain exactly four actual replacements/five indicators without a further live draw");
        if(ron) {
            check(end.kind()==EndKind.RON && end.winners().equals(List.of(0))
                            && state.kans().fourthStage()==KanRoundState.FourthStage.WON,
                    "a real ron beats fourth-kan abort and closes the exact fourth-discard gate as won");
            check(end.values().get(0).yakumanUnits()==2
                            && end.values().get(0).yaku().stream().anyMatch(y->y.yaku()==Yaku.SUUANKOU_TANKI),
                    "east completes the dealer's actual double suuankou tanki rather than a synthetic allowed decision");
            check(round.view(OBSERVER).seats().stream().map(PublicSeat::points).toList()
                            .equals(List.of(121_000,25_000,25_000,-71_000)),
                    "fourth-kan ron is paid once by its real discarder, with no abortive payment");
        } else {
            check(end.kind()==EndKind.FOUR_KANS && end.winners().isEmpty() && end.transfers().isEmpty()
                            && state.kans().fourthStage()==KanRoundState.FourthStage.ABORTED,
                    "all three unclaimed responses resolve before the fourth-kan abort, without noten payments");
            check(round.view(OBSERVER).seats().stream().allMatch(s->s.points()==25_000),
                    "the fourth-kan abort transfers no points");
        }
        round.assertConservation();
    }

    private static void calledDeclaration() {
        Wall wall=new Wall();
        wall.hand(0,0,1,2,3,4,5,9,10,11,18,19,20,27);
        wall.hand(1,31,31,6,6,6,7,7,7,12,12,12,28,28);
        int white=wall.live(0,31); FourPlayerRound round=wall.round(0);
        discard(round,white,true); Call pon=call(round,1,Meld.Kind.PON,List.of(31,31));
        respond(round,Map.of(1,pon));
        check(round.view(OBSERVER).phase()==Phase.CALL_DISCARD && round.view(OBSERVER).deposits()==1,"called declaration still establishes and funds the pool");
        check(round.serverState().riichi().getFirst().phase()==RiichiState.Phase.ESTABLISHED && !round.serverState().riichi().getFirst().ippatsu(),"the established pon cancels the declarer's ippatsu");
        check(round.view(OBSERVER).seats().getFirst().points()==24_000 && round.view(OBSERVER).seats().getFirst().river().getFirst().declaration(),"declaration river marker persists after being called away");
    }

    private static void fourthRiichiRon() {
        Wall wall=new Wall();
        for(int seat=0;seat<4;seat++) wall.hand(seat,0,1,2,3,4,5,9,10,11,18,19,20,27+seat);
        for(int seat=0;seat<3;seat++) wall.live(seat,31);
        int east=wall.live(3,27); FourPlayerRound round=wall.round(0);
        for(int seat=0;seat<3;seat++) {
            discard(round,round.view(PLAYERS.get(seat)).ownDraw().orElseThrow(),true); respond(round,Map.of());
        }
        discard(round,east,true);
        check(round.view(OBSERVER).phase()==Phase.DISCARD_RESPONSES && round.view(OBSERVER).deposits()==3,"fourth pending declaration cannot preempt its actual ron opportunity or debit its stick");
        check(round.view(PLAYERS.getFirst()).responses().contains(Simple.RON),"first established player genuinely waits on fourth declarer's east");
        respond(round,Map.of(0,Simple.RON));
        var end=round.view(OBSERVER).end().orElseThrow();
        check(end.kind()==EndKind.RON && end.winners().equals(List.of(0)),"ron overrides the fourth-riichi abort condition");
        check(round.serverState().riichi().get(3).phase()==RiichiState.Phase.UNDECLARED,"fourth declaration never establishes after ron");
        check(end.transfers().stream().filter(t->t.kind()==RoundLedger.Kind.DEPOSIT).mapToInt(RoundLedger.Transfer::points).sum()==3_000,"winner receives only the three already-established sticks");
        int handPaid=end.transfers().stream().filter(t->t.fromSeat()==3).mapToInt(RoundLedger.Transfer::points).sum();
        check(round.view(OBSERVER).seats().get(3).points()==25_000-handPaid,"fourth declarer pays ron without an extra declaration debit");
    }

    private static void fourWinds() {
        Wall wall=new Wall();
        for(int seat=0;seat<4;seat++) wall.hand(seat,0,1,2,3,4,5,9,10,11,18,19,20,27);
        for(int seat=0;seat<4;seat++) wall.live(seat,31);
        FourPlayerRound round=wall.round(0);
        for(int seat=0;seat<4;seat++) {
            var view=round.view(PLAYERS.get(seat));
            int east=view.ownHand().orElseThrow().concealedTiles().stream().filter(t->t/4==27).findFirst().orElseThrow();
            discard(round,east,false); respond(round,Map.of());
            if(seat<3) check(round.view(OBSERVER).phase()!=Phase.FINISHED,"fewer than four first winds cannot abort the round");
        }
        var end=round.view(OBSERVER).end().orElseThrow();
        check(end.kind()==EndKind.FOUR_WINDS && end.transfers().isEmpty() && end.dealerContinues(),"four identical first winds abort after responses without payments");
        check(round.view(OBSERVER).seats().stream().allMatch(s->s.river().size()==1 && s.river().getFirst().tileId()/4==27),"abort derives from four actual physical wind discards");
    }

    private static void pao() {
        Wall wall=new Wall();
        wall.hand(1,31,31,32,32,33,33,0,0,0,1,1,1,28);
        int white=wall.live(0,31), green=wall.live(1,32), red=wall.live(2,33);
        wall.live(3,2); wall.live(4,3); wall.live(5,4); wall.live(6,28);
        FourPlayerRound round=wall.round(2);
        for(int dragon:List.of(white,green,red)) {
            int provider=round.view(OBSERVER).actorSeat();
            discard(round,dragon,false); Call pon=call(round,1,Meld.Kind.PON,List.of(dragon/4,dragon/4));
            respond(round,Map.of(1,pon));
            check(round.view(PLAYERS.get(1)).seats().get(1).origins().getLast().equals(new MeldOrigin(provider,dragon)),"actual final-dragon provider and physical claim are retained");
            var caller=round.view(PLAYERS.get(1));
            int two=caller.turnOptions().discards().stream().filter(t->t/4==1).findFirst().orElseThrow();
            discard(round,two,false); respond(round,Map.of());
        }
        for(int actor:List.of(2,3,0)) {
            var view=round.view(PLAYERS.get(actor)); check(view.actorSeat()==actor,"script reaches the expected next genuine draw");
            discard(round,view.ownDraw().orElseThrow(),false); respond(round,Map.of());
        }
        var winner=round.view(PLAYERS.get(1));
        check(winner.ownDraw().orElseThrow()/4==28 && winner.turnOptions().tsumo(),"three called dragons plus concealed triplet and south pair genuinely win");
        check(round.tsumo(PLAYERS.get(1),winner.command())==Status.ACCEPTED,"pao hand actually settles through self-draw action");
        var end=round.view(OBSERVER).end().orElseThrow();
        check(end.values().get(1).yakumanUnits()==1 && end.values().get(1).yaku().stream().anyMatch(y->y.yaku()==Yaku.DAISANGEN),"responsibility applies to exactly one daisangen component");
        check(end.transfers().stream().allMatch(t->t.fromSeat()==2 && t.toSeat()==1),"final red-dragon provider pays all hand and honba responsibility");
        check(round.view(OBSERVER).seats().stream().map(PublicSeat::points).toList().equals(List.of(25_000,57_600,-7_600,25_000)),"actual nondealer daisangen plus two honba is paid only by responsible seat");
    }

    private static void nagashi() {
        Wall wall=new Wall(); int[] terminalTypes={0,8,9,17,18,26,27,28,29,30,31,32,33};
        for(int draw=0;draw<18;draw++) wall.live(draw*4,terminalTypes[draw%terminalTypes.length]);
        wall.live(1,1); wall.live(2,2); wall.live(3,3);
        FourPlayerRound round=wall.round(0); int draws=0;
        while(round.view(OBSERVER).phase()!=Phase.FINISHED) {
            var view=round.view(PLAYERS.get(round.view(OBSERVER).actorSeat()));
            check(++draws<=70,"wall-bounded nagashi trajectory terminates");
            discard(round,view.ownDraw().orElseThrow(),false); respond(round,Map.of());
        }
        var end=round.view(OBSERVER).end().orElseThrow();
        check(draws==70 && round.view(OBSERVER).liveTiles()==0,"nagashi is decided only at actual complete wall exhaustion");
        check(end.kind()==EndKind.NAGASHI && end.winners().equals(List.of(0)),"closed dealer's actual eighteen terminal/honor discards qualify alone");
        check(round.view(OBSERVER).seats().getFirst().melds().isEmpty() && round.view(OBSERVER).seats().getFirst().river().size()==18,"test fulfills user-confirmed menzen requirement without fabricated river state");
        check(end.transfers().stream().allMatch(t->t.kind()==RoundLedger.Kind.NAGASHI) && end.transfers().stream().mapToInt(RoundLedger.Transfer::points).sum()==12_000,"nagashi pays dealer mangan without noten or honba payments");
    }

    private static void discard(FourPlayerRound round,int tile,boolean riichi) {
        var view=round.view(PLAYERS.get(round.view(OBSERVER).actorSeat()));
        check((riichi?view.turnOptions().riichiDiscards():view.turnOptions().discards()).contains(tile),"script uses an exact currently offered physical discard");
        check(round.discard(PLAYERS.get(view.actorSeat()),view.command(),tile,riichi)==Status.ACCEPTED,"offered discard is accepted by unified authority");
    }

    private static Call call(FourPlayerRound round,int seat,Meld.Kind kind,List<Integer> types) {
        var expected=types.stream().sorted().toList();
        return round.view(PLAYERS.get(seat)).responses().stream().filter(c->c instanceof Call).map(c->(Call)c)
                .filter(c->c.kind()==kind && c.handTiles().stream().map(t->t/4).sorted().toList().equals(expected)).findFirst().orElseThrow();
    }

    private static void respond(FourPlayerRound round,Map<Integer,? extends Choice> selected) {
        var publicView=round.view(OBSERVER);
        if(publicView.phase()!=Phase.DISCARD_RESPONSES && publicView.phase()!=Phase.KAN_RESPONSES) {
            check(selected.isEmpty(),"chosen responses require a genuinely open window"); return;
        }
        Id id=publicView.opportunity().orElseThrow().id(); Set<Integer> supplied=new HashSet<>();
        for(int seat=0;seat<4;seat++) {
            var view=round.view(PLAYERS.get(seat)); if(view.responses().isEmpty()) continue;
            Choice choice=selected.containsKey(seat)?selected.get(seat):Simple.PASS;
            check(view.responses().contains(choice),"script selects only this recipient's actual offered response");
            check(round.respond(PLAYERS.get(seat),id,choice)==Status.ACCEPTED,"response uses stable window identity through other seats' updates");
            supplied.add(seat);
        }
        check(supplied.containsAll(selected.keySet()),"every scripted special response really executed");
        round.assertConservation();
    }

    private static final class Wall {
        final int[] order=new int[136]; final boolean[] used=new boolean[136];
        Wall() { Arrays.fill(order,-1); }
        void exact(int position,int tile) {
            if(position<0 || position>=136 || tile<0 || tile>=136 || order[position]>=0 || used[tile]) throw new IllegalArgumentException("Duplicate wall fixture position or physical copy");
            order[position]=tile; used[tile]=true;
        }
        int allocate(int type) { for(int tile=type*4;tile<type*4+4;tile++) if(!used[tile]) return tile; throw new IllegalArgumentException("Fixture asks for fifth copy of type "+type); }
        void hand(int seat,int...types) {
            if(types.length!=13) throw new IllegalArgumentException("Each fixed initial hand needs thirteen types");
            for(int index=0;index<13;index++) exact(index*4+seat,allocate(types[index]));
        }
        int live(int draw,int type) { int tile=allocate(type); exact(52+draw,tile); return tile; }
        FourPlayerRound round(int honba) {
            int next=0; for(int position=0;position<136;position++) if(order[position]<0) {
                while(used[next]) next++; exact(position,next);
            }
            var tiles=Arrays.stream(order).boxed().toList();
            check(tiles.size()==136 && new HashSet<>(tiles).size()==136,"fixture is a complete permutation of all 136 physical tiles");
            return FourPlayerRound.ordered(new UUID(72,++scenarioSerial),PLAYERS,0,27,honba,List.of(25_000,25_000,25_000,25_000),0,tiles);
        }
    }

    private static void check(boolean condition,String message) { assertions++; if(!condition) throw new AssertionError(message); }
    private static void run(String name,Runnable action) { try { action.run(); } catch(Throwable failure) { throw new AssertionError(name,failure); } }
}
