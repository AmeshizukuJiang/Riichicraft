package dev.riichicraft.worldgen;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Operator-only transport for the prototype; no portals, block edits, or player save fields. */
public final class TerrainTestCommands {
    private static final int LANDING_SEARCH_RADIUS = 16;

    // Each inner map belongs to that server's command thread. The outer map also supports
    // independent test servers in one JVM, and a stopped server retains no return points.
    private static final Map<MinecraftServer, Map<UUID, ReturnPoint>> RETURN_POINTS = new ConcurrentHashMap<>();

    private TerrainTestCommands() {}

    public static void initialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                Commands.literal("riichicraft")
                        .then(Commands.literal("terrain")
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .then(Commands.literal("enter").executes(context -> enter(context.getSource())))
                                .then(Commands.literal("leave").executes(context -> leave(context.getSource()))))));
        ServerLifecycleEvents.SERVER_STOPPED.register(RETURN_POINTS::remove);
    }

    private static int enter(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (player.isPassenger()) return fail(source, "riding");
        if (player.level().dimension().equals(TerrainTestDimension.LEVEL)) return fail(source, "already_inside");

        ServerLevel target = source.getServer().getLevel(TerrainTestDimension.LEVEL);
        if (target == null) return fail(source, "missing_dimension");
        Optional<Vec3> landing = findSurface(target, player, BlockPos.ZERO);
        if (landing.isEmpty()) return fail(source, "no_safe_position");

        ReturnPoint origin = new ReturnPoint(player.level().dimension(), player.position(), player.getYRot(), player.getXRot());
        if (!teleport(player, target, landing.get(), player.getYRot(), player.getXRot())) return fail(source, "teleport_failed");
        RETURN_POINTS.computeIfAbsent(source.getServer(), ignored -> new HashMap<>()).put(player.getUUID(), origin);
        source.sendSuccess(() -> Component.translatable("command.riichicraft.terrain.entered"), false);
        return 1;
    }

    private static int leave(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (player.isPassenger()) return fail(source, "riding");
        if (!player.level().dimension().equals(TerrainTestDimension.LEVEL)) return fail(source, "not_inside");

        Map<UUID, ReturnPoint> points = RETURN_POINTS.get(source.getServer());
        ReturnPoint origin = points == null ? null : points.get(player.getUUID());
        ServerLevel target = origin == null ? null : source.getServer().getLevel(origin.dimension());
        float yaw;
        float pitch;
        Optional<Vec3> landing;
        if (target != null) {
            yaw = origin.yaw();
            pitch = origin.pitch();
            // Restore an unobstructed, supported position; only an already flying player
            // may return in midair. A small downward collision check supports slabs/stairs.
            AABB originBody = player.getDimensions(Pose.STANDING).makeBoundingBox(origin.position());
            landing = isClear(target, player, origin.position())
                            && (player.getAbilities().flying || !target.noCollision(player, originBody.move(0, -0.0625, 0)))
                    ? Optional.of(origin.position())
                    : findSurface(target, player, BlockPos.containing(origin.position()));
        } else {
            target = source.getServer().overworld();
            var spawn = target.getRespawnData();
            yaw = spawn.yaw();
            pitch = spawn.pitch();
            landing = findSurface(target, player, spawn.pos());
        }
        if (landing.isEmpty()) return fail(source, "no_safe_position");
        if (!teleport(player, target, landing.get(), yaw, pitch)) return fail(source, "teleport_failed");
        if (points != null) points.remove(player.getUUID());
        source.sendSuccess(() -> Component.translatable("command.riichicraft.terrain.left"), false);
        return 1;
    }

    /** Only explicit operator travel may generate these bounded destination chunks. */
    private static Optional<Vec3> findSurface(ServerLevel level, ServerPlayer player, BlockPos center) {
        for (int radius = 0; radius <= LANDING_SEARCH_RADIUS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    int x = center.getX() + dx;
                    int z = center.getZ() + dz;
                    // Level.getHeight returns minY for an unloaded chunk; explicit travel
                    // must request the destination before reading its highest solid block.
                    int y = level.getChunk(x >> 4, z >> 4).getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15) + 1;
                    BlockPos floor = new BlockPos(x, y - 1, z);
                    if (y <= level.getMinY() || y + 2 > level.getMaxY()) continue;
                    if (!level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) continue;
                    if (!level.getFluidState(floor).isEmpty()) continue;
                    Vec3 candidate = new Vec3(x + 0.5, y, z + 0.5);
                    if (isClear(level, player, candidate)) return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    private static boolean isClear(ServerLevel level, ServerPlayer player, Vec3 position) {
        AABB body = player.getDimensions(Pose.STANDING).makeBoundingBox(position);
        return body.minY >= level.getMinY()
                && body.maxY <= level.getMaxY()
                && level.getWorldBorder().isWithinBounds(body)
                && !level.containsAnyLiquid(body)
                && level.noCollision(player, body);
    }

    private static boolean teleport(ServerPlayer player, ServerLevel target, Vec3 position, float yaw, float pitch) {
        if (!player.teleportTo(target, position.x, position.y, position.z, Set.of(), yaw, pitch, true)) return false;
        player.setDeltaMovement(Vec3.ZERO);
        player.resetFallDistance();
        return true;
    }

    private static int fail(CommandSourceStack source, String reason) {
        source.sendFailure(Component.translatable("command.riichicraft.terrain." + reason));
        return 0;
    }

    private record ReturnPoint(ResourceKey<Level> dimension, Vec3 position, float yaw, float pitch) {}
}
