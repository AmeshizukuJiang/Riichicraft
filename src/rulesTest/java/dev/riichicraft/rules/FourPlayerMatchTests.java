package dev.riichicraft.rules;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static dev.riichicraft.rules.DiscardResponseWindow.*;
import static dev.riichicraft.rules.FourPlayerRound.Status;

/** Match ownership exercised exclusively through actual complete walls and authenticated public actions. */
public final class FourPlayerMatchTests {
    private static final List<UUID> PLAYERS=List.of(new UUID(81,0),new UUID(81,1),new UUID(81,2),new UUID(81,3));
    private static final UUID OUTSIDER=new UUID(81,4);
    private static int assertions, serial;
    private enum Recipe { CHILD_RON, TENHOU, MANGAN, THREE_HAN_FORTY, ONE_HAN_FIFTY, FOUR_RIICHI, DEALER_TENPAI_DRAW, NOTEN_DEALER_DRAW, NINE_TERMINALS, FOUR_KANS }
    private enum Fault { NONE, REUSED_ID, WRONG_DEALER, WRONG_WIND, WRONG_HONBA, WRONG_POINTS, WRONG_DEPOSITS, WRONG_PLAYERS, ALREADY_PLAYED }
    private FourPlayerMatchTests() { }

    public static void main(String[] args) {
        long start=System.nanoTime();
        for(int initial=0;initial<4;initial++) rotationFollowsInitialDealer(initial);
        dealerWinThenChildWin();
        exhaustiveRotationCarriesHonba();
        stableVotesAndAbsenceEpoch();
        for(Fault fault:Fault.values()) if(fault!=Fault.NONE) rejectedFactoryPreservesPaidResult(fault);
        declaredKansDoNotSurviveNextRound();
        realPointsReachZeroThenBustWithRemainingDeposits();
        System.out.printf("PASS four-player match: %d assertions; %.3f s%n",assertions,(System.nanoTime()-start)/1_000_000_000.0);
    }

    private static void rotationFollowsInitialDealer(int initial) {
        Factory factory=new Factory(Recipe.CHILD_RON);
        FourPlayerMatch match=match(MatchRules.Format.SOUTH,initial,factory);
        for(int index=0;index<5;index++) {
            var before=match.view(null); var round=before.round();
            check(before.roundIndex()==index && round.dealerSeat()==(initial+index)%4 && round.roundWind()==27+index/4,
                    "round index follows the original dealer, including physical-seat wrap before the next wind");
            var setup=factory.setups.getLast();
            check(setup.roundIndex()==index && setup.dealerSeat()==round.dealerSeat() && setup.roundWind()==round.roundWind(),
                    "the actual factory receives the match's canonical index/dealer/wind");
            verifyFreshRound(match);
            var stale=before.command(); UUID oldRound=round.roundId();
            sameAfterReject(match,Status.NOT_A_PLAYER,()->match.discard(OUTSIDER,stale,0,false));
            sameAfterReject(match,Status.WRONG_ROUND,()->match.discard(PLAYERS.get(round.actorSeat()),
                    new FourPlayerMatch.Command(UUID.randomUUID(),oldRound,before.revision()),0,false));
            sameAfterReject(match,Status.WRONG_ROUND,()->match.discard(PLAYERS.get(round.actorSeat()),
                    new FourPlayerMatch.Command(before.matchId(),UUID.randomUUID(),before.revision()),0,false));
            sameAfterReject(match,Status.STALE_REVISION,()->match.discard(PLAYERS.get(round.actorSeat()),
                    new FourPlayerMatch.Command(before.matchId(),oldRound,before.revision()-1),0,false));
            sameAfterReject(match,Status.WRONG_PHASE,()->match.confirmNext(PLAYERS.getFirst(),before.continueId()));
            sameAfterReject(match,Status.NOT_OFFERED,()->match.discard(PLAYERS.get(round.actorSeat()),before.command(),-1,false));
            Id response=finishChildRon(match);
            var paid=match.view(null);
            check(paid.phase()==FourPlayerMatch.Phase.ROUND_RESULT && paid.round().end().orElseThrow().kind()==FourPlayerRound.EndKind.RON,
                    "a real child ron produces an intermission before any new deal");
            check(paid.round().end().orElseThrow().winners().equals(List.of((round.dealerSeat()+1)%4)),"the real next physical seat won");
            List<Integer> balances=paid.round().seats().stream().map(FourPlayerRound.PublicSeat::points).toList();
            confirmAll(match,factory);
            check(match.view(null).roundIndex()==index+1 && match.view(null).round().honba()==0,
                    "child-only ron rotates the dealer and resets honba");
            check(match.serverState().ledger().points().equals(balances) && match.view(null).round().deposits()==0,
                    "new actual ledger inherits exactly the preceding settled accounts");
            sameAfterReject(match,Status.WRONG_ROUND,()->match.discard(PLAYERS.get(round.dealerSeat()),stale,0,false));
            sameAfterReject(match,Status.WRONG_ROUND,()->match.respond(PLAYERS.get((round.dealerSeat()+1)%4),response,Simple.RON));
        }
        check(match.view(null).round().roundWind()==28 && factory.setups.size()==6,
                "five rotations create exactly six owned rounds and reach South independently of initial physical seat");
    }

    private static void stableVotesAndAbsenceEpoch() {
        Factory factory=new Factory(Recipe.NINE_TERMINALS);
        FourPlayerMatch match=match(MatchRules.Format.EAST,2,factory);
        finishNineTerminals(match);
        var paid=match.view(null); var oldToken=paid.continueId(); var roundReceipt=paid.round();
        check(match.clearNextConfirmations(),"a real absence invalidates the continue token even before anyone voted");
        check(match.view(null).continueEpoch()==oldToken.epoch()+1 && match.view(null).confirmations().equals(List.of(false,false,false,false)),
                "zero existing votes still require a fresh epoch after absence");
        check(match.view(null).round().equals(roundReceipt),"clearing intermission votes cannot alter the paid round");
        sameAfterReject(match,Status.WRONG_WINDOW,()->match.confirmNext(PLAYERS.getFirst(),oldToken));
        var token=match.view(null).continueId();
        sameAfterReject(match,Status.NOT_A_PLAYER,()->match.confirmNext(OUTSIDER,token));
        sameAfterReject(match,Status.NOT_A_PLAYER,()->match.confirmNext(null,token));
        sameAfterReject(match,Status.WRONG_ROUND,()->match.confirmNext(PLAYERS.getFirst(),null));
        sameAfterReject(match,Status.WRONG_ROUND,()->match.confirmNext(PLAYERS.getFirst(),
                new FourPlayerMatch.ContinueId(UUID.randomUUID(),token.roundId(),token.epoch())));
        sameAfterReject(match,Status.WRONG_ROUND,()->match.confirmNext(PLAYERS.getFirst(),
                new FourPlayerMatch.ContinueId(token.matchId(),UUID.randomUUID(),token.epoch())));
        for(int seat:List.of(3,1,0)) check(match.confirmNext(PLAYERS.get(seat),token)==Status.ACCEPTED,"three independent votes share a stable continue ID");
        sameAfterReject(match,Status.ALREADY_RESPONDED,()->match.confirmNext(PLAYERS.get(3),token));
        check(factory.setups.size()==1 && match.view(null).phase()==FourPlayerMatch.Phase.ROUND_RESULT,
                "three votes never redeal or discard the old result");
        check(match.clearNextConfirmations(),"a new absence clears three accepted confirmations");
        check(match.view(null).confirmations().stream().noneMatch(Boolean::booleanValue),"all votes are cleared together");
        sameAfterReject(match,Status.WRONG_WINDOW,()->match.confirmNext(PLAYERS.get(2),token));
        var renewed=match.view(null).continueId();
        for(int seat:List.of(1,2,3)) check(match.confirmNext(PLAYERS.get(seat),renewed)==Status.ACCEPTED,"renewed votes tolerate other players' revision changes");
        check(match.confirmNext(PLAYERS.getFirst(),renewed)==Status.ACCEPTED,"fourth renewed participant alone triggers a new deal");
        check(factory.setups.size()==2 && match.view(null).roundIndex()==0 && match.view(null).round().honba()==1,
                "nine-terminals repeat preserves index/dealer and advances honba once");
        sameAfterReject(match,Status.WRONG_ROUND,()->match.confirmNext(PLAYERS.getFirst(),renewed));
        var playing=match.view(null);
        check(!match.clearNextConfirmations() && playing.equals(match.view(null)),"playing rounds cannot be changed by intermission vote cleanup");
        verifyFreshRound(match);
    }

    private static void dealerWinThenChildWin() {
        Factory factory=new Factory(Recipe.TENHOU,Recipe.CHILD_RON,Recipe.NINE_TERMINALS);
        FourPlayerMatch match=match(MatchRules.Format.SOUTH,1,factory);
        var own=match.view(PLAYERS.get(1));
        check(own.round().turnOptions().tsumo() && match.tsumo(PLAYERS.get(1),own.command())==Status.ACCEPTED,
                "real dealer opening win enters the first continuing result");
        confirmAll(match,factory);
        check(match.view(null).roundIndex()==0 && match.view(null).round().honba()==1 && match.view(null).round().dealerSeat()==1,
                "the next real round after dealer win keeps the same physical dealer and gains one honba");
        finishChildRon(match); var paid=match.view(null);
        check(paid.round().end().orElseThrow().transfers().stream().filter(t->t.kind()==RoundLedger.Kind.HONBA)
                        .mapToInt(RoundLedger.Transfer::points).sum()==300,
                "child ron really collects the one honba carried from the preceding dealer win");
        var balances=match.serverState().ledger().points(); confirmAll(match,factory);
        check(match.view(null).roundIndex()==1 && match.view(null).round().dealerSeat()==2 && match.view(null).round().honba()==0
                        && match.serverState().ledger().points().equals(balances),
                "child-only win after an actual dealer repeat rotates once and clears the next honba without resetting accounts");
        verifyFreshRound(match);
    }

    private static void exhaustiveRotationCarriesHonba() {
        Factory factory=new Factory(Recipe.NOTEN_DEALER_DRAW,Recipe.NINE_TERMINALS);
        FourPlayerMatch match=match(MatchRules.Format.SOUTH,3,factory);
        finishExhaustive(match); var paid=match.view(null);
        check(paid.phase()==FourPlayerMatch.Phase.ROUND_RESULT && paid.round().end().orElseThrow().kind()==FourPlayerRound.EndKind.EXHAUSTIVE
                        && paid.round().end().orElseThrow().tenpai().equals(Set.of(0)),
                "real exhausted wall has only the child tenpai and therefore rotates the noten dealer");
        var balances=match.serverState().ledger().points(); confirmAll(match,factory);
        check(match.view(null).roundIndex()==1 && match.view(null).round().dealerSeat()==0 && match.view(null).round().roundWind()==27
                        && match.view(null).round().honba()==1 && match.serverState().ledger().points().equals(balances),
                "exhaustive rotation preserves the incremented honba and initial wind even across physical seat three to zero");
        verifyFreshRound(match);
    }

    private static void rejectedFactoryPreservesPaidResult(Fault fault) {
        Factory factory=new Factory(Recipe.NINE_TERMINALS);
        FourPlayerMatch match=match(MatchRules.Format.SOUTH,0,factory);
        finishNineTerminals(match); var token=match.view(null).continueId();
        for(int seat=0;seat<3;seat++) check(match.confirmNext(PLAYERS.get(seat),token)==Status.ACCEPTED,"valid first three confirmation votes accepted");
        var before=allViews(match); var secret=match.serverState(); factory.fault=fault;
        try { match.confirmNext(PLAYERS.get(3),token); throw new AssertionError("Expected rejected factory: "+fault); }
        catch(IllegalArgumentException expected) { assertions++; }
        check(before.equals(allViews(match)) && secret.equals(match.serverState()),"rejected "+fault+" factory cannot lose the paid result, old wall, version or three votes");
        check(match.view(null).confirmations().equals(List.of(true,true,true,false)),"invalid prepared round never consumes the fourth confirmation");
        factory.fault=Fault.NONE;
        check(match.confirmNext(PLAYERS.get(3),token)==Status.ACCEPTED,"the same fourth voter can retry after an external factory repair");
        check(factory.setups.size()==3 && match.view(null).phase()==FourPlayerMatch.Phase.PLAYING,
                "only the repaired fresh round becomes owned, despite an earlier rejected preparation");
        verifyFreshRound(match);
    }

    private static void declaredKansDoNotSurviveNextRound() {
        Factory factory=new Factory(Recipe.FOUR_KANS,Recipe.NINE_TERMINALS);
        FourPlayerMatch match=match(MatchRules.Format.SOUTH,0,factory);
        int[] live={68,72,76,80}, replacement={50,84,85,109};
        for(int seat=0;seat<4;seat++) {
            int target=seat; var view=match.view(PLAYERS.get(seat));
            check(view.round().actorSeat()==seat && view.round().ownDraw().orElseThrow()==live[seat],"real four-kan fixture reaches the correct owner's original draw");
            var kan=view.round().turnOptions().kans().stream().filter(k->k.kind()==KanActions.Kind.CLOSED && k.handTiles().getFirst()/4==target).findFirst().orElseThrow();
            check(match.declareKan(PLAYERS.get(seat),view.command(),kan)==Status.ACCEPTED,"match authenticates each physically offered closed kan");
            passResponses(match,-1);
            check(match.view(PLAYERS.get(seat)).round().ownDraw().orElseThrow()==replacement[seat],"exact replacement draw is owned by the match's current round");
            discard(match,live[seat],false); passResponses(match,-1);
        }
        check(match.view(null).round().end().orElseThrow().kind()==FourPlayerRound.EndKind.FOUR_KANS
                        && match.serverState().kans().establishedKans()==4 && match.view(null).round().doraIndicators().size()==5,
                "a genuinely dirty four-kan round settles before creating its replacement");
        confirmAll(match,factory); verifyFreshRound(match);
        check(match.view(null).roundIndex()==0 && match.view(null).round().honba()==1,
                "four-kan abort repeats dealer/honba while all kan/river/furiten state resets");
    }

    private static void realPointsReachZeroThenBustWithRemainingDeposits() {
        Factory factory=new Factory(Recipe.TENHOU,Recipe.MANGAN,Recipe.THREE_HAN_FORTY,Recipe.ONE_HAN_FIFTY,
                Recipe.FOUR_RIICHI,Recipe.DEALER_TENPAI_DRAW);
        FourPlayerMatch match=match(MatchRules.Format.SOUTH,0,factory);
        int[] expected={9_000,4_900,2_100,1_000};
        int[] base={16_000,4_000,2_600,800};
        for(int hand=0;hand<4;hand++) {
            if(hand>0) secondTurnDealerDraw(match);
            var view=match.view(PLAYERS.getFirst());
            check(view.round().turnOptions().tsumo(),"scripted actual complete hand has a legally scored self draw");
            check(match.tsumo(PLAYERS.getFirst(),view.command())==Status.ACCEPTED,"dealer self draw settles only through the match authority");
            var paid=match.view(null);
            check(paid.phase()==FourPlayerMatch.Phase.ROUND_RESULT && paid.roundIndex()==0,
                    "dealer wins repeat below scheduled last regardless of another player reaching zero later");
            check(paid.round().end().orElseThrow().values().get(0).points().payment().fromNonDealer()==base[hand],
                    "independent fixture expects exact underlying hand payment before carried honba");
            for(int seat=1;seat<4;seat++) check(paid.round().seats().get(seat).points()==expected[hand],
                    "real dealer win/honba debits reach the independently calculated running balance");
            confirmAll(match,factory);
            check(match.view(null).roundIndex()==0 && match.view(null).round().honba()==hand+1,
                    "each actual dealer win adds one honba, preserves points, and does not rotate");
            verifyFreshRound(match);
        }
        for(int seat=0;seat<4;seat++) {
            var view=match.view(PLAYERS.get(seat)); int white=view.round().ownDraw().orElseThrow();
            check(view.round().actorSeat()==seat && white/4==31 && view.round().turnOptions().riichiDiscards().contains(white),
                    "each real first-turn tenpai hand can afford its exact declaration, including exactly 1000 points");
            discard(match,white,true); passResponses(match,-1);
        }
        var zero=match.view(null);
        check(zero.phase()==FourPlayerMatch.Phase.ROUND_RESULT && zero.round().end().orElseThrow().kind()==FourPlayerRound.EndKind.FOUR_RIICHI,
                "four real funded declarations leave a zero-score intermission instead of busting");
        check(zero.round().deposits()==4 && zero.round().seats().stream().map(FourPlayerRound.PublicSeat::points).toList().equals(List.of(96_000,0,0,0)),
                "legal exactly-1000 riichi payments reach zero and conserve four actual sticks");
        check(match.serverState().riichi().stream().allMatch(r->r.phase()==RiichiState.Phase.ESTABLISHED),"old declarations genuinely exist before the next deal");
        confirmAll(match,factory); verifyFreshRound(match);
        check(match.view(null).round().honba()==5 && match.view(null).round().deposits()==4,
                "abortive next round inherits all five honba and four unclaimed deposits");
        finishExhaustive(match);
        var finished=match.view(null); var receipt=finished.round().end().orElseThrow();
        check(receipt.kind()==FourPlayerRound.EndKind.EXHAUSTIVE && receipt.tenpai().equals(Set.of(0)),
                "actual complete wall ends with precisely the dealer formally tenpai");
        check(finished.phase()==FourPlayerMatch.Phase.MATCH_RESULT && finished.result().orElseThrow().reason()==MatchRules.Reason.NEGATIVE_SCORE,
                "real noten payment from zero ends the entire match after the complete settlement");
        check(match.serverState().ledger().points().equals(List.of(99_000,-1_000,-1_000,-1_000)) && match.serverState().ledger().depositSticks()==4,
                "immutable final round receipt retains its original balances and unclaimed deposit pool");
        var standings=finished.result().orElseThrow();
        check(standings.depositRecipient()==0 && standings.depositPoints()==4_000,
                "match finalization assigns the four retained sticks once to the pre-award leader");
        check(standings.standings().stream().map(MatchRules.Standing::points).toList().equals(List.of(103_000,-1_000,-1_000,-1_000)),
                "final standings account for the actual supply pool without modifying the closed round receipt");
        check(standings.standings().stream().map(MatchRules.Standing::seat).toList().equals(List.of(0,1,2,3)),
                "equal losing totals retain initial-dealer seat priority");
        check(standings.standings().stream().mapToInt(MatchRules.Standing::totalTenths).sum()==0
                        && standings.standings().getFirst().totalTenths()==930,
                "25000 return and placement uma are exact tenths and conserve final zero-sum score");
        int calls=factory.setups.size(); var command=finished.command(); var token=finished.continueId();
        sameAfterReject(match,Status.WRONG_PHASE,()->match.confirmNext(PLAYERS.getFirst(),token));
        sameAfterReject(match,Status.WRONG_PHASE,()->match.discard(PLAYERS.getFirst(),command,0,false));
        sameAfterReject(match,Status.WRONG_PHASE,()->match.tsumo(PLAYERS.getFirst(),command));
        sameAfterReject(match,Status.WRONG_PHASE,()->match.respond(PLAYERS.get(1),new Id(command.roundId(),0),Simple.RON));
        check(!match.clearNextConfirmations() && finished.equals(match.view(null)),"terminal match ignores vote cleanup and never repeats final deposit payment");
        for(int i=0;i<4;i++) check(finished.equals(match.view(null)),"repeated result reads are immutable and never award deposits again");
        check(factory.setups.size()==calls && calls==6,"terminal match never asks its factory for another wall");
    }

    private static FourPlayerMatch match(MatchRules.Format format,int initial,Factory factory) {
        return new FourPlayerMatch(new UUID(82,++serial),PLAYERS,format,initial,factory);
    }
    private static Id finishChildRon(FourPlayerMatch match) {
        int dealer=match.view(null).round().dealerSeat(), winner=(dealer+1)%4;
        discard(match,match.view(PLAYERS.get(dealer)).round().ownDraw().orElseThrow(),false);
        Id id=match.view(null).round().opportunity().orElseThrow().id();
        check(match.view(PLAYERS.get(winner)).round().responses().contains(Simple.RON),"fixed child's real hand is offered ron on the dealer's actual discard");
        passResponses(match,winner); return id;
    }
    private static void finishNineTerminals(FourPlayerMatch match) {
        var view=match.view(null); int actor=view.round().actorSeat(); var own=match.view(PLAYERS.get(actor));
        check(own.round().turnOptions().nineTerminals(),"fixed initial hand really qualifies for nine terminals");
        check(match.nineTerminals(PLAYERS.get(actor),own.command())==Status.ACCEPTED,"nine terminals is submitted through the current match command");
    }
    private static void secondTurnDealerDraw(FourPlayerMatch match) {
        int dealer=match.view(null).round().dealerSeat();
        for(int relative=0;relative<4;relative++) {
            int actor=(dealer+relative)%4; var view=match.view(PLAYERS.get(actor));
            check(view.round().actorSeat()==actor && view.round().phase()==FourPlayerRound.Phase.DRAWN_TURN,
                    "ordinary dealer self-win fixture traverses all four genuine first draws");
            discard(match,view.round().ownDraw().orElseThrow(),false); passResponses(match,-1);
        }
    }
    private static void finishExhaustive(FourPlayerMatch match) {
        int draws=0;
        while(match.view(null).phase()==FourPlayerMatch.Phase.PLAYING) {
            var round=match.view(null).round();
            if(round.phase()==FourPlayerRound.Phase.DISCARD_RESPONSES || round.phase()==FourPlayerRound.Phase.KAN_RESPONSES) passResponses(match,-1);
            else {
                check(++draws<=70,"complete exhaustive wall has a finite real draw budget");
                var own=match.view(PLAYERS.get(round.actorSeat())).round(); discard(match,own.ownDraw().orElseThrow(),false);
            }
        }
        check(draws==70 && match.view(null).round().liveTiles()==0,"all seventy actual live draws were discarded before exhaustive settlement");
    }
    private static void discard(FourPlayerMatch match,int tile,boolean riichi) {
        int actor=match.view(null).round().actorSeat(); var view=match.view(PLAYERS.get(actor));
        check((riichi?view.round().turnOptions().riichiDiscards():view.round().turnOptions().discards()).contains(tile),"script submits only a current exact physical discard");
        check(match.discard(PLAYERS.get(actor),view.command(),tile,riichi)==Status.ACCEPTED,"match forwards the authenticated legal discard exactly once");
    }
    private static void passResponses(FourPlayerMatch match,int winner) {
        var view=match.view(null);
        if(view.round().opportunity().isEmpty()) { check(winner<0,"a scripted ron requires a real pending opportunity"); return; }
        Id id=view.round().opportunity().orElseThrow().id(); Set<Integer> supplied=new HashSet<>();
        for(int seat=0;seat<4;seat++) {
            var options=match.view(PLAYERS.get(seat)).round().responses(); if(options.isEmpty()) continue;
            Choice choice=seat==winner?Simple.RON:Simple.PASS;
            check(options.contains(choice),"response fixture uses the recipient's actual legal choice");
            check(match.respond(PLAYERS.get(seat),id,choice)==Status.ACCEPTED,"all independent responses remain bound to one stable real window"); supplied.add(seat);
        }
        check(winner<0 || supplied.contains(winner),"the expected ron was actually submitted, never inferred from an end flag");
    }
    private static void confirmAll(FourPlayerMatch match,Factory factory) {
        var paid=match.view(null); check(paid.phase()==FourPlayerMatch.Phase.ROUND_RESULT,"only a completed continuing round can collect four confirmations");
        var token=paid.continueId(); int calls=factory.setups.size(); var frozen=match.serverState();
        for(int seat=0;seat<4;seat++) {
            check(match.confirmNext(PLAYERS.get(seat),token)==Status.ACCEPTED,"same paid-result token survives the other participants' confirmations");
            if(seat<3) check(factory.setups.size()==calls && match.view(null).round().equals(paid.round()) && frozen.equals(match.serverState()),
                    "first three votes preserve every round tile/account and cannot call the factory");
        }
        check(factory.setups.size()==calls+1 && match.view(null).phase()==FourPlayerMatch.Phase.PLAYING,
                "exactly the fourth vote creates exactly one next owned round");
        check(match.view(null).confirmations().stream().noneMatch(Boolean::booleanValue),"new round contains no intermission votes");
    }
    private static void verifyFreshRound(FourPlayerMatch match) {
        var view=match.view(null); var state=match.serverState();
        check(view.phase()==FourPlayerMatch.Phase.PLAYING && view.round().phase()==FourPlayerRound.Phase.DRAWN_TURN
                        && view.round().revision()==0 && view.round().liveTiles()==69,
                "new round starts after one real dealer draw, with its own fresh action revision");
        check(view.round().seats().stream().allMatch(s->s.river().isEmpty() && s.melds().isEmpty())
                        && state.riichi().stream().allMatch(r->r.phase()==RiichiState.Phase.UNDECLARED)
                        && state.histories().stream().allMatch(h->h.discardedTypes().isEmpty() && !h.temporary() && !h.riichiPass()),
                "new round clears all private/public riichi, missed-ron, river and declared-group history");
        check(state.kans().establishedKans()==0 && state.wall().replacementDraws()==0 && view.round().doraIndicators().size()==1,
                "new physical wall resets kan progress and exposes exactly one initial indicator");
        check(view.round().ownHand().isEmpty() && view.round().ownHistory().isEmpty() && view.round().responses().isEmpty(),"match spectator view contains no private hand/history/action choices");
        Set<Integer> tiles=new HashSet<>(); int count=0;
        for(HandInput hand:state.hands()) { tiles.addAll(hand.concealedTiles()); count+=hand.concealedTiles().size(); }
        tiles.addAll(state.wall().liveTiles()); tiles.addAll(state.wall().deadTiles()); count+=state.wall().liveCount()+state.wall().deadTiles().size();
        check(count==136 && tiles.size()==136 && state.wall().deadTiles().size()==14,"each new round really owns all 136 unique copies and fourteen dead-wall tiles");
    }
    private static List<FourPlayerMatch.View> allViews(FourPlayerMatch match) {
        List<FourPlayerMatch.View> views=new ArrayList<>(); views.add(match.view(null)); for(UUID player:PLAYERS) views.add(match.view(player)); return List.copyOf(views);
    }
    private static void sameAfterReject(FourPlayerMatch match,Status expected,java.util.function.Supplier<Status> action) {
        var before=allViews(match); var state=match.serverState(); check(action.get()==expected,"rejected action has expected match/round/phase authority: "+expected);
        check(before.equals(allViews(match)) && state.equals(match.serverState()),"rejected action changes no recipient view, vote, version or private round state");
    }

    private static final class Factory implements FourPlayerMatch.RoundFactory {
        final List<Recipe> recipes; final List<FourPlayerMatch.RoundSetup> setups=new ArrayList<>();
        Fault fault=Fault.NONE; UUID firstRound;
        Factory(Recipe... recipes) { this.recipes=List.of(recipes); }
        @Override public FourPlayerRound create(FourPlayerMatch.RoundSetup setup) {
            setups.add(setup); Recipe recipe=recipes.get(Math.min(setups.size()-1,recipes.size()-1));
            UUID id=fault==Fault.REUSED_ID?firstRound:new UUID(83,++serial); if(firstRound==null) firstRound=id;
            var actual=setup;
            if(fault!=Fault.NONE && fault!=Fault.REUSED_ID && fault!=Fault.ALREADY_PLAYED) {
                int dealer=fault==Fault.WRONG_DEALER?(setup.dealerSeat()+1)%4:setup.dealerSeat();
                int wind=fault==Fault.WRONG_WIND?setup.roundWind()+1:setup.roundWind();
                int honba=fault==Fault.WRONG_HONBA?setup.honba()+1:setup.honba();
                List<Integer> points=new ArrayList<>(setup.points()); int deposits=setup.deposits(); var players=setup.players();
                if(fault==Fault.WRONG_POINTS) { points.set(0,points.get(0)-100); points.set(1,points.get(1)+100); }
                if(fault==Fault.WRONG_DEPOSITS) { deposits++; points.set(0,points.get(0)-1_000); }
                if(fault==Fault.WRONG_PLAYERS) players=List.of(PLAYERS.get(1),PLAYERS.getFirst(),PLAYERS.get(2),PLAYERS.get(3));
                actual=new FourPlayerMatch.RoundSetup(setup.matchId(),players,setup.roundIndex(),dealer,wind,honba,points,deposits);
            }
            Wall wall=new Wall(actual); configure(wall,recipe); FourPlayerRound round=wall.round(id);
            if(fault==Fault.ALREADY_PLAYED) {
                int actor=round.view(null).actorSeat(); var own=round.view(actual.players().get(actor));
                check(round.discard(actual.players().get(actor),own.command(),own.ownDraw().orElseThrow(),false)==Status.ACCEPTED,
                        "rejected factory fixture is itself advanced through a real action");
            }
            return round;
        }
    }
    private static void configure(Wall wall,Recipe recipe) {
        int dealer=wall.setup.dealerSeat();
        switch(recipe) {
            case CHILD_RON -> { wall.hand((dealer+1)%4,0,1,2,3,4,5,9,10,11,18,19,20,27); wall.live(0,27); wall.indicator(31); }
            case TENHOU -> { wall.hand(dealer,0,1,2,3,4,5,15,16,17,18,19,20,27); wall.live(0,27); wall.indicator(31); }
            case MANGAN -> { wall.hand(dealer,1,2,3,10,11,12,19,20,21,14,15,22,22); delayedWin(wall,16,31); }
            case THREE_HAN_FORTY -> { wall.hand(dealer,1,1,1,12,12,12,23,24,25,2,4,14,14); delayedWin(wall,3,1); }
            case ONE_HAN_FIFTY -> { wall.hand(dealer,0,0,0,3,4,5,17,17,17,20,22,27,27); delayedWin(wall,21,31); }
            case FOUR_RIICHI -> {
                for(int seat=0;seat<4;seat++) wall.hand(seat,0,1,2,3,4,5,9,10,11,18,19,20,27+seat);
                for(int turn=0;turn<4;turn++) wall.live(turn,31);
            }
            case DEALER_TENPAI_DRAW,NOTEN_DEALER_DRAW -> {
                int tenpai=recipe==Recipe.DEALER_TENPAI_DRAW?dealer:(dealer+1)%4;
                wall.hand(tenpai,0,1,2,3,4,5,9,10,11,18,19,20,27);
                for(int seat=0;seat<4;seat++) if(seat!=tenpai) wall.hand(seat,27,28,29,30,31,32,33,10,12,14,16,19,21);
                for(int draw=0;draw<4;draw++) wall.live(draw,draw+1);
            }
            case NINE_TERMINALS -> { wall.hand(dealer,0,8,9,17,18,26,27,28,29,30,31,32,33); wall.live(0,1); }
            case FOUR_KANS -> {
                int[] held={0,1,2,3,40,41,42,44,45,46,48,49,108};
                for(int i=0;i<13;i++) wall.exact(i*4,held[i]);
                for(int seat=1;seat<4;seat++) for(int copy=0;copy<4;copy++) wall.exact(copy*4+seat,seat*4+copy);
                int[] live={68,72,76,80}, replacement={50,84,85,109};
                for(int i=0;i<4;i++) { wall.exact(52+i,live[i]); wall.exact(122+i,replacement[i]); }
            }
        }
    }
    private static void delayedWin(Wall wall,int winner,int indicator) {
        wall.live(0,31); wall.live(1,32); wall.live(2,33); wall.live(3,26); wall.live(4,winner); wall.indicator(indicator);
    }
    private static final class Wall {
        final FourPlayerMatch.RoundSetup setup; final int[] order=new int[136]; final boolean[] used=new boolean[136];
        Wall(FourPlayerMatch.RoundSetup setup) { this.setup=setup; Arrays.fill(order,-1); }
        void exact(int position,int tile) {
            if(position<0 || position>=136 || tile<0 || tile>=136 || order[position]>=0 || used[tile]) throw new IllegalArgumentException("Invalid complete-wall fixture placement");
            order[position]=tile; used[tile]=true;
        }
        int take(int type) {
            // Explicitly choose ordinary fives first, so independent hand-payment fixtures have no accidental red bonus.
            int[] copies=type==4 || type==13 || type==22?new int[]{1,2,3,0}:new int[]{0,1,2,3};
            for(int copy:copies) if(!used[type*4+copy]) return type*4+copy;
            throw new IllegalArgumentException("Fixture asks for fifth copy of type "+type);
        }
        void hand(int seat,int...types) {
            if(types.length!=13) throw new IllegalArgumentException("Initial fixture needs thirteen concealed tiles");
            int relative=(seat-setup.dealerSeat()+4)%4;
            for(int index=0;index<13;index++) exact(index*4+relative,take(types[index]));
        }
        void live(int offset,int type) { exact(52+offset,take(type)); }
        void indicator(int type) { exact(126,take(type)); }
        FourPlayerRound round(UUID id) {
            int next=0; for(int position=0;position<136;position++) if(order[position]<0) {
                while(used[next]) next++; exact(position,next);
            }
            var tiles=Arrays.stream(order).boxed().toList(); check(tiles.size()==136 && new HashSet<>(tiles).size()==136,"factory recipe creates a real complete 136-copy permutation");
            return FourPlayerRound.ordered(id,setup.players(),setup.dealerSeat(),setup.roundWind(),setup.honba(),setup.points(),setup.deposits(),tiles);
        }
    }
    private static void check(boolean condition,String message) { assertions++; if(!condition) throw new AssertionError(message); }
}
