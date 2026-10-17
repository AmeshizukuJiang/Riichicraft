package dev.riichicraft.rules;

import java.util.List;
import java.util.Objects;

/** Three-player hand value; excludes honba, deposits, responsibility, and multi-winner settlement. */
public record ThreePlayerScoreResult(WinningInterpretation interpretation, int winningTileId,
                                     List<YakuAward> yaku, int han, int fu, int yakumanUnits,
                                     int dora, int aka, int ura, int kita,
                                     ThreePlayerPointCalculator.Points points, FuCalculator.FuResult fuResult) {
    public ThreePlayerScoreResult {
        Objects.requireNonNull(interpretation, "interpretation");
        ThreePlayerTiles.typeOf(winningTileId);
        yaku = List.copyOf(Objects.requireNonNull(yaku, "yaku"));
        Objects.requireNonNull(points, "points");
        Objects.requireNonNull(fuResult, "fuResult");
        if (han < 0 || fu < 0 || yakumanUnits < 0 || dora < 0 || aka < 0 || ura < 0 || kita < 0) {
            throw new IllegalArgumentException("Negative scoring value");
        }
    }
}
