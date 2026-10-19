package dev.riichicraft.client;

import dev.riichicraft.Riichicraft;
import dev.riichicraft.network.LobbySnapshotPayload;
import dev.riichicraft.network.TableScenePayload;
import java.util.Objects;
import java.util.UUID;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/** A chair/table session survives hiding controls; only server closure revokes the view. */
public final class TableInteractionClient {
    private static TableScenePayload pending, scene;
    private static LobbySnapshotPayload snapshot;
    private static KeyMapping reopenKey, perspectiveKey;
    private static boolean suppressReopenUntilRelease;

    private TableInteractionClient() { }

    static void initialize() {
        var category = KeyMapping.Category.register(Riichicraft.id("table"));
        reopenKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.riichicraft.table_controls", GLFW.GLFW_KEY_R, category));
        perspectiveKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.riichicraft.table_perspective", GLFW.GLFW_KEY_V, category));
        ClientPlayNetworking.registerGlobalReceiver(TableScenePayload.TYPE, (payload, context) -> {
            Minecraft client = context.client();
            if (client.level == null || client.player == null || !client.level.dimension().equals(payload.dimension())) return;
            if (scene != null && scene.viewId().equals(payload.viewId()) && Objects.equals(scene.gameId(), payload.gameId())
                    && payload.revision() < scene.revision()) return;
            pending = payload;
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (scene != null && (client.level == null || client.player == null
                    || !client.player.isAlive() || !client.level.dimension().equals(scene.dimension()))) {
                UUID old = scene.viewId();
                long revision = snapshot == null ? 0 : snapshot.revision();
                LobbyClient.closeView(old, revision);
                if (client.gui.screen() instanceof TableScreen) client.gui.setScreen(null);
            }
            while (reopenKey.consumeClick()) if (!suppressReopenUntilRelease && active() && client.gui.screen() == null) open(client);
            if (!reopenKey.isDown()) suppressReopenUntilRelease = false;
            while (perspectiveKey.consumeClick()) if (active() && client.gui.screen() == null
                    && (scene.mounted() || scene.ownSeat() < 0)) TableCamera.toggle();
        });
    }

    static boolean acceptSnapshot(LobbySnapshotPayload updated, Minecraft client) {
        TableScenePayload candidate = pending != null && pending.viewId().equals(updated.viewId()) ? pending : scene;
        if (candidate == null || !candidate.viewId().equals(updated.viewId()) || !candidate.tableId().equals(updated.tableId())) return false;
        // Scene precedes the matching game revision; never combine a hand with another turn.
        long expectedRevision = updated.game() == null ? updated.revision() : updated.game().revision();
        if (candidate.revision() != expectedRevision
                || !Objects.equals(candidate.gameId(), updated.game() == null ? null : updated.game().gameId())) return true;
        if (snapshot != null && snapshot.viewId().equals(updated.viewId()) && updated.revision() < snapshot.revision()) return true;
        if (snapshot != null && snapshot.game() != null && updated.game() != null
                && snapshot.game().gameId().equals(updated.game().gameId())
                && updated.game().revision() < snapshot.game().revision()) return true;
        boolean newView = scene == null || !scene.viewId().equals(candidate.viewId());
        boolean mountedNow = candidate.mounted() && (newView || !scene.mounted());
        boolean newRound = candidate.gameId() != null && (newView || !Objects.equals(candidate.gameId(), scene.gameId()));
        boolean newlyFinished = candidate.finished() && (newView || !scene.finished());
        scene = candidate;
        pending = null;
        snapshot = updated;
        TableDisplayClient.acceptScene(scene);
        if (!scene.mounted() && scene.ownSeat() >= 0) TableCamera.release();
        else TableCamera.activate(scene, newRound || newlyFinished || TableCamera.overhead());
        if (client.gui.screen() instanceof TableScreen screen && screen.viewId().equals(updated.viewId())) screen.acceptSnapshot(updated);
        else if ((newView || mountedNow || newRound || newlyFinished) && (client.gui.screen() == null
                || client.gui.screen() instanceof TableScreen || client.gui.screen() instanceof LobbyScreen)) open(client);
        return true;
    }

    private static void open(Minecraft client) {
        if (snapshot != null) client.gui.setScreen(new TableScreen(snapshot));
    }

    static boolean acceptClosed(UUID id, Minecraft client) {
        if (scene == null || !scene.viewId().equals(id)) return false;
        reset();
        if (client.gui.screen() instanceof TableScreen screen && screen.viewId().equals(id)) client.gui.setScreen(null);
        return true;
    }

    static void forget(UUID id) {
        if (scene != null && scene.viewId().equals(id) || pending != null && pending.viewId().equals(id)) reset();
    }

    static void reset() {
        suppressReopenUntilRelease = false;
        scene = null;
        pending = null;
        snapshot = null;
        TableDisplayClient.clearScene();
        TableCamera.release();
    }

    static boolean perspectiveKey(net.minecraft.client.input.KeyEvent event) {
        return perspectiveKey != null && perspectiveKey.matches(event);
    }

    /** Esc first leaves a hidden board camera; existing screens keep their own key handling. */
    public static boolean exitHiddenOverhead(Minecraft client, net.minecraft.client.input.KeyEvent event) {
        if (!event.isEscape() || !active() || !TableCamera.overhead()
                || client.gui.screen() != null || client.gui.overlay() != null) return false;
        TableCamera.setOverhead(false);
        return true;
    }

    static void hideWithControlsKey() {
        // KeyboardHandler may queue this same R after the Screen closes; don't immediately reopen it.
        suppressReopenUntilRelease = true;
    }

    static boolean controlsKey(net.minecraft.client.input.KeyEvent event) {
        return reopenKey != null && reopenKey.matches(event);
    }

    static net.minecraft.network.chat.Component controlsKeyName() { return reopenKey.getTranslatedKeyMessage(); }
    static net.minecraft.network.chat.Component perspectiveKeyName() { return perspectiveKey.getTranslatedKeyMessage(); }

    public static TableScenePayload scene() { return scene; }
    public static LobbySnapshotPayload snapshot() { return snapshot; }
    public static boolean active() { return scene != null && snapshot != null; }
    public static boolean overhead() { return TableCamera.overhead(); }
}
