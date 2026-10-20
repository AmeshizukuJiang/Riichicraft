package dev.riichicraft.server;

import dev.riichicraft.network.GameActionPayload;
import dev.riichicraft.network.GameSnapshot;
import dev.riichicraft.rules.DiscardResponseWindow;
import dev.riichicraft.rules.FourPlayerRound;
import dev.riichicraft.rules.FourPlayerMatch;
import dev.riichicraft.rules.ThreePlayerRound;
import dev.riichicraft.rules.ThreePlayerMatch;
import dev.riichicraft.rules.KanActions;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Pure conversion of recipient-filtered core views. No world, identity lookup, wall access or mutation. */
final class GameSnapshotProjector {
    record Context(long revision, boolean paused, String statusKey, Set<UUID> present, Set<UUID> confirmed) {
        Context {
            Objects.requireNonNull(statusKey, "statusKey");
            present = Set.copyOf(present);
            confirmed = Set.copyOf(confirmed);
        }
    }

    private GameSnapshotProjector() { }

    static GameSnapshot fourPlayer(FourPlayerRound.View view, GameSnapshot.MatchInfo match, Context context, List<Integer> ura) {
        var seats = view.seats().stream().map(seat -> new GameSnapshot.Seat(seat.concealedCount(),
                seat.river().stream().map(FourPlayerRound.RiverTile::tileId).toList(),
                context.present().contains(seat.playerId()), context.confirmed().contains(seat.playerId()))).toList();
        var rulesBySeat = new ArrayList<GameSnapshot.SeatRules>(view.seats().size());
        for (var seat : view.seats()) {
            var melds = new ArrayList<GameSnapshot.MeldView>();
            for (int index = 0; index < seat.melds().size(); index++) {
                var meld = seat.melds().get(index);
                var origin = seat.origins().get(index);
                melds.add(new GameSnapshot.MeldView(meld.kind().ordinal(), meld.tiles(), origin.providerSeat(), origin.claimedTileId()));
            }
            var river = seat.river().stream().map(tile ->
                    new GameSnapshot.RiverMark(tile.declaration(), tile.tsumogiri(), tile.claimedBy())).toList();
            rulesBySeat.add(new GameSnapshot.SeatRules(melds, river, seat.points(), seat.riichi().ordinal()));
        }
        var options = view.turnOptions();
        var opportunity = view.opportunity().map(value -> new GameSnapshot.Opportunity(
                value.id().serial(), value.actorSeat(), value.tileId(), value.kan())).orElse(null);
        var rules = new GameSnapshot.RuleState(view.phase().ordinal(), view.dealerSeat(), view.roundWind(), view.honba(), view.deposits(),
                view.doraIndicators(), rulesBySeat, options.discards().stream().sorted().toList(),
                options.riichiDiscards().stream().sorted().toList(), kanOptions(options.kans()), options.tsumo(),
                options.nineTerminals(), opportunity, responseOptions(view.responses()), view.end().map(end -> resultView(end, ura)).orElse(null));
        return new GameSnapshot(view.roundId(), context.revision(), view.actorSeat(), view.liveTiles(), seats,
                view.ownHand().map(hand -> hand.concealedTiles()).orElse(List.of()), view.ownDraw().orElse(-1),
                context.paused(), context.statusKey(), rules, match);
    }

    static GameSnapshot threePlayer(ThreePlayerRound.View view, GameSnapshot.MatchInfo match, Context context, List<Integer> ura) {
        var seats = view.seats().stream().map(seat -> new GameSnapshot.Seat(seat.concealedCount(),
                seat.river().stream().map(ThreePlayerRound.RiverTile::tileId).toList(),
                context.present().contains(seat.playerId()), context.confirmed().contains(seat.playerId()))).toList();
        var rulesBySeat = new ArrayList<GameSnapshot.SeatRules>(view.seats().size());
        for (var seat : view.seats()) {
            var melds = new ArrayList<GameSnapshot.MeldView>();
            for (int index = 0; index < seat.melds().size(); index++) {
                var meld = seat.melds().get(index);
                var origin = seat.origins().get(index);
                melds.add(new GameSnapshot.MeldView(meld.kind().ordinal(), meld.tiles(), origin.providerSeat(), origin.claimedTileId()));
            }
            var river = seat.river().stream().map(tile ->
                    new GameSnapshot.RiverMark(tile.declaration(), tile.tsumogiri(), tile.claimedBy())).toList();
            rulesBySeat.add(new GameSnapshot.SeatRules(melds, river, seat.points(), seat.riichi().ordinal(), seat.norths()));
        }
        var options = view.turnOptions();
        var opportunity = view.opportunity().map(value -> new GameSnapshot.Opportunity(
                value.id().serial(), value.actorSeat(), value.tileId(), value.kan())).orElse(null);
        var rules = new GameSnapshot.RuleState(view.phase().ordinal(), view.dealerSeat(), view.roundWind(), view.honba(), view.deposits(),
                view.doraIndicators(), rulesBySeat, options.discards().stream().sorted().toList(),
                options.riichiDiscards().stream().sorted().toList(), kanOptions(options.kans()), options.tsumo(),
                options.nineTerminals(), opportunity, responseOptions(view.responses()), view.end().map(end -> resultView(end, ura)).orElse(null),
                options.kitas().stream().sorted().toList());
        return new GameSnapshot(view.roundId(), context.revision(), view.actorSeat(), view.liveTiles(), seats,
                view.ownHand().map(hand -> hand.concealedTiles()).orElse(List.of()), view.ownDraw().orElse(-1),
                context.paused(), context.statusKey(), rules, match);
    }

    static GameSnapshot.MatchInfo matchInfo(FourPlayerMatch.View view) {
        var result = view.result().map(value -> new GameSnapshot.MatchResult(value.reason().name(),
                value.standings().stream().map(seat -> new GameSnapshot.Standing(seat.seat(), seat.rank(), seat.points(),
                        seat.rawTenths(), seat.umaTenths(), seat.totalTenths())).toList(),
                value.depositRecipient(), value.depositPoints())).orElse(null);
        return new GameSnapshot.MatchInfo(view.matchId(), view.format().ordinal(), view.phase().ordinal(), view.initialDealer(),
                view.roundIndex(), view.continueEpoch(), view.confirmations(), result);
    }

    static GameSnapshot.MatchInfo matchInfo(ThreePlayerMatch.View view) {
        var result = view.result().map(value -> new GameSnapshot.MatchResult(value.reason().name(),
                value.standings().stream().map(seat -> new GameSnapshot.Standing(seat.seat(), seat.rank(), seat.points(),
                        seat.rawTenths(), seat.umaTenths(), seat.totalTenths())).toList(),
                value.depositRecipient(), value.depositPoints())).orElse(null);
        return new GameSnapshot.MatchInfo(view.matchId(), view.format().ordinal(), view.phase().ordinal(), view.initialDealer(),
                view.roundIndex(), view.continueEpoch(), view.confirmations(), result);
    }

    private static List<GameSnapshot.ActionOption> kanOptions(Collection<KanActions.Choice> choices) {
        return choices.stream().map(choice -> new GameSnapshot.ActionOption(
                choice.kind() == KanActions.Kind.CLOSED ? GameActionPayload.CLOSED_KAN : GameActionPayload.ADDED_KAN,
                choice.meldIndex(), choice.handTiles())).sorted(optionOrder()).toList();
    }

    private static List<GameSnapshot.ActionOption> responseOptions(Collection<DiscardResponseWindow.Choice> choices) {
        return choices.stream().map(GameSnapshotProjector::responseOption).sorted(optionOrder()).toList();
    }

    private static Comparator<GameSnapshot.ActionOption> optionOrder() {
        // Preserve the existing wire order, including physical-copy selections.
        return Comparator.comparingInt(GameSnapshot.ActionOption::action).thenComparingInt(GameSnapshot.ActionOption::meldIndex)
                .thenComparing(option -> option.handTiles().toString());
    }

    private static GameSnapshot.ActionOption responseOption(DiscardResponseWindow.Choice choice) {
        if (choice == DiscardResponseWindow.Simple.PASS) return new GameSnapshot.ActionOption(GameActionPayload.PASS, -1, List.of());
        if (choice == DiscardResponseWindow.Simple.RON) return new GameSnapshot.ActionOption(GameActionPayload.RON, -1, List.of());
        var call = (DiscardResponseWindow.Call) choice;
        int action = switch (call.kind()) {
            case CHI -> GameActionPayload.CHI;
            case PON -> GameActionPayload.PON;
            case OPEN_KAN -> GameActionPayload.OPEN_KAN;
            case CLOSED_KAN -> throw new IllegalArgumentException("Closed kan is not a discard response");
        };
        return new GameSnapshot.ActionOption(action, -1, call.handTiles());
    }

    private static GameSnapshot.Result resultView(FourPlayerRound.End end, List<Integer> ura) {
        var values = end.values().entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry -> {
            var value = entry.getValue();
            var awards = value.yaku().stream().map(award ->
                    new GameSnapshot.Award(award.yaku().name(), award.han(), award.yakumanUnits())).toList();
            return new GameSnapshot.WinValue(entry.getKey(), value.winningTileId(), awards, value.han(), value.fu(), value.yakumanUnits(),
                    value.dora(), value.aka(), value.ura(), value.points().payment().totalPoints());
        }).toList();
        return new GameSnapshot.Result(end.kind().ordinal(), end.winners(), end.tenpai().stream().sorted().toList(), values,
                end.transfers().stream().map(value -> new GameSnapshot.Payment(value.fromSeat(), value.toSeat(), value.points(),
                        value.kind().ordinal())).toList(), end.dealerContinues(), ura);
    }

    private static GameSnapshot.Result resultView(ThreePlayerRound.End end, List<Integer> ura) {
        var values = end.values().entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry -> {
            var value = entry.getValue();
            var awards = value.yaku().stream().map(award ->
                    new GameSnapshot.Award(award.yaku().name(), award.han(), award.yakumanUnits())).toList();
            return new GameSnapshot.WinValue(entry.getKey(), value.winningTileId(), awards, value.han(), value.fu(), value.yakumanUnits(),
                    value.dora(), value.aka(), value.ura(), value.points().payment().totalPoints(), value.kita());
        }).toList();
        return new GameSnapshot.Result(end.kind().ordinal(), end.winners(), end.tenpai().stream().sorted().toList(), values,
                end.transfers().stream().map(value -> new GameSnapshot.Payment(value.fromSeat(), value.toSeat(), value.points(),
                        value.kind().ordinal())).toList(), end.dealerContinues(), ura);
    }

    static GameSnapshot waiting(GameSnapshot live, int discarder, int tile, boolean declaration, long serial) {
        int next = live.turnSeat();
        var seats = new ArrayList<>(live.seats());
        var drawnSeat = seats.get(next);
        seats.set(next, new GameSnapshot.Seat(drawnSeat.handCount() - 1, drawnSeat.discards(), drawnSeat.present(), false));
        var hand = new ArrayList<>(live.ownHand());
        if (live.drawnTile() >= 0) hand.remove(Integer.valueOf(live.drawnTile()));
        var rules = live.rules();
        var rulesBySeat = new ArrayList<>(rules.seats());
        int deposits = rules.deposits();
        if (declaration) {
            var declared = rulesBySeat.get(discarder);
            deposits--;
            rulesBySeat.set(discarder, new GameSnapshot.SeatRules(declared.melds(), declared.river(), declared.points() + 1000,
                    1, declared.norths()));
        }
        var waiting = new GameSnapshot.RuleState(GameSnapshot.DISCARD_RESPONSES, rules.dealerSeat(), rules.roundWind(), rules.honba(), deposits,
                rules.doraIndicators(), rulesBySeat, List.of(), List.of(), List.of(), false, false,
                new GameSnapshot.Opportunity(serial, discarder, tile, false), List.of(), null);
        return new GameSnapshot(live.gameId(), live.revision(), discarder, live.remainingTiles() + 1, seats, hand, -1,
                false, "", waiting, live.match());
    }
}
