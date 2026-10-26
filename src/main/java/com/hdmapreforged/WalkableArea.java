package com.hdmapreforged;

import static java.lang.Math.*;

/** How far a dungeon, cave or room reaches on its floor, to fit the map to the dungeon one enters. */
final class WalkableArea {

    /** {minX, minY, maxX, maxY} of the tiles walkable from a point, or null when none or over {@code limit} tiles. */
    static int[] bounds(CollisionMap map, int x, int y, int z, int limit) {
        int start = map.walkable(x, y, z) ? Tiles.pack(x, y, z) : map.walkableNear(x, y, z, 4);
        int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, 0, 0};
        return start < 0 || Pathfinder.reaches(map, start, at -> {
            b[0] = min(b[0], Tiles.x(at));
            b[1] = min(b[1], Tiles.y(at));
            b[2] = max(b[2], Tiles.x(at));
            b[3] = max(b[3], Tiles.y(at));
            return false;
        }, limit + 1) ? null : b;
    }
}
