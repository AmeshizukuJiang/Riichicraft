package dev.riichicraft.test;

import dev.riichicraft.block.ModBlocks;
import dev.riichicraft.network.LobbyActionPayload;
import dev.riichicraft.server.LobbyService;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

public final class LobbyServiceGameTests {
    @GameTest
    public void viewsBindPlayerAndTableAndCloseRetainsTheSeat(GameTestHelper helper) {
        BlockPos first = table(helper, new BlockPos(2, 2, 2));
        BlockPos second = table(helper, new BlockPos(4, 2, 2));
        ServerPlayer one = playerAt(helper, first);
        ServerPlayer two = playerAt(helper, first);
        LobbyService service = LobbyService.get(helper.getLevel().getServer());
        UUID firstView = service.open(one, first);
        service.handleAction(one, action(firstView, 0, LobbyActionPayload.JOIN, false));
        UUID tableId = service.registry().tableForPlayer(one.getUUID()).orElseThrow();
        service.handleAction(two, action(firstView, 1, LobbyActionPayload.LEAVE, false));
        helper.assertTrue(service.registry().tableForPlayer(two.getUUID()).isEmpty()
                        && service.registry().snapshot(tableId).orElseThrow().revision() == 1,
                "Another player cannot reuse a stolen view ID");

        UUID secondPlayerView = service.open(two, first);
        service.handleAction(two, action(secondPlayerView, 1, LobbyActionPayload.JOIN, false));
        service.handleAction(one, action(firstView, 1, LobbyActionPayload.SET_READY, true));
        helper.assertTrue(!service.registry().snapshot(tableId).orElseThrow().seats().getFirst().ready(),
                "A stale request cannot change readiness after another player's join");
        UUID reopened = service.open(one, first);
        service.handleAction(one, action(firstView, 2, LobbyActionPayload.LEAVE, false));
        helper.assertTrue(service.registry().tableForPlayer(one.getUUID()).isPresent(),
                "A superseded view cannot mutate the new view's table");
        service.handleAction(one, action(reopened, 2, LobbyActionPayload.CLOSE, false));
        service.handleAction(one, action(reopened, 2, LobbyActionPayload.LEAVE, false));
        helper.assertTrue(service.registry().tableForPlayer(one.getUUID()).isPresent(),
                "Closing keeps the seat but revokes the closed view's authority");

        UUID otherTableView = service.open(one, second);
        service.handleAction(one, action(otherTableView, 0, LobbyActionPayload.JOIN, false));
        helper.assertTrue(service.registry().tableForPlayer(one.getUUID()).orElseThrow().equals(tableId),
                "Opening a different table cannot bypass the global single-seat rule");
        helper.setBlock(new BlockPos(2, 2, 2), Blocks.AIR);
        helper.assertTrue(service.registry().snapshot(tableId).isEmpty()
                        && service.registry().tableForPlayer(one.getUUID()).isEmpty()
                        && service.registry().tableForPlayer(two.getUUID()).isEmpty(),
                "Actual block replacement must clean every seat immediately");
        service.handleAction(one, action(otherTableView, 0, LobbyActionPayload.JOIN, false));
        helper.assertTrue(service.registry().tableForPlayer(one.getUUID()).isPresent(),
                "Removing the first table cannot close the other table's view");
        service.disconnect(one);
        helper.succeed();
    }

    @GameTest
    public void invalidLocationAndDistanceCannotCreateOrMutateALobby(GameTestHelper helper) {
        BlockPos pos = table(helper, new BlockPos(3, 2, 3));
        ServerPlayer player = playerAt(helper, pos);
        LobbyService service = LobbyService.get(helper.getLevel().getServer());
        helper.assertTrue(service.open(player, pos.above()) == null, "Only a real table can open a view");
        UUID view = service.open(player, pos);
        service.handleAction(player, action(view, 0, LobbyActionPayload.JOIN, false));
        UUID id = service.registry().tableForPlayer(player.getUUID()).orElseThrow();
        player.setPos(pos.getX() + 100.0, pos.getY(), pos.getZ());
        service.handleAction(player, action(view, 1, LobbyActionPayload.SET_READY, true));
        helper.assertTrue(!service.registry().snapshot(id).orElseThrow().seats().getFirst().ready(),
                "An out-of-range action must be rejected even before the next tick cleanup");
        helper.assertTrue(service.open(player, pos) == null, "A distant player cannot open a table");
        service.disconnect(player);
        helper.assertTrue(service.registry().tableForPlayer(player.getUUID()).isEmpty(),
                "Disconnect cleanup must work after the view has already closed");
        helper.succeed();
    }

    @GameTest
    public void chunkUnloadListenerReleasesItsTemporaryLobbies(GameTestHelper helper) {
        BlockPos pos = table(helper, new BlockPos(3, 2, 3));
        ServerPlayer player = playerAt(helper, pos);
        LobbyService service = LobbyService.get(helper.getLevel().getServer());
        UUID view = service.open(player, pos);
        service.handleAction(player, action(view, 0, LobbyActionPayload.JOIN, false));
        UUID tableId = service.registry().tableForPlayer(player.getUUID()).orElseThrow();
        // Tests event wiring with a real loaded chunk; this does not pretend to unload it in the engine.
        ServerChunkEvents.CHUNK_UNLOAD.invoker().onChunkUnload(helper.getLevel(), helper.getLevel().getChunkAt(pos));
        helper.assertTrue(service.registry().snapshot(tableId).isEmpty()
                        && service.registry().tableForPlayer(player.getUUID()).isEmpty(),
                "Chunk-unload event handling must clear temporary lobbies and player indexes");
        helper.succeed();
    }

    private static BlockPos table(GameTestHelper helper, BlockPos relative) {
        helper.setBlock(relative.below(), Blocks.STONE);
        helper.setBlock(relative, ModBlocks.MAHJONG_TABLE);
        return helper.absolutePos(relative);
    }

    private static ServerPlayer playerAt(GameTestHelper helper, BlockPos pos) {
        ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayer(GameType.SURVIVAL);
        player.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 2.5);
        return player;
    }

    private static LobbyActionPayload action(UUID view, long revision, int action, boolean ready) {
        return new LobbyActionPayload(view, revision, action, ready);
    }
}
