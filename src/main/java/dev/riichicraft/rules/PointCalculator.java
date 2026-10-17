package dev.riichicraft.rules;

import java.util.Objects;

/** Four-player basic payments, excluding honba, deposits, multiple winners, and responsibility. */
public final class PointCalculator {
    public enum Limit {
        NONE,
        MANGAN,
        HANEMAN,
        BAIMAN,
        SANBAIMAN,
        YAKUMAN,
        KAZOE_YAKUMAN
    }

    /** Hand value before deciding how many players pay. Shared by the two fixed presets. */
    public record BasicValue(int basePoints, Limit limit) {
        public BasicValue {
            if (basePoints <= 0) throw new IllegalArgumentException("Basic points must be positive");
            Objects.requireNonNull(limit, "limit");
        }
    }

    /** A tsumo winner receives fromDealer once and fromNonDealer twice (three times if dealer). */
    public record Payment(int ronPoints, int fromDealer, int fromNonDealer, int totalPoints) {
        public Payment {
            if (ronPoints < 0 || fromDealer < 0 || fromNonDealer < 0 || totalPoints < 0) {
                throw new IllegalArgumentException("Payments cannot be negative");
            }
        }
    }

    public record Points(int basePoints, Limit limit, Payment payment) {
        public Points {
            if (basePoints <= 0) {
                throw new IllegalArgumentException("Basic points must be positive");
            }
            Objects.requireNonNull(limit, "limit");
            Objects.requireNonNull(payment, "payment");
        }
    }

    // Ordinary preset: no kiriage; 13+ han counts as one counted yakuman.
    private static final int MANGAN_BASE = 2_000;
    private static final int YAKUMAN_BASE = FourPlayerRules.YAKUMAN_BASE_POINTS;

    private PointCalculator() {
    }

    /**
     * Calculates an already established legal win. Han contains yaku plus permitted bonuses;
     * the caller must enforce the one-yaku minimum. True yakuman ignores ordinary han and fu.
     * Any result outside the int-based score representation is rejected rather than wrapped.
     */
    public static Points calculate(int han, int fu, int yakumanUnits, boolean dealer, boolean tsumo) {
        BasicValue value = basicValue(han, fu, yakumanUnits);
        long base = value.basePoints();
        long ron = 0;
        long fromDealer = 0;
        long fromNonDealer = 0;
        long total;
        if (!tsumo) {
            ron = roundHundred(base * (dealer ? 6L : 4L));
            total = ron;
        } else if (dealer) {
            fromNonDealer = roundHundred(base * 2L);
            total = fromNonDealer * 3L;
        } else {
            fromDealer = roundHundred(base * 2L);
            fromNonDealer = roundHundred(base);
            total = fromDealer + fromNonDealer * 2L;
        }
        return new Points(value.basePoints(), value.limit(),
                new Payment(asInt(ron), asInt(fromDealer), asInt(fromNonDealer), asInt(total)));
    }

    /**
     * The common, confirmed no-kiriage / counted-single-yakuman value policy. This does not
     * calculate a four-player bill, so a valid three-player bill cannot overflow an unused payer.
     */
    public static BasicValue basicValue(int han, int fu, int yakumanUnits) {
        if (han < 0 || fu < 0 || yakumanUnits < 0) {
            throw new IllegalArgumentException("Han, fu, and yakuman units cannot be negative");
        }

        long base;
        Limit limit;
        if (yakumanUnits > 0) {
            base = (long) YAKUMAN_BASE * yakumanUnits;
            limit = Limit.YAKUMAN;
        } else {
            if (han == 0) {
                throw new IllegalArgumentException("An ordinary win needs at least one han");
            }
            if (fu < 20 || (fu != 25 && fu % 10 != 0)) {
                throw new IllegalArgumentException("Ordinary fu must be 20, 25, or a multiple of ten above 20");
            }
            if (han >= FourPlayerRules.COUNTED_YAKUMAN_MIN_HAN) {
                base = (long) YAKUMAN_BASE * FourPlayerRules.COUNTED_YAKUMAN_UNITS;
                limit = Limit.KAZOE_YAKUMAN;
            } else if (han >= 11) {
                base = 6_000;
                limit = Limit.SANBAIMAN;
            } else if (han >= 8) {
                base = 4_000;
                limit = Limit.BAIMAN;
            } else if (han >= 6) {
                base = 3_000;
                limit = Limit.HANEMAN;
            } else {
                long uncapped = (long) fu * (1L << (han + 2));
                base = han == 5 ? MANGAN_BASE : Math.min(MANGAN_BASE, uncapped);
                if (FourPlayerRules.KIRIAGE_MANGAN && base == 1_920) base = MANGAN_BASE;
                limit = base == MANGAN_BASE ? Limit.MANGAN : Limit.NONE;
            }
        }

        return new BasicValue(asInt(base), limit);
    }

    private static long roundHundred(long points) {
        return ((points + 99) / 100) * 100;
    }

    private static int asInt(long value) {
        if (value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Payment exceeds the supported integer score range");
        }
        return (int) value;
    }
}
