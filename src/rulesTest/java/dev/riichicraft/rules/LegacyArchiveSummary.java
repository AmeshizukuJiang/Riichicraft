package dev.riichicraft.rules;

import dev.riichicraft.persistence.TableArchiveStore;
import java.util.List;
import java.util.stream.Collectors;

/** Explicit semantic summaries, also compiled against the frozen v16 writer when fixtures were made. */
final class LegacyArchiveSummary {
    private LegacyArchiveSummary() { }

    static String four(FourPlayerRound round) {
        return four(round.view(null), round.serverState());
    }

    static String four(FourPlayerMatch match) {
        var view = match.view(null);
        return "matchId=" + view.matchId() + ",revision=" + view.revision() + ",epoch=" + view.continueEpoch()
                + ",match=" + view.phase().name() + ",format=" + view.format().name()
                + ",index=" + view.roundIndex() + ",votes=" + view.confirmations()
                + ",final=" + view.result().map(r -> r.reason().name() + ":"
                        + r.standings().stream().map(s -> s.seat() + ":" + s.points() + ":" + s.totalTenths()).toList()).orElse("-")
                + ";" + four(view.round(), match.serverState());
    }

    private static String four(FourPlayerRound.View view, FourPlayerRound.ServerState state) {
        return "roundId=" + view.roundId() + ",revision=" + view.revision()
                + ",round=" + view.phase().name() + ",actor=" + view.actorSeat() + ",dealer=" + view.dealerSeat()
                + ",wind=" + view.roundWind() + ",live=" + view.liveTiles() + ",honba=" + view.honba()
                + ",pool=" + view.deposits() + ",points=" + state.ledger().points()
                + ",hands=" + state.hands().stream().map(LegacyArchiveSummary::hand).toList()
                + ",riichi=" + state.riichi().stream().map(r -> r.phase().name() + ":" + r.establishedRiichi().name()
                        + ":" + r.ippatsu() + ":" + r.waitTypes().stream().sorted().toList()).toList()
                + ",history=" + state.histories().stream().map(LegacyArchiveSummary::history).toList()
                + ",rivers=" + view.seats().stream().map(s -> s.river().stream().map(r -> r.tileId() + ":"
                        + r.declaration() + ":" + r.tsumogiri() + ":" + r.claimedBy()).toList()).toList()
                + ",window=" + view.opportunity().map(o -> o.id().serial() + ":" + o.actorSeat() + ":" + o.tileId() + ":" + o.kan()).orElse("-")
                + ",replacements=" + state.wall().replacementDraws()
                + ",liveWall=" + state.wall().liveTiles() + ",deadWall=" + state.wall().deadTiles()
                + ",end=" + view.end().map(e -> e.kind().name() + ":" + e.winners() + ":"
                        + e.tenpai().stream().sorted().toList() + ":" + e.dealerContinues() + ":"
                        + e.values().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).map(v -> v.getKey() + ":" + score(v.getValue())).toList() + ":"
                        + e.transfers().stream().map(p -> p.fromSeat() + ":" + p.toSeat() + ":" + p.points() + ":" + p.kind().name()).toList()).orElse("-");
    }

    static String three(ThreePlayerRound round) {
        return three(round.view(null), round.serverState());
    }

    static String three(ThreePlayerMatch match) {
        var view = match.view(null);
        return "matchId=" + view.matchId() + ",revision=" + view.revision() + ",epoch=" + view.continueEpoch()
                + ",match=" + view.phase().name() + ",format=" + view.format().name()
                + ",index=" + view.roundIndex() + ",votes=" + view.confirmations()
                + ",final=" + view.result().map(r -> r.reason().name() + ":"
                        + r.standings().stream().map(s -> s.seat() + ":" + s.points() + ":" + s.totalTenths()).toList()).orElse("-")
                + ";" + three(view.round(), match.serverState());
    }

    private static String three(ThreePlayerRound.View view, ThreePlayerRound.ServerState state) {
        return "roundId=" + view.roundId() + ",revision=" + view.revision()
                + ",round=" + view.phase().name() + ",actor=" + view.actorSeat() + ",dealer=" + view.dealerSeat()
                + ",wind=" + view.roundWind() + ",live=" + view.liveTiles() + ",honba=" + view.honba()
                + ",pool=" + view.deposits() + ",points=" + state.ledger().points()
                + ",hands=" + state.hands().stream().map(LegacyArchiveSummary::hand).toList()
                + ",riichi=" + state.riichi().stream().map(r -> r.phase().name() + ":" + r.establishedRiichi().name()
                        + ":" + r.ippatsu() + ":" + r.waitTypes().stream().sorted().toList()).toList()
                + ",history=" + state.histories().stream().map(LegacyArchiveSummary::history).toList()
                + ",rivers=" + view.seats().stream().map(s -> s.river().stream().map(r -> r.tileId() + ":"
                        + r.declaration() + ":" + r.tsumogiri() + ":" + r.claimedBy()).toList()).toList()
                + ",window=" + view.opportunity().map(o -> o.id().serial() + ":" + o.actorSeat() + ":" + o.tileId() + ":" + o.kan()).orElse("-")
                + ",replacements=" + state.wall().replacementDraws()
                + ",norths=" + state.norths() + ",liveWall=" + state.wall().liveTiles()
                + ",deadWall=" + state.wall().deadTiles()
                + ",end=" + view.end().map(e -> e.kind().name() + ":" + e.winners() + ":"
                        + e.tenpai().stream().sorted().toList() + ":" + e.dealerContinues() + ":"
                        + e.values().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).map(v -> v.getKey() + ":" + score(v.getValue())).toList() + ":"
                        + e.transfers().stream().map(p -> p.fromSeat() + ":" + p.toSeat() + ":" + p.points() + ":" + p.kind().name()).toList()).orElse("-");
    }

    private static String hand(HandInput hand) {
        return hand.concealedTiles() + "/" + hand.melds().stream().map(m -> m.kind().name() + ":" + m.tiles()).toList();
    }

    private static String history(FuritenState state) {
        return state.discardedTypes().stream().sorted().toList() + ":" + state.temporary() + ":" + state.riichiPass();
    }

    private static String score(ScoreResult value) {
        return value.winningTileId() + ":" + value.han() + ":" + value.fu() + ":" + value.yakumanUnits()
                + ":" + value.dora() + ":" + value.aka() + ":" + value.ura() + ":" + value.points().payment().totalPoints()
                + ":" + awards(value.yaku());
    }

    private static String score(ThreePlayerScoreResult value) {
        return value.winningTileId() + ":" + value.han() + ":" + value.fu() + ":" + value.yakumanUnits()
                + ":" + value.dora() + ":" + value.aka() + ":" + value.ura() + ":" + value.kita()
                + ":" + value.points().payment().totalPoints() + ":" + awards(value.yaku());
    }

    private static String awards(List<YakuAward> awards) {
        return awards.stream().map(y -> y.yaku().name() + ":" + y.han() + ":" + y.yakumanUnits()).collect(Collectors.joining("/"));
    }

    static String world(List<TableArchiveStore.Table> tables) {
        return tables.stream().map(t -> "id=" + t.tableId() + ",dimension=" + t.dimension() + ",pos=" + t.x() + ":" + t.y() + ":" + t.z()
                + ",mode=" + t.mode() + ",lobbyRevision=" + t.lobbyRevision() + ",gameRevision=" + t.gameRevision()
                + ",sides=" + t.chairSides() + ",seats=" + t.seats().stream().map(s -> s.playerId() + ":" + s.name()).toList()
                + ",core=" + switch (t.mode()) {
                    case 0 -> four(GameArchiveCodec.decodeRound(t.gameData()));
                    case 3 -> three(ThreePlayerArchiveCodec.decodeRound(t.gameData()));
                    default -> throw new IllegalArgumentException("Unexpected fixture world mode");
                }).toList().toString();
    }
}
