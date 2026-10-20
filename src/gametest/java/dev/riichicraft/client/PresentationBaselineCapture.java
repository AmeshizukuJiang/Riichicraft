package dev.riichicraft.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.riichicraft.network.GameSnapshot;
import dev.riichicraft.network.LobbySnapshotPayload;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipFile;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;

/** Guarded original-v17 capture; normal regressions use only the serialization helpers, never capture(). */
public final class PresentationBaselineCapture {
    private static final String OLD_SHA = "f849d904421bfab3d7e626af6429fc965fc917dbba3467fea53c45538004ba4c";
    private PresentationBaselineCapture() { }

    public static void capture() {
        try {
            Path root = Path.of(System.getProperty("riichicraft.test.projectRoot")).toAbsolutePath().normalize();
            Path output = root.resolve(".tmp/presentation-refactor/baseline-v17.json");
            if (Files.exists(output)) throw new IllegalStateException("Baseline output exists; refusing replacement");
            Path jar = root.resolve(".tmp/presentation-refactor/baseline-v17.jar");
            if (!sha(Files.readAllBytes(jar)).equals(OLD_SHA)) throw new IllegalStateException("Wrong original JAR");
            try (var zip = new ZipFile(jar.toFile())) {
                for (Class<?> type : List.of(GamePanel.class, TableScreen.class)) {
                    String resource = type.getName().replace('.', '/') + ".class";
                    try (var original = zip.getInputStream(zip.getEntry(resource)); var running = type.getResourceAsStream("/" + resource)) {
                        if (!sha(original.readAllBytes()).equals(sha(running.readAllBytes())))
                            throw new IllegalStateException("Capture requires the unchanged v17 class: " + resource);
                    }
                }
            }
            var rootJson = new JsonObject();
            rootJson.addProperty("version", "0.0.17-dev"); rootJson.addProperty("jarSha256", OLD_SHA);
            rootJson.addProperty("sourceCommit", "63ba8dfdbb5fb43584113142a182fb386758a6dc");
            var cases = new JsonObject();
            var legacy = GamePanel.class.getDeclaredMethod("resultLines"); legacy.setAccessible(true);
            var table = TableScreen.class.getDeclaredMethod("resultLines", LobbySnapshotPayload.class); table.setAccessible(true);
            var option = GamePanel.class.getDeclaredMethod("optionLabel", GameSnapshot.ActionOption.class); option.setAccessible(true);
            var action = TableScreen.class.getDeclaredMethod("actionLabel", int.class); action.setAccessible(true);
            for (var entry : PresentationFixtures.results().entrySet()) {
                var snapshot = entry.getValue(); var panel = new GamePanel(new LobbyScreen(snapshot));
                var value = new JsonObject();
                value.add("legacy", components((List<?>) legacy.invoke(panel)));
                value.add("table", components((List<?>) table.invoke(null, snapshot)));
                cases.add(entry.getKey(), value);
            }
            rootJson.add("results", cases);
            var actions = new JsonArray();
            var panel = new GamePanel(new LobbyScreen(PresentationFixtures.results().values().iterator().next()));
            for (var value : PresentationFixtures.actions()) {
                var row = new JsonObject(); row.add("legacy", component((Component) option.invoke(panel, value)));
                row.add("label", component((Component) action.invoke(null, value.action()))); actions.add(row);
            }
            rootJson.add("actions", actions);
            Files.writeString(output, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(rootJson) + "\n");
            System.out.println("CAPTURE original v17 presentation: " + cases.size() + " result fixtures, " + actions.size() + " action fixtures");
        } catch (ReflectiveOperationException | java.io.IOException failure) { throw new IllegalStateException(failure); }
    }

    static com.google.gson.JsonElement component(Component value) {
        return ComponentSerialization.CODEC.encodeStart(JsonOps.INSTANCE, value).getOrThrow();
    }
    static JsonArray components(List<?> values) {
        var result = new JsonArray(); for (var value : values) result.add(component((Component) value)); return result;
    }
    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
