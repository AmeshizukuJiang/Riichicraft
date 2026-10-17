package dev.riichicraft.block;

import dev.riichicraft.Riichicraft;
import net.minecraft.references.BlockItemId;

public final class ModBlockIds {
    public static final BlockItemId MAHJONG_TABLE = BlockItemId.create(
            Riichicraft.id("mahjong_table"), Riichicraft.id("mahjong_table"));
    public static final BlockItemId LARGE_MAHJONG_TABLE = BlockItemId.create(Riichicraft.id("large_mahjong_table"),Riichicraft.id("large_mahjong_table"));
    public static final BlockItemId MAHJONG_CHAIR = BlockItemId.create(Riichicraft.id("mahjong_chair"),Riichicraft.id("mahjong_chair"));
    public static final BlockItemId EMPTY_CHAIR = BlockItemId.create(Riichicraft.id("empty_chair"),Riichicraft.id("empty_chair"));

    private ModBlockIds() {
    }
}
