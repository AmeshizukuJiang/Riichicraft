package dev.riichicraft.rules;

import dev.riichicraft.game.TileCatalog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

/** Independent, dependency-free checks for hand structure; no Minecraft runtime is used. */
public final class HandAnalyzerTests {
    private static final int[] ORPHANS = {0, 8, 9, 17, 18, 26, 27, 28, 29, 30, 31, 32, 33};
    private static final List<Set<Integer>> SUIT_STATES = buildSuitStates();
    private static int assertions;
    private static int crossCheckedHands;
    private static int crossCheckedWaits;
    private static int exhaustiveSuitStates;
    private static int exhaustiveWinningSuitStates;

    private HandAnalyzerTests() {
    }

    public static void main(String[] args) {
        long started = System.nanoTime();
        run("known complete and incomplete hands", HandAnalyzerTests::knownHands);
        run("multiple decompositions and representation", HandAnalyzerTests::decompositions);
        run("waits and physical four-copy limits", HandAnalyzerTests::knownWaits);
        run("declared melds and closed kans", HandAnalyzerTests::declaredMelds);
        run("invalid input and defensive copies", HandAnalyzerTests::inputValidation);
        run("independent suit-state reference and symmetries", HandAnalyzerTests::crossChecks);
        run("exhaustive one-suit complete decomposition sets", HandAnalyzerTests::exhaustiveOneSuit);
        double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
        System.out.printf("PASS: %d assertions; %d complete-hand reference comparisons; "
                + "%d wait reference comparisons; %d exhaustive one-suit states (%d winning); %.3f s%n",
                assertions, crossCheckedHands, crossCheckedWaits, exhaustiveSuitStates, exhaustiveWinningSuitStates, seconds);
    }

    private static void run(String label, Runnable test) {
        int before = assertions;
        test.run();
        System.out.printf("  PASS %s (%d assertions)%n", label, assertions - before);
    }

    private static void knownHands() {
        HandInput standard = hand("123m123p123s11122z");
        check(hasForm(standard, HandDecomposition.Form.STANDARD), "ordinary standard hand");
        check(!hasForm(standard, HandDecomposition.Form.SEVEN_PAIRS), "ordinary hand is not seven pairs");
        check(!hasForm(standard, HandDecomposition.Form.THIRTEEN_ORPHANS), "ordinary hand is not kokushi");
        check(hasForm(hand("1122m3344p5566s77z"), HandDecomposition.Form.SEVEN_PAIRS), "seven distinct pairs");
        check(hasForm(hand("119m19p19s1234567z"), HandDecomposition.Form.THIRTEEN_ORPHANS), "thirteen orphans");
        check(hasForm(hand("111m999p111s22233z"), HandDecomposition.Form.STANDARD), "honor and terminal triplets");
        check(hasForm(hand("789m789p789s77766z"), HandDecomposition.Form.STANDARD), "last legal rank and honor boundaries");
        check(HandAnalyzer.analyze(hand("123m123p123s11223z")).isEmpty(), "unmatched honor cannot be a sequence");
        check(HandAnalyzer.analyze(hand("123m123p123s12344z")).isEmpty(), "three adjacent honors are not a sequence");
        check(HandAnalyzer.analyze(hand("89m1123p123s11122z")).isEmpty(), "sequence cannot cross suit boundary");
        check(HandAnalyzer.analyze(hand("123m123p123s1112z")).isEmpty(), "thirteen tiles cannot already be complete");
        HandInput quadPairs = hand("11112233445566m");
        check(!hasForm(quadPairs, HandDecomposition.Form.SEVEN_PAIRS), "four equal tiles do not count as two pairs");
        check(hasForm(quadPairs, HandDecomposition.Form.STANDARD), "four-copy seven-pairs counterexample can still be standard");
        HandInput red = hand("123456789m11122z");
        check(red.concealedTiles().contains(TileCatalog.RED_MAN_FIVE), "red-five physical ID represented");
        check(hasForm(red, HandDecomposition.Form.STANDARD), "red five has ordinary five's structural type");
        for (int tileId : red.concealedTiles()) {
            check(TileCatalog.typeOf(tileId) == tileId / 4, "catalog physical-to-type mapping");
        }
        verifyDecompositions(standard);
    }

    private static void decompositions() {
        HandInput ambiguous = hand("111222333m456p55s");
        List<HandDecomposition> split = HandAnalyzer.analyze(ambiguous);
        check(split.size() == 2, "111222333 has both triplets and three identical sequences");
        check(split.stream().anyMatch(d -> countKind(d, HandDecomposition.Kind.TRIPLET) == 3), "triplet interpretation retained");
        check(split.stream().anyMatch(d -> countKind(d, HandDecomposition.Kind.SEQUENCE) == 4), "sequence interpretation retained");
        verifyDecompositions(ambiguous);

        HandInput pairAmbiguous = hand("11223344556677m");
        List<HandDecomposition> choices = HandAnalyzer.analyze(pairAmbiguous);
        check(choices.size() == 4, "three standard pair choices plus seven pairs are all returned");
        Set<Integer> pairs = new TreeSet<>();
        for (HandDecomposition decomposition : choices) {
            if (decomposition.form() == HandDecomposition.Form.STANDARD) {
                pairs.add(decomposition.pairType());
            }
        }
        check(pairs.equals(Set.of(0, 3, 6)), "all and only 1m/4m/7m pair choices");
        verifyDecompositions(pairAmbiguous);

        List<Integer> reversed = new ArrayList<>(ambiguous.concealedTiles());
        java.util.Collections.reverse(reversed);
        check(signatures(split).equals(signatures(HandAnalyzer.analyze(new HandInput(reversed, List.of())))),
                "physical input order does not affect decompositions");
        HandDecomposition seven = HandAnalyzer.analyze(hand("1122m3344p5566s77z")).stream()
                .filter(d -> d.form() == HandDecomposition.Form.SEVEN_PAIRS).findFirst().orElseThrow();
        check(seven.pairType() == -1 && seven.groups().isEmpty(), "seven-pairs fields have unambiguous meaning");
        check(seven.sevenPairTypes().equals(List.of(0, 1, 11, 12, 22, 23, 33)), "seven-pairs type list is complete");
        HandDecomposition kokushi = HandAnalyzer.analyze(hand("119m19p19s1234567z")).getFirst();
        check(kokushi.pairType() == 0 && kokushi.groups().isEmpty() && kokushi.sevenPairTypes().isEmpty(),
                "kokushi duplicate type is retained separately from ordinary groups");
    }

    private static void knownWaits() {
        check(HandAnalyzer.waits(hand("123m123p123s1112z")).equals(Set.of(28)), "honor tanki");
        check(HandAnalyzer.waits(hand("123m123p123s1122z")).equals(Set.of(27, 28)), "honor shanpon");
        check(HandAnalyzer.waits(hand("123m123p123s11z23m")).equals(Set.of(0, 3)), "two-sided 23m waits on 1m/4m");
        check(HandAnalyzer.waits(hand("123m123p123s11z12m")).equals(Set.of(2)), "edge 12m waits only on 3m");
        check(HandAnalyzer.waits(hand("123m123p123s11z13m")).equals(Set.of(1)), "closed 13m waits only on 2m");
        check(HandAnalyzer.waits(hand("1122m3344p5566s7z")).equals(Set.of(33)), "seven pairs waits on seventh pair");
        check(HandAnalyzer.waits(hand("19m19p19s1234567z")).equals(asSet(ORPHANS)), "kokushi thirteen-sided wait");
        check(HandAnalyzer.waits(hand("119m19p19s123456z")).equals(Set.of(33)), "kokushi single missing type");
        check(HandAnalyzer.waits(hand("1112345678999m")).equals(Set.of(0, 1, 2, 3, 4, 5, 6, 7, 8)), "nine-gates nine-sided wait");
        for (int type = 0; type < 34; type++) {
            int[] counts = new int[34];
            counts[type] = 1;
            int triplets = 0;
            for (int honor = 27; honor < 34 && triplets < 4; honor++) {
                if (honor != type) {
                    counts[honor] = 3;
                    triplets++;
                }
            }
            HandInput tanki = fromCounts(counts);
            check(HandAnalyzer.waits(tanki).equals(Set.of(type)), "all 34 tile types support an isolated tanki: " + type);
            check(hasForm(appendAvailable(tanki, type), HandDecomposition.Form.STANDARD), "all 34 tile types can complete a pair: " + type);
        }
        HandInput fourCopies = hand("1s123m123p111z", spec(Meld.Kind.PON, "111s"));
        Set<Integer> waits = HandAnalyzer.waits(fourCopies);
        check(waits.isEmpty(), "declared copies count against the legal remaining physical supply");
        check(!waits.contains(18), "cannot return a structurally tempting fifth physical copy");

        // An abstract fifth 1m would make 111m + 11m alongside three honor
        // triplets, but no fifth copy exists in the 136-tile set.
        HandInput exhausted = hand("1111m111222333z");
        check(HandAnalyzer.waits(exhausted).isEmpty(), "four-copy cap removes a false five-copy wait");
        for (int type = 0; type < 34; type++) {
            check(!HandAnalyzer.waits(exhausted).contains(type) || countAll(exhausted)[type] < 4,
                    "every reported wait has a physically available copy");
        }
        expectIllegal(() -> HandAnalyzer.waits(hand("123m123p123s11122z")), "waits requires thirteen structural tiles");
    }

    private static void declaredMelds() {
        HandInput chi = hand("456m789m123p55s", spec(Meld.Kind.CHI, "321m"));
        check(!chi.isClosed() && chi.isComplete(), "chi opens an otherwise complete hand");
        check(hasForm(chi, HandDecomposition.Form.STANDARD), "declared chi contributes one fixed group");
        check(HandAnalyzer.analyze(chi).stream().allMatch(d -> d.groups().contains(
                new HandDecomposition.Group(HandDecomposition.Kind.SEQUENCE, 0, true))), "chi group is marked open");
        verifyDecompositions(chi);

        HandInput openKan = hand("123m123p123s22z", spec(Meld.Kind.OPEN_KAN, "1111z"));
        HandInput closedKan = hand("123m123p123s22z", spec(Meld.Kind.CLOSED_KAN, "1111z"));
        check(!openKan.isClosed() && closedKan.isClosed(), "closed kan preserves closed-hand status");
        check(openKan.isComplete() && closedKan.isComplete(), "kan contributes one group despite its fourth tile");
        check(openKan.concealedTiles().size() + openKan.melds().getFirst().tiles().size() == 15,
                "one-kan complete hand contains fifteen physical tiles");
        check(HandAnalyzer.analyze(openKan).getFirst().groups().contains(
                new HandDecomposition.Group(HandDecomposition.Kind.QUAD, 27, true)), "open kan flag retained");
        check(HandAnalyzer.analyze(closedKan).getFirst().groups().contains(
                new HandDecomposition.Group(HandDecomposition.Kind.QUAD, 27, false)), "closed kan flag retained");
        check(HandAnalyzer.analyze(closedKan).stream().noneMatch(d -> d.form() != HandDecomposition.Form.STANDARD),
                "closed kan does not permit a special form");
        verifyDecompositions(openKan);
        verifyDecompositions(closedKan);

        HandInput fourKans = hand("55z", spec(Meld.Kind.CLOSED_KAN, "1111z"), spec(Meld.Kind.CLOSED_KAN, "2222z"),
                spec(Meld.Kind.CLOSED_KAN, "3333z"), spec(Meld.Kind.CLOSED_KAN, "4444z"));
        check(fourKans.isClosed() && fourKans.isComplete(), "four closed kans have eighteen physical tiles");
        check(HandAnalyzer.analyze(fourKans).getFirst().groups().size() == 4, "four declared groups still require only one pair");
        verifyDecompositions(fourKans);

        HandInput fourMeldWait = hand("5m", spec(Meld.Kind.CHI, "123m"), spec(Meld.Kind.PON, "111z"),
                spec(Meld.Kind.OPEN_KAN, "2222z"), spec(Meld.Kind.CLOSED_KAN, "3333z"));
        check(fourMeldWait.isWaiting() && !fourMeldWait.isComplete(), "four-meld tanki has one concealed tile");
        check(HandAnalyzer.waits(fourMeldWait).equals(Set.of(4)), "four-meld tanki waits on the concealed tile's type");
        HandInput fourMeldComplete = appendAvailable(fourMeldWait, 4);
        check(hasForm(fourMeldComplete, HandDecomposition.Form.STANDARD), "four-meld tanki completes with its second tile");
        verifyDecompositions(fourMeldComplete);
        HandInput kanWait = hand("123m123p123s2z", spec(Meld.Kind.CLOSED_KAN, "1111z"));
        check(kanWait.isWaiting() && HandAnalyzer.waits(kanWait).equals(Set.of(28)), "closed-kan thirteen structural tiles wait normally");
    }

    private static void inputValidation() {
        List<Integer> fourteen = new ArrayList<>(hand("123m123p123s11122z").concealedTiles());
        List<Integer> duplicate = new ArrayList<>(fourteen);
        duplicate.set(1, duplicate.getFirst());
        expectIllegal(() -> new HandInput(duplicate, List.of()), "duplicate physical tile rejected");
        List<Integer> low = new ArrayList<>(fourteen);
        low.set(0, -1);
        expectIllegal(() -> new HandInput(low, List.of()), "negative physical ID rejected");
        List<Integer> high = new ArrayList<>(fourteen);
        high.set(0, 136);
        expectIllegal(() -> new HandInput(high, List.of()), "physical ID 136 rejected");
        expectIllegal(() -> new HandInput(fourteen.subList(0, 12), List.of()), "twelve structural tiles rejected");
        expectIllegal(() -> new HandInput(new ArrayList<>(java.util.stream.IntStream.range(0, 15).boxed().toList()), List.of()),
                "fifteen structural tiles without a kan rejected");
        expectIllegal(() -> new HandInput(List.of(), List.of()), "empty hand rejected");
        expectIllegal(() -> new Meld(Meld.Kind.CHI, List.of(0, 4)), "short chi rejected");
        expectIllegal(() -> new Meld(Meld.Kind.CHI, List.of(28, 32, 36)), "chi cannot cross m/p boundary");
        expectIllegal(() -> new Meld(Meld.Kind.CHI, List.of(108, 112, 116)), "honors cannot form chi");
        expectIllegal(() -> new Meld(Meld.Kind.CHI, List.of(0, 4, 12)), "gapped chi rejected");
        expectIllegal(() -> new Meld(Meld.Kind.PON, List.of(0, 1, 4)), "mixed-type pon rejected");
        expectIllegal(() -> new Meld(Meld.Kind.PON, List.of(0, 0, 1)), "duplicate physical tile in pon rejected");
        expectIllegal(() -> new Meld(Meld.Kind.OPEN_KAN, List.of(0, 1, 2)), "three-tile kan rejected");
        expectIllegal(() -> new Meld(Meld.Kind.CLOSED_KAN, List.of(0, 1, 2, 4)), "mixed-type kan rejected");

        Meld eastPon = new Meld(Meld.Kind.PON, List.of(108, 109, 110));
        List<Integer> colliding = new ArrayList<>(hand("123m123p123s22z", spec(Meld.Kind.PON, "111z")).concealedTiles());
        colliding.set(0, 108);
        expectIllegal(() -> new HandInput(colliding, List.of(eastPon)), "physical duplicate across concealed and meld rejected");
        expectIllegal(() -> new HandInput(List.of(0, 1), List.of(eastPon, eastPon, eastPon, eastPon, eastPon)),
                "more than four declared groups rejected");
        expectNullRejected(() -> new HandInput(null, List.of()), "null concealed list rejected");
        expectNullRejected(() -> new HandInput(fourteen, null), "null meld list rejected");
        List<Integer> nullTile = new ArrayList<>(fourteen);
        nullTile.set(0, null);
        expectNullRejected(() -> new HandInput(nullTile, List.of()), "null physical tile rejected");
        expectNullRejected(() -> new Meld(null, List.of(0, 1, 2)), "null meld kind rejected");

        List<Meld> mutableMelds = new ArrayList<>();
        HandInput copied = new HandInput(fourteen, mutableMelds);
        fourteen.clear();
        mutableMelds.add(eastPon);
        check(copied.concealedTiles().size() == 14 && copied.melds().isEmpty(), "HandInput copies constructor lists");
        expectUnsupported(() -> copied.concealedTiles().clear(), "concealed accessor immutable");
        expectUnsupported(() -> copied.melds().clear(), "meld accessor immutable");
        int[] counts = copied.tileCounts();
        counts[0] = 99;
        check(copied.tileCounts()[0] == 1, "tileCounts returns a defensive copy");
        List<Integer> mutableTiles = new ArrayList<>(List.of(0, 1, 2));
        Meld copiedMeld = new Meld(Meld.Kind.PON, mutableTiles);
        mutableTiles.clear();
        check(copiedMeld.tiles().size() == 3, "Meld copies constructor list");
        expectUnsupported(() -> copiedMeld.tiles().clear(), "meld tile accessor immutable");
        List<HandDecomposition> result = HandAnalyzer.analyze(copied);
        expectUnsupported(result::clear, "analysis result immutable");
        expectUnsupported(() -> result.getFirst().groups().clear(), "decomposition groups immutable");
        Set<Integer> waits = HandAnalyzer.waits(hand("19m19p19s1234567z"));
        expectUnsupported(waits::clear, "wait result immutable");
        HandDecomposition seven = HandAnalyzer.analyze(hand("1122m3344p5566s77z")).getFirst();
        expectUnsupported(() -> seven.sevenPairTypes().clear(), "seven-pair type accessor immutable");
        List<HandDecomposition.Group> mutableGroups = new ArrayList<>(result.getFirst().groups());
        HandDecomposition copiedResult = new HandDecomposition(HandDecomposition.Form.STANDARD,
                result.getFirst().pairType(), mutableGroups);
        mutableGroups.clear();
        check(copiedResult.groups().size() == 4, "decomposition copies constructor group list");
        List<Integer> mutablePairTypes = new ArrayList<>(seven.sevenPairTypes());
        HandDecomposition copiedPairs = new HandDecomposition(HandDecomposition.Form.SEVEN_PAIRS, -1, List.of(), mutablePairTypes);
        mutablePairTypes.clear();
        check(copiedPairs.sevenPairTypes().size() == 7, "decomposition copies constructor seven-pair list");
    }

    private static void crossChecks() {
        Random random = new Random(0x524949434849L);
        for (int sample = 0; sample < 4_000; sample++) {
            HandInput candidate = new HandInput(randomPhysicalTiles(random, 14), List.of());
            compareReference(candidate, "random complete " + sample);
            if (sample < 400) {
                compareSymmetry(candidate, new int[]{1, 2, 0});
            }
        }
        for (int sample = 0; sample < 2_000; sample++) {
            int[] counts = randomStandardCounts(random);
            HandInput complete = fromCounts(counts);
            compareReference(complete, "constructed complete " + sample);
            check(!HandAnalyzer.analyze(complete).isEmpty(), "constructed legal standard hand must complete");
            verifyDecompositions(complete);
            if (sample < 600) {
                compareSymmetry(complete, new int[]{2, 0, 1});
                List<Integer> thirteen = new ArrayList<>(complete.concealedTiles());
                thirteen.remove(random.nextInt(thirteen.size()));
                compareWaitReference(new HandInput(thirteen, List.of()), "constructed wait " + sample, true);
            }
        }
        for (int sample = 0; sample < 700; sample++) {
            HandInput waiting = new HandInput(randomPhysicalTiles(random, 13), List.of());
            compareWaitReference(waiting, "random wait " + sample, sample < 100);
            if (sample < 200) {
                Set<Integer> actual = HandAnalyzer.waits(waiting);
                Set<Integer> moved = HandAnalyzer.waits(permute(waiting, new int[]{2, 0, 1}));
                Set<Integer> expectedMoved = new HashSet<>();
                actual.forEach(type -> expectedMoved.add(permuteType(type, new int[]{2, 0, 1})));
                check(moved.equals(expectedMoved), "wait suit permutation preserves all candidates");
            }
        }
        for (int sample = 0; sample < 500; sample++) {
            int[] counts = randomStandardCounts(random);
            HandInput complete = fromCounts(counts);
            HandDecomposition split = HandAnalyzer.analyze(complete).stream()
                    .filter(d -> d.form() == HandDecomposition.Form.STANDARD).findFirst().orElseThrow();
            HandDecomposition.Group chosen = split.groups().get(random.nextInt(4));
            List<Integer> concealed = new ArrayList<>(complete.concealedTiles());
            List<Integer> meldTiles = removeGroup(concealed, chosen);
            Meld.Kind kind = chosen.kind() == HandDecomposition.Kind.SEQUENCE ? Meld.Kind.CHI : Meld.Kind.PON;
            HandInput open = new HandInput(concealed, List.of(new Meld(kind, meldTiles)));
            compareReference(open, "constructed declared meld " + sample);
            verifyDecompositions(open);
            concealed = new ArrayList<>(open.concealedTiles());
            concealed.remove(random.nextInt(concealed.size()));
            compareWaitReference(new HandInput(concealed, open.melds()), "declared wait " + sample, sample < 50);
        }
    }

    private static void compareReference(HandInput hand, String context) {
        int[] counts = countConcealed(hand);
        boolean expected = referenceWinning(counts, hand.melds().size(), hand.melds().isEmpty());
        boolean actual = !HandAnalyzer.analyze(hand).isEmpty();
        check(actual == expected, context + ": reference disagrees for " + Arrays.toString(counts));
        crossCheckedHands++;
    }

    private static void exhaustiveOneSuit() {
        List<OracleGroup> groups = new ArrayList<>();
        for (int rank = 0; rank < 9; rank++) {
            groups.add(new OracleGroup("TRIPLET:" + rank + ":false", new int[]{rank, rank, rank}));
        }
        for (int rank = 0; rank < 7; rank++) {
            groups.add(new OracleGroup("SEQUENCE:" + rank + ":false", new int[]{rank, rank + 1, rank + 2}));
        }
        Map<Integer, Set<String>> expected = new HashMap<>();
        enumerateSuitDecompositions(groups, 0, new int[9], new ArrayList<>(), expected);
        enumerateAllFourteenTileSuitStates(new int[9], 0, 14, expected);
        check(exhaustiveSuitStates == 118_800, "all 118800 legal fourteen-tile one-suit count states were traversed");
        check(exhaustiveWinningSuitStates == expected.size() + 36 - countSevenPairStandardOverlaps(expected),
                "winning-state accounting includes the independently generated 36 seven-pair states");
    }

    private record OracleGroup(String signature, int[] ranks) {
    }

    private static void enumerateSuitDecompositions(List<OracleGroup> groups, int first,
                                                   int[] counts, List<String> selected,
                                                   Map<Integer, Set<String>> expected) {
        if (selected.size() == 4) {
            List<String> canonical = selected.stream().sorted().toList();
            for (int pair = 0; pair < 9; pair++) {
                if (counts[pair] <= 2) {
                    counts[pair] += 2;
                    String explanation = "STANDARD:" + pair + ":" + canonical + ":[]";
                    expected.computeIfAbsent(suitCode(counts), unused -> new HashSet<>()).add(explanation);
                    counts[pair] -= 2;
                }
            }
            return;
        }
        for (int group = first; group < groups.size(); group++) {
            OracleGroup chosen = groups.get(group);
            for (int rank : chosen.ranks()) {
                counts[rank]++;
            }
            if (Arrays.stream(counts).allMatch(count -> count <= 4)) {
                selected.add(chosen.signature());
                enumerateSuitDecompositions(groups, group, counts, selected, expected);
                selected.removeLast();
            }
            for (int rank : chosen.ranks()) {
                counts[rank]--;
            }
        }
    }

    private static void enumerateAllFourteenTileSuitStates(int[] counts, int rank, int remaining,
                                                          Map<Integer, Set<String>> expected) {
        if (rank == 9) {
            if (remaining != 0) {
                return;
            }
            Set<String> explanations = new HashSet<>(expected.getOrDefault(suitCode(counts), Set.of()));
            List<Integer> pairTypes = new ArrayList<>();
            boolean sevenPairs = true;
            for (int type = 0; type < 9; type++) {
                if (counts[type] == 2) {
                    pairTypes.add(type);
                } else if (counts[type] != 0) {
                    sevenPairs = false;
                }
            }
            if (sevenPairs && pairTypes.size() == 7) {
                explanations.add("SEVEN_PAIRS:-1:[]:" + pairTypes);
            }
            Set<String> actual = signatures(HandAnalyzer.analyze(fromCounts(Arrays.copyOf(counts, 34))));
            check(actual.equals(explanations), "exhaustive one-suit mismatch for " + Arrays.toString(counts)
                    + "; expected=" + explanations + "; actual=" + actual);
            exhaustiveSuitStates++;
            if (!explanations.isEmpty()) {
                exhaustiveWinningSuitStates++;
            }
            return;
        }
        int minimum = Math.max(0, remaining - (8 - rank) * 4);
        int maximum = Math.min(4, remaining);
        for (int count = minimum; count <= maximum; count++) {
            counts[rank] = count;
            enumerateAllFourteenTileSuitStates(counts, rank + 1, remaining - count, expected);
        }
        counts[rank] = 0;
    }

    private static int countSevenPairStandardOverlaps(Map<Integer, Set<String>> expected) {
        int overlap = 0;
        for (int omittedA = 0; omittedA < 9; omittedA++) {
            for (int omittedB = omittedA + 1; omittedB < 9; omittedB++) {
                int[] counts = new int[9];
                Arrays.fill(counts, 2);
                counts[omittedA] = 0;
                counts[omittedB] = 0;
                if (expected.containsKey(suitCode(counts))) {
                    overlap++;
                }
            }
        }
        return overlap;
    }

    private static int suitCode(int[] counts) {
        int code = 0;
        int place = 1;
        for (int count : counts) {
            code += count * place;
            place *= 5;
        }
        return code;
    }

    private static void compareWaitReference(HandInput hand, String context, boolean allCandidates) {
        int[] counts = countConcealed(hand);
        int[] all = countAll(hand);
        Set<Integer> expected = new TreeSet<>();
        for (int type = 0; type < 34; type++) {
            if (all[type] == 4) {
                continue;
            }
            counts[type]++;
            if (referenceWinning(counts, hand.melds().size(), hand.melds().isEmpty())) {
                expected.add(type);
            }
            counts[type]--;
            if (allCandidates) {
                boolean complete = !HandAnalyzer.analyze(appendAvailable(hand, type)).isEmpty();
                check(complete == expected.contains(type), context + ": candidate " + type + " disagrees with reference");
                crossCheckedHands++;
            }
        }
        check(HandAnalyzer.waits(hand).equals(expected), context + ": waits disagree; expected " + expected);
        crossCheckedWaits++;
    }

    private static void compareSymmetry(HandInput hand, int[] suits) {
        Set<String> expected = new HashSet<>();
        for (HandDecomposition original : HandAnalyzer.analyze(hand)) {
            List<HandDecomposition.Group> groups = original.groups().stream()
                    .map(g -> new HandDecomposition.Group(g.kind(), permuteType(g.tileType(), suits), g.open())).toList();
            List<Integer> pairs = original.sevenPairTypes().stream().map(t -> permuteType(t, suits)).sorted().toList();
            HandDecomposition transformed = new HandDecomposition(original.form(),
                    original.pairType() < 0 ? -1 : permuteType(original.pairType(), suits), groups, pairs);
            expected.add(signature(transformed));
        }
        check(signatures(HandAnalyzer.analyze(permute(hand, suits))).equals(expected), "suit permutation preserves every decomposition");
    }

    /**
     * Independent oracle: construct every legal one-suit multiset from zero to
     * four complete groups, then test three suit-state memberships after choosing
     * a pair. It does not recursively remove a first tile from the tested hand.
     */
    private static boolean referenceWinning(int[] counts, int fixedGroups, boolean specials) {
        if (specials) {
            int pairs = 0;
            boolean sevenPairs = true;
            for (int count : counts) {
                pairs += count == 2 ? 1 : 0;
                sevenPairs &= count == 0 || count == 2;
            }
            if (sevenPairs && pairs == 7) {
                return true;
            }
            boolean orphans = true;
            int duplicate = 0;
            for (int type = 0; type < 34; type++) {
                boolean terminal = contains(ORPHANS, type);
                orphans &= terminal ? counts[type] == 1 || counts[type] == 2 : counts[type] == 0;
                duplicate += terminal && counts[type] == 2 ? 1 : 0;
            }
            if (orphans && duplicate == 1) {
                return true;
            }
        }
        for (int pair = 0; pair < 34; pair++) {
            if (counts[pair] < 2) {
                continue;
            }
            counts[pair] -= 2;
            int groupTotal = fixedGroups;
            boolean valid = true;
            for (int suit = 0; suit < 3; suit++) {
                int sum = 0;
                int code = 0;
                int place = 1;
                for (int rank = 0; rank < 9; rank++) {
                    int count = counts[suit * 9 + rank];
                    sum += count;
                    code += count * place;
                    place *= 5;
                }
                int groups = sum / 3;
                valid &= sum % 3 == 0 && groups <= 4 && SUIT_STATES.get(groups).contains(code);
                groupTotal += groups;
            }
            for (int honor = 27; honor < 34; honor++) {
                valid &= counts[honor] == 0 || counts[honor] == 3;
                groupTotal += counts[honor] / 3;
            }
            counts[pair] += 2;
            if (valid && groupTotal == 4) {
                return true;
            }
        }
        return false;
    }

    private static List<Set<Integer>> buildSuitStates() {
        List<Set<Integer>> states = new ArrayList<>();
        for (int groups = 0; groups <= 4; groups++) {
            states.add(new HashSet<>());
        }
        enumerateSuitStates(new int[9], 0, 0, states);
        return states.stream().map(Set::copyOf).toList();
    }

    private static void enumerateSuitStates(int[] counts, int firstGroup, int groupCount, List<Set<Integer>> states) {
        int code = 0;
        int place = 1;
        for (int count : counts) {
            code += count * place;
            place *= 5;
        }
        states.get(groupCount).add(code);
        if (groupCount == 4) {
            return;
        }
        for (int group = firstGroup; group < 16; group++) {
            int[] ranks = group < 9 ? new int[]{group, group, group} : new int[]{group - 9, group - 8, group - 7};
            for (int rank : ranks) {
                counts[rank]++;
            }
            if (Arrays.stream(counts).allMatch(count -> count <= 4)) {
                enumerateSuitStates(counts, group, groupCount + 1, states);
            }
            for (int rank : ranks) {
                counts[rank]--;
            }
        }
    }

    private static int[] randomStandardCounts(Random random) {
        while (true) {
            int[] counts = new int[34];
            counts[random.nextInt(34)] = 2;
            for (int group = 0; group < 4; group++) {
                if (random.nextBoolean()) {
                    int type = random.nextInt(34);
                    counts[type] += 3;
                } else {
                    int start = random.nextInt(3) * 9 + random.nextInt(7);
                    counts[start]++;
                    counts[start + 1]++;
                    counts[start + 2]++;
                }
            }
            if (Arrays.stream(counts).allMatch(count -> count <= 4)) {
                return counts;
            }
        }
    }

    private static List<Integer> randomPhysicalTiles(Random random, int length) {
        int[] ids = java.util.stream.IntStream.range(0, 136).toArray();
        List<Integer> result = new ArrayList<>();
        for (int index = 0; index < length; index++) {
            int other = index + random.nextInt(136 - index);
            int selected = ids[other];
            ids[other] = ids[index];
            ids[index] = selected;
            result.add(selected);
        }
        return result;
    }

    private static List<Integer> removeGroup(List<Integer> concealed, HandDecomposition.Group group) {
        List<Integer> selected = new ArrayList<>();
        int[] types = group.kind() == HandDecomposition.Kind.SEQUENCE
                ? new int[]{group.tileType(), group.tileType() + 1, group.tileType() + 2}
                : new int[]{group.tileType(), group.tileType(), group.tileType()};
        for (int type : types) {
            int physical = concealed.stream().filter(id -> id / 4 == type).findFirst().orElseThrow();
            concealed.remove(Integer.valueOf(physical));
            selected.add(physical);
        }
        return selected;
    }

    private static HandInput fromCounts(int[] counts) {
        List<Integer> ids = new ArrayList<>();
        for (int type = 0; type < 34; type++) {
            for (int copy = 0; copy < counts[type]; copy++) {
                ids.add(type * 4 + copy);
            }
        }
        return new HandInput(ids, List.of());
    }

    private static HandInput appendAvailable(HandInput hand, int type) {
        Set<Integer> used = new HashSet<>(hand.concealedTiles());
        hand.melds().forEach(meld -> used.addAll(meld.tiles()));
        int physical = java.util.stream.IntStream.range(type * 4, type * 4 + 4)
                .filter(id -> !used.contains(id)).findFirst().orElseThrow();
        List<Integer> concealed = new ArrayList<>(hand.concealedTiles());
        concealed.add(physical);
        return new HandInput(concealed, hand.melds());
    }

    private static HandInput permute(HandInput hand, int[] suits) {
        List<Integer> concealed = hand.concealedTiles().stream().map(id -> permuteType(id / 4, suits) * 4 + id % 4).toList();
        List<Meld> melds = hand.melds().stream().map(meld -> new Meld(meld.kind(),
                meld.tiles().stream().map(id -> permuteType(id / 4, suits) * 4 + id % 4).toList())).toList();
        return new HandInput(concealed, melds);
    }

    private static int permuteType(int type, int[] suits) {
        return type >= 27 ? type : suits[type / 9] * 9 + type % 9;
    }

    private static void verifyDecompositions(HandInput hand) {
        List<HandDecomposition> result = HandAnalyzer.analyze(hand);
        check(signatures(result).size() == result.size(), "no duplicate decomposition");
        for (HandDecomposition decomposition : result) {
            int[] reconstructed = new int[34];
            switch (decomposition.form()) {
                case STANDARD -> {
                    check(decomposition.groups().size() == 4 && decomposition.sevenPairTypes().isEmpty(), "standard has four groups and one pair");
                    reconstructed[decomposition.pairType()] += 2;
                    for (HandDecomposition.Group group : decomposition.groups()) {
                        int type = group.tileType();
                        if (group.kind() == HandDecomposition.Kind.SEQUENCE) {
                            check(type < 27 && type % 9 <= 6, "sequence stays within one suit");
                            reconstructed[type]++;
                            reconstructed[type + 1]++;
                            reconstructed[type + 2]++;
                        } else {
                            reconstructed[type] += group.kind() == HandDecomposition.Kind.QUAD ? 4 : 3;
                        }
                    }
                }
                case SEVEN_PAIRS -> {
                    check(hand.melds().isEmpty(), "seven pairs has no declared groups");
                    check(decomposition.sevenPairTypes().size() == 7 && decomposition.groups().isEmpty()
                            && decomposition.pairType() == -1, "seven pairs representation");
                    check(new HashSet<>(decomposition.sevenPairTypes()).size() == 7, "seven distinct pair types");
                    decomposition.sevenPairTypes().forEach(type -> reconstructed[type] += 2);
                }
                case THIRTEEN_ORPHANS -> {
                    check(hand.melds().isEmpty() && decomposition.groups().isEmpty(), "kokushi has no declared groups");
                    for (int type : ORPHANS) {
                        reconstructed[type]++;
                    }
                    reconstructed[decomposition.pairType()]++;
                }
            }
            check(Arrays.equals(reconstructed, countAll(hand)), "decomposition reconstructs exactly the physical hand's types");
        }
    }

    private static boolean hasForm(HandInput hand, HandDecomposition.Form form) {
        return HandAnalyzer.analyze(hand).stream().anyMatch(decomposition -> decomposition.form() == form);
    }

    private static long countKind(HandDecomposition decomposition, HandDecomposition.Kind kind) {
        return decomposition.groups().stream().filter(group -> group.kind() == kind).count();
    }

    private static Set<String> signatures(List<HandDecomposition> decompositions) {
        Set<String> result = new HashSet<>();
        decompositions.forEach(decomposition -> result.add(signature(decomposition)));
        return result;
    }

    private static String signature(HandDecomposition decomposition) {
        List<String> groups = decomposition.groups().stream()
                .map(group -> group.kind() + ":" + group.tileType() + ":" + group.open()).sorted().toList();
        return decomposition.form() + ":" + decomposition.pairType() + ":" + groups + ":"
                + decomposition.sevenPairTypes().stream().sorted().toList();
    }

    private static int[] countConcealed(HandInput hand) {
        int[] counts = new int[34];
        hand.concealedTiles().forEach(id -> counts[id / 4]++);
        return counts;
    }

    private static int[] countAll(HandInput hand) {
        int[] counts = countConcealed(hand);
        hand.melds().forEach(meld -> meld.tiles().forEach(id -> counts[id / 4]++));
        return counts;
    }

    private static Set<Integer> asSet(int[] values) {
        Set<Integer> result = new HashSet<>();
        Arrays.stream(values).forEach(result::add);
        return result;
    }

    private static boolean contains(int[] values, int target) {
        return Arrays.stream(values).anyMatch(value -> value == target);
    }

    private record MeldSpec(Meld.Kind kind, String notation) {
    }

    private static MeldSpec spec(Meld.Kind kind, String notation) {
        return new MeldSpec(kind, notation);
    }

    private static HandInput hand(String notation, MeldSpec... specs) {
        int[] used = new int[34];
        List<Meld> melds = new ArrayList<>();
        for (MeldSpec spec : specs) {
            melds.add(new Meld(spec.kind(), physical(parseTypes(spec.notation()), used)));
        }
        return new HandInput(physical(parseTypes(notation), used), melds);
    }

    private static List<Integer> physical(List<Integer> types, int[] used) {
        List<Integer> ids = new ArrayList<>();
        for (int type : types) {
            if (used[type] >= 4) {
                throw new AssertionError("Invalid test fixture: fifth copy of type " + type);
            }
            ids.add(type * 4 + used[type]++);
        }
        return ids;
    }

    private static List<Integer> parseTypes(String notation) {
        List<Integer> result = new ArrayList<>();
        List<Integer> ranks = new ArrayList<>();
        for (char character : notation.toCharArray()) {
            if (character >= '1' && character <= '9') {
                ranks.add(character - '1');
                continue;
            }
            int offset = switch (character) {
                case 'm' -> 0;
                case 'p' -> 9;
                case 's' -> 18;
                case 'z' -> 27;
                default -> throw new AssertionError("Invalid test notation " + notation);
            };
            for (int rank : ranks) {
                if (character == 'z' && rank >= 7) {
                    throw new AssertionError("Invalid test honor notation " + notation);
                }
                result.add(offset + rank);
            }
            ranks.clear();
        }
        if (!ranks.isEmpty()) {
            throw new AssertionError("Missing suit in test notation " + notation);
        }
        return result;
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void expectIllegal(Runnable action, String message) {
        assertions++;
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message + " (expected IllegalArgumentException)");
    }

    private static void expectNullRejected(Runnable action, String message) {
        assertions++;
        try {
            action.run();
        } catch (IllegalArgumentException | NullPointerException expected) {
            return;
        }
        throw new AssertionError(message + " (expected null rejection)");
    }

    private static void expectUnsupported(Runnable action, String message) {
        assertions++;
        try {
            action.run();
        } catch (UnsupportedOperationException expected) {
            return;
        }
        throw new AssertionError(message + " (expected UnsupportedOperationException)");
    }
}
