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
        TurnActionTests.main(args);
        RiichiTests.main(args);
        LedgerTests.main(args);
        KanActionTests.main(args);
        KanResponseTests.main(args);
        KanRoundTests.main(args);
        FourPlayerRoundTests.main(args);
        RoundDrawTests.main(args);
        RoundScenarioTests.main(args);
        MatchRulesTests.main(args);
        FourPlayerMatchTests.main(args);
        TableArchiveStoreTests.main(args);
        ArchiveTests.main(args);
        ThreePlayerBasicsTests.main(args);
    }
}
