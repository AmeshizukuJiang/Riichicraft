package dev.riichicraft.server;

import dev.riichicraft.block.ModBlocks;
import dev.riichicraft.lobby.LobbyRegistry;
import dev.riichicraft.network.GameActionPayload;
import dev.riichicraft.network.GameSnapshot;
import dev.riichicraft.network.LobbyActionPayload;
import dev.riichicraft.persistence.TableArchiveStore;
import dev.riichicraft.test.ConnectedTestPlayer;
import dev.riichicraft.test.FurnitureTestLayout;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.LevelResource;

/** Native transitions between retained games and transient lobbies, including the original six audit failures. */
public final class LobbyTransitionGameTests {
    @GameTest
    public void abortReleasesParticipantWhoDisconnectedDuringPlay(GameTestHelper helper) throws IOException {
        checkAbsentTransition(helper, false, true);
    }

    @GameTest
    public void returnLobbyReleasesParticipantWhoDisconnectedAfterResult(GameTestHelper helper) throws IOException {
        checkAbsentTransition(helper, true, true);
    }

    @GameTest
    public void abortReleasesParticipantWhoLeftChairDuringPlay(GameTestHelper helper) throws IOException {
        checkAbsentTransition(helper, false, false);
    }

    private void checkAbsentTransition(GameTestHelper helper, boolean finish, boolean disconnect) throws IOException {
        for (int count : new int[] {3, 4}) try (Scenario scenario = new Scenario(helper, count, true)) {
            if (finish) scenario.finishRound();
            UUID absentId = scenario.player(1).getUUID();
            Entity remainingMount = scenario.player(0).getVehicle();
            scenario.makeAbsent(1, disconnect);
            helper.assertTrue(scenario.service.registry().tableForPlayer(absentId).isPresent(),
                    "An absent participant keeps its identity until the retained game actually ends");
            var returnRequest = scenario.request(0, finish ? GameActionPayload.RETURN_LOBBY : GameActionPayload.ABORT);
            scenario.service.handleGameAction(scenario.player(0), returnRequest);
            scenario.assertReturnedAndSaved();
            helper.assertTrue(scenario.service.registry().tableForPlayer(absentId).isEmpty()
                            && !scenario.lobby().seats().get(1).occupied(),
                    "Returning to idle lobby releases the absent UUID and physical slot for " + count + " players");
            helper.assertTrue(scenario.player(0).getVehicle() == remainingMount
                            && scenario.lobby().seats().getFirst().playerId().equals(scenario.player(0).getUUID()),
                    "A participant still in its valid original chair remains seated through the transition");
            var after = scenario.lobby();
            scenario.service.handleGameAction(scenario.player(0), returnRequest);
            scenario.service.tick();
            helper.assertTrue(scenario.lobby().equals(after),
                    "Repeating the old game's return request cannot mutate the idle lobby or its readiness");
            scenario.replace(1);
            helper.assertTrue(scenario.lobby().seats().get(1).playerId().equals(scenario.player(1).getUUID()),
                    "A different authenticated player can immediately claim the vacated physical chair");
        }
        helper.succeed();
    }

    @GameTest
    public void absentInitialEastCanBeReplacedAndOldReturnCannotAbortTheNewGame(GameTestHelper helper) throws IOException {
        for (int count : new int[] {3, 4}) for (boolean finish : new boolean[] {false, true})
                try (Scenario scenario = new Scenario(helper, count, true)) {
            if (finish) scenario.finishRound();
            UUID formerEast = scenario.player(0).getUUID();
            scenario.makeAbsent(0, true);
            var oldRequest = scenario.request(1, finish ? GameActionPayload.RETURN_LOBBY : GameActionPayload.ABORT);
            scenario.service.handleGameAction(scenario.player(1), oldRequest);
            scenario.assertReturnedAndSaved();
            helper.assertTrue(scenario.service.registry().tableForPlayer(formerEast).isEmpty(),
                    "A disconnected initial east cannot reserve the next game's start authority");
            scenario.replace(0);
            UUID nextEast = scenario.player(0).getUUID();
            helper.assertTrue(scenario.lobby().seats().getFirst().playerId().equals(nextEast),
                    "The replacement owns the fixed initial east chair");
            scenario.start();
            var newGame = scenario.game(1);
            helper.assertTrue(!newGame.gameId().equals(oldRequest.gameId()) && !newGame.paused(),
                    "All retained players and the replacement can prepare and start a fresh game");
            scenario.service.handleGameAction(scenario.player(1), oldRequest);
            helper.assertTrue(scenario.game(1).equals(newGame),
                    "An old accepted return request cannot clear or advance the new game");
        }
        helper.succeed();
    }

    @GameTest
    public void sameTickDismountIsReleasedButClosedViewKeepsAValidPhysicalMount(GameTestHelper helper) throws IOException {
        for (int count : new int[] {3, 4}) try (Scenario scenario = new Scenario(helper, count, true)) {
            UUID absent = scenario.player(1).getUUID();
            UUID closed = scenario.player(2).getUUID();
            Entity retained = scenario.player(2).getVehicle();
            scenario.service.handleAction(scenario.player(2), new LobbyActionPayload(scenario.views.get(2),
                    scenario.lobby().revision(), LobbyActionPayload.CLOSE, false));
            var beforeDismount = scenario.request(0, GameActionPayload.ABORT);
            scenario.player(1).stopRiding();
            // No lifecycle tick: the transition itself must notice an invalid remaining mount record.
            scenario.service.handleGameAction(scenario.player(0), beforeDismount);
            helper.assertTrue(scenario.game(0) != null && scenario.game(0).paused()
                            && scenario.game(0).revision() > beforeDismount.revision(),
                    "The same-action presence check rejects a pre-dismount revision and acknowledges the pause");
            scenario.service.handleGameAction(scenario.player(0), scenario.request(0, GameActionPayload.ABORT));
            scenario.assertReturnedAndSaved();
            helper.assertTrue(scenario.service.registry().tableForPlayer(absent).isEmpty()
                            && scenario.player(1).getVehicle() == null,
                    "The return transition removes an already invalid physical mount even before refreshMounts runs");
            helper.assertTrue(scenario.service.registry().tableForPlayer(closed).orElseThrow().equals(scenario.tableId)
                            && scenario.player(2).getVehicle() == retained,
                    "Closing only the controls retains a participant still occupying its valid physical chair");
        }
        helper.succeed();
    }

    @GameTest
    public void legacyTableRetainsOnlineClosedViewsButReleasesOfflineParticipants(GameTestHelper helper) throws IOException {
        for (int count : new int[] {3, 4}) for (boolean finish : new boolean[] {false, true})
                try (Scenario scenario = new Scenario(helper, count, false)) {
            if (finish) scenario.finishRound();
            UUID offline = scenario.player(1).getUUID();
            UUID closed = scenario.player(2).getUUID();
            scenario.service.handleAction(scenario.player(2), new LobbyActionPayload(scenario.views.get(2),
                    scenario.lobby().revision(), LobbyActionPayload.CLOSE, false));
            scenario.makeAbsent(1, true);
            var oldRequest = scenario.request(0, finish ? GameActionPayload.RETURN_LOBBY : GameActionPayload.ABORT);
            scenario.service.handleGameAction(scenario.player(0), oldRequest);
            scenario.assertReturnedAndSaved();
            helper.assertTrue(scenario.service.registry().tableForPlayer(offline).isEmpty(),
                    "An offline legacy participant is released when its retained game returns to the lobby");
            helper.assertTrue(scenario.service.registry().tableForPlayer(closed).orElseThrow().equals(scenario.tableId)
                            && scenario.lobby().seats().get(2).playerId().equals(closed),
                    "An online legacy participant needs no physical mount and closing the view keeps its seat");
            scenario.replace(1);
            scenario.views.set(2, scenario.service.open(scenario.player(2), scenario.center));
            scenario.start();
            helper.assertTrue(scenario.game(0) != null && !scenario.game(0).paused(),
                    "The legacy lobby stays reusable after the closed view reopens and an offline player is replaced");
        }
        helper.succeed();
    }

    private static final class Scenario implements AutoCloseable {
        final GameTestHelper helper;
        final BlockPos center;
        final Path archive;
        final boolean physical;
        final LobbyService service;
        final List<ConnectedTestPlayer> fixtures = new ArrayList<>();
        final List<ServerPlayer> players = new ArrayList<>();
        final List<BlockPos> chairs = new ArrayList<>();
        final List<UUID> views = new ArrayList<>();
        UUID tableId;

        Scenario(GameTestHelper helper, int count, boolean physical) {
            this.helper = helper;
            this.physical = physical;
            center = helper.absolutePos(new BlockPos(3, 2, 3));
            archive = helper.getLevel().getServer().getWorldPath(LevelResource.ROOT)
                    .resolve("data/riichicraft-tests/lobby-transition-" + UUID.randomUUID() + "/tables.dat");
            service = new LobbyService(helper.getLevel().getServer(), archive);
            service.responseDelaySource = () -> 0;
            try {
                if (physical) {
                    FurnitureTestLayout.body(helper.getLevel(), center, Direction.NORTH);
                    FurnitureTestLayout.chairs(helper.getLevel(), center, count);
                    Direction side = Direction.SOUTH;
                    for (int index = 0; index < 4; index++, side = side.getCounterClockWise()) {
                        if (count == 3 && side == Direction.NORTH) continue;
                        chairs.add(center.relative(side, 2));
                    }
                } else helper.getLevel().setBlockAndUpdate(center, ModBlocks.MAHJONG_TABLE.defaultBlockState());
                for (int seat = 0; seat < count; seat++) {
                    players.add(newFixture().player());
                    views.add(null);
                    join(seat);
                    if (!physical && seat == 0 && count == 3) service.handleAction(player(0),
                            new LobbyActionPayload(views.getFirst(), lobby().revision(), LobbyActionPayload.SET_MODE,
                                    false, LobbyRegistry.THREE_SINGLE));
                }
                start();
            } catch (RuntimeException | Error failure) {
                try { close(); }
                catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        ConnectedTestPlayer newFixture() {
            var fixture = new ConnectedTestPlayer(helper.getLevel().getServer(), helper.getLevel(), center,
                    "Transition" + UUID.randomUUID().toString().substring(0, 6));
            fixtures.add(fixture);
            return fixture;
        }
        ServerPlayer player(int seat) { return players.get(seat); }
        LobbyRegistry.Snapshot lobby() { return service.registry().snapshot(tableId).orElseThrow(); }
        GameSnapshot game(int seat) { return service.gameView(tableId, player(seat).getUUID()); }
        void near(ServerPlayer player, BlockPos chair) { player.setPos(chair.getX() + .5, chair.getY(), chair.getZ() + .5); }
        void join(int seat) {
            ServerPlayer player = player(seat);
            if (physical) {
                near(player, chairs.get(seat));
                helper.assertTrue(service.sit(player, chairs.get(seat)), "An authenticated player can claim its free physical chair");
                tableId = service.registry().tableForPlayer(player.getUUID()).orElseThrow();
                views.set(seat, service.openImmersive(player, center));
            } else {
                UUID view = service.open(player, center);
                views.set(seat, view);
                service.handleAction(player, new LobbyActionPayload(view, tableId == null ? 0 : lobby().revision(),
                        LobbyActionPayload.JOIN, false));
                tableId = service.registry().tableForPlayer(player.getUUID()).orElseThrow();
            }
            helper.assertTrue(lobby().seats().get(seat).playerId().equals(player.getUUID()), "The requested logical slot has exactly its new owner");
        }
        void replace(int seat) {
            players.set(seat, newFixture().player());
            join(seat);
        }
        void makeAbsent(int seat, boolean disconnect) {
            ServerPlayer absent = player(seat);
            if (disconnect) {
                service.disconnect(absent);
                fixtures.stream().filter(fixture -> fixture.player() == absent).findFirst().orElseThrow().close();
                helper.assertTrue(helper.getLevel().getServer().getPlayerList().getPlayer(absent.getUUID()) == null,
                        "The disconnected native player is actually absent from PlayerList");
            } else {
                absent.stopRiding();
                service.tick();
                helper.assertTrue(absent.getVehicle() == null, "The participant has actually left its physical chair");
            }
        }
        void start() {
            for (int seat = 0; seat < players.size(); seat++) service.handleAction(player(seat),
                    new LobbyActionPayload(views.get(seat), lobby().revision(), LobbyActionPayload.SET_READY, true));
            service.handleAction(player(0), new LobbyActionPayload(views.getFirst(), lobby().revision(), LobbyActionPayload.START, false));
            helper.assertTrue(game(0) != null && !game(0).paused(), "All current owners prepare and initial east starts an actual game");
        }
        GameActionPayload request(int seat, int action) {
            var game = game(seat);
            return new GameActionPayload(views.get(seat), game.gameId(), game.revision(), action, -1);
        }
        void action(int seat, int action, int tile, long serial) {
            var game = game(seat);
            service.handleGameAction(player(seat), new GameActionPayload(views.get(seat), game.gameId(), game.revision(),
                    action, tile, serial, -1, List.of()));
        }
        void assertReturnedAndSaved() throws IOException {
            helper.assertTrue(game(0) == null, "The acknowledged transition removes the retained game");
            helper.assertTrue(lobby().seats().stream().noneMatch(LobbyRegistry.Seat::ready), "Every surviving lobby member must prepare again");
            helper.assertTrue(TableArchiveStore.read(archive).stream().noneMatch(table -> table.tableId().equals(tableId)),
                    "The transition's successful save removes the game from the actual archive");
        }
        void finishRound() {
            for (int step = 0; step < 500 && game(0).rules().result() == null; step++) {
                boolean response = false;
                for (int seat = 0; seat < players.size(); seat++) {
                    var game = game(seat);
                    if (!game.rules().responses().isEmpty()) {
                        action(seat, GameActionPayload.PASS, -1, game.rules().opportunity().serial());
                        response = true;
                    }
                }
                if (response || game(0).rules().result() != null) continue;
                int actor = game(0).turnSeat();
                var game = game(actor);
                int tile = game.rules().legalDiscards().contains(game.drawnTile())
                        ? game.drawnTile() : game.rules().legalDiscards().getFirst();
                action(actor, GameActionPayload.DISCARD, tile, -1);
            }
            helper.assertTrue(game(0).rules().result() != null, "A genuine sequence of legal actions reaches the retained terminal result");
        }
        @Override public void close() {
            for (var fixture : fixtures) { service.disconnect(fixture.player()); fixture.close(); }
            if (tableId != null) service.removeTable(helper.getLevel(), center);
            service.stop();
        }
    }
}
