package com.hdmapreforged;

import static com.hdmapreforged.BaseMap.*;

import com.google.gson.*;
import java.io.*;
import java.util.*;
import java.util.function.*;
import net.runelite.api.coords.*;

/** A map version's map list (our map server's maps.json); its region table decides where bounds overlap. */
final class BaseMaps {
    final List<BaseMap> sorted;
    final Map<Integer, BaseMap> byId = new HashMap<>();
    RegionTable regions = new RegionTable();
    /** The map version they are of; null when not known. */
    String version;

    BaseMaps(List<BaseMap> maps) {
        var list = new ArrayList<>(maps);
        // Surface first, then the combined map, then alphabetical.
        list.sort(Comparator.comparingInt((BaseMap m) -> m.id == SURFACE ? 0 : m.id == FULL ? 1 : 2)
            .thenComparing(m -> m.name, String.CASE_INSENSITIVE_ORDER));
        sorted = Collections.unmodifiableList(list);
        for (var map : list) {
            byId.put(map.id, map);
            map.maps = this;
        }
    }

    /** maps.json's entries: {@code {mapId, name, bounds: [[minX, minY], [maxX, maxY]], center: [x, y], tiles}}. */
    static final class Entry {
        int mapId;
        String name;
        int[][] bounds;
        int[] center;
        /** Absent: its own. */
        Integer tiles;
    }

    /** An unreadable list gives no maps (the builders check the entries: BaseMapsTest). */
    static BaseMaps parse(Gson gson, Reader json) {
        var maps = new ArrayList<BaseMap>();
        try {
            for (var e : gson.fromJson(json, Entry[].class)) {
                // A map without a name (the sort below needs it) makes the file unusable, as one without bounds.
                maps.add(new BaseMap(e.mapId, Objects.requireNonNull(e.name), e.bounds[0][0], e.bounds[0][1], e.bounds[1][0], e.bounds[1][1],
                    e.center[0], e.center[1], e.tiles == null ? e.mapId : e.tiles));
            }
        } catch (RuntimeException e) {
            maps.clear();
        }
        return new BaseMaps(maps);
    }

    BaseMap byId(int id) {
        return byId.get(id);
    }

    BaseMap surface() {
        var surface = byId.get(SURFACE);
        return surface != null ? surface : sorted.get(0);
    }

    /** One of the maps drawing the point's region (maps may share one), or the map {@link #find} gives. */
    boolean draws(BaseMap map, int x, int y) {
        var owners = drawers(x, y);
        return map != null && map.contains(x, y)
            && (owners != null && Arrays.stream(owners).anyMatch(id -> id == map.id) || find(x, y) == map);
    }

    /** The owners of the spot's 8×8 zone where known, else the region's owners; null when not known. */
    int[] drawers(int x, int y) {
        var zone = regions.zoneOwners(x, y);
        return zone != null ? zone : regions.owners(RegionTable.regionId(x, y));
    }

    /**
     * The floor {@code on} draws a game point's ground on where the region table says it is not the point's own (the
     * Brimhaven Dungeon's raised paths, walked on floor 2, drawn on floor 1), or -1. Any map drawn with the same tiles
     * draws it there.
     */
    int drawnFloor(BaseMap on, WorldPoint game) {
        var found = on == null ? null : regions.floorOverride(game.getX(), game.getY(), game.getPlane());
        var map = found == null ? null : byId(found[0]);
        return map != null && map.tiles == on.tiles ? found[1] : -1;
    }

    /**
     * The game's floor a spot of a map's drawn floor stands for: {@code game} (where the map's moves put it), unless that
     * is no ground ({@code ground}) and a floor the region table draws on {@code drawnFloor} there is: then that floor.
     */
    WorldPoint gameFloor(BaseMap on, WorldPoint game, int drawnFloor, Predicate<WorldPoint> ground) {
        if (on == null || ground == null || ground.test(game)) {
            return game;
        }
        for (int d = 1; d < 4; d++) {
            for (int floor : new int[]{game.getPlane() - d, game.getPlane() + d}) {
                var other = floor < 0 || floor > 3 ? null : new WorldPoint(game.getX(), game.getY(), floor);
                if (other != null && drawnFloor(on, other) == drawnFloor && ground.test(other)) {
                    return other;
                }
            }
        }
        return game;
    }

    /** The map drawing a game point elsewhere when one does (the Kalphite Lair), else {@link #find(int, int)}. */
    BaseMap find(WorldPoint game) {
        int x = game.getX(), y = game.getY();
        if (HousePlan.contains(x, y)) {
            // One's house is drawn at the surface map's edge.
            return byId(SURFACE);
        }
        // The map that draws the ground of the point's floor where the zone's own map does not draw it on that floor (the
        // region table's floor lines).
        var line = regions.floorOverride(x, y, game.getPlane());
        var floor = line == null ? null : byId(line[0]);
        if (floor != null && (floor.id == FULL || floor.contains(x, y))) {
            return floor;
        }
        var drawn = WorldMapMoves.drawn(game);
        var moved = drawn == null ? null : byId(drawn.map);
        // Some moves put a place outside the bounds of the map they name.
        if (moved != null && moved.contains(drawn.point.getX(), drawn.point.getY())) {
            return moved;
        }
        var found = find(x, y);
        if (found != null && WorldMapMoves.covers(found.id, x, y)) {
            // That map shows a moved place here: the smallest other map that draws the spot.
            BaseMap other = null;
            for (var map : sorted) {
                if (map != found && map.id != FULL && map.contains(x, y) && !WorldMapMoves.covers(map.id, x, y)
                    && (other == null || map.area() < other.area())) {
                    other = map;
                }
            }
            return other != null ? other : byId(FULL);
        }
        return found;
    }

    /** Whether a map shows this game point where it is, not a moved place instead. */
    boolean drawsGame(BaseMap map, WorldPoint game) {
        if (map != null && regions.floorOverride(game.getX(), game.getY(), game.getPlane()) != null) {
            // Its floor's ground is drawn by the map the table names (and every map with the same tiles).
            return map.contains(game.getX(), game.getY()) && drawnFloor(map, game) >= 0;
        }
        return map != null && draws(map, game.getX(), game.getY())
            && !WorldMapMoves.covers(map.id, game.getX(), game.getY());
    }

    /**
     * The smallest map drawing the point's region; the map of everything where the region has none; else the smallest
     * map containing the point, and the map of everything last. Null when no map holds it.
     */
    BaseMap find(int x, int y) {
        var full = byId.get(FULL);
        boolean fullDraws = full != null && full.contains(x, y);
        var owners = drawers(x, y);
        BaseMap best = null;
        if (owners != null) {
            for (int id : owners) {
                var map = byId.get(id);
                if (map != null && map.contains(x, y) && (best == null || map.area() < best.area())) {
                    best = map;
                }
            }
            if (best != null || owners.length == 0 && fullDraws) {
                return best != null ? best : full;
            }
        }
        for (var map : sorted) {
            if (map.id != FULL && map.contains(x, y) && (best == null || map.area() < best.area())) {
                best = map;
            }
        }
        // The map of everything draws all of the game's ground: where no other map does, it shows the spot.
        return best != null ? best : fullDraws ? full : null;
    }
}
