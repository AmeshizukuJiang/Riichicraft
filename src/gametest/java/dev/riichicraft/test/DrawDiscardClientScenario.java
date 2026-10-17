package dev.riichicraft.test;

import dev.riichicraft.client.LobbyScreen;
import dev.riichicraft.client.GameTileButton;
import dev.riichicraft.network.GameActionPayload;
import dev.riichicraft.network.GameSnapshot;
import dev.riichicraft.network.LobbyActionPayload;
import dev.riichicraft.network.LobbySnapshotPayload;
import dev.riichicraft.rules.CallActions;
import dev.riichicraft.rules.HandInput;
import dev.riichicraft.rules.Meld;
import dev.riichicraft.rules.WinContext;
import dev.riichicraft.server.LobbyService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import org.lwjgl.glfw.GLFW;

/** One real client plus three native in-memory server fixtures, never four real clients. */
final class DrawDiscardClientScenario {
    record TableFixtures(List<ConnectedTestPlayer> fixtures, List<UUID> viewIds, UUID tableId) { }

    static void run(ClientGameTestContext context, TestServerContext server, TestServerConnection connection,
            BlockPos target, boolean dedicated) {
        UUID playerView = snapshot(context).viewId();
        TableFixtures table = createFixtures(server, connection, target, playerView);
        try {
            runNormalGame(context, server, connection, target, table, playerView, dedicated);
            runMouseCall(context, server, connection, table, dedicated);
        } finally {
            closeFixtures(server, table);
        }
    }

    static TableFixtures createFixtures(TestServerContext server, TestServerConnection connection,
            BlockPos target, UUID playerView) {
        return server.computeOnServer(s -> {
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
    }

    private static void runNormalGame(ClientGameTestContext context, TestServerContext server,
            TestServerConnection connection, BlockPos target, TableFixtures table, UUID playerView, boolean dedicated) {
        sync(connection);
        context.clickScreenButton("screen.riichicraft.lobby.prepare");
        sync(connection);
        context.clickScreenButton("screen.riichicraft.lobby.start");
        sync(connection);
        GameSnapshot initial = snapshot(context).game();
        require(initial != null && initial.ownHand().size() == 14 && initial.ownHand().contains(initial.drawnTile())
                && initial.turnSeat() == 0 && initial.remainingTiles() == 69,
                "The real client must receive its complete fourteen-tile concealed hand and a draw reference");
        List<Integer> opponents = server.computeOnServer(s -> {
            List<Integer> tiles = new ArrayList<>();
            for (ConnectedTestPlayer fixture : table.fixtures()) {
                GameSnapshot other = LobbyService.get(s).gameView(table.tableId(), fixture.player().getUUID());
                tiles.addAll(other.ownHand());
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
        require(after.revision() == 1 && after.ownHand().size() == 13
                        && after.seats().getFirst().discards().equals(List.of(selected)),
                "The confirmed discard must appear exactly once before any pending response window resolves");
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
            int actions = 0;
            while (!isLateDraw(service.gameView(table.tableId(), connection.getServerPlayer().getUUID()))) {
                require(++actions <= 400, "The all-pass round must reach its late drawn turn within its tile/response budget");
                advance(service, table, connection.getServerPlayer());
            }
        });
        sync(connection);
        GameSnapshot late = snapshot(context).game();
        require(late.rules().phase() == GameSnapshot.DRAWN_TURN && late.turnSeat() == 0 && late.remainingTiles() == 1
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
        server.runOnServer(s -> {
            LobbyService service = LobbyService.get(s); int actions = 0;
            while (service.gameView(table.tableId(), connection.getServerPlayer().getUUID()).rules().phase() != GameSnapshot.FINISHED) {
                require(++actions <= 30, "The final two drawn tiles and their responses must finish in a bounded number of actions");
                advance(service, table, connection.getServerPlayer());
            }
        });
        sync(connection);
        GameSnapshot finished = snapshot(context).game();
        require(finished != null && finished.rules().phase() == GameSnapshot.FINISHED && finished.rules().result() != null
                        && finished.remainingTiles() == 0,
                "The final discard responses must leave a retained result and an exhausted live wall");
        context.waitTick();
        context.takeScreenshot(dedicated ? "riichicraft-dedicated-round-result" : "riichicraft-round-result");
        context.clickScreenButton("screen.riichicraft.game.close");
        sync(connection);
        MahjongTableClientGameTest.openTable(context, connection, target);
        require(snapshot(context).game().gameId().equals(finished.gameId())
                        && snapshot(context).game().rules().result().equals(finished.rules().result()),
                "Closing and reopening the physical table must restore the same settled result");
        context.clickScreenButton("screen.riichicraft.game.return_lobby");
        sync(connection);
        require(snapshot(context).game() == null && snapshot(context).ownSeat() == 0
                        && snapshot(context).seats().stream().noneMatch(LobbySnapshotPayload.Seat::ready),
                "An explicit return must clear the retained result and readiness while retaining all seats");
    }

    static void runReconnect(ClientGameTestContext context, TestDedicatedServerContext server) {
        TableFixtures table = null;
        GameSnapshot before;
        UUID playerId;
        UUID oldView;
        try {
            try (TestDedicatedServerConnection connection = server.connect()) {
                connection.waitForChunksRender();
                BlockPos target = context.computeOnClient(c -> c.player.blockPosition().offset(0, 0, -2));
                MahjongTableClientGameTest.openTable(context, connection, target);
                require(snapshot(context).ownSeat() == -1, "The preceding lobby disconnect must have released its seat");
                context.clickScreenButton("screen.riichicraft.lobby.join");
                sync(connection);
                require(snapshot(context).ownSeat() == 0, "The released lobby seat must be reusable");
                UUID initialView = snapshot(context).viewId();
                playerId = snapshot(context).seats().getFirst().playerId();
                table = createFixtures(server, connection, target, initialView);
                sync(connection);
                context.clickScreenButton("screen.riichicraft.lobby.prepare");
                sync(connection);
                context.clickScreenButton("screen.riichicraft.lobby.start");
                sync(connection);
                before = snapshot(context).game();
                require(before != null && !before.paused(), "The disconnect scenario must begin inside a running game");
                context.clickScreenButton("screen.riichicraft.game.close");
                sync(connection);
                TableFixtures openTable = table;
                require(server.computeOnServer(s -> !LobbyService.get(s).gameView(openTable.tableId(),
                                connection.getServerPlayer().getUUID()).paused()),
                        "Closing the real game screen must not pause a present participant's game");
                MahjongTableClientGameTest.openTable(context, connection, target);
                require(snapshot(context).game().gameId().equals(before.gameId())
                                && snapshot(context).game().ownHand().equals(before.ownHand()),
                        "Reopening a game screen must restore the same private hand");
                oldView = snapshot(context).viewId();
            }
            TableFixtures active = table;
            server.waitFor(s -> {
                GameSnapshot view = LobbyService.get(s).gameView(active.tableId(), playerId);
                return view != null && view.paused() && !view.seats().getFirst().present();
            });
            try (TestDedicatedServerConnection connection = server.connect()) {
                connection.waitForChunksRender();
                BlockPos target = context.computeOnClient(c -> c.player.blockPosition().offset(0, 0, -2));
                MahjongTableClientGameTest.openTable(context, connection, target);
                GameSnapshot restored = snapshot(context).game();
                require(snapshot(context).ownSeat() == 0 && restored != null && restored.paused()
                                && restored.gameId().equals(before.gameId()) && restored.ownHand().equals(before.ownHand())
                                && restored.drawnTile() == before.drawnTile() && restored.turnSeat() == before.turnSeat()
                                && restored.remainingTiles() == before.remainingTiles(),
                        "Real TCP reconnect must restore the same seat, hand, draw and turn in a paused game");
                context.runOnClient(c -> ClientPlayNetworking.send(new GameActionPayload(oldView, before.gameId(),
                        before.revision(), GameActionPayload.ABORT, -1)));
                sync(connection);
                require(snapshot(context).game() != null, "The pre-disconnect view cannot terminate the restored game");
                context.runOnClient(c -> c.gui.toastManager().clear());
                context.waitTick();
                context.takeScreenshot("riichicraft-paused-reconnected");
                server.runOnServer(s -> {
                    LobbyService service = LobbyService.get(s);
                    for (int seat = 1; seat < 4; seat++) {
                        ServerPlayer player = active.fixtures().get(seat - 1).player();
                        GameSnapshot view = service.gameView(active.tableId(), player.getUUID());
                        service.handleGameAction(player, new GameActionPayload(active.viewIds().get(seat),
                                view.gameId(), view.revision(), GameActionPayload.RESUME, -1));
                    }
                });
                sync(connection);
                require(snapshot(context).game().paused(), "Three confirmations must not resume without the returning player");
                context.clickScreenButton("screen.riichicraft.game.resume");
                sync(connection);
                GameSnapshot resumed = snapshot(context).game();
                require(!resumed.paused() && resumed.ownHand().equals(before.ownHand())
                                && resumed.drawnTile() == before.drawnTile() && resumed.remainingTiles() == 69,
                        "The fourth confirmation must resume without drawing, discarding or redealing");
                long revision = resumed.revision();
                context.clickScreenButton("screen.riichicraft.game.abort");
                sync(connection);
                require(snapshot(context).game() != null && snapshot(context).game().revision() == revision,
                        "The first terminate click must only open local confirmation");
                context.clickScreenButton("screen.riichicraft.game.confirm_abort");
                sync(connection);
                require(snapshot(context).game() == null && snapshot(context).ownSeat() == 0
                                && snapshot(context).seats().stream().noneMatch(LobbySnapshotPayload.Seat::ready),
                        "A confirmed termination must return to the lobby with seats retained and readiness cleared");
            }
        } finally {
            if (table != null) closeFixtures(server, table);
        }
    }

    static void runFinishedReconnect(ClientGameTestContext context, TestDedicatedServerContext server) {
        TableFixtures table = null;
        UUID playerId, gameId, closedView;
        GameSnapshot.Result result;
        List<Integer> points;
        BlockPos target;
        try {
            try (TestDedicatedServerConnection connection = server.connect()) {
                connection.waitForChunksRender();
                target = context.computeOnClient(c -> c.player.blockPosition().offset(0, 0, -2));
                MahjongTableClientGameTest.openTable(context, connection, target);
                context.clickScreenButton("screen.riichicraft.lobby.join"); sync(connection);
                require(snapshot(context).ownSeat() == 0, "The completed preceding scenario must leave a reusable east seat");
                closedView = snapshot(context).viewId(); playerId = snapshot(context).seats().getFirst().playerId();
                table = createFixtures(server, connection, target, closedView); sync(connection);
                context.clickScreenButton("screen.riichicraft.lobby.prepare"); sync(connection);
                context.clickScreenButton("screen.riichicraft.lobby.start"); sync(connection);
                TableFixtures active = table;
                server.runOnServer(s -> {
                    LobbyService service = LobbyService.get(s); int actions = 0;
                    while (service.gameView(active.tableId(), connection.getServerPlayer().getUUID()).rules().phase() != GameSnapshot.FINISHED) {
                        require(++actions <= 400, "A real-server all-pass round must reach one retained terminal settlement");
                        advance(service, active, connection.getServerPlayer());
                    }
                });
                sync(connection);
                GameSnapshot finished = snapshot(context).game();
                require(finished != null && finished.rules().result() != null, "The actual client must decode a result before disconnecting");
                gameId = finished.gameId(); result = finished.rules().result();
                points = finished.rules().seats().stream().map(GameSnapshot.SeatRules::points).toList();
            }
            TableFixtures retained = table;
            server.waitFor(s -> {
                GameSnapshot finished = LobbyService.get(s).gameView(retained.tableId(), playerId);
                return finished != null && finished.rules().phase() == GameSnapshot.FINISHED
                        && !finished.seats().getFirst().present() && !finished.paused();
            });
            try (TestDedicatedServerConnection connection = server.connect()) {
                connection.waitForChunksRender();
                MahjongTableClientGameTest.openTable(context, connection, target);
                GameSnapshot restored = snapshot(context).game();
                require(snapshot(context).ownSeat() == 0 && restored != null && !restored.paused()
                                && restored.gameId().equals(gameId) && restored.rules().result().equals(result)
                                && restored.rules().seats().stream().map(GameSnapshot.SeatRules::points).toList().equals(points),
                        "A real TCP reconnect after settlement preserves seat, ledger and result without asking to resume");
                context.runOnClient(c -> ClientPlayNetworking.send(new GameActionPayload(closedView, gameId,
                        restored.revision(), GameActionPayload.RETURN_LOBBY, -1)));
                sync(connection);
                require(snapshot(context).game().rules().result().equals(result),
                        "The pre-disconnect view cannot remove the retained settlement");
                context.runOnClient(c -> c.gui.toastManager().clear());
                context.waitTick(); context.takeScreenshot("riichicraft-result-reconnected");
                context.clickScreenButton("screen.riichicraft.game.return_lobby"); sync(connection);
                require(snapshot(context).game() == null && snapshot(context).ownSeat() == 0
                                && snapshot(context).seats().stream().noneMatch(LobbySnapshotPayload.Seat::ready),
                        "A reconnected participant may explicitly return the whole table to the unprepared lobby");
            }
        } finally {
            if (table != null) closeFixtures(server, table);
        }
    }

    private static void runMouseCall(ClientGameTestContext context, TestServerContext server,
            TestServerConnection connection, TableFixtures original, boolean dedicated) {
        List<UUID> views = new ArrayList<>(original.viewIds()); views.set(0, snapshot(context).viewId());
        TableFixtures table = new TableFixtures(original.fixtures(), List.copyOf(views), original.tableId());
        boolean called = false;
        for (int attempt = 0; attempt < 4 && !called; attempt++) {
            server.runOnServer(s -> {
                LobbyService service = LobbyService.get(s);
                for (int seat = 1; seat < 4; seat++) {
                    service.handleAction(table.fixtures().get(seat - 1).player(), new LobbyActionPayload(table.viewIds().get(seat),
                            service.registry().snapshot(table.tableId()).orElseThrow().revision(), LobbyActionPayload.SET_READY, true));
                }
            }); sync(connection);
            context.clickScreenButton("screen.riichicraft.lobby.prepare"); sync(connection);
            context.clickScreenButton("screen.riichicraft.lobby.start"); sync(connection);
            boolean found = server.computeOnServer(s -> seekEastCall(LobbyService.get(s), table, connection.getServerPlayer()));
            sync(connection);
            if (!found) {
                require(snapshot(context).game().rules().phase() == GameSnapshot.FINISHED,
                        "An unsuccessful bounded call search must end a genuine played round");
                context.clickScreenButton("screen.riichicraft.game.return_lobby"); sync(connection);
                continue;
            }
            GameSnapshot before = snapshot(context).game();
            require(before.rules().opportunity() != null && before.rules().responses().stream()
                            .anyMatch(option -> option.action() == GameActionPayload.CHI || option.action() == GameActionPayload.PON),
                    "The actual decoded client view must offer a legal exact physical chi or pon");
            server.runOnServer(s -> auditPhysicalCopies(LobbyService.get(s), table, connection.getServerPlayer()));
            context.runOnClient(c -> c.gui.toastManager().clear()); context.waitTick();
            context.takeScreenshot(dedicated ? "riichicraft-dedicated-call-menu" : "riichicraft-call-menu");
            int action = clickCallOption(context); sync(connection);
            GameSnapshot after = snapshot(context).game();
            require(after.rules().phase() == GameSnapshot.CALL_DISCARD && after.turnSeat() == 0 && after.drawnTile() == -1
                            && after.ownHand().size() == before.ownHand().size() - 2 && !after.rules().legalDiscards().isEmpty(),
                    "A real menu click must enter immediate chi/pon discard with no fabricated draw and a nonempty legal mask");
            List<Integer> consumed = before.ownHand().stream().filter(tile -> !after.ownHand().contains(tile)).sorted().toList();
            require(consumed.size() == 2 && before.rules().responses().stream().anyMatch(option -> option.action() == action
                            && option.handTiles().stream().sorted().toList().equals(consumed)),
                    "The mouse-selected menu sends one exact offered two-copy selection, including red-copy distinctions");
            GameSnapshot.MeldView meld = after.rules().seats().getFirst().melds().getFirst();
            int provider = before.rules().opportunity().actorSeat(), claimed = before.rules().opportunity().tileId();
            HashSet<Integer> group = new HashSet<>(consumed); group.add(claimed);
            require(meld.kind() == (action == GameActionPayload.CHI ? GameSnapshot.MELD_CHI : GameSnapshot.MELD_PON)
                            && new HashSet<>(meld.tiles()).equals(group) && meld.providerSeat() == provider && meld.claimedTileId() == claimed
                            && after.seats().get(provider).discards().getLast() == claimed
                            && after.rules().seats().get(provider).river().getLast().claimedBy() == 0
                            && after.remainingTiles() == before.remainingTiles(),
                    "The real call preserves exact claimed-river provenance and moves only the three meld copies without drawing");
            context.waitTick(); context.takeScreenshot(dedicated ? "riichicraft-dedicated-call-discard" : "riichicraft-call-discard");
            clickHandTile(context);
            int selected = context.computeOnClient(c -> ((LobbyScreen)c.gui.screen()).selectedTileId());
            require(after.rules().legalDiscards().contains(selected) && snapshot(context).game().revision() == after.revision(),
                    "A no-draw CALL_DISCARD hand still has actual clickable legal tiles and selection does not send early");
            context.clickScreenButton("screen.riichicraft.game.discard"); sync(connection);
            require(snapshot(context).game().seats().getFirst().discards().size() == before.seats().getFirst().discards().size() + 1
                            && snapshot(context).game().seats().getFirst().discards().getLast() == selected,
                    "The actual confirmation click appends exactly one legal post-call discard to east's river");
            server.runOnServer(s -> {
                LobbyService service = LobbyService.get(s); int replies = 0;
                while (service.gameView(table.tableId(), connection.getServerPlayer().getUUID()).rules().opportunity() != null) {
                    require(++replies <= 3, "Only the other three recipients may still answer the post-call discard");
                    advance(service, table, connection.getServerPlayer());
                }
                auditPhysicalCopies(service, table, connection.getServerPlayer());
            }); sync(connection);
            require(snapshot(context).game().rules().phase() == GameSnapshot.DRAWN_TURN
                            && snapshot(context).game().turnSeat() == 1
                            && snapshot(context).game().remainingTiles() == before.remainingTiles() - 1,
                    "Only finishing the actual post-call discard response window draws once for south");
            context.clickScreenButton("screen.riichicraft.game.abort"); sync(connection);
            context.clickScreenButton("screen.riichicraft.game.confirm_abort"); sync(connection);
            require(snapshot(context).game() == null, "The independent successful call scenario cleans up through the actual confirmed terminate action");
            called = true;
        }
        require(called, "At least one of four real played deals must exercise an actual mouse-selected chi or pon menu");
    }

    private static boolean seekEastCall(LobbyService service, TableFixtures table, ServerPlayer east) {
        int actions = 0;
        while (service.gameView(table.tableId(), east.getUUID()).rules().phase() != GameSnapshot.FINISHED) {
            require(++actions <= 400, "Seeking a real call is bounded by one round's actual tiles and responses");
            GameSnapshot own = service.gameView(table.tableId(), east.getUUID());
            if (own.rules().responses().stream().anyMatch(option -> option.action() == GameActionPayload.CHI || option.action() == GameActionPayload.PON)) {
                long serial = own.rules().opportunity().serial();
                for (int seat = 1; seat < 4; seat++) {
                    ServerPlayer player = table.fixtures().get(seat - 1).player();
                    GameSnapshot response = service.gameView(table.tableId(), player.getUUID());
                    if (!response.rules().responses().isEmpty()) service.handleGameAction(player,
                            new GameActionPayload(table.viewIds().get(seat), response.gameId(), response.revision(),
                                    GameActionPayload.PASS, -1, serial, -1, List.of()));
                }
                return true;
            }
            if (own.rules().phase() == GameSnapshot.DRAWN_TURN && own.turnSeat() != 0) {
                int actor = own.turnSeat(); ServerPlayer player = table.fixtures().get(actor - 1).player();
                GameSnapshot actorView = service.gameView(table.tableId(), player.getUUID());
                HandInput receiver = new HandInput(own.ownHand(), List.of());
                Integer supply = actorView.rules().legalDiscards().stream().filter(tile -> CallActions.options(receiver, tile,
                        new CallActions.Context(actor, 0, own.remainingTiles(), 0, WinContext.Riichi.NONE)).stream()
                        .anyMatch(call -> call.kind() == Meld.Kind.CHI || call.kind() == Meld.Kind.PON)).findFirst().orElse(null);
                if (supply != null) {
                    service.handleGameAction(player, new GameActionPayload(table.viewIds().get(actor), own.gameId(), actorView.revision(),
                            GameActionPayload.DISCARD, supply));
                    require(service.gameView(table.tableId(), east.getUUID()).revision() > own.revision(),
                            "Guidance still discards only a physical tile in the provider's current legal mask");
                    continue;
                }
            }
            advance(service, table, east);
        }
        return false;
    }

    private static int clickCallOption(ClientGameTestContext context) {
        double[] position = context.computeOnClient(c -> {
            Button button = c.gui.screen().children().stream().filter(child -> child instanceof Button)
                    .map(child -> (Button)child).filter(widget -> widget.active && widget.visible
                            && widget.getMessage().getContents() instanceof TranslatableContents contents
                            && (contents.getKey().equals("screen.riichicraft.game.chi") || contents.getKey().equals("screen.riichicraft.game.pon")))
                    .findFirst().orElseThrow();
            String key = ((TranslatableContents)button.getMessage().getContents()).getKey(); var window = c.getWindow();
            return new double[] {(button.getX()+button.getWidth()/2.0)*window.getScreenWidth()/window.getGuiScaledWidth(),
                    (button.getY()+button.getHeight()/2.0)*window.getScreenHeight()/window.getGuiScaledHeight(),
                    key.endsWith(".chi") ? GameActionPayload.CHI : GameActionPayload.PON};
        });
        context.getInput().setCursorPos(position[0], position[1]); context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        return (int)position[2];
    }

    private static void auditPhysicalCopies(LobbyService service, TableFixtures table, ServerPlayer east) {
        HashSet<Integer> tiles = new HashSet<>(); GameSnapshot publicView = service.gameView(table.tableId(), east.getUUID());
        for (int seat = 0; seat < 4; seat++) {
            ServerPlayer player = seat == 0 ? east : table.fixtures().get(seat - 1).player();
            for (int tile : service.gameView(table.tableId(), player.getUUID()).ownHand()) require(tiles.add(tile), "Concealed physical copies remain distinct across all recipients");
            for (GameSnapshot.MeldView meld : publicView.rules().seats().get(seat).melds())
                for (int tile : meld.tiles()) require(tiles.add(tile), "A called physical copy belongs to its public meld exactly once");
            for (int index = 0; index < publicView.seats().get(seat).discards().size(); index++) {
                if (publicView.rules().seats().get(seat).river().get(index).claimedBy() < 0)
                    require(tiles.add(publicView.seats().get(seat).discards().get(index)), "Only unclaimed river copies retain physical ownership");
            }
        }
        require(tiles.size() + publicView.remainingTiles() + 14 == 136,
                "All recipient hands, actual melds, unclaimed rivers, live count and fourteen dead tiles conserve the full wall");
    }

    static void closeFixtures(TestServerContext server, TableFixtures table) {
        server.runOnServer(s -> {
            LobbyService service = LobbyService.get(s);
            for (ConnectedTestPlayer fixture : table.fixtures()) {
                service.disconnect(fixture.player());
                fixture.close();
            }
        });
    }

    static void advance(LobbyService service, TableFixtures table, ServerPlayer east) {
        GameSnapshot current = service.gameView(table.tableId(), east.getUUID());
        require(current != null && current.rules().phase() != GameSnapshot.FINISHED,
                "A finished result cannot be advanced as though it were a live turn");
        if (current.rules().phase() == GameSnapshot.DISCARD_RESPONSES || current.rules().phase() == GameSnapshot.KAN_RESPONSES) {
            for (int seat = 0; seat < 4; seat++) {
                ServerPlayer responder = seat == 0 ? east : table.fixtures().get(seat - 1).player();
                GameSnapshot own = service.gameView(table.tableId(), responder.getUUID());
                if (own.rules().responses().isEmpty()) continue;
                require(own.rules().responses().stream().anyMatch(option -> option.action() == GameActionPayload.PASS),
                        "A server fixture may pass only an actual offered response");
                service.handleGameAction(responder, new GameActionPayload(table.viewIds().get(seat), own.gameId(),
                        own.revision(), GameActionPayload.PASS, -1, own.rules().opportunity().serial(), -1, List.of()));
                require(service.gameView(table.tableId(), east.getUUID()).revision() > current.revision(),
                        "An offered stable-window response must advance the authoritative revision");
                return;
            }
            throw new AssertionError("An open response window must have a pending recipient");
        }
        int seat = current.turnSeat();
        ServerPlayer player = seat == 0 ? east : table.fixtures().get(seat - 1).player();
        GameSnapshot own = service.gameView(table.tableId(), player.getUUID());
        int discard = own.rules().legalDiscards().contains(own.drawnTile())
                ? own.drawnTile() : own.rules().legalDiscards().getFirst();
        service.handleGameAction(player, new GameActionPayload(table.viewIds().get(seat), own.gameId(),
                own.revision(), GameActionPayload.DISCARD, discard));
        GameSnapshot next = service.gameView(table.tableId(), east.getUUID());
        require(next == null || next.revision() > current.revision(), "A valid server fixture discard must advance");
    }

    private static boolean isLateDraw(GameSnapshot snapshot) {
        require(snapshot != null && snapshot.rules().phase() != GameSnapshot.FINISHED,
                "The controlled first non-wind discard must allow the all-pass round to reach a late live draw");
        return snapshot.rules().phase() == GameSnapshot.DRAWN_TURN && snapshot.turnSeat() == 0 && snapshot.remainingTiles() == 1;
    }

    private static LobbySnapshotPayload snapshot(ClientGameTestContext context) {
        return context.computeOnClient(c -> ((LobbyScreen) c.gui.screen()).snapshot());
    }

    private static void clickHandTile(ClientGameTestContext context) {
        double[] position = context.computeOnClient(c -> {
            GameTileButton button = c.gui.screen().children().stream()
                    .filter(child -> child instanceof GameTileButton tile && tile.active && tile.visible)
                    .map(child -> (GameTileButton) child)
                    .sorted(java.util.Comparator.comparingInt(tile -> tile.tileId() / 4 >= 27 && tile.tileId() / 4 <= 30 ? 1 : 0))
                    .findFirst().orElseThrow();
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
