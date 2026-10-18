package dev.riichicraft.table;

/** Pure interpolation; time is supplied by the caller, never by the rules or a server clock. */
public final class TileMotion {
    public record Pose(float x, float y, float z, float yaw, float pitch, float scale) { }
    private TileMotion() { }
    public static float progress(long now, long start, long duration) {
        if (duration <= 0) throw new IllegalArgumentException("duration");
        if (now <= start) return 0;
        return (float)Math.min(1, (double)(now-start)/duration);
    }
    public static Pose sample(Pose from, Pose to, float progress, float arc) {
        float p=Math.clamp(progress,0,1), eased=p*p*(3-2*p);
        if(p==0)return from;if(p==1)return to;
        return new Pose(lerp(from.x,to.x,eased),lerp(from.y,to.y,eased)+(float)Math.sin(Math.PI*p)*arc,
                lerp(from.z,to.z,eased),from.yaw+angle(to.yaw-from.yaw)*eased,
                lerp(from.pitch,to.pitch,eased),lerp(from.scale,to.scale,eased));
    }
    private static float angle(float d) { return (d%360+540)%360-180; }
    private static float lerp(float a,float b,float t){return a+(b-a)*t;}
}
