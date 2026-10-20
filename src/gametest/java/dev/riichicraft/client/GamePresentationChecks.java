package dev.riichicraft.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.riichicraft.network.GameSnapshot;
import dev.riichicraft.network.LobbySnapshotPayload;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;

/** Frozen original UI outputs plus explicit accounting checks, run in the actual client. */
public final class GamePresentationChecks {
    private static int assertions;
    private GamePresentationChecks() { }

    public static void verify() {
        assertions = 0;
        String resource = "/dev/riichicraft/client/presentation-v17.json";
        try (var stream = GamePresentationChecks.class.getResourceAsStream(resource)) {
            if (stream == null) throw new AssertionError("Missing frozen presentation baseline");
            var baseline = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            require(baseline.get("jarSha256").getAsString().equals("f849d904421bfab3d7e626af6429fc965fc917dbba3467fea53c45538004ba4c"),
                    "The original writer remains the verified v17 JAR");
            var cases = baseline.getAsJsonObject("results");
            require(cases.keySet().equals(PresentationFixtures.results().keySet()), "Every original result fixture remains covered");
            var oldPanelLines = GamePanel.class.getDeclaredMethod("resultLines"); oldPanelLines.setAccessible(true);
            var tableLines = TableScreen.class.getDeclaredMethod("resultLines", LobbySnapshotPayload.class); tableLines.setAccessible(true);
            for (var entry : PresentationFixtures.results().entrySet()) {
                var snapshot = entry.getValue(); var expected = cases.getAsJsonObject(entry.getKey());
                compare(expected.getAsJsonArray("legacy"), GamePresentation.legacyResultLines(snapshot), entry.getKey() + " legacy");
                compare(expected.getAsJsonArray("table"), GamePresentation.tableResultLines(snapshot), entry.getKey() + " table");
                var panel = new GamePanel(new LobbyScreen(snapshot));
                require(PresentationBaselineCapture.components((List<?>) oldPanelLines.invoke(panel)).equals(expected.get("legacy")),
                        "The actual legacy screen delegates without changing its result output");
                require(PresentationBaselineCapture.components((List<?>) tableLines.invoke(null, snapshot)).equals(expected.get("table")),
                        "The actual table screen delegates without changing its result output");
                verifyAccounting(entry.getKey(), snapshot.game());
                // Returned Components are mutable; changing one view must not poison another projection.
                var changed = GamePresentation.tableResultLines(snapshot);
                if (!changed.isEmpty()) ((net.minecraft.network.chat.MutableComponent) changed.getFirst()).append("mutation");
                require(PresentationBaselineCapture.components(GamePresentation.tableResultLines(snapshot)).equals(expected.get("table")),
                        "One view cannot mutate another view's presentation");
            }
            var actions = baseline.getAsJsonArray("actions");
            var options = PresentationFixtures.actions();
            require(actions.size() == options.size(), "All original action labels remain covered");
            for (int i = 0; i < options.size(); i++) {
                var expected = actions.get(i).getAsJsonObject(); var option = options.get(i);
                require(PresentationBaselineCapture.component(GamePresentation.optionLabel(option)).equals(expected.get("legacy")),
                        "Action selection labels preserve red and normal face distinctions");
                require(PresentationBaselineCapture.component(GamePresentation.actionLabel(option.action())).equals(expected.get("label")),
                        "Both screens retain the same action translation");
            }
            System.out.println("PASS shared presentation: " + assertions + " assertions; " + cases.size()
                    + " original result fixtures, " + actions.size() + " action fixtures");
        } catch (ReflectiveOperationException | java.io.IOException failure) { throw new AssertionError(failure); }
    }

    private static void verifyAccounting(String name, GameSnapshot game) {
        var value = GamePresentation.settlement(game);
        boolean finalMatch = game.match() != null && game.match().result() != null;
        boolean paid = game.rules().result().kind() <= 3;
        var expected = new ArrayList<>(game.seats().size() == 3 ? List.of(paid ? 8000 : -1000, paid ? -7700 : 0, paid ? 700 : 0)
                : List.of(paid ? 8000 : -1000, paid ? -7700 : 0, paid ? 700 : 0, 0));
        if (finalMatch) expected.set(1, expected.get(1) + 3000);
        require(GamePresentation.settlementDeltas(game).equals(expected), name + " uses the actual transfers and only established riichi debit");
        require(TableScreen.settlementDeltas(game).equals(expected), "The existing public delta entry point stays compatible");
        require(GamePresentation.displayedDeposits(game) == (finalMatch ? 0 : 3), "Already-awarded final sticks cannot remain in the visible pool");
        for (var seat : value.seats()) {
            require(seat.roundDelta() == expected.get(seat.seat()), "Seat summaries preserve whole-hand accounting");
            require(seat.cumulativeDelta() == (seat.seat() == 0 ? -2100 : seat.seat() == 1 ? 1200 : 0),
                    "Legacy cumulative changes keep their original opening-point baseline");
            int expectedPoints = finalMatch ? (game.seats().size() == 3 ? 35000 : 25000) + (seat.seat() == 1 ? 3000 : 0)
                    : game.rules().seats().get(seat.seat()).points();
            require(seat.displayedPoints() == expectedPoints, "Final rankings override the round points exactly once");
        }
        try { value.seats().clear(); throw new AssertionError("Mutable settlement seats"); }
        catch (UnsupportedOperationException expectedFailure) { assertions++; }
    }

    private static void compare(JsonArray expected, List<Component> actual, String name) {
        require(expected.size() == actual.size(), name + " keeps line count and pagination inputs");
        for (int line = 0; line < actual.size(); line++)
            require(PresentationBaselineCapture.component(actual.get(line)).equals(expected.get(line)),
                    name + " preserves translation, arguments, values and order at line " + line);
    }
    private static void require(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
