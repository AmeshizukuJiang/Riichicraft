package dev.riichicraft.rules;

import java.util.Objects;

/** One decomposition together with the concealed group completed by the actual winning tile. */
public record WinningInterpretation(HandDecomposition decomposition, int groupIndex, Wait waitType) {
    public enum Wait { PAIR, RYANMEN, KANCHAN, PENCHAN, SHANPON, SPECIAL }

    public WinningInterpretation {
        Objects.requireNonNull(decomposition, "decomposition");
        Objects.requireNonNull(waitType, "waitType");
        if (decomposition.form() != HandDecomposition.Form.STANDARD) {
            Wait required = decomposition.form() == HandDecomposition.Form.SEVEN_PAIRS ? Wait.PAIR : Wait.SPECIAL;
            if (groupIndex != -1 || waitType != required) {
                throw new IllegalArgumentException("Invalid special-hand interpretation");
            }
        } else if (waitType == Wait.PAIR) {
            if (groupIndex != -1) throw new IllegalArgumentException("A pair wait has no completed group");
        } else {
            if (groupIndex < 0 || groupIndex >= decomposition.groups().size()) {
                throw new IllegalArgumentException("Invalid completed group index");
            }
            HandDecomposition.Group group = decomposition.groups().get(groupIndex);
            if (group.open() || group.kind() == HandDecomposition.Kind.QUAD
                    || group.kind() == HandDecomposition.Kind.TRIPLET && waitType != Wait.SHANPON
                    || group.kind() == HandDecomposition.Kind.SEQUENCE
                    && waitType != Wait.RYANMEN && waitType != Wait.KANCHAN && waitType != Wait.PENCHAN) {
                throw new IllegalArgumentException("Winning tile must complete an undeclared concealed group");
            }
        }
    }
}
