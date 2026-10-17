package dev.riichicraft.block;

import com.mojang.serialization.MapCodec;
import dev.riichicraft.server.LobbyService;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Nine local parts form one furniture item and one server-owned table at part four. */
public final class LargeMahjongTableBlock extends HorizontalDirectionalBlock {
    public static final MapCodec<LargeMahjongTableBlock> CODEC = simpleCodec(LargeMahjongTableBlock::new);
    public static final IntegerProperty PART = IntegerProperty.create("part",0,8);
    private static final ThreadLocal<Set<BlockPos>> CLEANING = ThreadLocal.withInitial(HashSet::new);
    private static final VoxelShape[][] SHAPES = new VoxelShape[4][9];
    static {
        for (int part=0;part<9;part++) {
            VoxelShape shape=Block.box(0,12,0,16,16,16);
            int x=part%3,z=part/3;
            if(x!=1 && z!=1) shape=Shapes.or(shape,Block.box(x==0?2:11,0,z==0?2:11,x==0?5:14,12,z==0?5:14));
            for(Direction side:Direction.Plane.HORIZONTAL) SHAPES[side.get2DDataValue()][part]=FurnitureShapes.rotate(shape,side);
        }
    }

    public LargeMahjongTableBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING,Direction.NORTH).setValue(PART,4));
    }
    public static BlockPos offset(Direction facing,int part) {
        int x=part%3-1,z=part/3-1;
        return switch(facing) {
            case EAST -> new BlockPos(-z,0,x); case SOUTH -> new BlockPos(-x,0,-z);
            case WEST -> new BlockPos(z,0,-x); default -> new BlockPos(x,0,z);
        };
    }
    public static BlockPos position(BlockPos center,Direction facing,int part) { return center.offset(offset(facing,part)); }
    public static BlockPos center(BlockPos pos,BlockState state) { return pos.subtract(offset(state.getValue(FACING),state.getValue(PART))); }
    public static boolean belongs(BlockState state,BlockPos pos,BlockPos center,Direction facing) {
        return state!=null && state.is(ModBlocks.LARGE_MAHJONG_TABLE) && state.getValue(FACING)==facing && center(pos,state).equals(center);
    }
    @Override protected MapCodec<? extends HorizontalDirectionalBlock> codec() { return CODEC; }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) { return defaultBlockState().setValue(FACING,context.getHorizontalDirection().getOpposite()); }
    @Override protected VoxelShape getShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context) {
        return SHAPES[state.getValue(FACING).get2DDataValue()][state.getValue(PART)];
    }
    @Override protected BlockState rotate(BlockState state,Rotation rotation) { return state.setValue(FACING,rotation.rotate(state.getValue(FACING))); }
    @Override protected BlockState mirror(BlockState state,Mirror mirror) {
        // Reflecting local x exchanges left/right parts in addition to reflecting the facing.
        if(mirror==Mirror.NONE) return state;
        int part=state.getValue(PART);
        return state.rotate(mirror.getRotation(state.getValue(FACING))).setValue(PART,part/3*3+2-part%3);
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder) { builder.add(FACING,PART); }
    @Override protected InteractionResult useWithoutItem(BlockState state,Level level,BlockPos pos,Player player,BlockHitResult hit) {
        if(player instanceof ServerPlayer serverPlayer) LobbyService.get(serverPlayer.level().getServer()).open(serverPlayer,pos);
        return InteractionResult.SUCCESS;
    }
    @Override public BlockState playerWillDestroy(Level level,BlockPos pos,BlockState state,Player player) {
        if(level instanceof ServerLevel server && player.preventsBlockDrops()) dismantle(server,pos,state,false);
        return super.playerWillDestroy(level,pos,state,player);
    }
    @Override protected void affectNeighborsAfterRemoval(BlockState state,ServerLevel level,BlockPos pos,boolean moved) {
        dismantle(level,pos,state,true);
        super.affectNeighborsAfterRemoval(state,level,pos,moved);
    }

    @Override protected void onExplosionHit(BlockState state,ServerLevel level,BlockPos pos,Explosion explosion,
            BiConsumer<ItemStack,BlockPos> drops) {
        BlockPos center=center(pos,state);
        BlockState root=TableStructure.loadedState(level,center);
        if(!pos.equals(center) && belongs(root,center,center,state.getValue(FACING))) {
            // Keep the original explosion's loot context even when a rim is processed before the center.
            root.onExplosionHit(level,center,explosion,drops);
        }
        if(TableStructure.loadedState(level,pos).is(this)) super.onExplosionHit(state,level,pos,explosion,drops);
    }

    private static void dismantle(ServerLevel level,BlockPos removed,BlockState state,boolean dropCenter) {
        BlockPos center=center(removed,state);
        Set<BlockPos> cleaning=CLEANING.get();
        if(!cleaning.add(center)) return;
        try {
            LobbyService.get(level.getServer()).removeTable(level,center);
            Direction facing=state.getValue(FACING);
            // Only the center has loot. Removing a rim delegates that one drop to the center.
            BlockState root=TableStructure.loadedState(level,center);
            if(!removed.equals(center) && belongs(root,center,center,facing)) level.destroyBlock(center,dropCenter);
            for(int part=0;part<9;part++) {
                BlockPos target=position(center,facing,part);
                if(!target.equals(removed) && belongs(TableStructure.loadedState(level,target),target,center,facing))
                    level.setBlock(target,Blocks.AIR.defaultBlockState(),Block.UPDATE_ALL);
            }
        } finally { cleaning.remove(center); if(cleaning.isEmpty()) CLEANING.remove(); }
    }

    @Override protected void onPlace(BlockState state,Level level,BlockPos pos,BlockState oldState,boolean moved) {
        if(level instanceof ServerLevel server) server.scheduleTick(pos,this,1);
    }
    @Override protected void tick(BlockState state,ServerLevel level,BlockPos pos,RandomSource random) {
        BlockPos center=center(pos,state); Direction facing=state.getValue(FACING);
        boolean complete=true;
        for(int part=0;part<9;part++) {
            BlockPos target=position(center,facing,part); BlockState member=TableStructure.loadedState(level,target);
            if(member==null) { level.scheduleTick(pos,this,20); return; }
            complete &= belongs(member,target,center,facing);
        }
        // Orphan cleanup does not mint another item when an unavailable companion chunk returns.
        if(!complete) { dismantle(level,pos,state,false); level.setBlock(pos,Blocks.AIR.defaultBlockState(),Block.UPDATE_ALL); }
    }

    /** Inspect only the supplied chunk during FULL completion; defer all world inspection to block ticks. */
    public static void loadedChunk(ServerLevel level,LevelChunk chunk) {
        var sections=chunk.getSections();
        for(int index=0;index<sections.length;index++) {
            var section=sections[index];
            if(!section.maybeHas(state->state.is(ModBlocks.LARGE_MAHJONG_TABLE))) continue;
            int bottom=level.getMinY()+index*16;
            for(int x=0;x<16;x++) for(int y=0;y<16;y++) for(int z=0;z<16;z++)
                if(section.getBlockState(x,y,z).is(ModBlocks.LARGE_MAHJONG_TABLE)) level.scheduleTick(
                        new BlockPos(chunk.getPos().getMinBlockX()+x,bottom+y,chunk.getPos().getMinBlockZ()+z),ModBlocks.LARGE_MAHJONG_TABLE,1);
        }
    }
}
