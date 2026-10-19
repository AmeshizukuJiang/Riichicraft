package dev.riichicraft.network;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Public tabletop data only: concealed tiles are represented exclusively by counts. */
public record TableDisplaySnapshot(UUID gameId, boolean paused, boolean finished,
        int frontSide, int liveTiles, List<Integer> indicators, List<Seat> seats, List<Integer> uraIndicators) {
    public TableDisplaySnapshot(UUID gameId,boolean paused,boolean finished,int frontSide,int liveTiles,List<Integer> indicators,List<Seat> seats) {
        this(gameId,paused,finished,frontSide,liveTiles,indicators,seats,List.of());
    }
    public static final int MAX_RIVER = 136;

    public record Discard(int tileId, boolean sideways) {
        public Discard { tile(tileId); }
    }
    public record Meld(int kind, List<Integer> tiles, int providerSeat, int claimedTileId, int addedTileId) {
        public Meld {
            tiles = checkedTiles(tiles, 4);
            new GameSnapshot.MeldView(kind, tiles, providerSeat, claimedTileId);
            if (addedTileId != -1 && (kind != GameSnapshot.MELD_OPEN_KAN || addedTileId == claimedTileId || !tiles.contains(addedTileId))) fail("added kan tile");
        }
        public boolean added() { return addedTileId >= 0; }
    }
    /** side: south/east/north/west = 0/1/2/3; these are physical positions, not seat winds. */
    public record Seat(int side, int concealedCount, List<Discard> river, List<Meld> melds, List<Integer> norths) {
        public Seat {
            river = List.copyOf(river); melds = List.copyOf(melds); norths = checkedTiles(norths, 4);
            if (side < 0 || side > 3 || concealedCount < 0 || concealedCount > 14
                    || river.size() > MAX_RIVER || melds.size() > 4 || norths.stream().anyMatch(t -> t / 4 != 30)) fail("public seat");
        }
    }
    public TableDisplaySnapshot {
        if (gameId == null || frontSide < 0 || frontSide > 3) fail("table metadata");
        indicators = checkedTiles(indicators, 5); seats = List.copyOf(seats);
        uraIndicators = checkedTiles(uraIndicators, 5);
        if (!uraIndicators.isEmpty() && (!finished || uraIndicators.size() != indicators.size())
                || uraIndicators.stream().anyMatch(indicators::contains)) fail("revealed ura indicators");
        if (seats.size() != 3 && seats.size() != 4
                || new HashSet<>(seats.stream().map(Seat::side).toList()).size() != seats.size()) fail("public seats");
        int replacements = replacements(seats);
        if (replacements > (seats.size() == 4 ? 4 : 8) || liveTiles < 0
                || liveTiles > (seats.size() == 4 ? 69 : 54) - replacements) fail("public wall counts");
        int kans = (int) seats.stream().flatMap(s -> s.melds().stream()).filter(m -> m.tiles().size() == 4).count();
        if (indicators.size() > kans + 1) fail("public indicator count");
        dev.riichicraft.table.TableWallLayout.slots(seats.size(), liveTiles, replacements, indicators.size(),
                frontSide, seats.stream().map(Seat::side).toList());
        for (int owner = 0; owner < seats.size(); owner++) for (Meld meld : seats.get(owner).melds()) {
            if (meld.kind() != GameSnapshot.MELD_CLOSED_KAN
                    && (meld.providerSeat() >= seats.size() || meld.providerSeat() == owner)) fail("meld provider");
        }
        if (seats.size() == 4 && seats.stream().anyMatch(s -> !s.norths().isEmpty())) fail("four-player kita");
        if (seats.size() == 3) {
            indicators.forEach(TableDisplaySnapshot::sanma);
            uraIndicators.forEach(TableDisplaySnapshot::sanma);
            for (Seat seat : seats) {
                seat.river().forEach(d -> sanma(d.tileId()));
                for (Meld meld : seat.melds()) {
                    if (meld.kind() == GameSnapshot.MELD_CHI) fail("sanma chi");
                    meld.tiles().forEach(TableDisplaySnapshot::sanma);
                }
            }
        }
    }

    public static TableDisplaySnapshot fromSpectator(GameSnapshot view, int front, List<Integer> sides,
            java.util.function.BiFunction<Integer, Integer, Integer> addedKan) {
        if (view.rules() == null || !view.ownHand().isEmpty() || view.drawnTile() != -1
                || !view.rules().legalDiscards().isEmpty() || !view.rules().riichiDiscards().isEmpty()
                || !view.rules().kans().isEmpty() || !view.rules().responses().isEmpty()
                || !view.rules().kitas().isEmpty() || view.rules().canTsumo() || view.rules().canNineTerminals()) {
            fail("public display requires a spectator view");
        }
        if (sides.size() != view.seats().size()) fail("side count");
        List<Seat> seats = new ArrayList<>();
        for (int index = 0; index < sides.size(); index++) {
            var seat = view.seats().get(index); var rules = view.rules().seats().get(index);
            List<Discard> river = new ArrayList<>(); boolean turnNext = false;
            for (int i = 0; i < seat.discards().size(); i++) {
                var mark = rules.river().get(i);
                turnNext |= mark.declaration();
                if (mark.claimedBy() >= 0) continue;
                river.add(new Discard(seat.discards().get(i), turnNext)); turnNext = false;
            }
            List<Meld> melds = new ArrayList<>();
            for (int i = 0; i < rules.melds().size(); i++) {
                var meld = rules.melds().get(i);
                melds.add(new Meld(meld.kind(), meld.tiles(), meld.providerSeat(), meld.claimedTileId(), addedKan.apply(index, i)));
            }
            seats.add(new Seat(sides.get(index), seat.handCount(), river, melds, rules.norths()));
        }
        return new TableDisplaySnapshot(view.gameId(), view.paused(), view.rules().phase() == GameSnapshot.FINISHED,
                front, view.remainingTiles(), view.rules().doraIndicators(), seats,
                view.rules().result()==null?List.of():view.rules().result().uraIndicators());
    }

    public int replacementDraws() { return replacements(seats); }
    private static int replacements(List<Seat> seats) {
        return seats.stream().mapToInt(s -> s.norths().size()
                + (int) s.melds().stream().filter(m -> m.tiles().size() == 4).count()).sum();
    }

    private static List<Integer> checkedTiles(List<Integer> tiles, int max) {
        tiles = List.copyOf(tiles);
        if (tiles.size() > max || new HashSet<>(tiles).size() != tiles.size()) fail("tile list");
        tiles.forEach(TableDisplaySnapshot::tile); return tiles;
    }
    private static void tile(int id) { if (id < 0 || id >= 136) fail("tile id"); }
    private static void sanma(int id) { if (id / 4 >= 1 && id / 4 <= 7) fail("sanma tile"); }
    private static void fail(String message) { throw new IllegalArgumentException(message); }
}
