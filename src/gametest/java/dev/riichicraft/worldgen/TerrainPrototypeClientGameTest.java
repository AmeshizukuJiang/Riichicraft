package dev.riichicraft.worldgen;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.Reader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.impl.client.gametest.context.TestDedicatedServerContextImpl;
import net.fabricmc.fabric.impl.client.gametest.util.DedicatedServerImplUtil;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugEntryBiome;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.lwjgl.glfw.GLFW;

/** Opt-in native generation suite. Every measured hash comes from a FULL chunk in a real dedicated world. */
@SuppressWarnings("UnstableApiUsage")
public final class TerrainPrototypeClientGameTest implements FabricClientGameTest {
    private static final long[] SEEDS = {20261020L, 42L, -7046029254386353131L};
    private static final List<ChunkPos> LATER_CHUNKS = List.of(new ChunkPos(4096, -4096), new ChunkPos(-8192, 6144));
    private record Sample(TerrainProbe.Measured measured, double milliseconds) { }

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!Boolean.getBoolean("riichicraft.test.terrain")) return;
        context.runOnClient(c -> c.options.cloudStatus().set(CloudStatus.OFF));
        Path root = projectRoot(context), output = root.resolve(".tmp/terrain-prototype");
        try {
            Files.createDirectories(output);
            JsonObject report = new JsonObject();
            report.addProperty("minecraft", "26.2");
            report.addProperty("originOverheadFeetAboveSurface", 40); report.addProperty("boundaryOverheadFeetAboveSurface", 48);
            report.addProperty("observationConditions", "clouds OFF, time noon, clear weather; identical for all seeds, test worlds only");
            report.addProperty("dimension", TerrainTestDimension.LEVEL.identifier().toString());
            report.addProperty("method", "independent native server A, graceful reopen A, reverse-order independent native server B");
            report.addProperty("performanceBoundary", "observed test machine timings, including support generation and tick waits; not a throughput guarantee");
            JsonArray results = new JsonArray(); report.add("seeds", results);
            for (int index = 0; index < SEEDS.length; index++) {
                results.add(runSeed(context, root, output, index, SEEDS[index]));
                Files.writeString(output.resolve("validation.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
            }
            // Dedicated B has no connection, so world creation can leave its previous menu open.
            // Explicitly restore the real title screen after every world context has stopped.
            context.waitFor(c -> c.level == null && c.player == null);
            context.setScreen(TitleScreen::new);
            context.waitForScreen(TitleScreen.class);
            context.waitTick();
            TerrainProbe.require(context.computeOnClient(c -> c.gui.screen() instanceof TitleScreen && c.level == null),
                    "The terrain suite must finish disconnected on the title screen");
            report.addProperty("finishedOnTitleScreen", true);
            report.addProperty("passed", true);
            Files.writeString(output.resolve("validation.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
            System.out.println("Riichicraft terrain: three fixed seeds, native FULL material/biome checks, A/B order, graceful reopen, new generation and real TCP screenshots passed");
        } catch (IOException failure) {
            throw new AssertionError("Could not write project-local terrain evidence", failure);
        }
    }

    private static JsonObject runSeed(ClientGameTestContext context, Path root, Path output, int index, long seed) throws IOException {
        Properties aProperties = properties(context, root, "riichicraft-terrain-seed-" + index + "-a");
        Properties bProperties = properties(context, root, "riichicraft-terrain-seed-" + index + "-b");
        JsonObject result = new JsonObject(); result.addProperty("seed", seed);
        result.addProperty("seedText", Long.toString(seed));
        result.addProperty("worldA", aProperties.getProperty("level-name")); result.addProperty("worldB", bProperties.getProperty("level-name"));
        Map<ChunkPos, TerrainProbe.Measured> original = new LinkedHashMap<>();
        List<Double> timings = new ArrayList<>(); JsonArray chunkReports = new JsonArray(), screenshots = new JsonArray();
        TerrainProbe.Forecast forecast;
        // The builder writes level.dat before native startup. setSeed, not server.properties, sets its actual seed.
        try (TestDedicatedServerContext a = context.worldBuilder().adjustSettings(ui -> ui.setSeed(Long.toString(seed))).createServer(aProperties)) {
            a.runOnServer(s -> TerrainProbe.require(level(s).getSeed() == seed, "Native world A must use its explicit seed"));
            long forecastStarted = System.nanoTime();
            forecast = a.computeOnServer(s -> TerrainProbe.forecast(level(s)));
            forecast.report().addProperty("serverThreadForecastWallMilliseconds", (System.nanoTime() - forecastStarted) / 1_000_000.0);
            result.add("prediction", forecast.report());
            ImageIO.write(forecast.map(), "PNG", output.resolve("seed-" + index + "-biome-prediction.png").toFile());
            ImageIO.write(forecast.heightMap(), "PNG", output.resolve("seed-" + index + "-height-prediction.png").toFile());
            for (ChunkPos pos : forecast.chunks()) {
                Sample sample = sample(a, pos); original.put(pos, sample.measured()); timings.add(sample.milliseconds());
                JsonObject value = sample.measured().report(); value.addProperty("initialFullRequestMilliseconds", sample.milliseconds()); chunkReports.add(value);
            }
            result.addProperty("fixedOriginGridChunks", 16); result.addProperty("extraPredictionSelectedChunks", forecast.chunks().size() - 16);
            result.add("initialChunks", chunkReports); result.add("continuity", TerrainProbe.continuity(original));
            int minimum = original.values().stream().mapToInt(TerrainProbe.Measured::minHeight).min().orElseThrow();
            int maximum = original.values().stream().mapToInt(TerrainProbe.Measured::maxHeight).max().orElseThrow();
            TerrainProbe.require(maximum - minimum >= 24, "Actual FULL chunks must include meaningful low/high terrain variation");
            result.addProperty("measuredMinimumTopY", minimum); result.addProperty("measuredMaximumTopY", maximum);
            Map<String, Integer> seen = new LinkedHashMap<>();
            original.values().forEach(v -> v.surfaceBiomes().forEach((id, count) -> seen.merge(id, count, Integer::sum)));
            TerrainProbe.require(seen.keySet().containsAll(TerrainProbe.BIOMES), "Real generated surface columns must encounter every biome, not merely a source prediction");
            JsonObject counts = new JsonObject(); seen.forEach(counts::addProperty); result.add("actualSurfaceBiomeColumns", counts);
            try (TestDedicatedServerConnection connection = a.connect()) {
                connection.waitForChunksRender();
                commandAndScreenshots(context, a, connection, index, forecast, screenshots);
                result.addProperty("survivalTravelPassed", true);
                result.add("exploration", TerrainExplorationProbe.run(context, a, connection));
            }
            a.runCommand("save-all flush");
        }
        Path savedWorld = Path.of("").toAbsolutePath().normalize().resolve(aProperties.getProperty("level-name"));
        TerrainProbe.require(Files.isRegularFile(savedWorld.resolve("level.dat")), "Graceful native shutdown must leave the same world on disk");
        TerrainProbe.require(Files.isDirectory(savedWorld.resolve("dimensions/riichicraft/terrain_test/region")), "Native test dimension must have actual saved region files");
        // createServer deletes its world; the pinned native starter reopens the very same level.dat and regions.
        for (ChunkPos later : LATER_CHUNKS) TerrainProbe.require(!Files.exists(savedWorld.resolve("dimensions/riichicraft/terrain_test/region")
                .resolve("r." + (later.x() >> 5) + "." + (later.z() >> 5) + ".mca")), "Continuation region must be genuinely ungenerated before reopen");
        try (TestDedicatedServerContext reopened = new TestDedicatedServerContextImpl(context, DedicatedServerImplUtil.start(context, aProperties))) {
            reopened.runOnServer(s -> TerrainProbe.require(level(s).getSeed() == seed, "Reopened native world must preserve its seed"));
            for (var entry : original.entrySet()) {
                Sample sample = sample(reopened, entry.getKey());
                TerrainProbe.require(sample.measured().hash().equals(entry.getValue().hash()), "Saved FULL terrain and biome palette must survive graceful reopen: " + entry.getKey());
            }
            JsonArray later = new JsonArray();
            for (ChunkPos pos : LATER_CHUNKS) {
                TerrainProbe.require(!original.containsKey(pos), "Continuation coordinates must be outside the original fixed target set");
                Sample sample = sample(reopened, pos); original.put(pos, sample.measured()); timings.add(sample.milliseconds());
                JsonObject value = sample.measured().report(); value.addProperty("newFullRequestMilliseconds", sample.milliseconds()); later.add(value);
            }
            result.add("generatedAfterReopen", later);
            try (TestDedicatedServerConnection connection = reopened.connect()) {
                observationSettings(context, reopened);
                connection.waitForChunksRender(1200);
                TerrainProbe.require(context.computeOnClient(c -> c.level.dimension().equals(TerrainTestDimension.LEVEL)),
                        "Native playerdata must retain the test dimension across an actual graceful server restart");
                reopened.runCommand("execute as @a run riichicraft terrain leave"); sync(context, connection);
                TerrainProbe.require(context.computeOnClient(c -> c.level.dimension().equals(Level.OVERWORLD)),
                        "After restart, leave without an in-memory return point must use the Overworld fallback");
                reopened.runOnServer(s -> {
                    var player = connection.getServerPlayer();
                    TerrainProbe.require(player.level().dimension().equals(Level.OVERWORLD)
                                    && player.blockPosition().closerThan(s.overworld().getRespawnData().pos(), 24),
                            "Fallback must land near the safe Overworld spawn, not a stale pre-restart return point");
                    TerrainProbe.require(s.overworld().noCollision(player) && s.overworld().getFluidState(player.blockPosition()).isEmpty()
                                    && !s.overworld().getBlockState(player.blockPosition().below()).isAir(),
                            "Fallback landing must have clear body space, dry feet and a supporting surface");
                });
                connection.waitForChunksRender(1200); aimAndVerify(context, connection, 35, 12);
                context.runOnClient(c -> { c.gui.toastManager().clear(); c.gui.hud.getChat().clearMessages(true); });
                String name = "riichicraft-terrain-seed-" + index + "-reopened-leave";
                Path screenshot = context.takeScreenshot(name);
                JsonObject shot = TerrainProbe.point(reopened.computeOnServer(s -> connection.getServerPlayer().blockPosition()));
                shot.addProperty("name", name); shot.addProperty("path", screenshot.toString()); shot.addProperty("yaw", 35); shot.addProperty("pitch", 12);
                shot.add("actualCamera", cameraMetadata(context)); screenshots.add(shot);
            }
            result.addProperty("restartWithoutReturnPointFallback", true); reopened.runCommand("save-all flush");
        }
        List<ChunkPos> reverse = new ArrayList<>(original.keySet()); Collections.reverse(reverse);
        try (TestDedicatedServerContext b = context.worldBuilder().adjustSettings(ui -> ui.setSeed(Long.toString(seed))).createServer(bProperties)) {
            b.runOnServer(s -> TerrainProbe.require(level(s).getSeed() == seed, "Independent B must use the same actual seed"));
            for (ChunkPos pos : reverse) {
                Sample sample = sample(b, pos); timings.add(sample.milliseconds());
                TerrainProbe.require(sample.measured().hash().equals(original.get(pos).hash()), "Native FULL block/palette hash must not depend on request order or prior restart: " + pos);
            }
        }
        result.add("screenshots", screenshots); result.add("observedFullRequestTimings", timings(timings));
        result.addProperty("reopenOldChunkHashesEqual", true); result.addProperty("reverseGenerationOrderHashesEqual", true);
        result.addProperty("comparedFullChunks", original.size()); result.addProperty("passed", true);
        System.out.println("Riichicraft terrain seed " + seed + ": " + original.size() + " FULL chunk hashes stable; actual top Y "
                + result.get("measuredMinimumTopY") + ".." + result.get("measuredMaximumTopY"));
        return result;
    }

    private static Sample sample(TestDedicatedServerContext server, ChunkPos pos) {
        long started = System.nanoTime();
        var future = server.computeOnServer(s -> level(s).getChunkSource().getChunkFuture(pos.x(), pos.z(), ChunkStatus.FULL, true));
        // Let native task queues and server ticks progress. Never join an incomplete FULL future on the server thread.
        server.waitFor(s -> future.isDone(), 1200);
        double milliseconds = (System.nanoTime() - started) / 1_000_000.0;
        long diagnosticStarted = System.nanoTime();
        TerrainProbe.Measured measured = server.computeOnServer(s -> TerrainProbe.measure(level(s),
                future.join().orElseThrow(() -> new AssertionError("Native FULL generation failed at " + pos))));
        measured.report().addProperty("serverThreadFullColumnDiagnosticMilliseconds", (System.nanoTime() - diagnosticStarted) / 1_000_000.0);
        return new Sample(measured, milliseconds);
    }

    private static void commandAndScreenshots(ClientGameTestContext context, TestDedicatedServerContext server,
            TestDedicatedServerConnection connection, int index, TerrainProbe.Forecast forecast, JsonArray shots) {
        observationSettings(context, server);
        // Exercise the normal survival path before using spectator solely as the observation camera.
        server.runCommand("gamemode survival @a"); sync(context, connection);
        BlockPos survivalReturn = server.computeOnServer(s -> connection.getServerPlayer().blockPosition());
        server.runCommand("execute as @a run riichicraft terrain enter"); sync(context, connection);
        verifySurvivalLanding(context, server, connection, TerrainTestDimension.LEVEL);
        server.runCommand("execute as @a run riichicraft terrain leave"); sync(context, connection);
        verifySurvivalLanding(context, server, connection, Level.OVERWORLD);
        server.runOnServer(s -> TerrainProbe.require(connection.getServerPlayer().blockPosition().closerThan(survivalReturn, 3),
                "Survival leave must retain the original supported return position"));
        server.runCommand("gamemode spectator @a");
        BlockPos returnPosition = server.computeOnServer(s -> connection.getServerPlayer().blockPosition());
        server.runCommand("execute as @a run riichicraft terrain enter"); sync(context, connection);
        TerrainProbe.require(context.computeOnClient(c -> c.level.dimension().equals(TerrainTestDimension.LEVEL)), "Actual command must move the TCP client into the independent test dimension");
        server.runOnServer(s -> TerrainProbe.require(connection.getServerPlayer().level().dimension().equals(TerrainTestDimension.LEVEL), "Server identity must enter the actual registered dimension"));
        server.runCommand("execute as @a run riichicraft terrain enter"); sync(context, connection);
        TerrainProbe.require(context.computeOnClient(c -> c.level.dimension().equals(TerrainTestDimension.LEVEL)), "Repeated enter is rejected without leaving or replacing the original return point");
        server.runCommand("execute as @a run riichicraft terrain leave"); sync(context, connection);
        TerrainProbe.require(context.computeOnClient(c -> c.level.dimension().equals(Level.OVERWORLD)), "Leave command must return the real client to its source dimension");
        server.runOnServer(s -> TerrainProbe.require(connection.getServerPlayer().blockPosition().closerThan(returnPosition, 3), "Leave must retain the original safe return location"));
        server.runCommand("execute as @a run riichicraft terrain enter"); sync(context, connection);
        sample(server, new ChunkPos(0, 0));
        int originY = server.computeOnServer(s -> level(s).getChunkSource().getChunkNow(0, 0).getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, 8, 8));
        photo(context, server, connection, index, "origin-overhead", new BlockPos(8, originY + 40, 8), 0, 90, shots);
        photo(context, server, connection, index, "origin-ground", new BlockPos(8, originY + 3, 8), -35, 7, shots);
        BlockPos boundary = forecast.boundaryPoint();
        Sample crossing = sample(server, TerrainProbe.chunkAt(boundary));
        int boundaryY = crossing.measured().heights()[(boundary.getZ() & 15) * 16 + (boundary.getX() & 15)];
        photo(context, server, connection, index, "boundary-overhead", new BlockPos(boundary.getX(), boundaryY + 48, boundary.getZ()), 0, 90, shots);
        // The fixed height survey selected these two targets; camera Y is measured from native FULL blocks.
        for (String field : List.of("estimatedLow", "estimatedHigh")) {
            JsonObject estimated = forecast.report().getAsJsonObject(field);
            BlockPos target = new BlockPos(estimated.get("x").getAsInt(), 0, estimated.get("z").getAsInt());
            TerrainProbe.require(forecast.chunks().contains(TerrainProbe.chunkAt(target)),
                    "Shape screenshots must reuse an already planned FULL target, not add another sampling grid");
            Sample actualShape = sample(server, TerrainProbe.chunkAt(target));
            int surfaceY = actualShape.measured().heights()[(target.getZ() & 15) * 16 + (target.getX() & 15)];
            boolean low = field.equals("estimatedLow");
            photo(context, server, connection, index, low ? "lowland-ground" : "highland-ground",
                    new BlockPos(target.getX(), surfaceY + 2, target.getZ()), low ? -35 : 35, low ? 5 : 12, shots);
            JsonObject shot = shots.get(shots.size() - 1).getAsJsonObject();
            shot.addProperty("selection", "fixed 33x33 height prediction " + field);
            shot.addProperty("actualFullSurfaceY", surfaceY);
        }
        for (int biome = 0; biome < forecast.regionPoints().size(); biome++) {
            int regionIndex = biome;
            BlockPos near = forecast.regionPoints().get(biome);
            sample(server, TerrainProbe.chunkAt(near));
            BlockPos actual = server.computeOnServer(s -> {
                var chunk = level(s).getChunkSource().getChunkNow(near.getX() >> 4, near.getZ() >> 4);
                TerrainProbe.require(chunk != null, "Every photographed region must already have a measured FULL chunk");
                for (int z = 1; z < 15; z++) for (int x = 1; x < 15; x++) {
                    int wx = chunk.getPos().getMinBlockX() + x, wz = chunk.getPos().getMinBlockZ() + z;
                    int y = chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z);
                    BlockPos pos = new BlockPos(wx, y, wz);
                    if (TerrainProbe.biomeId(level(s).getBiome(pos)).equals(TerrainProbe.BIOMES.get(regionIndex)) && TerrainProbe.biomeId(level(s).getBiome(pos.above(2))).equals(TerrainProbe.BIOMES.get(regionIndex))) return pos.above(2);
                }
                throw new AssertionError("Predicted region probe must contain a matching actual biome");
            });
            photo(context, server, connection, index, "region-" + (char) ('a' + biome) + "-ground", actual, 35, 9, shots);
            String clientBiome = context.computeOnClient(c -> TerrainProbe.biomeId(c.level.getBiome(c.player.blockPosition())));
            TerrainProbe.require(clientBiome.equals(TerrainProbe.BIOMES.get(biome)), "Actual TCP client biome lookup must identify the photographed registered region");
            server.runCommand("execute in riichicraft:terrain_test positioned " + actual.getX() + " " + actual.getY() + " " + actual.getZ()
                    + " if biome ~ ~ ~ " + TerrainProbe.BIOMES.get(biome) + " run say terrain-biome-query-confirmed");
        }
        DebugScreenEntryStatus previousBiomeStatus = context.computeOnClient(c -> c.debugEntries.getStatus(DebugScreenEntries.BIOME));
        // The native setter saves only this guarded project's client run/debug-profile.json.
        // Minecraft 26.2's default simplified F3 profile does not include the biome line.
        context.runOnClient(c -> c.debugEntries.setStatus(DebugScreenEntries.BIOME, DebugScreenEntryStatus.IN_OVERLAY));
        context.getInput().pressKey(GLFW.GLFW_KEY_F3); context.waitTicks(2);
        TerrainProbe.require(context.computeOnClient(c -> c.getDebugOverlay().showDebugScreen()
                        && c.debugEntries.isCurrentlyEnabled(DebugScreenEntries.BIOME)),
                "Vanilla debug overlay must enable its native biome entry in the registered dimension");
        List<String> biomeLines = context.computeOnClient(TerrainPrototypeClientGameTest::nativeBiomeDebugLines);
        String debugBiome = context.computeOnClient(c -> TerrainProbe.biomeId(c.level.getBiome(c.getCameraEntity().blockPosition())));
        TerrainProbe.require(biomeLines.stream().anyMatch(line -> line.contains(debugBiome)),
                "The native F3 biome renderer must produce the actual client's registered biome: " + biomeLines);
        System.out.println("Riichicraft native F3 biome entry: " + biomeLines);
        context.takeScreenshot("riichicraft-terrain-seed-" + index + "-debug-biome");
        context.getInput().pressKey(GLFW.GLFW_KEY_F3);
        context.runOnClient(c -> c.debugEntries.setStatus(DebugScreenEntries.BIOME, previousBiomeStatus));
        server.runCommand("execute as @a run riichicraft terrain leave"); sync(context, connection);
        server.runCommand("execute as @a run riichicraft terrain enter"); sync(context, connection);
        TerrainProbe.require(context.computeOnClient(c -> c.level.dimension().equals(TerrainTestDimension.LEVEL)),
                "The player must disconnect inside the test dimension so native restart can verify fallback leave");
    }

    /** Collects the same native entry called by DebugScreenOverlay, without replacing its text. */
    private static List<String> nativeBiomeDebugLines(Minecraft client) {
        TerrainProbe.require(client.level != null && client.getCameraEntity() != null,
                "Native biome debug output requires a loaded client world and camera entity");
        var entry = DebugScreenEntries.getEntry(DebugScreenEntries.BIOME);
        TerrainProbe.require(entry instanceof DebugEntryBiome, "The enabled biome renderer must be the vanilla entry");
        List<String> lines = new ArrayList<>();
        DebugScreenDisplayer collector = new DebugScreenDisplayer() {
            @Override public void addPriorityLine(String line) { lines.add(line); }
            @Override public void addLine(String line) { lines.add(line); }
            @Override public void addToGroup(Identifier group, Collection<String> groupLines) { lines.addAll(groupLines); }
            @Override public void addToGroup(Identifier group, String line) { lines.add(line); }
        };
        // DebugEntryBiome samples the actual camera and ClientLevel; remote TCP has no server chunk.
        entry.display(collector, client.level, client.level.getChunkAt(client.getCameraEntity().blockPosition()), null);
        return List.copyOf(lines);
    }

    private static void photo(ClientGameTestContext context, TestDedicatedServerContext server, TestDedicatedServerConnection connection,
            int index, String label, BlockPos camera, float yaw, float pitch, JsonArray shots) {
        server.runCommand("execute in riichicraft:terrain_test run tp @a " + (camera.getX() + 0.5) + " " + camera.getY() + " " + (camera.getZ() + 0.5) + " " + yaw + " " + pitch);
        sync(context, connection); connection.waitForChunksRender(1200);
        context.runOnClient(c -> { c.gui.toastManager().clear(); c.gui.hud.getChat().clearMessages(true); });
        aimAndVerify(context, connection, yaw, pitch);
        String name = "riichicraft-terrain-seed-" + index + "-" + label;
        Path screenshot = context.takeScreenshot(name);
        JsonObject shot = TerrainProbe.point(camera); shot.addProperty("yaw", yaw); shot.addProperty("pitch", pitch);
        shot.addProperty("name", name); shot.addProperty("path", screenshot.toString()); shot.add("actualCamera", cameraMetadata(context)); shots.add(shot);
    }

    private static void verifySurvivalLanding(ClientGameTestContext context, TestDedicatedServerContext server,
            TestDedicatedServerConnection connection, net.minecraft.resources.ResourceKey<Level> expectedDimension) {
        TerrainProbe.require(context.computeOnClient(c -> c.level.dimension().equals(expectedDimension)
                        && c.gameMode.getPlayerMode() == GameType.SURVIVAL),
                "Real TCP enter/leave must preserve survival mode and reach the requested dimension");
        server.runOnServer(s -> {
            var player = connection.getServerPlayer();
            TerrainProbe.require(player.level().dimension().equals(expectedDimension)
                            && player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL,
                    "Server enter/leave must preserve survival mode");
            TerrainProbe.require(player.level().noCollision(player) && player.level().getFluidState(player.blockPosition()).isEmpty()
                            && !player.level().getBlockState(player.blockPosition().below()).isAir(),
                    "Survival arrival must have clear body space, dry feet, and a supporting surface");
        });
    }

    private static void aimAndVerify(ClientGameTestContext context, TestDedicatedServerConnection connection, float yaw, float pitch) {
        context.getInput().lookAt(yaw, pitch);
        context.runOnClient(c -> {
            c.options.setCameraType(CameraType.FIRST_PERSON);
            c.setCameraEntity(c.player);
            // The input helper only sets current rotation; match the previous values so render interpolation is settled.
            c.player.yRotO = yaw; c.player.xRotO = pitch;
        });
        context.waitTicks(3); connection.waitForServerboundPackets();
        double expectedForwardY = -Math.sin(Math.toRadians(pitch));
        context.waitFor(c -> c.options.getCameraType() == CameraType.FIRST_PERSON && c.getCameraEntity() == c.player
                && Math.abs(c.player.getXRot() - pitch) < 0.05
                && Math.abs(c.gameRenderer.mainCamera().xRot() - pitch) < 0.05
                && Math.abs(c.gameRenderer.mainCamera().forwardVector().y() - expectedForwardY) < 0.02);
        TerrainProbe.require(context.computeOnClient(c -> Math.abs(c.gameRenderer.mainCamera().xRot() - pitch) < 0.05
                        && (pitch != 90 || c.gameRenderer.mainCamera().forwardVector().y() < -0.999)),
                "The actual native render camera must face the requested pitch; overhead must face down");
    }

    private static JsonObject cameraMetadata(ClientGameTestContext context) {
        return context.computeOnClient(c -> {
            var camera = c.gameRenderer.mainCamera(); JsonObject value = new JsonObject();
            value.addProperty("perspective", c.options.getCameraType().name()); value.addProperty("playerPitch", c.player.getXRot());
            value.addProperty("cameraPitch", camera.xRot()); value.addProperty("cameraYaw", camera.yRot());
            value.addProperty("forwardY", camera.forwardVector().y()); value.addProperty("x", camera.position().x);
            value.addProperty("y", camera.position().y); value.addProperty("z", camera.position().z); return value;
        });
    }
    private static void observationSettings(ClientGameTestContext context, TestDedicatedServerContext server) {
        // Runtime-only client option; no options.save() and no production dimension data changes.
        context.runOnClient(c -> c.options.cloudStatus().set(CloudStatus.OFF));
        server.runCommand("execute in riichicraft:terrain_test run time set noon");
        server.runCommand("execute in riichicraft:terrain_test run weather clear");
        server.runCommand("execute in minecraft:overworld run weather clear");
    }
    private static void sync(ClientGameTestContext context, TestDedicatedServerConnection connection) { context.waitTicks(2); connection.waitForClientboundPackets(); }
    private static ServerLevel level(MinecraftServer server) {
        ServerLevel level = server.getLevel(TerrainTestDimension.LEVEL);
        TerrainProbe.require(level != null, "Native dynamic registry must create the independent terrain test dimension"); return level;
    }
    private static Path projectRoot(ClientGameTestContext context) {
        String property = System.getProperty("riichicraft.test.projectRoot");
        TerrainProbe.require(property != null && !property.isBlank(), "Project root guard is required");
        Path root = Path.of(property).toAbsolutePath().normalize(), cwd = Path.of("").toAbsolutePath().normalize();
        Path client = context.computeOnClient(c -> c.gameDirectory.toPath().toAbsolutePath().normalize());
        TerrainProbe.require(cwd.equals(client) && cwd.startsWith(root) && !cwd.equals(root), "Terrain tests must run in a child run directory of this project"); return root;
    }
    private static Properties properties(ClientGameTestContext context, Path root, String name) throws IOException {
        Path cwd = Path.of("").toAbsolutePath().normalize(), world = cwd.resolve(name).normalize();
        TerrainProbe.require(cwd.startsWith(root) && world.getParent().equals(cwd) && !Files.isSymbolicLink(world), "Native test cleanup must stay in its fixed project-local world child");
        Properties eula = new Properties();
        try (Reader reader = Files.newBufferedReader(cwd.resolve("eula.txt"))) { eula.load(reader); }
        TerrainProbe.require(Boolean.parseBoolean(eula.getProperty("eula", "false")), "Authorized local test EULA must already be accepted");
        Properties properties = new Properties(); properties.setProperty("level-name", name); properties.setProperty("server-ip", "127.0.0.1");
        properties.setProperty("max-players", "1"); properties.setProperty("view-distance", "5"); properties.setProperty("simulation-distance", "5");
        try (ServerSocket port = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { properties.setProperty("server-port", Integer.toString(port.getLocalPort())); }
        return properties;
    }
    private static JsonObject timings(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values); Collections.sort(sorted); JsonObject out = new JsonObject();
        out.addProperty("requests", sorted.size()); out.addProperty("medianMilliseconds", sorted.get(sorted.size() / 2));
        out.addProperty("p95Milliseconds", sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * 0.95) - 1)));
        out.addProperty("maximumMilliseconds", sorted.getLast()); return out;
    }
}
