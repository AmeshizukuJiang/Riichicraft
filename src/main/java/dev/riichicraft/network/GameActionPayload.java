package dev.riichicraft.network;

import dev.riichicraft.Riichicraft;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Bounded intentions only; the authenticated connection and current server opportunity decide legality. */
public record GameActionPayload(UUID viewId, UUID gameId, long revision, int action, int tileId,
                                long windowSerial, int meldIndex, List<Integer> handTiles)
        implements CustomPacketPayload {
    public static final int DISCARD=0, RESUME=1, ABORT=2, RIICHI=3, TSUMO=4, NINE_TERMINALS=5,
            PASS=6, RON=7, CHI=8, PON=9, OPEN_KAN=10, CLOSED_KAN=11, ADDED_KAN=12, RETURN_LOBBY=13;
    public static final Type<GameActionPayload> TYPE = new Type<>(Riichicraft.id("game_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, GameActionPayload> CODEC = StreamCodec.of(
            (buf, value) -> {
                buf.writeUUID(value.viewId());
                buf.writeUUID(value.gameId());
                buf.writeLong(value.revision());
                buf.writeVarInt(value.action());
                buf.writeVarInt(value.tileId());
                buf.writeLong(value.windowSerial());
                buf.writeVarInt(value.meldIndex());
                buf.writeVarInt(value.handTiles().size());
                value.handTiles().forEach(buf::writeVarInt);
            }, buf -> {
                UUID view=buf.readUUID(), game=buf.readUUID(); long revision=buf.readLong();
                int action=buf.readVarInt(), tile=buf.readVarInt(); long serial=buf.readLong();
                int index=buf.readVarInt(), count=buf.readVarInt();
                if(count<0 || count>4) throw new DecoderException("Invalid action tile count");
                List<Integer> held=new ArrayList<>(count);
                for(int i=0;i<count;i++) held.add(buf.readVarInt());
                return new GameActionPayload(view,game,revision,action,tile,serial,index,held);
            });

    public GameActionPayload(UUID viewId, UUID gameId, long revision, int action, int tileId) {
        this(viewId,gameId,revision,action,tileId,-1,-1,List.of());
    }

    public GameActionPayload {
        Objects.requireNonNull(viewId, "viewId");
        Objects.requireNonNull(gameId, "gameId");
        handTiles=List.copyOf(handTiles);
        if(revision<0 || action<DISCARD || action>RETURN_LOBBY
                || (action==DISCARD || action==RIICHI ? tileId<0 || tileId>135 : tileId!=-1)
                || (isResponse(action) ? windowSerial<0 : windowSerial!=-1)
                || (action==ADDED_KAN ? meldIndex<0 || meldIndex>3 : meldIndex!=-1)) {
            throw new IllegalArgumentException("Invalid game action metadata");
        }
        validateChoice(action,meldIndex,handTiles);
    }

    public static boolean isResponse(int action) { return action>=PASS && action<=OPEN_KAN; }

    static void validateChoice(int action,int meldIndex,List<Integer> tiles) {
        int count=switch(action) { case CHI,PON->2; case OPEN_KAN->3; case CLOSED_KAN->4; case ADDED_KAN->1; default->0; };
        if(tiles.size()!=count || new HashSet<>(tiles).size()!=count
                || tiles.stream().anyMatch(tile->tile<0 || tile>135)
                || (action==ADDED_KAN ? meldIndex<0 || meldIndex>3 : meldIndex!=-1)) {
            throw new IllegalArgumentException("Invalid physical action selection");
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
