package com.hdmapreforged;

import java.util.*;
import java.util.concurrent.*;

/**
 * Which maps really show a 64×64 region where map bounds overlap (empty: checked, none), and where several share a
 * region, which draws each 8×8 zone: a map version's regions.json from the map server as Gson binds it (built from its
 * tiles by the test sources' RegionResolver; regions.tsv beside it is the released plugins'). Read only once made.
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
}
