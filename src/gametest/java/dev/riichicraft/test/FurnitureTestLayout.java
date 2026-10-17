package dev.riichicraft.test;

import dev.riichicraft.block.LargeMahjongTableBlock;
import dev.riichicraft.block.MahjongChairBlock;
import dev.riichicraft.block.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

public final class FurnitureTestLayout {
    private FurnitureTestLayout() { }
    public static void body(Level level,BlockPos center,Direction facing) {
        for(int part=0;part<9;part++) level.setBlock(LargeMahjongTableBlock.position(center,facing,part),
                ModBlocks.LARGE_MAHJONG_TABLE.defaultBlockState().setValue(LargeMahjongTableBlock.FACING,facing)
                        .setValue(LargeMahjongTableBlock.PART,part),3);
    }
    public static void chairs(Level level,BlockPos center,int players) {
        for(Direction side:Direction.Plane.HORIZONTAL) level.setBlock(center.relative(side,2),
                (players==3 && side==Direction.NORTH?ModBlocks.EMPTY_CHAIR:ModBlocks.MAHJONG_CHAIR)
                        .defaultBlockState().setValue(MahjongChairBlock.FACING,side.getOpposite()),3);
    }
    public static float yaw(Direction direction) {
        return switch(direction) { case SOUTH -> 0; case WEST -> 90; case NORTH -> 180; case EAST -> 270; default -> throw new IllegalArgumentException(); };
    }
}
