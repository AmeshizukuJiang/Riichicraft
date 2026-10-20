package dev.riichicraft.worldgen;

import dev.riichicraft.Riichicraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

/** Stable identifiers shared by the terrain prototype, commands, and validation. */
public final class TerrainTestDimension {
    public static final ResourceKey<Level> LEVEL = ResourceKey.create(Registries.DIMENSION, Riichicraft.id("terrain_test"));
    public static final ResourceKey<Biome> REGION_A = biome("test_region_a");
    public static final ResourceKey<Biome> REGION_B = biome("test_region_b");
    public static final ResourceKey<Biome> REGION_C = biome("test_region_c");

    private TerrainTestDimension() {}

    private static ResourceKey<Biome> biome(String path) {
        return ResourceKey.create(Registries.BIOME, Riichicraft.id(path));
    }
}
