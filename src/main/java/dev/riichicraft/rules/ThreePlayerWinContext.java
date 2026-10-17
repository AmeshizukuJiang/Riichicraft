package dev.riichicraft.rules;

import dev.riichicraft.game.TileCatalog;

import java.util.List;
import java.util.Objects;

/**
 * Three-player scoring facts, not proof of a legal action window or absence of furiten.
 * extractedNorths contains only this winner's already established physical kita tiles.
 * North events use NORMAL in the shared context: kita supplement adds rinshan separately;
 * robbery of north adds no chankan yaku and may retain a previously valid ippatsu.
 * The future round engine must supply only currently effective dora/ura indicators.
 */
public record ThreePlayerWinContext(WinContext context, List<Integer> extractedNorths, NorthEvent northEvent) {
    public enum NorthEvent { NONE, SUPPLEMENT, ROBBERY }

    public ThreePlayerWinContext {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(northEvent, "northEvent");
        extractedNorths = List.copyOf(Objects.requireNonNull(extractedNorths, "extractedNorths"));
        ThreePlayerTiles.typeOf(context.winningTileId());
        if (context.seatWind() == 30 || context.roundWind() == 30) {
            throw new IllegalArgumentException("North is neither a seat nor a round wind in this three-player preset");
        }
        boolean[] seen = new boolean[TileCatalog.TILE_COUNT];
        for (int tile : extractedNorths) {
            if (ThreePlayerTiles.typeOf(tile) != 30 || seen[tile]) {
                throw new IllegalArgumentException("Established kita must be distinct physical north tiles");
            }
            seen[tile] = true;
        }
        for (List<Integer> indicators : List.of(context.doraIndicators(), context.uraIndicators())) {
            for (int tile : indicators) {
                ThreePlayerTiles.typeOf(tile);
                if (seen[tile]) throw new IllegalArgumentException("An extracted north cannot also be an indicator");
            }
        }
        if (!extractedNorths.isEmpty() && context.firstTurn() != WinContext.FirstTurn.NONE) {
            throw new IllegalArgumentException("An established kita interrupts first-turn yakuman");
        }
        if (northEvent != NorthEvent.NONE && context.event() != WinContext.WinEvent.NORMAL) {
            throw new IllegalArgumentException("A north event cannot also be a kan or last-tile event");
        }
        if (northEvent == NorthEvent.SUPPLEMENT && (!context.tsumo() || extractedNorths.isEmpty()
                || context.ippatsu() || context.firstTurn() != WinContext.FirstTurn.NONE)) {
            throw new IllegalArgumentException("Kita supplement needs an established north, tsumo, and interrupted ippatsu");
        }
        if (northEvent == NorthEvent.ROBBERY && (context.tsumo()
                || ThreePlayerTiles.typeOf(context.winningTileId()) != 30)) {
            throw new IllegalArgumentException("Robbing north is ron on a north tile");
        }
    }
}
