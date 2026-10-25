package com.hdmapreforged;

import lombok.*;

/** A ladder, staircase, trapdoor or entrance from the game cache: an object's footprint and where it leads. */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class Transition {
    final int x, y, plane, sizeX, sizeY, to;
    final String name, action;
    final int[] origins;
    /** A solid object walked through (a gate, a gap), found by shape: it may ask for a quest or level. */
    final boolean through;

    /** {@code "x y plane sizeX sizeY <tab> x y plane <tab> name <tab> action <tab> x y;x y"}; null for bad lines. */
    static Transition parse(String line) {
        if (line.isEmpty() || line.startsWith("#")) {
            return null;
        }
        var cols = line.split("\t");
        if (cols.length < 5) {
            return null;
        }
        try {
            var from = Pathfinder.ints(cols[0]);
            var to = Pathfinder.ints(cols[1]);
            int z = from[2];
            if (from.length != 5 || to.length != 3 || !valid(from[0], from[1], z) || !valid(to[0], to[1], to[2])
                || from[3] < 1 || from[4] < 1 || from[3] > 16 || from[4] > 16) {
                return null;
            }
            var tiles = cols[4].trim().split(";");
            int[] origins = new int[tiles.length];
            for (int i = 0; i < tiles.length; i++) {
                var xy = Pathfinder.ints(tiles[i]);
                if (xy.length != 2 || !valid(xy[0], xy[1], z)) {
                    return null;
                }
                origins[i] = Tiles.pack(xy[0], xy[1], z);
            }
            return new Transition(from[0], from[1], z, from[3], from[4], Tiles.pack(to[0], to[1], to[2]), cols[2].trim(),
                cols[3].trim(), origins, cols.length > 5 && "through".equals(cols[5].trim()));
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            return null;
        }
    }

    static boolean valid(int x, int y, int z) {
        return x >= 0 && y >= 0 && x < 1 << 14 && y < 1 << 14 && z >= 0 && z < 4;
    }
}
