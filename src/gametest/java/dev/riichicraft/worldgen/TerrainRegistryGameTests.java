package dev.riichicraft.worldgen;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.riichicraft.Riichicraft;
import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

/** Real data-pack definitions and dynamic biomes, independent of their placeholder colours. */
public final class TerrainRegistryGameTests {
    @GameTest
    public void terrainDefinitionUsesThreeRegisteredEmptyBiomes(GameTestHelper helper) throws IOException {
        var server = helper.getLevel().getServer();
        var registries = server.registryAccess();
        var resource = server.getResourceManager().getResourceOrThrow(Riichicraft.id("dimension/terrain_test.json"));
        LevelStem definition;
        // GameTestServer bakes FLAT against an empty LEVEL_STEM registry, ignoring
        // datapackDimensions. Decode the shipped resource against its real WORLDGEN
        // registries here; actual level creation and travel use the dedicated suite.
        try (var reader = resource.openAsReader()) {
            definition = LevelStem.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, registries),
                    JsonParser.parseReader(reader)).getOrThrow();
        }
        var generator = definition.generator();
        helper.assertTrue(generator instanceof NoiseBasedChunkGenerator, "Terrain uses the native noise chunk generator");
        helper.assertTrue(generator.getBiomeSource() instanceof MultiNoiseBiomeSource, "Regions use the native multi-noise biome source");
        var biomes = generator.getBiomeSource().possibleBiomes();
        var keys = biomes.stream().map(biome -> biome.unwrapKey().orElseThrow()).collect(Collectors.toSet());
        helper.assertTrue(keys.equals(Set.of(TerrainTestDimension.REGION_A, TerrainTestDimension.REGION_B, TerrainTestDimension.REGION_C)),
                "All three region identities are registered and selected, rather than one recoloured biome");
        var biomeRegistry = registries.lookupOrThrow(Registries.BIOME);
        for (var biome : biomes) {
            helper.assertTrue(biomeRegistry.getValueOrThrow(biome.unwrapKey().orElseThrow()) == biome.value(),
                    "The dimension references the actual dynamically registered biome");
            var settings = biome.value().getGenerationSettings();
            helper.assertTrue(!settings.getCarvers().iterator().hasNext() && settings.features().stream().allMatch(stage -> stage.size() == 0),
                    "Prototype biomes have no carvers or placed features");
        }
        helper.assertTrue(generator.getMinY() == 0 && generator.getGenDepth() == 256 && generator.getSeaLevel() == 0,
                "The isolated prototype has its own bounded dry terrain settings");
        helper.assertTrue(definition.type().value().minY() == 0 && definition.type().value().height() == 256,
                "Dimension bounds match the terrain generator");
        helper.succeed();
    }
}
