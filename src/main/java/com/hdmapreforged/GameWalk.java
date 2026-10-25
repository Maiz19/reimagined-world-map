package com.hdmapreforged;

import static java.lang.Math.*;
import static com.hdmapreforged.Tiles.*;

import java.util.*;

/**
 * Walking the way the game walks: a breadth-first search over 128×128 tiles around the player with directions in a
 * fixed order, so the drawn route lies on the tiles the player really walks.
 */
final class GameWalk {
    static final int HALF = 64;
    /** Well inside the search area. */
    static final int PIECE = 48;
    static final int[] DX = {-1, 1, 0, 0, -1, 1, -1, 1};
    static final int[] DY = {0, 0, -1, 1, -1, -1, 1, 1};

    /**
     * A step the walk may not take although the ground allows it: a gate the planner keeps shut (a guild's door below
     * its level), the Wilderness a route keeps out of. Null where none is.
     */
    interface Shut {
        boolean shut(int x, int y, int z, int dx, int dy);
    }

    static boolean open(CollisionMap map, Shut shut, int x, int y, int z, int dx, int dy) {
        return map.canStep(x, y, z, dx, dy) && (shut == null || !shut.shut(x, y, z, dx, dy));
    }

    /** A route's walk as the game would take it; pieces the game cannot do as short keep the route's tiles. */
    static int[] follow(CollisionMap map, Shut shut, int[] points) {
        if (points.length < 3 || isSea(points[0])) {
            return points;
        }
        int[] out = new int[points.length * 2];
        int n = 0;
        out[n++] = points[0];
        int i = 0;
        while (i < points.length - 1) {
            int j = i + 1;
            while (j + 1 < points.length && z(points[j + 1]) == z(points[i])
                && distance(points[i], points[j + 1]) <= PIECE) {
                j++;
            }
            var piece = z(points[j]) == z(points[i]) ? path(map, shut, out[n - 1], new int[]{points[j]}) : null;
            if (piece == null || piece.length > j - i + 1 || newObstacle(map, piece, points, i, j)) {
                piece = Arrays.copyOfRange(points, i, j + 1);
            }
            if (n + piece.length > out.length) {
                out = Arrays.copyOf(out, (n + piece.length) * 2);
            }
            System.arraycopy(piece, 1, out, n, piece.length - 1);
            n += piece.length - 1;
            i = j;
        }
        return Arrays.copyOf(out, n);
    }

    /** How far ahead one run pair (straight, then diagonal) is looked for. */
    static final int RUN = 64;

    /**
     * The walk in as few runs as the ground allows, as a player walks it: from each tile to the farthest one ahead that
     * one straight run and then one diagonal run reach in as many steps, so no time changes. That is the game's walk to
     * a clicked tile in the open: its search tries west, east, south, north before the diagonals, so the path goes
     * straight along the longer axis first, then diagonally; never diagonally first. Not through a door or onto an
     * obstacle the walk kept clear of.
     */
    static int[] straighten(CollisionMap map, Shut shut, int[] points) {
        if (points.length < 3 || isSea(points[0])) {
            return points;
        }
        int[] out = new int[points.length];
        int n = 0;
        out[n++] = points[0];
        int i = 0;
        while (i < points.length - 1) {
            int[] best = null;
            int to = i + 1;
            for (int j = min(points.length - 1, i + RUN); j > i + 1 && best == null; j--) {
                best = runs(map, shut, points, i, j);
                to = j;
            }
            if (best == null) {
                out[n++] = points[i + 1];
                i++;
                continue;
            }
            System.arraycopy(best, 1, out, n, best.length - 1);
            n += best.length - 1;
            i = to;
        }
        return Arrays.copyOf(out, n);
    }

    /**
     * From {@code points[i]} to {@code points[j]} by one straight run and then one diagonal run, in exactly
     * {@code j - i} steps over walkable ground, crossing no door and stepping on no obstacle that {@code points[i..j]}
     * does not; else null.
     */
    static int[] runs(CollisionMap map, Shut shut, int[] points, int i, int j) {
        int from = points[i], to = points[j], z = z(from), dx = x(to) - x(from), dy = y(to) - y(from);
        int steps = max(abs(dx), abs(dy));
        if (z(to) != z || steps != j - i) {
            return null;
        }
        int diagonal = min(abs(dx), abs(dy)), sx = Integer.signum(dx), sy = Integer.signum(dy);
        // The straight run goes along the longer axis.
        int ax = abs(dx) > abs(dy) ? sx : 0, ay = abs(dx) > abs(dy) ? 0 : sy;
        int[] path = new int[steps + 1];
        path[0] = from;
        int x = x(from), y = y(from);
        for (int k = 0; k < steps; k++) {
            boolean diagonalStep = k >= steps - diagonal;
            int mx = diagonalStep ? sx : ax, my = diagonalStep ? sy : ay;
            if (!open(map, shut, x, y, z, mx, my) || (mx == 0 || my == 0) && map.door(x, y, z, mx, my)
                && !crossed(points, i, j, x, y, mx, my)) {
                return null;
            }
            x += mx;
            y += my;
            path[k + 1] = pack(x, y, z);
            if (newObstacle(map, new int[]{path[k + 1]}, points, i, j)) {
                return null;
            }
        }
        return path;
    }

    /** Whether {@code points[i..j]} steps from (x, y) by (mx, my) itself (so a door it opens may be crossed). */
    static boolean crossed(int[] points, int i, int j, int x, int y, int mx, int my) {
        for (int k = i; k < j; k++) {
            if (x(points[k]) == x && y(points[k]) == y && x(points[k + 1]) == x + mx
                && y(points[k + 1]) == y + my) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code piece} crosses an obstacle (vines, rocks) that {@code points[i..j]} kept clear of. */
    static boolean newObstacle(CollisionMap map, int[] piece, int[] points, int i, int j) {
        for (int t : piece) {
            if (map.isObstacle(x(t), y(t), t)
                && Arrays.stream(points, i, j + 1).noneMatch(p -> p == t)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The game's walk to the first of {@code goals} its search reaches (of a bank chest's sides, the one the game walks
     * to when it is clicked), both ends included; null when none is reached.
     */
    static int[] path(CollisionMap map, Shut shut, int from, int[] goals) {
        int z = z(from), ox = x(from) - HALF, oy = y(from) - HALF, size = HALF * 2;
        boolean[] wanted = new boolean[size * size];
        for (int to : goals) {
            int tx = x(to) - ox, ty = y(to) - oy;
            if (z(to) == z && tx >= 0 && ty >= 0 && tx < size && ty < size) {
                wanted[ty * size + tx] = true;
            }
        }
        // Direction each tile was reached from; -1 not reached.
        byte[] via = new byte[size * size];
        Arrays.fill(via, (byte) -1);
        int start = HALF * size + HALF;
        via[start] = 8;
        // Each tile is queued once: no boxing, as the walk is searched many times a route.
        int[] queue = new int[size * size];
        int head = 0, tail = 0;
        queue[tail++] = start;
        int goal = wanted[start] ? start : -1;
        while (head < tail && goal < 0) {
            int cell = queue[head++], cx = cell % size, cy = cell / size;
            for (int d = 0; d < 8; d++) {
                int nx = cx + DX[d], ny = cy + DY[d];
                if (nx < 0 || ny < 0 || nx >= size || ny >= size || via[ny * size + nx] >= 0) {
                    continue;
                }
                if (goal < 0 && open(map, shut, cx + ox, cy + oy, z, DX[d], DY[d])) {
                    via[ny * size + nx] = (byte) d;
                    queue[tail++] = ny * size + nx;
                    goal = wanted[ny * size + nx] ? ny * size + nx : -1;
                }
            }
        }
        if (goal < 0) {
            return null;
        }
        // Back from the goal (into the queue, done with), then each tile put before the one after it.
        int n = 0;
        for (int cell = goal; cell != start; n++) {
            queue[n] = cell;
            int d = via[cell];
            cell = (cell / size - DY[d]) * size + (cell % size - DX[d]);
        }
        int[] path = new int[n + 1];
        path[0] = from;
        for (int k = 0; k < n; k++) {
            path[n - k] = pack(queue[k] % size + ox, queue[k] / size + oy, z);
        }
        return path;
    }
}
