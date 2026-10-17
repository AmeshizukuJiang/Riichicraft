package dev.riichicraft.block;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Validate the entire footprint before making any changes; consume just one item via BlockItem. */
public final class LargeMahjongTableItem extends BlockItem {
    public LargeMahjongTableItem(LargeMahjongTableBlock block,Item.Properties properties) { super(block,properties); }

    @Override protected boolean canPlace(BlockPlaceContext context,BlockState state) {
        Level level=context.getLevel(); BlockPos center=context.getClickedPos();
        Direction facing=state.getValue(LargeMahjongTableBlock.FACING);
        for(int part=0;part<9;part++) {
            BlockPos target=LargeMahjongTableBlock.position(center,facing,part);
            if(level.isOutsideBuildHeight(target) || !level.getWorldBorder().isWithinBounds(target)
                    || level instanceof ServerLevel server && TableStructure.loadedState(server,target)==null
                    || !level.getBlockState(target).canBeReplaced(BlockPlaceContext.at(context,target,Direction.UP))
                    || context.getPlayer()!=null && !context.getPlayer().mayUseItemAt(target,context.getClickedFace(),context.getItemInHand())
                    || !super.canPlace(BlockPlaceContext.at(context,target,Direction.UP),state.setValue(LargeMahjongTableBlock.PART,part))) {
                if(context.getPlayer()!=null && level instanceof ServerLevel) context.getPlayer().sendOverlayMessage(
                        Component.translatable("message.riichicraft.structure.no_space"));
                return false;
            }
        }
        return true;
    }
    @Override protected boolean placeBlock(BlockPlaceContext context,BlockState state) {
        if(!canPlace(context,state)) return false;
        Level level=context.getLevel(); BlockPos center=context.getClickedPos(); Direction facing=state.getValue(LargeMahjongTableBlock.FACING);
        List<BlockPos> placed=new ArrayList<>(); List<BlockState> previous=new ArrayList<>();
        for(int part=0;part<9;part++) {
            BlockPos target=LargeMahjongTableBlock.position(center,facing,part);
            previous.add(level.getBlockState(target));
            if(!level.setBlock(target,state.setValue(LargeMahjongTableBlock.PART,part),Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE)) {
                // This path is exceptional (all nine positions were validated on this same thread).
                for(int index=placed.size()-1;index>=0;index--) level.setBlock(placed.get(index),previous.get(index),Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                return false;
            }
            placed.add(target);
        }
        for(BlockPos target:placed) level.updateNeighborsAt(target,getBlock());
        return true;
    }
}
