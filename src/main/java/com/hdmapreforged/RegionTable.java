package com.hdmapreforged;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * Which maps really show a 64×64 region where map bounds overlap (empty: checked, none), and where several share a
 * region, which draws each 8×8 zone: a map version's regions.tsv from the map server (built from its tiles by the test
 * sources' RegionResolver).
 */
class RegionTable {
    static final int[] NONE = new int[0];

    static final int ZONES = 8, ZONE = 8;
    /** A zone's owners as a bit mask over the region's owner list, one character (up to 6 owners). */
    static final String MASKS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ_-";

    final Map<Integer, int[]> owners = new ConcurrentHashMap<>();
    /** 64 mask characters, zone (x, y) at index x * 8 + y. */
    final Map<Integer, String> zones = new ConcurrentHashMap<>();
    /**
     * Per region, by zone and floor ({@code (zone * 4 + floor)}): the map and floor drawing that floor's ground where the
     * zone's own map does not, packed as {@code (map id + 2) << 2 | floor}; 0 where the zone's map draws it.
     */
    final Map<Integer, int[]> floors = new ConcurrentHashMap<>();
    /**
     * By tile and floor ({@link #tileKey}): the map and floor drawing that floor's ground at that one tile where the map
     * shown there (after the zone's floor line) draws nothing, packed as the zones' ({@code (map id + 2) << 2 | floor});
     * for strips narrower than a zone (along Cam Torum's walls).
     */
    final Map<Integer, Integer> tileFloors = new ConcurrentHashMap<>();

    static int regionId(int x, int y) {
        return ((x >> 6) << 8) | (y >> 6);
    }

    /** Owner map ids, empty when none, or null when the table does not list the region. */
    int[] owners(int region) {
        return owners.get(region);
    }

    void put(int region, int[] ids) {
        owners.put(region, ids);
    }

    /**
     * The owners drawing a tile's zone, or when none does, the zones around it (an entrance just beside a drawing);
     * null when zones are not known; empty when none draws anything near.
     */
    int[] zoneOwners(int x, int y) {
        int region = regionId(x, y);
        String text = zones.get(region);
        var ids = owners.get(region);
        if (text == null || ids == null) {
            return null;
        }
        int zx = (x & 63) / ZONE, zy = (y & 63) / ZONE, mask = MASKS.indexOf(text.charAt(zx * ZONES + zy));
        if (mask == 0) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    int nx = zx + dx, ny = zy + dy;
                    if (nx >= 0 && ny >= 0 && nx < ZONES && ny < ZONES) {
                        mask |= MASKS.indexOf(text.charAt(nx * ZONES + ny));
                    }
                }
            }
        }
        int count = Integer.bitCount(mask & ((1 << Math.min(6, ids.length)) - 1));
        var found = new int[count];
        int n = 0;
        for (int i = 0; i < ids.length && i < 6; i++) {
            if ((mask & 1 << i) != 0) {
                found[n++] = ids[i];
            }
        }
        return found;
    }

    /**
     * Where the ground a floor walks in this spot's zone is drawn when the zone's own map does not draw it on that floor
     * (an upper floor the game draws a floor lower, as the Brimhaven Dungeon's raised paths; a floor walked under the one
     * the game's map shows, as in Cam Torum): {map id, floor drawn}; null where the zone's map draws it.
     */
    int[] floorOverride(int x, int y, int floor) {
        if (floor < 0 || floor > 3) {
            return null;
        }
        Integer tile = tileFloors.isEmpty() ? null : tileFloors.get(tileKey(x, y, floor));
        if (tile != null) {
            return new int[]{(tile >> 2) - 2, tile & 3};
        }
        var packed = floors.get(regionId(x, y));
        int v = packed == null ? 0 : packed[((x & 63) / ZONE * ZONES + (y & 63) / ZONE) * 4 + floor];
        return v == 0 ? null : new int[]{(v >> 2) - 2, v & 3};
    }

    /** A tile and floor as one number (as the route data packs them). */
    static int tileKey(int x, int y, int floor) {
        return floor << 28 | x << 14 | y;
    }

    void putTileFloor(int x, int y, int floor, int mapId, int drawnFloor) {
        tileFloors.put(tileKey(x, y, floor), (mapId + 2) << 2 | drawnFloor);
    }

    void putFloor(int region, int zone, int floor, int mapId, int drawnFloor) {
        floors.computeIfAbsent(region, r -> new int[ZONES * ZONES * 4])[zone * 4 + floor] = (mapId + 2) << 2 | drawnFloor;
    }

    /**
     * Lines of region id, tab, comma-separated map ids, optionally tab and zones; floor lines ({@code F}, tab, region,
     * tab, {@code zone.floor=map/floor} comma-separated) and tile floor lines ({@code T}, tab, region, tab,
     * {@code x.y.floor=map/floor} comma-separated, x and y within the region), which tables before them did not have (a
     * reader that cannot read a line skips it); bad lines are skipped.
     */
    RegionTable read(Reader tsv) throws IOException {
        BufferedReader reader = new BufferedReader(tsv);
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("#") || line.trim().isEmpty()) {
                continue;
            }
            try {
                if (line.startsWith(FLOOR_LINE)) {
                    readFloors(line);
                    continue;
                }
                if (line.startsWith(TILE_FLOOR_LINE)) {
                    readTileFloors(line);
                    continue;
                }
                readLine(line);
            } catch (RuntimeException e) {
                // Skipped: the maps' bounds decide there.
            }
        }
        return this;
    }

    void readLine(String line) {
        var cells = line.split("\t", -1);
        var ids = NONE;
        if (cells.length > 1 && !cells[1].trim().isEmpty()) {
            ids = Arrays.stream(cells[1].split(",")).mapToInt(s -> Integer.parseInt(s.trim())).toArray();
        }
        int region = Integer.parseInt(cells[0].trim());
        if (region < 0 || region > 0xFFFF) {
            return;
        }
        String zoned = cells.length > 2 ? cells[2].trim() : "";
        if (zoned.length() == ZONES * ZONES && ids.length > 0 && zoned.chars().allMatch(c -> MASKS.indexOf(c) >= 0)) {
            zones.put(region, zoned);
        } else if (!Arrays.equals(owners.get(region), ids)) {
            // A later table with other owners: its zones are not known.
            zones.remove(region);
        }
        put(region, ids);
    }

    static boolean valid(int floor, int map, int drawn) {
        return floor >= 0 && floor < 4 && map >= BaseMap.FULL && map < 1 << 20 && drawn >= 0 && drawn < 4;
    }

    static final String FLOOR_LINE = "F\t";
    static final String TILE_FLOOR_LINE = "T\t";

    /** {@code T <tab> region <tab> x.y.floor=map/floor,...} (x, y within the region); entries out of range are skipped. */
    void readTileFloors(String line) {
        readEntries(line, 5, (region, p) -> {
            int x = p[0], y = p[1], floor = p[2], map = p[3], drawn = p[4];
            if (x >= 0 && x < 64 && y >= 0 && y < 64 && valid(floor, map, drawn)) {
                putTileFloor(((region >> 8) << 6) + x, ((region & 255) << 6) + y, floor, map, drawn);
            }
        });
    }

    /** {@code F <tab> region <tab> zone.floor=map/floor,...}; entries out of range are skipped. */
    void readFloors(String line) {
        readEntries(line, 4, (region, p) -> {
            int zone = p[0], floor = p[1], map = p[2], drawn = p[3];
            if (zone >= 0 && zone < ZONES * ZONES && valid(floor, map, drawn)) {
                putFloor(region, zone, floor, map, drawn);
            }
        });
    }

    /**
     * A {@code T} or {@code F} line's entries: {@code <tab> region <tab> a.b...=c/d,...}, each entry {@code fields}
     * numbers, passed with the region to {@code use}. The whole line is skipped where the region is unreadable or
     * {@code cells} too short; one bad or short entry skips only itself.
     */
    void readEntries(String line, int fields, BiConsumer<Integer, int[]> use) {
        var cells = line.split("\t");
        int region = Integer.parseInt(cells[1].trim());
        if (region < 0 || region > 0xFFFF || cells.length < 3) {
            return;
        }
        for (String entry : cells[2].split(",")) {
            var parts = entry.trim().split("[.=/]");
            if (parts.length != fields) {
                continue;
            }
            try {
                var p = new int[fields];
                for (int i = 0; i < fields; i++) {
                    p[i] = Integer.parseInt(parts[i]);
                }
                use.accept(region, p);
            } catch (RuntimeException e) {
                // That entry only.
            }
        }
    }
}
