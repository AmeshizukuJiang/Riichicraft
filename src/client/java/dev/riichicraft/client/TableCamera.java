package dev.riichicraft.client;

import dev.riichicraft.network.TableScenePayload;
import dev.riichicraft.table.TableAnimationPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Local render camera and hit tests. Never moves the player or changes server game mode. */
public final class TableCamera {
    public record View(double x, double y, double z, float yaw, float pitch) { }
    public record Bounds(double left, double top, double right, double bottom) { }
    private record Hand(int tileId, Vec3 center, List<Vec3> corners) { }
    private record Projected(int tileId, List<double[]> corners, double depth, Bounds bounds) { }
    private static TableScenePayload captured;
    private static CameraType previousType;
    private static boolean overhead;
    private static boolean observedMount;
    private static List<Hand> hands = List.of();
    private static UUID handTable, handGame;

    private TableCamera() { }

    public static void initialize() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> release());
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (captured == null) return;
            if (!valid(captured, client)) { release(); return; }
            if (captured.mounted()) {
                boolean riding = client.player.getVehicle() instanceof dev.riichicraft.entity.MahjongSeatEntity;
                // The first scene can precede vanilla entity tracking; do not reject that initial packet race.
                if (observedMount && !riding) { release(); return; }
                observedMount |= riding;
            }
            CameraType expected = overhead || captured.mounted() ? CameraType.FIRST_PERSON : previousType;
            if (client.options.getCameraType() != expected) client.options.setCameraType(expected);
        });
    }

    public static void activate(TableScenePayload scene, boolean top) {
        var client = Minecraft.getInstance();
        if (!valid(scene, client)) { release(); return; }
        if (captured == null) previousType = client.options.getCameraType();
        boolean newlyMounted = scene.mounted() && (captured == null || !captured.mounted()
                || !captured.tableId().equals(scene.tableId()) || captured.ownSeat() != scene.ownSeat());
        if (newlyMounted && scene.ownSeat() >= 0 && scene.ownSeat() < scene.sides().size()) {
            // Face the angular midpoint of the hand, which occupies the left part of this edge.
            float yaw = 166 - scene.sides().get(scene.ownSeat()) * 90;
            client.player.setYRot(yaw);
            client.player.setXRot(35);
            client.player.yRotO = yaw;
            client.player.xRotO = 35;
        }
        if (captured == null || !captured.tableId().equals(scene.tableId())) observedMount = false;
        if (captured == null || !captured.tableId().equals(scene.tableId())
                || !java.util.Objects.equals(captured.gameId(), scene.gameId())) clearHands();
        captured = scene;
        setOverhead(top);
    }

    public static void release() {
        if (previousType != null) Minecraft.getInstance().options.setCameraType(previousType);
        previousType = null;
        captured = null;
        overhead = false;
        observedMount = false;
        clearHands();
    }

    public static boolean overhead() { return captured != null && overhead; }
    public static boolean active() { return captured != null; }
    public static float fov(float vanilla) {
        if (overhead()) return 60F;
        return captured != null && captured.mounted() ? Math.max(85F, vanilla) : vanilla;
    }
    public static void toggle() { setOverhead(!overhead); }
    public static void setOverhead(boolean top) {
        if (captured == null) return;
        overhead = top;
        Minecraft.getInstance().options.setCameraType(top || captured.mounted()
                ? CameraType.FIRST_PERSON : previousType);
    }

    /** Used inside Camera's entity alignment, before it computes projection and culling. */
    public static View view() {
        var client = Minecraft.getInstance();
        if (!overhead() || !valid(captured, client)) return null;
        int seat = captured.ownSeat();
        int side = seat >= 0 && seat < captured.sides().size() ? captured.sides().get(seat) : captured.sides().getFirst();
        double aspect = (double)Math.max(1, client.getWindow().getWidth()) / Math.max(1, client.getWindow().getHeight());
        // Keep the complete three-block tabletop inside the narrower viewport dimension.
        double height = 3.35 / (2 * Math.tan(Math.toRadians(30)) * Math.min(1, aspect));
        return new View(captured.pos().getX() + .5, captured.pos().getY() + 1 + height,
                captured.pos().getZ() + .5, 180 - side * 90, 90);
    }

    private static boolean valid(TableScenePayload scene, Minecraft client) {
        return scene != null && client.player != null && client.player.isAlive() && client.level != null
                && scene.dimension().equals(client.level.dimension())
                && (scene.mounted() || scene.ownSeat() == -1)
                && client.player.distanceToSqr(Vec3.atCenterOf(scene.pos())) <= 48 * 48;
    }

    static void beginHandFrame() { hands = List.of(); handTable = null; handGame = null; }
    static void recordHandFrame(TableScenePayload scene, List<TableAnimationPlayer.Frame> frames) {
        if (scene.ownSeat() < 0) return;
        List<Hand> next = new ArrayList<>();
        for (var frame : frames) {
            var tile = frame.tile();
            if (tile.group() != dev.riichicraft.table.TableTileLayout.Group.HAND
                    || tile.seat() != scene.ownSeat() || tile.face() < 0) continue;
            var pose = frame.pose();
            var origin = new Vec3(scene.pos().getX() + .5, scene.pos().getY() + 1, scene.pos().getZ() + .5);
            var matrix = new Matrix4f().translation(pose.x() / 16, pose.y() / 16, pose.z() / 16)
                    .rotateY((float)Math.toRadians(pose.yaw())).rotateX((float)Math.toRadians(pose.pitch()))
                    .scale(pose.scale() / 16);
            List<Vec3> corners = List.of(point(matrix, origin, -.96F, -1.32F, .625F), point(matrix, origin, .96F, -1.32F, .625F),
                    point(matrix, origin, .96F, 1.32F, .625F), point(matrix, origin, -.96F, 1.32F, .625F));
            next.add(new Hand(tile.face(), point(matrix, origin, 0, 0, .625F), corners));
        }
        hands = List.copyOf(next); handTable = scene.tableId(); handGame = scene.gameId();
    }

    private static Vec3 point(Matrix4f transform, Vec3 origin, float x, float y, float z) {
        return origin.add(new Vec3(transform.transformPosition(new Vector3f(x, y, z))));
    }

    public static int pickTile(double mouseX, double mouseY) {
        int best = -1; double nearest = Double.POSITIVE_INFINITY;
        for (var projected : projections()) if (projected.depth() < nearest && inside(projected.corners(), mouseX, mouseY)) {
            best = projected.tileId(); nearest = projected.depth();
        }
        return best;
    }

    public static Bounds handBounds() {
        var projected = projections();
        if (projected.isEmpty()) return null;
        double left = Double.POSITIVE_INFINITY, top = left, right = Double.NEGATIVE_INFINITY, bottom = right;
        for (var tile : projected) {
            left = Math.min(left, tile.bounds().left()); top = Math.min(top, tile.bounds().top());
            right = Math.max(right, tile.bounds().right()); bottom = Math.max(bottom, tile.bounds().bottom());
        }
        return new Bounds(left, top, right, bottom);
    }

    public static Bounds tileBounds(int physicalTileId) {
        return projections().stream().filter(tile -> tile.tileId() == physicalTileId)
                .map(Projected::bounds).findFirst().orElse(null);
    }

    private static List<Projected> projections() {
        var client = Minecraft.getInstance(); var scene = TableInteractionClient.scene();
        if (scene == null || client.level == null || !scene.tableId().equals(handTable)
                || !java.util.Objects.equals(scene.gameId(), handGame)
                || !scene.dimension().equals(client.level.dimension())) return List.of();
        var camera = client.gameRenderer.mainCamera();
        if (!camera.isInitialized()) return List.of();
        var projection = camera.getViewRotationProjectionMatrix(new Matrix4f());
        int width = client.getWindow().getGuiScaledWidth(), height = client.getWindow().getGuiScaledHeight();
        List<Projected> result = new ArrayList<>();
        for (var hand : hands) {
            Vec3 normal = hand.corners().get(1).subtract(hand.corners().get(0))
                    .cross(hand.corners().get(2).subtract(hand.corners().get(1)));
            if (normal.dot(camera.position().subtract(hand.center())) <= 0) continue;
            List<double[]> points = new ArrayList<>(); boolean visible = true;
            double left = Double.POSITIVE_INFINITY, top = left, right = Double.NEGATIVE_INFINITY, bottom = right;
            for (var corner : hand.corners()) {
                var rel = corner.subtract(camera.position());
                var clip = projection.transform(new Vector4f((float)rel.x, (float)rel.y, (float)rel.z, 1));
                if (clip.w <= .001 || !Float.isFinite(clip.w)) { visible = false; break; }
                double x = (clip.x / clip.w + 1) * width / 2D, y = (1 - clip.y / clip.w) * height / 2D;
                if (!Double.isFinite(x) || !Double.isFinite(y)) { visible = false; break; }
                points.add(new double[]{x, y});
                left = Math.min(left, x); top = Math.min(top, y); right = Math.max(right, x); bottom = Math.max(bottom, y);
            }
            if (visible && right >= 0 && left < width && bottom >= 0 && top < height)
                result.add(new Projected(hand.tileId(), points, camera.position().distanceToSqr(hand.center()), new Bounds(left, top, right, bottom)));
        }
        return result;
    }

    private static boolean inside(List<double[]> polygon, double x, double y) {
        double sign = 0;
        for (int i = 0; i < polygon.size(); i++) {
            var a = polygon.get(i); var b = polygon.get((i + 1) % polygon.size());
            double cross = (b[0] - a[0]) * (y - a[1]) - (b[1] - a[1]) * (x - a[0]);
            if (Math.abs(cross) < 1e-7) continue;
            if (sign != 0 && Math.signum(cross) != sign) return false;
            sign = Math.signum(cross);
        }
        return sign != 0;
    }

    private static void clearHands() { hands = List.of(); handTable = null; handGame = null; }
}
