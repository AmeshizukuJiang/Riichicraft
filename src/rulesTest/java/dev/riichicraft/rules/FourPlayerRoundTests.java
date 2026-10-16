package dev.riichicraft.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import static dev.riichicraft.rules.DiscardResponseWindow.*;
import static dev.riichicraft.rules.FourPlayerRound.*;

/** End-to-end actions from a complete shuffled wall; no injected hands or trusted winning opportunities. */
public final class FourPlayerRoundTests {
    static final List<UUID> PLAYERS=List.of(new UUID(9,1),new UUID(9,2),new UUID(9,3),new UUID(9,4));
    static final UUID OBSERVER=new UUID(9,5);
    private static int assertions;
    private static int ordinaryEnds, winEnds, calls, kans, declarations, responseBatches;
    private FourPlayerRoundTests() { }

    public static void main(String[] args) {
        long start=System.nanoTime();
        initialAndRejectedCommands();
        for (int seed=0;seed<64;seed++) play(seed,seed%4,seed>=32);
        check(ordinaryEnds>=32,"all-pass complete rounds reach exhaustive settlement");
        check(calls>0,"random trajectories really execute calls");
        check(responseBatches>0,"trajectories include stable multi-responder windows");
        System.out.printf("PASS four-player round: %d assertions; %d draws, %d wins, %d calls, %d kans, %d declarations, %d response batches; %.3f s%n",
                assertions,ordinaryEnds,winEnds,calls,kans,declarations,responseBatches,(System.nanoTime()-start)/1_000_000_000.0);
    }

    private static void initialAndRejectedCommands() {
        for (int owner=0;owner<4;owner++) {
            int dealer=owner;
            List<Integer> order=new ArrayList<>(); for (int i=0;i<136;i++) order.add(i);
            var round=FourPlayerRound.ordered(new UUID(10,dealer),PLAYERS,dealer,28,2,List.of(24_000,25_000,25_000,25_000),1,order);
            var view=round.view(PLAYERS.get(dealer)); var command=view.command();
            check(view.actorSeat()==dealer && view.liveTiles()==69,"dealer receives the first real draw");
            check(view.ownDraw().orElseThrow()==52 && view.ownHand().orElseThrow().concealedTiles().contains(52),"exact first draw belongs to its owner");
            for (int relative=0;relative<4;relative++) {
                int seat=(dealer+relative)%4;
                check(round.view(PLAYERS.get(seat)).seats().get(seat).seatWind()==27+relative,"seat wind rotates independently of physical seat");
                check(round.serverState().hands().get(seat).concealedTiles().contains(relative),"deal order is relative to dealer");
            }
            sameAfterReject(round,PLAYERS.get(dealer),Status.NOT_A_PLAYER,()->round.discard(OBSERVER,command,52,false));
            sameAfterReject(round,PLAYERS.get(dealer),Status.WRONG_ROUND,()->round.discard(PLAYERS.get(dealer),new Command(UUID.randomUUID(),command.revision()),52,false));
            sameAfterReject(round,PLAYERS.get(dealer),Status.STALE_REVISION,()->round.discard(PLAYERS.get(dealer),new Command(command.roundId(),-1),52,false));
            sameAfterReject(round,PLAYERS.get(dealer),Status.NOT_YOUR_TURN,()->round.discard(PLAYERS.get((dealer+1)%4),command,52,false));
            sameAfterReject(round,PLAYERS.get(dealer),Status.NOT_OFFERED,()->round.discard(PLAYERS.get(dealer),command,135,false));
            sameAfterReject(round,PLAYERS.get(dealer),Status.NOT_OFFERED,()->round.discard(PLAYERS.get(dealer),command,-1,false));
            sameAfterReject(round,PLAYERS.get(dealer),Status.NOT_OFFERED,()->round.tsumo(PLAYERS.get(dealer),command));
            sameAfterReject(round,PLAYERS.get(dealer),Status.NOT_OFFERED,()->round.nineTerminals(PLAYERS.get(dealer),command));
            var frozen=round.serverState(); var frozenView=round.view(PLAYERS.get(dealer));
            check(round.discard(PLAYERS.get(dealer),command,52,false)==Status.ACCEPTED,"real discard accepted");
            check(frozen.hands().get(dealer).concealedTiles().size()==14 && frozen.wall().liveCount()==69,"server snapshots do not mutate after actions");
            check(frozenView.ownHand().orElseThrow().concealedTiles().size()==14 && frozenView.seats().get(dealer).river().isEmpty(),"recipient snapshots do not mutate");
            check(round.discard(PLAYERS.get(dealer),command,52,false)==Status.STALE_REVISION,"replayed turn command is inert");
            verifyViews(round);
            expectImmutable(()->frozen.hands().clear());
            expectImmutable(()->frozenView.seats().clear());
            expectImmutable(()->frozenView.ownHand().orElseThrow().concealedTiles().clear());
        }
        expectArgument(()->FourPlayerRound.shuffled(UUID.randomUUID(),List.of(PLAYERS.getFirst(),PLAYERS.getFirst(),PLAYERS.get(2),PLAYERS.get(3)),0,27,0,List.of(25_000,25_000,25_000,25_000),0,0));
        expectArgument(()->FourPlayerRound.shuffled(UUID.randomUUID(),PLAYERS,0,26,0,List.of(25_000,25_000,25_000,25_000),0,0));
    }

    private static void play(int seed,int dealer,boolean varied) {
        var round=FourPlayerRound.shuffled(new UUID(11,seed),PLAYERS,dealer,27,3,List.of(24_000,25_000,25_000,25_000),1,seed*31L+101);
        Random random=new Random(seed+91L); int actions=0;
        while (round.view(OBSERVER).phase()!=Phase.FINISHED) {
            check(++actions<=700,"round terminates within finite tile/action budget");
            verifyViews(round);
            var publicView=round.view(OBSERVER); int actor=publicView.actorSeat(); UUID player=PLAYERS.get(actor);
            var view=round.view(player);
            if (view.phase()==Phase.DRAWN_TURN || view.phase()==Phase.CALL_DISCARD) {
                var options=view.turnOptions();
                check(!options.discards().isEmpty(),"every live turn has a legal discard");
                Status accepted;
                if (varied && options.tsumo()) {
                    accepted=round.tsumo(player,view.command());
                } else if (varied && !options.kans().isEmpty() && random.nextBoolean()) {
                    var choice=options.kans().stream().sorted(Comparator.comparing(Object::toString)).findFirst().orElseThrow();
                    accepted=round.declareKan(player,view.command(),choice); kans++;
                } else if (varied && !options.riichiDiscards().isEmpty()) {
                    int tile=options.riichiDiscards().stream().min(Integer::compare).orElseThrow();
                    accepted=round.discard(player,view.command(),tile,true); declarations++;
                } else {
                    List<Integer> tiles=options.discards().stream().sorted().toList();
                    int tile=varied?tiles.get(random.nextInt(tiles.size())):view.ownDraw().orElseGet(()->tiles.getFirst());
                    accepted=round.discard(player,view.command(),tile,false);
                }
                check(accepted==Status.ACCEPTED,"offered turn action accepted");
            } else {
                Id id=publicView.opportunity().orElseThrow().id();
                List<Integer> pending=new ArrayList<>();
                for (int seat=0;seat<4;seat++) if (!round.view(PLAYERS.get(seat)).responses().isEmpty()) pending.add(seat);
                check(!pending.isEmpty(),"an unresolved window has an actionable responder");
                if (pending.size()>1) responseBatches++;
                Collections.shuffle(pending,random);
                var before=round.view(PLAYERS.get(pending.getFirst()));
                check(round.respond(OBSERVER,id,Simple.PASS)==Status.NOT_A_PLAYER,"spectator cannot respond");
                check(round.respond(PLAYERS.get(actor),id,Simple.PASS)==Status.NOT_YOUR_TURN,"discarder/declarer cannot respond to self");
                check(round.respond(PLAYERS.get(pending.getFirst()),new Id(id.roundId(),id.serial()+10_000),Simple.PASS)==Status.WRONG_WINDOW,"unoffered future window rejected");
                check(before.equals(round.view(PLAYERS.get(pending.getFirst()))),"bad responses do not alter any view or revision");
                long revision=before.revision();
                for (int seat:pending) {
                    var choices=round.view(PLAYERS.get(seat)).responses(); Choice choice=Simple.PASS;
                    if (varied && choices.contains(Simple.RON)) choice=Simple.RON;
                    else if (varied) {
                        var selected=choices.stream().filter(c->c instanceof Call).sorted(Comparator.comparing(Object::toString)).findFirst();
                        if (selected.isPresent() && random.nextInt(4)!=0) { choice=selected.orElseThrow(); calls++; }
                    }
                    check(round.respond(PLAYERS.get(seat),id,choice)==Status.ACCEPTED,"same window accepts each independently arriving response");
                    check(round.view(OBSERVER).revision()==++revision,"one successful command advances revision exactly once");
                    if (round.view(OBSERVER).opportunity().map(o->o.id().equals(id)).orElse(false)) {
                        check(round.respond(PLAYERS.get(seat),id,Simple.PASS)==Status.ALREADY_RESPONDED,"cannot overwrite an accepted response");
                    }
                }
            }
        }
        verifyViews(round); var end=round.view(OBSERVER).end().orElseThrow();
        check(round.serverState().ledger().finished(),"terminal round has exactly one settled ledger");
        if (end.kind()==EndKind.EXHAUSTIVE || end.kind()==EndKind.NAGASHI) ordinaryEnds++;
        else if (end.kind()==EndKind.RON || end.kind()==EndKind.TSUMO) winEnds++;
        var frozen=round.view(PLAYERS.get(round.view(OBSERVER).actorSeat())); UUID actor=PLAYERS.get(frozen.actorSeat());
        check(round.discard(actor,frozen.command(),0,false)==Status.WRONG_PHASE,"finished round refuses another discard");
        check(round.tsumo(actor,frozen.command())==Status.WRONG_PHASE,"finished round cannot pay twice");
        check(frozen.equals(round.view(actor)),"terminal rejected actions preserve result and ledger");
    }

    private static void verifyViews(FourPlayerRound round) {
        round.assertConservation(); var diagnostic=round.serverState();
        var spectator=round.view(OBSERVER);
        check(spectator.ownSeat()==-1 && spectator.ownHand().isEmpty() && spectator.ownDraw().isEmpty() && spectator.ownHistory().isEmpty(),"spectator has no concealed state");
        check(spectator.turnOptions().equals(TurnOptions.none()) && spectator.responses().isEmpty(),"spectator has no actionable mask");
        check(spectator.doraIndicators().equals(diagnostic.wall().doraIndicators(diagnostic.kans().revealedIndicators())),"only actually revealed dora become public");
        long total=spectator.seats().stream().mapToLong(PublicSeat::points).sum()+spectator.deposits()*1_000L;
        check(total==100_000L,"all views conserve scores and deposits");
        int declaredKans=diagnostic.hands().stream().flatMap(h->h.melds().stream()).mapToInt(m->m.tiles().size()==4?1:0).sum();
        check(declaredKans==diagnostic.kans().establishedKans(),"all actual quads agree with kan progress");
        for (int seat=0;seat<4;seat++) {
            var view=round.view(PLAYERS.get(seat));
            check(view.ownSeat()==seat && view.ownHand().orElseThrow().equals(diagnostic.hands().get(seat)),"each player receives exactly its own current hand");
            check(view.seats().equals(spectator.seats()) && view.doraIndicators().equals(spectator.doraIndicators()),"public information is recipient independent");
            for (int other=0;other<4;other++) {
                var publicSeat=view.seats().get(other);
                check(publicSeat.concealedCount()==diagnostic.hands().get(other).concealedTiles().size(),"opponent concealed count is public, identities are absent");
                check(publicSeat.melds().equals(diagnostic.hands().get(other).melds()),"only actual declared groups are public");
                for (var river:publicSeat.river()) {
                    check(diagnostic.histories().get(other).discardedTypes().contains(river.tileId()/4),"all rivers including claimed tiles remain in furiten history");
                }
            }
            if (seat!=spectator.actorSeat()) check(view.turnOptions().equals(TurnOptions.none()) && view.ownDraw().isEmpty(),"nonactor receives no self-turn actions or drawn identity");
        }
    }

    private static void sameAfterReject(FourPlayerRound round,UUID player,Status expected,java.util.function.Supplier<Status> command) {
        var before=round.view(player); check(command.get()==expected,"invalid command has precise rejection");
        check(before.equals(round.view(player)),"invalid command changes no observable state");
    }
    private static void expectImmutable(Runnable action) { try { action.run(); throw new AssertionError("Expected immutable collection"); } catch (UnsupportedOperationException expected) { assertions++; } }
    private static void expectArgument(Runnable action) { try { action.run(); throw new AssertionError("Expected invalid setup"); } catch (IllegalArgumentException expected) { assertions++; } }
    private static void check(boolean condition,String message) { assertions++; if(!condition) throw new AssertionError(message); }
}
