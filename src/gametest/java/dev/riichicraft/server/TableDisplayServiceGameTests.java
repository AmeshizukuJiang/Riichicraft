package dev.riichicraft.server;

import dev.riichicraft.network.*;
import dev.riichicraft.test.ConnectedTestPlayer;
import dev.riichicraft.test.FurnitureTestLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;

public final class TableDisplayServiceGameTests {
    @GameTest
    public void threePlayerPublicTilesRebuildFromSavedGameDespiteMissingOrdinaryChair(GameTestHelper helper) {
        var level = helper.getLevel(); var server = level.getServer(); var center = helper.absolutePos(new BlockPos(3,2,3));
        var archive = server.getWorldPath(LevelResource.ROOT).resolve("data/riichicraft-tests/display-"+UUID.randomUUID()+"/tables.dat");
        LobbyService service = new LobbyService(server,archive); var fixtures = new ArrayList<ConnectedTestPlayer>();
        try {
            FurnitureTestLayout.body(level,center,Direction.NORTH); FurnitureTestLayout.chairs(level,center,3);
            UUID id = null;
            for(int seat=0;seat<3;seat++) {
                var fixture = new ConnectedTestPlayer(server,level,center,"PublicSave"+seat);fixtures.add(fixture);
                UUID view = service.open(fixture.player(),center);
                service.handleAction(fixture.player(),new LobbyActionPayload(view,id==null?1:service.registry().snapshot(id).orElseThrow().revision(),LobbyActionPayload.JOIN,false));
                id=service.registry().tableForPlayer(fixture.player().getUUID()).orElseThrow();
                service.handleAction(fixture.player(),new LobbyActionPayload(view,service.registry().snapshot(id).orElseThrow().revision(),LobbyActionPayload.SET_READY,true));
                service.handleAction(fixture.player(),new LobbyActionPayload(view,0,LobbyActionPayload.CLOSE,false));
            }
            var first=fixtures.getFirst().player(); var view=service.open(first,center);
            service.handleAction(first,new LobbyActionPayload(view,service.registry().snapshot(id).orElseThrow().revision(),LobbyActionPayload.START,false));
            service.handleAction(first,new LobbyActionPayload(view,0,LobbyActionPayload.CLOSE,false));
            var before=service.tableDisplay(id);
            helper.assertTrue(before!=null && before.seats().stream().map(TableDisplaySnapshot.Seat::side).toList().equals(List.of(0,1,3)),
                    "Three-player public sides skip the physical north empty chair");
            helper.assertTrue(before.liveTiles()==54 && before.replacementDraws()==0,
                    "A genuine sanma opening projects 54 live plus fourteen reserve tiles after dealing");
            level.setBlockAndUpdate(center.south(2),Blocks.AIR.defaultBlockState());service.tick();
            helper.assertTrue(service.tableDisplay(id).paused(),"No open GUI is needed to preserve and pause the public table");
            service.stop(); service=new LobbyService(server,archive);
            var restored=service.tableDisplay(id);
            helper.assertTrue(restored!=null && restored.paused() && restored.gameId().equals(before.gameId())
                    && restored.seats().equals(before.seats()) && restored.indicators().equals(before.indicators())
                    && restored.liveTiles()==before.liveTiles() && restored.replacementDraws()==before.replacementDraws(),
                    "A fresh service reconstructs counts, positions and public faces from the actual saved core even with an ordinary chair missing");
            FurnitureTestLayout.chairs(level,center,3);service.tick();
            helper.assertTrue(service.tableDisplay(id).paused(),"Repair does not bypass the existing unanimous recovery requirement");
            view=service.open(first,center);var own=service.gameView(id,first.getUUID());
            service.handleGameAction(first,new GameActionPayload(view,own.gameId(),own.revision(),GameActionPayload.ABORT,-1));
            helper.assertTrue(service.tableDisplay(id)==null,"Explicit termination removes the public source along with its core");
            helper.succeed();
        } finally {
            for(var fixture:fixtures) {service.disconnect(fixture.player());fixture.close();}
            service.stop();
        }
    }
}
