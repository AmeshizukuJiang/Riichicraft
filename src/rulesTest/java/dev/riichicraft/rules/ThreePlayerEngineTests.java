package dev.riichicraft.rules;

import java.util.*;
import static dev.riichicraft.rules.DiscardResponseWindow.*;

/** Full physical-wall trajectories, exact response replay, and independent sanma account checks. */
public final class ThreePlayerEngineTests {
    private static int assertions;
    private static final List<UUID> PLAYERS=List.of(new UUID(71,0),new UUID(71,1),new UUID(71,2));
    public static void main(String[] args) {
        walls(); northChain(); doubleRobbery(); delayedKanThenKita(); riichiThenKita(); robberyKeepsIppatsu(); ponCannotKita(); waitsAndRiichi(); ledgers(); randomRounds(); matches();
        System.out.printf("PASS three-player engine: %d assertions%n",assertions);
    }
    private static void walls() {
        interleave(ThreePlayerWall.deal(ThreePlayerTiles.allTiles()).wall().drawLive().wall(),0,0,new HashSet<>());
        var initial=ThreePlayerWall.shuffled(91); var wall=initial.wall();
        var held=new HashSet<Integer>(); initial.hands().forEach(h->held.addAll(h.concealedTiles()));
        check(held.size()==39 && wall.liveCount()==55 && wall.deadTiles().size()==14,"39 dealt,55 live,14 reserved");
        while(wall.liveCount()>0) { var draw=wall.drawLive(); check(held.add(draw.tileId()),"live tile unique"); wall=draw.wall(); }
        check(held.size()==94 && !wall.canDrawReplacement(),"no replacement after last live");
        illegal(()->initial.wall().doraIndicators(2),"cannot reveal unestablished kan");
    }
    private static void interleave(ThreePlayerWall wall,int kans,int norths,Set<Integer> drawn) {
        check(wall.deadTiles().size()==14 && wall.liveCount()==54-kans-norths,"dynamic reserve remains fourteen");
        var ownership=new HashSet<>(wall.liveTiles());
        for(int tile:wall.deadTiles()) check(ownership.add(tile),"live/reserve disjoint");
        for(int tile:drawn) check(!ownership.contains(tile),"replacement never also in remaining wall");
        for(int tile:wall.doraIndicators(kans+1)) check(wall.deadTiles().contains(tile),"all available dora safely reserved");
        for(int tile:wall.uraIndicators(kans+1)) check(wall.deadTiles().contains(tile),"all available ura safely reserved");
        if(kans==4 && norths==4) { check(!wall.canDrawReplacement(),"eight total replacements"); return; }
        for(var source:List.of(ThreePlayerWall.Source.KAN,ThreePlayerWall.Source.KITA)) {
            if(source==ThreePlayerWall.Source.KAN?kans==4:norths==4) continue;
            var draw=wall.drawReplacement(source); var next=new HashSet<>(drawn);
            check(next.add(draw.tileId()),"unique shared supplemental sequence");
            interleave(draw.wall(),kans+(source==ThreePlayerWall.Source.KAN?1:0),norths+(source==ThreePlayerWall.Source.KITA?1:0),next);
        }
    }
    static ThreePlayerRound chainRound(UUID id) {
        return ThreePlayerRound.ordered(id,PLAYERS,0,27,0,List.of(35000,35000,35000),0,
                order(new String[]{"123p456p789s11m4z7s","999m111p222s345s5z","111s999p555z666z2p"},"4z","44z89s"));
    }
    private static void northChain() {
        var round=chainRound(new UUID(72,0));
        var old=round.view(PLAYERS.getFirst()).command();
        for(int count=0;count<4;count++) {
            var view=round.view(PLAYERS.getFirst()); int north=view.turnOptions().kitas().stream().min(Integer::compare).orElseThrow();
            check(round.kita(PLAYERS.getFirst(),view.command(),north)==ThreePlayerRound.Status.ACCEPTED,"establish north");
            check(round.view(null).phase()==ThreePlayerRound.Phase.DRAWN_TURN,"no-yaku responders auto pass");
            check(round.view(null).seats().getFirst().norths().size()==count+1,"exact established north count");
            check(round.serverState().wall().liveCount()==53-count && round.serverState().kans().establishedKans()==0,"kita consumes one live, no kan");
            check(round.serverState().histories().getFirst().discardedTypes().isEmpty(),"own kita is not own discard furiten");
            round.assertConservation();
            byte[] bytes=ThreePlayerArchiveCodec.encodeRound(round);
            var restored=ThreePlayerArchiveCodec.decodeRound(bytes);
            check(Arrays.equals(bytes,ThreePlayerArchiveCodec.encodeRound(restored)),"north sequence byte-stable restore");
            check(restored.view(PLAYERS.getFirst()).equals(round.view(PLAYERS.getFirst())),"same private/public snapshot after restore");
        }
        check(round.kita(PLAYERS.getFirst(),old,120)==ThreePlayerRound.Status.STALE_REVISION,"old command rejected");
        var view=round.view(PLAYERS.getFirst()); check(view.turnOptions().tsumo(),"kita supplemental win available");
        check(round.tsumo(PLAYERS.getFirst(),view.command())==ThreePlayerRound.Status.ACCEPTED,"actual supplemental tsumo");
        var value=round.view(null).end().orElseThrow().values().get(0);
        check(value.kita()==4 && value.yaku().stream().anyMatch(y->y.yaku()==Yaku.RINSHAN_KAIHOU),"four kita with genuine rinshan yaku");
        check(value.points().payment().totalPoints()==16000,"dealer baiman actual two-payer total");
    }
    private static void doubleRobbery() {
        var round=ThreePlayerRound.ordered(new UUID(73,0),PLAYERS,0,27,1,List.of(35000,35000,35000),0,
                order(new String[]{"123p456p789s11m4z7s","123p789p123s789s4z","123p789p123s789s4z"},"4z",""));
        var initial=round.view(PLAYERS.getFirst()); int tile=initial.turnOptions().kitas().stream().min(Integer::compare).orElseThrow();
        check(round.kita(PLAYERS.getFirst(),initial.command(),tile)==ThreePlayerRound.Status.ACCEPTED,"north declaration");
        check(round.view(null).phase()==ThreePlayerRound.Phase.NORTH_RESPONSES,"independent north response phase");
        check(round.serverState().wall().replacementDraws()==0 && round.view(null).seats().getFirst().norths().isEmpty(),"declaration has not established or drawn");
        var window=round.view(PLAYERS.get(1)).opportunity().orElseThrow().id();
        check(round.respond(PLAYERS.get(1),window,Simple.RON)==ThreePlayerRound.Status.ACCEPTED,"first ron accepted");
        check(round.view(null).phase()==ThreePlayerRound.Phase.NORTH_RESPONSES,"first ron waits for other responder");
        var restored=ThreePlayerArchiveCodec.decodeRound(ThreePlayerArchiveCodec.encodeRound(round));
        check(restored.view(PLAYERS.get(2)).responses().contains(Simple.RON),"remaining response restored");
        check(restored.respond(PLAYERS.get(1),window,Simple.RON)==ThreePlayerRound.Status.ALREADY_RESPONDED,"duplicate response rejected");
        check(restored.respond(PLAYERS.get(2),window,Simple.RON)==ThreePlayerRound.Status.ACCEPTED,"second ron accepted");
        var end=restored.view(null).end().orElseThrow(); check(end.winners().equals(List.of(1,2)),"both ron winners retained in draw order");
        check(restored.view(null).seats().getFirst().norths().isEmpty() && restored.serverState().wall().replacementDraws()==0,"robbed kita never establishes");
        check(restored.serverState().ledger().points().equals(List.of(29600,37800,37600)),"double 2600 ron; honba200 only closest");
        for(var value:end.values().values()) check(value.yaku().stream().noneMatch(y->y.yaku()==Yaku.CHANKAN),"no chankan for robbed north");
        check(Arrays.equals(ThreePlayerArchiveCodec.encodeRound(restored),ThreePlayerArchiveCodec.encodeRound(ThreePlayerArchiveCodec.decodeRound(ThreePlayerArchiveCodec.encodeRound(restored)))),"finished double-ron stable replay");
    }
    private static void waitsAndRiichi() {
        var used=new boolean[136]; var h=new HandInput(tiles("123456p789s345s4z",used),List.of());
        var norths=tiles("444z",used);
        check(HandAnalyzer.waits(h).contains(30) && ThreePlayerHands.waits(h,norths).isEmpty(),"cannot seek fifth north including extracted tiles");
        var complete=new HandInput(tiles("123456p789s345s22s",new boolean[136]),List.of());
        int drawn=complete.concealedTiles().getLast(); var r=ThreePlayerRiichi.initial();
        check(!r.declarationOptions(complete,drawn,new ThreePlayerRiichi.Context(0,35000,3,0,false),List.of()).isEmpty(),"three live permits sanma riichi");
        check(r.declarationOptions(complete,drawn,new ThreePlayerRiichi.Context(0,35000,2,0,false),List.of()).isEmpty(),"two live forbids riichi");
        var noYaku=new HandInput(tiles("123456p789s345s4z",new boolean[136]),List.of());
        var temporary=ThreePlayerHands.missed(noYaku,List.of(),121,FuritenState.initial(),WinContext.Riichi.NONE,false);
        check(temporary.temporary() && !temporary.afterDraw().temporary(),"no-yaku north pass triggers temporary furiten until actual draw");
        var permanent=ThreePlayerHands.missed(noYaku,List.of(),121,FuritenState.initial(),WinContext.Riichi.RIICHI,false);
        check(permanent.riichiPass() && permanent.afterDraw().riichiPass(),"established riichi north miss persists through draw");
    }
    private static void delayedKanThenKita() {
        for(boolean win:List.of(false,true)) {
            var order=new ArrayList<>(order(new String[]{"111999m111s222s3p","999p123p456s789s1z","222p333s555z666z7s"},"9p","4z1z"));
            // Reserve the second (deferred) dora as West without moving any dealt/supplemental tile.
            int west=order.indexOf(116); Collections.swap(order,96,west);
            var r=ThreePlayerRound.ordered(UUID.randomUUID(),PLAYERS,0,27,0,List.of(35000,35000,35000),0,order);
            var east=r.view(PLAYERS.getFirst());r.discard(PLAYERS.getFirst(),east.command(),east.ownDraw().orElseThrow(),false);
            var south=r.view(PLAYERS.get(1)); var kan=south.responses().stream().filter(c->c instanceof Call call && call.kind()==Meld.Kind.OPEN_KAN).findFirst().orElseThrow();
            r.respond(PLAYERS.get(1),south.opportunity().orElseThrow().id(),kan);
            for(UUID player:PLAYERS) { var v=r.view(player); if(!v.responses().isEmpty())r.respond(player,v.opportunity().orElseThrow().id(),Simple.PASS); }
            check(r.view(null).actorSeat()==1 && r.serverState().kans().delayedIndicatorPending(),"actual open kan has deferred indicator");
            var own=r.view(PLAYERS.get(1));int north=own.turnOptions().kitas().iterator().next();
            r.kita(PLAYERS.get(1),own.command(),north);
            check(r.serverState().wall().replacementDraws()==2 && r.view(null).doraIndicators().size()==1,"kita after kan does not reveal deferred dora");
            var copy=ThreePlayerArchiveCodec.decodeRound(ThreePlayerArchiveCodec.encodeRound(r));
            check(copy.serverState().kans().delayedIndicatorPending() && copy.view(null).doraIndicators().size()==1,"deferred indicator survives north replay");
            own=copy.view(PLAYERS.get(1));
            if(win) {
                check(own.turnOptions().tsumo(),"kita supplemental tsumo after open kan");
                copy.tsumo(PLAYERS.get(1),own.command());var score=copy.view(null).end().orElseThrow().values().get(1);
                // 20 base + 16 open terminal kan + 2 tsumo + 2 round-wind pair + 2 tanki = 42 -> 50.
                check(score.han()==2 && score.fu()==50 && score.dora()==0 && score.kita()==1 && score.points().payment().totalPoints()==2400,"hidden West indicator not counted in north supplement win: "+score);
            } else {
                int onePin=own.turnOptions().discards().stream().filter(t->t/4==9).findFirst().orElseThrow();
                copy.discard(PLAYERS.get(1),own.command(),onePin,false);
                check(copy.view(null).doraIndicators().size()==2 && copy.view(null).doraIndicators().get(1)==116,"deferred West indicator appears at real discard");
            }
        }
    }
    private static void riichiThenKita() {
        var r=ThreePlayerRound.ordered(UUID.randomUUID(),PLAYERS,0,27,0,List.of(35000,35000,35000),0,
                order(new String[]{"123456p789s345s4z","999m111p222s345s5z","111s999p555z666z2p"},"1m8p8s4z","4z"));
        var east=r.view(PLAYERS.getFirst());int discard=east.ownDraw().orElseThrow();
        check(east.turnOptions().riichiDiscards().contains(discard),"actual north wait allows declaration");
        r.discard(PLAYERS.getFirst(),east.command(),discard,true); passAll(r);
        for(int turn=0;turn<2;turn++) {
            var v=r.view(null); UUID player=PLAYERS.get(v.actorSeat());var own=r.view(player);
            r.discard(player,own.command(),own.ownDraw().orElseThrow(),false);passAll(r);
        }
        east=r.view(PLAYERS.getFirst());
        check(east.turnOptions().kitas().equals(Set.of(east.ownDraw().orElseThrow())),"riichi offers only newly drawn north, not original held north");
        r.kita(PLAYERS.getFirst(),east.command(),east.ownDraw().orElseThrow());passAll(r);
        var state=r.serverState().riichi().getFirst();
        check(state.establishedRiichi()==WinContext.Riichi.DOUBLE_RIICHI && !state.ippatsu(),"established double riichi retained; kita interrupts ippatsu");
        check(r.view(PLAYERS.getFirst()).turnOptions().tsumo() && r.serverState().ledger().depositSticks()==1,"real kita replacement can complete riichi north wait");
    }
    private static void passAll(ThreePlayerRound r) {
        for(UUID player:PLAYERS) {var v=r.view(player);if(!v.responses().isEmpty())r.respond(player,v.opportunity().orElseThrow().id(),Simple.PASS);}
    }
    private static void robberyKeepsIppatsu() {
        var r=ThreePlayerRound.ordered(UUID.randomUUID(),PLAYERS,0,27,0,List.of(35000,35000,35000),0,
                order(new String[]{"123456p789s345s4z","999m111p222s345s5z","111s999p555z666z2p"},"1m4z",""));
        var east=r.view(PLAYERS.getFirst());r.discard(PLAYERS.getFirst(),east.command(),east.ownDraw().orElseThrow(),true);passAll(r);
        var south=r.view(PLAYERS.get(1));r.kita(PLAYERS.get(1),south.command(),south.turnOptions().kitas().iterator().next());
        east=r.view(PLAYERS.getFirst());check(east.responses().contains(Simple.RON),"real ippatsu ron on north declaration offered");
        r.respond(PLAYERS.getFirst(),east.opportunity().orElseThrow().id(),Simple.RON);
        var value=r.view(null).end().orElseThrow().values().get(0);
        check(value.yaku().stream().anyMatch(y->y.yaku()==Yaku.IPPATSU) && value.yaku().stream().noneMatch(y->y.yaku()==Yaku.CHANKAN),"robbed north preserves genuine established ippatsu, without chankan");
        check(r.serverState().wall().replacementDraws()==0 && r.view(null).seats().get(1).norths().isEmpty(),"robbed north never interrupts by establishment or draws");
    }
    private static void ponCannotKita() {
        var r=ThreePlayerRound.ordered(UUID.randomUUID(),PLAYERS,0,27,0,List.of(35000,35000,35000),0,
                order(new String[]{"111999m111s222s3p","999p123p456s789s4z","222p333s555z666z7s"},"9p",""));
        var east=r.view(PLAYERS.getFirst());r.discard(PLAYERS.getFirst(),east.command(),east.ownDraw().orElseThrow(),false);
        var south=r.view(PLAYERS.get(1));var pon=south.responses().stream().filter(c->c instanceof Call call && call.kind()==Meld.Kind.PON).findFirst().orElseThrow();
        r.respond(PLAYERS.get(1),south.opportunity().orElseThrow().id(),pon);passAll(r);south=r.view(PLAYERS.get(1));
        check(south.phase()==ThreePlayerRound.Phase.CALL_DISCARD && south.turnOptions().kitas().isEmpty(),"pon requires immediate discard, no north extraction");
        int north=south.ownHand().orElseThrow().concealedTiles().stream().filter(tile->tile/4==30).findFirst().orElseThrow();
        check(r.kita(PLAYERS.get(1),south.command(),north)==ThreePlayerRound.Status.NOT_OFFERED,"forged kita after pon is inert");
    }
    private static void ledgers() {
        for(int dealer=0;dealer<3;dealer++) for(int mask=0;mask<8;mask++) for(int nagashi=0;nagashi<8;nagashi++) {
            var tenpai=new HashSet<Integer>(); var winners=new HashSet<Integer>();
            for(int seat=0;seat<3;seat++) { if((mask&(1<<seat))!=0)tenpai.add(seat); if((nagashi&(1<<seat))!=0)winners.add(seat); }
            var ledger=ThreePlayerLedger.begin(new UUID(74,mask*8+nagashi),dealer,4,List.of(34000,34000,34000),3);
            var result=ledger.settleExhaustive(tenpai,winners);
            check(result.ledger().points().stream().mapToInt(Integer::intValue).sum()==102000 && result.ledger().depositSticks()==3,"exhaustive/nagashi conserve deposits");
            if(nagashi==0) for(int seat=0;seat<3;seat++) {
                int delta=tenpai.isEmpty() || tenpai.size()==3?0:tenpai.contains(seat)?2000/tenpai.size():-2000/(3-tenpai.size());
                check(result.ledger().points().get(seat)==34000+delta,"independent 2000 noten table");
            }
        }
    }
    private static void randomRounds() {
        for(int seed=0;seed<32;seed++) {
            var r=ThreePlayerRound.shuffled(new UUID(75,seed),PLAYERS,seed%3,27,0,List.of(35000,35000,35000),0,seed);
            for(int action=0;r.view(null).phase()!=ThreePlayerRound.Phase.FINISHED;action++) {
                check(action<600,"finite complete trajectory"); drive(r,action); r.assertConservation();
                var publicView=r.view(null); check(publicView.ownHand().isEmpty() && publicView.responses().isEmpty(),"spectator has no private state");
                if(action%17==0) r=ThreePlayerArchiveCodec.decodeRound(ThreePlayerArchiveCodec.encodeRound(r));
            }
            check(r.serverState().ledger().finished(),"actual complete sanma receipt");
        }
    }
    private static void drive(ThreePlayerRound r,int action) {
        var view=r.view(null);
        if(view.phase()==ThreePlayerRound.Phase.DRAWN_TURN || view.phase()==ThreePlayerRound.Phase.CALL_DISCARD) {
            UUID player=PLAYERS.get(view.actorSeat()); var own=r.view(player); var options=own.turnOptions();
            if(!options.kitas().isEmpty()) r.kita(player,own.command(),options.kitas().stream().min(Integer::compare).orElseThrow());
            else if(!options.kans().isEmpty()) r.declareKan(player,own.command(),options.kans().stream().sorted(Comparator.comparing(Object::toString)).findFirst().orElseThrow());
            else r.discard(player,own.command(),options.discards().stream().max(Integer::compare).orElseThrow(),false);
        } else for(UUID player:PLAYERS) {
            var own=r.view(player); if(own.responses().isEmpty())continue;
            Choice choice=action%3==0?own.responses().stream().filter(c->c instanceof Call).sorted(Comparator.comparing(Object::toString)).findFirst().orElse(Simple.PASS):Simple.PASS;
            check(r.respond(player,own.opportunity().orElseThrow().id(),choice)==ThreePlayerRound.Status.ACCEPTED,"stable response accepted"); break;
        }
    }
    private static void matches() {
        for(var format:ThreePlayerMatchRules.Format.values()) {
            var m=new ThreePlayerMatch(new UUID(76,format.ordinal()),PLAYERS,format,0,setup->ThreePlayerRound.shuffled(UUID.randomUUID(),setup.players(),setup.dealerSeat(),setup.roundWind(),setup.honba(),setup.points(),setup.deposits(),setup.roundIndex()*31L+7));
            for(int step=0;m.view(null).phase()!=ThreePlayerMatch.Phase.MATCH_RESULT;step++) {
                check(step<5000,"finite complete sanma match"); var view=m.view(null);
                if(view.phase()==ThreePlayerMatch.Phase.ROUND_RESULT) {
                    var id=view.continueId();
                    for(UUID player:PLAYERS)check(m.confirmNext(player,id)==ThreePlayerRound.Status.ACCEPTED,"three next-round votes");
                    m=ThreePlayerArchiveCodec.decodeMatch(ThreePlayerArchiveCodec.encodeMatch(m),setup->ThreePlayerRound.shuffled(UUID.randomUUID(),setup.players(),setup.dealerSeat(),setup.roundWind(),setup.honba(),setup.points(),setup.deposits(),setup.roundIndex()*31L+7));
                } else {
                    var r=view.round(); UUID player=PLAYERS.get(r.actorSeat()); var own=m.view(player);
                    if(r.phase()==ThreePlayerRound.Phase.DRAWN_TURN || r.phase()==ThreePlayerRound.Phase.CALL_DISCARD) {
                        var opts=own.round().turnOptions(); m.discard(player,own.command(),opts.discards().stream().max(Integer::compare).orElseThrow(),false);
                    } else for(UUID responder:PLAYERS) {
                        var v=m.view(responder).round(); if(!v.responses().isEmpty()) { m.respond(responder,v.opportunity().orElseThrow().id(),Simple.PASS); break; }
                    }
                }
            }
            check(m.view(null).result().orElseThrow().standings().size()==3,"three final standings");
        }
    }
    private static List<Integer> order(String[] hands,String firstDraws,String replacements) {
        var used=new boolean[136]; Integer[] out=new Integer[108];
        for(int seat=0;seat<3;seat++) { var held=tiles(hands[seat],used); if(held.size()!=13)throw new AssertionError("Need thirteen"); for(int i=0;i<13;i++)out[i*3+seat]=held.get(i); }
        var draws=tiles(firstDraws,used); for(int i=0;i<draws.size();i++)out[39+i]=draws.get(i);
        var supplemental=tiles(replacements,used); for(int i=0;i<supplemental.size();i++)out[100+i]=supplemental.get(i);
        out[98]=tiles("1z",used).getFirst();
        var remaining=ThreePlayerTiles.allTiles().stream().filter(id->!used[id]).iterator();
        for(int i=0;i<108;i++) if(out[i]==null)out[i]=remaining.next();
        return List.of(out);
    }
    private static List<Integer> tiles(String notation,boolean[] used) {
        var out=new ArrayList<Integer>(); var ranks=new StringBuilder();
        for(char c:notation.toCharArray()) {
            if(Character.isDigit(c)) { ranks.append(c); continue; }
            int offset=switch(c) {case 'm'->0;case 'p'->9;case 's'->18;case 'z'->27;default->throw new AssertionError("Bad notation");};
            for(char digit:ranks.toString().toCharArray()) {
                int type=offset+(digit=='0'?5:digit-'0')-1,selected=-1;
                int[] copies=digit=='0'?new int[]{0}:type<27 && type%9==4?new int[]{1,2,3,0}:new int[]{0,1,2,3};
                for(int copy:copies)if(!used[type*4+copy]) { selected=type*4+copy;break; }
                if(selected<0)throw new AssertionError("Too many "+digit+c);used[selected]=true;out.add(selected);
            }
            ranks.setLength(0);
        }
        return out;
    }
    private static void check(boolean good,String detail) { assertions++;if(!good)throw new AssertionError(detail); }
    private static void illegal(Runnable action,String detail) { try{action.run();}catch(IllegalArgumentException expected){assertions++;return;}throw new AssertionError(detail); }
}
