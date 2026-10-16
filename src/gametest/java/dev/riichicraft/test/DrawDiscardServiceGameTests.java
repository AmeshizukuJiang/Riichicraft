package dev.riichicraft.test;

import dev.riichicraft.block.ModBlocks;
import dev.riichicraft.lobby.LobbyRegistry;
import dev.riichicraft.network.GameActionPayload;
import dev.riichicraft.network.GameSnapshot;
import dev.riichicraft.network.LobbyActionPayload;
import dev.riichicraft.server.LobbyService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

/** Server service integration with four native in-memory connections, not four TCP clients. */
public final class DrawDiscardServiceGameTests {
    @GameTest
    public void authorizedStartAndBoundIntentionsPlayThroughToWallExhaustion(GameTestHelper helper) {
        BlockPos relative = new BlockPos(3, 2, 3);
        helper.setBlock(relative.below(), Blocks.STONE);
        helper.setBlock(relative, ModBlocks.MAHJONG_TABLE);
        BlockPos tablePos = helper.absolutePos(relative);
        LobbyService service = LobbyService.get(helper.getLevel().getServer());
        List<ConnectedTestPlayer> fixtures = new ArrayList<>();
        try {
            for (String wind : List.of("East", "South", "West", "North")) {
                fixtures.add(new ConnectedTestPlayer(helper.getLevel().getServer(), helper.getLevel(), tablePos,
                        "RC_DD_" + wind));
            }
            List<ServerPlayer> players = fixtures.stream().map(ConnectedTestPlayer::player).toList();
            UUID[] viewIds = new UUID[4];
            UUID tableId = null;
            for (int seat = 0; seat < 4; seat++) {
                ServerPlayer player = players.get(seat);
                helper.assertTrue(helper.getLevel().getServer().getPlayerList().getPlayer(player.getUUID()) == player
                                && player.connection != null,
                        "Every fixture must have a native connection and a real PlayerList entry");
                viewIds[seat] = service.open(player, tablePos);
                helper.assertTrue(viewIds[seat] != null, "A nearby connected player must open the real table");
                long revision = tableId == null ? 0 : lobby(service, tableId).revision();
                service.handleAction(player, lobbyAction(viewIds[seat], revision, LobbyActionPayload.JOIN, false));
                UUID joinedTable = service.registry().tableForPlayer(player.getUUID()).orElseThrow();
                if (tableId == null) tableId = joinedTable;
                helper.assertTrue(tableId.equals(joinedTable), "All four fixtures must join the same table");
            }

            ServerPlayer east = players.getFirst();
            LobbyRegistry.Snapshot joined = lobby(service, tableId);
            service.handleAction(east, lobbyAction(viewIds[0], joined.revision(), LobbyActionPayload.START, false));
            helper.assertTrue(service.gameView(tableId, east.getUUID()) == null && lobby(service, tableId).equals(joined),
                    "East cannot start before all four seats have prepared");
            for (int seat = 0; seat < 3; seat++) {
                service.handleAction(players.get(seat), lobbyAction(viewIds[seat], lobby(service, tableId).revision(),
                        LobbyActionPayload.SET_READY, true));
            }
            service.handleAction(east, lobbyAction(viewIds[0], lobby(service, tableId).revision(),
                    LobbyActionPayload.START, false));
            helper.assertTrue(service.gameView(tableId, east.getUUID()) == null,
                    "Three prepared seats must still be insufficient to start");
            service.handleAction(players.get(3), lobbyAction(viewIds[3], lobby(service, tableId).revision(),
                    LobbyActionPayload.SET_READY, true));
            LobbyRegistry.Snapshot prepared = lobby(service, tableId);
            helper.assertTrue(prepared.allReady(), "All four connected fixtures must be prepared");
            service.handleAction(players.get(1), lobbyAction(viewIds[1], prepared.revision(),
                    LobbyActionPayload.START, false));
            helper.assertTrue(service.gameView(tableId, east.getUUID()) == null,
                    "A prepared non-east player cannot start the prototype");
            service.handleAction(east, lobbyAction(viewIds[0], prepared.revision() - 1,
                    LobbyActionPayload.START, false));
            helper.assertTrue(service.gameView(tableId, east.getUUID()) == null && lobby(service, tableId).equals(prepared),
                    "A stale start request must not deal or alter lobby state");

            LobbyActionPayload acceptedStart = lobbyAction(viewIds[0], prepared.revision(), LobbyActionPayload.START, false);
            service.handleAction(east, acceptedStart);
            List<GameSnapshot> initial = recipientViews(service, tableId, players);
            GameSnapshot eastView = initial.getFirst();
            helper.assertTrue(eastView != null && eastView.revision() == 0 && eastView.turnSeat() == 0
                            && eastView.remainingTiles() == 69 && !eastView.paused(),
                    "A valid east start must create one active deal with 69 live tiles remaining");
            assertRecipientCounts(helper, initial, 0, 0);
            GameSnapshot outsider = service.gameView(tableId, UUID.randomUUID());
            helper.assertTrue(outsider != null && outsider.ownHand().isEmpty() && outsider.drawnTile() == -1
                            && outsider.seats().equals(eastView.seats()),
                    "An outsider must receive public seat state without any private hand or separate draw");

            service.handleAction(east, acceptedStart);
            service.handleAction(east, lobbyAction(viewIds[0], prepared.revision() - 1, LobbyActionPayload.START, false));
            assertUnchanged(helper, service, tableId, players, initial,
                    "Duplicate and old START requests cannot replace the active game or reshuffle its deal");
            for (int seat = 0; seat < 4; seat++) {
                for (int action : List.of(LobbyActionPayload.JOIN, LobbyActionPayload.SET_READY, LobbyActionPayload.LEAVE)) {
                    service.handleAction(players.get(seat), lobbyAction(viewIds[seat], prepared.revision(), action, false));
                }
            }
            helper.assertTrue(lobby(service, tableId).equals(prepared),
                    "Lobby join, leave and preparation requests must not change active-game participants");
            assertUnchanged(helper, service, tableId, players, initial,
                    "Blocked lobby requests must preserve the active private hands and wall count");

            int eastDraw = eastView.drawnTile();
            UUID gameId = eastView.gameId();
            service.handleGameAction(players.get(1), discard(viewIds[0], gameId, 0, eastDraw));
            assertUnchanged(helper, service, tableId, players, initial,
                    "A second player cannot reuse east's view token to discard on east's behalf");
            UUID obsoleteEastView = viewIds[0];
            viewIds[0] = service.open(east, tablePos);
            helper.assertTrue(viewIds[0] != null && !viewIds[0].equals(obsoleteEastView),
                    "Reopening a table must issue a fresh view token");
            service.handleGameAction(east, discard(obsoleteEastView, gameId, 0, eastDraw));
            service.handleGameAction(east, discard(viewIds[0], UUID.randomUUID(), 0, eastDraw));
            service.handleGameAction(east, discard(viewIds[0], gameId, 1, eastDraw));
            service.handleGameAction(players.get(1), discard(viewIds[1], gameId, 0, initial.get(1).ownHand().getFirst()));
            service.handleGameAction(east, discard(viewIds[0], gameId, 0, initial.get(1).ownHand().getFirst()));
            assertUnchanged(helper, service, tableId, players, initial,
                    "Superseded views, wrong game/revision, wrong turns and unowned tiles must all preserve the game");

            GameActionPayload firstDiscard = discard(viewIds[0], gameId, 0, eastDraw);
            service.handleGameAction(east, firstDiscard);
            List<GameSnapshot> afterFirst = recipientViews(service, tableId, players);
            assertRecipientCounts(helper, afterFirst, 1, 1);
            helper.assertTrue(afterFirst.getFirst().remainingTiles() == 68
                            && afterFirst.getFirst().seats().getFirst().discards().equals(List.of(eastDraw)),
                    "The first valid discard must enter east's river and draw exactly once for south");
            service.handleGameAction(east, firstDiscard);
            service.handleAction(east, acceptedStart);
            assertUnchanged(helper, service, tableId, players, afterFirst,
                    "A repeated old discard and duplicate START must not advance or restart an already advancing game");

            int acceptedDiscards = 1;
            GameActionPayload lastDiscard = firstDiscard;
            while (service.gameView(tableId, east.getUUID()) != null) {
                GameSnapshot before = service.gameView(tableId, east.getUUID());
                int seat = before.turnSeat();
                ServerPlayer actor = players.get(seat);
                GameSnapshot actorView = service.gameView(tableId, actor.getUUID());
                helper.assertTrue(actorView != null && actorView.drawnTile() >= 0 && actorView.ownHand().size() == 13,
                        "The current actor must receive their thirteen closed tiles plus one separate draw");
                if (before.remainingTiles() == 0) {
                    helper.assertTrue(acceptedDiscards == 69,
                            "The final drawn tile must remain playable until its seventieth discard");
                }
                int tile = acceptedDiscards % 2 == 0 ? actorView.drawnTile() : actorView.ownHand().getFirst();
                lastDiscard = discard(viewIds[seat], actorView.gameId(), actorView.revision(), tile);
                service.handleGameAction(actor, lastDiscard);
                acceptedDiscards++;
                helper.assertTrue(acceptedDiscards <= 70, "The service must finish after exactly seventy live-wall draws");
                GameSnapshot after = service.gameView(tableId, east.getUUID());
                if (after != null) {
                    helper.assertTrue(after.gameId().equals(gameId) && after.revision() == before.revision() + 1
                                    && after.remainingTiles() == Math.max(0, before.remainingTiles() - 1),
                            "Every accepted service intention must preserve game identity and advance exactly once");
                    assertRecipientCounts(helper, recipientViews(service, tableId, players),
                            (seat + 1) % 4, acceptedDiscards);
                }
            }

            helper.assertTrue(acceptedDiscards == 70, "Wall exhaustion must occur only after the final discard");
            LobbyRegistry.Snapshot ended = lobby(service, tableId);
            helper.assertTrue(!ended.allReady() && ended.seats().stream().noneMatch(LobbyRegistry.Seat::ready),
                    "Exhaustion must return the table to a lobby with all preparation flags cleared");
            for (int seat = 0; seat < 4; seat++) {
                helper.assertTrue(ended.seats().get(seat).playerId().equals(players.get(seat).getUUID())
                                && service.registry().tableForPlayer(players.get(seat).getUUID()).orElseThrow().equals(tableId)
                                && service.gameView(tableId, players.get(seat).getUUID()) == null,
                        "Exhaustion must retain every seat while removing all four active-game views");
            }
            service.handleGameAction(players.get(1), lastDiscard);
            service.handleAction(east, acceptedStart);
            helper.assertTrue(service.gameView(tableId, east.getUUID()) == null && lobby(service, tableId).equals(ended),
                    "Late game intentions and old START cannot revive an exhausted game or bypass renewed preparation");
            helper.succeed();
        } finally {
            cleanup(service, fixtures, helper, tablePos);
        }
    }

    private static List<GameSnapshot> recipientViews(LobbyService service, UUID tableId, List<ServerPlayer> players) {
        return players.stream().map(player -> service.gameView(tableId, player.getUUID())).toList();
    }

    private static void assertUnchanged(GameTestHelper helper, LobbyService service, UUID tableId,
            List<ServerPlayer> players, List<GameSnapshot> expected, String message) {
        helper.assertTrue(recipientViews(service, tableId, players).equals(expected), message);
    }

    private static void assertRecipientCounts(GameTestHelper helper, List<GameSnapshot> views,
            int turnSeat, int discardedCount) {
        GameSnapshot publicView = views.getFirst();
        Set<Integer> concealed = new HashSet<>();
        for (int seat = 0; seat < 4; seat++) {
            GameSnapshot own = views.get(seat);
            helper.assertTrue(own != null && own.turnSeat() == turnSeat && own.ownHand().size() == 13
                            && (own.drawnTile() >= 0) == (seat == turnSeat)
                            && own.seats().equals(publicView.seats()),
                    "Every recipient must see the same public state and only their own separate draw");
            helper.assertTrue(own.seats().get(seat).handCount() == (seat == turnSeat ? 14 : 13)
                            && own.seats().stream().allMatch(GameSnapshot.Seat::present),
                    "Native connected participants must expose accurate public hand counts and presence");
            helper.assertTrue(own.seats().stream().mapToInt(publicSeat -> publicSeat.discards().size()).sum() == discardedCount,
                    "The four public rivers must contain exactly one tile per accepted intention");
            for (int tile : own.ownHand()) {
                helper.assertTrue(concealed.add(tile),
                        "Recipient-specific views must not accidentally repeat another participant's private hand");
            }
            if (own.drawnTile() >= 0) {
                helper.assertTrue(concealed.add(own.drawnTile()), "A recipient's draw must be a distinct physical tile");
            }
        }
        helper.assertTrue(concealed.size() == 53, "The four private views must partition the 53 currently held tiles");
        Set<Integer> rivers = new HashSet<>();
        for (GameSnapshot.Seat seat : publicView.seats()) {
            for (int tile : seat.discards()) {
                helper.assertTrue(!concealed.contains(tile) && rivers.add(tile),
                        "A public river tile cannot remain concealed or be discarded twice");
            }
        }
    }

    private static LobbyRegistry.Snapshot lobby(LobbyService service, UUID tableId) {
        return service.registry().snapshot(tableId).orElseThrow();
    }

    private static LobbyActionPayload lobbyAction(UUID viewId, long revision, int action, boolean ready) {
        return new LobbyActionPayload(viewId, revision, action, ready);
    }

    private static GameActionPayload discard(UUID viewId, UUID gameId, long revision, int tile) {
        return new GameActionPayload(viewId, gameId, revision, GameActionPayload.DISCARD, tile);
    }

    private static void cleanup(LobbyService service, List<ConnectedTestPlayer> fixtures,
            GameTestHelper helper, BlockPos tablePos) {
        RuntimeException failure = null;
        for (ConnectedTestPlayer fixture : fixtures) {
            try {
                service.disconnect(fixture.player());
            } catch (RuntimeException cleanupFailure) {
                failure = addFailure(failure, cleanupFailure);
            }
        }
        for (ConnectedTestPlayer fixture : fixtures) {
            try {
                fixture.close();
            } catch (RuntimeException cleanupFailure) {
                failure = addFailure(failure, cleanupFailure);
            }
        }
        try {
            service.removeTable(helper.getLevel(), tablePos);
        } catch (RuntimeException cleanupFailure) {
            failure = addFailure(failure, cleanupFailure);
        }
        if (failure != null) throw failure;
    }

    private static RuntimeException addFailure(RuntimeException failure, RuntimeException additional) {
        if (failure == null) return additional;
        failure.addSuppressed(additional);
        return failure;
    }
}
