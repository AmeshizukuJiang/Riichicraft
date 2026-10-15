package dev.riichicraft.block;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

public final class ModBlocks {
    public static final MahjongTableBlock MAHJONG_TABLE = Registry.register(
            BuiltInRegistries.BLOCK,
            ModBlockIds.MAHJONG_TABLE.block(),
            new MahjongTableBlock(BlockBehaviour.Properties.of()
                    .setId(ModBlockIds.MAHJONG_TABLE.block())
                    .strength(2.0F)
                    .sound(SoundType.WOOD)
                    .noOcclusion()));

    private ModBlocks() {
    }

    public static void initialize() {
        Registry.register(BuiltInRegistries.ITEM, ModBlockIds.MAHJONG_TABLE.item(),
                new BlockItem(MAHJONG_TABLE, new Item.Properties()
                        .useBlockDescriptionPrefix()
                        .setId(ModBlockIds.MAHJONG_TABLE.item())));

        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS)
                .register(output -> output.accept(MAHJONG_TABLE.asItem()));
    }
}
