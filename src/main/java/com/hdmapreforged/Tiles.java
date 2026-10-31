package com.hdmapreforged;

/** Route graph nodes as ints: a game tile, or with {@link #SEA} set a 4×4 block of sea tiles on the ground floor. */
final class Tiles {
    static final int SEA = 1 << 30, CELL = 4;

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

    /** The Wilderness: x from WILD_X, WILD_SIZE tiles across and deep, its edge at each of WILD_STARTS (above ground, and its dungeons). */
    static final int WILD_X = 2944, WILD_SIZE = 448;
    static final int[] WILD_STARTS = {3520, 9920};

    /** The Wilderness level of a tile (level n starts 8(n-1) tiles north of its edge, above or below ground); else 0. */
    static int wildernessLevel(int x, int y) {
        int start = y >= WILD_STARTS[1] ? WILD_STARTS[1] : WILD_STARTS[0];
        return x >= WILD_X && x < WILD_X + WILD_SIZE && y >= start && y < start + WILD_SIZE ? (y - start) / 8 + 1 : 0;
    }

    /** In the Wilderness, not its safe zones, nor the ground south of its edge (the map version's zones). */
    static boolean wilderness(int node) {
        int x = x(node), y = y(node);
        if (isSea(node) || wildernessLevel(x, y) <= 0) {
            return false;
        }
        for (var zone : RouteFiles.tables().zones) {
            if (x >= zone[0] && x <= zone[1] && y >= zone[2] && y <= zone[3]) {
                return false;
            }
        }
        return true;
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
