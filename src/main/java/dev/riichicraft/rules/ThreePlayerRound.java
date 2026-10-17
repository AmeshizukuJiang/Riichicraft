package dev.riichicraft.rules;

import dev.riichicraft.game.TileCatalog;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import static dev.riichicraft.rules.DiscardResponseWindow.*;
import static dev.riichicraft.rules.WinContext.*;

/**
 * Single-threaded authority for ONE three-player round. The adapter supplies the authenticated
 * connection's UUID, never a client-supplied seat. No Minecraft classes, clocks or network IO.
 * Views contain recipient-only private information; serverState is strictly server diagnostics.
 */
public final class ThreePlayerRound {
    public enum Phase { DRAWN_TURN, CALL_DISCARD, DISCARD_RESPONSES, KAN_RESPONSES, FINISHED, NORTH_RESPONSES }
    public enum Status { ACCEPTED, WRONG_ROUND, NOT_A_PLAYER, STALE_REVISION, WRONG_PHASE, NOT_YOUR_TURN,
        NOT_OFFERED, WRONG_WINDOW, ALREADY_RESPONDED }
    public enum EndKind { RON, TSUMO, EXHAUSTIVE, NAGASHI, NINE_TERMINALS, FOUR_WINDS, FOUR_RIICHI, FOUR_KANS }
    public record Command(UUID roundId, long revision) { }
    public record RiverTile(int tileId, boolean declaration, boolean tsumogiri, int claimedBy) { }
    /** A closed kan has provider/claimed tile -1; an added kan retains its original pon provenance. */
    public record MeldOrigin(int providerSeat, int claimedTileId) { }
    public record TurnOptions(Set<Integer> discards, Set<Integer> riichiDiscards, Set<KanActions.Choice> kans,
                              Set<Integer> kitas, boolean tsumo, boolean nineTerminals) {
        public TurnOptions { discards=Set.copyOf(discards); riichiDiscards=Set.copyOf(riichiDiscards); kans=Set.copyOf(kans); kitas=Set.copyOf(kitas); }
        public static TurnOptions none() { return new TurnOptions(Set.of(),Set.of(),Set.of(),Set.of(),false,false); }
    }
    public record Opportunity(Id id, int actorSeat, int tileId, boolean kan) { }
    public record PublicSeat(UUID playerId, int seatWind, int concealedCount, List<Meld> melds,
                             List<MeldOrigin> origins, List<RiverTile> river, int points, ThreePlayerRiichi.Phase riichi, List<Integer> norths) {
        public PublicSeat { melds=List.copyOf(melds); origins=List.copyOf(origins); river=List.copyOf(river); norths=List.copyOf(norths); }
    }
    public record End(EndKind kind, List<Integer> winners, Set<Integer> tenpai, Map<Integer,ThreePlayerScoreResult> values,
                      List<ThreePlayerLedger.Transfer> transfers, boolean dealerContinues) {
        public End { winners=List.copyOf(winners); tenpai=Set.copyOf(tenpai); values=Map.copyOf(values); transfers=List.copyOf(transfers); }
    }
    public record View(UUID roundId, long revision, Phase phase, int actorSeat, int dealerSeat, int roundWind,
                       int liveTiles, int honba, int deposits, List<Integer> doraIndicators, List<PublicSeat> seats,
                       int ownSeat, Optional<HandInput> ownHand, Optional<Integer> ownDraw,
                       Optional<FuritenState> ownHistory, TurnOptions turnOptions,
                       Optional<Opportunity> opportunity, Set<Choice> responses, Optional<End> end) {
        public View { doraIndicators=List.copyOf(doraIndicators); seats=List.copyOf(seats); responses=Set.copyOf(responses); }
        public Command command() { return new Command(roundId,revision); }
    }
    /** Contains every secret. Never use this type as a client payload. All members are immutable snapshots. */
    public record ServerState(List<HandInput> hands, List<FuritenState> histories, List<ThreePlayerRiichi> riichi,
                              ThreePlayerWall wall, ThreePlayerKanState kans, ThreePlayerLedger ledger, Optional<Integer> robbedTile, List<List<Integer>> norths) {
        public ServerState { hands=List.copyOf(hands); histories=List.copyOf(histories); riichi=List.copyOf(riichi); norths=norths.stream().map(List::copyOf).toList(); }
    }

    private final UUID roundId;
    private final List<UUID> players;
    private final int dealerSeat, roundWind;
    // Server-only replay material. This is deliberately absent from View/ServerState and every network DTO.
    private final ThreePlayerArchiveCodec.RoundStart archiveStart;
    private final List<ThreePlayerArchiveCodec.Action> acceptedActions=new ArrayList<>();
    private final List<HandInput> hands=new ArrayList<>();
    private final List<FuritenState> histories=new ArrayList<>();
    private final List<ThreePlayerRiichi> riichi=new ArrayList<>();
    private final List<List<RiverTile>> rivers=new ArrayList<>();
    private final List<List<MeldOrigin>> origins=new ArrayList<>();
    private final List<List<Integer>> norths=new ArrayList<>();
    private int pendingNorth=-1;
    private final Map<Integer,ThreePlayerLedger.Pao> responsibility=new HashMap<>();
    private ThreePlayerWall wall;
    private ThreePlayerLedger ledger;
    private ThreePlayerKanState kans;
    private Phase phase;
    private int actor, drawnTile;
    private ThreePlayerWall.Source drawSource;
    private boolean anyCallOccurred;
    private long revision, nextSerial;
    private ThreePlayerDiscardWindow discards;
    private ThreePlayerKanWindow kanResponses;
    private CallActions.Application immediateCall;
    private End end;
    private Integer robbedTile;

    public static ThreePlayerRound shuffled(UUID roundId, List<UUID> players, int dealerSeat, int roundWind,
                                           int honba, List<Integer> points, int deposits, long seed) {
        return new ThreePlayerRound(players,roundWind,ThreePlayerLedger.begin(roundId,dealerSeat,honba,points,deposits),ThreePlayerWall.shuffled(seed));
    }

    /** Fixed complete permutation for deterministic verification/replays; not a client action. */
    public static ThreePlayerRound ordered(UUID roundId, List<UUID> players, int dealerSeat, int roundWind,
                                          int honba, List<Integer> points, int deposits, List<Integer> order) {
        return new ThreePlayerRound(players,roundWind,ThreePlayerLedger.begin(roundId,dealerSeat,honba,points,deposits),ThreePlayerWall.deal(order));
    }

    private ThreePlayerRound(List<UUID> players, int roundWind, ThreePlayerLedger ledger, ThreePlayerWall.Deal deal) {
        this.players=List.copyOf(players);
        if (this.players.size()!=3 || new HashSet<>(players).size()!=3 || roundWind<27 || roundWind>29) {
            throw new IllegalArgumentException("Three different players and a valid round wind required");
        }
        this.roundId=ledger.roundId(); this.ledger=ledger; dealerSeat=ledger.dealerSeat(); this.roundWind=roundWind;
        // Deal sorts each initial hand. Interleaving those sorted hands is the canonical, equivalent
        // initial permutation; the live/dead order and every physical dealt owner remain exact.
        List<Integer> canonicalOrder=new ArrayList<>();
        for(int index=0;index<13;index++) for(int seat=0;seat<3;seat++) canonicalOrder.add(deal.hands().get(seat).concealedTiles().get(index));
        canonicalOrder.addAll(deal.wall().liveTiles()); canonicalOrder.addAll(deal.wall().deadTiles());
        archiveStart=new ThreePlayerArchiveCodec.RoundStart(roundId,this.players,dealerSeat,roundWind,ledger.honba(),ledger.points(),ledger.depositSticks(),canonicalOrder);
        kans=ThreePlayerKanState.initial(roundId); wall=deal.wall();
        for (int seat=0; seat<3; seat++) {
            hands.add(deal.hands().get((seat-dealerSeat+3)%3));
            histories.add(FuritenState.initial()); riichi.add(ThreePlayerRiichi.initial());
            rivers.add(new ArrayList<>()); origins.add(new ArrayList<>()); norths.add(new ArrayList<>());
        }
        draw(dealerSeat,false); assertConservation();
    }

    public View view(UUID recipient) {
        int own=recipient==null?-1:players.indexOf(recipient);
        List<PublicSeat> publicSeats=new ArrayList<>();
        for (int seat=0; seat<3; seat++) publicSeats.add(new PublicSeat(players.get(seat),seatWind(seat),
                hands.get(seat).concealedTiles().size(),hands.get(seat).melds(),origins.get(seat),rivers.get(seat),
                ledger.points().get(seat),riichi.get(seat).phase(),norths.get(seat)));
        Optional<Opportunity> opportunity=Optional.empty(); Set<Choice> responses=Set.of();
        if (phase==Phase.DISCARD_RESPONSES || phase==Phase.NORTH_RESPONSES) {
            opportunity=Optional.of(new Opportunity(discards.id(),actor,discards.discardedTileId(),false));
            if (own>=0 && discards.pendingSeats().contains(own)) responses=discards.offers(own);
        } else if (phase==Phase.KAN_RESPONSES) {
            opportunity=Optional.of(new Opportunity(kanResponses.id(),actor,kanResponses.proposal().claimableTileId(),true));
            if (own>=0 && kanResponses.pendingSeats().contains(own)) responses=new HashSet<>(kanResponses.offers(own));
        }
        return new View(roundId,revision,phase,actor,dealerSeat,roundWind,wall.liveCount(),ledger.honba(),ledger.depositSticks(),
                wall.doraIndicators(kans.revealedIndicators()),publicSeats,own,
                own<0?Optional.empty():Optional.of(hands.get(own)),
                own==actor && phase==Phase.DRAWN_TURN?Optional.of(drawnTile):Optional.empty(),
                own<0?Optional.empty():Optional.of(histories.get(own)),own==actor?turnOptions():TurnOptions.none(),
                opportunity,responses,Optional.ofNullable(end));
    }

    public ServerState serverState() { return new ServerState(hands,histories,riichi,wall,kans,ledger,Optional.ofNullable(robbedTile),norths); }

    /** Exact physical discard; declaration and normal discard share one response/settlement pipeline. */
    public Status discard(UUID player, Command command, int tile, boolean declareRiichi) {
        Status check=checkTurn(player,command); if (check!=Status.ACCEPTED) return check;
        int actingSeat=actor;
        TurnOptions options=turnOptions();
        if (!(declareRiichi?options.riichiDiscards():options.discards()).contains(tile)) return Status.NOT_OFFERED;
        Id id=freshWindow(); DiscardActions.Result discarded;
        if (declareRiichi) {
            var declaration=riichi.get(actor).declare(hands.get(actor),drawnTile,riichiContext(),histories.get(actor),tile,id,norths.get(actor));
            riichi.set(actor,declaration.state()); discarded=declaration.discard();
        } else {
            discarded=phase==Phase.CALL_DISCARD?DiscardActions.afterCall(immediateCall,histories.get(actor),tile)
                    :DiscardActions.afterDraw(hands.get(actor),drawnTile,riichi.get(actor).establishedRiichi(),histories.get(actor),tile);
            riichi.set(actor,riichi.get(actor).afterOwnDiscard());
        }
        hands.set(actor,discarded.hand()); histories.set(actor,discarded.history());
        rivers.get(actor).add(new RiverTile(tile,declareRiichi,discarded.tsumogiri(),-1)); immediateCall=null;
        kans=kans.onDiscardOpening(actor,id);
        boolean ronOnly=kans.fourthStage()==ThreePlayerKanState.FourthStage.RESPONSES;
        WinEvent event=drawSource==ThreePlayerWall.Source.LAST_LIVE?WinEvent.HOUTEI:WinEvent.NORMAL;
        var participants=new ArrayList<ThreePlayerDiscardWindow.Participant>();
        for (int seat=0; seat<3; seat++) if (seat!=actor) {
            Set<Call> calls=ronOnly?Set.of():CallActions.options(hands.get(seat),tile,callContext(seat)).stream().filter(c->c.kind()!=Meld.Kind.CHI && (c.kind()!=Meld.Kind.OPEN_KAN || wall.canDrawReplacement())).collect(java.util.stream.Collectors.toUnmodifiableSet());
            participants.add(new ThreePlayerDiscardWindow.Participant(seat,hands.get(seat),context(seat,WinMethod.RON,tile,event),histories.get(seat),calls));
        }
        discards=new ThreePlayerDiscardWindow(id,actor,tile,participants); phase=Phase.DISCARD_RESPONSES;
        resolveDiscardIfComplete(); return accepted(new ThreePlayerArchiveCodec.Action(declareRiichi?ThreePlayerArchiveCodec.ActionKind.RIICHI:ThreePlayerArchiveCodec.ActionKind.DISCARD,actingSeat,tile,-1,-1,List.of()));
    }

    /** Responses are bound to a stable window, independent of other players' revision increments. */
    public Status respond(UUID player, Id window, Choice choice) {
        int seat=player==null?-1:players.indexOf(player); if (seat<0) return Status.NOT_A_PLAYER;
        if (window==null || !roundId.equals(window.roundId())) return Status.WRONG_ROUND;
        Submission submitted;
        if (phase==Phase.DISCARD_RESPONSES || phase==Phase.NORTH_RESPONSES) submitted=discards.submit(window,seat,choice);
        else if (phase==Phase.KAN_RESPONSES) submitted=kanResponses.submit(window,seat,choice);
        else return Status.WRONG_PHASE;
        if (submitted!=Submission.ACCEPTED) return switch(submitted) {
            case WRONG_WINDOW -> Status.WRONG_WINDOW;
            case ALREADY_RESPONDED -> Status.ALREADY_RESPONDED;
            case NOT_A_RESPONDER -> Status.NOT_YOUR_TURN;
            case CLOSED -> Status.WRONG_PHASE;
            default -> Status.NOT_OFFERED;
        };
        if (phase==Phase.DISCARD_RESPONSES) resolveDiscardIfComplete();
        else if(phase==Phase.NORTH_RESPONSES) resolveNorthIfComplete(); else resolveKanIfComplete();
        return accepted(ThreePlayerArchiveCodec.Action.response(seat,window.serial(),choice));
    }

    public Status declareKan(UUID player, Command command, KanActions.Choice choice) {
        Status check=checkTurn(player,command); if (check!=Status.ACCEPTED) return check;
        int actingSeat=actor;
        if (phase!=Phase.DRAWN_TURN) return Status.WRONG_PHASE;
        if (choice==null || !turnOptions().kans().contains(choice)) return Status.NOT_OFFERED;
        var proposal=KanActions.propose(hands.get(actor),drawnTile,kanContext(),choice,h->ThreePlayerHands.waits(h,norths.get(actor)));
        kans=kans.beforeSelfKanDeclaration(actor); // Flush only a previous kan's deferred indicator.
        var participants=new ArrayList<ThreePlayerKanWindow.Participant>();
        for (int seat=0; seat<3; seat++) if (seat!=actor) participants.add(new ThreePlayerKanWindow.Participant(seat,
                hands.get(seat),context(seat,WinMethod.RON,proposal.claimableTileId(),WinEvent.CHANKAN),histories.get(seat)));
        kanResponses=new ThreePlayerKanWindow(freshWindow(),proposal,participants); phase=Phase.KAN_RESPONSES;
        resolveKanIfComplete(); return accepted(new ThreePlayerArchiveCodec.Action(choice.kind()==KanActions.Kind.CLOSED?ThreePlayerArchiveCodec.ActionKind.CLOSED_KAN:ThreePlayerArchiveCodec.ActionKind.ADDED_KAN,actingSeat,-1,-1,choice.meldIndex(),choice.handTiles()));
    }

    public Status kita(UUID player, Command command, int tile) {
        Status check=checkTurn(player,command); if(check!=Status.ACCEPTED) return check;
        if(!turnOptions().kitas().contains(tile)) return Status.NOT_OFFERED;
        int declarer=actor; pendingNorth=tile;
        var participants=new ArrayList<ThreePlayerDiscardWindow.Participant>();
        for(int seat=0;seat<3;seat++) if(seat!=actor) participants.add(new ThreePlayerDiscardWindow.Participant(seat,
                hands.get(seat),northContext(seat,tile,ThreePlayerWinContext.NorthEvent.ROBBERY),histories.get(seat),Set.of()));
        discards=new ThreePlayerDiscardWindow(freshWindow(),actor,tile,participants); phase=Phase.NORTH_RESPONSES;
        resolveNorthIfComplete();
        return accepted(new ThreePlayerArchiveCodec.Action(ThreePlayerArchiveCodec.ActionKind.KITA,declarer,tile,-1,-1,List.of()));
    }

    private Set<Integer> kitaOptions() {
        if(phase!=Phase.DRAWN_TURN || !wall.canDrawReplacement() || kans.fourthStage()!=ThreePlayerKanState.FourthStage.NONE) return Set.of();
        return hands.get(actor).concealedTiles().stream().filter(tile->tile/4==30
                && (riichi.get(actor).establishedRiichi()==Riichi.NONE || tile==drawnTile))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private void resolveNorthIfComplete() {
        if(discards.resolution().isEmpty()) return;
        var result=discards.resolution().orElseThrow(); result.historiesAfter().forEach(histories::set);
        var held=new ArrayList<>(hands.get(actor).concealedTiles()); held.remove(Integer.valueOf(pendingNorth));
        hands.set(actor,new HandInput(held,hands.get(actor).melds()));
        if(result.outcome()==Outcome.RON) {
            robbedTile=pendingNorth;
            finish(EndKind.RON,ledger.settleRon(discards,paoFor(result.ronValues())),Set.of(),result.ronValues());
        } else {
            norths.get(actor).add(pendingNorth); callEstablished(); discards=null;
            drawReplacement(actor,ThreePlayerWall.Source.KITA);
        }
        pendingNorth=-1;
    }

    public Status tsumo(UUID player, Command command) {
        Status check=checkTurn(player,command); if (check!=Status.ACCEPTED) return check;
        if (phase!=Phase.DRAWN_TURN) return Status.WRONG_PHASE;
        var decision=selfDecision(); if (!decision.allowed()) return Status.NOT_OFFERED;
        ThreePlayerScoreResult value=decision.handValue().orElseThrow();
        var settlement=ledger.settleTsumo(actor,decision,paoFor(actor,value));
        if (drawSource==ThreePlayerWall.Source.KAN) kans=kans.afterRinshanWin();
        finish(EndKind.TSUMO,settlement,Set.of(),Map.of(actor,value)); return accepted(ThreePlayerArchiveCodec.Action.turn(ThreePlayerArchiveCodec.ActionKind.TSUMO,actor));
    }

    public Status nineTerminals(UUID player, Command command) {
        Status check=checkTurn(player,command); if (check!=Status.ACCEPTED) return check;
        if (!turnOptions().nineTerminals()) return Status.NOT_OFFERED;
        finishAbort(EndKind.NINE_TERMINALS); return accepted(ThreePlayerArchiveCodec.Action.turn(ThreePlayerArchiveCodec.ActionKind.NINE_TERMINALS,actor));
    }

    private TurnOptions turnOptions() {
        if (phase==Phase.CALL_DISCARD) return new TurnOptions(immediateCall.legalDiscardTiles(),Set.of(),Set.of(),Set.of(),false,false);
        if (phase!=Phase.DRAWN_TURN) return TurnOptions.none();
        return new TurnOptions(DiscardActions.afterDrawOptions(hands.get(actor),drawnTile,riichi.get(actor).establishedRiichi()),
                riichi.get(actor).declarationOptions(hands.get(actor),drawnTile,riichiContext(),norths.get(actor)).keySet(),
                wall.canDrawReplacement() && kans.fourthStage()==ThreePlayerKanState.FourthStage.NONE?KanActions.options(hands.get(actor),drawnTile,kanContext(),h->ThreePlayerHands.waits(h,norths.get(actor))):Set.of(),kitaOptions(),selfDecision().allowed(),
                RoundDrawRules.nineTerminals(hands.get(actor),rivers.get(actor).size(),anyCallOccurred));
    }

    private void resolveDiscardIfComplete() {
        if (discards.resolution().isEmpty()) return;
        var result=discards.resolution().orElseThrow(); result.historiesAfter().forEach(histories::set);
        if (riichi.get(actor).phase()==ThreePlayerRiichi.Phase.PENDING) {
            var entry=ledger.establishRiichi(riichi.get(actor),discards); ledger=entry.ledger(); riichi.set(actor,entry.state());
        }
        if (kans.fourthStage()==ThreePlayerKanState.FourthStage.RESPONSES) kans=kans.resolveFourthDiscard(discards);
        if (result.outcome()==Outcome.RON) {
            var settlement=ledger.settleRon(discards,paoFor(result.ronValues()));
            finish(EndKind.RON,settlement,Set.of(),result.ronValues()); return;
        }
        if (kans.fourthStage()==ThreePlayerKanState.FourthStage.ABORTED) { finishAbort(EndKind.FOUR_KANS); return; }

        if (result.outcome()==Outcome.CALL) {
            var selected=result.call().orElseThrow(); int caller=selected.seat();
            var application=CallActions.apply(hands.get(caller),discards.discardedTileId(),callContext(caller),selected.choice());
            RoundDrawRules.responsibility(hands.get(caller),application).ifPresent(p->responsibility.put(caller,new ThreePlayerLedger.Pao(p.liableSeat(),p.component())));
            hands.set(caller,application.hand());
            origins.get(caller).add(new MeldOrigin(actor,discards.discardedTileId()));
            var river=rivers.get(actor); var last=river.removeLast();
            river.add(new RiverTile(last.tileId(),last.declaration(),last.tsumogiri(),caller));
            callEstablished(); actor=caller; discards=null;
            if (application.needsReplacementDraw()) {
                kans=kans.afterEstablished(actor,Meld.Kind.OPEN_KAN); draw(actor,true);
            } else { immediateCall=application; phase=Phase.CALL_DISCARD; drawSource=null; }
        } else {
            discards=null;
            if (wall.liveCount()==0) finishExhaustive(); else draw((actor+1)%3,false);
        }
    }

    private void resolveKanIfComplete() {
        if (kanResponses.resolution().isEmpty()) return;
        var result=kanResponses.resolution().orElseThrow(); var proposal=kanResponses.proposal();
        result.historiesAfter().forEach(histories::set); hands.set(actor,result.declarerHand());
        if (result.robbed()) {
            robbedTile=proposal.claimableTileId();
            var settlement=ledger.settleRon(kanResponses,paoFor(result.ronValues()));
            finish(EndKind.RON,settlement,Set.of(),result.ronValues()); return;
        }
        if (proposal.choice().kind()==KanActions.Kind.CLOSED) origins.get(actor).add(new MeldOrigin(-1,-1));
        callEstablished(); kans=kans.afterEstablished(actor,proposal.meldIfEstablished().kind()); kanResponses=null;
        draw(actor,true);
    }

    private void draw(int seat, boolean replacement) {
        takeDraw(seat,replacement?wall.drawReplacement(ThreePlayerWall.Source.KAN):wall.drawLive());
    }
    private void drawReplacement(int seat,ThreePlayerWall.Source source) { takeDraw(seat,wall.drawReplacement(source)); }
    private void takeDraw(int seat,ThreePlayerWall.Draw drawn) {
        wall=drawn.wall(); actor=seat;
        drawnTile=drawn.tileId(); drawSource=drawn.source();
        var held=new ArrayList<>(hands.get(seat).concealedTiles()); held.add(drawnTile);
        hands.set(seat,new HandInput(held.stream().sorted().toList(),hands.get(seat).melds()));
        histories.set(seat,histories.get(seat).afterDraw()); riichi.set(seat,riichi.get(seat).afterOwnDraw());
        phase=Phase.DRAWN_TURN;
    }

    private void callEstablished() {
        anyCallOccurred=true;
        for (int seat=0; seat<3; seat++) riichi.set(seat,riichi.get(seat).afterCallEstablished());
    }

    private void finishExhaustive() {
        Set<Integer> tenpai=new HashSet<>(), nagashi=new HashSet<>();
        for (int seat=0; seat<3; seat++) {
            if (!ThreePlayerHands.waits(hands.get(seat),norths.get(seat)).isEmpty()) tenpai.add(seat);
            if (RoundDrawRules.nagashi(hands.get(seat),riverIds().get(seat),rivers.get(seat).stream().anyMatch(r->r.claimedBy()>=0))) nagashi.add(seat);
        }
        finish(nagashi.isEmpty()?EndKind.EXHAUSTIVE:EndKind.NAGASHI,ledger.settleExhaustive(tenpai,nagashi),tenpai,Map.of());
    }

    private void finishAbort(EndKind kind) { finish(kind,ledger.settleAbortive(),Set.of(),Map.of()); }

    private void finish(EndKind kind, ThreePlayerLedger.Settlement settlement, Set<Integer> tenpai, Map<Integer,ThreePlayerScoreResult> values) {
        ledger=settlement.ledger();
        boolean dealerContinues=switch(kind) {
            case RON, TSUMO -> settlement.winners().contains(dealerSeat);
            case EXHAUSTIVE, NAGASHI -> tenpai.contains(dealerSeat);
            default -> true;
        };
        end=new End(kind,settlement.winners(),tenpai,values,settlement.transfers(),dealerContinues); phase=Phase.FINISHED;
        discards=null; kanResponses=null; immediateCall=null;
    }

    private ThreePlayerWinEvaluator.Decision selfDecision() {
        WinEvent event=switch(drawSource) { case KAN -> WinEvent.RINSHAN; case LAST_LIVE -> WinEvent.HAITEI; case LIVE,KITA -> WinEvent.NORMAL; };
        var facts=drawSource==ThreePlayerWall.Source.KITA?northContext(actor,drawnTile,ThreePlayerWinContext.NorthEvent.SUPPLEMENT):context(actor,WinMethod.TSUMO,drawnTile,event);
        return ThreePlayerWinEvaluator.evaluate(hands.get(actor),facts,histories.get(actor),ThreePlayerWinEvaluator.Source.SELF_DRAW);
    }

    private ThreePlayerWinContext context(int seat, WinMethod method, int tile, WinEvent event) {
        FirstTurn first=FirstTurn.NONE;
        if (method==WinMethod.TSUMO && event==WinEvent.NORMAL && rivers.get(seat).isEmpty() && !anyCallOccurred) {
            first=seat==dealerSeat?FirstTurn.TENHOU:FirstTurn.CHIIHOU;
        }
        return new ThreePlayerWinContext(new WinContext(method,tile,seatWind(seat),roundWind,riichi.get(seat).establishedRiichi(),riichi.get(seat).ippatsu(),
                event,first,wall.doraIndicators(kans.revealedIndicators()),wall.uraIndicators(kans.revealedIndicators())),norths.get(seat),ThreePlayerWinContext.NorthEvent.NONE);
    }
    private ThreePlayerWinContext northContext(int seat,int tile,ThreePlayerWinContext.NorthEvent northEvent) {
        return new ThreePlayerWinContext(new WinContext(northEvent==ThreePlayerWinContext.NorthEvent.SUPPLEMENT?WinMethod.TSUMO:WinMethod.RON,
                tile,seatWind(seat),roundWind,riichi.get(seat).establishedRiichi(),riichi.get(seat).ippatsu(),WinEvent.NORMAL,
                FirstTurn.NONE,wall.doraIndicators(kans.revealedIndicators()),wall.uraIndicators(kans.revealedIndicators())),norths.get(seat),northEvent);
    }

    private ThreePlayerRiichi.Context riichiContext() { return new ThreePlayerRiichi.Context(actor,ledger.points().get(actor),wall.liveCount(),rivers.get(actor).size(),anyCallOccurred); }
    private KanActions.Context kanContext() { return new KanActions.Context(actor,wall.liveCount(),kans.establishedKans(),riichi.get(actor).establishedRiichi()); }
    private CallActions.Context callContext(int caller) { return new CallActions.Context(actor,caller,wall.liveCount(),kans.establishedKans(),riichi.get(caller).establishedRiichi()); }
    private int seatWind(int seat) { return 27+(seat-dealerSeat+3)%3; }
    private Id freshWindow() { return new Id(roundId,nextSerial++); }
    private List<List<Integer>> riverIds() { return rivers.stream().map(r->r.stream().map(RiverTile::tileId).toList()).toList(); }
    private Optional<ThreePlayerLedger.Pao> paoFor(int seat, ThreePlayerScoreResult value) {
        return Optional.ofNullable(responsibility.get(seat)).filter(p->value.yaku().stream().anyMatch(y->y.yaku()==p.component()));
    }
    private Map<Integer,ThreePlayerLedger.Pao> paoFor(Map<Integer,ThreePlayerScoreResult> values) {
        Map<Integer,ThreePlayerLedger.Pao> result=new HashMap<>(); values.forEach((seat,value)->paoFor(seat,value).ifPresent(p->result.put(seat,p))); return result;
    }

    private Status checkTurn(UUID player, Command command) {
        int seat=player==null?-1:players.indexOf(player); if (seat<0) return Status.NOT_A_PLAYER;
        if (command==null || !roundId.equals(command.roundId())) return Status.WRONG_ROUND;
        if (command.revision()!=revision) return Status.STALE_REVISION;
        if (phase!=Phase.DRAWN_TURN && phase!=Phase.CALL_DISCARD) return Status.WRONG_PHASE;
        if (seat!=actor) return Status.NOT_YOUR_TURN;
        return Status.ACCEPTED;
    }
    private Status accepted(ThreePlayerArchiveCodec.Action action) {
        acceptedActions.add(action); revision=Math.incrementExact(revision); assertConservation(); return Status.ACCEPTED;
    }

    /** Established added-kan tile, derived from accepted history without changing archive state. */
    public int addedKanTile(int seat, int meldIndex) {
        if (seat < 0 || seat >= 3 || meldIndex < 0 || meldIndex >= hands.get(seat).melds().size()) throw new IllegalArgumentException("meld index");
        if (hands.get(seat).melds().get(meldIndex).kind() != Meld.Kind.OPEN_KAN) return -1;
        return acceptedActions.stream().filter(a -> a.kind() == ThreePlayerArchiveCodec.ActionKind.ADDED_KAN && a.seat() == seat && a.meldIndex() == meldIndex)
                .map(a -> a.tiles().getFirst()).findFirst().orElse(-1);
    }

    /** Frozen server archive; callers must capture it on the owning server thread. */
    ThreePlayerArchiveCodec.RoundArchive archive() {
        return new ThreePlayerArchiveCodec.RoundArchive(archiveStart,acceptedActions,archiveFingerprint());
    }

    static ThreePlayerRound restore(ThreePlayerArchiveCodec.RoundArchive archive) {
        var start=archive.start();
        ThreePlayerRound restored=ordered(start.roundId(),start.players(),start.dealerSeat(),start.roundWind(),start.honba(),start.points(),start.deposits(),start.order());
        for(var action:archive.actions()) {
            UUID player=start.players().get(action.seat()); Command command=new Command(start.roundId(),restored.revision);
            Status status=switch(action.kind()) {
                case DISCARD,RIICHI -> restored.discard(player,command,action.tile(),action.kind()==ThreePlayerArchiveCodec.ActionKind.RIICHI);
                case TSUMO -> restored.tsumo(player,command);
                case NINE_TERMINALS -> restored.nineTerminals(player,command);
                case KITA -> restored.kita(player,command,action.tile());
                case CLOSED_KAN,ADDED_KAN -> restored.declareKan(player,command,new KanActions.Choice(action.kind()==ThreePlayerArchiveCodec.ActionKind.CLOSED_KAN?KanActions.Kind.CLOSED:KanActions.Kind.ADDED,action.meldIndex(),action.tiles()));
                case PASS,RON,CHI,PON,OPEN_KAN -> restored.respond(player,new Id(start.roundId(),action.windowSerial()),action.responseChoice());
            };
            if(status!=Status.ACCEPTED) throw new IllegalArgumentException("Archive action is not accepted: "+status);
        }
        restored.assertConservation();
        if(!restored.acceptedActions.equals(archive.actions()) || !java.security.MessageDigest.isEqual(restored.archiveFingerprint(),archive.fingerprint())) {
            throw new IllegalArgumentException("Archive replay state does not match its receipt");
        }
        return restored;
    }

    /** Compatibility guard over deterministic private state, not a second mutable-state serialization. */
    private byte[] archiveFingerprint() {
        List<String> tokens=new ArrayList<>();
        tokens.add(roundId.toString()); tokens.add(players.toString()); tokens.add(dealerSeat+":"+roundWind);
        tokens.add(phase+":"+actor+":"+drawnTile+":"+drawSource+":"+anyCallOccurred+":"+revision+":"+nextSerial);
        for(int seat=0;seat<3;seat++) {
            tokens.add(hands.get(seat).toString());
            var history=histories.get(seat); tokens.add(history.discardedTypes().stream().sorted().toList()+":"+history.temporary()+":"+history.riichiPass());
            var r=riichi.get(seat); tokens.add(r.phase()+":"+r.establishedRiichi()+":"+r.ippatsu()+":"+r.waitTypes().stream().sorted().toList());
            if(r.pending().isPresent()) { var p=r.pending().orElseThrow(); tokens.add(p.windowId()+":"+p.seat()+":"+p.discardedTileId()+":"+p.kind()+":"+p.waitTypes().stream().sorted().toList()); }
            else tokens.add("no-pending-riichi");
            tokens.add(rivers.get(seat).toString()); tokens.add(origins.get(seat).toString()); tokens.add(String.valueOf(responsibility.get(seat)));
        }
        tokens.add(wall.liveTiles().toString()); tokens.add(wall.deadTiles().toString()); tokens.add(String.valueOf(wall.replacementDraws()));
        tokens.add(ledger.points()+":"+ledger.honba()+":"+ledger.depositSticks()+":"+ledger.finished());
        tokens.add(kans.kansBySeat()+":"+kans.revealedIndicators()+":"+kans.delayedIndicatorPending()+":"+kans.fourthStage()+":"+kans.fourthWindow());
        tokens.add(String.valueOf(robbedTile));
        tokens.add(norths.toString()+":"+pendingNorth);
        if(discards!=null) {
            tokens.add("discard:"+discards.id()+":"+discards.discardedTileId()+":"+discards.pendingSeats().stream().sorted().toList());
            for(int seat=0;seat<3;seat++) if(seat!=actor) {
                tokens.add(String.valueOf(discards.responses().get(seat)));
                tokens.add(discards.offers(seat).stream().map(Object::toString).sorted().toList().toString());
            }
        } else tokens.add("no-discard-window");
        if(kanResponses!=null) {
            tokens.add("kan:"+kanResponses.id()+":"+kanResponses.proposal().choice()+":"+kanResponses.proposal().claimableTileId()+":"+kanResponses.pendingSeats().stream().sorted().toList());
            for(int seat=0;seat<3;seat++) if(seat!=actor) {
                tokens.add(String.valueOf(kanResponses.responses().get(seat)));
                tokens.add(kanResponses.offers(seat).stream().map(Object::toString).sorted().toList().toString());
            }
        } else tokens.add("no-kan-window");
        if(immediateCall!=null) tokens.add(immediateCall.hand()+":"+immediateCall.meld()+":"+immediateCall.callerSeat()+":"+immediateCall.providerSeat()+":"+immediateCall.claimedTileId()+":"+immediateCall.forbiddenDiscardTypes().stream().sorted().toList()+":"+immediateCall.legalDiscardTiles().stream().sorted().toList());
        else tokens.add("no-immediate-call");
        if(end!=null) {
            tokens.add(end.kind()+":"+end.winners()+":"+end.tenpai().stream().sorted().toList()+":"+end.dealerContinues());
            for(int seat=0;seat<3;seat++) tokens.add(String.valueOf(end.values().get(seat)));
            tokens.add(end.transfers().toString());
        } else tokens.add("no-result");
        return ThreePlayerArchiveCodec.fingerprint(tokens);
    }

    /** Production invariant as well as a verification aid: exactly 108 owned physical copies, no duplicated claims. */
    public void assertConservation() {
        Set<Integer> physical=new HashSet<>();
        for (int seat=0; seat<3; seat++) {
            HandInput hand=hands.get(seat); hand.concealedTiles().forEach(t->addPhysical(physical,t));
            hand.melds().forEach(m->m.tiles().forEach(t->addPhysical(physical,t)));
            norths.get(seat).forEach(t->addPhysical(physical,t));
            ThreePlayerHands.validate(hand,norths.get(seat));
            for (RiverTile tile:rivers.get(seat)) if (tile.claimedBy()<0) addPhysical(physical,tile.tileId());
            if (origins.get(seat).size()!=hand.melds().size()) throw new IllegalStateException("Missing meld provenance");
            long actualKans=hand.melds().stream().filter(m->m.tiles().size()==4).count();
            if (actualKans!=kans.kansBySeat().get(seat)) throw new IllegalStateException("Declared kans disagree with ownership");
            boolean drawnActor=seat==actor && (phase==Phase.DRAWN_TURN || phase==Phase.CALL_DISCARD || phase==Phase.KAN_RESPONSES || phase==Phase.NORTH_RESPONSES
                    || phase==Phase.FINISHED && (end.kind()==EndKind.TSUMO || end.kind()==EndKind.NINE_TERMINALS));
            if (hand.isComplete()!=drawnActor) throw new IllegalStateException("Hand size disagrees with action phase");
        }
        wall.liveTiles().forEach(t->addPhysical(physical,t)); wall.deadTiles().forEach(t->addPhysical(physical,t));
        if (robbedTile!=null) addPhysical(physical,robbedTile);
        if (physical.size()!=108 || wall.deadTiles().size()!=14 || wall.replacementDraws()!=kans.establishedKans()+norths.stream().mapToInt(List::size).sum()) {
            throw new IllegalStateException("Physical tile/wall/kan count is not conserved");
        }
        long money=ledger.points().stream().mapToLong(Integer::longValue).sum()+ledger.depositSticks()*1_000L;
        if (money!=105_000L || ledger.finished()!=(phase==Phase.FINISHED)) throw new IllegalStateException("Ledger phase or total is inconsistent");
    }
    private static void addPhysical(Set<Integer> seen, int tile) {
        TileCatalog.typeOf(tile); if (!seen.add(tile)) throw new IllegalStateException("Duplicated physical tile "+tile);
    }
}
