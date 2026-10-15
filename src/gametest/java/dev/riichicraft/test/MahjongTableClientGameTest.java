package dev.riichicraft.test;

import dev.riichicraft.Riichicraft;
import java.io.IOException;
import java.io.Reader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.phys.BlockHitResult;
import org.lwjgl.glfw.GLFW;

/**
 * Opens a real client world and records the table for visual review. This is a loading and
 * resource smoke test, not an assertion that the appearance has passed human acceptance.
 */
@SuppressWarnings("UnstableApiUsage")
public final class MahjongTableClientGameTest implements FabricClientGameTest {
    private static final String DEDICATED_WORLD_NAME = "riichicraft-dedicated-test-world";

    @Override
    public void runTest(ClientGameTestContext context) {
        if (Boolean.getBoolean("riichicraft.test.dedicated")) {
            Properties properties = dedicatedServerProperties(context);
            try (TestDedicatedServerContext server = context.worldBuilder().createServer(properties);
                    TestDedicatedServerConnection connection = server.connect()) {
                runTableScenario(context, server, connection, true);
            }
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
            String messageKey = "message.riichicraft.table.prototype";
            if (Component.translatable(messageKey).getString().equals(messageKey)) {
                throw new AssertionError("The prototype feedback translation must be loaded");
            }
        });

        assertOneFeedbackPerClick(context, server, connection);
        context.takeScreenshot(dedicated ? "riichicraft-dedicated-table" : "riichicraft-basic-table");
    }

    private static void assertOneFeedbackPerClick(ClientGameTestContext context,
            TestServerContext server, TestServerConnection connection) {
        AtomicBoolean recording = new AtomicBoolean(true);
        AtomicInteger feedbackCount = new AtomicInteger();
        context.runOnClient(client -> ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (recording.get() && overlay
                    && message.getContents() instanceof TranslatableContents translation
                    && translation.getKey().equals("message.riichicraft.table.prototype")) {
                feedbackCount.incrementAndGet();
            }
        }));

        try {
            BlockPos target = context.computeOnClient(client -> client.player.blockPosition().offset(0, 0, -2));
            context.getInput().lookAt(target);
            context.waitTick();
            context.runOnClient(client -> {
                if (!(client.hitResult instanceof BlockHitResult hit) || !hit.getBlockPos().equals(target)) {
                    throw new AssertionError("The real client crosshair must be aimed at the table");
                }
            });

            String[][] heldItems = {
                    {},
                    {"weapon.mainhand", "minecraft:stick"},
                    {"weapon.offhand", "minecraft:stick"}
            };
            for (String[] heldItem : heldItems) {
                server.runCommand("clear @a");
                if (heldItem.length != 0) {
                    server.runCommand("item replace entity @a " + heldItem[0]
                            + " with " + heldItem[1]);
                }
                context.waitTick();
                connection.waitForClientboundPackets();
                feedbackCount.set(0);

                // Exercise actual input, client prediction, packets and server-side feedback.
                context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
                connection.waitForServerboundPackets();
                connection.waitForClientboundPackets();
                if (feedbackCount.get() != 1) {
                    throw new AssertionError("One physical right click must yield one prototype feedback; got "
                            + feedbackCount.get() + " for hand setup " + String.join(" ", heldItem));
                }
            }
        } finally {
            // Fabric events have no unregister operation; leave an inert listener after this test.
            recording.set(false);
        }
    }
}
