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
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
