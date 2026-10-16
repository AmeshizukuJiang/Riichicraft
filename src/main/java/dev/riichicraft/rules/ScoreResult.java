package dev.riichicraft.rules;

import java.util.List;
import java.util.Objects;

/** Explained hand payment only, before honba, riichi deposits, liability and simultaneous winners. */
public record ScoreResult(WinningInterpretation interpretation, int winningTileId, List<YakuAward> yaku,
                          int han, int fu, int yakumanUnits, int dora, int aka, int ura,
                          PointCalculator.Points points, FuCalculator.FuResult fuResult) {
    public ScoreResult {
        Objects.requireNonNull(interpretation, "interpretation");
        yaku = List.copyOf(Objects.requireNonNull(yaku, "yaku"));
        Objects.requireNonNull(points, "points");
        Objects.requireNonNull(fuResult, "fuResult");
        if (han < 0 || fu < 0 || yakumanUnits < 0 || dora < 0 || aka < 0 || ura < 0) {
            throw new IllegalArgumentException("Score components cannot be negative");
        }
    }
}
