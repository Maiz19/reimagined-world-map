package com.hdmapreforged;

import static com.hdmapreforged.Tiles.*;

import java.io.*;
import java.util.*;
import java.util.stream.*;
import java.util.zip.*;

/**
 * One's own house as last seen there: where one can walk and what stands where. Its tiles are at {@link #X},
 * {@link #Y} plus the house's own place (its south-west room at chunk 2, wherever the game loads it), where no map of
 * the game lies, so routes walk through it like anywhere else ({@link CollisionMap#setHouse}); the surface map draws
 * it at {@link #DRAWN_X}, {@link #DRAWN_Y}. Immutable.
 */
final class HousePlan {
    static final int X = 12800, Y = 12800;
    /** The game's scene, 104 tiles square: the house lies within it. */
    static final int SIZE = 104;
    /** Where the world map draws it: the surface map's empty west edge. */
    static final int DRAWN_X = 896, DRAWN_Y = 3000, TILES = SIZE * SIZE;

    /**
     * Per floor, per tile (x * SIZE + y): 1 walkable, 2 wall north, 4 wall east, 8 door north, 16 door east, 32 part of
     * a thing west of it, 64 part of a thing south of it.
     */
    final byte[][] tiles;
    /** What stands where (by node): the portals, jewellery box, stairs and such, by their names in the game. */
    final Map<Integer, String> things;
    /** Where one arrives: north of the exit portal. */
    final int arrival;
    /** Per region of the plan (2 × 2) and floor: {@link CollisionMap}'s layers. */
    final long[][] layers = new long[16][];

    HousePlan(byte[][] tiles, Map<Integer, String> things, int arrival) {
        this.tiles = tiles;
        this.things = Collections.unmodifiableMap(new LinkedHashMap<>(things));
        this.arrival = arrival;
        for (int z = 0; z < 4; z++) {
            for (int i = 0; i < TILES; i++) {
                int x = i / SIZE, y = i % SIZE, bit = (x & 63) << 6 | (y & 63);
                for (int k = 0; k < 5; k++) {
                    if ((tiles[z][i] & 1 << k) != 0) {
                        int at = ((x >> 6) * 2 + (y >> 6)) * 4 + z;
                        if (layers[at] == null) {
                            layers[at] = new long[320];
                        }
                        layers[at][k * 64 + (bit >> 6)] |= 1L << (bit & 63);
                    }
                }
            }
        }
    }

    /** Only floor one can walk to from these nodes stays walkable: grass beyond the walls is not the house. */
    static void keepReachable(byte[][] tiles, List<Integer> from) {
        boolean[][] reached = new boolean[4][TILES];
        var queue = new ArrayDeque<Integer>();
        for (int node : from) {
            reach(tiles, reached, queue, contains(node) ? (x(node) - X) * SIZE + y(node) - Y : -1, z(node));
        }
        while (!queue.isEmpty()) {
            int at = queue.poll();
            for (int n : steps(tiles[at / TILES], at % TILES)) {
                reach(tiles, reached, queue, n, at / TILES);
            }
        }
        for (int z = 0; z < 4; z++) {
            for (int i = 0; i < TILES; i++) {
                tiles[z][i] &= reached[z][i] ? ~0 : ~1;
            }
        }
    }

    static void reach(byte[][] tiles, boolean[][] reached, ArrayDeque<Integer> queue, int i, int z) {
        if (i >= 0 && z >= 0 && z < 4 && !reached[z][i] && (tiles[z][i] & 1) != 0) {
            reached[z][i] = true;
            queue.add(z * TILES + i);
        }
    }

    /**
     * Where one stands to use a thing: on it (a glory on a wall), else each free tile beside its tiles with no wall
     * between (a portal against a wall is used from its room, not the next); the game walks to the first its search
     * reaches ({@link GameWalk#path}). Its tiles east and north of the first are marked 32 and 64; empty none.
     */
    int[] approach(int node) {
        int z = z(node), x0 = x(node) - X, y0 = y(node) - Y;
        if (!contains(node) || (tiles[z][x0 * SIZE + y0] & 1) != 0) {
            return contains(node) ? new int[]{node} : new int[0];
        }
        int w = 1, h = 1;
        while (x0 + w < SIZE && (tiles[z][(x0 + w) * SIZE + y0] & 32) != 0) {
            w++;
        }
        while (y0 + h < SIZE && (tiles[z][x0 * SIZE + y0 + h] & 64) != 0) {
            h++;
        }
        var sides = IntStream.builder();
        for (int i = 0; i < w * h; i++) {
            int at = (x0 + i / h) * SIZE + y0 + i % h;
            for (int n : steps(tiles[z], at)) {
                if (n >= 0 && (tiles[z][n] & 1) != 0) {
                    sides.add(pack(X + n / SIZE, Y + n % SIZE, z));
                }
            }
        }
        return sides.build().toArray();
    }

    /**
     * The tiles one steps to from {@code i} (north, east, south, west), -1 where a wall is: walls stand on a tile's
     * north and east edges, and doors let one through.
     */
    static int[] steps(byte[] floor, int i) {
        int x = i / SIZE, y = i % SIZE;
        return new int[]{y < SIZE - 1 && (floor[i] & 2) == 0 ? i + 1 : -1, x < SIZE - 1 && (floor[i] & 4) == 0 ? i + SIZE
            : -1, y > 0 && (floor[i - 1] & 2) == 0 ? i - 1 : -1, x > 0 && (floor[i - SIZE] & 4) == 0 ? i - SIZE : -1};
    }

    static int node(int sceneX, int sceneY, int plane) {
        return pack(X + sceneX, Y + sceneY, plane);
    }

    static boolean contains(int node) {
        return contains(x(node), y(node));
    }

    static boolean contains(int x, int y) {
        return x >= X && x < X + SIZE && y >= Y && y < Y + SIZE;
    }

    long[] layers(int region, int z) {
        int dx = (region >> 8) - (X >> 6), dy = (region & 255) - (Y >> 6);
        return dx < 0 || dx > 1 || dy < 0 || dy > 1 ? null : layers[(dx * 2 + dy) * 4 + z];
    }

    /** As kept per account: the tiles deflated in Base64, the arrival, then per thing "node TAB name". */
    String encode() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DeflaterOutputStream out = new DeflaterOutputStream(bytes)) {
            for (var floor : tiles) {
                out.write(floor);
            }
        } catch (IOException e) {
            return null;
        }
        StringBuilder text = new StringBuilder(Base64.getEncoder().encodeToString(bytes.toByteArray()))
            .append('\n').append(arrival);
        things.forEach((node, name) -> text.append('\n').append(node).append('\t').append(name));
        return text.toString();
    }

    /** Null when unreadable; things off the plan are left out. */
    static HousePlan decode(String text) {
        var lines = text.split("\n");
        try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(lines[0])))) {
            var all = in.readNBytes(4 * TILES + 1);
            int arrival = Integer.parseInt(lines[1]);
            if (all.length != 4 * TILES || !contains(arrival)) {
                return null;
            }
            byte[][] tiles = new byte[4][];
            for (int z = 0; z < 4; z++) {
                tiles[z] = Arrays.copyOfRange(all, z * TILES, (z + 1) * TILES);
            }
            var things = new LinkedHashMap<Integer, String>();
            for (int i = 2; i < lines.length; i++) {
                var parts = lines[i].split("\t", 2);
                int node = Integer.parseInt(parts[0]);
                if (parts.length == 2 && contains(node) && parts[1].length() <= 2000) {
                    things.put(node, parts[1]);
                }
            }
            return new HousePlan(tiles, things, arrival);
        } catch (RuntimeException | IOException e) {
            return null;
        }
    }
}
