package dev.riichicraft.rules;

import java.util.Objects;

/** Three-player tsumo-loss basic bills; excludes honba, deposits, liability and multiple winners. */
public final class ThreePlayerPointCalculator {
    /** A child receives each nonzero payer amount once; a dealer receives fromNonDealer twice. */
    public record Payment(int ronPoints, int fromDealer, int fromNonDealer, int totalPoints) {
        public Payment {
            if (ronPoints < 0 || fromDealer < 0 || fromNonDealer < 0 || totalPoints < 0) {
                throw new IllegalArgumentException("Payments cannot be negative");
            }
        }
    }

    public record Points(int basePoints, PointCalculator.Limit limit, Payment payment) {
        public Points {
            if (basePoints <= 0) throw new IllegalArgumentException("Basic points must be positive");
            Objects.requireNonNull(limit, "limit");
            Objects.requireNonNull(payment, "payment");
        }
    }

    private ThreePlayerPointCalculator() { }

    /**
     * Caller must first establish a legal win with a yaku. Each actual payer is rounded to
     * hundreds independently; no phantom north payment is computed, split or removed later.
     */
    public static Points calculate(int han, int fu, int yakumanUnits, boolean dealer, boolean tsumo) {
        PointCalculator.BasicValue value = PointCalculator.basicValue(han, fu, yakumanUnits);
        long base = value.basePoints();
        long ron = 0;
        long fromDealer = 0;
        long fromNonDealer = 0;
        long total;
        if (!tsumo) {
            ron = hundred(base * (dealer ? 6L : 4L));
            total = ron;
        } else if (dealer) {
            fromNonDealer = hundred(base * 2L);
            total = fromNonDealer * 2L;
        } else {
            fromDealer = hundred(base * 2L);
            fromNonDealer = hundred(base);
            total = fromDealer + fromNonDealer;
        }
        return new Points(value.basePoints(), value.limit(),
                new Payment(exactInt(ron), exactInt(fromDealer), exactInt(fromNonDealer), exactInt(total)));
    }

    private static long hundred(long points) { return ((points + 99) / 100) * 100; }

    private static int exactInt(long points) {
        if (points < 0 || points > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Payment exceeds the supported integer score range");
        }
        return (int) points;
    }
}
