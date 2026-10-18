package dev.riichicraft.server;

import dev.riichicraft.block.MahjongChairBlock;
import dev.riichicraft.block.ModBlocks;
import dev.riichicraft.block.TableStructure;
import dev.riichicraft.entity.MahjongSeatEntity;
import dev.riichicraft.lobby.LobbyRegistry;
import dev.riichicraft.network.GameActionPayload;
import dev.riichicraft.network.GameSnapshot;
import dev.riichicraft.network.LobbyActionPayload;
import dev.riichicraft.network.TableScenePayload;
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
import net.minecraft.world.level.storage.LevelResource;

/** Native mount, roster, hand visibility and disk restoration; fixtures are not remote TCP clients. */
public final class SeatingServiceGameTests {
    @GameTest
    public void chairSideWinsOverJoinOrderForBothCountsAndAllTableRotations(GameTestHelper helper) {
        for (int count : new int[] {3, 4}) for (Direction front : Direction.Plane.HORIZONTAL)
                for (Direction empty : count == 3 ? List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST) : List.of(Direction.NORTH)) {
            try (Scenario s = new Scenario(helper, count, front, empty)) {
                for (int logical = count - 1; logical >= 0; logical--) s.sit(logical);
                for (int logical = 0; logical < count; logical++) {
                    var scene = s.scene(logical);
                    helper.assertTrue(scene.ownSeat() == logical && scene.sides().equals(s.sides)
                            && scene.mounted() && s.player(logical).getVehicle() instanceof MahjongSeatEntity,
                            "Actual physical side selects fixed seat despite reverse join order and table rotation");
                }
                UUID observerId = UUID.randomUUID();
                try (var observer = new ConnectedTestPlayer(helper.getLevel().getServer(), helper.getLevel(), s.pos,
                        observerId, "SeatObs" + observerId.toString().substring(0, 6))) {
                    var candidate = observer.player();
                    s.near(candidate, s.chairs.getFirst());
                    var occupied = s.player(0).getVehicle();
                    helper.assertTrue(!s.service.sit(candidate, s.chairs.getFirst()) && candidate.getVehicle() == null
                            && s.player(0).getVehicle() == occupied && s.lobby().seats().stream().allMatch(LobbyRegistry.Seat::occupied),
                            "Occupied physical chair rejects another player without duplicate mount or roster mutation");
                    if (count == 3) {
                        s.near(candidate, s.emptyChair);
                        helper.assertTrue(!s.service.sit(candidate, s.emptyChair) && candidate.getVehicle() == null,
                                "Empty Chair is a layout marker and cannot become a fourth participant");
                    }
                    s.service.disconnect(candidate);
                }
                var current = s.player(0).getVehicle();
                helper.assertTrue(s.service.sit(s.player(0), s.chairs.getFirst()) && s.player(0).getVehicle() == current,
                        "Repeated click on own chair reopens its view without adding another entity");
                s.views.set(0, s.service.openImmersive(s.player(0), s.pos));
                s.player(0).stopRiding(); s.service.tick();
                helper.assertTrue(s.service.registry().tableForPlayer(s.player(0).getUUID()).isEmpty()
                        && !s.lobby().seats().getFirst().occupied(), "Standing in the idle lobby releases its physical seat");
            }
        }
        helper.succeed();
    }

    @GameTest
    public void sameTickEmptyChairSwapCannotAcceptAnOtherwiseLegalDiscard(GameTestHelper helper) {
        for (Direction empty : Direction.Plane.HORIZONTAL) try (Scenario s = new Scenario(helper, 3, Direction.EAST, empty)) {
            s.sitAll(); s.start();
            var original = s.game(0); var hands = s.hands();
            var originalMount = s.player(1).getVehicle();
            var occupiedState = helper.getLevel().getBlockState(s.chairs.get(1));
            var emptyState = helper.getLevel().getBlockState(s.emptyChair);
            helper.getLevel().setBlockAndUpdate(s.chairs.get(1), ModBlocks.EMPTY_CHAIR.defaultBlockState()
                    .setValue(MahjongChairBlock.FACING, occupiedState.getValue(MahjongChairBlock.FACING)));
            helper.getLevel().setBlockAndUpdate(s.emptyChair, ModBlocks.MAHJONG_CHAIR.defaultBlockState()
                    .setValue(MahjongChairBlock.FACING, emptyState.getValue(MahjongChairBlock.FACING)));
            helper.assertTrue(TableStructure.inspect(helper.getLevel(), s.pos).matches(3)
                    && s.player(1).getVehicle() == originalMount && !originalMount.isRemoved(),
                    "Same-tick swap leaves a structurally valid three-player layout before the original mount can tick");
            helper.assertTrue(original.rules().legalDiscards().contains(original.drawnTile()), "Attempted discard is genuinely legal before the chair mutation");
            s.service.handleGameAction(s.player(0), new GameActionPayload(s.views.getFirst(), original.gameId(), original.revision(),
                    GameActionPayload.DISCARD, original.drawnTile(), -1, -1, List.of()));
            var current = s.game(0);
            helper.assertTrue(current.paused() && current.gameId().equals(original.gameId()) && s.hands().equals(hands)
                    && current.remainingTiles() == original.remainingTiles() && current.rules().seats().equals(original.rules().seats())
                    && !s.scene(1).mounted() && s.scene(1).ownSeat() == 1,
                    "Presence recheck rejects the legal action and preserves private membership without waiting for entity/service tick");
        }
        helper.succeed();
    }

    @GameTest
    public void leavingActiveChairRetainsOwnFacesAndRequiresFullMountedRecovery(GameTestHelper helper) {
        for (int count : new int[] {3, 4}) try (Scenario s = new Scenario(helper, count, Direction.WEST)) {
            s.sitAll(); s.start();
            var before = s.game(0); var hands = s.hands();
            s.player(1).stopRiding(); s.service.tick();
            var away = s.scene(1);
            helper.assertTrue(!away.mounted() && away.ownSeat() == 1 && away.hands().get(1).equals(hands.get(1))
                    && away.hands().stream().filter(hand -> !hand.isEmpty()).count() == 1,
                    "Leaving the chair preserves participant identity and only that player's hand faces");
            helper.assertTrue(s.game(0).paused() && s.hands().equals(hands) && s.game(0).gameId().equals(before.gameId())
                    && s.game(0).remainingTiles() == before.remainingTiles(), "Dismount pauses without consuming or replacing any game tile");
            UUID outsider = UUID.randomUUID();
            var spectator = s.service.sceneView(UUID.randomUUID(), s.table, outsider);
            helper.assertTrue(spectator.ownSeat() == -1 && !spectator.mounted() && spectator.hands().equals(hands),
                    "An actual non-participant observer can see all hands during that pause");
            s.action(0, GameActionPayload.RESUME);
            helper.assertTrue(s.game(0).paused(), "Players cannot confirm recovery while an original seat remains physically empty");
            s.near(s.player(1), s.chairs.get(2 % count));
            helper.assertTrue(!s.service.sit(s.player(1), s.chairs.get(2 % count)),
                    "An active participant cannot claim a different physical chair");
            s.sit(1);
            helper.assertTrue(s.game(0).paused() && s.hands().equals(hands), "Remount alone does not bypass the recovery quorum");
            for (int seat = 0; seat < count; seat++) {
                s.action(seat, GameActionPayload.RESUME);
                helper.assertTrue(s.game(0).paused() == (seat < count - 1) && s.hands().equals(hands),
                        "Only all original mounted participants' fresh confirmations resume the exact same hand");
            }
        }
        helper.succeed();
    }

    @GameTest
    public void serviceRestoreFreezesSkippedPhysicalSidesAndRequiresOriginalMounts(GameTestHelper helper) throws IOException {
        for (int count : new int[] {3, 4}) try (Scenario s = new Scenario(helper, count, Direction.EAST)) {
            s.sitAll(); s.start(); var original = s.game(0); var hands = s.hands();
            helper.assertTrue(s.service.save(), "Mounted game must reach a successful project-local save");
            var saved = TableArchiveStore.read(s.path).getFirst();
            helper.assertTrue(saved.chairSides().equals(s.sides), "Archive stores exact rotated physical-side order including the sanma gap");
            s.service.stop();
            s.service = new LobbyService(helper.getLevel().getServer(), s.path);
            for (int seat = 0; seat < count; seat++) {
                s.views.set(seat, s.service.openImmersive(s.player(seat), s.pos));
                var scene = s.scene(seat);
                helper.assertTrue(scene.ownSeat() == seat && scene.sides().equals(s.sides) && !scene.mounted()
                        && scene.hands().get(seat).equals(hands.get(seat)),
                        "Restored unmounted participants retain frozen seats and only their own identities");
            }
            helper.assertTrue(s.game(0).paused() && s.game(0).gameId().equals(original.gameId()) && s.hands().equals(hands),
                    "Fresh service restores the same paused hand while transient mount entities do not survive");
            s.action(0, GameActionPayload.RESUME);
            helper.assertTrue(s.game(0).paused(), "Opening the restored table cannot substitute for sitting at its original chair");
            s.sitAll();
            for (int seat = 0; seat < count; seat++) s.action(seat, GameActionPayload.RESUME);
            helper.assertTrue(!s.game(0).paused() && s.hands().equals(hands) && s.scene(0).sides().equals(s.sides),
                    "Original chair mounts plus fresh full quorum recover the exact saved scene");
        }
        helper.succeed();
    }

    private static final class Scenario implements AutoCloseable {
        final GameTestHelper helper;
        final BlockPos pos;
        final BlockPos emptyChair;
        final Path path;
        final List<ConnectedTestPlayer> fixtures = new ArrayList<>();
        final List<UUID> views = new ArrayList<>();
        final List<BlockPos> chairs = new ArrayList<>();
        final List<Integer> sides = new ArrayList<>();
        LobbyService service;
        UUID table;

        Scenario(GameTestHelper helper, int count, Direction front) {
            this(helper, count, front, Direction.NORTH);
        }
        Scenario(GameTestHelper helper, int count, Direction front, Direction empty) {
            this.helper = helper; pos = helper.absolutePos(new BlockPos(3, 2, 3));
            emptyChair = count == 3 ? pos.relative(empty, 2) : null;
            path = helper.getLevel().getServer().getWorldPath(LevelResource.ROOT)
                    .resolve("data/riichicraft-tests/" + UUID.randomUUID() + "/tables.dat");
            service = new LobbyService(helper.getLevel().getServer(), path);
            FurnitureTestLayout.body(helper.getLevel(), pos, front);
            for (Direction side : Direction.Plane.HORIZONTAL)
                helper.getLevel().setBlockAndUpdate(pos.relative(side, 2),
                        (count == 3 && side == empty ? ModBlocks.EMPTY_CHAIR : ModBlocks.MAHJONG_CHAIR).defaultBlockState()
                                .setValue(MahjongChairBlock.FACING, side.getOpposite()));
            Direction physical = front.getOpposite();
            for (int index = 0; index < 4; index++, physical = physical.getCounterClockWise()) {
                if (count == 3 && physical == empty) continue;
                chairs.add(pos.relative(physical, 2)); sides.add(side(physical));
            }
            for (int seat = 0; seat < count; seat++) {
                fixtures.add(new ConnectedTestPlayer(helper.getLevel().getServer(), helper.getLevel(), pos,
                        "Seat" + UUID.randomUUID().toString().substring(0, 9)));
                views.add(null);
            }
        }
        ServerPlayer player(int seat) { return fixtures.get(seat).player(); }
        void near(ServerPlayer player, BlockPos chair) { player.setPos(chair.getX() + 0.5, chair.getY(), chair.getZ() + 0.5); }
        void sit(int seat) {
            near(player(seat), chairs.get(seat));
            helper.assertTrue(service.sit(player(seat), chairs.get(seat)), "Original prescribed physical chair accepts its owner");
            table = service.registry().tableForPlayer(player(seat).getUUID()).orElseThrow();
            views.set(seat, service.openImmersive(player(seat), pos));
            helper.assertTrue(views.get(seat) != null, "Mounted owner opens a fresh immersive subscription");
        }
        void sitAll() { for (int seat = 0; seat < fixtures.size(); seat++) sit(seat); }
        LobbyRegistry.Snapshot lobby() { return service.registry().snapshot(table).orElseThrow(); }
        GameSnapshot game(int seat) { return service.gameView(table, player(seat).getUUID()); }
        List<List<Integer>> hands() { var result = new ArrayList<List<Integer>>(); for (int seat = 0; seat < fixtures.size(); seat++) result.add(game(seat).ownHand()); return List.copyOf(result); }
        TableScenePayload scene(int seat) { return service.sceneView(views.get(seat), table, player(seat).getUUID()); }
        void start() {
            for (int seat = 0; seat < fixtures.size(); seat++) service.handleAction(player(seat),
                    new LobbyActionPayload(views.get(seat), lobby().revision(), LobbyActionPayload.SET_READY, true));
            service.handleAction(player(0), new LobbyActionPayload(views.getFirst(), lobby().revision(), LobbyActionPayload.START, false));
            helper.assertTrue(game(0) != null && !game(0).paused(), "Fixed initial east starts only after all mounted seats are ready");
        }
        void action(int seat, int action) {
            var game = game(seat);
            service.handleGameAction(player(seat), new GameActionPayload(views.get(seat), game.gameId(), game.revision(), action, -1, -1, -1, List.of()));
        }
        @Override public void close() {
            for (var fixture : fixtures) { service.disconnect(fixture.player()); fixture.close(); }
            service.removeTable(helper.getLevel(), pos); service.stop();
        }
    }
    private static int side(Direction side) {
        return switch (side) { case SOUTH -> 0; case EAST -> 1; case NORTH -> 2; case WEST -> 3; default -> throw new IllegalArgumentException(); };
    }
}
