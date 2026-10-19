package dev.riichicraft.client;

import dev.riichicraft.network.TableAnimationStep;
import dev.riichicraft.network.TableDisplayPayload;
import dev.riichicraft.network.TableDisplaySnapshot;
import dev.riichicraft.network.TableScenePayload;
import dev.riichicraft.table.TableAnimationPlayer;
import dev.riichicraft.table.TableTileLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.lwjgl.glfw.GLFW;

/** Temporary audit-only reproduction, never a production or release fixture. */
public final class AuditClientChecks {
    private AuditClientChecks() { }

    /**
     * Call in ImmersiveClientScenario.run after its existing line-125 assertion
     * ("Reopening controls preserves the normal chair view") and before line 126.
     * At that point a real TCP player is mounted, a round is running, TableScreen
     * is open, and the camera is in normal chair perspective.
     *
     * Current implementation should fail the final assertion: with no TableScreen,
     * vanilla Esc opens PauseScreen without invoking TableScreen.onClose.
     */
    public static void verifyEscapeFromHiddenOverhead(ClientGameTestContext context) {
        context.waitForScreen(TableScreen.class);
        require(context.computeOnClient(c -> c.player.getVehicle() != null),
                "Audit precondition: actual seated client");
        context.getInput().pressKey(GLFW.GLFW_KEY_R);
        context.waitForScreen(null);
        context.waitTicks(2);
        require(context.computeOnClient(c -> !TableCamera.overhead()),
                "Audit precondition: R hides controls and exits overhead");
        context.getInput().pressKey(GLFW.GLFW_KEY_V);
        context.waitTicks(2);
        require(context.computeOnClient(c -> TableCamera.overhead() && c.gui.screen() == null),
                "Audit precondition: V enters overhead with controls hidden");
        try {
            context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
            context.waitTicks(2);
            require(context.computeOnClient(c -> !TableCamera.overhead()),
                    "AUDIT: Esc must exit overhead even when TableScreen is hidden");
        } finally {
            // Leave the scenario at its original mounted, normal-view TableScreen.
            context.runOnClient(c -> {
                c.gui.setScreen(null);
                TableCamera.setOverhead(false);
            });
            context.getInput().pressKey(GLFW.GLFW_KEY_R);
            context.waitForScreen(TableScreen.class);
        }
    }

    /**
     * Pure client frame comparison; call on the client thread independently of the
     * live seated check above. A player discards physical tile 0 from the left end
     * of a fourteen-tile private hand while another player considers a response.
     * Current code starts that discard at anonymous h:0:13, the rightmost slot.
     */
    public static void verifySelectedDiscardOrigin() {
        UUID game=UUID.randomUUID(),table=UUID.randomUUID(),view=UUID.randomUUID(),epoch=UUID.randomUUID();
        var seats=IntStream.range(0,4).mapToObj(side->new TableDisplaySnapshot.Seat(side,
                side==0?14:13,List.of(),List.of(),List.of())).toList();
        var before=new TableDisplaySnapshot(game,false,false,0,69,List.of(132),seats);
        var next=new ArrayList<>(seats);
        next.set(0,new TableDisplaySnapshot.Seat(0,13,List.of(new TableDisplaySnapshot.Discard(0,false)),List.of(),List.of()));
        var after=new TableDisplaySnapshot(game,false,false,0,69,List.of(132),next);
        var initial=new TableDisplayPayload(Level.OVERWORLD,table,BlockPos.ZERO,1,before,epoch,0,List.of());
        var update=new TableDisplayPayload(Level.OVERWORLD,table,BlockPos.ZERO,2,after,epoch,1,
                List.of(new TableAnimationStep(1,before,after)));
        var beforeScene=new TableScenePayload(view,table,Level.OVERWORLD,BlockPos.ZERO,game,1,0,
                List.of(0,1,2,3),true,List.of(IntStream.range(0,14).boxed().toList(),List.of(),List.of(),List.of()),
                List.of(13,-1,-1,-1),false,before.hashCode());
        var afterScene=new TableScenePayload(view,table,Level.OVERWORLD,BlockPos.ZERO,game,2,0,
                List.of(0,1,2,3),true,List.of(IntStream.range(1,14).boxed().toList(),List.of(),List.of(),List.of()),
                List.of(-1,-1,-1,-1),false,after.hashCode());
        var oldVisible=TableDisplayRenderer.visibleFrames(initial,TableAnimationPlayer.still(before),beforeScene,false);
        var oldTile=oldVisible.stream().filter(f->f.tile().group()==TableTileLayout.Group.HAND && f.tile().face()==0)
                .findFirst().orElseThrow();
        var player=new TableAnimationPlayer();
        player.accept(initial,0);player.accept(update,0);
        var current=TableDisplayRenderer.visibleFrames(update,player.frames(table,0),afterScene,false);
        var flying=current.stream().filter(f->f.tile().group()==TableTileLayout.Group.RIVER && f.tile().face()==0)
                .findFirst().orElseThrow();
        require(Math.abs(oldTile.pose().x()-flying.pose().x())<.001F
                        && Math.abs(oldTile.pose().z()-flying.pose().z())<.001F,
                "AUDIT: selected discard must begin at its actual previous private-hand position; old="
                        +oldTile.pose()+", flying="+flying.pose());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
