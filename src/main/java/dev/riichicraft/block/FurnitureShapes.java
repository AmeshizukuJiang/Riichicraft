package dev.riichicraft.block;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

final class FurnitureShapes {
    private FurnitureShapes() { }

    static VoxelShape rotate(VoxelShape north, Direction facing) {
        int turns = switch (facing) { case EAST -> 1; case SOUTH -> 2; case WEST -> 3; default -> 0; };
        VoxelShape result = north;
        for (int turn = 0; turn < turns; turn++) {
            VoxelShape[] next = {Shapes.empty()};
            result.forAllBoxes((x1,y1,z1,x2,y2,z2) -> next[0] = Shapes.or(next[0],
                    Shapes.box(1-z2,y1,x1,1-z1,y2,x2)));
            result = next[0];
        }
        return result.optimize();
    }
}
