package dev.riichicraft.client;

import dev.riichicraft.network.GameSnapshot;
import dev.riichicraft.network.LobbySnapshotPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import static dev.riichicraft.network.GameActionPayload.*;

/** Read-only presentation of server summaries. Layout, input and legal-action selection stay in each screen. */
final class GamePresentation {
    private static final String[] WINDS = {"east", "south", "west", "north"};
    private static final String[] ENDS = {"ron", "tsumo", "exhaustive", "nagashi", "nine_terminals", "four_winds", "four_riichi", "four_kans"};

    /** The legacy cumulative delta and the table's whole-hand delta intentionally have different baselines. */
    record SeatResult(int seat, int roundPoints, int displayedPoints, int roundDelta, int cumulativeDelta) { }
    record Settlement(GameSnapshot.Result round, List<SeatResult> seats) {
        Settlement { seats = List.copyOf(seats); }
    }

    private GamePresentation() { }

    static Settlement settlement(GameSnapshot game) {
        var changes = settlementDeltas(game);
        int openingPoints = game.seats().size() == 3 ? 35000 : 25000;
        var seats = new ArrayList<SeatResult>();
        for (int seat = 0; seat < game.seats().size(); seat++) {
            int points = game.rules().seats().get(seat).points();
            seats.add(new SeatResult(seat, points, displayedPoints(game, seat), changes.get(seat), points - openingPoints));
        }
        return new Settlement(game.rules().result(), seats);
    }

    /** Accepted declaration debit plus settlement transfers and any final unclaimed-stick award. */
    static List<Integer> settlementDeltas(GameSnapshot game) {
        int[] deltas = new int[game.seats().size()];
        if (game.rules() != null) for (int seat = 0; seat < deltas.length; seat++)
            if (game.rules().seats().get(seat).riichiPhase() == 2) deltas[seat] -= 1000;
        if (game.rules() != null && game.rules().result() != null) for (var payment : game.rules().result().transfers()) {
            if (payment.fromSeat() >= 0) deltas[payment.fromSeat()] -= payment.points();
            deltas[payment.toSeat()] += payment.points();
        }
        if (game.match() != null && game.match().result() != null && game.match().result().depositRecipient() >= 0)
            deltas[game.match().result().depositRecipient()] += game.match().result().depositPoints();
        var result = new ArrayList<Integer>();
        for (int delta : deltas) result.add(delta);
        return List.copyOf(result);
    }

    static int displayedPoints(GameSnapshot game, int seat) {
        if (game.match() != null && game.match().result() != null)
            for (var standing : game.match().result().standings()) if (standing.seat() == seat) return standing.points();
        return game.rules().seats().get(seat).points();
    }

    static int displayedDeposits(GameSnapshot game) {
        return game.match() != null && game.match().result() != null ? 0 : game.rules().deposits();
    }

    static List<Component> tableResultLines(LobbySnapshotPayload snapshot) {
        var game = snapshot.game(); var value = settlement(game); var result = value.round();
        List<Component> lines = new ArrayList<>();
        if (!result.uraIndicators().isEmpty()) lines.add(uraIndicators(result));
        for (var win : result.values()) {
            lines.add(winValue(snapshot, win));
            for (var award : win.yaku()) lines.add(tableText(award.yakumanUnits() > 0 ? "award_yakuman" : "award",
                    yakuName(award), award.yakumanUnits() > 0 ? award.yakumanUnits() : award.han()));
            if (win.yakumanUnits() == 0) lines.add(bonuses(win, true));
        }
        lines.add(tableText("delta_heading"));
        for (var seat : value.seats()) lines.add(tableText("points", wind(snapshot, seat.seat()),
                snapshot.seats().get(seat.seat()).name(), seat.displayedPoints(), signed(seat.roundDelta())));
        for (var payment : result.transfers()) lines.add(payment(snapshot, payment));
        if (!result.tenpai().isEmpty()) lines.add(seatList(snapshot, "tenpai", result.tenpai()));
        if (game.match() != null && game.match().result() != null)
            for (var standing : game.match().result().standings()) lines.add(standing(snapshot, standing));
        return List.copyOf(lines);
    }

    static List<Component> legacyResultLines(LobbySnapshotPayload snapshot) {
        var game = snapshot.game();
        if (game.rules() == null || game.rules().result() == null) return List.of();
        var value = settlement(game); var result = value.round();
        boolean finalMatch = game.match() != null && game.match().phase() == GameSnapshot.MATCH_RESULT;
        List<Component> lines = new ArrayList<>();
        if (finalMatch) {
            var end = game.match().result();
            for (var standing : end.standings()) lines.add(standing(snapshot, standing));
            lines.add(gameText(game.seats().size() == 3 ? "ranking_formula_three" : "ranking_formula"));
            if (end.depositPoints() > 0) lines.add(gameText("final_deposits", end.depositPoints(),
                    snapshot.seats().get(end.depositRecipient()).name()));
            lines.add(gameText(game.seats().size() == 3 ? "last_round_result_three" : "last_round_result"));
        } else if (game.match() != null) lines.add(gameText(game.seats().size() == 3 ? "cumulative_points_three" : "cumulative_points"));
        if (!result.winners().isEmpty()) lines.add(seatList(snapshot, "winners", result.winners()));
        for (var seat : value.seats()) lines.add(gameText("result_points", wind(snapshot, seat.seat()),
                seat.roundPoints(), signed(seat.cumulativeDelta())));
        if (!result.tenpai().isEmpty()) lines.add(seatList(snapshot, "tenpai", result.tenpai()));
        for (var win : result.values()) {
            lines.add(winValue(snapshot, win));
            MutableComponent awards = Component.empty();
            for (var award : win.yaku()) {
                if (!awards.getString().isEmpty()) awards.append(" · ");
                awards.append(yakuName(award));
            }
            lines.add(awards);
            if (win.yakumanUnits() == 0) lines.add(bonuses(win, win.kita() > 0));
        }
        if (!result.uraIndicators().isEmpty()) lines.add(uraIndicators(result));
        for (var payment : result.transfers()) lines.add(payment(snapshot, payment));
        lines.add(gameText(game.match() == null ? "single_round_hint" : finalMatch ? "match_result_hint" : "next_round_hint_count",
                game.match() == null ? 0L : game.match().confirmations().stream().filter(Boolean::booleanValue).count(), game.seats().size()));
        return List.copyOf(lines);
    }

    static MutableComponent actionLabel(int action) {
        return gameText(switch (action) {
            case TSUMO -> "tsumo"; case NINE_TERMINALS -> "nine_terminals"; case PASS -> "pass"; case RON -> "ron";
            case CHI -> "chi"; case PON -> "pon"; case OPEN_KAN -> "open_kan"; case CLOSED_KAN -> "closed_kan";
            case ADDED_KAN -> "added_kan"; case KITA -> "kita"; default -> "actions";
        });
    }

    static Component optionLabel(GameSnapshot.ActionOption option) {
        var label = actionLabel(option.action());
        for (int tile : option.handTiles()) label.append(" ").append(TileFaces.shortName(tile));
        return label;
    }

    static Component wind(LobbySnapshotPayload snapshot, int seat) {
        int dealer = snapshot.game() == null || snapshot.game().rules() == null ? 0 : snapshot.game().rules().dealerSeat();
        int wind = (seat - dealer + snapshot.seats().size()) % snapshot.seats().size();
        return Component.translatable("screen.riichicraft.lobby." + WINDS[wind]);
    }

    static Component endLabel(int kind) { return gameText("end." + ENDS[kind]); }
    static Component roundWind(int tileType) { return Component.translatable("screen.riichicraft.lobby." + WINDS[tileType - 27]); }

    private static Component winValue(LobbySnapshotPayload snapshot, GameSnapshot.WinValue value) {
        return value.yakumanUnits() > 0
                ? gameText("value_yakuman", wind(snapshot, value.seat()), value.yakumanUnits(), value.totalPoints())
                : gameText("value", wind(snapshot, value.seat()), value.han(), value.fu(), value.totalPoints());
    }
    private static Component yakuName(GameSnapshot.Award award) {
        return Component.translatable("yaku.riichicraft." + award.yaku().toLowerCase(Locale.ROOT));
    }
    private static Component bonuses(GameSnapshot.WinValue value, boolean withKita) {
        return gameText(withKita ? "bonuses_kita" : "bonuses", value.dora(), value.aka(), value.ura(), value.kita());
    }
    private static Component uraIndicators(GameSnapshot.Result result) {
        var line = gameText("ura_indicators");
        for (int tile : result.uraIndicators()) line.append(" ").append(TileFaces.shortName(tile));
        return line;
    }
    private static Component seatList(LobbySnapshotPayload snapshot, String key, List<Integer> seats) {
        var line = gameText(key);
        for (int seat : seats) line.append(" ").append(wind(snapshot, seat));
        return line;
    }
    private static Component payment(LobbySnapshotPayload snapshot, GameSnapshot.Payment value) {
        return gameText("payment", value.fromSeat() < 0 ? gameText("deposit_pool") : wind(snapshot, value.fromSeat()),
                wind(snapshot, value.toSeat()), value.points());
    }
    private static Component standing(LobbySnapshotPayload snapshot, GameSnapshot.Standing value) {
        return gameText("standing", value.rank(), snapshot.seats().get(value.seat()).name(), value.points(), tenths(value.totalTenths()));
    }
    private static String signed(int value) { return (value >= 0 ? "+" : "") + value; }
    private static String tenths(int value) {
        long magnitude = Math.abs((long) value);
        return (value < 0 ? "-" : "+") + magnitude / 10 + "." + magnitude % 10;
    }
    private static MutableComponent gameText(String key, Object... args) { return Component.translatable("screen.riichicraft.game." + key, args); }
    private static MutableComponent tableText(String key, Object... args) { return Component.translatable("screen.riichicraft.table." + key, args); }
}
