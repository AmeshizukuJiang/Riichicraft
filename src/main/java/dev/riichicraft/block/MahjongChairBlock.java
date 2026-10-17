package dev.riichicraft.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Ordinary and intentionally empty chairs share geometry, but are separate blocks and items. */
public final class MahjongChairBlock extends HorizontalDirectionalBlock {
    public static final MapCodec<MahjongChairBlock> CODEC = simpleCodec(MahjongChairBlock::new);
    private static final VoxelShape NORTH = Shapes.or(
            Block.box(2,6,2,14,8,14), Block.box(2,8,12,14,16,14),
            Block.box(2,0,2,4,6,4), Block.box(12,0,2,14,6,4),
            Block.box(2,0,12,4,6,14), Block.box(12,0,12,14,6,14));
    private static final VoxelShape[] SHAPES = new VoxelShape[4];
    static { for (Direction side : Direction.Plane.HORIZONTAL) SHAPES[side.get2DDataValue()] = FurnitureShapes.rotate(NORTH,side); }

    public MahjongChairBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING,Direction.NORTH));
    }
    @Override protected MapCodec<? extends HorizontalDirectionalBlock> codec() { return CODEC; }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING,context.getHorizontalDirection());
    }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES[state.getValue(FACING).get2DDataValue()];
    }
    @Override protected BlockState rotate(BlockState state, Rotation rotation) { return state.setValue(FACING,rotation.rotate(state.getValue(FACING))); }
    @Override protected BlockState mirror(BlockState state, Mirror mirror) { return state.rotate(mirror.getRotation(state.getValue(FACING))); }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder) { builder.add(FACING); }
}
