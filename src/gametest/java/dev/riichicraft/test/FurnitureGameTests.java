package dev.riichicraft.test;

import dev.riichicraft.block.*;
import dev.riichicraft.lobby.LobbyRegistry;
import dev.riichicraft.network.*;
import dev.riichicraft.server.LobbyService;
import io.netty.buffer.Unpooled;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class FurnitureGameTests {
    @GameTest
    public void realItemPlacesNineRotatedPartsAndConsumesOnlyOneItem(GameTestHelper helper) {
        BlockPos center=helper.absolutePos(new BlockPos(3,2,3));
        Player player=helper.makeMockPlayer(GameType.SURVIVAL);
        ItemStack stack=new ItemStack(ModBlocks.LARGE_MAHJONG_TABLE.asItem(),4);
        for(Direction facing:Direction.Plane.HORIZONTAL) {
            place(helper,center,player,stack,facing);
            for(int part=0;part<9;part++) {
                BlockPos target=LargeMahjongTableBlock.position(center,facing,part);
                var state=helper.getLevel().getBlockState(target);
                helper.assertTrue(state.is(ModBlocks.LARGE_MAHJONG_TABLE) && state.getValue(LargeMahjongTableBlock.PART)==part
                        && state.getValue(LargeMahjongTableBlock.FACING)==facing
                        && TableStructure.resolve(helper.getLevel(),target).equals(center),"All rotated parts resolve to their single center");
            }
            helper.getLevel().destroyBlock(center,false);
        }
        helper.assertTrue(stack.isEmpty(),"Four complete placements consume four items, not thirty-six");
        helper.succeed();
    }

    @GameTest
    public void everyObstructedPositionRejectsWithoutConsumingOrOverwriting(GameTestHelper helper) {
        BlockPos center=helper.absolutePos(new BlockPos(3,2,3));
        Player player=helper.makeMockPlayer(GameType.SURVIVAL);
        ItemStack stack=new ItemStack(ModBlocks.LARGE_MAHJONG_TABLE.asItem());
        for(int blocked=0;blocked<9;blocked++) {
            BlockPos obstacle=LargeMahjongTableBlock.position(center,Direction.NORTH,blocked);
            // Place against the side of a solid center when its own destination is blocked.
            helper.getLevel().setBlockAndUpdate(obstacle,Blocks.STONE.defaultBlockState());
            place(helper,center,player,stack,Direction.NORTH);
            helper.assertTrue(stack.getCount()==1 && helper.getLevel().getBlockState(obstacle).is(Blocks.STONE),"Obstruction retains item and original block");
            for(int part=0;part<9;part++) helper.assertTrue(!helper.getLevel().getBlockState(
                    LargeMahjongTableBlock.position(center,Direction.NORTH,part)).is(ModBlocks.LARGE_MAHJONG_TABLE),"No partial table survives rejected placement");
            helper.getLevel().setBlockAndUpdate(obstacle,Blocks.AIR.defaultBlockState());
        }
        helper.succeed();
    }

    @GameTest
    public void breakingEveryPartDropsOneInSurvivalAndNoneInCreative(GameTestHelper helper) {
        BlockPos center=helper.absolutePos(new BlockPos(3,2,3));
        for(GameType mode:List.of(GameType.SURVIVAL,GameType.CREATIVE)) for(int part=0;part<9;part++) {
            FurnitureTestLayout.body(helper.getLevel(),center,Direction.EAST);
            ServerPlayer player=(ServerPlayer)helper.makeMockServerPlayer(mode);
            BlockPos target=LargeMahjongTableBlock.position(center,Direction.EAST,part);
            player.setPos(target.getX()+0.5,target.getY()+1,target.getZ()+0.5);
            helper.assertTrue(player.gameMode.destroyBlock(target),"Native player destroys the selected table part");
            int count=helper.getLevel().getEntitiesOfClass(ItemEntity.class,new AABB(center).inflate(3)).stream()
                    .filter(e->e.getItem().is(ModBlocks.LARGE_MAHJONG_TABLE.asItem())).mapToInt(e->e.getItem().getCount()).sum();
            helper.assertTrue(count==(mode==GameType.CREATIVE?0:1),"A complete table has exactly one survival drop and zero creative drops, including rim destruction");
            for(int member=0;member<9;member++) helper.assertTrue(helper.getLevel().getBlockState(
                    LargeMahjongTableBlock.position(center,Direction.EAST,member)).isAir(),"Every companion is removed");
            helper.getLevel().getEntitiesOfClass(ItemEntity.class,new AABB(center).inflate(3)).forEach(ItemEntity::discard);
        }
        helper.succeed();
    }

    @GameTest
    public void nativeExplosionCannotDuplicateTheFurnitureItem(GameTestHelper helper) {
        BlockPos center=helper.absolutePos(new BlockPos(3,2,3));
        FurnitureTestLayout.body(helper.getLevel(),center,Direction.NORTH);
        helper.getLevel().explode(null,center.getX()+0.5,center.getY()+0.9,center.getZ()+0.5,2.0F,Level.ExplosionInteraction.TNT);
        int count=helper.getLevel().getEntitiesOfClass(ItemEntity.class,new AABB(center).inflate(4)).stream()
                .filter(e->e.getItem().is(ModBlocks.LARGE_MAHJONG_TABLE.asItem())).mapToInt(e->e.getItem().getCount()).sum();
        helper.assertTrue(count<=1,"One native explosion cannot drop more than one table item");
        for(int part=0;part<9;part++) helper.assertTrue(!helper.getLevel().getBlockState(
                LargeMahjongTableBlock.position(center,Direction.NORTH,part)).is(ModBlocks.LARGE_MAHJONG_TABLE),"Explosion tears down all nine parts");
        helper.succeed();
    }

    @GameTest
    public void crossChunkLayoutAndUnloadedQueriesDoNotCreateRemoteChunks(GameTestHelper helper) {
        var origin=helper.absolutePos(new BlockPos(3,20,3));
        BlockPos center=new BlockPos((origin.getX() & ~15)+15,origin.getY(),(origin.getZ() & ~15)+15);
        FurnitureTestLayout.body(helper.getLevel(),center,Direction.SOUTH);
        FurnitureTestLayout.chairs(helper.getLevel(),center,3);
        helper.runAfterDelay(2,()->{
            helper.assertTrue(TableStructure.inspect(helper.getLevel(),center).players()==3,"An intact table spanning four loaded chunks is recognized");
            BlockPos remote=center.offset(1_000_000,0,1_000_000);
            helper.assertTrue(TableStructure.loadedState(helper.getLevel(),remote)==null,"The remote test position starts unloaded");
            helper.assertTrue(!TableStructure.inspect(helper.getLevel(),remote).loaded()
                    && TableStructure.resolve(helper.getLevel(),remote)==null
                    && helper.getLevel().getChunkSource().getChunkNow(remote.getX()>>4,remote.getZ()>>4)==null,"Inspection and canonical lookup never load the remote chunk");
            helper.getLevel().destroyBlock(center.east(),false);
            for(int part=0;part<9;part++)helper.assertTrue(helper.getLevel().getBlockState(
                    LargeMahjongTableBlock.position(center,Direction.SOUTH,part)).isAir(),"Cross-chunk teardown removes every loaded companion");
            for(Direction side:Direction.Plane.HORIZONTAL)helper.getLevel().setBlockAndUpdate(center.relative(side,2),Blocks.AIR.defaultBlockState());
            helper.succeed();
        });
    }

    @GameTest
    public void chairLayoutsValidateFacingAndExactlyOneEmptyChair(GameTestHelper helper) {
        BlockPos center=helper.absolutePos(new BlockPos(3,2,3));
        FurnitureTestLayout.body(helper.getLevel(),center,Direction.WEST);
        FurnitureTestLayout.chairs(helper.getLevel(),center,4);
        helper.assertTrue(TableStructure.inspect(helper.getLevel(),center).players()==4,"Four inward chairs enable four players");
        for(Direction side:Direction.Plane.HORIZONTAL) {
            helper.getLevel().setBlockAndUpdate(center.relative(side,2),ModBlocks.EMPTY_CHAIR.defaultBlockState().setValue(MahjongChairBlock.FACING,side.getOpposite()));
            helper.assertTrue(TableStructure.inspect(helper.getLevel(),center).players()==3,"Any one prescribed chair may be the empty chair");
            helper.getLevel().setBlockAndUpdate(center.relative(side,2),ModBlocks.EMPTY_CHAIR.defaultBlockState().setValue(MahjongChairBlock.FACING,side));
            helper.assertTrue(TableStructure.inspect(helper.getLevel(),center).players()==0,"An outward empty chair is invalid");
            FurnitureTestLayout.chairs(helper.getLevel(),center,4);
        }
        FurnitureTestLayout.chairs(helper.getLevel(),center,3);
        helper.getLevel().setBlockAndUpdate(center.south(2),ModBlocks.EMPTY_CHAIR.defaultBlockState());
        helper.assertTrue(TableStructure.inspect(helper.getLevel(),center).players()==0,"Two empty chairs do not enable a game");
        helper.getLevel().setBlockAndUpdate(center.south(2),Blocks.AIR.defaultBlockState());
        helper.assertTrue(TableStructure.inspect(helper.getLevel(),center).players()==0,"Missing chair does not stand in for the Empty Chair block");
        helper.succeed();
    }

    @GameTest
    public void rotationsAndMirrorsKeepAllPartsOwnedByTheTransformedCenter(GameTestHelper helper) {
        BlockPos origin=new BlockPos(10,0,20);
        for(Direction facing:Direction.Plane.HORIZONTAL) for(int part=0;part<9;part++) {
            var state=ModBlocks.LARGE_MAHJONG_TABLE.defaultBlockState().setValue(LargeMahjongTableBlock.FACING,facing).setValue(LargeMahjongTableBlock.PART,part);
            BlockPos relative=LargeMahjongTableBlock.offset(facing,part);
            for(Rotation rotation:Rotation.values()) {
                var rotated=state.rotate(rotation);
                helper.assertTrue(LargeMahjongTableBlock.offset(rotated.getValue(LargeMahjongTableBlock.FACING),rotated.getValue(LargeMahjongTableBlock.PART))
                        .equals(relative.rotate(rotation)),"Rotated geometry and ownership agree");
            }
            for(Mirror mirror:Mirror.values()) {
                var mirrored=state.mirror(mirror);
                var expected=mirror==Mirror.LEFT_RIGHT?new BlockPos(relative.getX(),0,-relative.getZ())
                        :mirror==Mirror.FRONT_BACK?new BlockPos(-relative.getX(),0,relative.getZ()):relative;
                helper.assertTrue(LargeMahjongTableBlock.center(origin.offset(expected),mirrored).equals(origin),"Mirrored part still resolves to transformed center");
            }
        }
        helper.succeed();
    }

    @GameTest
    public void physicalCapacityClearsReadinessPreservesOccupantsAndRejectsCountForgery(GameTestHelper helper) {
        try(Scenario s=new Scenario(helper,4,false)) {
            for(int seat=0;seat<4;seat++)s.lobby(seat,LobbyActionPayload.SET_READY,true,s.state().mode());
            long oldRevision=s.state().revision();var occupants=s.state().seats().stream().map(LobbyRegistry.Seat::playerId).toList();
            FurnitureTestLayout.chairs(helper.getLevel(),s.center,3);s.service.tick();
            helper.assertTrue(s.state().seats().size()==4 && s.state().seats().stream().map(LobbyRegistry.Seat::playerId).toList().equals(occupants)
                    && !s.state().allReady() && s.state().seats().stream().noneMatch(LobbyRegistry.Seat::ready),"Changing to three-player furniture cannot kick any of four occupants and clears readiness");
            s.service.handleAction(s.players.getFirst(),new LobbyActionPayload(s.views.getFirst(),oldRevision,LobbyActionPayload.SET_READY,true));
            helper.assertTrue(s.state().seats().stream().noneMatch(LobbyRegistry.Seat::ready),"Old preparation cannot bypass the physical mismatch");
            s.lobby(3,LobbyActionPayload.LEAVE,false,0);s.service.tick();
            helper.assertTrue(s.state().seats().size()==3 && s.state().seats().stream().map(LobbyRegistry.Seat::playerId).toList().equals(occupants.subList(0,3)),"Voluntary leave enables three-player mode with order preserved");
            s.lobby(0,LobbyActionPayload.SET_MODE,false,LobbyRegistry.SOUTH);
            helper.assertTrue(s.state().mode()==LobbyRegistry.THREE_SINGLE,"Forged count toggle cannot override the chairs");
            s.lobby(0,LobbyActionPayload.SET_MODE,false,LobbyRegistry.THREE_SOUTH);
            helper.assertTrue(s.state().mode()==LobbyRegistry.THREE_SOUTH,"Initial east still chooses the match format");
        }
        helper.succeed();
    }

    @GameTest
    public void bothPlayerCountsPauseBeforeDiscardAndRequireEveryFreshRecoveryVote(GameTestHelper helper) {
        for(int count:List.of(3,4)) try(Scenario s=new Scenario(helper,count,true)) {
            var before=s.cores();var game=s.game(0);
            helper.getLevel().setBlockAndUpdate(s.center.south(2),Blocks.AIR.defaultBlockState());
            // No lifecycle tick: the command itself must detect and reject against the broken layout.
            s.action(0,GameActionPayload.DISCARD,game.drawnTile(),-1);
            helper.assertTrue(s.game(0).paused() && s.game(0).statusKey().equals("message.riichicraft.structure.paused") && s.cores().equals(before),"Missing chair pauses without consuming tiles or settling points");
            long invalidRevision=s.game(0).revision();
            FurnitureTestLayout.chairs(helper.getLevel(),s.center,count==3?4:3);s.service.tick();
            helper.assertTrue(s.game(0).revision()>invalidRevision,"Changing from missing chairs to the wrong valid count publishes fresh public furniture status even while still paused");
            s.action(0,GameActionPayload.RESUME,-1,-1);
            helper.assertTrue(s.game(0).paused() && s.game(0).seats().stream().noneMatch(GameSnapshot.Seat::confirmed),"A different valid count cannot resume the original game");
            FurnitureTestLayout.chairs(helper.getLevel(),s.center,count);s.service.tick();
            for(int seat=0;seat<count;seat++) {
                s.action(seat,GameActionPayload.RESUME,-1,-1);
                helper.assertTrue(s.game(0).paused()==(seat<count-1) && s.cores().equals(before),"Only the final original participant resumes the unchanged game");
            }
            // Rotation invalidates preparation for recovery again.
            var chair=helper.getLevel().getBlockState(s.center.east(2));
            helper.getLevel().setBlockAndUpdate(s.center.east(2),chair.rotate(Rotation.CLOCKWISE_90));s.service.tick();
            helper.assertTrue(s.game(0).paused(),"Wrong chair facing also pauses active play");
            helper.getLevel().destroyBlock(s.center.west(),false);
            helper.assertTrue(s.game(0)==null && s.service.registry().snapshot(s.id).isEmpty(),"Breaking any table part terminates and removes its single lobby");
        }
        helper.succeed();
    }

    @GameTest
    public void intermissionLayoutChangeRevokesVotesAndRetainsPaidResult(GameTestHelper helper) {
        try(Scenario s=new Scenario(helper,3,true)) {
            // Start a genuine east match rather than fabricating a terminal DTO.
            s.action(0,GameActionPayload.ABORT,-1,-1);s.lobby(0,LobbyActionPayload.SET_MODE,false,LobbyRegistry.THREE_EAST);s.start();
            s.finishRound();var paid=s.game(0);long epoch=paid.match().continueEpoch();
            s.action(0,GameActionPayload.NEXT_ROUND,-1,epoch);
            helper.getLevel().setBlockAndUpdate(s.center.north(2),Blocks.AIR.defaultBlockState());s.service.tick();
            helper.assertTrue(s.game(0).paused() && s.game(0).rules().result().equals(paid.rules().result())
                    && s.game(0).match().continueEpoch()>epoch && s.game(0).match().confirmations().stream().noneMatch(Boolean::booleanValue),"Chair loss revokes continuation votes but retains the already-paid result");
            FurnitureTestLayout.chairs(helper.getLevel(),s.center,3);s.service.tick();
            s.action(1,GameActionPayload.NEXT_ROUND,-1,epoch);
            helper.assertTrue(s.game(0).match().confirmations().stream().noneMatch(Boolean::booleanValue),"Repaired furniture cannot reuse the old continuation epoch");
            long fresh=s.game(0).match().continueEpoch();
            for(int seat=0;seat<3;seat++)s.action(seat,GameActionPayload.NEXT_ROUND,-1,fresh);
            helper.assertTrue(!s.game(0).gameId().equals(paid.gameId()) && !s.game(0).paused(),"Three fresh continuation votes deal exactly the next round");
        }
        helper.succeed();
    }

    @GameTest
    public void structureSnapshotCountIsBoundedAndRoundTrips(GameTestHelper helper) {
        for(int count:List.of(3,4)) for(int physical:List.of(-1,0,3,4)) {
            var seats=new ArrayList<LobbySnapshotPayload.Seat>();for(int i=0;i<count;i++)seats.add(new LobbySnapshotPayload.Seat(null,"",false));
            var value=new LobbySnapshotPayload(UUID.randomUUID(),UUID.randomUUID(),BlockPos.ZERO,1,seats,-1,false,"",null,count==3?3:0,physical);
            var buf=new RegistryFriendlyByteBuf(Unpooled.buffer(),helper.getLevel().registryAccess());
            try { LobbySnapshotPayload.CODEC.encode(buf,value);helper.assertTrue(value.equals(LobbySnapshotPayload.CODEC.decode(buf)) && buf.readableBytes()==0,"Furniture status survives complete bounded packet without hidden state"); }
            finally { buf.release(); }
        }
        boolean rejected=false;
        try { new LobbySnapshotPayload(UUID.randomUUID(),UUID.randomUUID(),BlockPos.ZERO,1,List.of(
                new LobbySnapshotPayload.Seat(null,"",false),new LobbySnapshotPayload.Seat(null,"",false),new LobbySnapshotPayload.Seat(null,"",false)),-1,false,"",null,3,Integer.MAX_VALUE); }
        catch(IllegalArgumentException expected) { rejected=true; }
        helper.assertTrue(rejected,"Unknown furniture counts are rejected");helper.succeed();
    }

    private static void place(GameTestHelper helper,BlockPos center,Player player,ItemStack stack,Direction facing) {
        helper.getLevel().setBlockAndUpdate(center.below(),Blocks.STONE.defaultBlockState());
        player.setPos(center.getX()+3.5,center.getY(),center.getZ()+0.5);player.setYRot(FurnitureTestLayout.yaw(facing.getOpposite()));player.setXRot(0);
        player.setItemInHand(InteractionHand.MAIN_HAND,stack);
        stack.useOn(new UseOnContext(player,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(center.below()).add(0,0.5,0),Direction.UP,center.below(),false)));
    }

    static final class Scenario implements AutoCloseable {
        final LobbyService service;final BlockPos center;final UUID id;final List<ConnectedTestPlayer> fixtures=new ArrayList<>();
        final List<ServerPlayer> players=new ArrayList<>();final List<UUID> views=new ArrayList<>();
        Scenario(GameTestHelper helper,int count,boolean start) {
            service=LobbyService.get(helper.getLevel().getServer());center=helper.absolutePos(new BlockPos(3,2,3));
            FurnitureTestLayout.body(helper.getLevel(),center,Direction.NORTH);FurnitureTestLayout.chairs(helper.getLevel(),center,count);
            UUID found=null;
            for(int i=0;i<count;i++) {
                var fixture=new ConnectedTestPlayer(helper.getLevel().getServer(),helper.getLevel(),center,"Furn"+UUID.randomUUID().toString().substring(0,7));
                fixtures.add(fixture);players.add(fixture.player());
                UUID view=service.open(fixture.player(),center.offset(i%3-1,0,i/3-1));views.add(view);
                helper.assertTrue(view!=null,"Every table part opens an accessible canonical view");
                // A new physical layout invalidates the initial preparation revision exactly once.
                long revision=found==null?1:service.registry().snapshot(found).orElseThrow().revision();
                service.handleAction(fixture.player(),new LobbyActionPayload(view,revision,LobbyActionPayload.JOIN,false));
                UUID actual=service.registry().tableForPlayer(fixture.player().getUUID()).orElseThrow();
                if(found==null)found=actual;
                helper.assertTrue(found.equals(actual),"Opening different parts must join the same lobby");
            }
            id=Objects.requireNonNull(found);
            if(start)start();
        }
        LobbyRegistry.Snapshot state(){return service.registry().snapshot(id).orElseThrow();}
        GameSnapshot game(int seat){return service.gameView(id,players.get(seat).getUUID());}
        void lobby(int seat,int action,boolean ready,int mode){service.handleAction(players.get(seat),new LobbyActionPayload(views.get(seat),state().revision(),action,ready,mode));}
        void start(){for(int seat=0;seat<players.size();seat++)lobby(seat,LobbyActionPayload.SET_READY,true,state().mode());lobby(0,LobbyActionPayload.START,false,state().mode());}
        void action(int seat,int action,int tile,long window){var game=game(seat);service.handleGameAction(players.get(seat),new GameActionPayload(views.get(seat),game.gameId(),game.revision(),action,tile,window,-1,List.of()));}
        List<Object> cores(){return players.stream().map(player->{var g=service.gameView(id,player.getUUID());return (Object)List.of(g.gameId(),g.turnSeat(),g.remainingTiles(),g.ownHand(),g.drawnTile(),g.rules());}).toList();}
        void finishRound(){for(int step=0;step<600;step++){var first=game(0);if(first.rules().result()!=null)return;boolean acted=false;for(int seat=0;seat<players.size();seat++){var own=game(seat);if(!own.rules().responses().isEmpty()){action(seat,GameActionPayload.PASS,-1,own.rules().opportunity().serial());acted=true;break;}}if(!acted){int seat=first.turnSeat();var own=game(seat);action(seat,GameActionPayload.DISCARD,own.drawnTile(),-1);}}throw new AssertionError("Finite real round");}
        @Override public void close(){for(var player:players)service.disconnect(player);for(var fixture:fixtures)fixture.close();service.removeTable(players.getFirst().level(),center);}
    }
}
