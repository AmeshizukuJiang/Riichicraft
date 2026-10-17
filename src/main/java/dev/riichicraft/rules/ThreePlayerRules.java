package dev.riichicraft.rules;

/** Confirmed fixed three-player preset, separate from four-player games and their archives. */
public final class ThreePlayerRules {
    public static final String ID = "riichicraft:three_player_v1";
    public static final int REVISION = 1;
    public static final int SEAT_COUNT = 3;
    public static final int TILE_COUNT = 108;
    public static final int TYPE_COUNT = 27;
    public static final int STARTING_POINTS = 35_000;
    public static final int RETURN_POINTS = 35_000;
    public static final int TARGET_POINTS = 40_000;

    private ThreePlayerRules() { }
}
