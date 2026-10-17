package dev.riichicraft.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/** Loaded-chunk-only furniture inspection. A world direction is never a player's seat wind. */
public final class TableStructure {
    private TableStructure() { }

    public record Layout(boolean large, boolean loaded, boolean bodyComplete, int players) {
        public boolean matches(int count) { return !large || loaded && bodyComplete && players == count; }
    }

    public static BlockState loadedState(ServerLevel level, BlockPos pos) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk == null ? null : chunk.getBlockState(pos);
    }

    public static boolean isRoot(BlockState state) {
        return state.is(ModBlocks.MAHJONG_TABLE) || state.is(ModBlocks.LARGE_MAHJONG_TABLE)
                && state.getValue(LargeMahjongTableBlock.PART) == 4;
    }

    public static BlockPos resolve(ServerLevel level, BlockPos pos) {
        BlockState state = loadedState(level, pos);
        if (state == null) return null;
        if (state.is(ModBlocks.MAHJONG_TABLE)) return pos.immutable();
        return state.is(ModBlocks.LARGE_MAHJONG_TABLE) ? LargeMahjongTableBlock.center(pos, state) : null;
    }

    public static Layout inspect(ServerLevel level, BlockPos center) {
        BlockState root = loadedState(level, center);
        if (root == null) return new Layout(true, false, false, 0);
        if (root.is(ModBlocks.MAHJONG_TABLE)) return new Layout(false, true, true, 0);
        if (!isRoot(root)) return new Layout(true, true, false, 0);
        Direction facing = root.getValue(LargeMahjongTableBlock.FACING);
        boolean complete = true;
        for (int part = 0; part < 9; part++) {
            BlockState state = loadedState(level, LargeMahjongTableBlock.position(center, facing, part));
            if (state == null) return new Layout(true, false, false, 0);
            complete &= state.is(ModBlocks.LARGE_MAHJONG_TABLE)
                    && state.getValue(LargeMahjongTableBlock.PART) == part
                    && state.getValue(LargeMahjongTableBlock.FACING) == facing;
        }
        int regular = 0, empty = 0;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockState chair = loadedState(level, center.relative(side, 2));
            if (chair == null) return new Layout(true, false, complete, 0);
            if (!(chair.is(ModBlocks.MAHJONG_CHAIR) || chair.is(ModBlocks.EMPTY_CHAIR))
                    || chair.getValue(MahjongChairBlock.FACING) != side.getOpposite()) {
                return new Layout(true, true, complete, 0);
            }
            if (chair.is(ModBlocks.EMPTY_CHAIR)) empty++; else regular++;
        }
        int players = complete && (regular == 4 && empty == 0 || regular == 3 && empty == 1) ? regular : 0;
        return new Layout(true, true, complete, players);
    }
}
