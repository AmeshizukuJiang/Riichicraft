package dev.riichicraft.server;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipFile;

/** Manual capture only: requires the original running service class and exact original production JAR. */
final class SnapshotBaselineCapture {
    static final String SHA = "a9572d5ea1240f0abe31f8aa9fcaf771cb7145fdabc2b692ef703dfa064c2cce";
    private SnapshotBaselineCapture() { }
    static void write(Map<String, String> packets) throws IOException {
        Path root = Path.of(System.getProperty("riichicraft.test.projectRoot")).toAbsolutePath().normalize();
        Path jar = root.resolve(".tmp/snapshot-refactor/baseline-v18.jar");
        Path destination = root.resolve(".tmp/snapshot-refactor/baseline-v18.json");
        if (Files.exists(destination)) throw new IOException("Snapshot capture refuses to replace an existing output");
        if (!SnapshotFixtures.sha(Files.readAllBytes(jar)).equals(SHA)) throw new IOException("Wrong original JAR");
        String entry = "dev/riichicraft/server/LobbyService.class";
        try (var zip = new ZipFile(jar.toFile()); var old = zip.getInputStream(zip.getEntry(entry));
                var running = LobbyService.class.getResourceAsStream("/" + entry)) {
            if (!SnapshotFixtures.sha(old.readAllBytes()).equals(SnapshotFixtures.sha(running.readAllBytes())))
                throw new IOException("Capture requires original v18 LobbyService bytecode");
        }
        var result = new JsonObject(); result.addProperty("version", "0.0.18-dev"); result.addProperty("jarSha256", SHA);
        result.addProperty("sourceCommit", "3d80e7649b29036bf4a2afdaaeb205527f36ad5b");
        var values = new JsonObject(); packets.forEach(values::addProperty); result.add("packets", values);
        Files.writeString(destination, new GsonBuilder().setPrettyPrinting().create().toJson(result) + "\n");
    }
}
