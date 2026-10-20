package dev.riichicraft.client;

import dev.riichicraft.network.GameActionPayload;
import dev.riichicraft.network.GameSnapshot;
import dev.riichicraft.network.LobbySnapshotPayload;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;

/** Display-only DTO fixtures, not claims that these summaries came from complete legal games. */
final class PresentationFixtures {
    private PresentationFixtures() { }

    static Map<String, LobbySnapshotPayload> results() {
        var result = new LinkedHashMap<String, LobbySnapshotPayload>();
        for (int count : new int[] {3, 4}) for (int dealer : new int[] {0, count - 1}) {
            String prefix = count + "p-dealer" + dealer + "-";
            for (int kind = 0; kind < 8; kind++) result.put(prefix + "end" + kind, snapshot(count, dealer, kind, 0));
            result.put(prefix + "intermission", snapshot(count, dealer, 0, 1));
            result.put(prefix + "final", snapshot(count, dealer, 2, 2));
            result.put(prefix + "multi-ron", snapshot(count, dealer, 0, 3));
            result.put(prefix + "yakuman", snapshot(count, dealer, 1, 4));
        }
        return result;
    }

    static List<GameSnapshot.ActionOption> actions() {
        return List.of(option(GameActionPayload.PASS), option(GameActionPayload.RON), option(GameActionPayload.TSUMO),
                option(GameActionPayload.NINE_TERMINALS), new GameSnapshot.ActionOption(GameActionPayload.CHI, -1, List.of(48, 56)),
                new GameSnapshot.ActionOption(GameActionPayload.PON, -1, List.of(52, 53)),
                new GameSnapshot.ActionOption(GameActionPayload.PON, -1, List.of(53, 54)),
                new GameSnapshot.ActionOption(GameActionPayload.OPEN_KAN, -1, List.of(52, 53, 54)),
                new GameSnapshot.ActionOption(GameActionPayload.CLOSED_KAN, -1, List.of(52, 53, 54, 55)),
                new GameSnapshot.ActionOption(GameActionPayload.ADDED_KAN, 0, List.of(52)),
                new GameSnapshot.ActionOption(GameActionPayload.KITA, -1, List.of(120)));
    }

    private static GameSnapshot.ActionOption option(int action) { return new GameSnapshot.ActionOption(action, -1, List.of()); }

    private static LobbySnapshotPayload snapshot(int count, int dealer, int kind, int variant) {
        List<Integer> winners = kind <= 1 || kind == 3 ? variant == 3 ? List.of(0, 2) : List.of(0) : List.of();
        List<Integer> tenpai = kind == 2 ? List.of(0, count - 1) : List.of();
        var values = new ArrayList<GameSnapshot.WinValue>();
        if (kind <= 1) for (int seat : winners) {
            boolean yakuman = variant == 4;
            values.add(new GameSnapshot.WinValue(seat, 52, yakuman
                    ? List.of(new GameSnapshot.Award("DAISANGEN", 0, 1), new GameSnapshot.Award("SUUANKOU_TANKI", 0, 2))
                    : List.of(new GameSnapshot.Award("RIICHI", 1, 0), new GameSnapshot.Award("PINFU", 1, 0)),
                    yakuman ? 0 : 4, yakuman ? 0 : 30, yakuman ? 3 : 0, 1, 1, 0,
                    yakuman ? 96000 : 7700, count == 3 ? 2 : 0));
        }
        List<GameSnapshot.Payment> payments = kind <= 3 ? List.of(
                new GameSnapshot.Payment(1, 0, 7700, 0), new GameSnapshot.Payment(2, 0, 300, 1),
                new GameSnapshot.Payment(-1, 0, 2000, 2), new GameSnapshot.Payment(0, 2, 1000, 3)) : List.of();
        var end = new GameSnapshot.Result(kind, winners, tenpai, values, payments, dealer == 0,
                kind <= 1 ? List.of(52, 108) : List.of());
        int start = count == 3 ? 35000 : 25000;
        var seats = IntStream.range(0, count).mapToObj(i -> new GameSnapshot.Seat(13, List.of(), true, false)).toList();
        var rulesSeats = IntStream.range(0, count).mapToObj(i -> new GameSnapshot.SeatRules(List.of(), List.of(),
                start + (i == 0 ? -2100 : i == 1 ? 1200 : 0), i == 0 ? 2 : i == 1 ? 1 : 0)).toList();
        var rules = new GameSnapshot.RuleState(GameSnapshot.FINISHED, dealer, 27, 2, 3, List.of(132, 133),
                rulesSeats, List.of(), List.of(), List.of(), false, false, null, List.of(), end);
        GameSnapshot.MatchInfo match = null;
        if (variant == 1 || variant == 2) {
            GameSnapshot.MatchResult finalResult = null;
            if (variant == 2) {
                var standings = new ArrayList<GameSnapshot.Standing>();
                // Ranking order differs from seat order; totals exercise signed tenths including int-min.
                for (int rank = 1; rank <= count; rank++) {
                    int seat = count - rank;
                    standings.add(new GameSnapshot.Standing(seat, rank, start + (seat == 1 ? 3000 : 0),
                            0, 0, rank == count ? Integer.MIN_VALUE : 155 - rank * 10));
                }
                finalResult = new GameSnapshot.MatchResult("TARGET_REACHED", standings, 1, 3000);
            }
            match = new GameSnapshot.MatchInfo(new UUID(2, count), variant == 2 ? 1 : 0,
                    variant == 2 ? GameSnapshot.MATCH_RESULT : GameSnapshot.MATCH_ROUND_RESULT,
                    0, count - 1, 7, IntStream.range(0, count).mapToObj(i -> i == 0).toList(), finalResult);
        }
        var game = new GameSnapshot(new UUID(3, count), 8, 0, 0, seats, List.of(), -1, false, "", rules, match);
        var lobbySeats = IntStream.range(0, count).mapToObj(i -> new LobbySnapshotPayload.Seat(new UUID(4, i),
                i == 0 ? "测试玩家零" : "Player-" + i, false)).toList();
        int mode = (count == 3 ? 3 : 0) + (variant == 1 ? 1 : variant == 2 ? 2 : 0);
        return new LobbySnapshotPayload(new UUID(5, count), new UUID(6, count), BlockPos.ZERO, 9,
                lobbySeats, 0, false, "", game, mode);
    }
}
