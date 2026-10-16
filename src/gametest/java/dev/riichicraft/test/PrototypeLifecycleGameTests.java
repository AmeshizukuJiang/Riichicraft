package dev.riichicraft.test;

import dev.riichicraft.block.ModBlocks;
import dev.riichicraft.lobby.LobbyRegistry;
import dev.riichicraft.network.GameActionPayload;
import dev.riichicraft.network.GameSnapshot;
import dev.riichicraft.network.LobbyActionPayload;
import dev.riichicraft.server.LobbyService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

/** Prototype lifecycle through the real service entry points, with native in-memory player connections. */
public final class PrototypeLifecycleGameTests {
    private static int fixtureSequence;

    @GameTest
    public void closingKeepsPlayAndDistancePausesBeforeAnImmediateDiscard(GameTestHelper helper) {
        try (Scenario scenario = Scenario.create(helper, new BlockPos(3, 2, 3))) {
            List<PlayState> before = scenario.playStates();
            GameSnapshot initial = scenario.game(0);
            UUID closedView = scenario.viewIds[0];
            scenario.lobbyAction(0, LobbyActionPayload.CLOSE, false);
            scenario.service.tick();
            helper.assertTrue(!scenario.game(0).paused() && scenario.game(0).revision() == initial.revision(),
                    "Closing only the view must not pause or advance an otherwise present game");
            scenario.service.handleGameAction(scenario.players.getFirst(), new GameActionPayload(closedView,
                    initial.gameId(), initial.revision(), GameActionPayload.DISCARD, initial.drawnTile()));
            scenario.assertPlayStates(before, "A closed view must lose action authority without losing any tiles");
            scenario.reopen(0);

            scenario.moveAway(0);
            // Deliberately do not tick first: presence validation must precede this attempted discard.
            scenario.gameAction(0, GameActionPayload.DISCARD, scenario.game(0).drawnTile());
            GameSnapshot paused = scenario.game(0);
            helper.assertTrue(paused.paused() && !paused.seats().getFirst().present()
                            && paused.revision() > initial.revision(),
                    "An out-of-range intention must pause and reject before the next lifecycle tick");
            scenario.assertPlayStates(before, "The rejected out-of-range discard cannot consume a tile or turn");
            scenario.service.tick();
            scenario.service.tick();
            helper.assertTrue(scenario.game(0).revision() == paused.revision(),
                    "Repeated ticks in an unchanged paused state cannot keep increasing revisions");

            scenario.returnToTable(0);
            scenario.reopen(0);
            scenario.service.tick();
            helper.assertTrue(scenario.game(0).paused()
                            && scenario.game(0).seats().stream().allMatch(GameSnapshot.Seat::present),
                    "Returning to the table must still require explicit confirmation from all four players");
            for (int seat = 0; seat < 4; seat++) {
                scenario.gameAction(seat, GameActionPayload.RESUME, -1);
                helper.assertTrue(scenario.game(0).paused() == (seat < 3),
                        "Only the fourth present participant's confirmation may resume play");
                if (seat < 3) {
                    helper.assertTrue(scenario.game(0).seats().stream().filter(GameSnapshot.Seat::confirmed).count() == seat + 1,
                            "Each confirmation must belong to exactly the participant who submitted it");
                    long confirmedRevision = scenario.game(0).revision();
                    scenario.gameAction(seat, GameActionPayload.RESUME, -1);
                    helper.assertTrue(scenario.game(0).revision() == confirmedRevision,
                            "Repeating a participant's desired confirmation must be idempotent");
                }
                scenario.assertPlayStates(before, "Pause and confirmation must preserve every private and public tile");
            }
            long resumedRevision = scenario.game(0).revision();
            scenario.gameAction(0, GameActionPayload.DISCARD, scenario.game(0).drawnTile());
            helper.assertTrue(scenario.game(0).revision() == resumedRevision + 1
                            && scenario.game(0).turnSeat() == 1 && scenario.game(0).remainingTiles() == 68,
                    "A resumed game must accept its original actor's discard despite the intervening lifecycle revisions");
        }
        helper.succeed();
    }

    @GameTest
    public void disconnectRetainsSeatsAndASecondAbsenceRevokesAllPriorConfirmations(GameTestHelper helper) {
        try (Scenario scenario = Scenario.create(helper, new BlockPos(3, 2, 3))) {
            List<PlayState> before = scenario.playStates();
            UUID westId = scenario.players.get(2).getUUID();
            UUID disconnectedView = scenario.viewIds[2];
            scenario.service.disconnect(scenario.players.get(2));
            helper.assertTrue(scenario.game(0).paused()
                            && !scenario.game(0).seats().get(2).present()
                            && scenario.service.registry().tableForPlayer(westId).orElseThrow().equals(scenario.tableId)
                            && scenario.lobby().seats().get(2).playerId().equals(westId),
                    "A play disconnect must pause while preserving the participant's seat");
            scenario.assertPlayStates(before, "A disconnect cannot redeal or remove the disconnected hand");
            GameSnapshot disconnected = scenario.game(2);
            scenario.service.handleGameAction(scenario.players.get(2), new GameActionPayload(disconnectedView,
                    disconnected.gameId(), disconnected.revision(), GameActionPayload.RESUME, -1));
            helper.assertTrue(!scenario.game(2).seats().get(2).confirmed(),
                    "Disconnect must revoke the old view even if its native fixture remains in PlayerList");
            long disconnectedRevision = scenario.game(0).revision();
            scenario.gameAction(0, GameActionPayload.RESUME, -1);
            helper.assertTrue(scenario.game(0).revision() == disconnectedRevision
                            && scenario.game(0).seats().stream().noneMatch(GameSnapshot.Seat::confirmed),
                    "Present participants cannot start confirming until all four participants have returned");

            // Simulates the service's disconnect/reopen entry points. Real TCP reconnect is a client integration test.
            scenario.reopen(2);
            scenario.service.tick();
            scenario.assertPlayStates(before, "Reopening as the same UUID must recover the same private hand");
            for (int seat = 0; seat < 3; seat++) scenario.gameAction(seat, GameActionPayload.RESUME, -1);
            GameSnapshot partiallyConfirmed = scenario.game(0);
            helper.assertTrue(partiallyConfirmed.paused() && partiallyConfirmed.seats().get(2).confirmed()
                            && !partiallyConfirmed.seats().get(3).confirmed(),
                    "Three confirmations must leave play paused while the fourth remains outstanding");

            scenario.moveAway(2);
            scenario.service.tick();
            GameSnapshot absentAgain = scenario.game(0);
            helper.assertTrue(absentAgain.paused() && !absentAgain.seats().get(2).present()
                            && absentAgain.seats().stream().noneMatch(GameSnapshot.Seat::confirmed),
                    "A second absence must revoke every prior confirmation before the next recovery attempt");
            scenario.service.tick();
            helper.assertTrue(scenario.game(0).revision() == absentAgain.revision(),
                    "An unchanged second absence must not advance another lifecycle revision");
            scenario.returnToTable(2);
            scenario.reopen(2);
            scenario.service.tick();
            for (int seat = 0; seat < 4; seat++) {
                scenario.gameAction(seat, GameActionPayload.RESUME, -1);
                helper.assertTrue(scenario.game(0).paused() == (seat < 3),
                        "Every participant must confirm again after a second absence");
            }
            scenario.assertPlayStates(before, "Repeated absence and reconfirmation must preserve all held and discarded tiles");
        }
        helper.succeed();
    }

    @GameTest
    public void nativeDeathSpectatorAndLevelStatePauseWithoutChangingTiles(GameTestHelper helper) {
        try (Scenario scenario = Scenario.create(helper, new BlockPos(3, 2, 3))) {
            List<PlayState> before = scenario.playStates();
            ServerPlayer south = scenario.players.get(1);
            float originalHealth = south.getHealth();
            try {
                south.setHealth(0.0F);
                scenario.service.tick();
                helper.assertTrue(!south.isAlive() && scenario.game(0).paused()
                                && !scenario.game(0).seats().get(1).present(),
                        "A participant at zero health must become unavailable and pause the prototype");
                scenario.assertPlayStates(before, "Death availability must not discard, draw, redeal or release any hand");
            } finally {
                // This targeted test changes native health state, rather than exercising a client respawn protocol.
                south.setHealth(originalHealth);
                scenario.positionAtTable(south);
            }
            reopenAndConfirmAll(scenario, 1, before);

            try {
                south.setGameMode(GameType.SPECTATOR);
                scenario.service.tick();
                helper.assertTrue(south.isSpectator() && scenario.game(0).paused()
                                && !scenario.game(0).seats().get(1).present(),
                        "A participant switched to spectator must become unavailable and pause play");
                scenario.assertPlayStates(before, "Spectator mode must preserve the participant's concealed tiles and seat");
            } finally {
                south.setGameMode(GameType.CREATIVE);
                scenario.positionAtTable(south);
            }
            reopenAndConfirmAll(scenario, 1, before);

            ServerLevel originalLevel = south.level();
            ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
            helper.assertTrue(nether != null && !nether.dimension().equals(originalLevel.dimension()),
                    "The target server must expose a distinct native dimension for the presence check");
            try {
                // Native dimension-state check only: no entity transfer, portal travel or remote chunk generation.
                south.setServerLevel(nether);
                scenario.service.tick();
                helper.assertTrue(south.level().dimension().equals(Level.NETHER) && scenario.game(0).paused()
                                && !scenario.game(0).seats().get(1).present(),
                        "A participant whose native level differs from the table must pause play");
                scenario.assertPlayStates(before, "A different native dimension must leave all gameplay tiles unchanged");
            } finally {
                // Restore the level before PlayerList/world cleanup, since setServerLevel is not a full transfer.
                south.setServerLevel(originalLevel);
                scenario.positionAtTable(south);
            }
            reopenAndConfirmAll(scenario, 1, before);
        }
        helper.succeed();
    }

    @GameTest
    public void abortRequiresCurrentParticipantAuthorityInRunningAndPausedGames(GameTestHelper helper) {
        try (Scenario scenario = Scenario.create(helper, new BlockPos(3, 2, 3))) {
            List<GameSnapshot> initial = scenario.snapshots();
            GameSnapshot firstGame = scenario.game(0);
            ServerPlayer observer = (ServerPlayer) helper.makeMockServerPlayer(GameType.CREATIVE);
            scenario.positionAtTable(observer);
            UUID observerView = scenario.service.open(observer, scenario.tablePos);
            helper.assertTrue(observerView != null, "An unseated observer must be able to view the public table");
            scenario.service.handleGameAction(observer, new GameActionPayload(observerView, firstGame.gameId(),
                    firstGame.revision(), GameActionPayload.ABORT, -1));
            scenario.service.handleGameAction(scenario.players.get(1), new GameActionPayload(scenario.viewIds[0],
                    firstGame.gameId(), firstGame.revision(), GameActionPayload.ABORT, -1));
            scenario.service.handleGameAction(scenario.players.get(1), new GameActionPayload(scenario.viewIds[1],
                    UUID.randomUUID(), firstGame.revision(), GameActionPayload.ABORT, -1));
            scenario.service.handleGameAction(scenario.players.get(1), new GameActionPayload(scenario.viewIds[1],
                    firstGame.gameId(), firstGame.revision() + 1, GameActionPayload.ABORT, -1));
            UUID obsoleteView = scenario.viewIds[1];
            scenario.reopen(1);
            scenario.service.handleGameAction(scenario.players.get(1), new GameActionPayload(obsoleteView,
                    firstGame.gameId(), firstGame.revision(), GameActionPayload.ABORT, -1));
            helper.assertTrue(scenario.snapshots().equals(initial),
                    "Observers, stolen/obsolete views, wrong games and wrong revisions cannot abort running play");

            GameActionPayload acceptedAbort = new GameActionPayload(scenario.viewIds[2], firstGame.gameId(),
                    firstGame.revision(), GameActionPayload.ABORT, -1);
            scenario.service.handleGameAction(scenario.players.get(2), acceptedAbort);
            scenario.assertReturnedToLobby();
            LobbyRegistry.Snapshot afterAbort = scenario.lobby();
            scenario.service.handleGameAction(scenario.players.get(2), acceptedAbort);
            helper.assertTrue(scenario.lobby().equals(afterAbort), "Repeating an accepted abort must not mutate the returned lobby");

            scenario.start();
            helper.assertTrue(!scenario.game(0).gameId().equals(firstGame.gameId()), "A newly prepared game must have a fresh game ID");
            scenario.moveAway(3);
            scenario.service.tick();
            GameSnapshot paused = scenario.game(0);
            helper.assertTrue(paused.paused() && paused.revision() > 0, "The second game must pause when north leaves range");
            List<GameSnapshot> pausedState = scenario.snapshots();
            for (int action : List.of(GameActionPayload.RESUME, GameActionPayload.ABORT, GameActionPayload.DISCARD)) {
                int tile = action == GameActionPayload.DISCARD ? paused.drawnTile() : -1;
                scenario.service.handleGameAction(scenario.players.getFirst(), new GameActionPayload(scenario.viewIds[0],
                        paused.gameId(), paused.revision() - 1, action, tile));
            }
            scenario.service.handleGameAction(scenario.players.get(2), acceptedAbort);
            helper.assertTrue(scenario.snapshots().equals(pausedState),
                    "Stale resume/abort/discard and an old game's abort cannot mutate paused play or confirmations");
            scenario.gameAction(1, GameActionPayload.ABORT, -1);
            scenario.assertReturnedToLobby();
            scenario.service.disconnect(observer);
        }
        helper.succeed();
    }

    @GameTest
    public void realBlockRemovalAndChunkUnloadCallbackReleaseActiveGamesAndSeats(GameTestHelper helper) {
        BlockPos relative = new BlockPos(3, 2, 3);
        try (Scenario scenario = Scenario.create(helper, relative)) {
            helper.setBlock(relative, Blocks.AIR);
            scenario.assertRemoved("Actual table block replacement must remove the active game and all seats");
        }
        try (Scenario scenario = Scenario.create(helper, relative)) {
            // Exercises the registered callback with a real loaded chunk; does not claim an engine-driven chunk unload.
            ServerChunkEvents.CHUNK_UNLOAD.invoker().onChunkUnload(helper.getLevel(), helper.getLevel().getChunkAt(scenario.tablePos));
            scenario.assertRemoved("The chunk-unload listener must remove its active game and all seat indexes");
        }
        helper.succeed();
    }

    @GameTest
    public void stoppingClearsEveryActiveTableAndRevokesTheirViews(GameTestHelper helper) {
        try (Scenario first = Scenario.create(helper, new BlockPos(2, 2, 2));
                Scenario second = Scenario.create(helper, new BlockPos(4, 2, 4))) {
            GameSnapshot oldFirst = first.game(0);
            GameSnapshot oldSecond = second.game(0);
            first.service.stop();
            first.assertRemoved("Stopping must clear the first table's game, lobby and player indexes");
            second.assertRemoved("Stopping must clear every table rather than only the currently viewed one");
            first.service.handleAction(first.players.getFirst(), new LobbyActionPayload(first.viewIds[0], 0,
                    LobbyActionPayload.JOIN, false));
            second.service.handleGameAction(second.players.getFirst(), new GameActionPayload(second.viewIds[0],
                    oldSecond.gameId(), oldSecond.revision(), GameActionPayload.DISCARD, oldSecond.drawnTile()));
            first.service.handleGameAction(first.players.getFirst(), new GameActionPayload(first.viewIds[0],
                    oldFirst.gameId(), oldFirst.revision(), GameActionPayload.RESUME, -1));
            first.assertRemoved("Old lobby and game intentions must not resurrect state after stop");
            second.assertRemoved("Stopping must revoke both tables' view authority");
        }
        helper.succeed();
    }

    private static void reopenAndConfirmAll(Scenario scenario, int returningSeat, List<PlayState> expected) {
        scenario.reopen(returningSeat);
        scenario.service.tick();
        scenario.helper.assertTrue(scenario.game(0).paused()
                        && scenario.game(0).seats().stream().allMatch(GameSnapshot.Seat::present)
                        && scenario.game(0).seats().stream().noneMatch(GameSnapshot.Seat::confirmed),
                "Restoring availability must still require all four participants to confirm the preserved game");
        for (int seat = 0; seat < 4; seat++) {
            scenario.gameAction(seat, GameActionPayload.RESUME, -1);
            scenario.helper.assertTrue(scenario.game(0).paused() == (seat < 3),
                    "Only a fresh fourth confirmation may resume after health, mode or dimension recovery");
            scenario.assertPlayStates(expected, "Availability recovery and confirmation cannot change the original tile state");
        }
    }

    private record PlayState(UUID gameId, int turnSeat, int remainingTiles,
            List<Integer> ownHand, int drawnTile, List<List<Integer>> rivers) {
        static PlayState of(GameSnapshot snapshot) {
            return new PlayState(snapshot.gameId(), snapshot.turnSeat(), snapshot.remainingTiles(),
                    snapshot.ownHand(), snapshot.drawnTile(), snapshot.seats().stream().map(GameSnapshot.Seat::discards).toList());
        }
    }

    private static final class Scenario implements AutoCloseable {
        final GameTestHelper helper;
        final LobbyService service;
        final BlockPos tablePos;
        final List<ConnectedTestPlayer> fixtures = new ArrayList<>();
        final List<ServerPlayer> players = new ArrayList<>();
        final UUID[] viewIds = new UUID[4];
        UUID tableId;

        private Scenario(GameTestHelper helper, BlockPos relative) {
            this.helper = helper;
            service = LobbyService.get(helper.getLevel().getServer());
            helper.setBlock(relative.below(), Blocks.STONE);
            helper.setBlock(relative, ModBlocks.MAHJONG_TABLE);
            tablePos = helper.absolutePos(relative);
        }

        static Scenario create(GameTestHelper helper, BlockPos relative) {
            Scenario scenario = new Scenario(helper, relative);
            int sequence = ++fixtureSequence;
            try {
                for (int seat = 0; seat < 4; seat++) {
                    ConnectedTestPlayer fixture = new ConnectedTestPlayer(helper.getLevel().getServer(), helper.getLevel(),
                            scenario.tablePos, "PT" + sequence + "_" + seat);
                    scenario.fixtures.add(fixture);
                    scenario.players.add(fixture.player());
                    scenario.reopen(seat);
                    long revision = scenario.tableId == null ? 0 : scenario.lobby().revision();
                    scenario.service.handleAction(fixture.player(), new LobbyActionPayload(scenario.viewIds[seat],
                            revision, LobbyActionPayload.JOIN, false));
                    UUID joinedTable = scenario.service.registry().tableForPlayer(fixture.player().getUUID()).orElseThrow();
                    if (scenario.tableId == null) scenario.tableId = joinedTable;
                    helper.assertTrue(scenario.tableId.equals(joinedTable), "Every scenario player must occupy the same table");
                }
                scenario.start();
                return scenario;
            } catch (RuntimeException | Error failure) {
                try {
                    scenario.close();
                } catch (RuntimeException | Error cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        }

        void start() {
            for (int seat = 0; seat < 4; seat++) lobbyAction(seat, LobbyActionPayload.SET_READY, true);
            lobbyAction(0, LobbyActionPayload.START, false);
            helper.assertTrue(game(0) != null && !game(0).paused(), "Four present prepared native players must start a scenario");
        }

        void reopen(int seat) {
            viewIds[seat] = service.open(players.get(seat), tablePos);
            helper.assertTrue(viewIds[seat] != null, "A present scenario player must receive a fresh table view");
        }

        void lobbyAction(int seat, int action, boolean ready) {
            service.handleAction(players.get(seat), new LobbyActionPayload(viewIds[seat], lobby().revision(), action, ready));
        }

        void gameAction(int seat, int action, int tile) {
            GameSnapshot state = game(seat);
            helper.assertTrue(state != null, "The scenario must still have an active game before an intention");
            service.handleGameAction(players.get(seat), new GameActionPayload(viewIds[seat], state.gameId(), state.revision(), action, tile));
        }

        GameSnapshot game(int seat) {
            return service.gameView(tableId, players.get(seat).getUUID());
        }

        LobbyRegistry.Snapshot lobby() {
            return service.registry().snapshot(tableId).orElseThrow();
        }

        List<GameSnapshot> snapshots() {
            return players.stream().map(player -> service.gameView(tableId, player.getUUID())).toList();
        }

        List<PlayState> playStates() {
            return snapshots().stream().map(PlayState::of).toList();
        }

        void assertPlayStates(List<PlayState> expected, String message) {
            helper.assertTrue(playStates().equals(expected), message);
        }

        void moveAway(int seat) {
            players.get(seat).setPos(tablePos.getX() + 100.5, tablePos.getY() + 1.0, tablePos.getZ() + 1.5);
        }

        void returnToTable(int seat) {
            positionAtTable(players.get(seat));
        }

        void positionAtTable(ServerPlayer player) {
            player.setPos(tablePos.getX() + 0.5, tablePos.getY() + 1.0, tablePos.getZ() + 1.5);
        }

        void assertReturnedToLobby() {
            LobbyRegistry.Snapshot state = lobby();
            helper.assertTrue(!state.allReady() && state.seats().stream().noneMatch(LobbyRegistry.Seat::ready),
                    "Ending a prototype must clear every preparation flag");
            for (int seat = 0; seat < 4; seat++) {
                helper.assertTrue(state.seats().get(seat).playerId().equals(players.get(seat).getUUID())
                                && service.registry().tableForPlayer(players.get(seat).getUUID()).orElseThrow().equals(tableId)
                                && game(seat) == null,
                        "Ending a prototype must preserve all four seat identities and remove the active game");
            }
        }

        void assertRemoved(String message) {
            helper.assertTrue(service.registry().snapshot(tableId).isEmpty()
                            && players.stream().allMatch(player -> service.registry().tableForPlayer(player.getUUID()).isEmpty()
                            && service.gameView(tableId, player.getUUID()) == null), message);
        }

        @Override
        public void close() {
            RuntimeException failure = null;
            for (ServerPlayer player : players) {
                try {
                    service.disconnect(player);
                } catch (RuntimeException cleanupFailure) {
                    failure = collect(failure, cleanupFailure);
                }
            }
            for (ConnectedTestPlayer fixture : fixtures) {
                try {
                    fixture.close();
                } catch (RuntimeException cleanupFailure) {
                    failure = collect(failure, cleanupFailure);
                }
            }
            try {
                service.removeTable(helper.getLevel(), tablePos);
            } catch (RuntimeException cleanupFailure) {
                failure = collect(failure, cleanupFailure);
            }
            if (failure != null) throw failure;
        }

        private static RuntimeException collect(RuntimeException failure, RuntimeException additional) {
            if (failure == null) return additional;
            failure.addSuppressed(additional);
            return failure;
        }
    }
}
