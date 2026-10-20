package dev.riichicraft.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/** A bounded, diagnostic-free normal player chunk-loading observation. Not a long-run TPS/FPS benchmark. */
final class TerrainExplorationProbe {
    private static final int START_X = 8192, START_Z = 8192, Y = 220, STEP = 32, STEPS = 16;
    private static final Map<MinecraftServer, TickWindow> ACTIVE = new ConcurrentHashMap<>();
    private static boolean installed;

    private static final class TickWindow {
        final List<Double> executionMilliseconds = new ArrayList<>();
        long started;
    }

    static JsonObject run(ClientGameTestContext context, TestDedicatedServerContext server,
            TestDedicatedServerConnection connection) {
        installListeners();
        Path project = Path.of(System.getProperty("riichicraft.test.projectRoot")).toAbsolutePath().normalize();
        Path world = server.computeOnServer(s -> s.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize());
        TerrainProbe.require(world.startsWith(project), "Exploration evidence must inspect only its project-local test world");
        Path initialRegion = world.resolve("dimensions/riichicraft/terrain_test/region/r.16.16.mca");
        TerrainProbe.require(!Files.exists(initialRegion), "The fixed exploration route must begin in an unsaved, ungenerated region");
        server.runOnServer(s -> TerrainProbe.require(s.getLevel(TerrainTestDimension.LEVEL).getChunkSource()
                .getChunkNow(START_X >> 4, START_Z >> 4) == null, "The route's initial FULL chunk must not already be loaded"));
        JsonObject report = new JsonObject();
        report.addProperty("method", "normal player loading after fixed 32-block teleports; no biome forecast or full-column diagnostics in this window");
        report.addProperty("boundary", "finite local-machine route observation; server tick execution differs from wall interval and does not include all asynchronous generation; not sustained TPS/FPS");
        report.addProperty("startX", START_X); report.addProperty("startZ", START_Z); report.addProperty("cameraFeetY", Y);
        report.addProperty("stepBlocks", STEP); report.addProperty("plannedSteps", STEPS);
        report.addProperty("initialRegionFileAbsent", true);
        TickWindow window = server.computeOnServer(s -> {
            TickWindow fresh = new TickWindow();
            TerrainProbe.require(ACTIVE.putIfAbsent(s, fresh) == null, "Only one exploration timing window may own a server");
            return fresh;
        });
        JsonArray segments = new JsonArray(); List<Double> times = new ArrayList<>();
        try {
            report.addProperty("initialLoadMilliseconds", moveAndRender(context, server, connection, START_X));
            for (int step = 1; step <= STEPS; step++) {
                int x = START_X + step * STEP;
                double milliseconds = moveAndRender(context, server, connection, x); times.add(milliseconds);
                JsonObject segment = new JsonObject(); segment.addProperty("step", step); segment.addProperty("x", x);
                segment.addProperty("z", START_Z); segment.addProperty("downloadAndRenderMilliseconds", milliseconds); segments.add(segment);
            }
        } finally {
            // The callbacks remain registered once, but retain no server or measurements outside this active window.
            server.runOnServer(s -> ACTIVE.remove(s));
        }
        TerrainProbe.require(!window.executionMilliseconds.isEmpty(), "The route must observe actual native server tick callbacks");
        report.add("segments", segments); report.addProperty("completedSteps", times.size());
        report.add("segmentTiming", statistics(times)); report.add("serverTickExecution", statistics(window.executionMilliseconds));
        long longTicks = window.executionMilliseconds.stream().filter(ms -> ms > 2000).count();
        long longSegments = times.stream().filter(ms -> ms > 2000).count();
        report.addProperty("ticksOverTwoSeconds", longTicks); report.addProperty("segmentsOverTwoSeconds", longSegments);
        report.addProperty("severeTickObserved", longTicks != 0);
        report.addProperty("observationCompleted", true);
        report.addProperty("assessment", longTicks == 0 ? "No server tick over two seconds observed in this fixed finite window"
                : "A server tick exceeded two seconds; investigate this recorded observation before claiming smooth exploration");
        System.out.println("Riichicraft terrain exploration: " + STEPS + " fixed steps, tick max "
                + report.getAsJsonObject("serverTickExecution").get("maximumMilliseconds") + " ms, ticks >2s " + longTicks);
        return report;
    }

    private static double moveAndRender(ClientGameTestContext context, TestDedicatedServerContext server,
            TestDedicatedServerConnection connection, int x) {
        long started = System.nanoTime();
        server.runCommand("execute in riichicraft:terrain_test run tp @a " + (x + 0.5) + " " + Y + " " + (START_Z + 0.5) + " -90 25");
        context.waitTicks(2); connection.waitForClientboundPackets();
        context.waitFor(c -> c.level.dimension().equals(TerrainTestDimension.LEVEL)
                && Math.abs(c.player.getX() - (x + 0.5)) < 1 && Math.abs(c.player.getZ() - (START_Z + 0.5)) < 1, 1200);
        connection.waitForChunksDownload(1200); connection.waitForChunksRender(1200);
        server.runOnServer(s -> TerrainProbe.require(s.getLevel(TerrainTestDimension.LEVEL).getChunkSource()
                .getChunkNow(x >> 4, START_Z >> 4) != null, "The moving real player must load its native FULL target chunk"));
        return (System.nanoTime() - started) / 1_000_000.0;
    }

    private static synchronized void installListeners() {
        if (installed) return;
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            TickWindow window = ACTIVE.get(server); if (window != null) window.started = System.nanoTime();
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            TickWindow window = ACTIVE.get(server);
            if (window != null && window.started != 0) {
                window.executionMilliseconds.add((System.nanoTime() - window.started) / 1_000_000.0); window.started = 0;
            }
        });
        installed = true;
    }

    private static JsonObject statistics(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values); Collections.sort(sorted); JsonObject result = new JsonObject();
        result.addProperty("count", sorted.size()); result.addProperty("medianMilliseconds", sorted.get(sorted.size() / 2));
        result.addProperty("p95Milliseconds", sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * 0.95) - 1)));
        result.addProperty("maximumMilliseconds", sorted.getLast()); return result;
    }
}
