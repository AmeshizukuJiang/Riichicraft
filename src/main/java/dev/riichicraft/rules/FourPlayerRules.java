package dev.riichicraft.rules;

/** Versioned, fixed scoring choices; these are not player-selectable configuration switches. */
public final class FourPlayerRules {
    public static final String ID = "riichicraft:four_player_v1";
    public static final int REVISION = 1;
    public static final boolean KIRIAGE_MANGAN = false;
    public static final int COUNTED_YAKUMAN_MIN_HAN = 13;
    public static final int COUNTED_YAKUMAN_UNITS = 1;
    public static final int SPECIAL_YAKUMAN_UNITS = 2;
    public static final int YAKUMAN_BASE_POINTS = 8_000;

    private FourPlayerRules() {
    }
}
