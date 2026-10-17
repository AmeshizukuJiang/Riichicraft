package dev.riichicraft.rules;

import dev.riichicraft.game.TileCatalog;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Independent tile membership and fixed payment tables, without a game or a three-player round. */
public final class ThreePlayerBasicsTests {
    private static int assertions;

    private ThreePlayerBasicsTests() { }

    public static void main(String[] args) {
        long start = System.nanoTime();
        tilePool();
        indicators();
        fixedPayments();
        paymentMatrix();
        limitsAndBounds();
        System.out.printf("PASS three-player basics: %d assertions; %.3f s%n",
                assertions, (System.nanoTime() - start) / 1_000_000_000.0);
    }

    private static void tilePool() {
        List<Integer> expectedTypes = List.of(0, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17,
                18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33);
        List<Integer> expectedTiles = new ArrayList<>();
        for (int type : expectedTypes) for (int copy = 0; copy < 4; copy++) expectedTiles.add(type * 4 + copy);
        check(ThreePlayerTiles.allTypes().equals(expectedTypes), "the exact legal types exclude seven manzu ranks only");
        check(ThreePlayerTiles.allTiles().equals(expectedTiles), "each legal physical identity is present exactly once, without renumbering");
        check(new HashSet<>(ThreePlayerTiles.allTiles()).size() == ThreePlayerRules.TILE_COUNT,
                "the 108-tile preset has 108 different physical instances");
        Set<Integer> reds = new HashSet<>();
        for (int tile = 0; tile < 136; tile++) {
            boolean legal = expectedTiles.contains(tile);
            check(ThreePlayerTiles.contains(tile) == legal, "all shared IDs have the correct membership");
            if (legal) {
                check(ThreePlayerTiles.typeOf(tile) == tile / 4 && TileCatalog.typeOf(tile) == tile / 4,
                        "three-player identities preserve their four-player type and physical copy");
                if (ThreePlayerTiles.isRed(tile)) reds.add(tile);
            } else {
                final int removed = tile;
                invalid(() -> ThreePlayerTiles.typeOf(removed));
                invalid(() -> ThreePlayerTiles.isRed(removed));
                invalid(() -> ThreePlayerTiles.doraType(removed));
            }
        }
        check(reds.equals(Set.of(52, 88)), "one red pin and one red sou replace their ordinary copies; no red man");
        check(TileCatalog.allTiles().size() == 136 && TileCatalog.isRed(16), "the existing four-player catalog remains intact");
        for (int id : List.of(Integer.MIN_VALUE, -1, 136, Integer.MAX_VALUE)) {
            check(!ThreePlayerTiles.contains(id), "out-of-range membership is false");
            invalid(() -> ThreePlayerTiles.typeOf(id));
            invalid(() -> ThreePlayerTiles.doraType(id));
        }
        for (int type = -1; type <= 34; type++) {
            check(ThreePlayerTiles.containsType(type) == expectedTypes.contains(type), "type-domain holes cannot become waits");
        }
        immutable(() -> ThreePlayerTiles.allTiles().removeFirst());
        immutable(() -> ThreePlayerTiles.allTypes().set(0, 1));
    }

    private static void indicators() {
        int[] expected = {8, -1, -1, -1, -1, -1, -1, -1, 0,
                10, 11, 12, 13, 14, 15, 16, 17, 9,
                19, 20, 21, 22, 23, 24, 25, 26, 18,
                28, 29, 30, 27, 32, 33, 31};
        int[] hits = new int[34];
        for (int tile : ThreePlayerTiles.allTiles()) {
            int target = ThreePlayerTiles.doraType(tile);
            check(target == expected[tile / 4], "all four physical copies obey each independently listed indicator mapping");
            check(ThreePlayerTiles.containsType(target), "an indicator never names a removed manzu tile");
            hits[target]++;
        }
        for (int type : ThreePlayerTiles.allTypes()) check(hits[type] == 4, "each legal dora type has exactly one four-copy indicator kind");
        check(ThreePlayerTiles.doraType(29 * 4) == 30 && ThreePlayerTiles.doraType(30 * 4) == 27,
                "three seats do not shrink the four-wind indicator cycle");
        check(ThreePlayerTiles.doraType(52) == ThreePlayerTiles.doraType(53)
                        && ThreePlayerTiles.doraType(88) == ThreePlayerTiles.doraType(89),
                "red indicators have their ordinary tile kind's successor");
        check(HandScorer.doraType(0) == 1 && ThreePlayerTiles.doraType(0) == 8,
                "the three-player 1m exception cannot silently alter four-player scoring");
    }

    private static void fixedPayments() {
        // Arithmetic inputs, not a claim that every fu/han/method combination is a legal hand.
        // han, fu, child ron, dealer ron, child's payment from the other child, payment from dealer.
        int[][] rows = {
                {1,30,1000,1500,300,500}, {1,40,1300,2000,400,700}, {1,50,1600,2400,400,800},
                {1,60,2000,2900,500,1000}, {2,25,1600,2400,400,800}, {2,30,2000,2900,500,1000},
                {2,40,2600,3900,700,1300}, {2,50,3200,4800,800,1600}, {2,60,3900,5800,1000,2000},
                {3,20,2600,3900,700,1300}, {3,25,3200,4800,800,1600}, {3,30,3900,5800,1000,2000},
                {3,40,5200,7700,1300,2600}, {3,50,6400,9600,1600,3200}, {3,60,7700,11600,2000,3900},
                {3,70,8000,12000,2000,4000}, {4,20,5200,7700,1300,2600}, {4,25,6400,9600,1600,3200},
                {4,30,7700,11600,2000,3900}, {4,40,8000,12000,2000,4000}, {5,30,8000,12000,2000,4000},
                {6,30,12000,18000,3000,6000}, {7,30,12000,18000,3000,6000},
                {8,30,16000,24000,4000,8000}, {10,30,16000,24000,4000,8000},
                {11,30,24000,36000,6000,12000}, {12,30,24000,36000,6000,12000},
                {13,30,32000,48000,8000,16000}, {26,30,32000,48000,8000,16000}
        };
        for (int[] row : rows) {
            check(three(row[0], row[1], false, false).equals(new ThreePlayerPointCalculator.Payment(row[2], 0, 0, row[2])), "fixed child ron table");
            check(three(row[0], row[1], true, false).equals(new ThreePlayerPointCalculator.Payment(row[3], 0, 0, row[3])), "fixed dealer ron table");
            check(three(row[0], row[1], false, true).equals(new ThreePlayerPointCalculator.Payment(0, row[5], row[4], row[4] + row[5])), "fixed child two-payer tsumo table");
            check(three(row[0], row[1], true, true).equals(new ThreePlayerPointCalculator.Payment(0, 0, row[5], row[5] * 2)), "fixed dealer two-payer tsumo table");
        }
        check(three(1, 30, false, true).totalPoints() == 800,
                "300+500 rounds by payer; multiplying four-player 1100 by 3/4 would be wrong");
        check(ThreePlayerPointCalculator.calculate(4,30,0,false,true).basePoints() == 1920
                        && ThreePlayerPointCalculator.calculate(3,60,0,true,true).limit() == PointCalculator.Limit.NONE,
                "both no-kiriage edges retain 1920 base rather than mangan");
    }

    private static void paymentMatrix() {
        List<Integer> fuValues = new ArrayList<>(List.of(25));
        for (int fu = 20; fu <= 150; fu += 10) fuValues.add(fu);
        for (int han = 1; han <= 30; han++) for (int fu : fuValues) {
            int base = referenceBase(han, fu);
            for (boolean dealer : List.of(false, true)) for (boolean tsumo : List.of(false, true)) {
                var actual = ThreePlayerPointCalculator.calculate(han, fu, 0, dealer, tsumo);
                int child = nextHundred(base), parent = nextHundred(base * 2);
                int ron = nextHundred(base * (dealer ? 6 : 4));
                var expected = !tsumo ? new ThreePlayerPointCalculator.Payment(ron,0,0,ron)
                        : dealer ? new ThreePlayerPointCalculator.Payment(0,0,parent,parent*2)
                        : new ThreePlayerPointCalculator.Payment(0,parent,child,parent+child);
                check(actual.basePoints() == base && actual.payment().equals(expected), "complete han/fu/role/method payment matrix");
                var four = PointCalculator.calculate(han,fu,0,dealer,tsumo);
                check(actual.limit() == four.limit() && actual.basePoints() == four.basePoints(), "presets share hand-value caps, not payer counts");
                check(four.payment().totalPoints() - actual.payment().totalPoints() == (tsumo ? actual.payment().fromNonDealer() : 0),
                        "only the missing non-dealer payment disappears, with no point redistribution");
            }
        }
        for (int units = 1; units <= 8; units++) {
            for (boolean dealer : List.of(false,true)) for (boolean tsumo : List.of(false,true)) {
                var actual = ThreePlayerPointCalculator.calculate(0,0,units,dealer,tsumo);
                int expected = units * (tsumo ? dealer ? 32_000 : 24_000 : dealer ? 48_000 : 32_000);
                check(actual.payment().totalPoints() == expected && actual.limit() == PointCalculator.Limit.YAKUMAN,
                        "true yakuman units preserve two-payer tsumo loss and full ron values");
            }
        }
    }

    private static void limitsAndBounds() {
        var kazoe = ThreePlayerPointCalculator.calculate(Integer.MAX_VALUE,30,0,false,true);
        check(kazoe.basePoints() == 8000 && kazoe.payment().totalPoints() == 24_000
                        && kazoe.limit() == PointCalculator.Limit.KAZOE_YAKUMAN,
                "arbitrarily high ordinary han remains a single counted yakuman without shifting overflow");
        int dealerMax = Integer.MAX_VALUE / 32_000;
        int childMax = Integer.MAX_VALUE / 24_000;
        var dealer = ThreePlayerPointCalculator.calculate(0,0,dealerMax,true,true);
        var child = ThreePlayerPointCalculator.calculate(0,0,childMax,false,true);
        check(dealer.payment().totalPoints() == dealerMax * 32_000L, "largest representable dealer tsumo is valid");
        check(child.payment().totalPoints() == childMax * 24_000L, "largest representable child tsumo is valid");
        invalid(() -> PointCalculator.calculate(0,0,dealerMax,true,true));
        invalid(() -> PointCalculator.calculate(0,0,childMax,false,true));
        invalid(() -> ThreePlayerPointCalculator.calculate(0,0,dealerMax+1,true,true));
        invalid(() -> ThreePlayerPointCalculator.calculate(0,0,childMax+1,false,true));
        for (boolean isDealer : List.of(false,true)) {
            int max = Integer.MAX_VALUE / (isDealer ? 48_000 : 32_000);
            check(ThreePlayerPointCalculator.calculate(0,0,max,isDealer,false).payment().ronPoints()
                    == max * (isDealer ? 48_000L : 32_000L), "ron integer boundary is checked against the actual bill");
            invalid(() -> ThreePlayerPointCalculator.calculate(0,0,max+1,isDealer,false));
        }
        for (int[] invalid : new int[][]{{0,30,0},{-1,30,0},{1,-1,0},{1,30,-1},{1,0,0},{1,19,0},{1,22,0},{1,26,0},{0,0,Integer.MAX_VALUE}}) {
            invalid(() -> ThreePlayerPointCalculator.calculate(invalid[0],invalid[1],invalid[2],false,true));
            invalid(() -> PointCalculator.basicValue(invalid[0],invalid[1],invalid[2]));
        }
    }

    private static ThreePlayerPointCalculator.Payment three(int han, int fu, boolean dealer, boolean tsumo) {
        return ThreePlayerPointCalculator.calculate(han,fu,0,dealer,tsumo).payment();
    }

    private static int referenceBase(int han, int fu) {
        if (han >= 13) return 8000;
        if (han >= 11) return 6000;
        if (han >= 8) return 4000;
        if (han >= 6) return 3000;
        if (han == 5) return 2000;
        return BigInteger.valueOf(fu).shiftLeft(han+2).min(BigInteger.valueOf(2000)).intValueExact();
    }

    private static int nextHundred(int value) { while (value % 100 != 0) value++; return value; }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static void invalid(Runnable action) {
        assertions++;
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected invalid tile or score to be rejected");
    }

    private static void immutable(Runnable action) {
        assertions++;
        try { action.run(); } catch (UnsupportedOperationException expected) { return; }
        throw new AssertionError("Expected immutable preset tiles");
    }
}
