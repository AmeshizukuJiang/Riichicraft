package dev.riichicraft.client;

import dev.riichicraft.network.TableAnimationStep;
import dev.riichicraft.network.TableDisplayPayload;
import dev.riichicraft.network.TableDisplaySnapshot;
import dev.riichicraft.network.TableScenePayload;
import dev.riichicraft.table.TableAnimationPlayer;
import dev.riichicraft.table.TableTileLayout;
import dev.riichicraft.table.TileMotion;
import dev.riichicraft.test.TableAnimationGameTests;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** Client geometry fixtures: identity origins, message order and queue history, without new wire data. */
public final class TableHandAnimationChecks {
    private static int assertions;
    private TableHandAnimationChecks() { }

    public static void verify() {
        assertions = 0;
        for (var action : TableAnimationGameTests.scenes()) {
            int owner = switch (action.name()) { case "pon", "open-kan", "added-kan", "deferred-flip-and-discard" -> 2;
                case "chi" -> 1; default -> 0; };
            List<Integer> sourceTiles = switch (action.name()) {
                case "pon" -> List.of(1, 2); case "open-kan" -> List.of(1, 2, 3);
                case "added-kan" -> List.of(3); case "closed-kan-and-flip" -> List.of(0, 1, 2, 3);
                case "kita" -> List.of(120); case "chi" -> List.of(4, 8);
                case "deferred-flip-and-discard" -> List.of(8); default -> List.of(0);
            };
            for (int rotation = 0; rotation < 4; rotation++) for (boolean observer : List.of(false, true))
                for (boolean overhead : List.of(false, true)) verifyAction(rotate(action, rotation), owner, sourceTiles, observer, overhead);
        }
        verifyQueuedActions();
        System.out.println("PASS private hand animation: " + assertions + " assertions; 128 action/view/orientation fixtures and queued actions");
    }

    private static void verifyAction(TableAnimationGameTests.Scene action, int owner, List<Integer> sources,
            boolean observer, boolean overhead) {
        var before = action.before(); var after = action.after();
        Set<Integer> occupied = publicIds(before); occupied.addAll(publicIds(after)); occupied.addAll(sources);
        var oldHands = new ArrayList<List<Integer>>();
        for (int seat = 0; seat < before.seats().size(); seat++) {
            var hand = new ArrayList<Integer>(seat == owner ? sources : List.of());
            while (hand.size() < before.seats().get(seat).concealedCount()) hand.add(nextFree(occupied, before.seats().size()));
            oldHands.add(List.copyOf(hand));
        }
        var newHands = new ArrayList<List<Integer>>();
        var oldDraws = new ArrayList<Integer>(); var newDraws = new ArrayList<Integer>();
        var afterPublic = publicIds(after);
        for (int seat = 0; seat < oldHands.size(); seat++) {
            var old = oldHands.get(seat);
            oldDraws.add(old.size() % 3 == 2 ? old.getLast() : -1);
            var hand = new ArrayList<>(old.stream().filter(t -> !afterPublic.contains(t)).toList());
            int draw = -1;
            while (hand.size() < after.seats().get(seat).concealedCount()) { draw = nextFree(occupied, before.seats().size()); hand.add(draw); }
            require(hand.size() == after.seats().get(seat).concealedCount(), "Fixture conserves each concealed count");
            newHands.add(List.copyOf(hand)); newDraws.add(draw);
        }
        UUID table = UUID.randomUUID(), epoch = UUID.randomUUID(), view = UUID.randomUUID();
        int own = observer ? -1 : owner;
        var first = packet(table, epoch, 0, before, List.of());
        var update = packet(table, epoch, 1, after, List.of(new TableAnimationStep(1, before, after)));
        var oldScene = scene(view, table, before, own, oldHands, oldDraws, 0);
        var newScene = scene(view, table, after, own, newHands, newDraws, 1);
        var history = new TableHandAnimation(); history.accept(oldScene);
        var initial = history.frames(first, TableAnimationPlayer.still(before), oldScene, overhead);
        history.accept(newScene);
        var early = history.frames(first, TableAnimationPlayer.still(before), newScene, overhead);
        require(handIdentities(initial).equals(handIdentities(early)), "Private-first message preserves the matching previous hand");
        var coldHistory = new TableHandAnimation(); coldHistory.accept(newScene);
        var coldEarly = coldHistory.frames(first, TableAnimationPlayer.still(before), newScene, overhead);
        for (int draw : newDraws) if (draw >= 0) {
            require(!handIdentities(early).contains(draw), "Private-first new draw does not flash with matching history");
            require(!handIdentities(coldEarly).contains(draw), "Private-first new draw does not flash without preceding private history");
        }
        var player = new TableAnimationPlayer(); player.accept(first, 0); player.accept(update, 0);
        var origin = history.frames(update, player.frames(table, 0), newScene, overhead);
        for (int tile : sources) {
            var previous = hand(initial, owner, tile);
            var flying = origin.stream().filter(f -> f.tile().key().equals("p:" + tile)).findFirst().orElseThrow();
            samePosition(previous.pose(), flying.pose(), "Exact private origin for " + action.name() + " tile=" + tile);
        }
        // Every remaining known copy also starts at its old identity slot, rather than jumping one slot left.
        for (int tile : oldHands.get(owner)) if (newHands.get(owner).contains(tile))
            samePosition(hand(initial, owner, tile).pose(), hand(origin, owner, tile).pose(), "Remaining tile preserves its own origin");
        var afterKeys = TableTileLayout.tiles(after).stream().map(TableTileLayout.Tile::key).collect(java.util.stream.Collectors.toSet());
        var removedWall = TableAnimationPlayer.still(before).stream()
                .filter(f -> f.tile().key().startsWith("w:") && !afterKeys.contains(f.tile().key())).toList();
        if (!removedWall.isEmpty()) for (int seat = 0; seat < newDraws.size(); seat++) {
            int draw = newDraws.get(seat);
            if (draw >= 0 && (observer || seat == owner))
                samePosition(hand(origin, seat, draw).pose(), removedWall.getFirst().pose(),
                        "Private source correction preserves the real live/rinshan origin for " + action.name());
        }
        for (long time : new long[] {0, 120_000_000, 239_000_000, 300_000_000, 700_000_000, 1_000_000_000}) {
            var frames = history.frames(update, player.frames(table, time), newScene, overhead);
            var identities = frames.stream().map(f -> physicalId(f.tile())).filter(id -> id >= 0).toList();
            require(new HashSet<>(identities).size() == identities.size(), "Each visible physical copy appears once");
            require(frames.stream().allMatch(f -> Float.isFinite(f.pose().x()) && Float.isFinite(f.pose().y())
                    && Float.isFinite(f.pose().z()) && Float.isFinite(f.pose().scale())), "Every corrected frame remains finite");
        }
        require(history.frames(update, player.frames(table, 2_000_000_000L), newScene, overhead)
                        .equals(TableDisplayRenderer.visibleFrames(update, TableAnimationPlayer.still(after), newScene, overhead)),
                "Exact existing endpoint after " + action.name());
        var publicFirst = new TableHandAnimation(); publicFirst.accept(oldScene);
        var pending = publicFirst.frames(update, new TableAnimationPlayer().frames(table, 0), oldScene, overhead);
        // Missing playback/history is harmless, while actual public-first playback never duplicates the departing copy.
        require(pending.isEmpty(), "Missing public track remains empty");
        var publicPlayer = new TableAnimationPlayer(); publicPlayer.accept(first, 0); publicPlayer.accept(update, 0);
        for (long time : new long[] {0, 120_000_000, 300_000_000, 700_000_000, 2_000_000_000L}) {
            var ahead = publicFirst.frames(update, publicPlayer.frames(table, time), oldScene, overhead);
            for (int tile : sources) require(ahead.stream().noneMatch(f -> f.tile().group() == TableTileLayout.Group.HAND && f.tile().face() == tile),
                    "Public-first declaration removes the old private duplicate");
            for (int seat = 0; seat < oldHands.size(); seat++) if (observer || seat == owner)
                for (int tile : oldHands.get(seat)) if (newHands.get(seat).contains(tile))
                    require(ahead.stream().filter(f -> physicalId(f.tile()) == tile).count() == 1,
                            "Public-first update retains each known copy, including after playback with the private packet still late");
            var identities = ahead.stream().map(f -> physicalId(f.tile())).filter(id -> id >= 0).toList();
            require(new HashSet<>(identities).size() == identities.size(), "Public-first update never duplicates a physical copy");
            for (int draw : newDraws) if (draw >= 0)
                require(!handIdentities(ahead).contains(draw), "Public-first playback never guesses an unknown replacement identity");
        }
        publicFirst.accept(newScene);
        require(publicFirst.frames(update, publicPlayer.frames(table, 2_000_000_000L), newScene, overhead)
                        .equals(TableDisplayRenderer.visibleFrames(update, TableAnimationPlayer.still(after), newScene, overhead)),
                "Late private endpoint converges exactly after playback");
        var participant = scene(UUID.randomUUID(), table, after, owner, newHands, newDraws, 1);
        var reset = publicFirst.frames(update, TableAnimationPlayer.still(after), participant, overhead);
        require(reset.stream().filter(f -> f.tile().group() == TableTileLayout.Group.HAND && f.tile().face() >= 0)
                .allMatch(f -> f.tile().seat() == owner), "A new participant view never reuses observer identities");
    }

    private static void verifyQueuedActions() {
        UUID game = UUID.randomUUID(), table = UUID.randomUUID(), view = UUID.randomUUID(), epoch = UUID.randomUUID();
        var seats = IntStream.range(0, 4).mapToObj(side -> new TableDisplaySnapshot.Seat(side, side == 0 ? 14 : 13,
                List.of(), List.of(), List.of())).toList();
        var before = new TableDisplaySnapshot(game, false, false, 0, 69, List.of(132), seats);
        var first = TableAnimationGameTests.discard(before, 0, 0, true);
        var second = TableAnimationGameTests.discard(first, 1, 20, true);
        var hands0 = List.of(range(0, 14), range(20, 33), range(40, 53), range(60, 73));
        var hands1 = List.of(range(1, 14), range(20, 34), range(40, 53), range(60, 73));
        var hands2 = List.of(range(1, 14), range(21, 34), range(40, 54), range(60, 73));
        var scene0 = scene(view, table, before, -1, hands0, List.of(13, -1, -1, -1), 0);
        var scene1 = scene(view, table, first, -1, hands1, List.of(-1, 33, -1, -1), 1);
        var scene2 = scene(view, table, second, -1, hands2, List.of(-1, -1, 53, -1), 2);
        var history = new TableHandAnimation(); history.accept(scene0); history.accept(scene1); history.accept(scene2);
        var player = new TableAnimationPlayer();
        player.accept(packet(table, epoch, 0, before, List.of()), 0);
        player.accept(packet(table, epoch, 1, first, List.of(new TableAnimationStep(1, before, first))), 0);
        var latest = packet(table, epoch, 2, second, List.of(new TableAnimationStep(2, first, second)));
        player.accept(latest, 100_000_000);
        var queued = history.frames(latest, player.frames(table, 150_000_000), scene2, true);
        require(queued.stream().noneMatch(f -> f.tile().face() == 53), "A queued older action cannot expose the later draw at destination");
        var old = history.frames(packet(table, epoch, 1, first, List.of()), TableAnimationPlayer.still(first), scene1, true);
        var next = history.frames(latest, player.frames(table, 500_000_000), scene2, true);
        var flying = next.stream().filter(f -> f.tile().key().equals("p:20")).findFirst().orElseThrow();
        samePosition(hand(old, 1, 20).pose(), flying.pose(), "Second queued discard uses its own preceding private hand");
    }

    private static TableScenePayload scene(UUID view, UUID table, TableDisplaySnapshot state, int own,
            List<List<Integer>> hands, List<Integer> draws, long revision) {
        var visible = new ArrayList<List<Integer>>(); var visibleDraws = new ArrayList<Integer>();
        for (int seat = 0; seat < hands.size(); seat++) {
            visible.add(own < 0 || seat == own ? hands.get(seat) : List.of());
            visibleDraws.add(own < 0 || seat == own ? draws.get(seat) : -1);
        }
        return new TableScenePayload(view, table, Level.OVERWORLD, BlockPos.ZERO, state.gameId(), revision, own,
                state.seats().stream().map(TableDisplaySnapshot.Seat::side).toList(), own >= 0, visible, visibleDraws, false, state.hashCode());
    }

    private static TableDisplayPayload packet(UUID id, UUID epoch, long serial, TableDisplaySnapshot state, List<TableAnimationStep> steps) {
        return new TableDisplayPayload(Level.OVERWORLD, id, BlockPos.ZERO, serial + 1, state, epoch, serial, steps);
    }
    private static TableAnimationGameTests.Scene rotate(TableAnimationGameTests.Scene scene, int rotation) {
        return new TableAnimationGameTests.Scene(scene.name(), rotate(scene.before(), rotation), rotate(scene.after(), rotation));
    }
    private static TableDisplaySnapshot rotate(TableDisplaySnapshot state, int rotation) {
        var seats = state.seats().stream().map(s -> new TableDisplaySnapshot.Seat((s.side() + rotation) % 4,
                s.concealedCount(), s.river(), s.melds(), s.norths())).toList();
        return new TableDisplaySnapshot(state.gameId(), state.paused(), state.finished(), (state.frontSide() + rotation) % 4,
                state.liveTiles(), state.indicators(), seats, state.uraIndicators());
    }
    private static Set<Integer> publicIds(TableDisplaySnapshot state) {
        var result = new HashSet<Integer>(state.indicators()); result.addAll(state.uraIndicators());
        for (var seat : state.seats()) {
            seat.river().forEach(d -> result.add(d.tileId())); seat.melds().forEach(m -> result.addAll(m.tiles())); result.addAll(seat.norths());
        }
        return result;
    }
    private static int nextFree(Set<Integer> used, int players) {
        for (int id = 0; id < 136; id++) if (!(players == 3 && id >= 4 && id < 32) && used.add(id)) return id;
        throw new AssertionError("Fixture ran out of physical identities");
    }
    private static List<Integer> range(int from, int to) { return IntStream.range(from, to).boxed().toList(); }
    private static Set<Integer> handIdentities(List<TableAnimationPlayer.Frame> frames) {
        var result = new HashSet<Integer>();
        frames.stream().filter(f -> f.tile().group() == TableTileLayout.Group.HAND && f.tile().face() >= 0).forEach(f -> result.add(f.tile().face()));
        return result;
    }
    private static TableAnimationPlayer.Frame hand(List<TableAnimationPlayer.Frame> frames, int seat, int id) {
        return frames.stream().filter(f -> f.tile().group() == TableTileLayout.Group.HAND && f.tile().seat() == seat && f.tile().face() == id)
                .findFirst().orElseThrow();
    }
    private static int physicalId(TableTileLayout.Tile tile) {
        if (tile.face() >= 0) return tile.face();
        return tile.key().startsWith("p:") ? Integer.parseInt(tile.key().substring(2)) : -1;
    }
    private static void samePosition(TileMotion.Pose a, TileMotion.Pose b, String message) {
        require(Math.abs(a.x() - b.x()) < .0001F && Math.abs(a.y() - b.y()) < .0001F && Math.abs(a.z() - b.z()) < .0001F,
                message + ": " + a + " / " + b);
    }
    private static void require(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
