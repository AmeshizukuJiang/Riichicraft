package dev.riichicraft.rules;

import dev.riichicraft.game.TileCatalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/** Immutable server-only wall. Never serialize this object or its concealed indicator list to clients. */
public final class RoundWall {
    public enum Source { LIVE, LAST_LIVE, RINSHAN }
    public record Draw(int tileId, Source source, RoundWall wall) { }
    public record Deal(List<HandInput> hands, RoundWall wall) {
        public Deal { hands = List.copyOf(hands); Objects.requireNonNull(wall, "wall"); }
    }

    // Logical order, not a rendered stack/dice layout: live [52,122), then four replacements,
    // then five alternating dora/ura pairs. A future physical table may map these indices visually.
    private final List<Integer> liveTiles;
    private final List<Integer> deadTiles;
    private final int replacementDraws;

    private RoundWall(List<Integer> liveTiles, List<Integer> deadTiles, int replacementDraws) {
        this.liveTiles = List.copyOf(liveTiles);
        this.deadTiles = List.copyOf(deadTiles);
        this.replacementDraws = replacementDraws;
    }

    /** Repeatable Fisher-Yates order; all 136 physical tiles occur exactly once. */
    public static Deal shuffled(long seed) {
        var tiles = new ArrayList<>(TileCatalog.allTiles());
        Random random = new Random(seed);
        for (int remaining = tiles.size(); remaining > 1; remaining--) {
            Collections.swap(tiles, remaining - 1, random.nextInt(remaining));
        }
        return deal(tiles);
    }

    /** Supplied complete permutation, useful for reproducible scenarios. Hands are relative to dealer. */
    public static Deal deal(List<Integer> orderedTiles) {
        List<Integer> tiles = List.copyOf(Objects.requireNonNull(orderedTiles, "orderedTiles"));
        if (tiles.size() != TileCatalog.TILE_COUNT || new HashSet<>(tiles).size() != TileCatalog.TILE_COUNT) {
            throw new IllegalArgumentException("A wall requires all 136 different physical tiles");
        }
        tiles.forEach(TileCatalog::typeOf);
        List<HandInput> hands = new ArrayList<>();
        for (int seat = 0; seat < 4; seat++) {
            List<Integer> held = new ArrayList<>();
            for (int round = 0; round < 13; round++) held.add(tiles.get(round * 4 + seat));
            hands.add(new HandInput(held.stream().sorted().toList(), List.of()));
        }
        return new Deal(hands, new RoundWall(tiles.subList(52, 122), tiles.subList(122, 136), 0));
    }

    public int liveCount() { return liveTiles.size(); }
    public int replacementDraws() { return replacementDraws; }
    public boolean canDrawReplacement() { return replacementDraws < 4 && !liveTiles.isEmpty(); }

    /** Live/dead identities are strictly server diagnostics; even unrevealed dora and all ura are private. */
    public List<Integer> liveTiles() { return liveTiles; }
    public List<Integer> deadTiles() { return deadTiles; }

    public Draw drawLive() {
        if (liveTiles.isEmpty()) throw new IllegalStateException("No live tile remains");
        Source source = liveTiles.size() == 1 ? Source.LAST_LIVE : Source.LIVE;
        return new Draw(liveTiles.getFirst(), source,
                new RoundWall(liveTiles.subList(1, liveTiles.size()), deadTiles, replacementDraws));
    }

    /** Call only after kan survives its robbery window. Move the live tail into the dead wall. */
    public Draw drawReplacement() {
        if (!canDrawReplacement()) throw new IllegalStateException("No replacement draw is available");
        int tile = deadTiles.get(replacementDraws);
        var nextDead = new ArrayList<>(deadTiles);
        nextDead.set(replacementDraws, liveTiles.getLast());
        return new Draw(tile, Source.RINSHAN, new RoundWall(liveTiles.subList(0, liveTiles.size() - 1),
                nextDead, replacementDraws + 1));
    }

    /** The owner controls reveal timing: ankan immediately, open/added kan after the later discard. */
    public List<Integer> doraIndicators(int revealedCount) { return indicators(revealedCount, 4); }
    public List<Integer> uraIndicators(int revealedCount) { return indicators(revealedCount, 5); }

    private List<Integer> indicators(int count, int offset) {
        if (count < 1 || count > 5) {
            throw new IllegalArgumentException("Indicator count must be in 1..5; the owner establishes reveal timing");
        }
        List<Integer> indicators = new ArrayList<>();
        for (int index = 0; index < count; index++) indicators.add(deadTiles.get(offset + index * 2));
        return List.copyOf(indicators);
    }
}
