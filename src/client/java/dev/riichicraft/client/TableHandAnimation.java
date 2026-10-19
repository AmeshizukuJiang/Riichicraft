package dev.riichicraft.client;

import dev.riichicraft.network.TableDisplayPayload;
import dev.riichicraft.network.TableScenePayload;
import dev.riichicraft.table.TableAnimationPlayer;
import dev.riichicraft.table.TableTileLayout;
import dev.riichicraft.table.TileMotion;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bounded, recipient-authorized history for correcting anonymous animation origins locally. */
final class TableHandAnimation {
    private static final int HISTORY_LIMIT = 32;
    private final LinkedHashMap<Integer, TableScenePayload> history = new LinkedHashMap<>();
    private final LinkedHashMap<Integer, Boolean> observedPublicStates = new LinkedHashMap<>();
    private TableScenePayload latest;

    void clear() {
        history.clear();
        observedPublicStates.clear();
        latest = null;
    }

    void accept(TableScenePayload scene) {
        if (scene == null || !sameSession(latest, scene)) clear();
        latest = scene;
        if (scene == null || scene.gameId() == null || scene.publicStateHash() == null) return;
        history.put(scene.publicStateHash(), scene);
        while (history.size() > HISTORY_LIMIT) history.remove(history.keySet().iterator().next());
    }

    List<TableAnimationPlayer.Frame> frames(TableDisplayPayload table, List<TableAnimationPlayer.Frame> publicFrames,
            TableScenePayload scene, boolean overhead) {
        if (!TableDisplayRenderer.matches(table, scene) || publicFrames.isEmpty()) return publicFrames;
        accept(scene);
        int publicHash = publicFrames.getFirst().stateHash();
        rememberPublicState(publicHash);
        var publicTransition = publicFrames.getFirst().transition();
        if (publicTransition != null) rememberPublicState(publicTransition.beforeStateHash());
        // A queued public action uses its own private endpoint, rather than a newer turn's hand.
        var endpoint = scene.finished() ? scene : history.getOrDefault(publicHash, scene);
        var visible = TableDisplayRenderer.visibleFrames(table, publicFrames, endpoint, overhead);
        var result = new ArrayList<TableAnimationPlayer.Frame>(visible.size());
        var publicTiles = new HashSet<Integer>();
        for (var frame : visible) if (frame.tile().group() != TableTileLayout.Group.HAND) {
            int id = physicalId(frame.tile());
            if (id >= 0) publicTiles.add(id);
        }
        Map<Integer, Map<Integer, TileMotion.Pose>> previousPoses = new HashMap<>();
        for (var frame : visible) {
            var tile = frame.tile();
            // A public packet can precede its private endpoint: never show a departed copy twice.
            if (tile.group() == TableTileLayout.Group.HAND && tile.face() >= 0 && publicTiles.contains(tile.face())) continue;
            var transition = frame.transition();
            var before = transition == null ? null : history.get(transition.beforeStateHash());
            int physical = physicalId(tile), owner = tile.seat();
            if (before == null || owner < 0 || physical < 0 || !before.hands().get(owner).contains(physical)
                    || !authorized(scene, owner)) {
                result.add(frame);
                continue;
            }
            var poses = previousPoses.computeIfAbsent(transition.beforeStateHash(), ignored -> handPoses(before, overhead));
            var from = poses.get(physical);
            TileMotion.Pose corrected;
            if (tile.group() == TableTileLayout.Group.HAND) {
                var to = new TileMotion.Pose(tile.x(), tile.y(), tile.z(), tile.yaw(), tile.flat() ? -90 : 0, tile.scale());
                corrected = TileMotion.sample(from, to, transition.handProgress(), 0);
            } else {
                corrected = correctSource(frame.pose(), transition, from);
            }
            result.add(new TableAnimationPlayer.Frame(tile, corrected, frame.stateHash(), transition));
        }
        // Public-first updates can remove the old draw's anonymous slot, or reuse it for a
        // replacement whose identity has not arrived. Keep the already-known retained copy at
        // its old position instead of treating it as an unseen new draw. A private-first new
        // draw has no observed public state yet, so the renderer's no-flash guard still applies.
        if (!endpoint.finished() && endpoint.publicStateHash() != null
                && endpoint.publicStateHash() != publicHash && observedPublicStates.containsKey(endpoint.publicStateHash())) {
            var displayed = new HashSet<Integer>(publicTiles);
            for (var frame : result) if (frame.tile().group() == TableTileLayout.Group.HAND && frame.tile().face() >= 0)
                displayed.add(frame.tile().face());
            for (int seat = 0; seat < endpoint.hands().size(); seat++) {
                if (!authorized(scene, seat)) continue;
                var hand = new ArrayList<>(endpoint.hands().get(seat));
                hand.sort(Integer::compareTo);
                int drawn = endpoint.drawnTiles().get(seat);
                if (drawn >= 0 && hand.remove(Integer.valueOf(drawn))) hand.add(drawn);
                for (int index = 0; index < hand.size(); index++) if (displayed.add(hand.get(index))) {
                    var tile = TableDisplayRenderer.handTile(endpoint, seat, index, hand.get(index), overhead);
                    var pose = new TileMotion.Pose(tile.x(), tile.y(), tile.z(), tile.yaw(), tile.flat() ? -90 : 0, tile.scale());
                    result.add(new TableAnimationPlayer.Frame(tile, pose, publicHash));
                }
            }
        }
        return List.copyOf(result);
    }

    private void rememberPublicState(int hash) {
        observedPublicStates.put(hash, Boolean.TRUE);
        while (observedPublicStates.size() > HISTORY_LIMIT)
            observedPublicStates.remove(observedPublicStates.keySet().iterator().next());
    }

    private static TileMotion.Pose correctSource(TileMotion.Pose pose, TableAnimationPlayer.Transition motion,
            TileMotion.Pose from) {
        float p = motion.progress(), remaining = 1 - p * p * (3 - 2 * p);
        var source = motion.source();
        return new TileMotion.Pose(pose.x() + (from.x() - source.x()) * remaining,
                pose.y() + (from.y() - source.y()) * remaining,
                pose.z() + (from.z() - source.z()) * remaining,
                pose.yaw() + angle(from.yaw() - source.yaw()) * remaining,
                pose.pitch() + (from.pitch() - source.pitch()) * remaining,
                pose.scale() + (from.scale() - source.scale()) * remaining);
    }

    private static Map<Integer, TileMotion.Pose> handPoses(TableScenePayload scene, boolean overhead) {
        var result = new HashMap<Integer, TileMotion.Pose>();
        for (int seat = 0; seat < scene.hands().size(); seat++) {
            var hand = new ArrayList<>(scene.hands().get(seat));
            hand.sort(Integer::compareTo);
            int drawn = scene.drawnTiles().get(seat);
            if (drawn >= 0 && hand.remove(Integer.valueOf(drawn))) hand.add(drawn);
            for (int index = 0; index < hand.size(); index++) {
                var tile = TableDisplayRenderer.handTile(scene, seat, index, hand.get(index), overhead);
                result.put(hand.get(index), new TileMotion.Pose(tile.x(), tile.y(), tile.z(), tile.yaw(),
                        tile.flat() ? -90 : 0, tile.scale()));
            }
        }
        return result;
    }

    private static boolean authorized(TableScenePayload current, int seat) {
        return current.finished() || current.ownSeat() < 0 || current.ownSeat() == seat;
    }

    private static boolean sameSession(TableScenePayload a, TableScenePayload b) {
        return a != null && b != null && a.viewId().equals(b.viewId()) && a.tableId().equals(b.tableId())
                && Objects.equals(a.gameId(), b.gameId()) && a.ownSeat() == b.ownSeat()
                && a.dimension().equals(b.dimension()) && a.pos().equals(b.pos()) && a.sides().equals(b.sides());
    }

    private static int physicalId(TableTileLayout.Tile tile) {
        if (tile.face() >= 0) return tile.face();
        if (tile.group() == TableTileLayout.Group.MELD && tile.key().startsWith("p:")) {
            try { return Integer.parseInt(tile.key().substring(2)); }
            catch (NumberFormatException ignored) { return -1; }
        }
        return -1;
    }

    private static float angle(float value) { return (value % 360 + 540) % 360 - 180; }
}
