package dev.riichicraft.rules;

/** Runs every standalone rules suite without starting a game or loading game libraries. */
public final class RulesTests {
    private RulesTests() {
    }

    public static void main(String[] args) {
        HandAnalyzerTests.main(args);
        ScoringTests.main(args);
        FuritenTests.main(args);
        DiscardFuritenTests.main(args);
        DiscardResponseTests.main(args);
        CallActionsTests.main(args);
        RoundWallTests.main(args);
    }
}
