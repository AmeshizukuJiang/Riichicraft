package dev.riichicraft.test;

import dev.riichicraft.network.TableScenePayload;
import dev.riichicraft.table.TableHandVisibility;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.Level;

public final class TableSceneGameTests {
    @GameTest
    public void recipientHandsLobbyAndSettlementRoundTrip(GameTestHelper helper) {
        for (int count : new int[] {3, 4}) {
            var players = players(count); var hands = hands(count); var draws = hands.stream().map(List::getLast).toList();
            for (boolean finished : List.of(false, true)) for (int recipient = -1; recipient < count; recipient++) {
                UUID player = recipient < 0 ? new UUID(99, count) : players.get(recipient);
                var projection = TableHandVisibility.project(player, players, hands, draws, finished);
                for (boolean mounted : recipient < 0 ? List.of(false) : List.of(false, true)) {
                    var scene = new TableScenePayload(UUID.randomUUID(), UUID.randomUUID(), Level.NETHER,
                            new BlockPos(-17, 64, 31), UUID.randomUUID(), 97, projection.ownSeat(), sides(count),
                            mounted, projection.hands(), projection.drawnTiles(), finished);
                    assertRoundTrip(helper, scene);
                }
            }
            var lobby = new TableScenePayload(UUID.randomUUID(), UUID.randomUUID(), Level.OVERWORLD, BlockPos.ZERO,
                    null, 0, 0, sides(count), true, emptyHands(count), IntStream.range(0, count).map(i -> -1).boxed().toList(), false);
            assertRoundTrip(helper, lobby);
        }
        helper.succeed();
    }

    @GameTest
    public void collectionBoundsAreCheckedBeforeAllocation(GameTestHelper helper) {
        // Each malformed stream stops immediately at its collection count, with no following data.
        for (int field = 0; field < 4; field++) for (int bad : new int[] {-1, 5, Integer.MAX_VALUE}) {
            var b = buffer(helper);
            try {
                prefix(b, 1, -1);
                b.writeVarInt(field == 0 ? bad : 4);
                if (field > 0) { for (int side = 0; side < 4; side++) b.writeVarInt(side); b.writeBoolean(false); }
                if (field > 0) b.writeVarInt(field == 1 ? bad : 4);
                if (field > 1) b.writeVarInt(field == 2 ? (bad == 5 ? 15 : bad) : 0);
                if (field > 2) { for (int seat = 1; seat < 4; seat++) b.writeVarInt(0); b.writeVarInt(bad); }
                rejects(helper, b, "Collection count field " + field + " rejects " + bad + " before allocation");
            } finally { b.release(); }
        }
        for (int invalidTile : new int[] {-1, 136, Integer.MAX_VALUE}) {
            var b = buffer(helper);
            try {
                prefix(b, 1, -1); b.writeVarInt(3); b.writeVarInt(0); b.writeVarInt(1); b.writeVarInt(3); b.writeBoolean(false);
                b.writeVarInt(3); b.writeVarInt(1); b.writeVarInt(invalidTile);
                rejects(helper, b, "Tile identities bounded before remaining hands are decoded");
            } finally { b.release(); }
        }
        helper.succeed();
    }

    @GameTest
    public void offChairParticipantsCannotBecomeObservers(GameTestHelper helper) {
        var players = players(4); var hands = hands(4); var draws = hands.stream().map(List::getLast).toList();
        for (int iteration = 0; iteration < 20; iteration++) for (int own = 0; own < 4; own++) {
            var view = TableHandVisibility.project(players.get(own), players, hands, draws, false);
            var scene = new TableScenePayload(UUID.randomUUID(), UUID.randomUUID(), Level.OVERWORLD, BlockPos.ZERO,
                    UUID.randomUUID(), iteration, own, sides(4), false, view.hands(), view.drawnTiles(), false);
            helper.assertTrue(scene.hands().stream().filter(hand -> !hand.isEmpty()).count() == 1
                    && scene.hands().get(own).equals(hands.get(own)), "Unmounted participant retains only its own faces");
            boolean rejected = false;
            try {
                new TableScenePayload(scene.viewId(), scene.tableId(), scene.dimension(), scene.pos(), scene.gameId(),
                        scene.revision(), own, scene.sides(), false, hands, draws, false);
            } catch (IllegalArgumentException expected) { rejected = true; }
            helper.assertTrue(rejected, "Active participant packet cannot include opponents' concealed identities");
            var spectator = TableHandVisibility.project(new UUID(999, iteration), players, hands, draws, false);
            helper.assertTrue(spectator.hands().equals(hands) && view.hands().get((own + 1) % 4).isEmpty(),
                    "Interleaved observer requests cannot mutate an earlier participant scene");
        }
        // Constructor validation also applies to malicious wire data, not only server-built records.
        var b = buffer(helper);
        try {
            prefix(b, 1, 0); b.writeVarInt(4); for (int side = 0; side < 4; side++) b.writeVarInt(side); b.writeBoolean(false);
            b.writeVarInt(4); for (int seat = 0; seat < 4; seat++) { b.writeVarInt(1); b.writeVarInt(seat); }
            b.writeVarInt(4); for (int seat = 0; seat < 4; seat++) b.writeVarInt(-1); b.writeBoolean(false);
            rejects(helper, b, "Wire visibility cannot expand when mount flag is false");
        } finally { b.release(); }
        var mutable = new BlockPos.MutableBlockPos(1, 2, 3);
        var mutableHands = new ArrayList<List<Integer>>(); hands.forEach(hand -> mutableHands.add(new ArrayList<>(hand)));
        var mutableDraws = new ArrayList<>(draws);
        var scene = new TableScenePayload(UUID.randomUUID(), UUID.randomUUID(), Level.OVERWORLD, mutable,
                UUID.randomUUID(), 0, -1, sides(4), false, mutableHands, mutableDraws, false);
        mutable.set(7, 8, 9); mutableHands.getFirst().clear(); mutableDraws.clear();
        helper.assertTrue(scene.pos().equals(new BlockPos(1, 2, 3)) && scene.hands().equals(hands)
                && scene.drawnTiles().equals(draws), "Packet defensively owns position and nested identities");
        helper.succeed();
    }

    private static void prefix(RegistryFriendlyByteBuf b, long revision, int own) {
        b.writeUUID(UUID.randomUUID()); b.writeUUID(UUID.randomUUID()); b.writeResourceKey(Level.OVERWORLD); b.writeBlockPos(BlockPos.ZERO);
        b.writeBoolean(true); b.writeUUID(UUID.randomUUID()); b.writeVarLong(revision); b.writeVarInt(own);
    }
    private static void rejects(GameTestHelper helper, RegistryFriendlyByteBuf b, String message) {
        boolean rejected = false;
        try { TableScenePayload.CODEC.decode(b); } catch (DecoderException expected) { rejected = true; }
        helper.assertTrue(rejected, message);
    }
    private static void assertRoundTrip(GameTestHelper helper, TableScenePayload scene) {
        var b = buffer(helper);
        try {
            TableScenePayload.CODEC.encode(b, scene);
            helper.assertTrue(TableScenePayload.CODEC.decode(b).equals(scene) && b.readableBytes() == 0,
                    "Exactly one bounded scene round-trips, including nullable lobby round");
        } finally { b.release(); }
    }
    private static RegistryFriendlyByteBuf buffer(GameTestHelper helper) {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
    }
    private static List<UUID> players(int count) { return IntStream.range(0, count).mapToObj(i -> new UUID(count, i + 1)).toList(); }
    private static List<Integer> sides(int count) { return count == 3 ? List.of(0, 1, 3) : List.of(0, 1, 2, 3); }
    private static List<List<Integer>> hands(int count) {
        return IntStream.range(0, count).mapToObj(seat -> IntStream.range(seat * 28, seat * 28 + (seat == 0 ? 14 : 13)).boxed().toList()).toList();
    }
    private static List<List<Integer>> emptyHands(int count) { return IntStream.range(0, count).mapToObj(i -> List.<Integer>of()).toList(); }
}
