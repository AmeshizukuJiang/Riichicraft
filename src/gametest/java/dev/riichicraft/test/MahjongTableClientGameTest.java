package dev.riichicraft.test;

import dev.riichicraft.Riichicraft;
import dev.riichicraft.client.LobbyScreen;
import dev.riichicraft.network.LobbyActionPayload;
import dev.riichicraft.network.LobbySnapshotPayload;
import dev.riichicraft.server.LobbyService;
import java.io.IOException;
import java.io.Reader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import org.lwjgl.glfw.GLFW;

/**
 * Exercises actual client input and lobby packets in integrated and TCP server modes.
 * Screenshots document the current interface; they do not establish long-term human acceptance.
 */
@SuppressWarnings("UnstableApiUsage")
public final class MahjongTableClientGameTest implements FabricClientGameTest {
    private static final String DEDICATED_WORLD_NAME = "riichicraft-dedicated-test-world";

    @Override
    public void runTest(ClientGameTestContext context) {
        if (Boolean.getBoolean("riichicraft.test.dedicated")) {
            Properties properties = dedicatedServerProperties(context);
            try (TestDedicatedServerContext server = context.worldBuilder().createServer(properties)) {
                UUID playerId;
                try (TestDedicatedServerConnection connection = server.connect()) {
                    runTableScenario(context, server, connection, true);
                    playerId = server.computeOnServer(s -> connection.getServerPlayer().getUUID());
                }
                server.waitFor(s -> LobbyService.get(s).registry().tableForPlayer(playerId).isEmpty());
                DrawDiscardClientScenario.runReconnect(context, server);
                DrawDiscardClientScenario.runFinishedReconnect(context, server);
                MatchClientScenario.run(context, server);
                ThreePlayerClientScenario.run(context, server);
            }
            PersistenceClientScenario.run(context,properties);
            ThreePlayerPersistenceClientScenario.run(context,properties);
            ThreePlayerPersistenceClientScenario.runFurniture(context,properties);
            return;
        }

        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            runTableScenario(context, world.getServer(), world.getConnection(), false);
        }
    }

    private static Properties dedicatedServerProperties(ClientGameTestContext context) {
        String projectRootProperty = System.getProperty("riichicraft.test.projectRoot");
        if (projectRootProperty == null || projectRootProperty.isBlank()) {
            throw new AssertionError("Dedicated tests require riichicraft.test.projectRoot to guard world cleanup");
        }
        Path projectRoot = Path.of(projectRootProperty).toAbsolutePath().normalize();
        Path runDirectory = Path.of("").toAbsolutePath().normalize();
        Path gameDirectory = context.computeOnClient(client -> client.gameDirectory.toPath().toAbsolutePath().normalize());
        if (!runDirectory.equals(gameDirectory) || !runDirectory.startsWith(projectRoot)
                || runDirectory.equals(projectRoot)) {
            throw new AssertionError("Dedicated tests must run in a Minecraft run directory inside this project");
        }
        Path testWorld = runDirectory.resolve(DEDICATED_WORLD_NAME).normalize();
        if (!testWorld.getParent().equals(runDirectory) || Files.isSymbolicLink(testWorld)) {
            throw new AssertionError("Dedicated test world cleanup must stay in the dedicated run-directory child");
        }

        try {
            Properties eula = new Properties();
            // Native Main reads eula.txt from its working directory; this test never writes it.
            try (Reader reader = Files.newBufferedReader(runDirectory.resolve("eula.txt"))) {
                eula.load(reader);
            }
            if (!Boolean.parseBoolean(eula.getProperty("eula", "false"))) {
                throw new AssertionError("This project's local test server requires the authorized EULA setting");
            }

            Properties properties = new Properties();
            properties.setProperty("server-ip", "127.0.0.1");
            properties.setProperty("level-name", DEDICATED_WORLD_NAME);
            // The framework's one-player default must also accommodate three native server fixtures.
            properties.setProperty("max-players", "8");
            // Choose an available loopback port rather than taking the player's normal server port.
            try (ServerSocket reservation = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
                properties.setProperty("server-port", Integer.toString(reservation.getLocalPort()));
            }
            return properties;
        } catch (IOException exception) {
            throw new AssertionError("Could not prepare the authorized local dedicated-server test", exception);
        }
    }

    private static void runTableScenario(ClientGameTestContext context, TestServerContext server,
            TestServerConnection connection, boolean dedicated) {
        server.runCommand("gamemode creative @a");
        server.runCommand("execute as @a at @s run fill ~-3 ~-1 ~-3 ~3 ~-1 ~3 minecraft:stone");
        server.runCommand("execute as @a at @s run fill ~-3 ~ ~-3 ~3 ~4 ~3 minecraft:air");
        server.runCommand("execute as @a at @s run setblock ~ ~ ~-2 riichicraft:mahjong_table[facing=south]");
        server.runCommand("execute as @a at @s run tp @s ~ ~ ~ 180 30");
        server.runCommand("give @a riichicraft:mahjong_table");

        context.waitTicks(20);
        connection.waitForClientboundPackets();
        connection.waitForChunksRender();
        context.runOnClient(client -> {
            if (client.level == null || client.player == null) {
                throw new AssertionError("The client must have entered the test world");
            }
            if (client.hasSingleplayerServer() == dedicated) {
                throw new AssertionError("The client must be connected to the requested server kind");
            }
            String[] resources = {
                    "blockstates/mahjong_table.json",
                    "items/mahjong_table.json",
                    "models/block/mahjong_table.json",
                    "textures/block/mahjong_table_top.png",
                    "textures/block/mahjong_table_wood.png",
                    "textures/block/mahjong_table_end.png",
                    "lang/en_us.json",
                    "lang/zh_cn.json"
            };
            for (String resource : resources) {
                if (client.getResourceManager().getResource(Riichicraft.id(resource)).isEmpty()) {
                    throw new AssertionError("Missing client resource: " + resource);
                }
            }
            String messageKey = "screen.riichicraft.lobby.title";
            if (Component.translatable(messageKey).getString().equals(messageKey)) {
                throw new AssertionError("The lobby translation must be loaded");
            }
        });

        runLobbyScenario(context, server, connection, dedicated);
    }

    private static void runLobbyScenario(ClientGameTestContext context,
            TestServerContext server, TestServerConnection connection, boolean dedicated) {
        BlockPos target = context.computeOnClient(client -> client.player.blockPosition().offset(0, 0, -2));
        String[][] heldItems = {{}, {"weapon.mainhand", "minecraft:stick"}, {"weapon.offhand", "minecraft:stick"}};
        for (String[] heldItem : heldItems) {
            server.runCommand("clear @a");
            if (heldItem.length != 0) server.runCommand("item replace entity @a " + heldItem[0] + " with " + heldItem[1]);
            context.waitTick();
            connection.waitForClientboundPackets();
            openTable(context, connection, target);
            context.clickScreenButton("screen.riichicraft.lobby.close");
            sync(connection);
            context.waitForScreen(null);
        }

        openTable(context, connection, target);
        context.clickScreenButton("screen.riichicraft.lobby.join");
        sync(connection);
        LobbySnapshotPayload joined = snapshot(context);
        if (joined.ownSeat() != 0) throw new AssertionError("The first player must automatically receive East");
        context.clickScreenButton("screen.riichicraft.lobby.prepare");
        sync(connection);
        if (!snapshot(context).seats().get(0).ready()) throw new AssertionError("Ready must reach the server and return");
        context.clickScreenButton("screen.riichicraft.lobby.cancel_ready");
        sync(connection);
        long revision = snapshot(context).revision();
        context.runOnClient(client -> {
            ClientPlayNetworking.send(new LobbyActionPayload(joined.viewId(), joined.revision(),
                    LobbyActionPayload.SET_READY, true));
            ClientPlayNetworking.send(new LobbyActionPayload(UUID.randomUUID(), revision,
                    LobbyActionPayload.LEAVE, false));
        });
        sync(connection);
        if (snapshot(context).revision() != revision || snapshot(context).seats().get(0).ready()) {
            throw new AssertionError("Stale or forged view requests must not change current state");
        }
        context.clickScreenButton("screen.riichicraft.lobby.close");
        sync(connection);
        openTable(context, connection, target);
        if (snapshot(context).ownSeat() != 0) throw new AssertionError("Closing the screen must retain the seat");
        context.clickScreenButton("screen.riichicraft.lobby.leave");
        sync(connection);
        if (snapshot(context).ownSeat() != -1) throw new AssertionError("Explicit leave must release the seat");
        context.clickScreenButton("screen.riichicraft.lobby.join");
        sync(connection);
        context.clickScreenButton("screen.riichicraft.lobby.prepare");
        sync(connection);

        // Three server fixtures exercise the four-seat/all-ready view without claiming four real clients.
        server.runOnServer(s -> {
            var registry = LobbyService.get(s).registry();
            UUID tableId = registry.tableForPlayer(connection.getServerPlayer().getUUID()).orElseThrow();
            for (String name : new String[] {"TestSouth", "TestWest", "TestNorth"}) {
                UUID fixture = UUID.randomUUID();
                registry.join(tableId, fixture, name, registry.snapshot(tableId).orElseThrow().revision());
                registry.setReady(tableId, fixture, true, registry.snapshot(tableId).orElseThrow().revision());
            }
        });
        sendReady(context, true);
        sync(connection); // The stale view receives a complete refreshed snapshot.
        sendReady(context, true);
        sync(connection); // Desired-state repetition must keep all four seats ready.
        if (!snapshot(context).allReady()) throw new AssertionError("Four ready seats must enable the ready indication");

        var reload = context.computeOnClient(client -> {
            client.getLanguageManager().setSelected("zh_cn");
            return client.reloadResourcePacks();
        });
        context.waitFor(client -> reload.isDone());
        reload.join();
        context.waitFor(client -> client.gui.overlay() == null);
        context.runOnClient(client -> client.gui.toastManager().clear());
        context.waitTick();
        context.takeScreenshot(dedicated ? "riichicraft-dedicated-lobby" : "riichicraft-lobby");

        server.runCommand("execute as @a at @s run setblock ~ ~ ~-2 minecraft:air");
        context.waitForScreen(null);
        server.runOnServer(s -> {
            if (LobbyService.get(s).registry().tableForPlayer(connection.getServerPlayer().getUUID()).isPresent()) {
                throw new AssertionError("Breaking a table must release its lobby seats");
            }
        });
        server.runCommand("execute as @a at @s run setblock ~ ~ ~-2 riichicraft:mahjong_table");
        context.waitTick();
        connection.waitForClientboundPackets();
        openTable(context, connection, target);
        context.clickScreenButton("screen.riichicraft.lobby.join");
        sync(connection);
        DrawDiscardClientScenario.run(context, server, connection, target, dedicated);
    }

    static void openTable(ClientGameTestContext context, TestServerConnection connection, BlockPos target) {
        context.getInput().lookAt(target);
        context.waitTick();
        context.runOnClient(client -> {
            if (!(client.hitResult instanceof BlockHitResult hit) || !hit.getBlockPos().equals(target)) {
                throw new AssertionError("The actual crosshair must hit the mahjong table");
            }
        });
        context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        sync(connection);
        context.waitForScreen(LobbyScreen.class);
    }

    private static LobbySnapshotPayload snapshot(ClientGameTestContext context) {
        return context.computeOnClient(client -> ((LobbyScreen) client.gui.screen()).snapshot());
    }

    private static void sendReady(ClientGameTestContext context, boolean ready) {
        context.runOnClient(client -> {
            LobbySnapshotPayload current = ((LobbyScreen) client.gui.screen()).snapshot();
            ClientPlayNetworking.send(new LobbyActionPayload(current.viewId(), current.revision(),
                    LobbyActionPayload.SET_READY, ready));
        });
    }

    private static void sync(TestServerConnection connection) {
        connection.waitForServerboundPackets();
        connection.waitForClientboundPackets();
    }
}
