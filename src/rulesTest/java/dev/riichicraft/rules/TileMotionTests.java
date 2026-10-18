package dev.riichicraft.rules;
import dev.riichicraft.table.TileMotion;

public final class TileMotionTests {
    public static void main(String[] args){
        int checks=0;var a=new TileMotion.Pose(-3,1,8,350,90,0.7F);var b=new TileMotion.Pose(9,2,-4,10,-90,0.85F);
        for(int fps:new int[]{30,60,144}) {
            float last=-1;
            for(int frame=0;frame<=fps;frame++) {
                float p=TileMotion.progress(frame*1_000_000_000L/fps,0,1_000_000_000L);
                if(p<last || p<0 || p>1)throw new AssertionError("Clock progress must be monotonic and bounded");checks++;last=p;
                var pose=TileMotion.sample(a,b,p,3.5F);
                if(!Float.isFinite(pose.x()) || pose.y()<Math.min(a.y(),b.y()) || pose.scale()<0.7F || pose.scale()>0.85F)
                    throw new AssertionError("Finite low-arc interpolation");checks++;
            }
            if(!TileMotion.sample(a,b,last,3.5F).equals(b))throw new AssertionError("Frame-rate independent exact endpoint");checks++;
        }
        if(!TileMotion.sample(a,b,0,3.5F).equals(a) || TileMotion.progress(-1,0,1)!=0 || TileMotion.progress(100,0,1)!=1)
            throw new AssertionError("Clock boundaries");checks++;
        if(Math.abs(TileMotion.sample(a,b,0.5F,0).yaw()-360)>0.001)throw new AssertionError("Shortest yaw rotation");checks++;
        System.out.println("PASS tile motion: "+checks+" assertions");
    }
}
