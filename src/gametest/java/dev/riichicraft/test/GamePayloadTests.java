package dev.riichicraft.test;

import dev.riichicraft.network.GameActionPayload;
import dev.riichicraft.network.GameSnapshot;
import dev.riichicraft.network.LobbySnapshotPayload;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;

/** Wire checks only; service tests verify which recipient is allowed to receive a private hand. */
public final class GamePayloadTests {
    @GameTest
    public void recipientAndSpectatorSnapshotsRoundTripWithoutAdditionalState(GameTestHelper helper) {
        List<GameSnapshot.Seat> seats = List.of(
                new GameSnapshot.Seat(14, List.of(0, 4), true, true),
                new GameSnapshot.Seat(13, List.of(8), true, false),
                new GameSnapshot.Seat(13, List.of(12), false, false),
                new GameSnapshot.Seat(13, List.of(16), true, true));
        GameSnapshot privateView = new GameSnapshot(id(100), 9, 0, 64, seats,
                List.of(17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29), 135,
                true, "message.riichicraft.game.paused");
        GameSnapshot spectatorView = new GameSnapshot(id(100), 9, 0, 64, seats,
                List.of(), -1, true, "message.riichicraft.game.paused");
        List<LobbySnapshotPayload.Seat> occupants = List.of(
                new LobbySnapshotPayload.Seat(id(1), "East", true),
                new LobbySnapshotPayload.Seat(id(2), "South", true),
                new LobbySnapshotPayload.Seat(id(3), "West", true),
                new LobbySnapshotPayload.Seat(id(4), "North", true));
        for (GameSnapshot view : List.of(privateView, spectatorView)) {
            LobbySnapshotPayload payload = new LobbySnapshotPayload(id(10), id(20), BlockPos.ZERO,
                    8, occupants, view == privateView ? 0 : -1, true, "", view);
            RegistryFriendlyByteBuf buf = buffer(helper);
            try {
                LobbySnapshotPayload.CODEC.encode(buf, payload);
                LobbySnapshotPayload decoded = LobbySnapshotPayload.CODEC.decode(buf);
                helper.assertTrue(decoded.equals(payload),
                        "Recipient-specific hand, public rivers and lifecycle flags must survive the complete packet");
                helper.assertTrue(buf.readableBytes() == 0,
                        "The snapshot packet must contain no trailing internal game state");
                if (decoded.ownSeat() == -1) {
                    helper.assertTrue(decoded.game().ownHand().isEmpty() && decoded.game().drawnTile() == -1,
                            "A spectator snapshot must preserve its empty private view");
                }
            } finally {
                buf.release();
            }
        }
        RegistryFriendlyByteBuf noGame = buffer(helper);
        try {
            LobbySnapshotPayload lobby = new LobbySnapshotPayload(id(10), id(20), BlockPos.ZERO,
                    8, occupants, 0, true, "");
            LobbySnapshotPayload.CODEC.encode(noGame, lobby);
            helper.assertTrue(LobbySnapshotPayload.CODEC.decode(noGame).equals(lobby)
                            && noGame.readableBytes() == 0,
                    "An ordinary lobby must still round-trip with an explicitly absent game");
        } finally {
            noGame.release();
        }
        assertSpectatorHandRejected(helper, occupants, privateView);
        assertSpectatorHandRejected(helper, occupants, new GameSnapshot(id(100), 9, 0, 64, seats,
                List.of(), 135, true, "message.riichicraft.game.paused"));
        helper.succeed();
    }

    @GameTest
    public void maliciousCollectionLengthsAndTileIdsAreRejectedBeforeAllocation(GameTestHelper helper) {
        assertDecodeRejected(helper, buf -> {
            writeHeader(buf);
            buf.writeVarInt(13);
            buf.writeVarInt(Integer.MAX_VALUE);
        }, "A huge river length must fail at its length field");
        assertDecodeRejected(helper, buf -> {
            writeHeader(buf);
            buf.writeVarInt(13);
            buf.writeVarInt(-1);
        }, "A negative river length must fail at its length field");
        assertDecodeRejected(helper, buf -> {
            writeHeader(buf);
            writeEmptySeats(buf);
            buf.writeVarInt(15);
        }, "Fifteen tiles cannot be smuggled into the fourteen-tile concealed-hand field");
        assertDecodeRejected(helper, buf -> {
            writeHeader(buf);
            buf.writeVarInt(13);
            buf.writeVarInt(1);
            buf.writeVarInt(136);
        }, "A nonexistent physical river tile must be rejected");
        assertDecodeRejected(helper, buf -> {
            writeHeader(buf);
            writeEmptySeats(buf);
            buf.writeVarInt(1);
            buf.writeVarInt(-1);
        }, "The absent-tile sentinel is invalid inside a hand");
        assertDecodeRejected(helper, buf -> {
            writeHeader(buf);
            writeEmptySeats(buf);
            buf.writeVarInt(0);
            buf.writeVarInt(136);
        }, "A nonexistent drawn tile must be rejected");
        helper.succeed();
    }

    @GameTest
    public void snapshotMetadataCannotEscapeProtocolLimits(GameTestHelper helper) {
        assertDecodeRejected(helper, buf -> {
            buf.writeUUID(id(100));
            buf.writeLong(0);
            buf.writeVarInt(4);
        }, "A fifth turn seat must be rejected before reading its body");
        assertDecodeRejected(helper, buf -> {
            buf.writeUUID(id(100));
            buf.writeLong(0);
            buf.writeVarInt(0);
            buf.writeVarInt(70);
        }, "The initial drawn tile leaves at most sixty-nine live-wall tiles");
        assertDecodeRejected(helper, buf -> {
            writeHeader(buf);
            buf.writeVarInt(15);
        }, "A concealed hand count cannot exceed fourteen");
        assertDecodeRejected(helper, buf -> {
            writeHeader(buf);
            writeEmptySeats(buf);
            buf.writeVarInt(0);
            buf.writeVarInt(-1);
            buf.writeBoolean(false);
            buf.writeUtf("x".repeat(129));
        }, "An excessive status key must fail the bounded string reader");
        helper.succeed();
    }

    @GameTest
    public void publishedPrivateViewsDoNotAliasMutableLists(GameTestHelper helper) {
        List<Integer> river = new ArrayList<>(List.of(12));
        List<Integer> hand = new ArrayList<>(List.of(16, 17));
        List<GameSnapshot.Seat> seats = new ArrayList<>(List.of(
                new GameSnapshot.Seat(13, river, true, true),
                new GameSnapshot.Seat(13, List.of(), true, true),
                new GameSnapshot.Seat(13, List.of(), true, true),
                new GameSnapshot.Seat(13, List.of(), true, true)));
        GameSnapshot snapshot = new GameSnapshot(id(100), 0, 0, 69, seats, hand, 18, false, "");
        river.clear();
        hand.clear();
        seats.clear();
        helper.assertTrue(snapshot.ownHand().equals(List.of(16, 17))
                        && snapshot.seats().size() == 4
                        && snapshot.seats().getFirst().discards().equals(List.of(12)),
                "Reusing mutable service lists must not change an already-published recipient view");
        assertUnmodifiable(helper, snapshot.ownHand()::clear);
        assertUnmodifiable(helper, snapshot.seats()::clear);
        assertUnmodifiable(helper, snapshot.seats().getFirst().discards()::clear);
        helper.succeed();
    }

    @GameTest
    public void gameIntentRoundTripAndInvalidPhysicalChoicesAreBounded(GameTestHelper helper) {
        for (GameActionPayload action : List.of(
                new GameActionPayload(id(1), id(100), 9, GameActionPayload.DISCARD, 135),
                new GameActionPayload(id(1), id(100), 9, GameActionPayload.RESUME, -1),
                new GameActionPayload(id(1), id(100), 9, GameActionPayload.ABORT, -1),
                new GameActionPayload(id(1), id(100), 9, GameActionPayload.RIICHI, 135),
                new GameActionPayload(id(1), id(100), 9, GameActionPayload.TSUMO, -1),
                new GameActionPayload(id(1), id(100), 9, GameActionPayload.NINE_TERMINALS, -1),
                new GameActionPayload(id(1), id(100), 9, GameActionPayload.PASS, -1, 8, -1, List.of()),
                new GameActionPayload(id(1), id(100), 9, GameActionPayload.RON, -1, 8, -1, List.of()),
                new GameActionPayload(id(1), id(100), 9, GameActionPayload.CHI, -1, 8, -1, List.of(4, 8)),
                new GameActionPayload(id(1), id(100), 9, GameActionPayload.PON, -1, 8, -1, List.of(16, 17)),
                new GameActionPayload(id(1), id(100), 9, GameActionPayload.OPEN_KAN, -1, 8, -1, List.of(16, 17, 18)),
                new GameActionPayload(id(1), id(100), 9, GameActionPayload.CLOSED_KAN, -1, -1, -1, List.of(16, 17, 18, 19)),
                new GameActionPayload(id(1), id(100), 9, GameActionPayload.ADDED_KAN, -1, -1, 2, List.of(19)),
                new GameActionPayload(id(1), id(100), 9, GameActionPayload.RETURN_LOBBY, -1))) {
            RegistryFriendlyByteBuf buf = buffer(helper);
            try {
                GameActionPayload.CODEC.encode(buf, action);
                helper.assertTrue(GameActionPayload.CODEC.decode(buf).equals(action) && buf.readableBytes() == 0,
                        "Game intentions must preserve view, game, revision, action and physical tile identity");
            } finally {
                buf.release();
            }
        }
        assertActionRejected(helper, GameActionPayload.DISCARD, -1);
        assertActionRejected(helper, GameActionPayload.DISCARD, 136);
        assertActionRejected(helper, GameActionPayload.RESUME, 0);
        assertActionRejected(helper, 14, -1);
        helper.succeed();
    }

    @GameTest
    public void nestedRulesAndRetainedSettlementRoundTripWithoutRevealingOtherHands(GameTestHelper helper) {
        List<GameSnapshot.Seat> seats = List.of(new GameSnapshot.Seat(13, List.of(4, 135), true, false),
                new GameSnapshot.Seat(10, List.of(), true, false), new GameSnapshot.Seat(13, List.of(), true, false),
                new GameSnapshot.Seat(13, List.of(), true, false));
        List<Integer> hand = List.of(132, 133, 20, 21, 24, 25, 28, 29, 32, 33);
        GameSnapshot.MeldView chi = new GameSnapshot.MeldView(GameSnapshot.MELD_CHI, List.of(4, 8, 12), 0, 4);
        List<GameSnapshot.SeatRules> ruleSeats = List.of(
                new GameSnapshot.SeatRules(List.of(), List.of(new GameSnapshot.RiverMark(false, false, 1),
                        new GameSnapshot.RiverMark(true, true, -1)), 24_000, 2),
                new GameSnapshot.SeatRules(List.of(chi), List.of(), 25_000, 0),
                new GameSnapshot.SeatRules(List.of(), List.of(), 25_000, 0),
                new GameSnapshot.SeatRules(List.of(), List.of(), 25_000, 0));
        List<GameSnapshot.ActionOption> responses = List.of(new GameSnapshot.ActionOption(GameActionPayload.PASS, -1, List.of()),
                new GameSnapshot.ActionOption(GameActionPayload.PON, -1, List.of(132, 133)));
        GameSnapshot.RuleState responding = new GameSnapshot.RuleState(GameSnapshot.DISCARD_RESPONSES, 0, 27, 2, 1,
                List.of(40), ruleSeats, List.of(), List.of(), List.of(), false, false,
                new GameSnapshot.Opportunity(8, 0, 135, false), responses, null);
        GameSnapshot snapshot = new GameSnapshot(id(100), 9, 0, 61, seats, hand, -1, false, "", responding);
        assertRoundTrip(helper, snapshot);
        GameSnapshot.Result result = new GameSnapshot.Result(0, List.of(1), List.of(),
                List.of(new GameSnapshot.WinValue(1, 135, List.of(new GameSnapshot.Award("YAKUHAI_RED", 1, 0)),
                        1, 30, 0, 0, 0, 0, 1_000)),
                List.of(new GameSnapshot.Payment(0, 1, 1_000, 0), new GameSnapshot.Payment(-1, 1, 1_000, 2)), false);
        GameSnapshot.RuleState finished = new GameSnapshot.RuleState(GameSnapshot.FINISHED, 0, 27, 2, 0,
                List.of(40), ruleSeats, List.of(), List.of(), List.of(), false, false, null, List.of(), result);
        assertRoundTrip(helper, new GameSnapshot(id(100), 12, 0, 61, seats, hand, -1, false, "", finished));
        GameSnapshot.RuleState observerRules = new GameSnapshot.RuleState(GameSnapshot.DISCARD_RESPONSES, 0, 27, 2, 1,
                List.of(40), ruleSeats, List.of(), List.of(), List.of(), false, false,
                new GameSnapshot.Opportunity(8, 0, 135, false), List.of(), null);
        GameSnapshot observer = new GameSnapshot(id(100), 9, 0, 61, seats, List.of(), -1, false, "", observerRules);
        assertRoundTrip(helper, observer);
        helper.assertTrue(observer.ownHand().isEmpty() && observer.rules().responses().isEmpty()
                        && observer.rules().legalDiscards().isEmpty() && observer.rules().kans().isEmpty(),
                "A rules-aware observer packet contains neither concealed tiles nor recipient action selections");
        assertUnmodifiable(helper, snapshot.rules().seats()::clear);
        assertUnmodifiable(helper, snapshot.rules().responses()::clear);
        assertUnmodifiable(helper, snapshot.rules().seats().get(1).melds().getFirst().tiles()::clear);
        assertUnmodifiable(helper, finished.result().transfers()::clear);
        helper.succeed();
    }

    @GameTest
    public void nestedCollectionAndExactSelectionCountsRejectHostileLengths(GameTestHelper helper) {
        assertDecodeRejected(helper, buf -> {
            writeRulesPrefix(buf);
            buf.writeVarInt(Integer.MAX_VALUE);
        }, "Nested dora length is bounded before allocation");
        assertDecodeRejected(helper, buf -> {
            writeRulesPrefix(buf);
            buf.writeVarInt(0);
            buf.writeVarInt(5);
        }, "A fifth nested rules seat is rejected before its body");
        assertDecodeRejected(helper, buf -> {
            writeRulesPrefix(buf);
            buf.writeVarInt(0);
            buf.writeVarInt(4);
            buf.writeVarInt(5);
        }, "A fifth declared meld is rejected before its body");
        RegistryFriendlyByteBuf buf = buffer(helper);
        try {
            writeActionHeader(buf, GameActionPayload.CLOSED_KAN, -1);
            buf.writeLong(-1); buf.writeVarInt(-1); buf.writeVarInt(Integer.MAX_VALUE);
            boolean rejected = false;
            try { GameActionPayload.CODEC.decode(buf); } catch (DecoderException expected) { rejected = true; }
            helper.assertTrue(rejected, "Hostile exact selection length is rejected before collection allocation");
        } finally { buf.release(); }
        for (List<Integer> invalid : List.of(List.of(16, 16, 18, 19), List.of(16, 17, 18, 136))) {
            boolean rejected = false;
            try { new GameActionPayload(id(1), id(100), 9, GameActionPayload.CLOSED_KAN, -1, -1, -1, invalid); }
            catch (IllegalArgumentException expected) { rejected = true; }
            helper.assertTrue(rejected, "Duplicated and nonexistent exact physical selections are rejected");
        }
        helper.succeed();
    }

    private static void assertDecodeRejected(GameTestHelper helper,
            Consumer<RegistryFriendlyByteBuf> write, String message) {
        RegistryFriendlyByteBuf buf = buffer(helper);
        try {
            write.accept(buf);
            boolean rejected = false;
            try {
                GameSnapshot.CODEC.decode(buf);
            } catch (DecoderException expected) {
                rejected = true;
            }
            // Partial bodies deliberately make an unbounded reader fail differently (or attempt allocation).
            helper.assertTrue(rejected, message);
        } finally {
            buf.release();
        }
    }

    private static void assertActionRejected(GameTestHelper helper, int action, int tileId) {
        RegistryFriendlyByteBuf buf = buffer(helper);
        try {
            writeActionHeader(buf, action, tileId);
            buf.writeLong(-1);
            buf.writeVarInt(-1);
            buf.writeVarInt(0);
            boolean rejected = false;
            try {
                GameActionPayload.CODEC.decode(buf);
            } catch (IllegalArgumentException expected) {
                rejected = true;
            }
            helper.assertTrue(rejected, "Malformed actions must be rejected by the packet decoder");
        } finally {
            buf.release();
        }
    }

    private static void assertRoundTrip(GameTestHelper helper, GameSnapshot snapshot) {
        RegistryFriendlyByteBuf buf = buffer(helper);
        try {
            GameSnapshot.CODEC.encode(buf, snapshot);
            helper.assertTrue(GameSnapshot.CODEC.decode(buf).equals(snapshot) && buf.readableBytes() == 0,
                    "Nested legal masks, provenance, score components and settlement entries round-trip completely");
        } finally { buf.release(); }
    }

    private static void writeActionHeader(RegistryFriendlyByteBuf buf, int action, int tile) {
        buf.writeUUID(id(1)); buf.writeUUID(id(100)); buf.writeLong(9); buf.writeVarInt(action); buf.writeVarInt(tile);
    }

    private static void writeRulesPrefix(RegistryFriendlyByteBuf buf) {
        writeHeader(buf); writeEmptySeats(buf); buf.writeVarInt(0); buf.writeVarInt(-1);
        buf.writeBoolean(false); buf.writeUtf(""); buf.writeBoolean(true);
        buf.writeVarInt(GameSnapshot.DRAWN_TURN); buf.writeVarInt(0); buf.writeVarInt(27);
        buf.writeVarInt(0); buf.writeVarInt(0);
    }

    private static void assertSpectatorHandRejected(GameTestHelper helper,
            List<LobbySnapshotPayload.Seat> occupants, GameSnapshot privateView) {
        boolean rejected = false;
        try {
            new LobbySnapshotPayload(id(10), id(20), BlockPos.ZERO, 8, occupants, -1, true, "", privateView);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        helper.assertTrue(rejected,
                "The server cannot accidentally wrap another player's hand or drawn tile in a spectator payload");
    }

    private static void assertUnmodifiable(GameTestHelper helper, Runnable mutation) {
        boolean rejected = false;
        try {
            mutation.run();
        } catch (UnsupportedOperationException expected) {
            rejected = true;
        }
        helper.assertTrue(rejected, "Published snapshot collections must be unmodifiable");
    }

    private static void writeHeader(RegistryFriendlyByteBuf buf) {
        buf.writeUUID(id(100));
        buf.writeLong(0);
        buf.writeVarInt(0);
        buf.writeVarInt(69);
    }

    private static void writeEmptySeats(RegistryFriendlyByteBuf buf) {
        for (int index = 0; index < 4; index++) {
            buf.writeVarInt(13);
            buf.writeVarInt(0);
            buf.writeBoolean(true);
            buf.writeBoolean(true);
        }
    }

    private static RegistryFriendlyByteBuf buffer(GameTestHelper helper) {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
    }

    private static UUID id(long value) {
        return new UUID(0, value);
    }
}
