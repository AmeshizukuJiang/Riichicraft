package dev.riichicraft.client;

import dev.riichicraft.network.TableDisplayPayload;
import dev.riichicraft.network.TableDisplaySnapshot;
import dev.riichicraft.network.TableScenePayload;
import dev.riichicraft.table.TableAnimationPlayer;
import dev.riichicraft.table.TableTileLayout;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** Client-side geometry/privacy regression, complementary to live camera and mouse input tests. */
public final class TableCameraChecks {
    private TableCameraChecks() { }

    public static void verifyVisibleHands() {
        UUID game = UUID.randomUUID(), table = UUID.randomUUID(), view = UUID.randomUUID();
        var seats = IntStream.range(0, 4).mapToObj(side -> new TableDisplaySnapshot.Seat(side, 14,
                List.of(), List.of(), List.of())).toList();
        var state = new TableDisplaySnapshot(game, false, false, 0, 69, List.of(132), seats);
        var payload = new TableDisplayPayload(Level.OVERWORLD, table, BlockPos.ZERO, 1, state);
        var still = TableAnimationPlayer.still(state);
        var hands = IntStream.range(0, 4).mapToObj(seat -> IntStream.range(seat * 20, seat * 20 + 13).boxed().toList()).toList();
        for (int owner = 0; owner < 4; owner++) {
            var limited = new ArrayList<List<Integer>>();
            for (int seat = 0; seat < 4; seat++) limited.add(seat == owner ? hands.get(seat) : List.of());
            var scene = new TableScenePayload(view, table, Level.OVERWORLD, BlockPos.ZERO, game, 2, owner,
                    List.of(0, 1, 2, 3), true, limited, List.of(-1, -1, -1, -1), false);
            for (boolean top : List.of(false, true)) {
                var frames = TableDisplayRenderer.visibleFrames(payload, still, scene, top);
                int own = owner;
                var visible = frames.stream().filter(f -> f.tile().group() == TableTileLayout.Group.HAND && f.tile().face() >= 0).toList();
                require(visible.size() == 13, "Current private count overrides stale public 14-card hand");
                require(visible.stream().allMatch(f -> f.tile().seat() == own), "Other participant hands remain covered");
                require(new HashSet<>(visible.stream().map(f -> f.tile().face()).toList()).equals(new HashSet<>(hands.get(owner))), "Physical private identities preserved");
                require(visible.stream().allMatch(f -> f.pose().pitch() == (top ? -90 : 0)), "Own hand posture follows local perspective");
                require(new HashSet<>(frames.stream().map(f -> f.tile().key()).toList()).size() == frames.size(), "No duplicate anonymous/private hand slot");
                var detached = new TableScenePayload(view, table, Level.OVERWORLD, BlockPos.ZERO, game, 2, owner,
                        List.of(0, 1, 2, 3), false, limited, List.of(-1, -1, -1, -1), false);
                var offChair = TableDisplayRenderer.visibleFrames(payload, still, detached, false);
                require(offChair.stream().filter(f -> f.tile().group() == TableTileLayout.Group.HAND && f.tile().face() >= 0)
                        .allMatch(f -> f.tile().seat() == own), "Dismounted participant never becomes an all-hand observer");
            }
        }
        var observer = new TableScenePayload(view, table, Level.OVERWORLD, BlockPos.ZERO, game, 2, -1,
                List.of(0, 1, 2, 3), false, hands, List.of(-1, -1, -1, -1), false);
        var visible = TableDisplayRenderer.visibleFrames(payload, still, observer, true);
        require(visible.stream().filter(f -> f.tile().group() == TableTileLayout.Group.HAND && f.tile().face() >= 0).count() == 52,
                "Explicit observer scene reveals all 52 current hand cards");
        require(visible.stream().filter(f -> f.tile().group() == TableTileLayout.Group.HAND).allMatch(f -> f.pose().pitch() == -90), "Observer overhead shows flat hands");
        var result = new TableScenePayload(view, table, Level.OVERWORLD, BlockPos.ZERO, game, 2, 0,
                List.of(0, 1, 2, 3), true, hands, List.of(-1, -1, -1, -1), true);
        require(TableDisplayRenderer.visibleFrames(payload, still, result, false).stream()
                .filter(f -> f.tile().group() == TableTileLayout.Group.HAND).allMatch(f -> f.tile().flat() && f.pose().pitch() == -90),
                "Settlement lays every authorized hand flat even in normal perspective");
        var other = new TableScenePayload(view, table, Level.OVERWORLD, BlockPos.ZERO, UUID.randomUUID(), 2, -1,
                List.of(0, 1, 2, 3), false, hands, List.of(-1, -1, -1, -1), false);
        require(TableDisplayRenderer.visibleFrames(payload, still, other, true) == still, "Stale game scene cannot replace another game's hands");
        verifyIncomingDraw();
    }

    private static void verifyIncomingDraw() {
        UUID game=UUID.randomUUID(),id=UUID.randomUUID(),epoch=UUID.randomUUID();
        var seats=IntStream.range(0,4).mapToObj(side->new TableDisplaySnapshot.Seat(side,13,List.of(),List.of(),List.of())).toList();
        var before=new TableDisplaySnapshot(game,false,false,0,69,List.of(132),seats);
        var next=new ArrayList<>(seats);next.set(0,new TableDisplaySnapshot.Seat(0,14,List.of(),List.of(),List.of()));
        var after=new TableDisplaySnapshot(game,false,false,0,68,List.of(132),next);
        var oldPacket=new TableDisplayPayload(Level.OVERWORLD,id,BlockPos.ZERO,1,before,epoch,0,List.of());
        var packet=new TableDisplayPayload(Level.OVERWORLD,id,BlockPos.ZERO,2,after,epoch,1,
                List.of(new dev.riichicraft.network.TableAnimationStep(1,before,after)));
        var scene=new TableScenePayload(UUID.randomUUID(),id,Level.OVERWORLD,BlockPos.ZERO,game,4,0,List.of(0,1,2,3),true,
                List.of(IntStream.range(0,14).boxed().toList(),List.of(),List.of(),List.of()),List.of(13,-1,-1,-1),false,after.hashCode());
        for(boolean top:List.of(false,true)) {
            var early=TableDisplayRenderer.visibleFrames(oldPacket,TableAnimationPlayer.still(before),scene,top);
            require(early.stream().noneMatch(f->f.tile().face()==13),"Private-first packet never flashes the new tile at the absent hand destination");
            var oldSameCount=new TableDisplaySnapshot(game,false,false,0,69,List.of(132),next);
            var stale=TableDisplayRenderer.visibleFrames(oldPacket,TableAnimationPlayer.still(oldSameCount),scene,top);
            require(stale.stream().noneMatch(f->f.tile().face()==13),"A replacement or queued older draw cannot reuse the previous fourteen-card destination");
            var player=new TableAnimationPlayer();player.accept(oldPacket,0);player.accept(packet,0);
            var endpoint=TableDisplayRenderer.visibleFrames(packet,TableAnimationPlayer.still(after),scene,top).stream()
                    .filter(f->f.tile().face()==13).findFirst().orElseThrow().pose();
            for(long time:new long[]{0,50_000_000,150_000_000,259_000_000,260_000_000}) {
                var frames=TableDisplayRenderer.visibleFrames(packet,player.frames(id,time),scene,top);
                var drawn=frames.stream().filter(f->f.tile().face()==13).toList();
                require(drawn.size()==1,"Draw is rendered once throughout its actual flight");
                require((time==260_000_000)==drawn.getFirst().pose().equals(endpoint),"Draw arrives at hand only at the animation endpoint");
            }
        }
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
