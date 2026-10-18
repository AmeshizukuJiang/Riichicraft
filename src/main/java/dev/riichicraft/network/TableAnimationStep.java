package dev.riichicraft.network;

/** Two consecutive PUBLIC states from one accepted server action, not a rules replay journal. */
public record TableAnimationStep(long serial, TableDisplaySnapshot before, TableDisplaySnapshot after) {
    public TableAnimationStep {
        if(serial<1 || before==null || after==null || before.equals(after)
                || before.paused() || after.paused() || before.finished() || after.finished()
                || !before.gameId().equals(after.gameId()) || before.frontSide()!=after.frontSide()
                || !before.seats().stream().map(TableDisplaySnapshot.Seat::side).toList()
                .equals(after.seats().stream().map(TableDisplaySnapshot.Seat::side).toList()))
            throw new IllegalArgumentException("public animation step");
    }
}
