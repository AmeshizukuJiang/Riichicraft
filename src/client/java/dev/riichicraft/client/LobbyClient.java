package dev.riichicraft.client;

import dev.riichicraft.network.LobbyActionPayload;
import dev.riichicraft.network.LobbyClosedPayload;
import dev.riichicraft.network.LobbySnapshotPayload;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Client view lifecycle; seats and ready state always come from the server. */
final class LobbyClient {
    private static final Set<UUID> DISMISSED_VIEWS = new HashSet<>();
    private static UUID activeViewId;
    private static boolean connected;

    private LobbyClient() {
    }

    static void initialize() {
        ClientPlayNetworking.registerGlobalReceiver(LobbySnapshotPayload.TYPE,
                (payload, context) -> acceptSnapshot(payload, context.client()));
        ClientPlayNetworking.registerGlobalReceiver(LobbyClosedPayload.TYPE,
                (payload, context) -> acceptClosed(payload, context.client()));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset(true));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset(false));
    }

    private static void reset(boolean isConnected) {
        connected = isConnected;
        activeViewId = null;
        DISMISSED_VIEWS.clear();
    }

    private static void acceptSnapshot(LobbySnapshotPayload payload, Minecraft client) {
        if (!connected || client.level == null || client.player == null
                || DISMISSED_VIEWS.contains(payload.viewId())) {
            return;
        }
        if (client.gui.screen() instanceof LobbyScreen screen
                && screen.snapshot().viewId().equals(payload.viewId())) {
            screen.acceptSnapshot(payload);
            return;
        }
        if (payload.viewId().equals(activeViewId)) {
            // This view was replaced locally. An update must not reopen it.
            closeView(payload.viewId(), payload.revision());
            return;
        }
        if (activeViewId != null) {
            DISMISSED_VIEWS.add(activeViewId);
        }
        activeViewId = payload.viewId();
        client.gui.setScreen(new LobbyScreen(payload));
    }

    private static void acceptClosed(LobbyClosedPayload payload, Minecraft client) {
        boolean active = payload.viewId().equals(activeViewId);
        boolean visible = client.gui.screen() instanceof LobbyScreen screen
                && screen.snapshot().viewId().equals(payload.viewId());
        DISMISSED_VIEWS.add(payload.viewId());
        if (active) {
            activeViewId = null;
        }
        if (visible) {
            client.gui.setScreen(null);
        }
        if ((active || visible) && client.player != null && !payload.reasonKey().isEmpty()) {
            client.player.sendOverlayMessage(Component.translatable(payload.reasonKey()));
        }
    }

    static boolean request(LobbySnapshotPayload snapshot, int action, boolean ready) {
        if (!snapshot.viewId().equals(activeViewId) || DISMISSED_VIEWS.contains(snapshot.viewId())
                || !canSend()) {
            return false;
        }
        ClientPlayNetworking.send(new LobbyActionPayload(snapshot.viewId(), snapshot.revision(), action, ready));
        return true;
    }

    static void closeView(UUID viewId, long revision) {
        boolean firstClose = DISMISSED_VIEWS.add(viewId);
        if (viewId.equals(activeViewId)) {
            activeViewId = null;
        }
        if (firstClose && canSend()) {
            // Closing a view intentionally keeps the server-side seat.
            ClientPlayNetworking.send(new LobbyActionPayload(viewId, revision, LobbyActionPayload.CLOSE, false));
        }
    }

    static boolean canSend() {
        return connected && Minecraft.getInstance().getConnection() != null
                && ClientPlayNetworking.canSend(LobbyActionPayload.TYPE);
    }
}
