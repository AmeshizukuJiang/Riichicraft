package dev.riichicraft.server;

import dev.riichicraft.lobby.LobbyRegistry;
import dev.riichicraft.network.GameActionPayload;
import dev.riichicraft.network.GameSnapshot;
import dev.riichicraft.network.LobbyActionPayload;
import dev.riichicraft.test.ConnectedTestPlayer;
import dev.riichicraft.test.FurnitureTestLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/** Audit-only reproductions. These assert the existing idle-lobby contract and fail on v0.0.16-dev. */
public final class AuditServerGameTests {
    @GameTest
    public void abortReleasesParticipantWhoDisconnectedDuringPlay(GameTestHelper helper) {
        checkTransition(helper, false, true);
    }

    @GameTest
    public void returnLobbyReleasesParticipantWhoDisconnectedAfterResult(GameTestHelper helper) {
        checkTransition(helper, true, true);
    }

    @GameTest
    public void abortReleasesParticipantWhoLeftChairDuringPlay(GameTestHelper helper) {
        checkTransition(helper, false, false);
    }

    private void checkTransition(GameTestHelper helper, boolean finish, boolean disconnect) {
        var failures = new ArrayList<String>();
        for (int count : new int[] {3, 4}) try (Scenario scenario = new Scenario(helper, count)) {
            if (finish) scenario.finishRound();
            ServerPlayer absent = scenario.player(1);
            UUID absentId = absent.getUUID();
            if (disconnect) {
                scenario.service.disconnect(absent);
                scenario.fixtures.get(1).close();
                helper.assertTrue(helper.getLevel().getServer().getPlayerList().getPlayer(absentId) == null,
                        "The disconnected native player is actually absent from PlayerList");
            } else {
                absent.stopRiding();
                scenario.service.tick();
                helper.assertTrue(absent.getVehicle() == null, "The participant has actually dismounted");
            }
            helper.assertTrue(scenario.service.registry().tableForPlayer(absentId).isPresent(),
                    "Before ending the retained game, an absent participant must still own the original seat");
            scenario.action(0, finish ? GameActionPayload.RETURN_LOBBY : GameActionPayload.ABORT, -1, -1);
            scenario.service.tick();
            helper.assertTrue(scenario.game(0) == null, "The authorized transition actually returned the table to idle lobby");
            boolean released = scenario.service.registry().tableForPlayer(absentId).isEmpty();
            try (var replacement = new ConnectedTestPlayer(helper.getLevel().getServer(), helper.getLevel(), scenario.center,
                    "AuditNew" + UUID.randomUUID().toString().substring(0, 7))) {
                scenario.near(replacement.player(), scenario.chairs.get(1));
                boolean accepted = scenario.service.sit(replacement.player(), scenario.chairs.get(1));
                if (!released || !accepted) failures.add(count + "p: released=" + released + ", replacement accepted=" + accepted);
                scenario.service.disconnect(replacement.player());
            }
        }
        helper.assertTrue(failures.isEmpty(), "Idle lobby must release absent former participants: " + failures);
        helper.succeed();
    }

    private static final class Scenario implements AutoCloseable {
        final GameTestHelper helper;
        final BlockPos center;
        final LobbyService service;
        final List<ConnectedTestPlayer> fixtures = new ArrayList<>();
        final List<BlockPos> chairs = new ArrayList<>();
        final List<UUID> views = new ArrayList<>();
        UUID tableId;

        Scenario(GameTestHelper helper, int count) {
            this.helper = helper;
            center = helper.absolutePos(new BlockPos(3, 2, 3));
            var archive = helper.getLevel().getServer().getWorldPath(LevelResource.ROOT)
                    .resolve("data/riichicraft-audit/" + UUID.randomUUID() + "/tables.dat");
            service = new LobbyService(helper.getLevel().getServer(), archive);
            service.responseDelaySource = () -> 0;
            FurnitureTestLayout.body(helper.getLevel(), center, Direction.NORTH);
            FurnitureTestLayout.chairs(helper.getLevel(), center, count);
            Direction side = Direction.SOUTH;
            for (int physical = 0; physical < 4; physical++, side = side.getCounterClockWise()) {
                if (count == 3 && side == Direction.NORTH) continue;
                chairs.add(center.relative(side, 2));
            }
            for (int seat = 0; seat < count; seat++) {
                var fixture = new ConnectedTestPlayer(helper.getLevel().getServer(), helper.getLevel(), center,
                        "Audit" + UUID.randomUUID().toString().substring(0, 9));
                fixtures.add(fixture);
                near(fixture.player(), chairs.get(seat));
                helper.assertTrue(service.sit(fixture.player(), chairs.get(seat)), "Each original player sits in its actual physical chair");
                tableId = service.registry().tableForPlayer(fixture.player().getUUID()).orElseThrow();
                views.add(service.openImmersive(fixture.player(), center));
            }
            for (int seat = 0; seat < count; seat++) service.handleAction(player(seat),
                    new LobbyActionPayload(views.get(seat), lobby().revision(), LobbyActionPayload.SET_READY, true));
            service.handleAction(player(0), new LobbyActionPayload(views.getFirst(), lobby().revision(), LobbyActionPayload.START, false));
            helper.assertTrue(game(0) != null && !game(0).paused(), "The test begins with an actual active table");
        }

        ServerPlayer player(int seat) { return fixtures.get(seat).player(); }
        LobbyRegistry.Snapshot lobby() { return service.registry().snapshot(tableId).orElseThrow(); }
        GameSnapshot game(int seat) { return service.gameView(tableId, player(seat).getUUID()); }
        void near(ServerPlayer player, BlockPos chair) { player.setPos(chair.getX() + .5, chair.getY(), chair.getZ() + .5); }
        void action(int seat, int action, int tile, long serial) {
            var game = game(seat);
            service.handleGameAction(player(seat), new GameActionPayload(views.get(seat), game.gameId(), game.revision(),
                    action, tile, serial, -1, List.of()));
        }
        void finishRound() {
            for (int step = 0; step < 500 && game(0).rules().result() == null; step++) {
                boolean response = false;
                for (int seat = 0; seat < fixtures.size(); seat++) {
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
            helper.assertTrue(game(0).rules().result() != null, "The single round reaches an actual paid terminal result");
        }
        @Override public void close() {
            for (var fixture : fixtures) { service.disconnect(fixture.player()); fixture.close(); }
            service.removeTable(helper.getLevel(), center);
            service.stop();
        }
    }
}
