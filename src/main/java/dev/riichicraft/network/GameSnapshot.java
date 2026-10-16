package dev.riichicraft.network;

import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/** A recipient's visible state. Wall order, shuffle seeds and opponents' hands have no wire fields. */
public record GameSnapshot(UUID gameId, long revision, int turnSeat, int remainingTiles,
        List<Seat> seats, List<Integer> ownHand, int drawnTile, boolean paused, String statusKey) {
    private static final int SEAT_COUNT = 4;
    private static final int MAX_HAND_SIZE = 13;
    private static final int MAX_DISCARDS = 24;
    private static final int MAX_STATUS_LENGTH = 128;

    public static final StreamCodec<RegistryFriendlyByteBuf, GameSnapshot> CODEC = StreamCodec.of(
            GameSnapshot::encode, GameSnapshot::decode);

    public GameSnapshot {
        Objects.requireNonNull(gameId, "gameId");
        Objects.requireNonNull(statusKey, "statusKey");
        seats = List.copyOf(seats);
        ownHand = immutableTiles(ownHand, MAX_HAND_SIZE, "own hand");
        if (revision < 0 || turnSeat < 0 || turnSeat >= SEAT_COUNT
                || remainingTiles < 0 || remainingTiles > 69 || seats.size() != SEAT_COUNT
                || drawnTile < -1 || drawnTile > 135 || statusKey.length() > MAX_STATUS_LENGTH) {
            throw new IllegalArgumentException("Invalid game snapshot bounds");
        }
    }

    public record Seat(int handCount, List<Integer> discards, boolean present, boolean confirmed) {
        public Seat {
            if (handCount < 0 || handCount > 14) {
                throw new IllegalArgumentException("Invalid concealed hand count");
            }
            discards = immutableTiles(discards, MAX_DISCARDS, "discards");
        }
    }

    private static void encode(RegistryFriendlyByteBuf buf, GameSnapshot value) {
        buf.writeUUID(value.gameId());
        buf.writeLong(value.revision());
        buf.writeVarInt(value.turnSeat());
        buf.writeVarInt(value.remainingTiles());
        for (Seat seat : value.seats()) {
            buf.writeVarInt(seat.handCount());
            writeTiles(buf, seat.discards());
            buf.writeBoolean(seat.present());
            buf.writeBoolean(seat.confirmed());
        }
        writeTiles(buf, value.ownHand());
        buf.writeVarInt(value.drawnTile());
        buf.writeBoolean(value.paused());
        buf.writeUtf(value.statusKey(), MAX_STATUS_LENGTH);
    }

    private static GameSnapshot decode(RegistryFriendlyByteBuf buf) {
        UUID gameId = buf.readUUID();
        long revision = buf.readLong();
        int turnSeat = readRange(buf, 0, SEAT_COUNT - 1, "turn seat");
        int remainingTiles = readRange(buf, 0, 69, "remaining tiles");
        List<Seat> seats = new ArrayList<>(SEAT_COUNT);
        for (int index = 0; index < SEAT_COUNT; index++) {
            int handCount = readRange(buf, 0, 14, "concealed hand count");
            List<Integer> discards = readTiles(buf, MAX_DISCARDS, "discards");
            seats.add(new Seat(handCount, discards, buf.readBoolean(), buf.readBoolean()));
        }
        List<Integer> ownHand = readTiles(buf, MAX_HAND_SIZE, "own hand");
        int drawnTile = readRange(buf, -1, 135, "drawn tile");
        return new GameSnapshot(gameId, revision, turnSeat, remainingTiles, seats, ownHand,
                drawnTile, buf.readBoolean(), buf.readUtf(MAX_STATUS_LENGTH));
    }

    private static List<Integer> immutableTiles(List<Integer> tiles, int limit, String field) {
        List<Integer> copy = List.copyOf(tiles);
        if (copy.size() > limit || copy.stream().anyMatch(tile -> tile < 0 || tile > 135)) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        return copy;
    }

    private static void writeTiles(RegistryFriendlyByteBuf buf, List<Integer> tiles) {
        buf.writeVarInt(tiles.size());
        for (int tile : tiles) {
            buf.writeVarInt(tile);
        }
    }

    private static List<Integer> readTiles(RegistryFriendlyByteBuf buf, int limit, String field) {
        // Validate before allocating: hostile lengths cannot cause an unbounded collection allocation.
        int count = readRange(buf, 0, limit, field + " length");
        List<Integer> tiles = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            tiles.add(readRange(buf, 0, 135, field + " tile"));
        }
        return tiles;
    }

    private static int readRange(RegistryFriendlyByteBuf buf, int minimum, int maximum, String field) {
        int value = buf.readVarInt();
        if (value < minimum || value > maximum) {
            throw new DecoderException("Invalid " + field + ": " + value);
        }
        return value;
    }
}
