package com.hdmapreforged;

import java.io.*;
import java.util.*;
import java.util.function.*;
import java.util.zip.*;
import lombok.*;

/**
 * Where one can walk, from the bundled collision.zip: per region and floor, bitsets of walkable tiles and of walls and
 * doors on the north and east edges. Immutable after loading but for one's own house ({@link #setHouse}, replaced
 * whole), so any thread may read it.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class CollisionMap {
    static final String RESOURCE = RouteFiles.DIR + "collision.zip";
    static final int MAGIC = 0x48444d43, WALK = 0, WALL_N = 64, WALL_E = 128, DOOR_N = 192, DOOR_E = 256, LAYERS = 320;
    static final int MAX_BIN_BYTES = 64 * 1024 * 1024, MAX_TABLE_BYTES = 16 * 1024 * 1024;

    /** By {@code region * 4 + plane}: five bitsets of 64 longs, or null. */
    final long[][] floors = new long[1 << 18][];
    final List<Transition> transitions;
    /** By packed tile: "Chop-down Vines (bring an axe)". */
    final Map<Integer, String> obstacles;
    final boolean[] obstacleRegions = new boolean[1 << 16];
    /** One's own house, where no map of the game lies; replaced whole. */
    @Setter
    volatile HousePlan house;

    static CollisionMap load() throws IOException {
        try (InputStream in = RouteFiles.require(RESOURCE)) {
            return read(in);
        }
    }

    static CollisionMap read(InputStream zipped) throws IOException {
        byte[] bin = null;
        var transitions = new ArrayList<Transition>();
        var obstacles = new HashMap<Integer, String>();
        var zip = new ZipInputStream(zipped);
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
            if ("collision.bin".equals(entry.getName())) {
                bin = RouteFiles.read(zip, MAX_BIN_BYTES, "collision.bin");
            } else if ("transitions.tsv".equals(entry.getName())) {
                Pathfinder.rows(new ByteArrayInputStream(RouteFiles.read(zip, MAX_TABLE_BYTES, "transitions.tsv")), 1, line -> {
                    Transition t = Transition.parse(line[0]);
                    if (t != null) {
                        transitions.add(t);
                    }
                });
            } else if ("obstacles.tsv".equals(entry.getName())) {
                Pathfinder.rows(new ByteArrayInputStream(RouteFiles.read(zip, MAX_TABLE_BYTES, "obstacles.tsv")), 0,
                    parts -> obstacle(obstacles, parts));
            }
        }
        if (bin == null) {
            throw new IOException("No collision.bin");
        }
        var map = new CollisionMap(Collections.unmodifiableList(transitions), obstacles);
        obstacles.keySet().forEach(node -> map.obstacleRegions[region(Tiles.x(node), Tiles.y(node))] = true);
        map.decode(bin);
        return map;
    }

    void decode(byte[] bin) throws IOException {
        var in = new DataInputStream(new ByteArrayInputStream(bin));
        if (in.readInt() != MAGIC || in.readInt() != 1) {
            throw new IOException("Unknown collision data");
        }
        int count = in.readInt();
        if (count < 0 || count > 1 << 16) {
            throw new IOException("Bad region count " + count);
        }
        for (int i = 0; i < count; i++) {
            int region = in.readUnsignedShort(), mask = in.readUnsignedByte();
            for (int z = 0; z < 4; z++) {
                if ((mask & 1 << z) != 0) {
                    floors[region << 2 | z] = longs(in, LAYERS);
                }
            }
            // Ground floor water: only the development tools use it.
            in.skipBytes((mask & 16) != 0 ? 64 * 8 : 0);
        }
    }

    static long[] longs(DataInputStream in, int count) throws IOException {
        long[] longs = new long[count];
        for (int j = 0; j < count; j++) {
            longs[j] = in.readLong();
        }
        return longs;
    }

    /** The region of a tile, or -1 off the world. */
    static int region(int x, int y) {
        return (x | y) >>> 14 == 0 ? (x >> 6) << 8 | (y >> 6) : -1;
    }

    /** "x y plane <tab> name <tab> action <tab> what it takes"; bad lines are skipped. */
    static void obstacle(Map<Integer, String> obstacles, String[] parts) {
        if (parts.length < 4) {
            return;
        }
        int at = Tiles.parse(parts[0]);
        if (at < 0) {
            return;
        }
        String action = parts[2].isEmpty() ? parts[2] : Character.toUpperCase(parts[2].charAt(0)) + parts[2].substring(1);
        obstacles.put(at, action + " " + parts[1] + " (bring " + parts[3] + ")");
    }

    String obstacle(int node) {
        return isObstacle(Tiles.x(node), Tiles.y(node), node) ? obstacles.get(node) : null;
    }

    boolean isObstacle(int x, int y, int node) {
        int region = region(x, y);
        return region >= 0 && obstacleRegions[region] && obstacles.containsKey(node);
    }

    long[] layers(int x, int y, int z) {
        int region = region(x, y);
        var found = region < 0 || z < 0 || z > 3 ? null : floors[region << 2 | z];
        var plan = found == null && region >= 0 && z >= 0 && z <= 3 ? house : null;
        return plan != null ? plan.layers(region, z) : found;
    }

    static boolean bit(long[] layers, int offset, int x, int y) {
        int i = (x & 63) << 6 | (y & 63);
        return (layers[offset + (i >> 6)] & 1L << (i & 63)) != 0;
    }

    boolean walkable(int x, int y, int z) {
        return edge(x, y, z, WALK);
    }

    boolean edge(int x, int y, int z, int offset) {
        var layers = layers(x, y, z);
        return layers != null && bit(layers, offset, x, y);
    }

    boolean canStep(int x, int y, int z, int dx, int dy) {
        if (!walkable(x + dx, y + dy, z)) {
            return false;
        }
        if (dx != 0 && dy != 0) {
            // Diagonal: both straight routes around the corner must be open, and no door on the way.
            return walkable(x + dx, y, z) && walkable(x, y + dy, z) && clear(x, y, z, dx, 0) && clear(x + dx, y, z, 0, dy)
                && clear(x, y, z, 0, dy) && clear(x, y + dy, z, dx, 0);
        }
        return straightOpen(x, y, z, dx, dy);
    }

    boolean straightOpen(int x, int y, int z, int dx, int dy) {
        return !crosses(x, y, z, dx, dy, WALL_N, WALL_E);
    }

    boolean clear(int x, int y, int z, int dx, int dy) {
        return straightOpen(x, y, z, dx, dy) && !door(x, y, z, dx, dy);
    }

    boolean door(int x, int y, int z, int dx, int dy) {
        return (dx == 1 || dx == -1 || dy == 1 || dy == -1) && crosses(x, y, z, dx, dy, DOOR_N, DOOR_E);
    }

    boolean crosses(int x, int y, int z, int dx, int dy, int north, int east) {
        return dx == 1 ? edge(x, y, z, east) : dx == -1 ? edge(x - 1, y, z, east)
            : dy == 1 ? edge(x, y, z, north) : edge(x, y - 1, z, north);
    }

    /** Nearest walkable tile within {@code radius} (Chebyshev rings, then straight distance), packed, or -1. */
    int nearestWalkable(int x, int y, int z, int radius) {
        return nearest(x, y, z, 0, radius, x, y, null, false);
    }

    /**
     * The same, ties going farthest from {@code (awayX, awayY)} (a shortcut's near side, not a pocket between its
     * stones), and tiles {@code avoid} names (a shut-in pocket) only when no other is within reach.
     */
    int nearestWalkable(int x, int y, int z, int radius, int awayX, int awayY, IntPredicate avoid) {
        int plain = nearest(x, y, z, 0, radius, awayX, awayY, null, false);
        if (avoid == null || plain < 0 || !avoid.test(plain)) {
            return plain;
        }
        int open = nearest(x, y, z, 0, radius, awayX, awayY, avoid, false);
        return open >= 0 ? open : plain;
    }

    /**
     * The nearest walkable tile in rings {@code first} to {@code radius} round (x, y) that {@code avoid} does not name
     * (null: any): by straight distance, ties farthest from (awayX, awayY); {@code scanned}: the first met, west to east
     * and south to north. Packed, or -1.
     */
    int nearest(int x, int y, int z, int first, int radius, int awayX, int awayY, IntPredicate avoid, boolean scanned) {
        for (int r = first; r <= radius; r++) {
            int best = -1;
            double bestDistance = Double.MAX_VALUE;
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != r || !walkable(x + dx, y + dy, z)
                        || avoid != null && avoid.test(Tiles.pack(x + dx, y + dy, z))) {
                        continue;
                    }
                    double away = (x + dx - awayX) * (double) (x + dx - awayX) + (y + dy - awayY) * (double) (y + dy - awayY);
                    double d = scanned ? 0 : (dx * dx + dy * dy) * 1e6 - away;
                    if (d < bestDistance) {
                        bestDistance = d;
                        best = Tiles.pack(x + dx, y + dy, z);
                    }
                }
            }
            if (best >= 0) {
                return best;
            }
        }
        return -1;
    }
}
