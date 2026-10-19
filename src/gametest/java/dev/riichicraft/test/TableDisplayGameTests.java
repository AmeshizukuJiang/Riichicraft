package dev.riichicraft.test;

import dev.riichicraft.network.*;
import dev.riichicraft.table.TableDisplayCache;
import dev.riichicraft.table.TableTileLayout;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.Level;

public final class TableDisplayGameTests {
    public static TableDisplaySnapshot opening(boolean three) {
        var seats=new ArrayList<TableDisplaySnapshot.Seat>();
        for(int side:three?new int[]{0,1,3}:new int[]{0,1,2,3})
            seats.add(new TableDisplaySnapshot.Seat(side,side==0?14:13,List.of(),List.of(),List.of()));
        return new TableDisplaySnapshot(UUID.randomUUID(),false,false,0,three?54:69,List.of(100),seats);
    }
    private static TableDisplaySnapshot capacity(boolean three) {
        var seats=new ArrayList<TableDisplaySnapshot.Seat>(); var melds=new ArrayList<TableDisplaySnapshot.Meld>();
        int offset=three?36:0;
        for(int i=0;i<4;i++) {int t=offset+i*4;melds.add(new TableDisplaySnapshot.Meld(3,List.of(t,t+1,t+2,t+3),-1,-1,-1));}
        for(int side:three?new int[]{0,1,3}:new int[]{0,1,2,3})
            seats.add(new TableDisplaySnapshot.Seat(side,side==0?2:13,List.of(),side==0?melds:List.of(),three && side==0?List.of(120,121,122,123):List.of()));
        return new TableDisplaySnapshot(UUID.randomUUID(),false,false,0,three?46:65,List.of(108,112,116,124,128),seats);
    }
    public static TableDisplaySnapshot showcase(boolean three) {
        var melds = three ? List.of(new TableDisplaySnapshot.Meld(1, List.of(36,37,38), 1, 36, -1),
                new TableDisplaySnapshot.Meld(3, List.of(52,53,54,55), -1, -1, -1))
                : List.of(new TableDisplaySnapshot.Meld(0, List.of(0,4,8), 3, 0, -1),
                new TableDisplaySnapshot.Meld(1, List.of(12,13,14), 2, 13, -1),
                new TableDisplaySnapshot.Meld(2, List.of(16,17,18,19), 1, 18, 16),
                new TableDisplaySnapshot.Meld(3, List.of(52,53,54,55), -1, -1, -1));
        List<TableDisplaySnapshot.Seat> seats = new ArrayList<>();
        int[] sides = three ? new int[] {0,1,3} : new int[] {0,1,2,3};
        for (int i = 0; i < sides.length; i++) {
            List<TableDisplaySnapshot.Discard> river = new ArrayList<>();
            for (int j = 0; j < 19; j++) river.add(new TableDisplaySnapshot.Discard(60 + i * 19 + j, j == 5));
            seats.add(new TableDisplaySnapshot.Seat(sides[i], i == 0 ? (three ? 8 : 2) : 13, river,
                    i == 0 ? melds : List.of(), three && i == 0 ? List.of(120,121) : List.of()));
        }
        return new TableDisplaySnapshot(UUID.randomUUID(), false, false, 0, three ? 12 : 15,
                three ? List.of(40,44) : List.of(40,44,48), seats);
    }

    public static TableDisplaySnapshot revealedUra(boolean three) {
        var s=capacity(three);
        return new TableDisplaySnapshot(s.gameId(),false,true,s.frontSide(),s.liveTiles(),s.indicators(),s.seats(),
                s.indicators().stream().map(tile->tile+1).toList());
    }

    @GameTest
    public void publicCodecAndAllAllocationBounds(GameTestHelper helper) {
        for (var snapshot : new TableDisplaySnapshot[] {showcase(false), showcase(true), capacity(false), capacity(true), revealedUra(false),revealedUra(true),null}) {
            var payload = new TableDisplayPayload(Level.OVERWORLD, UUID.randomUUID(), new BlockPos(-17,64,31), 81, snapshot);
            var buf = buffer(helper);
            try {
                TableDisplayPayload.CODEC.encode(buf, payload);
                helper.assertTrue(TableDisplayPayload.CODEC.decode(buf).equals(payload) && buf.readableBytes() == 0,
                        "Three/four-player public data and removals round-trip exactly");
            } finally { buf.release(); }
        }
        // Corrupt each collection's length before any associated list can be allocated.
        for (int target = 0; target < 5; target++) for (int length : new int[] {-1, Integer.MAX_VALUE}) {
            var b = buffer(helper);
            try {
                b.writeResourceKey(Level.OVERWORLD); b.writeUUID(UUID.randomUUID()); b.writeBlockPos(BlockPos.ZERO); b.writeVarLong(1); b.writeBoolean(true);
                b.writeUUID(UUID.randomUUID()); b.writeBoolean(false); b.writeBoolean(false); b.writeVarInt(0); b.writeVarInt(69);
                b.writeVarInt(target == 0 ? length : 0);
                if (target > 0) { b.writeVarInt(0); b.writeVarInt(target == 1 ? length : 4); }
                if (target > 1) { b.writeVarInt(0); b.writeVarInt(13); b.writeVarInt(target == 2 ? length : 0); }
                if (target > 2) b.writeVarInt(target == 3 ? length : 0);
                if (target > 3) b.writeVarInt(length);
                boolean rejected = false;
                try { TableDisplayPayload.CODEC.decode(b); } catch (DecoderException expected) { rejected = true; }
                helper.assertTrue(rejected, "Invalid public collection length rejected at its field: " + target);
            } finally { b.release(); }
        }
        helper.succeed();
    }

    @GameTest
    public void wallCountsAndUnrevealedFacesAreBounded(GameTestHelper helper) {
        for (int live : new int[] {-1,70,Integer.MAX_VALUE}) {
            var b=buffer(helper);
            try {
                b.writeResourceKey(Level.OVERWORLD); b.writeUUID(UUID.randomUUID()); b.writeBlockPos(BlockPos.ZERO); b.writeVarLong(1); b.writeBoolean(true);
                b.writeUUID(UUID.randomUUID()); b.writeBoolean(false); b.writeBoolean(false); b.writeVarInt(0); b.writeVarInt(live);
                boolean rejected=false;
                try {TableDisplayPayload.CODEC.decode(b);} catch(DecoderException expected){rejected=true;}
                helper.assertTrue(rejected,"Invalid live count is rejected before reading collections");
            } finally {b.release();}
        }
        for(boolean three:List.of(false,true)) {
            var s=showcase(three); var wall=TableTileLayout.tiles(s).stream().filter(t->t.seat()==-1).toList();
            helper.assertTrue(wall.size()==s.liveTiles()+14,"World wall contains live plus exactly fourteen reserve tiles");
            helper.assertTrue(wall.stream().filter(t->t.face()>=0).map(TableTileLayout.Tile::face).toList().size()==s.indicators().size()
                    && wall.stream().filter(t->t.face()>=0).allMatch(t->t.group()==TableTileLayout.Group.INDICATOR && s.indicators().contains(t.face())),
                    "Only already public indicators receive real face identities; no duplicated central indicator line");
            helper.assertTrue(wall.stream().filter(t->t.face()<0).allMatch(t->t.down()),"Unrevealed live/reserve models use only the hidden sentinel");
            var maximum=capacity(three);
            helper.assertTrue(maximum.replacementDraws()==(three?8:4) && maximum.indicators().size()==5,
                    "Maximum shared replacement and five-indicator public state is representable");
            boolean rejected=false;
            try {new TableDisplaySnapshot(maximum.gameId(),false,false,0,maximum.liveTiles()+1,maximum.indicators(),maximum.seats());}
            catch(IllegalArgumentException expected){rejected=true;}
            helper.assertTrue(rejected,"Replacement consumption cannot leave more than the corresponding maximum live wall");
        }
        helper.succeed();
    }

    @GameTest
    public void wallsNorthsLongRiversAndMeldsFitEveryPhysicalOrientation(GameTestHelper helper) {
        for(boolean three:List.of(false,true)) for(int rotation=0;rotation<4;rotation++) for(boolean finished:List.of(false,true)) {
            var base=showcase(three); List<TableDisplaySnapshot.Seat> seats=new ArrayList<>();
            for(int seat=0;seat<base.seats().size();seat++) {
                var original=base.seats().get(seat);var river=new ArrayList<TableDisplaySnapshot.Discard>();
                for(int i=0;i<48;i++)river.add(new TableDisplaySnapshot.Discard(36+i,i==5));
                seats.add(new TableDisplaySnapshot.Seat((original.side()+rotation)%4,original.concealedCount(),river,original.melds(),original.norths()));
            }
            var scene=new TableDisplaySnapshot(base.gameId(),false,finished,rotation,base.liveTiles(),base.indicators(),seats,
                    finished?base.indicators().stream().map(tile->tile+1).toList():List.of());
            var tiles=TableTileLayout.tiles(scene);
            for(int i=0;i<tiles.size();i++) {
                var a=tiles.get(i);float[] ab=box(a);
                helper.assertTrue(Math.abs(a.x())+ab[0]<23.5 && Math.abs(a.z())+ab[2]<23.5,"Full tile bounds fit the tabletop");
                for(int j=0;j<i;j++) {
                    var b=tiles.get(j);float[] bb=box(b);
                    boolean overlaps=Math.abs(a.x()-b.x())<ab[0]+bb[0]-0.001
                            && Math.abs(a.y()-b.y())<ab[1]+bb[1]-0.001 && Math.abs(a.z()-b.z())<ab[2]+bb[2]-0.001;
                    helper.assertTrue(!overlaps,"Tile bodies must not intersect: "+a+" / "+b);
                }
            }
        }
        helper.succeed();
    }

    private static float[] box(TableTileLayout.Tile t) {
        float x=t.scale(), y=(t.flat()?1.25F:2.75F)*t.scale()/2, z=(t.flat()?2.75F:1.25F)*t.scale()/2;
        return Math.floorMod(Math.round(t.yaw()),180)==90?new float[]{z,y,x}:new float[]{x,y,z};
    }

    @GameTest
    public void removalAndLevelChangesCannotResurrectOldPublicTiles(GameTestHelper helper) {
        var cache = new TableDisplayCache(); var id = UUID.randomUUID(); var scene = showcase(false);
        var first = new TableDisplayPayload(Level.OVERWORLD, id, BlockPos.ZERO, 1, scene);
        helper.assertTrue(cache.accept(first, Level.OVERWORLD) && cache.snapshots().size() == 1, "First public snapshot is visible");
        cache.accept(new TableDisplayPayload(Level.OVERWORLD, id, BlockPos.ZERO, 2, null), Level.OVERWORLD);
        helper.assertTrue(!cache.accept(first, Level.OVERWORLD) && cache.snapshots().isEmpty(), "Delayed pre-removal data cannot resurrect tiles");
        cache.clearTables();
        helper.assertTrue(!cache.accept(new TableDisplayPayload(Level.NETHER, id, BlockPos.ZERO, 3, scene), Level.OVERWORLD), "Wrong dimension rejected");
        helper.assertTrue(!cache.accept(new TableDisplayPayload(Level.OVERWORLD, id, BlockPos.ZERO, 2, scene), Level.OVERWORLD), "Level transition retains sequence ordering");
        helper.assertTrue(cache.accept(new TableDisplayPayload(Level.OVERWORLD, id, BlockPos.ZERO, 4, scene), Level.OVERWORLD), "Re-entering range accepts a new send of unchanged public content");
        cache.reset(); helper.assertTrue(cache.accept(first, Level.OVERWORLD), "A genuinely new connection resets its sequence domain");
        helper.succeed();
    }

    @GameTest
    public void allMeldSourcesRedClosedKanAndExactAddedTileHaveCorrectGeometry(GameTestHelper helper) {
        for (int source = 1; source < 4; source++) {
            var seats = new ArrayList<>(showcase(false).seats());
            seats.set(0, new TableDisplaySnapshot.Seat(0, 11, List.of(),
                    List.of(new TableDisplaySnapshot.Meld(1, List.of(16,17,18), source, 17, -1)), List.of()));
            var scene = new TableDisplaySnapshot(UUID.randomUUID(), false, false, 0, 15, List.of(), seats);
            var meld = TableTileLayout.tiles(scene).stream().filter(t -> t.seat() == 0 && t.group() == TableTileLayout.Group.MELD).toList();
            int slot = source == 3 ? 0 : source == 2 ? 1 : 2;
            helper.assertTrue(meld.get(slot).face() == 17 && meld.get(slot).yaw() == 90
                    && meld.stream().filter(t -> t.yaw() == 90).count() == 1, "Claimed tile alone lies sideways toward its actual physical provider");
        }
        var scene = showcase(false); var tiles = TableTileLayout.tiles(scene);
        helper.assertTrue(tiles.stream().filter(t -> t.group() == TableTileLayout.Group.HAND).allMatch(t -> t.face() == -1 && !t.flat()),
                "No concealed hand placement contains any face identity, even from behind");
        var melds = tiles.stream().filter(t -> t.group() == TableTileLayout.Group.MELD).toList();
        var stack = melds.stream().filter(t -> t.face() == 16).findFirst().orElseThrow();
        var claimed = melds.stream().filter(t -> t.face() == 18).findFirst().orElseThrow();
        helper.assertTrue(stack.x() == claimed.x() && stack.z() == claimed.z() && stack.y() > claimed.y()
                && stack.yaw() == claimed.yaw(), "The exact red added tile (lowest ID) stacks on the original sideways pon tile");
        helper.assertTrue(melds.stream().filter(t -> t.down()).count() == 2 && melds.stream().anyMatch(t -> t.face() == 52),
                "Closed kan covers exactly two ends while displaying its red five");
        for (boolean three : List.of(false,true)) for (var t : TableTileLayout.tiles(showcase(three))) {
            helper.assertTrue(Float.isFinite(t.x()) && Float.isFinite(t.y()) && Float.isFinite(t.z())
                    && Math.abs(t.x()) < 23 && Math.abs(t.z()) < 23 && t.y() > 0, "Every public placement fits within the three-block tabletop");
        }
        helper.succeed();
    }

    @GameTest
    public void calledRiichiTileLeavesNoDuplicateAndNextRetainedDiscardTurns(GameTestHelper helper) {
        List<GameSnapshot.Seat> seats = new ArrayList<>(); List<GameSnapshot.SeatRules> rules = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            seats.add(new GameSnapshot.Seat(13, i == 0 ? List.of(0,4,8,12) : List.of(), true, false));
            rules.add(new GameSnapshot.SeatRules(List.of(), i == 0 ? List.of(new GameSnapshot.RiverMark(false,false,-1),
                    new GameSnapshot.RiverMark(true,false,1), new GameSnapshot.RiverMark(false,false,-1),
                    new GameSnapshot.RiverMark(false,false,-1)) : List.of(), 25000, i == 0 ? 1 : 0));
        }
        var state = new GameSnapshot.RuleState(0,0,27,0,1,List.of(100),rules,List.of(),List.of(),List.of(),false,false,null,List.of(),null);
        var view = new GameSnapshot(UUID.randomUUID(), 7,1,60,seats,List.of(),-1,false,"",state);
        var display = TableDisplaySnapshot.fromSpectator(view,0,List.of(0,1,2,3),(seat,index)->-1);
        helper.assertTrue(display.seats().getFirst().river().equals(List.of(new TableDisplaySnapshot.Discard(0,false),
                new TableDisplaySnapshot.Discard(8,true),new TableDisplaySnapshot.Discard(12,false))), "Called tile is absent; later discards compact and next retained tile carries riichi orientation");
        var privateView = new GameSnapshot(view.gameId(),7,1,60,seats,List.of(60),60,false,"",state);
        boolean rejected = false;
        try { TableDisplaySnapshot.fromSpectator(privateView,0,List.of(0,1,2,3),(seat,index)->-1); }
        catch (IllegalArgumentException expected) { rejected = true; }
        helper.assertTrue(rejected, "The public adapter refuses a recipient's private snapshot");
        helper.succeed();
    }
    private static RegistryFriendlyByteBuf buffer(GameTestHelper helper) { return new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess()); }
}
