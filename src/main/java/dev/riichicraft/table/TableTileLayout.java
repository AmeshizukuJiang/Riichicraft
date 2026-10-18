package dev.riichicraft.table;

import dev.riichicraft.game.TileCatalog;
import dev.riichicraft.network.GameSnapshot;
import dev.riichicraft.network.TableDisplaySnapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Deterministic geometry in model units around the table center, with Y=0 at its top. */
public final class TableTileLayout {
    public enum Group { HAND, RIVER, MELD, INDICATOR, NORTH, WALL, RESERVE }
    public record Tile(int face, float x, float y, float z, float yaw, boolean flat, boolean down, float scale, Group group, int seat, String key) { }
    private TableTileLayout() { }

    public static List<Tile> tiles(TableDisplaySnapshot snapshot) {
        List<Tile> result = new ArrayList<>();
        int players = snapshot.seats().size(), perWall = players == 4 ? 17 : 18;
        for (var slot : TableWallLayout.slots(players, snapshot.liveTiles(), snapshot.replacementDraws(),
                snapshot.indicators().size(), snapshot.frontSide(), snapshot.seats().stream().map(TableDisplaySnapshot.Seat::side).toList())) {
            int face = slot.indicator() < 0 ? -1 : snapshot.indicators().get(slot.indicator());
            add(result, face, (slot.stack() - (perWall - 1) / 2F) * 1.5F,
                    slot.layer() * (1.25F * 0.7F + 0.025F), 16, slot.side(), 0, true, face < 0, 0.7F,
                    face >= 0 ? Group.INDICATOR : slot.reserve() ? Group.RESERVE : Group.WALL, -1,"w:"+slot.index());
        }
        for (int owner = 0; owner < snapshot.seats().size(); owner++) {
            var seat = snapshot.seats().get(owner); int side = seat.side();
            for (int i = 0; i < seat.concealedCount(); i++)
                add(result, -1, -17.65F + i * 1.82F, 0, 20.5F, side, 0, false, false, 0.85F, Group.HAND, owner,"h:"+owner+":"+i);
            int rows = (seat.river().size() + 5) / 6;
            float scale = Math.min(0.75F, 8.9F / (Math.max(0, rows - 1) * 2.95F + 1.375F));
            for (int row = 0; row < rows; row++) {
                float left = -10.5F;
                for (int column = 0; column < 6 && row * 6 + column < seat.river().size(); column++) {
                    var discard = seat.river().get(row * 6 + column);
                    float width = (discard.sideways() ? 2.75F : 2F) * scale;
                    add(result, discard.tileId(), left + width / 2, 0, 5.5F + row * 2.95F * scale,
                            side, discard.sideways() ? 90 : 0, true, false, scale, Group.RIVER, owner,"p:"+discard.tileId());
                    left += width + 0.2F * scale;
                }
            }
            float right = 18.5F;
            for (var meld : seat.melds()) {
                boolean closed = meld.kind() == GameSnapshot.MELD_CLOSED_KAN;
                List<Integer> sorted = new ArrayList<>(meld.tiles()); sorted.sort(Comparator.naturalOrder());
                if (meld.added()) sorted.remove(Integer.valueOf(meld.addedTileId()));
                int slot = -1;
                if (!closed) {
                    int delta = Math.floorMod(snapshot.seats().get(meld.providerSeat()).side() - side, 4);
                    slot = delta == 3 ? 0 : delta == 2 ? 1 : (meld.added() ? 2 : sorted.size() - 1);
                    sorted.remove(Integer.valueOf(meld.claimedTileId())); sorted.add(slot, meld.claimedTileId());
                } else {
                    // An available red five remains face-up between the two covered ends.
                    sorted.sort(Comparator.<Integer, Boolean>comparing(TileCatalog::isRed).reversed().thenComparingInt(Integer::intValue));
                    sorted = new ArrayList<>(List.of(sorted.get(2), sorted.get(0), sorted.get(1), sorted.get(3)));
                }
                int size = meld.added() ? 3 : 4;
                if (meld.kind() == GameSnapshot.MELD_CHI || meld.kind() == GameSnapshot.MELD_PON) size = 3;
                Integer added = meld.added() ? meld.addedTileId() : null;
                float total = 0;
                for (int i = 0; i < size; i++) total += (i == slot ? 2.75F : 2) * 0.85F + (i == 0 ? 0 : 0.1F);
                float x = right - total;
                for (int i = 0; i < size; i++) {
                    float width = (i == slot ? 2.75F : 2) * 0.85F;
                    boolean down = closed && (i == 0 || i == size - 1);
                    add(result, down ? -1 : sorted.get(i), x + width / 2, 0, 20.5F, side, i == slot ? 90 : 0,
                            true, down, 0.85F, Group.MELD, owner,"p:"+sorted.get(i));
                    if (added != null && i == slot) add(result, added, x + width / 2, 1.25F * 0.85F + 0.025F,
                            20.5F, side, 90, true, false, 0.85F, Group.MELD, owner,"p:"+added);
                    x += width + 0.1F;
                }
                right -= total + 0.55F;
            }
            for (int i = 0; i < seat.norths().size(); i++) add(result, seat.norths().get(i), -17.4F + i * 1.8F,
                    0, 18.25F, side, 0, true, false, 0.75F, Group.NORTH, owner,"p:"+seat.norths().get(i));
        }
        return List.copyOf(result);
    }
    private static void add(List<Tile> output, int face, float x, float lift, float z, int side, float yaw,
            boolean flat, boolean down, float scale, Group group, int seat, String key) {
        float rx = switch (side) { case 1 -> z; case 2 -> -x; case 3 -> -z; default -> x; };
        float rz = switch (side) { case 1 -> -x; case 2 -> -z; case 3 -> x; default -> z; };
        float y = 0.02F + lift + (flat ? 1.25F : 2.75F) * scale / 2;
        output.add(new Tile(face, rx, y, rz, side * 90 + yaw, flat, down, scale, group, seat,key));
    }
}
