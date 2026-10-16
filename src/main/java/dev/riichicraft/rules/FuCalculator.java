package dev.riichicraft.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Fu for one valid winning interpretation; yaku and payments are evaluated separately. */
public final class FuCalculator {
    public enum Source {
        BASE,
        MENZEN_RON,
        TSUMO,
        DRAGON_PAIR,
        SEAT_WIND_PAIR,
        ROUND_WIND_PAIR,
        OPEN_TRIPLET,
        CLOSED_TRIPLET,
        OPEN_QUAD,
        CLOSED_QUAD,
        PAIR_WAIT,
        CLOSED_WAIT,
        EDGE_WAIT,
        OPEN_RON_MINIMUM,
        SEVEN_PAIRS
    }

    public record FuComponent(Source source, int fu) {
        public FuComponent {
            Objects.requireNonNull(source, "source");
            if (fu <= 0) {
                throw new IllegalArgumentException("A fu component must be positive");
            }
        }
    }

    /** rawFu includes special minimum adjustments, before rounding up to the next ten. */
    public record FuResult(int rawFu, int fu, List<FuComponent> components) {
        public FuResult {
            components = List.copyOf(Objects.requireNonNull(components, "components"));
            if (rawFu < 0 || fu < rawFu || components.stream().mapToInt(FuComponent::fu).sum() != rawFu) {
                throw new IllegalArgumentException("Inconsistent fu breakdown");
            }
        }
    }

    private FuCalculator() {
    }

    /**
     * Seven pairs is exactly 25 fu. Thirteen orphans has no fu calculation and returns zero.
     * Other yakuman can be skipped by the scorer, since their payments do not depend on fu.
     */
    public static FuResult calculate(HandInput hand, WinContext context,
                                     WinningInterpretation interpretation) {
        WinInterpreter.requireValid(hand, context, interpretation);
        HandDecomposition decomposition = interpretation.decomposition();
        if (decomposition.form() == HandDecomposition.Form.THIRTEEN_ORPHANS) {
            return new FuResult(0, 0, List.of());
        }
        if (decomposition.form() == HandDecomposition.Form.SEVEN_PAIRS) {
            return new FuResult(25, 25, List.of(new FuComponent(Source.SEVEN_PAIRS, 25)));
        }

        List<FuComponent> components = new ArrayList<>();
        add(components, Source.BASE, 20);
        boolean pinfu = isPinfuShape(hand, context, interpretation);
        if (!context.tsumo() && hand.isClosed()) {
            add(components, Source.MENZEN_RON, 10);
        }
        if (context.tsumo() && !pinfu) {
            add(components, Source.TSUMO, 2);
        }

        int pairType = decomposition.pairType();
        if (pairType >= 31) {
            add(components, Source.DRAGON_PAIR, 2);
        }
        if (pairType == context.seatWind()) {
            add(components, Source.SEAT_WIND_PAIR, 2);
        }
        if (pairType == context.roundWind()) {
            add(components, Source.ROUND_WIND_PAIR, 2);
        }

        List<HandDecomposition.Group> groups = decomposition.groups();
        for (int index = 0; index < groups.size(); index++) {
            HandDecomposition.Group group = groups.get(index);
            if (group.kind() == HandDecomposition.Kind.SEQUENCE) {
                continue;
            }
            // Ron completes this triplet from another player's tile; it does not break menzen.
            boolean ronCompletedTriplet = !context.tsumo() && interpretation.groupIndex() == index
                    && group.kind() == HandDecomposition.Kind.TRIPLET;
            boolean open = group.open() || ronCompletedTriplet;
            boolean quad = group.kind() == HandDecomposition.Kind.QUAD;
            int groupFu = quad ? (open ? 8 : 16) : (open ? 2 : 4);
            if (HandDecomposition.isOrphan(group.tileType())) {
                groupFu *= 2;
            }
            Source source = quad ? (open ? Source.OPEN_QUAD : Source.CLOSED_QUAD)
                    : (open ? Source.OPEN_TRIPLET : Source.CLOSED_TRIPLET);
            add(components, source, groupFu);
        }

        switch (interpretation.waitType()) {
            case PAIR -> add(components, Source.PAIR_WAIT, 2);
            case KANCHAN -> add(components, Source.CLOSED_WAIT, 2);
            case PENCHAN -> add(components, Source.EDGE_WAIT, 2);
            default -> { }
        }

        int rawFu = components.stream().mapToInt(FuComponent::fu).sum();
        if (!context.tsumo() && !hand.isClosed() && rawFu == 20) {
            add(components, Source.OPEN_RON_MINIMUM, 10);
            rawFu = 30;
        }
        return new FuResult(rawFu, ((rawFu + 9) / 10) * 10, components);
    }

    /** Pinfu requires a closed standard hand, four sequences, a valueless pair, and ryanmen. */
    public static boolean isPinfu(HandInput hand, WinContext context,
                                   WinningInterpretation interpretation) {
        WinInterpreter.requireValid(hand, context, interpretation);
        return isPinfuShape(hand, context, interpretation);
    }

    private static boolean isPinfuShape(HandInput hand, WinContext context,
                                        WinningInterpretation interpretation) {
        HandDecomposition decomposition = interpretation.decomposition();
        if (!hand.isClosed() || decomposition.form() != HandDecomposition.Form.STANDARD
                || interpretation.waitType() != WinningInterpretation.Wait.RYANMEN
                || decomposition.pairType() >= 31
                || decomposition.pairType() == context.seatWind()
                || decomposition.pairType() == context.roundWind()) {
            return false;
        }
        return decomposition.groups().stream()
                .allMatch(group -> group.kind() == HandDecomposition.Kind.SEQUENCE);
    }

    private static void add(List<FuComponent> components, Source source, int fu) {
        components.add(new FuComponent(source, fu));
    }
}
