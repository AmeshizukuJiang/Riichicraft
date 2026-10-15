package dev.riichicraft.test;

import dev.riichicraft.block.MahjongTableBlock;
import dev.riichicraft.block.ModBlocks;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class MahjongTableGameTests {
    @GameTest
    public void blockItemPlacesFacingThePlayerInAllFourDirections(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        ItemStack tables = new ItemStack(ModBlocks.MAHJONG_TABLE.asItem(), 4);
        player.setItemInHand(InteractionHand.MAIN_HAND, tables);

        Direction[] directions = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
        for (int index = 0; index < directions.length; index++) {
            Direction playerDirection = directions[index];
            BlockPos relativeTarget = new BlockPos(1 + (index % 2) * 3, 2, 1 + (index / 2) * 3);
            helper.setBlock(relativeTarget.below(), Blocks.STONE);
            BlockPos absoluteTarget = helper.absolutePos(relativeTarget);
            player.setPos(absoluteTarget.getX() + 2.5, absoluteTarget.getY(), absoluteTarget.getZ() + 2.5);
            player.setYRot(yawFor(playerDirection));
            player.setXRot(0.0F);

            // Use the real BlockItem path rather than setting the expected block state directly.
            BlockHitResult supportHit = new BlockHitResult(
                    Vec3.atCenterOf(absoluteTarget.below()).add(0.0, 0.5, 0.0),
                    Direction.UP, absoluteTarget.below(), false);
            tables.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, supportHit));

            helper.assertBlockPresent(ModBlocks.MAHJONG_TABLE, relativeTarget);
            helper.assertTrue(helper.getBlockState(relativeTarget).getValue(MahjongTableBlock.FACING)
                            == playerDirection.getOpposite(),
                    "Placed table must face the player for player direction " + playerDirection);
        }

        helper.assertTrue(tables.isEmpty(), "Four survival placements must consume exactly four table items");
        helper.succeed();
    }

    @GameTest
    public void survivalPlayerBreakDropsExactlyOneTable(GameTestHelper helper) {
        assertPlayerBreakDrops(helper, GameType.SURVIVAL, 1);
    }

    @GameTest
    public void creativePlayerBreakDoesNotDropATable(GameTestHelper helper) {
        assertPlayerBreakDrops(helper, GameType.CREATIVE, 0);
    }

    private static void assertPlayerBreakDrops(GameTestHelper helper, GameType gameType,
            int expectedDrops) {
        // 26.2's native unconnected server-player mock preserves the requested game mode.
        // This drives actual player destruction, but does not simulate a connected client.
        ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayer(gameType);
        BlockPos relativeTarget = new BlockPos(3, 2, 3);
        BlockPos absoluteTarget = helper.absolutePos(relativeTarget);
        helper.setBlock(relativeTarget.below(), Blocks.STONE);
        helper.setBlock(relativeTarget, ModBlocks.MAHJONG_TABLE);
        player.setPos(absoluteTarget.getX() + 2.5, absoluteTarget.getY(), absoluteTarget.getZ() + 0.5);
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);

        helper.assertTrue(player.isCreative() == (gameType == GameType.CREATIVE),
                "The mock player must preserve the requested creative/survival mode");
        helper.assertTrue(player.preventsBlockDrops() == (gameType == GameType.CREATIVE),
                "The actual player drop-prevention flag must match the requested game mode");
        helper.assertTrue(player.gameMode.destroyBlock(absoluteTarget), "Player destruction must succeed");
        helper.assertBlockPresent(Blocks.AIR, relativeTarget);

        int actualDrops = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                        new AABB(absoluteTarget).inflate(1.0))
                .stream()
                .map(ItemEntity::getItem)
                .filter(stack -> stack.is(ModBlocks.MAHJONG_TABLE.asItem()))
                .mapToInt(ItemStack::getCount)
                .sum();
        helper.assertTrue(actualDrops == expectedDrops,
                gameType + " must drop " + expectedDrops + " table item(s), got " + actualDrops);
        helper.succeed();
    }

    private static float yawFor(Direction direction) {
        return switch (direction) {
            case SOUTH -> 0.0F;
            case WEST -> 90.0F;
            case NORTH -> 180.0F;
            case EAST -> 270.0F;
            default -> throw new IllegalArgumentException("Expected a horizontal direction");
        };
    }

}
