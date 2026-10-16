package dev.riichicraft.test;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;

/**
 * A native server player with an in-memory connection, following 26.2's GameTestHelper setup.
 * This is a fixture, not a TCP connection or a running Minecraft client. Construct and close on the server thread.
 */
public final class ConnectedTestPlayer implements AutoCloseable {
    private final MinecraftServer server;
    private final ServerPlayer player;
    private final EmbeddedChannel channel;
    private boolean closed;

    public ConnectedTestPlayer(MinecraftServer server, ServerLevel level, BlockPos tablePos, String name) {
        this.server = Objects.requireNonNull(server, "server");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(tablePos, "tablePos");
        Objects.requireNonNull(name, "name");
        requireServerThread();
        if (level.getServer() != server || name.isBlank()) {
            throw new IllegalArgumentException("The fixture needs its server's level and a non-blank player name");
        }

        GameProfile profile = new GameProfile(UUID.randomUUID(), name);
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
        player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        channel = new EmbeddedChannel(connection);
        try {
            server.getPlayerList().placeNewPlayer(connection, player, cookie);
            player.setGameMode(GameType.CREATIVE);
            player.setNoGravity(true);
            // Stay beside the table, leaving the real client's south-side interaction ray unobstructed.
            player.setPos(tablePos.getX() + 2.5, tablePos.getY(), tablePos.getZ() + 0.5);
            if (server.getPlayerList().getPlayer(player.getUUID()) != player || player.connection == null
                    || !player.isAlive() || player.isSpectator()
                    || !player.isWithinBlockInteractionRange(tablePos, 1.0)) {
                throw new IllegalStateException("The native test player was not connected near its table");
            }
        } catch (RuntimeException | Error failure) {
            try {
                close();
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    public ServerPlayer player() {
        return player;
    }

    /** The scenario must explicitly perform its mod's disconnect cleanup before closing this fixture. */
    @Override
    public void close() {
        requireServerThread();
        if (closed) return;
        closed = true;
        try {
            if (server.getPlayerList().getPlayer(player.getUUID()) == player) {
                server.getPlayerList().remove(player);
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private void requireServerThread() {
        if (Thread.currentThread() != server.getRunningThread()) {
            throw new IllegalStateException("ConnectedTestPlayer must be used on the server thread");
        }
    }
}
