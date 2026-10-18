package dev.riichicraft.entity;

import dev.riichicraft.block.MahjongChairBlock;
import dev.riichicraft.block.ModBlocks;
import dev.riichicraft.block.TableStructure;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/** A transient mounting point, never the authority for membership or saved game state. */
public final class MahjongSeatEntity extends Entity {
    private BlockPos chair;
    private UUID tableId;
    private Direction facing;

    public MahjongSeatEntity(EntityType<? extends MahjongSeatEntity> type, Level level) {
        super(type, level);
        noPhysics = true;
        setNoGravity(true);
        setInvisible(true);
    }

    public void bind(UUID tableId, BlockPos chair, Direction facing) {
        this.tableId = tableId;
        this.chair = chair.immutable();
        this.facing = facing;
        setPos(chair.getX() + 0.5, chair.getY() + 0.5, chair.getZ() + 0.5);
        setYRot(facing.toYRot());
    }

    public UUID tableId() { return tableId; }
    public BlockPos chair() { return chair; }
    public Direction facing() { return facing; }

    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) { }
    @Override protected void readAdditionalSaveData(ValueInput input) { }
    @Override protected void addAdditionalSaveData(ValueOutput output) { }
    @Override public boolean hurtServer(ServerLevel level, DamageSource source, float amount) { return false; }
    @Override public boolean isPickable() { return false; }
    @Override public boolean isPushable() { return false; }
    @Override public boolean isPushedByFluid() { return false; }
    @Override public boolean shouldBeSaved() { return false; }
    @Override protected boolean canAddPassenger(Entity passenger) { return getPassengers().isEmpty(); }

    @Override public void tick() {
        super.tick();
        if (level() instanceof ServerLevel serverLevel) {
            var state = chair == null ? null : TableStructure.loadedState(serverLevel, chair);
            if (getPassengers().isEmpty() || state == null || !state.is(ModBlocks.MAHJONG_CHAIR)
                    || state.getValue(MahjongChairBlock.FACING) != facing) discard();
        }
    }

    @Override protected void positionRider(Entity passenger, MoveFunction move) {
        // Respect the player's native vehicle attachment (hips), rather than placing feet on the cushion.
        super.positionRider(passenger,move);
    }
    @Override public Vec3 getPassengerRidingPosition(Entity passenger) { return position(); }

    @Override public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        // Outside the table, at the chair's edge. Never force-load a neighbor for dismounting.
        if (facing != null && chair != null) {
            Vec3 candidate = Vec3.atBottomCenterOf(chair).relative(facing.getOpposite(), 1.2);
            if (level().noCollision(passenger, passenger.getBoundingBox().move(candidate.subtract(passenger.position()))))
                return candidate;
        }
        return super.getDismountLocationForPassenger(passenger);
    }
}
