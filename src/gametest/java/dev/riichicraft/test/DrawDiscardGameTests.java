package dev.riichicraft.test;

import dev.riichicraft.game.DrawDiscardGame;
import dev.riichicraft.game.DrawDiscardGame.PrivateSeat;
import dev.riichicraft.game.DrawDiscardGame.ServerState;
import dev.riichicraft.game.DrawDiscardGame.Status;
import dev.riichicraft.game.DrawDiscardGame.View;
import dev.riichicraft.game.TileCatalog;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Pure core checks; real payload privacy, screen input and lifecycle are tested by the adapting layer. */
public final class DrawDiscardGameTests {
    @GameTest
    public void catalogContainsFourOfEachTypeAndExactlyThreeRedFives(GameTestHelper helper) {
        int[] copies = new int[TileCatalog.TYPE_COUNT];
        List<Integer> red = new ArrayList<>();
        for (int tile : TileCatalog.allTiles()) {
            copies[TileCatalog.typeOf(tile)]++;
            if (TileCatalog.isRed(tile)) red.add(tile);
        }
        helper.assertTrue(TileCatalog.allTiles().size() == 136
                        && new HashSet<>(TileCatalog.allTiles()).size() == 136,
                "Every physical tile must have a unique ID");
        for (int count : copies) helper.assertTrue(count == 4, "Each tile type must have four copies");
        helper.assertTrue(red.equals(List.of(16, 52, 88)), "Only one five per suit must be red");
        helper.assertTrue(TileCatalog.typeOf(16) == 4 && TileCatalog.typeOf(52) == 13
                        && TileCatalog.typeOf(88) == 22 && TileCatalog.typeOf(108) == 27
                        && TileCatalog.typeOf(135) == 33,
                "Suit and honor type indices must follow the documented catalog");
        assertImmutable(helper, TileCatalog.allTiles(), "The catalog cannot be changed by callers");
        helper.succeed();
    }

    @GameTest
    public void dealKeepsThirteenClosedTilesAndOneEastDraw(GameTestHelper helper) {
        DrawDiscardGame game = game(41);
        ServerState state = game.serverState();
        helper.assertTrue(game.revision() == 0 && !game.isFinished() && game.currentSeat() == 0,
                "East must act first in a new game at revision zero");
        helper.assertTrue(game.liveWallCount() == 69 && state.deadWall().size() == 14,
                "Dealing 52 tiles and east's first draw must leave 69 live tiles and 14 dead tiles");
        for (int seat = 0; seat < 4; seat++) {
            PrivateSeat hand = state.seats().get(seat);
            helper.assertTrue(hand.closedHand().size() == 13 && (hand.drawnTile() != null) == (seat == 0),
                    "Only east may begin with a separate fourteenth tile");
            helper.assertTrue(hand.discards().isEmpty(), "The initial rivers must be empty");
        }
        assertConserved(helper, state);
        boolean duplicatesRejected = false;
        try {
            new DrawDiscardGame(id(100), List.of(id(1), id(1), id(3), id(4)), 41);
        } catch (IllegalArgumentException expected) {
            duplicatesRejected = true;
        }
        helper.assertTrue(duplicatesRejected, "One UUID cannot occupy two match seats");
        helper.succeed();
    }

    @GameTest
    public void rejectedIntentionsAndDuplicateRequestsCannotAdvanceTheGame(GameTestHelper helper) {
        DrawDiscardGame game = game(42);
        ServerState initial = game.serverState();
        int eastDraw = initial.seats().getFirst().drawnTile();
        int opponentTile = initial.seats().get(1).closedHand().getFirst();
        helper.assertTrue(game.discard(id(99), eastDraw, 0).status() == Status.NOT_A_PLAYER,
                "An outsider cannot discard another player's tile");
        helper.assertTrue(game.discard(id(2), opponentTile, 0).status() == Status.NOT_YOUR_TURN,
                "South cannot act during east's turn");
        helper.assertTrue(game.discard(id(1), eastDraw, -1).status() == Status.STALE_REVISION,
                "A stale request cannot mutate the current turn");
        helper.assertTrue(game.discard(id(1), opponentTile, 0).status() == Status.TILE_NOT_IN_HAND
                        && game.discard(id(1), -1, 0).status() == Status.TILE_NOT_IN_HAND
                        && game.discard(id(1), 136, 0).status() == Status.TILE_NOT_IN_HAND,
                "Opponent tiles and out-of-range physical IDs cannot be discarded");
        helper.assertTrue(game.serverState().equals(initial),
                "Every rejected request must preserve hands, wall, rivers, turn and revision");
        helper.assertTrue(game.discard(id(1), eastDraw, 0).changed(), "The valid discard must succeed once");
        ServerState after = game.serverState();
        helper.assertTrue(game.discard(id(1), eastDraw, 0).status() == Status.STALE_REVISION,
                "Retrying an already applied request must not produce a second discard or draw");
        helper.assertTrue(game.discard(id(1), eastDraw, game.revision()).status() == Status.NOT_YOUR_TURN,
                "A fresh revision cannot grant the previous actor another turn");
        helper.assertTrue(game.serverState().equals(after) && game.revision() == 1 && game.liveWallCount() == 68,
                "Exactly one valid action must advance exactly one revision and draw exactly one tile");
        assertConserved(helper, after);
        helper.succeed();
    }

    @GameTest
    public void handDiscardAndTsumogiriMoveTheCorrectPhysicalTile(GameTestHelper helper) {
        DrawDiscardGame tsumogiri = game(43);
        PrivateSeat before = tsumogiri.serverState().seats().getFirst();
        tsumogiri.discard(id(1), before.drawnTile(), 0);
        PrivateSeat afterDrawDiscard = tsumogiri.serverState().seats().getFirst();
        helper.assertTrue(afterDrawDiscard.closedHand().equals(before.closedHand())
                        && afterDrawDiscard.drawnTile() == null
                        && afterDrawDiscard.discards().equals(List.of(before.drawnTile())),
                "Tsumogiri must discard the drawn physical tile without changing the closed hand");

        DrawDiscardGame handDiscard = game(43);
        int selected = before.closedHand().getFirst();
        handDiscard.discard(id(1), selected, 0);
        PrivateSeat afterHandDiscard = handDiscard.serverState().seats().getFirst();
        Set<Integer> expected = new HashSet<>(before.closedHand());
        expected.remove(selected);
        expected.add(before.drawnTile());
        helper.assertTrue(afterHandDiscard.closedHand().size() == 13
                        && new HashSet<>(afterHandDiscard.closedHand()).equals(expected)
                        && afterHandDiscard.drawnTile() == null
                        && afterHandDiscard.discards().equals(List.of(selected)),
                "A hand discard must move the selected tile to the river and fold the draw into the closed hand");
        helper.assertTrue(handDiscard.currentSeat() == 1
                        && handDiscard.serverState().seats().get(1).drawnTile() != null,
                "South must automatically receive the next draw after east discards");
        assertConserved(helper, tsumogiri.serverState());
        assertConserved(helper, handDiscard.serverState());
        helper.succeed();
    }

    @GameTest
    public void everyTileIsConservedUntilTheFinalDiscardExhaustsTheWall(GameTestHelper helper) {
        for (long seed = 0; seed < 12; seed++) {
            DrawDiscardGame game = game(seed);
            int actions = 0;
            while (!game.isFinished()) {
                ServerState before = game.serverState();
                assertConserved(helper, before);
                int seat = game.currentSeat();
                PrivateSeat actor = before.seats().get(seat);
                int selected = actions % 2 == 0 ? actor.drawnTile() : actor.closedHand().getFirst();
                int remaining = game.liveWallCount();
                helper.assertTrue(game.discard(actor.playerId(), selected, game.revision()).changed(),
                        "Every legal turn must accept one discard");
                actions++;
                helper.assertTrue(game.revision() == actions, "Revision must count accepted discards only");
                helper.assertTrue(game.liveWallCount() == Math.max(0, remaining - 1),
                        "The final discard must not draw from the dead wall");
                helper.assertTrue(game.currentSeat() == (remaining == 0 ? -1 : (seat + 1) % 4),
                        "Turns must cycle east/south/west/north until the final discard");
                helper.assertTrue(actions <= 70, "The prototype must stop after the 70 live-wall draws");
            }
            ServerState exhausted = game.serverState();
            assertConserved(helper, exhausted);
            helper.assertTrue(actions == 70 && game.liveWallCount() == 0 && exhausted.deadWall().size() == 14
                            && exhausted.seats().stream().allMatch(seat -> seat.drawnTile() == null)
                            && exhausted.seats().stream().mapToInt(seat -> seat.discards().size()).sum() == 70,
                    "Exhaustion must follow the last draw's discard, preserving four closed hands and the dead wall");
            helper.assertTrue(game.discard(id(1), 0, game.revision()).status() == Status.GAME_FINISHED
                            && game.serverState().equals(exhausted),
                    "A finished game must reject further discards without changing its final state");
        }
        helper.succeed();
    }

    @GameTest
    public void recipientViewsExposeOnlyTheirOwnHandAndPublicRivers(GameTestHelper helper) {
        DrawDiscardGame game = game(45);
        int discarded = game.view(id(1)).drawnTile();
        game.discard(id(1), discarded, 0);
        ServerState state = game.serverState();
        for (int seat = 0; seat < 4; seat++) {
            View view = game.view(id(seat + 1));
            PrivateSeat own = state.seats().get(seat);
            helper.assertTrue(view.ownSeat() == seat && view.closedHand().equals(own.closedHand())
                            && java.util.Objects.equals(view.drawnTile(), own.drawnTile()),
                    "A recipient must receive exactly their own closed hand and draw");
            Set<Integer> exposed = new HashSet<>(view.closedHand());
            if (view.drawnTile() != null) exposed.add(view.drawnTile());
            for (int other = 0; other < 4; other++) {
                PrivateSeat serverSeat = state.seats().get(other);
                var publicSeat = view.seats().get(other);
                helper.assertTrue(publicSeat.discards().equals(serverSeat.discards())
                                && publicSeat.handCount() == 13 + (other == game.currentSeat() ? 1 : 0),
                        "All recipients must receive public rivers and accurate concealed-tile counts");
                exposed.addAll(publicSeat.discards());
                if (other != seat) {
                    for (int hidden : serverSeat.closedHand()) {
                        helper.assertTrue(!exposed.contains(hidden), "Opponent physical hand IDs must remain private");
                    }
                    helper.assertTrue(serverSeat.drawnTile() == null || !exposed.contains(serverSeat.drawnTile()),
                            "An opponent's separate draw must remain private");
                }
            }
            helper.assertTrue(exposed.contains(discarded), "A discarded tile must become public for all four seats");
            for (int hidden : state.liveWall()) helper.assertTrue(!exposed.contains(hidden), "Future draws remain secret");
            for (int hidden : state.deadWall()) helper.assertTrue(!exposed.contains(hidden), "Dead-wall tiles remain secret");
        }
        View outsider = game.view(id(99));
        helper.assertTrue(outsider.ownSeat() == -1 && outsider.closedHand().isEmpty() && outsider.drawnTile() == null
                        && outsider.seats().equals(game.view(id(1)).seats()),
                "An outsider may receive public data but must not inherit any participant's private hand");
        helper.succeed();
    }

    @GameTest
    public void fixedSeedsAndPublishedSnapshotsRemainReproducibleAndImmutable(GameTestHelper helper) {
        List<UUID> mutablePlayers = new ArrayList<>(players());
        DrawDiscardGame first = new DrawDiscardGame(id(100), mutablePlayers, 46);
        DrawDiscardGame second = game(46);
        mutablePlayers.set(0, id(99));
        ServerState original = first.serverState();
        View originalView = first.view(id(1));
        helper.assertTrue(original.equals(second.serverState()) && first.players().equals(players()),
                "The fixed seed must reproduce the entire deal and caller list changes cannot replace a player");
        helper.assertTrue(!original.equals(game(47).serverState()), "A different seed must change the tile order");
        for (int turn = 0; turn < 8; turn++) {
            UUID actor = first.players().get(first.currentSeat());
            int tile = first.view(actor).drawnTile();
            first.discard(actor, tile, first.revision());
            second.discard(actor, tile, second.revision());
        }
        helper.assertTrue(first.serverState().equals(second.serverState()),
                "The same seed and accepted intentions must reproduce all later state");
        helper.assertTrue(original.revision() == 0 && original.liveWall().size() == 69
                        && original.seats().stream().allMatch(seat -> seat.discards().isEmpty())
                        && originalView.revision() == 0 && originalView.seats().getFirst().discards().isEmpty(),
                "Later draws and discards must not mutate already published snapshots");
        assertImmutable(helper, first.players(), "The player order must be immutable");
        assertImmutable(helper, original.seats(), "The full-state seat list must be immutable");
        assertImmutable(helper, original.liveWall(), "The full-state live wall must be immutable");
        assertImmutable(helper, original.deadWall(), "The full-state dead wall must be immutable");
        assertImmutable(helper, original.seats().getFirst().closedHand(), "Private closed hands must be immutable");
        assertImmutable(helper, first.serverState().seats().getFirst().discards(), "Server rivers must be immutable");
        assertImmutable(helper, originalView.seats(), "Recipient seat lists must be immutable");
        assertImmutable(helper, originalView.closedHand(), "Recipient private hands must be immutable");
        assertImmutable(helper, first.view(id(1)).seats().getFirst().discards(), "Recipient rivers must be immutable");
        helper.succeed();
    }

    private static void assertConserved(GameTestHelper helper, ServerState state) {
        List<Integer> all = new ArrayList<>(state.liveWall());
        all.addAll(state.deadWall());
        int drawnCount = 0;
        for (PrivateSeat seat : state.seats()) {
            helper.assertTrue(seat.closedHand().size() == 13, "Every closed hand must stay at thirteen tiles");
            all.addAll(seat.closedHand());
            all.addAll(seat.discards());
            if (seat.drawnTile() != null) {
                drawnCount++;
                all.add(seat.drawnTile());
                helper.assertTrue(seat.index() == state.currentSeat(), "Only the current actor may hold a draw");
            }
        }
        helper.assertTrue(drawnCount == (state.finished() ? 0 : 1), "There must be exactly one active draw until exhaustion");
        helper.assertTrue(all.size() == 136 && new HashSet<>(all).equals(new HashSet<>(TileCatalog.allTiles())),
                "Closed hands, separate draws, rivers, live wall and dead wall must partition all 136 physical tiles");
    }

    private static void assertImmutable(GameTestHelper helper, List<?> list, String message) {
        boolean rejected = false;
        try {
            list.clear();
        } catch (UnsupportedOperationException expected) {
            rejected = true;
        }
        helper.assertTrue(rejected, message);
    }

    private static DrawDiscardGame game(long seed) {
        return new DrawDiscardGame(id(100), players(), seed);
    }

    private static List<UUID> players() {
        return List.of(id(1), id(2), id(3), id(4));
    }

    private static UUID id(long value) {
        return new UUID(0, value);
    }
}
