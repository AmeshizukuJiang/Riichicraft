package dev.riichicraft.server;

import com.google.gson.JsonParser;
import dev.riichicraft.block.ModBlocks;
import dev.riichicraft.network.GameActionPayload;
import dev.riichicraft.network.GameSnapshot;
import dev.riichicraft.persistence.TableArchiveStore;
import dev.riichicraft.rules.*;
import dev.riichicraft.test.ConnectedTestPlayer;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.storage.LevelResource;

/** Original wire compatibility and explicit recipient boundaries across real service owners. */
public final class SnapshotProjectionGameTests {
    @GameTest
    public void historicalAndRiichiCoverPacketsRetainOriginalEncoding(GameTestHelper helper) throws IOException {
        var checks = new Checks(helper); var packets = new LinkedHashMap<String, String>();
        var fixtures = new ArrayList<>(SnapshotFixtures.historical());
        for (int mode = 0; mode < 6; mode++) fixtures.add(SnapshotFixtures.riichi(mode));
        for (var core : fixtures) try (var scenario = new Scenario(helper, core)) {
            scenario.sample("restored", packets, checks);
            scenario.openAll();
            boolean active = scenario.game(0).rules().phase() != GameSnapshot.FINISHED;
            if (active) {
                scenario.action(0, GameActionPayload.RESUME, -1);
                scenario.sample("one-confirmed", packets, checks);
                for (int seat = 1; seat < core.players().size(); seat++) scenario.action(seat, GameActionPayload.RESUME, -1);
                checks.require(!scenario.game(0).paused(), "All original identities resume the unchanged restored core");
            }
            scenario.sample("opened", packets, checks);
            if (core.name().startsWith("riichi-cover")) {
                var opening = scenario.game(0); int tile = opening.drawnTile();
                checks.require(opening.rules().riichiDiscards().contains(tile), "Deterministic real wall offers the declaration");
                scenario.service.responseDelaySource = () -> 4;
                scenario.action(0, GameActionPayload.RIICHI, tile);
                var waiting = scenario.game(0);
                checks.require(waiting.rules().phase() == GameSnapshot.DISCARD_RESPONSES && waiting.rules().responses().isEmpty()
                        && waiting.rules().seats().getFirst().riichiPhase() == 1 && waiting.rules().deposits() == 0,
                        "A genuine accepted declaration is shown as pending while the next draw is cosmetically held");
                scenario.sample("riichi-cover", packets, checks);
            }
            scenario.disconnectLast();
            scenario.sample("absent", packets, checks);
        }
        if (Boolean.getBoolean("riichicraft.test.captureSnapshots")) SnapshotBaselineCapture.write(packets);
        else {
            try (var stream = getClass().getResourceAsStream("/dev/riichicraft/server/snapshots-v18.json")) {
                if (stream == null) throw new IOException("Missing original snapshot wire baseline");
                var root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
                checks.require(root.get("jarSha256").getAsString().equals(SnapshotBaselineCapture.SHA), "Frozen writer identity stays original v18");
                var old = root.getAsJsonObject("packets");
                checks.require(old.keySet().equals(packets.keySet()), "Every original recipient and lifecycle packet remains covered");
                for (var value : packets.entrySet()) checks.require(old.get(value.getKey()).getAsString().equals(value.getValue()),
                        "Original network bytes remain identical: " + value.getKey());
            }
        }
        System.out.println("PASS snapshot projection wire: " + checks.count + " assertions; " + packets.size() + " recipient packets; " + fixtures.size() + " actual cores");
        helper.succeed();
    }

    @GameTest
    public void projectionMetadataIsImmutableAndDoesNotMutateAuthoritativeCores(GameTestHelper helper) {
        var checks = new Checks(helper);
        for (int count : new int[] {3, 4}) {
            var players = SnapshotFixtures.players(count);
            var present = new HashSet<>(players); var confirmed = new HashSet<>(List.of(players.getFirst()));
            var context = new GameSnapshotProjector.Context(17, true, "message.riichicraft.game.awaiting_confirmations", present, confirmed);
            present.clear(); confirmed.clear();
            checks.require(context.present().size() == count && context.confirmed().equals(Set.of(players.getFirst())), "Projection metadata takes defensive copies");
            try { context.present().clear(); throw new AssertionError("Mutable projection context"); }
            catch (UnsupportedOperationException expected) { checks.count++; }
            GameSnapshot snapshot;
            if (count == 4) {
                var core = SnapshotFixtures.four(new UUID(0x1930, 4), players, 0, 27, 0, List.of(25000,25000,25000,25000), 0);
                byte[] before = GameArchiveCodec.encodeRound(core);
                snapshot = GameSnapshotProjector.fourPlayer(core.view(players.getFirst()), null, context, List.of());
                checks.require(Arrays.equals(before, GameArchiveCodec.encodeRound(core)), "Four-player projection cannot mutate accepted history or wall");
            } else {
                var core = SnapshotFixtures.three(new UUID(0x1930, 3), players, 0, 27, 0, List.of(35000,35000,35000), 0);
                byte[] before = ThreePlayerArchiveCodec.encodeRound(core);
                snapshot = GameSnapshotProjector.threePlayer(core.view(players.getFirst()), null, context, List.of());
                checks.require(Arrays.equals(before, ThreePlayerArchiveCodec.encodeRound(core)), "Three-player projection cannot mutate accepted history or wall");
            }
            checks.require(snapshot.seats().stream().allMatch(GameSnapshot.Seat::present) && snapshot.seats().getFirst().confirmed()
                    && snapshot.seats().stream().skip(1).noneMatch(GameSnapshot.Seat::confirmed), "Both modes use the copied presence and confirmation metadata");
            checks.require(snapshot.paused() && snapshot.revision() == 17 && snapshot.ownHand().size() == 14, "Pure conversion retains runtime revision, pause and the recipient's original draw");
        }
        System.out.println("PASS snapshot projection purity: " + checks.count + " assertions");
        helper.succeed();
    }

    private static final class Checks {
        final GameTestHelper helper; int count;
        Checks(GameTestHelper helper) { this.helper = helper; }
        void require(boolean condition, String message) { count++; helper.assertTrue(condition, message); }
    }

    private static final class Scenario implements AutoCloseable {
        final GameTestHelper helper; final SnapshotFixtures.Core core; final LobbyService service;
        final List<ConnectedTestPlayer> players = new ArrayList<>(); final List<UUID> views = new ArrayList<>();
        final UUID table;
        Scenario(GameTestHelper helper, SnapshotFixtures.Core core) throws IOException {
            this.helper = helper; this.core = core;
            var pos = helper.absolutePos(new BlockPos(2,2,2));
            helper.setBlock(new BlockPos(2,2,2), ModBlocks.MAHJONG_TABLE);
            table = UUID.nameUUIDFromBytes(core.name().getBytes(StandardCharsets.UTF_8));
            var path = helper.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("data/riichicraft-tests/snapshots-" + UUID.randomUUID() + "/tables.dat");
            var seats = new ArrayList<TableArchiveStore.Seat>();
            for (int i = 0; i < core.players().size(); i++) {
                players.add(new ConnectedTestPlayer(helper.getLevel().getServer(), helper.getLevel(), pos, core.players().get(i), "Snap" + i));
                seats.add(new TableArchiveStore.Seat(core.players().get(i), "Snap" + i));
            }
            TableArchiveStore.write(path, List.of(new TableArchiveStore.Table(table, helper.getLevel().dimension().identifier().toString(),
                    pos.getX(), pos.getY(), pos.getZ(), core.mode(), 5, 11, seats, core.bytes())));
            service = new LobbyService(helper.getLevel().getServer(), path); service.responseDelaySource = () -> 0;
        }
        GameSnapshot game(int seat) { return service.gameView(table, core.players().get(seat)); }
        void openAll() {
            var pos = helper.absolutePos(new BlockPos(2,2,2));
            for (var player : players) views.add(service.open(player.player(), pos));
            if (views.stream().anyMatch(Objects::isNull)) throw new AssertionError("Original participants must reopen their stored table");
        }
        void action(int seat, int action, int tile) {
            var state = game(seat);
            service.handleGameAction(players.get(seat).player(), new GameActionPayload(views.get(seat), state.gameId(), state.revision(), action, tile));
        }
        void disconnectLast() {
            var player = players.getLast(); service.disconnect(player.player()); player.close();
        }
        void sample(String stage, Map<String,String> output, Checks checks) {
            var publicView = service.gameView(table, null);
            var outsider = service.gameView(table, new UUID(0x1940, 99));
            checks.require(publicView != null && publicView.equals(outsider), "Unknown recipient stays identical to the public view");
            var rule = publicView.rules();
            checks.require(publicView.ownHand().isEmpty() && publicView.drawnTile() == -1 && rule.legalDiscards().isEmpty()
                    && rule.riichiDiscards().isEmpty() && rule.kans().isEmpty() && rule.kitas().isEmpty() && rule.responses().isEmpty()
                    && !rule.canTsumo() && !rule.canNineTerminals(), "Public and unknown identities never receive a concealed hand or private action mask");
            var seen = new HashSet<Integer>();
            for (int seat = 0; seat < core.players().size(); seat++) {
                var own = game(seat);
                checks.require(own.ownHand().size() == own.seats().get(seat).handCount(), "Each original identity has exactly its own concealed count");
                checks.require(own.rules().seats().equals(rule.seats()), "Private action masks cannot change public seat state");
                for (int tile : own.ownHand()) checks.require(seen.add(tile), "No physical tile leaks into two participants' concealed hands");
                output.put(core.name() + "/" + stage + "/p" + seat, encode(own));
            }
            output.put(core.name() + "/" + stage + "/public", encode(publicView));
            output.put(core.name() + "/" + stage + "/outsider", encode(outsider));
        }
        String encode(GameSnapshot snapshot) {
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
            try { GameSnapshot.CODEC.encode(buffer, snapshot); byte[] bytes = new byte[buffer.readableBytes()]; buffer.readBytes(bytes); return HexFormat.of().formatHex(bytes); }
            finally { buffer.release(); }
        }
        @Override public void close() {
            for (var player : players) { service.disconnect(player.player()); player.close(); }
            service.stop();
        }
    }
}
