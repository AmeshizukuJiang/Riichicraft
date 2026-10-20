package dev.riichicraft.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/** Read-only probes. Source predictions are kept separate from measurements of native FULL chunks. */
final class TerrainProbe {
    static final List<String> BIOMES = List.of("riichicraft:test_region_a", "riichicraft:test_region_b", "riichicraft:test_region_c");
    private static final List<Block> MATERIALS = List.of(Blocks.CONCRETE.pick(DyeColor.LIME), Blocks.CONCRETE.pick(DyeColor.ORANGE), Blocks.CONCRETE.pick(DyeColor.LIGHT_BLUE));
    private static final int[] COLORS = {0xFF79BD33, 0xFFE98B2C, 0xFF55ADCF};
    private static final int MAP_MIN = -2048, MAP_STEP = 32, MAP_SIZE = 129;

    record Forecast(JsonObject report, List<ChunkPos> chunks, List<BlockPos> regionPoints, BlockPos boundaryPoint, BufferedImage map, BufferedImage heightMap) { }
    record Measured(ChunkPos pos, String hash, int minHeight, int maxHeight, int[] heights,
            Map<String, Integer> surfaceBiomes, JsonObject report) { }

    static Forecast forecast(ServerLevel level) {
        var generator = level.getChunkSource().getGenerator();
        var sampler = level.getChunkSource().randomState().sampler();
        Set<String> registered = new LinkedHashSet<>();
        generator.getBiomeSource().possibleBiomes().forEach(b -> registered.add(biomeId(b)));
        require(registered.equals(new LinkedHashSet<>(BIOMES)), "Biome source must contain exactly the three test biomes");
        for (String id : BIOMES) require(level.registryAccess().lookupOrThrow(Registries.BIOME)
                .get(net.minecraft.resources.ResourceKey.create(Registries.BIOME, Identifier.parse(id))).isPresent(), "Dynamic biome registry must contain " + id);
        int[] cells = new int[MAP_SIZE * MAP_SIZE], counts = new int[3];
        BlockPos[] nearest = new BlockPos[3];
        BlockPos boundary = null;
        int edges = 0;
        BufferedImage image = new BufferedImage(MAP_SIZE, MAP_SIZE, BufferedImage.TYPE_INT_ARGB);
        for (int z = 0; z < MAP_SIZE; z++) for (int x = 0; x < MAP_SIZE; x++) {
            int wx = MAP_MIN + x * MAP_STEP, wz = MAP_MIN + z * MAP_STEP;
            int biome = BIOMES.indexOf(biomeId(generator.getBiomeSource().getNoiseBiome(
                    QuartPos.fromBlock(wx), QuartPos.fromBlock(128), QuartPos.fromBlock(wz), sampler)));
            require(biome >= 0, "Predicted biome must belong to registered test regions");
            cells[z * MAP_SIZE + x] = biome; counts[biome]++;
            BlockPos point = new BlockPos(wx, 128, wz);
            if (nearest[biome] == null || distance(point) < distance(nearest[biome])) nearest[biome] = point;
            image.setRGB(x, z, COLORS[biome]);
            if ((x > 0 && cells[z * MAP_SIZE + x - 1] != biome) || (z > 0 && cells[(z - 1) * MAP_SIZE + x] != biome)) {
                edges++;
                if (boundary == null || distance(point) < distance(boundary)) boundary = point;
            }
        }
        require(boundary != null, "Fixed 4 km survey must contain a biome boundary");
        JsonObject report = new JsonObject();
        report.addProperty("kind", "biome-source prediction; not generated blocks");
        report.addProperty("minimumCoordinate", MAP_MIN); report.addProperty("maximumCoordinate", -MAP_MIN);
        report.addProperty("step", MAP_STEP); report.addProperty("samples", cells.length);
        report.addProperty("differentNeighbourCells", edges);
        JsonArray regions = new JsonArray();
        for (int i = 0; i < 3; i++) {
            require(counts[i] > 0, "Each fixed-seed survey must encounter all three biome identities");
            JsonObject region = new JsonObject();
            region.addProperty("biome", BIOMES.get(i)); region.addProperty("cells", counts[i]);
            region.addProperty("fraction", (double) counts[i] / cells.length);
            int[] parts = components(cells, i);
            region.addProperty("connectedComponents", parts[0]); region.addProperty("largestComponentCells", parts[1]);
            regions.add(region);
        }
        report.add("regions", regions);
        // Fixed, independent shape survey. It does not load or generate FULL chunks.
        HeightSurvey heightSurvey = heightSurvey(level);
        report.add("heightPrediction", heightSurvey.report());
        BlockPos low = heightSurvey.low(), high = heightSurvey.high();
        report.add("estimatedLow", point(low)); report.add("estimatedHigh", point(high)); report.add("boundaryProbe", point(boundary));
        Set<ChunkPos> targets = new LinkedHashSet<>();
        for (int z = -2; z < 2; z++) for (int x = -2; x < 2; x++) targets.add(new ChunkPos(x, z));
        for (BlockPos pos : nearest) targets.add(chunkAt(pos));
        targets.add(chunkAt(low)); targets.add(chunkAt(high));
        ChunkPos crossing = chunkAt(boundary);
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) targets.add(new ChunkPos(crossing.x() + dx, crossing.z() + dz));
        return new Forecast(report, List.copyOf(targets), List.of(nearest), boundary, image, heightSurvey.image());
    }

    private record HeightSurvey(JsonObject report, BlockPos low, BlockPos high, BufferedImage image) { }

    private static HeightSurvey heightSurvey(ServerLevel level) {
        long started = System.nanoTime();
        int size = 33, step = 128;
        int[] grid = new int[size * size];
        List<Integer> sorted = new ArrayList<>();
        List<Double> gradients = new ArrayList<>();
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        BlockPos low = null, high = null;
        for (int z = 0; z < size; z++) for (int x = 0; x < size; x++) {
            int wx = MAP_MIN + x * step, wz = MAP_MIN + z * step;
            int height = level.getChunkSource().getGenerator().getBaseHeight(wx, wz, Heightmap.Types.WORLD_SURFACE_WG,
                    level, level.getChunkSource().randomState());
            grid[z * size + x] = height; sorted.add(height);
            if (low == null || height < low.getY()) low = new BlockPos(wx, height, wz);
            if (high == null || height > high.getY()) high = new BlockPos(wx, height, wz);
            if (x > 0) gradients.add(Math.abs(height - grid[z * size + x - 1]) / (double) step);
            if (z > 0) gradients.add(Math.abs(height - grid[(z - 1) * size + x]) / (double) step);
            // Fixed 0..255 scale across seeds; no per-image contrast stretching.
            int shade = Math.clamp(height, 0, 255);
            image.setRGB(x, z, 0xFF000000 | (shade << 16) | (shade << 8) | shade);
        }
        java.util.Collections.sort(sorted); java.util.Collections.sort(gradients);
        JsonObject report = new JsonObject();
        report.addProperty("kind", "fixed-grid getBaseHeight prediction (first free Y); not actual FULL chunks or game screenshot");
        report.addProperty("minimumCoordinate", MAP_MIN); report.addProperty("maximumCoordinate", -MAP_MIN);
        report.addProperty("step", step); report.addProperty("samples", grid.length);
        report.addProperty("minimumPredictedY", sorted.getFirst()); report.addProperty("maximumPredictedY", sorted.getLast());
        report.addProperty("predictedHeightP10", sorted.get(sorted.size() / 10));
        report.addProperty("predictedHeightMedian", sorted.get(sorted.size() / 2));
        report.addProperty("predictedHeightP90", sorted.get(sorted.size() * 9 / 10));
        report.addProperty("neighbourGradients", gradients.size());
        report.addProperty("gradientMedian", gradients.get(gradients.size() / 2));
        report.addProperty("gradientP90", gradients.get(gradients.size() * 9 / 10));
        report.addProperty("gradientMaximum", gradients.getLast());
        for (double threshold : new double[] {0.02, 0.05, 0.1}) report.addProperty("gradientAtMost" + threshold + "Fraction",
                (double) gradients.stream().filter(v -> v <= threshold).count() / gradients.size());
        JsonArray bands = new JsonArray();
        for (int bottom = 0; bottom < 256; bottom += 32) {
            int from = bottom, until = bottom + 32;
            JsonObject band = new JsonObject(); band.addProperty("fromInclusive", from); band.addProperty("untilExclusive", until);
            band.addProperty("fraction", (double) sorted.stream().filter(y -> y >= from && y < until).count() / sorted.size()); bands.add(band);
        }
        report.add("heightBands", bands); report.addProperty("elapsedMilliseconds", (System.nanoTime() - started) / 1_000_000.0);
        return new HeightSurvey(report, low, high, image);
    }
    static Measured measure(ServerLevel level, ChunkAccess chunk) {
        MessageDigest hash = sha256();
        Map<Block, byte[]> blockEncodings = new HashMap<>();
        ChunkPos cp = chunk.getPos();
        int[] heights = new int[256];
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE, maxStep = 0;
        long solids = 0, air = 0, mapped = 0;
        Map<String, Integer> biomes = new LinkedHashMap<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int wx = cp.getMinBlockX() + x, wz = cp.getMinBlockZ() + z;
            int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            heights[z * 16 + x] = top; min = Math.min(min, top); max = Math.max(max, top);
            require(top > level.getMinY() && top < level.getMaxY() - 1, "Terrain must leave floor and sky at " + wx + "," + wz);
            require(!chunk.getBlockState(cursor.set(wx, top, wz)).isAir() && chunk.getBlockState(cursor.set(wx, top + 1, wz)).isAir(),
                    "FULL chunk heightmap must point to its actual top block");
            String surface = biomeId(level.getBiome(cursor.set(wx, top, wz)));
            require(BIOMES.contains(surface), "Actual surface has a registered test biome");
            biomes.merge(surface, 1, Integer::sum);
            for (int y = level.getMinY(); y <= level.getMaxY(); y++) {
                var state = chunk.getBlockState(cursor.set(wx, y, wz));
                hash.update(blockEncodings.computeIfAbsent(state.getBlock(), b -> (BuiltInRegistries.BLOCK.getKey(b).toString() + "\0").getBytes(StandardCharsets.UTF_8)));
                if ((x & 3) == 0 && (z & 3) == 0 && (y & 3) == 0) update(hash,
                        biomeId(chunk.getNoiseBiome(QuartPos.fromBlock(wx), QuartPos.fromBlock(y), QuartPos.fromBlock(wz))));
                if (state.isAir()) {
                    air++; require(y > top, "Initial heightfield must not contain caves");
                } else {
                    solids++; require(y <= top && state.getFluidState().isEmpty(), "Terrain is solid below its top and contains no fluid");
                    if (state.is(Blocks.BEDROCK)) require(y < level.getMinY() + 5, "Bedrock is only allowed in the bottom five layers");
                    else {
                        // SurfaceSystem uses BiomeManager's fuzzy boundary, not floor-divided quart cells.
                        String actualBiome = biomeId(level.getBiome(cursor));
                        int index = BIOMES.indexOf(actualBiome);
                        if (index < 0 || !state.is(MATERIALS.get(index))) throw new AssertionError("Solid block/biome mismatch at " + cursor + ": " + actualBiome + " / " + state);
                        mapped++;
                    }
                }
            }
            if (x > 0) maxStep = Math.max(maxStep, Math.abs(top - heights[z * 16 + x - 1]));
            if (z > 0) maxStep = Math.max(maxStep, Math.abs(top - heights[(z - 1) * 16 + x]));
        }
        require(maxStep <= 12, "Heightfield must not create vertical steps inside a chunk: " + maxStep);
        String digest = HexFormat.of().formatHex(hash.digest());
        JsonObject report = new JsonObject();
        report.addProperty("chunkX", cp.x()); report.addProperty("chunkZ", cp.z());
        report.addProperty("fullBlockAndBiomeSha256", digest); report.addProperty("minimumTopY", min); report.addProperty("maximumTopY", max);
        report.addProperty("maximumInteriorAdjacentStep", maxStep); report.addProperty("solidBlocksChecked", solids);
        report.addProperty("airBlocksChecked", air); report.addProperty("biomeMappedBlocksChecked", mapped);
        JsonObject surfaceCounts = new JsonObject(); biomes.forEach(surfaceCounts::addProperty); report.add("surfaceBiomeColumns", surfaceCounts);
        return new Measured(cp, digest, min, max, heights, Map.copyOf(biomes), report);
    }

    static JsonObject continuity(Map<ChunkPos, Measured> chunks) {
        List<Integer> within = new ArrayList<>(), borders = new ArrayList<>(), heights = new ArrayList<>();
        for (Measured measured : chunks.values()) {
            int[] h = measured.heights();
            for (int y : h) heights.add(y);
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                if (x > 0) within.add(Math.abs(h[z * 16 + x] - h[z * 16 + x - 1]));
                if (z > 0) within.add(Math.abs(h[z * 16 + x] - h[(z - 1) * 16 + x]));
            }
            Measured east = chunks.get(new ChunkPos(measured.pos().x() + 1, measured.pos().z()));
            Measured south = chunks.get(new ChunkPos(measured.pos().x(), measured.pos().z() + 1));
            for (int i = 0; i < 16; i++) {
                if (east != null) borders.add(Math.abs(h[i * 16 + 15] - east.heights()[i * 16]));
                if (south != null) borders.add(Math.abs(h[15 * 16 + i] - south.heights()[i]));
            }
        }
        require(!borders.isEmpty(), "Real sample must include adjacent FULL chunks");
        int maxBorder = borders.stream().mapToInt(Integer::intValue).max().orElseThrow();
        require(maxBorder <= 12, "Adjacent chunk edges must not introduce vertical walls: " + maxBorder);
        JsonObject result = new JsonObject();
        result.addProperty("interiorAdjacentPairs", within.size()); result.addProperty("chunkBorderAdjacentPairs", borders.size());
        result.addProperty("interiorMeanStep", within.stream().mapToInt(Integer::intValue).average().orElse(0));
        result.addProperty("chunkBorderMeanStep", borders.stream().mapToInt(Integer::intValue).average().orElse(0));
        result.addProperty("maximumChunkBorderStep", maxBorder);
        java.util.Collections.sort(heights);
        result.addProperty("heightP10", heights.get(heights.size() / 10));
        result.addProperty("heightMedian", heights.get(heights.size() / 2));
        result.addProperty("heightP90", heights.get(heights.size() * 9 / 10));
        List<Integer> all = new ArrayList<>(within); all.addAll(borders);
        for (int threshold : new int[] {1, 2, 4}) result.addProperty("adjacentStepAtMost" + threshold + "Fraction",
                (double) all.stream().filter(v -> v <= threshold).count() / all.size());
        result.addProperty("adjacentStepOver4Fraction", (double) all.stream().filter(v -> v > 4).count() / all.size());
        return result;
    }

    static ChunkPos chunkAt(BlockPos pos) { return new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4); }
    static JsonObject point(BlockPos pos) {
        JsonObject value = new JsonObject(); value.addProperty("x", pos.getX()); value.addProperty("y", pos.getY()); value.addProperty("z", pos.getZ()); return value;
    }
    static String biomeId(Holder<Biome> biome) { return biome.unwrapKey().orElseThrow().identifier().toString(); }
    static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static long distance(BlockPos pos) { return (long) pos.getX() * pos.getX() + (long) pos.getZ() * pos.getZ(); }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException failure) { throw new AssertionError(failure); }
    }
    private static void update(MessageDigest digest, String value) { digest.update(value.getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0); }
    private static int[] components(int[] cells, int wanted) {
        boolean[] visited = new boolean[cells.length]; int components = 0, largest = 0;
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int origin = 0; origin < cells.length; origin++) {
            if (visited[origin] || cells[origin] != wanted) continue;
            components++; int size = 0; queue.add(origin); visited[origin] = true;
            while (!queue.isEmpty()) {
                int cell = queue.removeFirst(); size++; int x = cell % MAP_SIZE, z = cell / MAP_SIZE;
                if (x > 0) enqueue(cells, visited, wanted, cell - 1, queue);
                if (x < MAP_SIZE - 1) enqueue(cells, visited, wanted, cell + 1, queue);
                if (z > 0) enqueue(cells, visited, wanted, cell - MAP_SIZE, queue);
                if (z < MAP_SIZE - 1) enqueue(cells, visited, wanted, cell + MAP_SIZE, queue);
            }
            largest = Math.max(largest, size);
        }
        return new int[] {components, largest};
    }
    private static void enqueue(int[] cells, boolean[] visited, int wanted, int next, ArrayDeque<Integer> queue) {
        if (!visited[next] && cells[next] == wanted) { visited[next] = true; queue.addLast(next); }
    }
}
