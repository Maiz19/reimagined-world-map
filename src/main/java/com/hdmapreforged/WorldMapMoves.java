package com.hdmapreforged;

import java.util.*;
import java.util.function.*;
import java.util.stream.*;
import lombok.*;
import net.runelite.api.coords.*;

/**
 * Where the game's world map draws part of the game elsewhere (the Kalphite Lair beside the desert caves,
 * floor 2 as floor 0): turns drawn coordinates into the game's own. Also where it draws the floors above a part
 * flat on the ground floor (Keldagrim's houses). From the map version's {@code world_map_moves.tsv}.
 */
final class WorldMapMoves {
    @RequiredArgsConstructor
    static final class Move {
        final int map, x, y, width, height, dx, dy, plane, planes;
    }

    /**
     * Every part listed, in the file's order, the ones drawn where they are with floors drawn flat too: for
     * {@link #floorsDrawnFlat}. Map id, x y width height, dx dy floor [floors].
     */
    static final RouteFiles.Table<List<Move>> ALL = new RouteFiles.Table<>(() -> Pathfinder.table(RouteFiles.DIR
        + "world_map_moves.tsv", c -> {
            var v = Pathfinder.ints(String.join(" ", c));
            return new Move(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v.length > 8 ? v[8] : 1);
        }));
    /** The parts drawn elsewhere. */
    static final RouteFiles.Table<List<Move>> MOVES = new RouteFiles.Table<>(() -> ALL.get().stream()
        .filter(move -> move.dx != 0 || move.dy != 0 || move.plane != 0).collect(Collectors.toList()));

    /** The last move that fits: whole map squares are listed before zones, so a zone's (finer) wins, as in the game. */
    static Move last(Predicate<Move> fits) {
        return last(MOVES.get(), fits);
    }

    static Move last(List<Move> moves, Predicate<Move> fits) {
        for (int i = moves.size() - 1; i >= 0; i--) {
            Move move = moves.get(i);
            if (fits.test(move)) {
                return move;
            }
        }
        return null;
    }

    static WorldPoint toWorld(int mapId, WorldPoint drawn) {
        Move found = last(move -> move.map == mapId && inside(move, drawn.getX(), drawn.getY()));
        return found == null ? drawn : new WorldPoint(drawn.getX() + found.dx, drawn.getY() + found.dy,
            Math.min(3, found.plane + Math.min(drawn.getPlane(), found.planes - 1)));
    }

    /** As the map of everything draws them: a moved part is drawn where its own map draws it. */
    static WorldPoint fromDrawnAnywhere(WorldPoint drawn) {
        Move found = last(move -> inside(move, drawn.getX(), drawn.getY()));
        return found == null ? drawn : toWorld(found.map, drawn);
    }

    /** Whether map {@code mapId} draws a moved part of the game at this spot (the Dagannoth Kings' lair). */
    static boolean covers(int mapId, int x, int y) {
        return last(move -> move.map == mapId && inside(move, x, y)) != null;
    }

    @RequiredArgsConstructor
    static final class Drawn {
        final int map;
        final WorldPoint point;
    }

    static WorldPoint toDrawn(int mapId, WorldPoint game) {
        Move found = last(move -> move.map == mapId && moves(move, game));
        return found == null ? null : drawnBy(found, game);
    }

    static Drawn drawn(WorldPoint game) {
        Move found = last(move -> moves(move, game));
        return found == null ? null : new Drawn(found.map, drawnBy(found, game));
    }

    static boolean moves(Move move, WorldPoint game) {
        int floor = game.getPlane() - move.plane;
        return inside(move, game.getX() - move.dx, game.getY() - move.dy) && floor >= 0 && floor < move.planes;
    }

    static boolean inside(Move move, int x, int y) {
        return x >= move.x && x < move.x + move.width && y >= move.y && y < move.y + move.height;
    }

    static WorldPoint drawnBy(Move move, WorldPoint game) {
        return new WorldPoint(game.getX() - move.dx, game.getY() - move.dy, game.getPlane() - move.plane);
    }

    /**
     * How many floors a map draws flat on a drawn spot, as the game's own map does (Keldagrim's houses, the God Wars
     * boss rooms' three floors): 1 where only the floor drawn there shows, every other floor there being another place.
     * The map of everything draws each part as the part's own map does: ask with that map's id.
     */
    static int floorsDrawnFlat(int mapId, int x, int y) {
        Move found = last(ALL.get(), move -> move.map == mapId && inside(move, x, y));
        return found == null ? 1 : found.planes;
    }
}
