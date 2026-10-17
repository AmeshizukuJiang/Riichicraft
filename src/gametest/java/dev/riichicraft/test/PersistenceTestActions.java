package dev.riichicraft.test;

import dev.riichicraft.network.GameSnapshot;
import dev.riichicraft.rules.CallActions;
import dev.riichicraft.rules.HandInput;
import dev.riichicraft.rules.WinContext;
import java.util.List;

/** Test-only legal guidance from four separately authorized private views; never injects engine state. */
public final class PersistenceTestActions {
    private PersistenceTestActions() { }

    /** Prefer a real legal discard that offers calls to two players, otherwise keep normal drawn-tile play. */
    public static int multiResponseDiscard(List<GameSnapshot> recipients) {
        int actor=recipients.getFirst().turnSeat(); GameSnapshot own=recipients.get(actor);
        int fallback=own.rules().legalDiscards().contains(own.drawnTile())?own.drawnTile():own.rules().legalDiscards().getFirst();
        // Persistence trajectories deliberately pass every call and do not declare riichi or kan.
        if(own.rules().seats().stream().anyMatch(seat->!seat.melds().isEmpty() || seat.riichiPhase()!=0)) return fallback;
        for(int tile:own.rules().legalDiscards()) {
            int callers=0;
            for(int seat=0;seat<4;seat++) {
                if(seat==actor) continue;
                var hand=new HandInput(recipients.get(seat).ownHand(),List.of());
                var context=new CallActions.Context(actor,seat,own.remainingTiles(),0,WinContext.Riichi.NONE);
                if(!CallActions.options(hand,tile,context).isEmpty()) callers++;
            }
            if(callers>=2) return tile;
        }
        return fallback;
    }
}
