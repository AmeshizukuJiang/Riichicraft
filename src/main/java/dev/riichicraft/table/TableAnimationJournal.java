package dev.riichicraft.table;

import dev.riichicraft.network.TableAnimationStep;
import dev.riichicraft.network.TableDisplaySnapshot;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;

/** Bounded transient PUBLIC history. Never written to the game archive. */
public final class TableAnimationJournal {
    public static final int LIMIT=16;
    private UUID epoch;
    private long serial;
    private TableDisplaySnapshot current;
    private final ArrayDeque<TableAnimationStep> steps=new ArrayDeque<>();
    public TableAnimationJournal(TableDisplaySnapshot state){reset(state);}
    public UUID epoch(){return epoch;}
    public long serial(){return serial;}
    public void synchronize(TableDisplaySnapshot state){if(!state.equals(current))reset(state);}
    public void record(TableDisplaySnapshot before,TableDisplaySnapshot after){
        synchronize(before);
        if(before.equals(after))return;
        try {
            var step=new TableAnimationStep(serial+1,before,after);
            serial++;current=after;steps.addLast(step);if(steps.size()>LIMIT)steps.removeFirst();
        } catch(IllegalArgumentException lifecycle){reset(after);}
    }
    public List<TableAnimationStep> since(UUID knownEpoch,long cursor){
        if(!epoch.equals(knownEpoch) || cursor<0 || cursor>=serial || steps.isEmpty()
                || cursor<steps.getFirst().serial()-1)return List.of();
        return steps.stream().filter(s->s.serial()>cursor).toList();
    }
    private void reset(TableDisplaySnapshot state){epoch=UUID.randomUUID();serial=0;current=state;steps.clear();}
}
