package dev.riichicraft.rules;

import java.util.Objects;

/** One ordinary-han component or one genuine yakuman component, never both. */
public record YakuAward(Yaku yaku, int han, int yakumanUnits) {
    public YakuAward {
        Objects.requireNonNull(yaku, "yaku");
        if (han < 0 || han > 6 || yakumanUnits < 0 || yakumanUnits > 2
                || (han == 0) == (yakumanUnits == 0)) {
            throw new IllegalArgumentException("A yaku award needs either positive han or positive yakuman units");
        }
    }
}
