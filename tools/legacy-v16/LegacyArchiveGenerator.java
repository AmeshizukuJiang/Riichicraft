package dev.riichicraft.rules;

import dev.riichicraft.persistence.TableArchiveStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/** Explicit, manually invoked historical writer. Compile only against the hash-guarded old release JAR. */
public final class LegacyArchiveGenerator {
    static final String OLD_JAR_SHA = "2dc248499ec8e60851dc4d73b0e91c63e9db8657f6f3ceef596b1f0e1e03d7a5";
    private record Fixture(String kind, byte[] bytes, String summary) { }

    public static void main(String[] args) throws Exception {
        Path writer = Path.of(GameArchiveCodec.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!Files.isRegularFile(writer) || !sha(Files.readAllBytes(writer)).equals(OLD_JAR_SHA))
            throw new IllegalStateException("Historical production classes must come from the exact v0.0.16 JAR");
        Path destination = Path.of(args[0]);
        Files.createDirectories(destination);
        try (var files = Files.list(destination)) {
            if (files.findAny().isPresent()) throw new IllegalStateException("Refusing to overwrite frozen legacy fixtures");
        }
        var fixtures = new LinkedHashMap<String,Fixture>();
        var fourRounds = ArchiveTests.legacyRounds();
        fourRounds.forEach((name, value) -> fixtures.put(name,
                new Fixture("FOUR_ROUND", GameArchiveCodec.encodeRound(value), LegacyArchiveSummary.four(value))));
        ArchiveTests.legacyMatches().forEach((name, value) -> fixtures.put(name,
                new Fixture("FOUR_MATCH", GameArchiveCodec.encodeMatch(value), LegacyArchiveSummary.four(value))));
        var threeRounds = ThreePlayerEngineTests.legacyRounds();
        threeRounds.forEach((name, value) -> fixtures.put(name,
                new Fixture("THREE_ROUND", ThreePlayerArchiveCodec.encodeRound(value), LegacyArchiveSummary.three(value))));
        ThreePlayerEngineTests.legacyMatches().forEach((name, value) -> fixtures.put(name,
                new Fixture("THREE_MATCH", ThreePlayerArchiveCodec.encodeMatch(value), LegacyArchiveSummary.three(value))));
        var previouslyBroken = RiichiNorthExhaustion.legacyAcceptedBrokenState();
        fixtures.put("three-riichi-north-exhausted", new Fixture("THREE_ROUND",
                ThreePlayerArchiveCodec.encodeRound(previouslyBroken), LegacyArchiveSummary.three(previouslyBroken)));
        var tables = new ArrayList<TableArchiveStore.Table>();
        var four = fourRounds.get("four-partial-riichi");
        tables.add(new TableArchiveStore.Table(new UUID(910, 1), "minecraft:overworld", 32, 64, -16, 0, 12, 23,
                four.view(null).seats().stream().map(s -> new TableArchiveStore.Seat(s.playerId(), "LegacyFour" + s.seatWind())).toList(),
                GameArchiveCodec.encodeRound(four), List.of(2, 3, 0, 1)));
        var three = threeRounds.get("three-north-active");
        tables.add(new TableArchiveStore.Table(new UUID(910, 2), "minecraft:the_nether", 32, 64, -16, 3, 34, 45,
                three.view(null).seats().stream().map(s -> new TableArchiveStore.Seat(s.playerId(), "LegacyThree" + s.seatWind())).toList(),
                ThreePlayerArchiveCodec.encodeRound(three), List.of(1, 2, 0)));
        fixtures.put("world-tables-v2", new Fixture("WORLD_TABLES", TableArchiveStore.encode(tables), LegacyArchiveSummary.world(tables)));
        StringBuilder manifest = new StringBuilder("format.version=1\nwriter.version=0.0.16-dev\nwriter.jar.sha256=")
                .append(OLD_JAR_SHA).append("\nsource.ref=51e06b0ff9b9f527938f004dbeb83cb9c4a55ea0\n")
                .append("source.ArchiveTests.blob=0ea128d9c421cf2369e13571ae3f4031b5735a1d\n")
                .append("source.ThreePlayerEngineTests.blob=802ac87cec7146dfd499695087f2699f85b2bd5b\n")
                .append("source.RiichiNorthExhaustion.blob=5e004f2e4ad9b84c5711acbe409076c2d1287fa5\n")
                .append("fixture.names=").append(String.join(",", fixtures.keySet())).append('\n');
        for (var entry : fixtures.entrySet()) {
            String name = entry.getKey(); Fixture fixture = entry.getValue();
            Files.write(destination.resolve(name + ".bin"), fixture.bytes());
            manifest.append("fixture.").append(name).append(".kind=").append(fixture.kind()).append('\n')
                    .append("fixture.").append(name).append(".sha256=").append(sha(fixture.bytes())).append('\n')
                    .append("fixture.").append(name).append(".summary=").append(fixture.summary()).append('\n');
        }
        Files.writeString(destination.resolve("manifest.properties"), manifest, StandardCharsets.UTF_8);
        System.out.println("Wrote " + fixtures.size() + " historical fixtures using " + writer + " to " + destination);
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
