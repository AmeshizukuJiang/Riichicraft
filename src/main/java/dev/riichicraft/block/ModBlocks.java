package dev.riichicraft.block;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;

public final class ModBlocks {
    public static final MahjongTableBlock MAHJONG_TABLE = Registry.register(
            BuiltInRegistries.BLOCK,
            ModBlockIds.MAHJONG_TABLE.block(),
            new MahjongTableBlock(BlockBehaviour.Properties.of()
                    .setId(ModBlockIds.MAHJONG_TABLE.block())
                    .strength(2.0F)
                    .sound(SoundType.WOOD)
                    .noOcclusion()));
    public static final LargeMahjongTableBlock LARGE_MAHJONG_TABLE=Registry.register(BuiltInRegistries.BLOCK,
            ModBlockIds.LARGE_MAHJONG_TABLE.block(),new LargeMahjongTableBlock(BlockBehaviour.Properties.of()
                    .setId(ModBlockIds.LARGE_MAHJONG_TABLE.block()).strength(2.0F).sound(SoundType.WOOD).noOcclusion().pushReaction(PushReaction.BLOCK)));
    public static final MahjongChairBlock MAHJONG_CHAIR=Registry.register(BuiltInRegistries.BLOCK,
            ModBlockIds.MAHJONG_CHAIR.block(),new MahjongChairBlock(BlockBehaviour.Properties.of()
                    .setId(ModBlockIds.MAHJONG_CHAIR.block()).strength(1.5F).sound(SoundType.WOOD).noOcclusion().pushReaction(PushReaction.BLOCK)));
    public static final MahjongChairBlock EMPTY_CHAIR=Registry.register(BuiltInRegistries.BLOCK,
            ModBlockIds.EMPTY_CHAIR.block(),new MahjongChairBlock(BlockBehaviour.Properties.of()
                    .setId(ModBlockIds.EMPTY_CHAIR.block()).strength(1.5F).sound(SoundType.WOOD).noOcclusion().pushReaction(PushReaction.BLOCK)));

    private ModBlocks() {
    }

    public static void initialize() {
        Registry.register(BuiltInRegistries.ITEM, ModBlockIds.MAHJONG_TABLE.item(),
                new BlockItem(MAHJONG_TABLE, new Item.Properties()
                        .useBlockDescriptionPrefix()
                        .setId(ModBlockIds.MAHJONG_TABLE.item())));
        Registry.register(BuiltInRegistries.ITEM,ModBlockIds.LARGE_MAHJONG_TABLE.item(),new LargeMahjongTableItem(LARGE_MAHJONG_TABLE,
                new Item.Properties().useBlockDescriptionPrefix().setId(ModBlockIds.LARGE_MAHJONG_TABLE.item())));
        Registry.register(BuiltInRegistries.ITEM,ModBlockIds.MAHJONG_CHAIR.item(),new BlockItem(MAHJONG_CHAIR,
                new Item.Properties().useBlockDescriptionPrefix().setId(ModBlockIds.MAHJONG_CHAIR.item())));
        Registry.register(BuiltInRegistries.ITEM,ModBlockIds.EMPTY_CHAIR.item(),new BlockItem(EMPTY_CHAIR,
                new Item.Properties().useBlockDescriptionPrefix().setId(ModBlockIds.EMPTY_CHAIR.item())));

        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS)
                .register(output -> { output.accept(MAHJONG_TABLE.asItem()); output.accept(LARGE_MAHJONG_TABLE.asItem());
                    output.accept(MAHJONG_CHAIR.asItem()); output.accept(EMPTY_CHAIR.asItem()); });
        ServerChunkEvents.CHUNK_LOAD.register((level,chunk,newChunk)->LargeMahjongTableBlock.loadedChunk(level,chunk));
    }
}
