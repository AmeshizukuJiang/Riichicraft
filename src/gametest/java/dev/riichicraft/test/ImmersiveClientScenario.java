package dev.riichicraft.test;

import dev.riichicraft.client.DoubleClickDiscardGate;
import dev.riichicraft.client.TableCamera;
import dev.riichicraft.client.TableInteractionClient;
import dev.riichicraft.client.TableScreen;
import dev.riichicraft.network.GameActionPayload;
import dev.riichicraft.network.GameSnapshot;
import dev.riichicraft.network.LobbyActionPayload;
import dev.riichicraft.network.LobbySnapshotPayload;
import dev.riichicraft.server.LobbyService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.client.CameraType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import org.lwjgl.glfw.GLFW;

/** One real TCP player clicking a real chair and world tile, plus three mounted native fixtures. */
final class ImmersiveClientScenario {
    private record Session(BlockPos center, UUID table, List<ConnectedTestPlayer> fixtures, List<UUID> fixtureViews) { }

    static void run(ClientGameTestContext context, TestDedicatedServerContext server) {
        verifyDoubleClickGate();
        dev.riichicraft.client.TableCameraChecks.verifyVisibleHands();
        Session session = null;
        CameraType originalPerspective = context.computeOnClient(client -> client.options.getCameraType());
        context.getInput().resizeWindow(1280, 960);
        try (var connection = server.connect()) {
            BlockPos center = server.computeOnServer(s -> {
                ServerPlayer player = connection.getServerPlayer();
                var level = connection.getServerLevel();
                BlockPos pos = new BlockPos(24, player.blockPosition().getY(), 8);
                for (int x = -3; x <= 3; x++) for (int z = -4; z <= 4; z++) {
                    for (int y = 0; y <= 5; y++) level.setBlockAndUpdate(pos.offset(x, y, z), Blocks.AIR.defaultBlockState());
                    level.setBlockAndUpdate(pos.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                }
                FurnitureTestLayout.body(level, pos, Direction.NORTH);
                FurnitureTestLayout.chairs(level, pos, 4);
                player.setGameMode(GameType.CREATIVE);
                player.teleportTo(pos.getX() + .5, pos.getY(), pos.getZ() + 3.4);
                player.setYRot(180); player.setXRot(25);
                return pos;
            });
            connection.waitForClientboundPackets(); connection.waitForChunksRender(); context.waitTicks(3);
            context.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_BACK));
            context.getInput().lookAt(center.south(2)); context.waitTick();
            context.runOnClient(client -> require(client.hitResult instanceof BlockHitResult hit
                    && hit.getBlockPos().equals(center.south(2)), "Actual crosshair must hit the ordinary south chair"));
            context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            sync(connection); context.waitForScreen(TableScreen.class);
            context.waitFor(client -> TableInteractionClient.scene() != null && TableInteractionClient.scene().mounted(), 200);
            var joined = snapshot(context);
            require(joined.ownSeat() == 0, "The clicked south physical chair is fixed initial East for a north-facing table");
            require(server.computeOnServer(s -> connection.getServerPlayer().isPassenger()), "Chair interaction actually mounts a server entity");
            // A true escape hides controls without closing the server subscription or reserving a new seat.
            context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE); context.waitForScreen(null);
            context.getInput().pressKey(GLFW.GLFW_KEY_R); context.waitForScreen(TableScreen.class);
            require(snapshot(context).viewId().equals(joined.viewId()), "R reopens the same authenticated table view after Escape");
            context.clickScreenButton("screen.riichicraft.lobby.mode.single"); sync(connection);
            require(snapshot(context).mode() == 1, "The small table settings choose East match mode");
            session = server.computeOnServer(s -> {
                var service = LobbyService.get(s);
                List<ConnectedTestPlayer> fixtures = new ArrayList<>();
                List<UUID> views = new ArrayList<>();
                for (Direction side : List.of(Direction.EAST, Direction.NORTH, Direction.WEST)) {
                    var fixture = new ConnectedTestPlayer(s, connection.getServerLevel(), center, "Seated" + side.name());
                    fixtures.add(fixture);
                    fixture.player().teleportTo(center.relative(side, 2).getX() + .5, center.getY(), center.relative(side, 2).getZ() + .5);
                    require(service.sit(fixture.player(), center.relative(side, 2)), "Native fixture mounts its specific physical chair");
                    UUID view = service.open(fixture.player(), center); views.add(view);
                    UUID table = service.registry().tableForPlayer(fixture.player().getUUID()).orElseThrow();
                    service.handleAction(fixture.player(), new LobbyActionPayload(view, service.registry().snapshot(table).orElseThrow().revision(), LobbyActionPayload.SET_READY, true));
                }
                UUID table = service.registry().tableForPlayer(connection.getServerPlayer().getUUID()).orElseThrow();
                return new Session(center, table, List.copyOf(fixtures), List.copyOf(views));
            });
            var active = session; sync(connection);
            context.clickScreenButton("screen.riichicraft.lobby.prepare"); sync(connection);
            require(snapshot(context).allReady(), "All four mounted seats report ready");
            context.clickScreenButton("screen.riichicraft.lobby.start"); sync(connection);
            context.waitFor(client -> TableInteractionClient.snapshot() != null && TableInteractionClient.snapshot().game() != null
                    && TableCamera.overhead() && TableCamera.handBounds() != null, 200);
            GameSnapshot opening = snapshot(context).game();
            context.runOnClient(client -> {
                var scene = TableInteractionClient.scene();
                require(scene.ownSeat() == 0 && scene.hands().get(0).equals(opening.ownHand()), "The real participant's physical hand is rendered from its authorized scene");
                for (int seat = 1; seat < 4; seat++) require(scene.hands().get(seat).isEmpty(), "Mounted participant receives no opponent concealed identities");
                require(TableCamera.active() && TableCamera.overhead(), "New round switches the real render camera to overhead");
            });
            verifySettlementDeltas(opening);
            context.getInput().pressKey(GLFW.GLFW_KEY_T);
            context.waitForScreen(net.minecraft.client.gui.screens.ChatScreen.class);
            context.getInput().typeChars("r v");context.waitTick();
            require(context.computeOnClient(c->TableCamera.overhead()) && snapshot(context).game().revision()==opening.revision(),
                    "Typing table hotkey letters in actual chat neither changes camera nor plays a tile");
            context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);context.waitForScreen(null);
            context.getInput().pressKey(GLFW.GLFW_KEY_R);context.waitForScreen(TableScreen.class);
            shot(context,"riichicraft-immersive-overhead");
            int tile = opening.rules().legalDiscards().getFirst();
            double[] cursor = tileCursor(context, tile);
            context.getInput().setCursorPos(cursor[0], cursor[1]);
            context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            require(snapshot(context).game().revision() == opening.revision(), "First real world-tile click selects without sending a discard");
            require(context.computeOnClient(client -> ((TableScreen) client.gui.screen()).selectedTileId()) == tile, "World projection selects the exact physical copy");
            context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT); sync(connection);
            context.waitFor(client -> TableInteractionClient.snapshot().game().revision() > opening.revision(), 200);
            GameSnapshot afterDiscard = snapshot(context).game();
            require(afterDiscard.seats().get(0).discards().equals(List.of(tile)), "The second real click appends exactly one authoritative discard");
            context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT); sync(connection);
            require(snapshot(context).game().seats().get(0).discards().equals(List.of(tile)), "A third click cannot replay the accepted discard");
            context.getInput().pressKey(GLFW.GLFW_KEY_V); context.waitTick();
            require(!context.computeOnClient(client -> TableCamera.overhead()), "V returns to the normal seated view");
            context.waitTicks(2);
            context.runOnClient(client->{
                int width=client.getWindow().getGuiScaledWidth(),height=client.getWindow().getGuiScaledHeight();
                for(int id:TableInteractionClient.snapshot().game().ownHand()) {
                    var bounds=TableCamera.tileBounds(id);
                    require(bounds!=null && bounds.left()>=0 && bounds.right()<=width && bounds.top()>=0 && bounds.bottom()<=height,
                            "Every own standing hand tile stays completely visible in the actual normal chair camera");
                }
            });
            shot(context,"riichicraft-immersive-seated");
            context.getInput().pressKey(GLFW.GLFW_KEY_V); context.waitTick();
            context.getInput().pressKey(GLFW.GLFW_KEY_R); context.waitForScreen(null);
            context.getInput().holdKeyFor(GLFW.GLFW_KEY_LEFT_SHIFT,4);
            sync(connection);
            context.waitFor(client -> TableInteractionClient.scene() != null && !TableInteractionClient.scene().mounted()
                    && TableInteractionClient.snapshot().game().paused(), 200);
            context.getInput().pressKey(GLFW.GLFW_KEY_R); context.waitForScreen(TableScreen.class);
            context.runOnClient(client -> {
                var scene = TableInteractionClient.scene();
                require(scene.ownSeat() == 0 && !scene.hands().get(0).isEmpty() && !TableCamera.active(), "Dismount retains own participant hand and restores free camera");
                require(client.options.getCameraType() == CameraType.THIRD_PERSON_BACK, "Dismount restores the original third-person perspective exactly");
                for (int seat = 1; seat < 4; seat++) require(scene.hands().get(seat).isEmpty(), "Dismount cannot turn a participant into an all-hands observer");
            });
            server.runOnServer(s -> {
                var service = LobbyService.get(s);
                require(service.sit(connection.getServerPlayer(), active.center().south(2)), "The original participant reclaims its original chair");
            });
            sync(connection);
            UUID resumedView = snapshot(context).viewId();
            server.runOnServer(s -> {
                for (int seat = 0; seat < 4; seat++) act(LobbyService.get(s), active, connection.getServerPlayer(), resumedView, seat, GameActionPayload.RESUME, -1);
            });
            sync(connection);
            context.waitFor(client -> !TableInteractionClient.snapshot().game().paused(), 200);
            // Process a complete legal suffix, without replacing the TCP player's immersive view.
            UUID playerView = snapshot(context).viewId();
            server.runOnServer(s -> {
                var service = LobbyService.get(s);
                for (int step = 0; step < 500 && service.gameView(active.table(), null).rules().phase() != GameSnapshot.FINISHED; step++)
                    advance(service, active, connection.getServerPlayer(), playerView);
                require(service.gameView(active.table(), null).rules().phase() == GameSnapshot.FINISHED, "Legal all-pass play reaches a retained settlement");
            });
            sync(connection);
            context.waitFor(client -> TableInteractionClient.scene().finished(), 200);
            GameSnapshot finished = snapshot(context).game();
            context.runOnClient(client -> {
                require(TableCamera.overhead() && TableInteractionClient.scene().hands().stream().allMatch(hand -> !hand.isEmpty()),
                        "Settlement lays every concealed hand out in the real overhead scene");
                require(client.gui.screen() instanceof TableScreen, "Compact settlement stays over the actual world table");
            });
            shot(context,"riichicraft-immersive-result");
            require(finished.match().phase() == GameSnapshot.MATCH_ROUND_RESULT, "First exhaustive East round offers continuation");
            context.clickScreenButton("screen.riichicraft.game.next_round"); sync(connection);
            require(snapshot(context).game().match().confirmations().get(0), "Compact result button records the real player's continuation vote");
            require(snapshot(context).game().gameId().equals(finished.gameId()), "One vote does not advance other participants");
            server.runOnServer(s -> {
                var service = LobbyService.get(s);
                for (int seat = 1; seat < 4; seat++) act(service, active, connection.getServerPlayer(), playerView, seat, GameActionPayload.NEXT_ROUND, -1);
            });
            sync(connection);
            context.waitFor(client -> !TableInteractionClient.snapshot().game().gameId().equals(finished.gameId()), 200);
            require(context.computeOnClient(client -> TableCamera.overhead()), "The next accepted round automatically restores overhead view");
            server.runOnServer(s -> s.overworld().destroyBlock(active.center(), false));
            context.waitForScreen(null);
            context.runOnClient(client -> require(!TableCamera.active() && !TableInteractionClient.active(), "Destroyed table releases local camera and interaction session"));
        } finally {
            context.runOnClient(client -> client.options.setCameraType(originalPerspective));
            context.getInput().resizeWindow(640, 480);
            if (session != null) {
                var active = session;
                server.runOnServer(s -> { for (var fixture : active.fixtures()) { LobbyService.get(s).disconnect(fixture.player()); fixture.close(); } });
            }
        }
    }

    private static void advance(LobbyService service, Session session, ServerPlayer actual, UUID actualView) {
        GameSnapshot publicView = service.gameView(session.table(), null);
        int phase = publicView.rules().phase();
        if (phase == GameSnapshot.DRAWN_TURN || phase == GameSnapshot.CALL_DISCARD) {
            int actor = publicView.turnSeat();
            var own = service.gameView(session.table(), player(session, actual, actor).getUUID());
            int tile = own.rules().legalDiscards().contains(own.drawnTile()) ? own.drawnTile() : own.rules().legalDiscards().getFirst();
            act(service, session, actual, actualView, actor, GameActionPayload.DISCARD, tile);
        } else if (phase != GameSnapshot.FINISHED) {
            long serial = publicView.rules().opportunity().serial();
            for (int seat = 0; seat < 4; seat++) {
                var own = service.gameView(session.table(), player(session, actual, seat).getUUID());
                if (own.rules().opportunity() != null && own.rules().opportunity().serial() == serial && !own.rules().responses().isEmpty())
                    act(service, session, actual, actualView, seat, GameActionPayload.PASS, -1);
            }
        }
    }

    private static ServerPlayer player(Session session, ServerPlayer actual, int seat) { return seat == 0 ? actual : session.fixtures().get(seat - 1).player(); }

    private static void act(LobbyService service, Session session, ServerPlayer actual, UUID actualView, int seat, int action, int tile) {
        ServerPlayer player = player(session, actual, seat);
        UUID view = seat == 0 ? actualView : session.fixtureViews().get(seat - 1);
        GameSnapshot own = service.gameView(session.table(), player.getUUID());
        long window = action == GameActionPayload.NEXT_ROUND ? own.match().continueEpoch()
                : GameActionPayload.isResponse(action) ? own.rules().opportunity().serial() : -1;
        service.handleGameAction(player, new GameActionPayload(view, own.gameId(), own.revision(), action, tile, window, -1, List.of()));
    }

    private static double[] tileCursor(ClientGameTestContext context, int tile) {
        context.waitFor(client -> TableCamera.tileBounds(tile) != null, 200);
        return context.computeOnClient(client -> {
            var bounds = TableCamera.tileBounds(tile);
            double x = (bounds.left() + bounds.right()) / 2, y = (bounds.top() + bounds.bottom()) / 2;
            require(TableCamera.pickTile(x, y) == tile, "Projected native tile center picks the exact physical hand identity");
            var window = client.getWindow();
            return new double[]{x * window.getScreenWidth() / window.getGuiScaledWidth(), y * window.getScreenHeight() / window.getGuiScaledHeight()};
        });
    }

    static void verifyDoubleClickGate() {
        var gate = new DoubleClickDiscardGate(); UUID game = UUID.randomUUID(); long now = 1_000_000_000L;
        require(!gate.click(game, 3, 20, false, now, true), "First click only selects");
        require(gate.click(game, 3, 20, false, now + 100_000_000L, true), "Matching physical tile confirms within 300ms");
        require(!gate.click(game, 3, 20, false, now + 110_000_000L, true), "Third click starts a new pair");
        require(!gate.click(game, 3, 21, false, now + 120_000_000L, true), "An identical face's other physical copy is not a second click");
        require(!gate.click(game, 4, 21, false, now + 130_000_000L, true), "New authoritative revision invalidates the pair");
        require(!gate.click(game, 4, 21, false, now + 440_000_001L, true), "Expired double click cannot discard");
        require(!gate.click(game, 4, 21, true, now + 450_000_000L, true), "Riichi mode change needs a new pair");
        require(!gate.click(game, 4, 21, true, now + 460_000_000L, false), "Lost legal mask clears pending click");
        require(!gate.click(game, 4, 21, true, now + 470_000_000L, true), "A fresh click after lost eligibility only selects");
        gate.reset();
        require(!gate.click(game, 4, 21, true, now + 480_000_000L, true), "Explicit screen/resize reset invalidates selection");
        require(!gate.click(UUID.randomUUID(), 4, 21, true, now + 490_000_000L, true), "Another game cannot complete an old pair");
    }

    static void verifySettlementDeltas(GameSnapshot opening) {
        List<GameSnapshot.Payment> transfers = List.of(new GameSnapshot.Payment(0, 1, 2_000, 0),
                new GameSnapshot.Payment(2, 1, 100, 1), new GameSnapshot.Payment(-1, 1, 3_000, 2), new GameSnapshot.Payment(1, 3, 1_000, 3));
        var result = new GameSnapshot.Result(0, List.of(1), List.of(), List.of(), transfers, false);
        var old = opening.rules();
        var rules = new GameSnapshot.RuleState(GameSnapshot.FINISHED, old.dealerSeat(), old.roundWind(), old.honba(), 0,
                old.doraIndicators(), old.seats(), List.of(), List.of(), List.of(), false, false, null, List.of(), result);
        var game = new GameSnapshot(opening.gameId(), opening.revision() + 1, opening.turnSeat(), opening.remainingTiles(),
                opening.seats(), opening.ownHand(), opening.drawnTile(), false, "", rules);
        require(TableScreen.settlementDeltas(game).equals(List.of(-2_000, 4_100, -100, 1_000)),
                "Settlement deltas follow real payer/payee transfers, including deposits, rather than subtracting starting points");
        var riichiSeats=new ArrayList<>(old.seats());
        var first=riichiSeats.getFirst();
        riichiSeats.set(0,new GameSnapshot.SeatRules(first.melds(),first.river(),first.points(),2,first.norths()));
        var pending=riichiSeats.get(2);
        riichiSeats.set(2,new GameSnapshot.SeatRules(pending.melds(),pending.river(),pending.points(),1,pending.norths()));
        var riichiRules=new GameSnapshot.RuleState(GameSnapshot.FINISHED,old.dealerSeat(),old.roundWind(),old.honba(),0,
                old.doraIndicators(),riichiSeats,List.of(),List.of(),List.of(),false,false,null,List.of(),result);
        var withRiichi=new GameSnapshot(game.gameId(),game.revision(),game.turnSeat(),game.remainingTiles(),game.seats(),
                game.ownHand(),game.drawnTile(),false,"",riichiRules);
        require(TableScreen.settlementDeltas(withRiichi).equals(List.of(-3_000,4_100,-100,1_000)),
                "Whole-hand delta includes exactly one established riichi debit, but a robbed pending declaration costs nothing");
    }

    private static LobbySnapshotPayload snapshot(ClientGameTestContext context) { return context.computeOnClient(client -> TableInteractionClient.snapshot()); }
    private static void shot(ClientGameTestContext context,String name) {
        // Test-only offline identity notifications obscure the board, as in the existing screenshot fixtures.
        context.runOnClient(client->client.gui.toastManager().clear());context.waitTick();context.takeScreenshot(name);
    }
    private static void sync(TestServerConnection connection) { connection.waitForServerboundPackets(); connection.waitForClientboundPackets(); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
