package dev.riichicraft.server;

import dev.riichicraft.rules.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.IntStream;

/** Genuine historical cores and deterministic legal riichi walls; no reflective state injection. */
final class SnapshotFixtures {
    record Core(String name, int mode, List<UUID> players, byte[] bytes) {
        Core { players = List.copyOf(players); bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
    private SnapshotFixtures() { }

    static List<Core> historical() throws IOException {
        var properties = new Properties();
        try (var stream = resource("manifest.properties")) { properties.load(stream); }
        var result = new ArrayList<Core>();
        for (String name : properties.getProperty("fixture.names").split(",")) {
            if (name.equals("world-tables-v2")) continue;
            byte[] bytes;
            try (var stream = resource(name + ".bin")) { bytes = stream.readAllBytes(); }
            if (!sha(bytes).equals(properties.getProperty("fixture." + name + ".sha256")))
                throw new AssertionError("Historical core bytes changed: " + name);
            String kind = properties.getProperty("fixture." + name + ".kind");
            int mode; List<UUID> players;
            switch (kind) {
                case "FOUR_ROUND" -> { var view = GameArchiveCodec.decodeRound(bytes).view(null); mode = 0; players = view.seats().stream().map(FourPlayerRound.PublicSeat::playerId).toList(); }
                case "THREE_ROUND" -> { var view = ThreePlayerArchiveCodec.decodeRound(bytes).view(null); mode = 3; players = view.seats().stream().map(ThreePlayerRound.PublicSeat::playerId).toList(); }
                case "FOUR_MATCH" -> {
                    var view = GameArchiveCodec.decodeMatch(bytes, setup -> { throw new AssertionError("Restore must not deal another round"); }).view(null);
                    mode = view.format() == MatchRules.Format.EAST ? 1 : 2;
                    players = view.round().seats().stream().map(FourPlayerRound.PublicSeat::playerId).toList();
                }
                case "THREE_MATCH" -> {
                    var view = ThreePlayerArchiveCodec.decodeMatch(bytes, setup -> { throw new AssertionError("Restore must not deal another round"); }).view(null);
                    mode = view.format() == ThreePlayerMatchRules.Format.EAST ? 4 : 5;
                    players = view.round().seats().stream().map(ThreePlayerRound.PublicSeat::playerId).toList();
                }
                default -> throw new AssertionError("Unknown historical fixture kind: " + kind);
            }
            result.add(new Core(name, mode, players, bytes));
        }
        return List.copyOf(result);
    }

    static Core riichi(int mode) {
        int count = mode >= 3 ? 3 : 4;
        var players = players(count);
        UUID id = new UUID(0x1900, mode + 1);
        byte[] bytes;
        if (mode == 0) bytes = GameArchiveCodec.encodeRound(four(id, players, 0, 27, 0, List.of(25000, 25000, 25000, 25000), 0));
        else if (mode == 3) bytes = ThreePlayerArchiveCodec.encodeRound(three(id, players, 0, 27, 0, List.of(35000, 35000, 35000), 0));
        else if (count == 4) bytes = GameArchiveCodec.encodeMatch(new FourPlayerMatch(id, players,
                mode == 1 ? MatchRules.Format.EAST : MatchRules.Format.SOUTH, 0,
                setup -> four(new UUID(0x1910, mode), setup.players(), setup.dealerSeat(), setup.roundWind(), setup.honba(), setup.points(), setup.deposits())));
        else bytes = ThreePlayerArchiveCodec.encodeMatch(new ThreePlayerMatch(id, players,
                mode == 4 ? ThreePlayerMatchRules.Format.EAST : ThreePlayerMatchRules.Format.SOUTH, 0,
                setup -> three(new UUID(0x1910, mode), setup.players(), setup.dealerSeat(), setup.roundWind(), setup.honba(), setup.points(), setup.deposits())));
        return new Core("riichi-cover-" + mode, mode, players, bytes);
    }

    static List<UUID> players(int count) { return IntStream.range(0, count).mapToObj(i -> new UUID(0x1920 + count, i + 1)).toList(); }
    static FourPlayerRound four(UUID id, List<UUID> players, int dealer, int wind, int honba, List<Integer> points, int pool) {
        return FourPlayerRound.ordered(id, players, dealer, wind, honba, points, pool, wall(4));
    }
    static ThreePlayerRound three(UUID id, List<UUID> players, int dealer, int wind, int honba, List<Integer> points, int pool) {
        return ThreePlayerRound.ordered(id, players, dealer, wind, honba, points, pool, wall(3));
    }
    private static List<Integer> wall(int count) {
        int[][] hands = count == 4 ? new int[][] {
            {0,1,2,9,10,11,18,19,20,24,25,26,27},
            {0,0,0,9,9,9,18,18,18,28,28,28,31},
            {3,3,3,12,12,12,21,21,21,29,29,29,32},
            {6,6,6,15,15,15,24,24,24,30,30,30,33}
        } : new int[][] {
            {9,10,11,12,13,14,15,16,17,18,19,20,27},
            {9,9,9,12,12,12,15,15,15,28,28,28,31},
            {10,10,10,13,13,13,16,16,16,29,29,29,32}
        };
        int[] order = new int[count == 4 ? 136 : 108], copies = new int[34];
        Arrays.fill(order, -1); boolean[] used = new boolean[136];
        for (int seat = 0; seat < count; seat++) for (int i = 0; i < 13; i++) {
            int type = hands[seat][i];
            if (copies[type] == 4) throw new AssertionError("Fifth physical tile in deterministic wall");
            int tile = type * 4 + copies[type]++;
            order[i * count + seat] = tile; used[tile] = true;
        }
        int drawn = (count == 4 ? 8 : 26) * 4;
        if (used[drawn]) throw new AssertionError("Opening draw already held");
        order[13 * count] = drawn; used[drawn] = true;
        int next = 0;
        for (int i = 0; i < order.length; i++) if (order[i] < 0) {
            while (used[next] || count == 3 && next >= 4 && next < 32) next++;
            order[i] = next; used[next] = true;
        }
        return Arrays.stream(order).boxed().toList();
    }
    private static java.io.InputStream resource(String name) throws IOException {
        var stream = SnapshotFixtures.class.getResourceAsStream("/dev/riichicraft/rules/legacy/v0.0.16/" + name);
        if (stream == null) throw new IOException("Missing historical core resource: " + name);
        return stream;
    }
    static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
