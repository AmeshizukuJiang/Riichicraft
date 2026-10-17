package dev.riichicraft.rules;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static dev.riichicraft.rules.DiscardResponseWindow.*;
import static dev.riichicraft.rules.FourPlayerRound.*;

/** Small real-wall recovery trajectories. No reflection, Minecraft, injected hands or duplicated rule matrix. */
public final class ArchiveTests {
    private static final List<UUID> PLAYERS=List.of(new UUID(151,0),new UUID(151,1),new UUID(151,2),new UUID(151,3));
    private static final int[] ORPHANS={0,8,9,17,18,26,27,28,29,30,31,32,33};
    private static int assertions,serial;
    private ArchiveTests() { }
    public static void main(String[] args) {
        if(args.length>0) {
            try { childMode(args); return; } catch(Exception failure) { throw new AssertionError("Archive subprocess failed",failure); }
        }
        long start=System.nanoTime();
        run("partial ron/pass and pending riichi survive without premature payment",ArchiveTests::partialDiscard);
        run("pending riichi becomes one funded declaration and retains call discard mask",ArchiveTests::calledDeclaration);
        run("partial closed-kan robbery responses preserve all four physical copies",ArchiveTests::partialClosedKan);
        run("actual added kan, red robbery, replacement source and deferred dora",ArchiveTests::addedKanAndDeferred);
        run("fourth-kan replacement and final-discard gate survive recovery",ArchiveTests::fourthKanGate);
        run("responsibility comes from restored call history rather than final yaku",ArchiveTests::responsibility);
        run("cross-round votes, prior round IDs, accounts and final-only deposit award",ArchiveTests::matchVotesAndFinalPool);
        run("framing, versions, checksum, truncation and bounded decoding",ArchiveTests::invalidFraming);
        run("fresh JVMs retain partial responses, scored wins and final deposit standings",ArchiveTests::crossJvm);
        System.out.printf("PASS archives: %d assertions; %.3f s%n",assertions,(System.nanoTime()-start)/1_000_000_000.0);
    }

    private static void partialDiscard() {
        var a=partialFixture(); int east=a.view(null).opportunity().orElseThrow().tileId();
        var old=new Command(a.view(null).roundId(),0);
        check(a.view(null).phase()==Phase.DISCARD_RESPONSES && a.serverState().riichi().getFirst().phase()==RiichiState.Phase.PENDING,"archive point has one pass, one ron and a still-unfunded declaration");
        var b=restore(a); same(a,b);
        check(b.view(null).deposits()==3 && b.view(null).seats().getFirst().points()==24_000,"replay cannot fund pending riichi or settle the already-received ron early");
        Id window=a.view(null).opportunity().orElseThrow().id();
        check(a.respond(PLAYERS.get(2),window,Simple.RON)==Status.ALREADY_RESPONDED
                && b.respond(PLAYERS.get(2),window,Simple.RON)==Status.ALREADY_RESPONDED,"restored answered seats cannot submit twice");
        check(a.discard(PLAYERS.getFirst(),old,east,true)==Status.STALE_REVISION
                && b.discard(PLAYERS.getFirst(),old,east,true)==Status.STALE_REVISION,"old accepted turn command remains stale after decoding");
        same(a,b); bothRespond(a,b,1,Simple.RON);
        check(b.view(null).end().orElseThrow().winners().equals(List.of(1,2)),"last reply completes both original winners in draw order");
        check(b.serverState().riichi().getFirst().phase()==RiichiState.Phase.UNDECLARED
                && b.view(null).deposits()==0,"declaration is cancelled and only old sticks are awarded");
        check(b.view(null).end().orElseThrow().transfers().stream().filter(t->t.kind()==RoundLedger.Kind.DEPOSIT).mapToInt(RoundLedger.Transfer::points).sum()==3_000,"old deposit pool is paid once, with no extra pending declaration stick");
        same(b,restore(b));
    }

    private static FourPlayerRound partialFixture() {
        Wall w=new Wall();
        w.hand(0,13,13,13,14,14,14,15,15,15,16,16,16,28);
        for(int seat=1;seat<4;seat++) w.hand(seat,0,1,2,3,4,5,9,10,11,18,19,20,27);
        int east=w.live(0,27); var a=w.round(2,List.of(24_000,24_000,24_000,25_000),3);
        discard(a,east,true); respond(a,3,Simple.PASS); respond(a,2,Simple.RON); return a;
    }

    private static FourPlayerRound ambiguousWinFixture() {
        Wall w=new Wall(); w.hand(0,0,0,1,1,2,2,3,3,4,4,5,5,6);
        w.live(0,31); w.live(1,32); w.live(2,33); w.live(3,30); w.live(4,6); w.indicator(31);
        var round=w.round();
        for(int seat=0;seat<4;seat++) { discard(round,round.view(PLAYERS.get(seat)).ownDraw().orElseThrow(),false); complete(round,Map.of()); }
        var own=round.view(PLAYERS.getFirst());
        var context=new WinContext(WinContext.WinMethod.TSUMO,own.ownDraw().orElseThrow(),27,27,WinContext.Riichi.NONE,false,
                WinContext.WinEvent.NORMAL,WinContext.FirstTurn.NONE,own.doraIndicators(),List.of());
        var alternatives=HandScorer.scoreAll(own.ownHand().orElseThrow(),context); var best=alternatives.getFirst();
        check(best.han()==11 && alternatives.stream().filter(s->s.points().equals(best.points()) && s.han()==best.han() && s.fu()==best.fu()).count()>1,
                "cross-JVM fixture genuinely has multiple equal-best 11-han scoring interpretations");
        check(round.tsumo(PLAYERS.getFirst(),own.command())==Status.ACCEPTED,"ambiguous score is settled through its real second-draw win"); return round;
    }

    private static void calledDeclaration() {
        Wall w=new Wall(); w.hand(0,0,1,2,3,4,5,9,10,11,18,19,20,27);
        w.hand(1,31,31,6,6,6,7,7,7,12,12,12,28,28); int white=w.live(0,31);
        var a=w.round(); discard(a,white,true); var b=restore(a);
        Call pon=call(a,1,Meld.Kind.PON,List.of(31,31));
        completeBoth(a,b,Map.of(1,pon));
        check(b.view(null).phase()==Phase.CALL_DISCARD && b.view(null).deposits()==1
                && b.view(null).seats().getFirst().points()==24_000,"a restored declaration called by pon establishes and debits exactly once");
        check(!b.serverState().riichi().getFirst().ippatsu(),"the established call cancels ippatsu after restoration");
        var c=restore(b); same(b,c);
        check(c.view(PLAYERS.get(1)).ownDraw().isEmpty()
                && c.view(PLAYERS.get(1)).turnOptions().discards().stream().noneMatch(t->t/4==31),"restored immediate-call phase has no draw and preserves kuikae mask");
        int tile=b.view(PLAYERS.get(1)).turnOptions().discards().stream().min(Integer::compare).orElseThrow();
        bothDiscard(b,c,tile,false); completeBoth(b,c,Map.of());
        check(c.view(null).deposits()==1,"later ordinary discard does not replay the earlier declaration debit");
    }

    private static void partialClosedKan() {
        for(boolean robbery:List.of(false,true)) {
            var a=closedRobberyRound(); var own=a.view(PLAYERS.getFirst());
            var choice=own.turnOptions().kans().stream().filter(k->k.kind()==KanActions.Kind.CLOSED && k.handTiles().getFirst()/4==27).findFirst().orElseThrow();
            check(a.declareKan(PLAYERS.getFirst(),own.command(),choice)==Status.ACCEPTED,"four actual east copies open the closed-kan window");
            respond(a,3,Simple.PASS); respond(a,2,robbery?Simple.RON:Simple.PASS);
            check(a.view(null).phase()==Phase.KAN_RESPONSES,"two collected responses still leave a real third kokushi choice");
            var b=restore(a);
            check(b.serverState().kans().establishedKans()==0 && b.serverState().wall().replacementDraws()==0
                    && b.serverState().hands().getFirst().tileCounts()[27]==4,"archive never commits a pending closed-kan proposal");
            bothRespond(a,b,1,robbery?Simple.RON:Simple.PASS);
            if(robbery) {
                check(b.view(null).end().orElseThrow().winners().equals(List.of(1,2)),"stored ron and later ron both survive the recovery window");
                check(b.serverState().hands().getFirst().tileCounts()[27]==3 && b.serverState().robbedTile().orElseThrow()/4==27
                        && b.serverState().kans().establishedKans()==0 && b.serverState().wall().replacementDraws()==0
                        && b.view(null).doraIndicators().size()==1,"robbery removes one owned east, adds no kan/rinshan/dora, and accounts for the shared win tile once");
            } else {
                check(b.serverState().hands().getFirst().melds().getFirst().kind()==Meld.Kind.CLOSED_KAN
                        && b.serverState().wall().replacementDraws()==1 && b.view(null).doraIndicators().size()==2,"all passes establish exactly one closed kan, reveal and replacement");
                same(b,restore(b));
            }
        }
    }

    private static FourPlayerRound closedRobberyRound() {
        Wall w=new Wall(); w.hand(0,27,27,27,33,32,31,30,29,28,26,25,24,23); w.live(0,27);
        for(int seat=1;seat<4;seat++) {
            List<Integer> types=new ArrayList<>(); for(int type:ORPHANS) if(type!=27) types.add(type);
            types.add(new int[]{0,8,9}[seat-1]); w.hand(seat,types.stream().mapToInt(Integer::intValue).toArray());
        }
        return w.round();
    }

    private static FourPlayerRound addedKanRound() {
        Wall w=new Wall(); w.hand(1,4,4,18,18,18,19,19,19,20,20,20,5,6);
        for(int seat:List.of(2,3)) w.hand(seat,9,10,11,12,13,14,24,25,26,2,3,27,27);
        int five=w.live(0,4); w.live(1,31); w.live(2,32); w.live(3,33);
        w.exact(56,16); w.exact(122,w.take(6)); w.exact(126,w.take(9)); w.exact(128,w.take(3));
        var round=w.round(); discard(round,five,false); complete(round,Map.of(1,call(round,1,Meld.Kind.PON,List.of(4,4))));
        int six=round.view(PLAYERS.get(1)).turnOptions().discards().stream().filter(t->t/4==5).findFirst().orElseThrow();
        discard(round,six,false); complete(round,Map.of());
        for(int seat:List.of(2,3,0)) {
            check(round.view(null).actorSeat()==seat,"added-kan fixture reaches each real intervening draw");
            discard(round,round.view(PLAYERS.get(seat)).ownDraw().orElseThrow(),false); complete(round,Map.of());
        }
        check(round.view(PLAYERS.get(1)).ownDraw().orElseThrow()==16,"the actual later draw is the unique red five, not a substituted ordinary copy");
        return round;
    }

    private static void addedKanAndDeferred() {
        for(boolean robbery:List.of(true,false)) {
            var a=addedKanRound(); var own=a.view(PLAYERS.get(1));
            var choice=own.turnOptions().kans().stream().filter(k->k.kind()==KanActions.Kind.ADDED).findFirst().orElseThrow();
            List<Integer> originalPon=a.serverState().hands().get(1).melds().getFirst().tiles();
            check(a.declareKan(PLAYERS.get(1),own.command(),choice)==Status.ACCEPTED,"actual original pon upgrades only through its red-fourth-copy proposal");
            check(a.view(PLAYERS.get(2)).responses().contains(Simple.RON) && a.view(PLAYERS.get(3)).responses().contains(Simple.RON),"both real hands have cleared earlier temporary no-yaku misses on their intervening draws");
            respond(a,2,Simple.PASS); var b=restore(a); bothRespond(a,b,3,robbery?Simple.RON:Simple.PASS);
            if(robbery) {
                check(b.serverState().hands().get(1).melds().getFirst().tiles().equals(originalPon)
                        && b.serverState().robbedTile().orElseThrow()==16 && b.serverState().wall().replacementDraws()==0,"robbed kakan retains its original physical pon and removes only the red fourth tile");
                check(b.view(null).end().orElseThrow().values().get(3).aka()==1,"the exact robbed red identity survives into actual scoring");
            } else {
                check(b.serverState().kans().delayedIndicatorPending() && b.view(null).doraIndicators().size()==1
                        && b.serverState().wall().replacementDraws()==1,"survived kakan has one replacement and its own dora still deferred");
                var c=restore(b); byte[] branch=GameArchiveCodec.encodeRound(b); same(b,c);
                var win=b.view(PLAYERS.get(1)); check(win.turnOptions().tsumo(),"real seven-man pair on rinshan makes the actor's actual hand win");
                check(b.tsumo(PLAYERS.get(1),win.command())==Status.ACCEPTED
                        && c.tsumo(PLAYERS.get(1),c.view(PLAYERS.get(1)).command())==Status.ACCEPTED,"restored rinshan win remains offered"); same(b,c);
                var value=c.view(null).end().orElseThrow().values().get(1);
                check(value.han()==6 && value.dora()==0 && value.aka()==1 && c.view(null).doraIndicators().size()==1,"immediate rinshan score excludes the deferred new 4m indicator and its four 5m dora");
                var d=GameArchiveCodec.decodeRound(branch); var e=GameArchiveCodec.decodeRound(branch);
                bothDiscard(d,e,d.view(PLAYERS.get(1)).ownDraw().orElseThrow(),false);
                check(d.view(null).doraIndicators().size()==2 && !d.serverState().kans().delayedIndicatorPending(),"the alternate discard suffix reveals exactly one deferred indicator before responding");
                completeBoth(d,e,Map.of()); same(e,restore(e));
            }
        }
    }

    private static void responsibility() {
        Wall w=new Wall(); w.hand(1,31,31,32,32,33,33,0,0,0,1,1,1,28);
        int white=w.live(0,31),green=w.live(1,32),red=w.live(2,33);
        w.live(3,2); w.live(4,3); w.live(5,4); w.live(6,28); var a=w.round(2,List.of(25_000,25_000,25_000,25_000),0);
        for(int dragon:List.of(white,green,red)) {
            discard(a,dragon,false); complete(a,Map.of(1,call(a,1,Meld.Kind.PON,List.of(dragon/4,dragon/4))));
            if(dragon!=red) {
                int tile=a.view(PLAYERS.get(1)).turnOptions().discards().stream().filter(t->t/4==1).findFirst().orElseThrow();
                discard(a,tile,false); complete(a,Map.of());
            }
        }
        var b=restore(a);
        int two=a.view(PLAYERS.get(1)).turnOptions().discards().stream().filter(t->t/4==1).findFirst().orElseThrow();
        bothDiscard(a,b,two,false); completeBoth(a,b,Map.of());
        for(int seat:List.of(2,3,0)) { bothDiscard(a,b,a.view(PLAYERS.get(seat)).ownDraw().orElseThrow(),false); completeBoth(a,b,Map.of()); }
        check(a.tsumo(PLAYERS.get(1),a.view(PLAYERS.get(1)).command())==Status.ACCEPTED
                && b.tsumo(PLAYERS.get(1),b.view(PLAYERS.get(1)).command())==Status.ACCEPTED,"restored three-dragon history reaches its actual self draw"); same(a,b);
        check(b.view(null).end().orElseThrow().transfers().stream().allMatch(t->t.fromSeat()==2 && t.toSeat()==1),"restored final-dragon provider retains all responsibility including two honba");
        check(b.view(null).seats().stream().map(PublicSeat::points).toList().equals(List.of(25_000,57_600,-7_600,25_000)),"restored pao history produces its independently known full payment");
    }

    private static void fourthKanGate() {
        Wall w=new Wall(); int[] dealer={0,1,2,3,40,41,42,44,45,46,48,49,108};
        for(int i=0;i<13;i++) w.exact(i*4,dealer[i]);
        for(int seat=1;seat<4;seat++) for(int copy=0;copy<4;copy++) w.exact(copy*4+seat,seat*4+copy);
        int[] live={68,72,76,80},replacement={50,84,85,109};
        for(int i=0;i<4;i++) { w.exact(52+i,live[i]); w.exact(122+i,replacement[i]); }
        var a=w.round();
        for(int seat=0;seat<4;seat++) {
            int type=seat; var view=a.view(PLAYERS.get(seat));
            var choice=view.turnOptions().kans().stream().filter(k->k.kind()==KanActions.Kind.CLOSED && k.handTiles().getFirst()/4==type).findFirst().orElseThrow();
            check(a.declareKan(PLAYERS.get(seat),view.command(),choice)==Status.ACCEPTED,"fourth-gate fixture establishes each real owner's kan"); complete(a,Map.of());
            if(seat<3) { discard(a,live[seat],false); complete(a,Map.of()); }
        }
        var b=restore(a);
        check(b.serverState().kans().fourthStage()==KanRoundState.FourthStage.NEEDS_DISCARD
                && b.view(null).liveTiles()==62 && b.serverState().wall().replacementDraws()==4,"saved fourth kan still requires its actual replacement discard");
        byte[] branch=GameArchiveCodec.encodeRound(a); bothDiscard(a,b,109,false); var c=restore(b);
        bothRespond(b,c,0,Simple.RON);
        check(c.view(null).end().orElseThrow().values().get(0).yakumanUnits()==2
                && c.view(null).seats().get(3).points()==-71_000,"restored exact fourth-discard window pays the real double-suuankou ron before abort");
        var d=GameArchiveCodec.decodeRound(branch); var e=GameArchiveCodec.decodeRound(branch);
        bothDiscard(d,e,80,false); completeBoth(d,e,Map.of());
        check(e.view(null).end().orElseThrow().kind()==EndKind.FOUR_KANS && e.view(null).liveTiles()==62,"alternate restored fourth-kan suffix aborts only after the discarded tile is unclaimed, without another draw");
    }

    private static void matchVotesAndFinalPool() {
        var a=intermissionFixture(); var token=a.view(null).continueId();
        byte[] encoded=GameArchiveCodec.encodeMatch(a); ArchiveFactory restoredFactory=new ArchiveFactory();
        var b=GameArchiveCodec.decodeMatch(encoded,restoredFactory);
        check(restoredFactory.calls==0,"loading a match must not replay earlier hands or call its future round factory"); sameMatch(a,b);
        check(b.view(null).confirmations().equals(List.of(true,true,false,true)) && b.view(null).continueEpoch()==token.epoch(),"core archive retains exactly three votes and their saved epoch; lifecycle clearing is separate");
        restoredFactory.forcedId=new UUID(162,1); var paid=b.view(null);
        expectInvalid(()->b.confirmNext(PLAYERS.get(2),b.view(null).continueId()),"restored used-round IDs reject a factory's historical first UUID");
        check(b.view(null).equals(paid),"failed next-round construction preserves the paid result and all three saved votes");
        restoredFactory.forcedId=null;
        check(a.confirmNext(PLAYERS.get(2),token)==Status.ACCEPTED
                && b.confirmNext(PLAYERS.get(2),token)==Status.ACCEPTED,"only the missing fourth confirmation creates the next real round"); sameMatch(a,b);
        check(b.view(null).round().honba()==5 && b.view(null).round().deposits()==4
                && b.serverState().ledger().points().equals(List.of(96_000,0,0,0)),"future factory receives exact saved next honba, bank and pool without resetting the match");
        int turns=0;
        while(a.view(null).phase()==FourPlayerMatch.Phase.PLAYING) {
            check(++turns<=70,"final real exhaustive wall has a finite draw budget");
            var av=a.view(null).round(); int actor=av.actorSeat(); var own=a.view(PLAYERS.get(actor)).round();
            int tile=own.ownDraw().orElseGet(()->own.turnOptions().discards().stream().min(Integer::compare).orElseThrow());
            matchDiscard(a,tile,false); matchDiscard(b,tile,false); matchComplete(a); matchComplete(b); sameMatch(a,b);
        }
        check(b.view(null).phase()==FourPlayerMatch.Phase.MATCH_RESULT
                && b.serverState().ledger().points().equals(List.of(99_000,-1_000,-1_000,-1_000))
                && b.serverState().ledger().depositSticks()==4,"closed final round retains its pre-final-award bank and original pool");
        check(b.view(null).result().orElseThrow().standings().stream().map(MatchRules.Standing::points).toList().equals(List.of(103_000,-1_000,-1_000,-1_000)),"final result separately awards the four saved sticks once");
        ArchiveFactory forbiddenFactory=new ArchiveFactory(); forbiddenFactory.forbidden=true;
        var c=GameArchiveCodec.decodeMatch(GameArchiveCodec.encodeMatch(b),forbiddenFactory);
        check(forbiddenFactory.calls==0,"loading already-final standings never deals a replacement round"); sameMatch(b,c);
        for(int i=0;i<3;i++) check(c.view(null).result().orElseThrow().equals(b.view(null).result().orElseThrow()),"reopening final results cannot repeatedly add the 4000-point deposit award");
        check(c.confirmNext(PLAYERS.getFirst(),c.view(null).continueId())==Status.WRONG_PHASE,"restored final match cannot accept a continue vote");
        sameMatch(b,c);
    }

    private static FourPlayerMatch intermissionFixture() {
        var a=new FourPlayerMatch(new UUID(161,1),PLAYERS,MatchRules.Format.SOUTH,0,new ArchiveFactory());
        for(int hand=0;hand<4;hand++) {
            if(hand>0) for(int seat=0;seat<4;seat++) { matchDiscard(a,a.view(PLAYERS.get(seat)).round().ownDraw().orElseThrow(),false); matchComplete(a); }
            check(a.view(PLAYERS.getFirst()).round().turnOptions().tsumo(),"real carried-score fixture reaches its exact dealer win");
            check(a.tsumo(PLAYERS.getFirst(),a.view(PLAYERS.getFirst()).command())==Status.ACCEPTED,"match win settles through actual authority");
            matchConfirmAll(a);
        }
        for(int seat=0;seat<4;seat++) { matchDiscard(a,a.view(PLAYERS.get(seat)).round().ownDraw().orElseThrow(),true); matchComplete(a); }
        check(a.view(null).phase()==FourPlayerMatch.Phase.ROUND_RESULT && a.view(null).round().deposits()==4
                && a.serverState().ledger().points().equals(List.of(96_000,0,0,0)),"sixth-wall trajectory has four genuinely funded sticks and non-25000 scores");
        check(a.clearNextConfirmations(),"a real absence advances the intermission epoch before the saved votes");
        var token=a.view(null).continueId();
        for(int seat:List.of(3,1,0)) check(a.confirmNext(PLAYERS.get(seat),token)==Status.ACCEPTED,"three real confirmations remain below the redeal threshold");
        return a;
    }

    private static void invalidFraming() {
        var round=closedRobberyRound(); byte[] valid=GameArchiveCodec.encodeRound(round);
        for(int offset:List.of(0,4,8,12,16)) {
            byte[] bad=valid.clone(); ByteBuffer.wrap(bad).putInt(offset,ByteBuffer.wrap(bad).getInt(offset)+1);
            expectInvalid(()->GameArchiveCodec.decodeRound(sign(bad)),"unsupported magic/schema/replay/type/rule revision must be rejected after a valid checksum");
        }
        int idLength=ByteBuffer.wrap(valid).getInt(20),body=28+idLength,bodySize=ByteBuffer.wrap(valid).getInt(24+idLength);
        for(int value:List.of(-1,Integer.MAX_VALUE)) {
            byte[] badId=valid.clone(); ByteBuffer.wrap(badId).putInt(20,value);
            expectInvalid(()->GameArchiveCodec.decodeRound(sign(badId)),"invalid rule ID length rejected before allocating its string");
            byte[] malformed=valid.clone(); ByteBuffer.wrap(malformed).putInt(24+idLength,value);
            expectInvalid(()->GameArchiveCodec.decodeRound(sign(malformed)),"invalid archive body length rejected before allocation");
        }
        for(int length:List.of(0,1,4,16,body-1,body,valid.length-33,valid.length-1)) {
            byte[] truncated=Arrays.copyOf(valid,length);
            expectInvalid(()->GameArchiveCodec.decodeRound(truncated),"truncated archive is never treated as a partial valid snapshot");
        }
        byte[] changed=valid.clone(); changed[body+bodySize/2]^=1;
        expectInvalid(()->GameArchiveCodec.decodeRound(changed),"single payload corruption fails its checksum");
        expectInvalid(()->GameArchiveCodec.decodeRound(new byte[GameArchiveCodec.MAX_BYTES+1]),"whole archive size is bounded before parsing");
        byte[] trailing=new byte[valid.length+1]; System.arraycopy(valid,0,trailing,0,valid.length-32);
        expectInvalid(()->GameArchiveCodec.decodeRound(sign(trailing)),"even correctly signed trailing bytes are rejected");
        for(int count:List.of(-1,135,Integer.MAX_VALUE)) {
            byte[] bad=valid.clone(); ByteBuffer.wrap(bad).putInt(body+112,count);
            expectInvalid(()->GameArchiveCodec.decodeRound(sign(bad)),"initial complete-wall count is exactly bounded at 136");
        }
        byte[] duplicate=valid.clone(); ByteBuffer bytes=ByteBuffer.wrap(duplicate);
        bytes.putInt(body+120,bytes.getInt(body+116));
        expectInvalid(()->GameArchiveCodec.decodeRound(sign(duplicate)),"correctly signed duplicate physical wall identity rejected");
        byte[] excessive=valid.clone(); ByteBuffer.wrap(excessive).putInt(body+660,GameArchiveCodec.MAX_ACTIONS+1);
        expectInvalid(()->GameArchiveCodec.decodeRound(sign(excessive)),"replay action count is bounded before collection allocation");

        Wall w=new Wall(); w.hand(0,13,13,13,14,14,14,15,15,15,16,16,16,28);
        for(int seat=1;seat<4;seat++) w.hand(seat,0,1,2,3,4,5,9,10,11,18,19,20,27);
        int east=w.live(0,27); var partial=w.round(); discard(partial,east,true); respond(partial,3,Simple.PASS); respond(partial,2,Simple.RON);
        byte[] journal=GameArchiveCodec.encodeRound(partial); int journalBody=28+ByteBuffer.wrap(journal).getInt(20),first=journalBody+664;
        byte[] notOwned=journal.clone(); ByteBuffer.wrap(notOwned).putInt(first+8,135);
        expectInvalid(()->GameArchiveCodec.decodeRound(sign(notOwned)),"signed replay cannot discard a tile that the actual actor never held");
        byte[] stale=journal.clone(); ByteBuffer.wrap(stale).putLong(first+28+12,999);
        expectInvalid(()->GameArchiveCodec.decodeRound(sign(stale)),"signed replay rejects a response for the wrong current window");
        byte[] wrongSeat=journal.clone(); ByteBuffer.wrap(wrongSeat).putInt(first+56+4,3);
        expectInvalid(()->GameArchiveCodec.decodeRound(sign(wrongSeat)),"signed replay rejects a second answer from a seat that already passed");
        byte[] badLength=journal.clone(); ByteBuffer.wrap(badLength).putInt(first+24,Integer.MAX_VALUE);
        expectInvalid(()->GameArchiveCodec.decodeRound(sign(badLength)),"replay hand selection count has a small pre-allocation bound");
        byte[] fingerprint=journal.clone(); fingerprint[fingerprint.length-33]^=1;
        expectInvalid(()->GameArchiveCodec.decodeRound(sign(fingerprint)),"successful actions still must reproduce the saved final-state fingerprint");
        byte[] before=GameArchiveCodec.encodeRound(partial); Id id=partial.view(null).opportunity().orElseThrow().id();
        check(partial.respond(PLAYERS.get(2),id,Simple.RON)==Status.ALREADY_RESPONDED,"rejected duplicate response is a genuine current API rejection");
        check(Arrays.equals(before,GameArchiveCodec.encodeRound(partial)),"rejected actions are not appended to the accepted replay journal");
        same(partial,GameArchiveCodec.decodeRound(before));
        ArchiveFactory factory=new ArchiveFactory(); var match=new FourPlayerMatch(new UUID(164,1),PLAYERS,MatchRules.Format.EAST,0,factory);
        byte[] matchBytes=GameArchiveCodec.encodeMatch(match); ArchiveFactory unused=new ArchiveFactory(); unused.forbidden=true;
        expectInvalid(()->GameArchiveCodec.decodeRound(matchBytes),"match archive cannot be mistaken for a round archive");
        expectInvalid(()->GameArchiveCodec.decodeMatch(valid,unused),"round archive cannot be mistaken for a match archive");
        check(unused.calls==0,"bad archive decoding never calls the external future-round factory");
    }

    private static byte[] sign(byte[] bytes) {
        byte[] result=bytes.clone();
        try { byte[] hash=MessageDigest.getInstance("SHA-256").digest(Arrays.copyOf(result,result.length-32)); System.arraycopy(hash,0,result,result.length-32,32); return result; }
        catch(java.security.NoSuchAlgorithmException failure) { throw new AssertionError(failure); }
    }

    private static Path projectRoot() {
        Path temporary=Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        if(!temporary.getFileName().toString().equals(".tmp")) throw new AssertionError("Archive subprocess tests require the project-local .tmp");
        return temporary.getParent();
    }
    private static void crossJvm() {
        try {
            Path root=projectRoot(); Path javaHome=Path.of(System.getProperty("java.home")).toAbsolutePath().normalize();
            check(javaHome.startsWith(root.resolve(".tools/java")),"subprocess Java executable is the approved project-local toolchain");
            String classPath=System.getProperty("java.class.path");
            for(String item:classPath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                check(Path.of(item).toAbsolutePath().normalize().startsWith(root),"subprocess classpath contains only this workspace");
            }
            Path directory=Files.createTempDirectory(root.resolve(".tmp"),"archive-cross-jvm-");
            for(String mode:List.of("--archive-write","--archive-read")) {
                Path output=directory.resolve(mode.substring(2)+".log");
                List<String> command=List.of(javaHome.resolve("bin/java.exe").toString(),
                        "-Duser.home="+root.resolve(".tools/home"),"-Djava.io.tmpdir="+root.resolve(".tmp"),
                        "-ea","--class-path",classPath,ArchiveTests.class.getName(),mode,directory.toString());
                Process process=new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
                if(!process.waitFor(30,TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("Archive child process timed out"); }
                String log=Files.readString(output);
                check(process.exitValue()==0 && log.contains("PASS archive child "),"fresh JVM archive roundtrip succeeded: "+mode+"\n"+log);
                var count=java.util.regex.Pattern.compile("PASS archive child (?:write|read): ([0-9]+) assertions").matcher(log);
                check(count.find(),"subprocess reports the assertions it actually executed");
                assertions=Math.addExact(assertions,Integer.parseInt(count.group(1)));
            }
        } catch(Exception failure) { throw new AssertionError("Cross-JVM archive verification failed",failure); }
    }
    private static void childMode(String[] args) throws Exception {
        if(args.length!=2) throw new IllegalArgumentException("Expected one test mode and workspace temporary directory");
        Path directory=Path.of(args[1]).toAbsolutePath().normalize(),root=projectRoot();
        if(!directory.startsWith(root.resolve(".tmp"))) throw new IllegalArgumentException("Test archive directory must stay inside project .tmp");
        if(args[0].equals("--archive-write")) {
            var partial=partialFixture(); Files.write(directory.resolve("partial.arc"),GameArchiveCodec.encodeRound(partial));
            respond(partial,1,Simple.RON);
            check(partial.view(null).end().orElseThrow().values().size()==2,"cross-process scored fixture has two real ScoreResults");
            Files.write(directory.resolve("won.arc"),GameArchiveCodec.encodeRound(partial));
            Files.write(directory.resolve("ambiguous-win.arc"),GameArchiveCodec.encodeRound(ambiguousWinFixture()));
            var match=intermissionFixture(); check(match.confirmNext(PLAYERS.get(2),match.view(null).continueId())==Status.ACCEPTED,"writer's fourth real vote starts final wall");
            int turns=0;
            while(match.view(null).phase()==FourPlayerMatch.Phase.PLAYING) {
                check(++turns<=70,"cross-process final complete wall terminates");
                int actor=match.view(null).round().actorSeat(); var own=match.view(PLAYERS.get(actor)).round();
                matchDiscard(match,own.ownDraw().orElseThrow(),false); matchComplete(match);
            }
            check(match.view(null).result().orElseThrow().depositPoints()==4_000,"writer has actually awarded the final four sticks");
            Files.write(directory.resolve("final-match.arc"),GameArchiveCodec.encodeMatch(match));
            System.out.printf("PASS archive child write: %d assertions%n",assertions);
        } else if(args[0].equals("--archive-read")) {
            byte[] bytes=Files.readAllBytes(directory.resolve("partial.arc")); var partial=GameArchiveCodec.decodeRound(bytes);
            check(Arrays.equals(bytes,GameArchiveCodec.encodeRound(partial)),"new JVM preserves byte-stable partial response archive and canonical fingerprint");
            check(partial.view(null).phase()==Phase.DISCARD_RESPONSES
                    && partial.serverState().riichi().getFirst().phase()==RiichiState.Phase.PENDING
                    && partial.view(PLAYERS.get(2)).responses().isEmpty() && partial.view(PLAYERS.get(3)).responses().isEmpty()
                    && partial.view(PLAYERS.get(1)).responses().contains(Simple.RON),"new JVM retains partial ron/pass and pending declaration masks");
            respond(partial,1,Simple.RON); byte[] wonBytes=Files.readAllBytes(directory.resolve("won.arc"));
            var won=GameArchiveCodec.decodeRound(wonBytes); same(partial,won);
            check(Arrays.equals(wonBytes,GameArchiveCodec.encodeRound(won)),"new JVM retains byte-stable fully scored win, including tied best-score interpretation");
            check(won.view(null).end().orElseThrow().winners().equals(List.of(1,2))
                    && won.view(null).end().orElseThrow().values().values().stream().allMatch(v->v.han()>0),"actual scored winner receipts survive a new Java process");
            byte[] ambiguousBytes=Files.readAllBytes(directory.resolve("ambiguous-win.arc")); var ambiguous=GameArchiveCodec.decodeRound(ambiguousBytes);
            check(Arrays.equals(ambiguousBytes,GameArchiveCodec.encodeRound(ambiguous))
                    && ambiguous.view(null).end().orElseThrow().values().get(0).han()==11,"new JVM retains exact selected explanation and fingerprint among actual equal-highest scoring candidates");
            byte[] matchBytes=Files.readAllBytes(directory.resolve("final-match.arc")); ArchiveFactory forbidden=new ArchiveFactory(); forbidden.forbidden=true;
            var match=GameArchiveCodec.decodeMatch(matchBytes,forbidden);
            check(forbidden.calls==0 && Arrays.equals(matchBytes,GameArchiveCodec.encodeMatch(match)),"new JVM preserves final match bytes without any future factory call");
            check(match.view(null).result().orElseThrow().standings().stream().map(MatchRules.Standing::points).toList().equals(List.of(103_000,-1_000,-1_000,-1_000))
                    && match.serverState().ledger().points().equals(List.of(99_000,-1_000,-1_000,-1_000))
                    && match.serverState().ledger().depositSticks()==4,"new process does not award a second pool or mutate the closed final round");
            auditOwnership(match.view(null).round(),match.serverState());
            System.out.printf("PASS archive child read: %d assertions%n",assertions);
        } else throw new IllegalArgumentException("Unknown archive child test mode");
    }
    private static FourPlayerRound restore(FourPlayerRound source) {
        var first=GameArchiveCodec.decodeRound(GameArchiveCodec.encodeRound(source)); same(source,first);
        var second=GameArchiveCodec.decodeRound(GameArchiveCodec.encodeRound(first)); same(first,second); return second;
    }
    private static void same(FourPlayerRound a,FourPlayerRound b) {
        check(a.view(null).equals(b.view(null)),"restored public phase, window, dora, river and end receipt equal the unbroken trajectory");
        for(UUID player:PLAYERS) check(a.view(player).equals(b.view(player)),"each private hand, exact legal mask and answered/pending response state equals the original");
        sameState(a.serverState(),b.serverState()); a.assertConservation(); b.assertConservation();
    }
    private static void sameState(FourPlayerRound.ServerState a,FourPlayerRound.ServerState b) {
        check(a.hands().equals(b.hands()) && a.histories().equals(b.histories()) && a.robbedTile().equals(b.robbedTile()),"private ownership, furiten and external robbed tile agree");
        check(a.wall().liveTiles().equals(b.wall().liveTiles()) && a.wall().deadTiles().equals(b.wall().deadTiles())
                && a.wall().replacementDraws()==b.wall().replacementDraws(),"exact future live/rinshan/indicator order and dead-wall tail replacement agree");
        check(a.kans().kansBySeat().equals(b.kans().kansBySeat()) && a.kans().revealedIndicators()==b.kans().revealedIndicators()
                && a.kans().delayedIndicatorPending()==b.kans().delayedIndicatorPending() && a.kans().fourthStage()==b.kans().fourthStage()
                && a.kans().fourthWindow().equals(b.kans().fourthWindow()),"kan ownership, deferred reveal and exact fourth-discard gate agree");
        for(int seat=0;seat<4;seat++) {
            var x=a.riichi().get(seat); var y=b.riichi().get(seat);
            check(x.phase()==y.phase() && x.establishedRiichi()==y.establishedRiichi() && x.ippatsu()==y.ippatsu()
                    && x.waitTypes().equals(y.waitTypes()) && x.pending().equals(y.pending()),"pending/established riichi, wait types, ippatsu and bound window survive");
        }
        check(a.ledger().roundId().equals(b.ledger().roundId()) && a.ledger().points().equals(b.ledger().points())
                && a.ledger().depositSticks()==b.ledger().depositSticks() && a.ledger().honba()==b.ledger().honba()
                && a.ledger().dealerSeat()==b.ledger().dealerSeat() && a.ledger().finished()==b.ledger().finished(),"real paid account, carried pool, dealer/honba and closed flag agree");
        check(b.ledger().points().stream().mapToLong(Integer::longValue).sum()+b.ledger().depositSticks()*1_000L==100_000L,"decoded real round bank including pool conserves 100000");
    }
    private static void sameMatch(FourPlayerMatch a,FourPlayerMatch b) {
        check(a.view(null).equals(b.view(null)),"match phase/index/next-votes/epoch/final standings agree");
        for(UUID player:PLAYERS) check(a.view(player).equals(b.view(player)),"restored match supplies the same recipient-private round view");
        sameState(a.serverState(),b.serverState());
        auditOwnership(a.view(null).round(),a.serverState()); auditOwnership(b.view(null).round(),b.serverState());
    }
    private static void auditOwnership(View view,ServerState state) {
        List<Integer> owned=new ArrayList<>();
        for(var hand:state.hands()) { owned.addAll(hand.concealedTiles()); hand.melds().forEach(m->owned.addAll(m.tiles())); }
        for(var seat:view.seats()) for(var river:seat.river()) if(river.claimedBy()<0) owned.add(river.tileId());
        owned.addAll(state.wall().liveTiles()); owned.addAll(state.wall().deadTiles()); state.robbedTile().ifPresent(owned::add);
        check(owned.size()==136 && new HashSet<>(owned).size()==136 && owned.stream().allMatch(t->t>=0 && t<136),"independent ownership bag has exactly the original 136 physical copies without counted-twice calls or ron");
        check(state.wall().deadTiles().size()==14,"decoded complete match still has fourteen dead-wall copies");
    }
    private static void discard(FourPlayerRound round,int tile,boolean riichi) {
        int actor=round.view(null).actorSeat(); check(round.discard(PLAYERS.get(actor),round.view(PLAYERS.get(actor)).command(),tile,riichi)==Status.ACCEPTED,"fixture uses an accepted physical discard");
    }
    private static void respond(FourPlayerRound round,int seat,Choice choice) {
        check(round.respond(PLAYERS.get(seat),round.view(null).opportunity().orElseThrow().id(),choice)==Status.ACCEPTED,"fixture uses an offered current response");
    }
    private static void bothDiscard(FourPlayerRound a,FourPlayerRound b,int tile,boolean riichi) { discard(a,tile,riichi); discard(b,tile,riichi); same(a,b); }
    private static void bothRespond(FourPlayerRound a,FourPlayerRound b,int seat,Choice choice) { respond(a,seat,choice); respond(b,seat,choice); same(a,b); }
    private static Call call(FourPlayerRound round,int seat,Meld.Kind kind,List<Integer> types) {
        var wanted=types.stream().sorted().toList();
        return round.view(PLAYERS.get(seat)).responses().stream().filter(c->c instanceof Call).map(c->(Call)c)
                .filter(c->c.kind()==kind && c.handTiles().stream().map(t->t/4).sorted().toList().equals(wanted)).findFirst().orElseThrow();
    }
    private static void complete(FourPlayerRound round,Map<Integer,? extends Choice> choices) {
        var original=round.view(null).opportunity(); if(original.isEmpty()) { check(choices.isEmpty(),"special responses require an active window"); return; }
        for(int seat=0;seat<4;seat++) if(!round.view(PLAYERS.get(seat)).responses().isEmpty()) respond(round,seat,choices.containsKey(seat)?choices.get(seat):Simple.PASS);
    }
    private static void completeBoth(FourPlayerRound a,FourPlayerRound b,Map<Integer,? extends Choice> choices) { complete(a,choices); complete(b,choices); same(a,b); }
    private static void matchDiscard(FourPlayerMatch match,int tile,boolean riichi) {
        int actor=match.view(null).round().actorSeat(); check(match.discard(PLAYERS.get(actor),match.view(PLAYERS.get(actor)).command(),tile,riichi)==Status.ACCEPTED,"fixture uses authenticated match discard");
    }
    private static void matchComplete(FourPlayerMatch match) {
        var original=match.view(null).round().opportunity(); if(original.isEmpty()) return;
        for(int seat=0;seat<4;seat++) if(!match.view(PLAYERS.get(seat)).round().responses().isEmpty()) check(match.respond(PLAYERS.get(seat),original.orElseThrow().id(),Simple.PASS)==Status.ACCEPTED,"match fixture explicitly passes remaining responses");
    }
    private static void matchConfirmAll(FourPlayerMatch match) {
        var token=match.view(null).continueId(); for(UUID player:PLAYERS) check(match.confirmNext(player,token)==Status.ACCEPTED,"real match advances only through four confirmations");
    }
    private static final class ArchiveFactory implements FourPlayerMatch.RoundFactory {
        int calls; boolean forbidden; UUID forcedId;
        public FourPlayerRound create(FourPlayerMatch.RoundSetup setup) {
            calls++; if(forbidden) throw new AssertionError("Restoration unexpectedly called round factory");
            Wall w=new Wall(); int dealer=setup.dealerSeat();
            switch(setup.honba()) {
                case 0 -> { w.hand(dealer,0,1,2,3,4,5,15,16,17,18,19,20,27); w.live(0,27); w.indicator(31); }
                case 1 -> { w.hand(dealer,1,2,3,10,11,12,19,20,21,14,15,22,22); delayed(w,16,31); }
                case 2 -> { w.hand(dealer,1,1,1,12,12,12,23,24,25,2,4,14,14); delayed(w,3,1); }
                case 3 -> { w.hand(dealer,0,0,0,3,4,5,17,17,17,20,22,27,27); delayed(w,21,31); }
                case 4 -> { for(int seat=0;seat<4;seat++) w.hand(seat,0,1,2,3,4,5,9,10,11,18,19,20,27+seat); for(int turn=0;turn<4;turn++) w.live(turn,31); }
                case 5 -> {
                    w.hand(dealer,0,1,2,3,4,5,9,10,11,18,19,20,27);
                    for(int seat=0;seat<4;seat++) if(seat!=dealer) w.hand(seat,27,28,29,30,31,32,33,10,12,14,16,19,21);
                    for(int draw=0;draw<4;draw++) w.live(draw,draw+1);
                }
                default -> throw new AssertionError("Unexpected future archived match configuration");
            }
            UUID id=forcedId==null?new UUID(162,setup.honba()+1):forcedId;
            return FourPlayerRound.ordered(id,setup.players(),dealer,setup.roundWind(),setup.honba(),setup.points(),setup.deposits(),w.order());
        }
        private static void delayed(Wall w,int winner,int indicator) { w.live(0,31); w.live(1,32); w.live(2,33); w.live(3,26); w.live(4,winner); w.indicator(indicator); }
    }
    private static final class Wall {
        final int[] tiles=new int[136]; final boolean[] used=new boolean[136];
        Wall() { Arrays.fill(tiles,-1); }
        void exact(int position,int tile) {
            if(position<0 || position>=136 || tile<0 || tile>=136 || tiles[position]>=0 || used[tile]) throw new AssertionError("Bad physical-wall fixture");
            tiles[position]=tile; used[tile]=true;
        }
        int take(int type) {
            int[] copies=type==4 || type==13 || type==22?new int[]{1,2,3,0}:new int[]{0,1,2,3};
            for(int copy:copies) if(!used[type*4+copy]) return type*4+copy;
            throw new AssertionError("Fixture needs fifth copy");
        }
        void hand(int seat,int...types) { if(types.length!=13) throw new AssertionError("Initial hand length"); for(int index=0;index<13;index++) exact(index*4+seat,take(types[index])); }
        int live(int offset,int type) { int tile=take(type); exact(52+offset,tile); return tile; }
        void indicator(int type) { exact(126,take(type)); }
        List<Integer> order() {
            int tile=0; for(int position=0;position<136;position++) if(tiles[position]<0) { while(used[tile]) tile++; exact(position,tile); }
            var result=Arrays.stream(tiles).boxed().toList(); check(new HashSet<>(result).size()==136,"fixture contains all 136 distinct physical copies"); return result;
        }
        FourPlayerRound round() { return round(0,List.of(25_000,25_000,25_000,25_000),0); }
        FourPlayerRound round(int honba,List<Integer> points,int deposits) { return FourPlayerRound.ordered(new UUID(152,++serial),PLAYERS,0,27,honba,points,deposits,order()); }
    }
    private static void check(boolean condition,String message) { assertions++; if(!condition) throw new AssertionError(message); }
    private static void expectInvalid(Runnable action,String message) {
        assertions++; try { action.run(); } catch(IllegalArgumentException expected) { return; }
        throw new AssertionError(message+"; expected IllegalArgumentException");
    }
    private static void run(String name,Runnable action) { try { action.run(); } catch(Throwable failure) { throw new AssertionError("Archive group failed: "+name,failure); } }
}
