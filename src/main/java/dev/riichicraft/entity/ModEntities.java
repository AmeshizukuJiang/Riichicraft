package dev.riichicraft.entity;

import dev.riichicraft.Riichicraft;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public final class ModEntities {
    public static final EntityType<MahjongSeatEntity> MAHJONG_SEAT = Registry.register(
            BuiltInRegistries.ENTITY_TYPE, Riichicraft.id("mahjong_seat"),
            EntityType.Builder.<MahjongSeatEntity>of(MahjongSeatEntity::new, MobCategory.MISC)
                    // 26.2 refuses mounting a non-serializable EntityType; the instance vetoes saving.
                    .sized(0.1F, 0.1F).noSummon().noLootTable().fireImmune()
                    .clientTrackingRange(8).updateInterval(20)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, Riichicraft.id("mahjong_seat"))));
    private ModEntities() { }
    public static void initialize() { }
}
