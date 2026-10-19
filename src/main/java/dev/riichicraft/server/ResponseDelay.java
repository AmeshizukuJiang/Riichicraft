package dev.riichicraft.server;

/** Cosmetic response cover: 25% selection; decreasing 1/t weights on native 50ms ticks. */
final class ResponseDelay {
    private ResponseDelay() { }
    static int sample(double chance, double duration) {
        if(chance<0 || chance>=1 || duration<0 || duration>=1) throw new IllegalArgumentException("random bounds");
        if(chance>=.25) return 0;
        double total=0;for(int tick=4;tick<=40;tick++) total+=1D/tick;
        double threshold=duration*total;
        for(int tick=4;tick<=40;tick++) { threshold-=1D/tick;if(threshold<0) return tick; }
        return 40;
    }
}
