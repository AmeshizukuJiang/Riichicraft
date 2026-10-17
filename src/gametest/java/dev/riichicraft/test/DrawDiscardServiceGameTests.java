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
            assertRecipientViews(helper, initial, 0);
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
            assertRecipientViews(helper, afterFirst, 1);
            helper.assertTrue(afterFirst.getFirst().seats().getFirst().discards().equals(List.of(eastDraw)),
                    "The first valid discard must enter east's river exactly once, before any pending responses finish");
            service.handleGameAction(east, firstDiscard);
            service.handleAction(east, acceptedStart);
            assertUnchanged(helper, service, tableId, players, afterFirst,
                    "A repeated old discard and duplicate START must not advance or restart an already advancing game");

            int acceptedDiscards = 1;
            GameActionPayload lastDiscard = firstDiscard;
            int commands = 0;
            boolean responsePauseChecked = false;
            while (service.gameView(tableId, east.getUUID()).rules().phase() != GameSnapshot.FINISHED) {
                helper.assertTrue(++commands <= 400, "A finite all-pass round must terminate within its tile and response budget");
                GameSnapshot before = service.gameView(tableId, east.getUUID());
                if (before.rules().phase() == GameSnapshot.DISCARD_RESPONSES
                        || before.rules().phase() == GameSnapshot.KAN_RESPONSES) {
                    long serial = before.rules().opportunity().serial();
                    List<Integer> pending = new ArrayList<>();
                    for (int seat = 0; seat < 4; seat++) {
                        if (!service.gameView(tableId, players.get(seat).getUUID()).rules().responses().isEmpty()) pending.add(seat);
                    }
                    helper.assertTrue(!pending.isEmpty(), "An unresolved response window must offer at least one real recipient action");
                    int firstResponder = pending.getFirst();
                    GameSnapshot beforeBad = service.gameView(tableId, players.get(firstResponder).getUUID());
                    service.handleGameAction(players.get(firstResponder), new GameActionPayload(viewIds[firstResponder], gameId,
                            before.revision(), GameActionPayload.PASS, -1, serial + 10_000, -1, List.of()));
                    helper.assertTrue(service.gameView(tableId, players.get(firstResponder).getUUID()).equals(beforeBad),
                            "An unoffered window serial cannot consume a real response or alter its mask");
                    if (!responsePauseChecked) {
                        responsePauseChecked = true;
                        GameSnapshot.RuleState held = before.rules();
                        players.get(3).setPos(tablePos.getX() + 100.0, tablePos.getY(), tablePos.getZ());
                        service.tick();
                        GameSnapshot paused = service.gameView(tableId, east.getUUID());
                        helper.assertTrue(paused.paused() && paused.rules().equals(held),
                                "A pending response window pauses without resolving, redrawing or changing its legal options");
                        service.handleGameAction(players.get(firstResponder), new GameActionPayload(viewIds[firstResponder], gameId,
                                paused.revision(), GameActionPayload.PASS, -1, serial, -1, List.of()));
                        helper.assertTrue(service.gameView(tableId, east.getUUID()).rules().equals(held),
                                "Even a valid current-window response must not resolve while play is paused");
                        players.get(3).setPos(tablePos.getX() + 0.5, tablePos.getY() + 1.0, tablePos.getZ() + 1.5);
                        viewIds[3] = service.open(players.get(3), tablePos);
                        service.tick();
                        for (int seat = 0; seat < 4; seat++) {
                            GameSnapshot state = service.gameView(tableId, players.get(seat).getUUID());
                            service.handleGameAction(players.get(seat), new GameActionPayload(viewIds[seat], gameId,
                                    state.revision(), GameActionPayload.RESUME, -1));
                        }
                        helper.assertTrue(!service.gameView(tableId, east.getUUID()).paused()
                                        && service.gameView(tableId, east.getUUID()).rules().equals(held),
                                "Four confirmations resume the same stable response window without replaying any action");
                    }
                    long responseRevision = service.gameView(tableId, east.getUUID()).revision();
                    for (int seat : pending) {
                        GameSnapshot current = service.gameView(tableId, players.get(seat).getUUID());
                        GameActionPayload pass = new GameActionPayload(viewIds[seat], gameId, responseRevision,
                                GameActionPayload.PASS, -1, serial, -1, List.of());
                        service.handleGameAction(players.get(seat), pass);
                        GameSnapshot next = service.gameView(tableId, east.getUUID());
                        helper.assertTrue(next.revision() > current.revision(),
                                "Stable window responses accept the first responder's revision despite other responses");
                        List<GameSnapshot> frozen = recipientViews(service, tableId, players);
                        service.handleGameAction(players.get(seat), pass);
                        assertUnchanged(helper, service, tableId, players, frozen,
                                "A response cannot be overwritten or replayed after the window moves on");
                    }
                    continue;
                }
                int seat = before.turnSeat();
                ServerPlayer actor = players.get(seat);
                GameSnapshot actorView = service.gameView(tableId, actor.getUUID());
                helper.assertTrue(actorView != null && !actorView.rules().legalDiscards().isEmpty(),
                        "The current actor must receive an authoritative exact physical discard mask");
                int tile = actorView.rules().legalDiscards().contains(actorView.drawnTile())
                        ? actorView.drawnTile() : actorView.rules().legalDiscards().getFirst();
                lastDiscard = discard(viewIds[seat], actorView.gameId(), actorView.revision(), tile);
                service.handleGameAction(actor, lastDiscard);
                acceptedDiscards++;
                helper.assertTrue(acceptedDiscards <= 70, "The service must finish after exactly seventy live-wall draws");
                GameSnapshot after = service.gameView(tableId, east.getUUID());
                helper.assertTrue(after.gameId().equals(gameId) && after.revision() == before.revision() + 1,
                        "An exact offered discard advances once without replacing the round");
                assertRecipientViews(helper, recipientViews(service, tableId, players), acceptedDiscards);
            }

            GameSnapshot finished = service.gameView(tableId, east.getUUID());
            helper.assertTrue(responsePauseChecked,
                    "This complete native-service trajectory must actually exercise pausing and resuming a real response window");
            helper.assertTrue(finished.rules().result() != null && !finished.paused(),
                    "A finished round must retain an actual immutable settlement for review");
            if (finished.remainingTiles() == 0) helper.assertTrue(acceptedDiscards == 70,
                    "Natural wall exhaustion occurs only after the final physical discard");
            GameSnapshot.Result settlement = finished.rules().result();
            int deposits = finished.rules().deposits();
            int total = finished.rules().seats().stream().mapToInt(GameSnapshot.SeatRules::points).sum() + deposits * 1_000;
            helper.assertTrue(total == 100_000, "The retained settlement conserves all points and deposit sticks");
            service.handleAction(east, lobbyAction(viewIds[0], lobby(service, tableId).revision(), LobbyActionPayload.CLOSE, false));
            viewIds[0] = service.open(east, tablePos);
            helper.assertTrue(service.gameView(tableId, east.getUUID()).rules().result().equals(settlement),
                    "Closing and reopening must show the same settled result rather than a new deal");
            service.disconnect(east);
            service.tick();
            helper.assertTrue(service.gameView(tableId, east.getUUID()).rules().result().equals(settlement)
                            && !service.gameView(tableId, east.getUUID()).paused(),
                    "A finished participant disconnect must not revoke the ledger or enter recovery confirmations");
            viewIds[0] = service.open(east, tablePos);
            GameSnapshot returned = service.gameView(tableId, players.get(1).getUUID());
            service.handleGameAction(players.get(1), new GameActionPayload(viewIds[1], gameId, returned.revision(),
                    GameActionPayload.RETURN_LOBBY, -1));
            LobbyRegistry.Snapshot ended = lobby(service, tableId);
            helper.assertTrue(!ended.allReady() && ended.seats().stream().noneMatch(LobbyRegistry.Seat::ready),
                    "A participant's explicit return clears the result and all preparation flags for the table");
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

    private static void assertRecipientViews(GameTestHelper helper, List<GameSnapshot> views, int discardedCount) {
        GameSnapshot publicView = views.getFirst();
        Set<Integer> concealed = new HashSet<>();
        for (int seat = 0; seat < 4; seat++) {
            GameSnapshot own = views.get(seat);
            helper.assertTrue(own != null && own.ownHand().size() <= 14
                            && own.seats().equals(publicView.seats()),
                    "Every recipient must see the same public state and only their own concealed tiles");
            helper.assertTrue(own.seats().get(seat).handCount() == own.ownHand().size()
                            && own.seats().stream().allMatch(GameSnapshot.Seat::present),
                    "Native connected participants must expose accurate public hand counts and presence");
            helper.assertTrue(own.seats().stream().mapToInt(publicSeat -> publicSeat.discards().size()).sum() == discardedCount,
                    "The four public rivers must contain exactly one tile per accepted intention");
            for (int tile : own.ownHand()) {
                helper.assertTrue(concealed.add(tile),
                        "Recipient-specific views must not accidentally repeat another participant's private hand");
            }
            if (own.drawnTile() >= 0) {
                helper.assertTrue(own.ownHand().contains(own.drawnTile()) && seat == own.turnSeat(),
                        "The recipient's draw is a reference into that same concealed hand, not a duplicated extra tile");
            }
        }
        int expectedHeld = publicView.rules().phase() == GameSnapshot.DRAWN_TURN ? 53 : 52;
        helper.assertTrue(concealed.size() == expectedHeld, "The private views partition only the currently held physical tiles");
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
