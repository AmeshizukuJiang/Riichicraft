package dev.riichicraft.test;

import dev.riichicraft.client.LobbyScreen;
import dev.riichicraft.client.GameTileButton;
import dev.riichicraft.network.GameActionPayload;
import dev.riichicraft.network.GameSnapshot;
import dev.riichicraft.network.LobbyActionPayload;
import dev.riichicraft.network.LobbySnapshotPayload;
import dev.riichicraft.server.LobbyService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import org.lwjgl.glfw.GLFW;

/** One real client plus three native in-memory server fixtures, never four real clients. */
final class DrawDiscardClientScenario {
    private record TableFixtures(List<ConnectedTestPlayer> fixtures, List<UUID> viewIds, UUID tableId) { }

    static void run(ClientGameTestContext context, TestServerContext server, TestServerConnection connection,
            BlockPos target, boolean dedicated) {
        UUID playerView = snapshot(context).viewId();
        TableFixtures table = server.computeOnServer(s -> {
            LobbyService service = LobbyService.get(s);
            UUID id = service.registry().tableForPlayer(connection.getServerPlayer().getUUID()).orElseThrow();
            List<ConnectedTestPlayer> fixtures = new ArrayList<>();
            List<UUID> views = new ArrayList<>(List.of(playerView));
            try {
                for (String name : List.of("DrawSouth", "DrawWest", "DrawNorth")) {
                    ConnectedTestPlayer fixture = new ConnectedTestPlayer(s, connection.getServerLevel(), target, name);
                    fixtures.add(fixture);
                    ServerPlayer player = fixture.player();
                    UUID view = service.open(player, target);
                    views.add(view);
                    service.handleAction(player, new LobbyActionPayload(view,
                            service.registry().snapshot(id).orElseThrow().revision(), LobbyActionPayload.JOIN, false));
                    service.handleAction(player, new LobbyActionPayload(view,
                            service.registry().snapshot(id).orElseThrow().revision(), LobbyActionPayload.SET_READY, true));
                }
                return new TableFixtures(List.copyOf(fixtures), List.copyOf(views), id);
            } catch (RuntimeException | Error failure) {
                for (ConnectedTestPlayer fixture : fixtures) {
                    service.disconnect(fixture.player());
                    fixture.close();
                }
                throw failure;
            }
        });
        try {
            sync(connection);
            context.clickScreenButton("screen.riichicraft.lobby.prepare");
            sync(connection);
            context.clickScreenButton("screen.riichicraft.lobby.start");
            sync(connection);
            GameSnapshot initial = snapshot(context).game();
            require(initial != null && initial.ownHand().size() == 13 && initial.drawnTile() >= 0
                    && initial.turnSeat() == 0 && initial.remainingTiles() == 69,
                    "The real client must receive its 13 closed tiles and the separate first draw");
            List<Integer> opponents = server.computeOnServer(s -> {
                List<Integer> tiles = new ArrayList<>();
                for (ConnectedTestPlayer fixture : table.fixtures()) {
                    GameSnapshot other = LobbyService.get(s).gameView(table.tableId(), fixture.player().getUUID());
                    tiles.addAll(other.ownHand());
                    if (other.drawnTile() >= 0) tiles.add(other.drawnTile());
                }
                return tiles;
            });
            HashSet<Integer> visible = new HashSet<>(initial.ownHand());
            visible.add(initial.drawnTile());
            require(opponents.stream().noneMatch(visible::contains),
                    "The actual decoded payload must not contain an opponent's concealed physical tiles");

            clickHandTile(context);
            int selected = context.computeOnClient(c -> ((LobbyScreen) c.gui.screen()).selectedTileId());
            require(initial.ownHand().contains(selected), "A hand click must select a tile the client owns");
            sync(connection);
            require(snapshot(context).game().revision() == initial.revision()
                            && snapshot(context).game().seats().getFirst().discards().isEmpty(),
                    "Selecting a tile must not send a discard before confirmation");
            context.clickScreenButton("screen.riichicraft.game.discard");
            sync(connection);
            GameSnapshot after = snapshot(context).game();
            require(after.revision() == 1 && after.turnSeat() == 1 && after.remainingTiles() == 68
                            && after.seats().getFirst().discards().equals(List.of(selected)),
                    "The confirmed hand discard must appear once in the public river and advance the turn");
            context.runOnClient(c -> {
                ClientPlayNetworking.send(new GameActionPayload(playerView, initial.gameId(), initial.revision(),
                        GameActionPayload.DISCARD, selected));
                ClientPlayNetworking.send(new GameActionPayload(playerView, UUID.randomUUID(), after.revision(),
                        GameActionPayload.DISCARD, after.ownHand().getFirst()));
                ClientPlayNetworking.send(new GameActionPayload(playerView, after.gameId(), after.revision(),
                        GameActionPayload.DISCARD, after.ownHand().getFirst()));
            });
            sync(connection);
            require(snapshot(context).game().revision() == 1,
                    "Replayed, wrong-game and wrong-turn packets cannot advance a real server");

            server.runOnServer(s -> {
                LobbyService service = LobbyService.get(s);
                while (service.gameView(table.tableId(), connection.getServerPlayer().getUUID()).revision() < 68) {
                    advance(service, table, connection.getServerPlayer());
                }
            });
            sync(connection);
            GameSnapshot late = snapshot(context).game();
            require(late.turnSeat() == 0 && late.remainingTiles() == 1
                            && late.seats().stream().allMatch(seat -> seat.discards().size() == 17),
                    "The late-round client view must show all four three-row rivers");
            context.runOnClient(c -> c.gui.toastManager().clear());
            context.waitTick();
            context.takeScreenshot(dedicated ? "riichicraft-dedicated-draw-discard" : "riichicraft-draw-discard");
            if (dedicated) {
                context.getInput().resizeWindow(640, 480);
                context.waitTick();
                require(context.computeOnClient(c -> c.getWindow().getGuiScaledWidth()) == 320,
                        "The compact client scenario must actually use a 320-pixel GUI");
                context.takeScreenshot("riichicraft-draw-discard-compact");
            }
            clickHandTile(context);
            context.clickScreenButton("screen.riichicraft.game.discard");
            sync(connection);
            require(snapshot(context).game().remainingTiles() == 0 && snapshot(context).game().turnSeat() == 1,
                    "The final drawn tile must still be discardable after the live wall reaches zero");
            server.runOnServer(s -> advance(LobbyService.get(s), table, connection.getServerPlayer()));
            sync(connection);
            require(snapshot(context).game() == null && snapshot(context).ownSeat() == 0
                            && snapshot(context).seats().stream().noneMatch(LobbySnapshotPayload.Seat::ready),
                    "Wall exhaustion must return to the unprepared lobby while retaining seats");
        } finally {
            server.runOnServer(s -> {
                LobbyService service = LobbyService.get(s);
                for (ConnectedTestPlayer fixture : table.fixtures()) {
                    service.disconnect(fixture.player());
                    fixture.close();
                }
            });
        }
    }

    private static void advance(LobbyService service, TableFixtures table, ServerPlayer east) {
        GameSnapshot current = service.gameView(table.tableId(), east.getUUID());
        int seat = current.turnSeat();
        ServerPlayer player = seat == 0 ? east : table.fixtures().get(seat - 1).player();
        GameSnapshot own = service.gameView(table.tableId(), player.getUUID());
        service.handleGameAction(player, new GameActionPayload(table.viewIds().get(seat), own.gameId(),
                own.revision(), GameActionPayload.DISCARD, own.drawnTile()));
        GameSnapshot next = service.gameView(table.tableId(), east.getUUID());
        require(next == null || next.revision() > current.revision(), "A valid server fixture discard must advance");
    }

    private static LobbySnapshotPayload snapshot(ClientGameTestContext context) {
        return context.computeOnClient(c -> ((LobbyScreen) c.gui.screen()).snapshot());
    }

    private static void clickHandTile(ClientGameTestContext context) {
        double[] position = context.computeOnClient(c -> {
            GameTileButton button = c.gui.screen().children().stream()
                    .filter(child -> child instanceof GameTileButton tile && tile.active && tile.visible)
                    .map(child -> (GameTileButton) child).findFirst().orElseThrow();
            var window = c.getWindow();
            return new double[] {
                    (button.getX() + button.getWidth() / 2.0) * window.getScreenWidth() / window.getGuiScaledWidth(),
                    (button.getY() + button.getHeight() / 2.0) * window.getScreenHeight() / window.getGuiScaledHeight()
            };
        });
        context.getInput().setCursorPos(position[0], position[1]);
        context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }

    private static void sync(TestServerConnection connection) {
        connection.waitForServerboundPackets();
        connection.waitForClientboundPackets();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
