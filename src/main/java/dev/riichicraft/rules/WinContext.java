package dev.riichicraft.rules;

import dev.riichicraft.game.TileCatalog;

import java.util.List;
import java.util.Objects;

/**
 * Facts supplied by a future authoritative round engine. This value checks their syntax, not
 * the history that established riichi, first turn, last tile, furiten or a legal kan/ron window.
 * Empty indicator lists permit isolated hand-value analysis; a real round supplies its indicators.
 */
public record WinContext(WinMethod method, int winningTileId, int seatWind, int roundWind,
                         Riichi riichi, boolean ippatsu, WinEvent event, FirstTurn firstTurn,
                         List<Integer> doraIndicators, List<Integer> uraIndicators) {
    public enum WinMethod { RON, TSUMO }
    public enum Riichi { NONE, RIICHI, DOUBLE_RIICHI }
    public enum WinEvent { NORMAL, RINSHAN, CHANKAN, HAITEI, HOUTEI }
    public enum FirstTurn { NONE, TENHOU, CHIIHOU }

    public WinContext {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(riichi, "riichi");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(firstTurn, "firstTurn");
        TileCatalog.typeOf(winningTileId);
        if (seatWind < 27 || seatWind > 30 || roundWind < 27 || roundWind > 30) {
            throw new IllegalArgumentException("Seat and round winds must be wind tile types 27..30");
        }
        doraIndicators = List.copyOf(Objects.requireNonNull(doraIndicators, "doraIndicators"));
        uraIndicators = List.copyOf(Objects.requireNonNull(uraIndicators, "uraIndicators"));
        if (doraIndicators.size() > 5 || uraIndicators.size() > 5) {
            throw new IllegalArgumentException("At most five indicators of each kind can be exposed");
        }
        if (!uraIndicators.isEmpty() && uraIndicators.size() != doraIndicators.size()) {
            throw new IllegalArgumentException("Provided ura indicators must match the dora indicator count");
        }
        boolean[] seen = new boolean[TileCatalog.TILE_COUNT];
        for (List<Integer> indicators : List.of(doraIndicators, uraIndicators)) {
            for (int tile : indicators) {
                TileCatalog.typeOf(tile);
                if (seen[tile]) throw new IllegalArgumentException("An indicator physical tile occurs twice");
                seen[tile] = true;
            }
        }
        if (ippatsu && riichi == Riichi.NONE) {
            throw new IllegalArgumentException("Ippatsu requires a riichi declaration");
        }
        if ((event == WinEvent.RINSHAN || event == WinEvent.HAITEI) && method != WinMethod.TSUMO
                || (event == WinEvent.CHANKAN || event == WinEvent.HOUTEI) && method != WinMethod.RON) {
            throw new IllegalArgumentException("Winning event does not match the winning method");
        }
        if (event == WinEvent.RINSHAN && ippatsu) {
            throw new IllegalArgumentException("The completed kan interrupts ippatsu");
        }
        if (firstTurn != FirstTurn.NONE && (method != WinMethod.TSUMO || event != WinEvent.NORMAL
                || riichi != Riichi.NONE || ippatsu
                || firstTurn == FirstTurn.TENHOU && seatWind != 27
                || firstTurn == FirstTurn.CHIIHOU && seatWind == 27)) {
            throw new IllegalArgumentException("Invalid first-turn winning context");
        }
    }

    public boolean dealer() { return seatWind == 27; }
    public boolean tsumo() { return method == WinMethod.TSUMO; }
}
