package com.hdmapreforged;

import java.util.*;

/** Route graph nodes as ints: a game tile, or with {@link #SEA} set a 4×4 block of sea tiles on the ground floor. */
final class Tiles {
    static final int SEA = 1 << 30, CELL = 4;

    /**
     * The Wilderness's safe zones and the ground south of its edge (the map version's wilderness.tsv); a row of fewer
     * than four numbers holds no tile.
     */
    static final RouteFiles.Table<List<int[]>> ZONES = new RouteFiles.Table<>(() -> Pathfinder.table(RouteFiles.DIR
        + "wilderness.tsv", c -> Arrays.copyOf(Pathfinder.ints(c[1]), 4)));

    static int pack(int x, int y, int z) {
        return (z & 3) << 28 | (x & 0x3fff) << 14 | (y & 0x3fff);
    }

    static int x(int node) {
        return isSea(node) ? cellX(node) * CELL + CELL / 2 : node >> 14 & 0x3fff;
    }

    static int y(int node) {
        return isSea(node) ? cellY(node) * CELL + CELL / 2 : node & 0x3fff;
    }

    static int z(int node) {
        return isSea(node) ? 0 : node >> 28 & 3;
    }

    static boolean isSea(int node) {
        return (node & SEA) != 0;
    }

    static int sea(int cellX, int cellY) {
        return SEA | (cellX & 0xfff) << 12 | (cellY & 0xfff);
    }

    static int cellX(int node) {
        return node >> 12 & 0xfff;
    }

    static int cellY(int node) {
        return node & 0xfff;
    }

    static int seaAt(int x, int y) {
        return sea(x / CELL, y / CELL);
    }

    /** {@code "x y plane"} packed; -1 when it is not one. */
    static int parse(String text) {
        var xyz = text.trim().split("\\s+");
        if (xyz.length != 3) {
            return -1;
        }
        for (String part : xyz) {
            // Too many digits: not a tile (and no overflow).

            if (part.isEmpty() || part.length() > 5 || !part.chars().allMatch(Character::isDigit)) {
                return -1;
            }
        }
        int x = Integer.parseInt(xyz[0]), y = Integer.parseInt(xyz[1]), z = Integer.parseInt(xyz[2]);
        return x < 1 << 14 && y < 1 << 14 && z < 4 ? pack(x, y, z) : -1;
    }

    static String format(int node) {
        return x(node) + ", " + y(node) + ", " + z(node) + (isSea(node) ? " (sea)" : "");
    }

    /**
     * In the Wilderness: above ground x 2944-3391, y 3520-3967, and its dungeons 6400 tiles north of that; not its safe
     * zones, nor the ground south of its edge ({@link #ZONES}).
     */
    static boolean wilderness(int node) {
        if (isSea(node)) {
            return false;
        }
        int x = x(node), y = y(node);
        return x >= 2944 && x <= 3391 && (y >= 3520 && y <= 3967 || y >= 9920 && y <= 10367)
            && !within(ZONES.get(), x, y);
    }

    static boolean within(List<int[]> zones, int x, int y) {
        for (var zone : zones) {
            if (x >= zone[0] && x <= zone[1] && y >= zone[2] && y <= zone[3]) {
                return true;
            }
        }
        return false;
    }

    /** On the same floor (the sea's is 0) and within {@code radius} tiles. */
    static boolean near(int a, int b, int radius) {
        return z(a) == z(b) && distance(a, b) <= radius;
    }

    /** Chebyshev distance in tiles, ignoring floors. */
    static int distance(int a, int b) {
        return Math.max(Math.abs(x(a) - x(b)), Math.abs(y(a) - y(b)));
    }
}
