package com.hdmapreforged;

import java.util.*;

/** How far a dungeon, cave or room reaches on its floor, to fit the map to the dungeon one enters. */
final class WalkableArea {

    /** {minX, minY, maxX, maxY} of the tiles walkable from a point, or null when none or over {@code limit} tiles. */
    static int[] bounds(CollisionMap map, int x, int y, int z, int limit) {
        int start = map.walkable(x, y, z) ? Tiles.pack(x, y, z) : map.nearestWalkable(x, y, z, 4);
        if (start < 0) {
            return null;
        }
        BitSet seen = new BitSet();
        // Dungeons never span more than this.
        int size = 1024, sx = Tiles.x(start), sy = Tiles.y(start), ox = sx - size / 2, oy = sy - size / 2;
        var open = new ArrayDeque<int[]>();
        open.add(new int[]{sx, sy});
        seen.set((sx - ox) * size + (sy - oy));
        int[] b = {sx, sy, sx, sy};
        int count = 0;
        while (!open.isEmpty()) {
            var p = open.poll();
            if (++count > limit) {
                return null;
            }
            b[0] = Math.min(b[0], p[0]);
            b[1] = Math.min(b[1], p[1]);
            b[2] = Math.max(b[2], p[0]);
            b[3] = Math.max(b[3], p[1]);
            for (int d = 0; d < 8; d++) {
                int nx = p[0] + Pathfinder.DX[d], ny = p[1] + Pathfinder.DY[d], wx = nx - ox, wy = ny - oy;
                if (wx < 0 || wy < 0 || wx >= size || wy >= size || seen.get(wx * size + wy)
                    || !map.canStep(p[0], p[1], z, Pathfinder.DX[d], Pathfinder.DY[d])) {
                    continue;
                }
                seen.set(wx * size + wy);
                open.add(new int[]{nx, ny});
            }
        }
        return b;
    }
}
