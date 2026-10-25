package com.hdmapreforged;

import static com.hdmapreforged.GameWalk.*;
import static java.util.stream.Collectors.*;
import static com.hdmapreforged.RouteFiles.*;
import static com.hdmapreforged.IntMap.MISSING;
import static com.hdmapreforged.Route.Outcome.*;
import static java.lang.Math.*;
import static com.hdmapreforged.Tiles.*;

import com.hdmapreforged.Route.*;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;
import lombok.*;

/**
 * A* over tiles, sea blocks and jumps ({@link Edge}); costs in half ticks. The heuristic stays a lower bound by folding
 * underground y onto the surface (y − 6400) and bounding far/cheap jumps with a backward search over the jumps alone.
 * Beside it a flood runs back from the goal over the same ways ({@link Backward}): the first side to run out of nodes
 * settles whether the goal is in reach at all, so a target shut off from the player (a quest's door, an island, an
 * instance) is proved out of reach after its own small area instead of after the whole world. Thread-safe: each search
 * keeps its own state; the maps are immutable (one's house is swapped whole).
 */
final class Pathfinder {
    /** Half ticks. */
    static final int RUN = 1, WALK = 2, DOOR = 2;
    /** Vines, jungle or a web: needs a tool, so walking around is better. */
    static final int OBSTACLE = 10, SAIL = CELL, STAIRS = 5;
    /** Through a solid object found by shape: only when no known way is much shorter. */
    static final int THROUGH = 30;
    static final String UNKNOWN_NEEDS = "May need a quest, level or item the map does not know";
    static final int SNAP_RADIUS = 12, LONG_JUMP = 24, BLOCK = 8;
    static final int[] DX = {1, -1, 0, 0, 1, 1, -1, -1};
    static final int[] DY = {0, 0, 1, -1, 1, -1, 1, -1};

    final CollisionMap map;
    final SeaMap sea;
    /** Every passage the planner knows besides the request's: stairs, entrances, map links and the like. */
    final List<Edge> passages;
    /** The passages that are Agility shortcuts, which a request adds only for a player who meets them. */
    final List<ShortcutPassage> shortcutPassages;
    final EdgeIndex passagesByOrigin;
    /** Where the Agility shortcuts start (shortcuts.tsv, the game's passages that are one): ways out for {@link #pocket}. */
    final IntMap shortcutStarts = new IntMap(1024);
    final EdgeIndex passagesByDestination;
    /** The jumps the heuristic must know when a tile costs a half tick (running, sailing) and when it costs one. */
    final List<Edge> heuristicPassages, walkingPassages;
    /**
     * Walking steps a gates.tsv row between two neighbouring tiles closes (a guild's door, which the game's collision has
     * as an ordinary door): per origin tile, a bit per direction of {@link #DX}; the row's own edge opens the step to
     * players who meet its needs. Read only after the constructor.
     */
    final IntMap closedSteps = new IntMap(64);
    /** The heuristic's jump bounds as last worked out, by goal and the request's jumps: re-plans and a tour's stops. */
    final Map<List<Integer>, int[][]> bounds = Collections.synchronizedMap(new LruMap<>(32, BOUNDS_KEPT));
    /** Enough for a tour's stops (up to 12) and the player's own target. */
    static final int BOUNDS_KEPT = 16;

    Pathfinder(CollisionMap map, SeaMap sea) {
        this.map = map;
        this.sea = sea;
        var edges = new ArrayList<Edge>();
        // Passages only from: the game's map links, hand links citing a source,
        // and cache passages: within SAME_SPOT, or far ones (into a dungeon, 6400 tiles north) that the collision tool
        // paired with a way back at the other end, unless a map link of the game's starts there and leads elsewhere.
        var mapLinks = links(map, DIR + "map_link_passages.tsv");
        var handLinks = links(map, DIR + "links.tsv");
        var gameLinks = gameLinkStarts();
        for (Transition t : map.transitions) {
            var kind = z(t.to) != t.plane && abs(y(t.to) - t.y) < 1000 ? Edge.Kind.STAIRS
                : Edge.Kind.ENTRANCE;
            String name = t.action + (t.name.isEmpty() ? "" : " " + t.name);
            var fee = Fees.at(t.x, t.y, t.plane);
            String detail = fee != null ? "Needs: " + fee.text
                : t.through ? UNKNOWN_NEEDS : null;
            int cost = STAIRS + (fee == null ? 0 : fee.cost) + (t.through ? THROUGH : 0);
            for (int origin : t.origins) {
                // A game map link starts within 4 tiles: where it leads is the game's word, not the cache's pairing.
                if (sameSpot(origin, t.to) || confirmed(mapLinks, origin, t.to) || confirmed(handLinks, origin, t.to)
                    || gameLinks.stream().noneMatch(s -> s[2] == z(origin) && abs(s[0] - x(origin)) <= 4
                        && abs(s[1] - y(origin)) <= 4)) {
                    edges.add(new Edge(origin, t.to, kind, name, detail, cost));
                }
            }
        }
        edges.addAll(mapLinks);
        edges.addAll(handLinks);
        edges.addAll(fromOpenGround(map, edges));
        var oneWay = OneWay.load();
        edges.removeIf(e -> oneWay.against(e.from, e.to));
        // A passage a gates.tsv row covers (a quest-locked city gate the game's map links have as open) is never free:
        // RouteSource offers the row's own edge to players who meet its needs.
        var gated = new ArrayList<int[]>();
        for (var parts : rows(DIR + "gates.tsv")) {
            int a = parts.length < 3 ? -1 : parse(parts[1]), b = a < 0 ? -1 : parse(parts[2]);
            if (b >= 0) {
                gated.add(new int[]{a, b});
                close(a, b);
            }
        }
        edges.removeIf(e -> gated.stream().anyMatch(g -> covers(g, e)));
        // Passages that are Agility shortcuts (shortcuts.tsv): added only to requests whose player meets the shortcut.
        var atShortcuts = new ArrayList<ShortcutPassage>();
        edges.removeIf(e -> {
            // Its needs are checked per request: no toll for needs the map would not know.
            var at = ShortcutPassage.of(UNKNOWN_NEEDS.equals(e.detail)
                ? new Edge(e.from, e.to, e.kind, e.name, null, e.cost - THROUGH) : e);
            return at != null && atShortcuts.add(at);
        });
        shortcutPassages = Collections.unmodifiableList(atShortcuts);
        ShortcutPassage.known().forEach(s -> shortcutStarts.put(s.origin, 0));
        atShortcuts.forEach(s -> shortcutStarts.put(s.edge.from, 0));
        passages = Collections.unmodifiableList(edges);
        passagesByOrigin = EdgeIndex.of(edges, 0, false);
        passagesByDestination = EdgeIndex.of(edges, 0, true);
        heuristicPassages = edges.stream().filter(e -> shortCut(e, RUN)).collect(toUnmodifiableList());
        walkingPassages = edges.stream().filter(e -> shortCut(e, WALK)).collect(toUnmodifiableList());
    }

    /** Lists the planner and its texts use (the map version's lists.tsv): by name, its items; the first of a name. */
    static final Table<Map<String, List<String>>> LISTS = new Table<>(() -> rows(
        DIR + "lists.tsv").stream().collect(toMap(c -> c[0], c -> List.of(c).subList(1, c.length),
        (a, b) -> a)));

    /** A list by its name; empty while the map version has none. */
    static List<String> list(String name) {
        return LISTS.get().getOrDefault(name, List.of());
    }

    /** A list's items as {@code parse} reads them; an item it cannot read (throws, gives null) is left out. */
    static <T> List<T> list(String name, Function<String, T> parse) {
        return each(list(name), parse);
    }

    /** A list of "key=value" items as a map, each value as {@code parse} reads it; the first of a key. */
    static <T> Map<String, T> pairs(String list, Function<String, T> parse) {
        return list(list, item -> {
            var p = item.split("=", 2);
            return Map.entry(p[0], parse.apply(p[1]));
        }).stream().collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));
    }

    /**
     * Whether a gates.tsv row (from, to) covers a passage: one going its own way, from near its start to near its end. Not
     * the way back: where a gate's two ends lie within 2 tiles of each other (Lletya's trees, Villa Lucens' entryway), the
     * passage out is near both ends too, and was taken for the gate's own (a row stands for one direction, as its closed
     * step does). Within a gate row's reach: the same floor, two tiles.
     */
    static boolean covers(int[] gate, Edge e) {
        return near(e.from, gate[0], 2) && near(e.to, gate[1], 2)
            && distance(e.from, gate[0]) <= distance(e.from, gate[1])
            && distance(e.to, gate[1]) <= distance(e.to, gate[0]);
    }

    /** Whether the step from {@code node} by (dx, dy) is one {@link #close} shut. */
    boolean closed(int node, int dx, int dy) {
        int closed = closedSteps.get(node);
        for (int d = 0; closed != MISSING && d < DX.length; d++) {
            if (DX[d] == dx && DY[d] == dy) {
                return (closed & 1 << d) != 0;
            }
        }
        return false;
    }

    /** A gates.tsv row between neighbouring tiles: that step is not walked, only taken by the row's edge. */
    void close(int from, int to) {
        if (z(from) != z(to) || distance(from, to) != 1) {
            return;
        }
        for (int d = 0; d < DX.length; d++) {
            if (x(to) - x(from) == DX[d] && y(to) - y(from) == DY[d]) {
                int known = closedSteps.get(from);
                closedSteps.put(from, (known == MISSING ? 0 : known) | 1 << d);
            }
        }
    }

    static boolean sameSpot(int from, int to) {
        return distance(from, to) <= SAME_SPOT;
    }

    /** A cache passage this near its start is the other side of an obstacle or the same spot on another floor. */
    static final int SAME_SPOT = 10;

    /** Where the game's own map links start (route/map_link_starts.tsv): x, y, plane. */
    static List<int[]> gameLinkStarts() {
        return table(DIR + "map_link_starts.tsv", parts -> {
            int start = parse(parts[0]);
            return start < 0 ? null : new int[]{x(start), y(start), z(start)};
        });
    }

    /**
     * A passage whose start is a tile shut in by walls (the snapped tile behind a cave mouth) is also taken from the
     * open tiles right beside it, where one stands to use it in the game.
     */
    static List<Edge> fromOpenGround(CollisionMap map, List<Edge> edges) {
        var open = new HashMap<Integer, Boolean>();
        var extra = new ArrayList<Edge>();
        for (Edge e : edges) {
            if (e.from < 0 || isSea(e.from) || open.computeIfAbsent(e.from, n -> reaches(map, n, at -> false))) {
                continue;
            }
            for (int d = 0; d < 8; d++) {
                int x = x(e.from) + DX[d], y = y(e.from) + DY[d], next = pack(x, y, z(e.from));
                if (map.walkable(x, y, z(e.from)) && open.computeIfAbsent(next, n -> reaches(map, n, at -> false))) {
                    extra.add(e.withFrom(next));
                }
            }
        }
        return extra;
    }

    /** Tiles a walk reaches out in the open, not shut in by walls. */
    static final int OPEN = 120;

    /** Whether walking from a tile reaches {@link #OPEN} tiles, or one that {@code way} leads out of. */
    static boolean reaches(CollisionMap map, int node, IntPredicate way) {
        // Run for every passage at start: no boxing.
        int[] queue = new int[OPEN];
        var seen = new IntMap(2 * OPEN);
        queue[0] = node;
        seen.put(node, 0);
        for (int head = 0, tail = 1; head < tail; head++) {
            int at = queue[head];
            if (way.test(at)) {
                return true;
            }
            for (int d = 0; d < 4; d++) {
                int next = pack(x(at) + DX[d], y(at) + DY[d], z(at));
                if (map.canStep(x(at), y(at), z(at), DX[d], DY[d]) && seen.get(next) == MISSING) {
                    seen.put(next, 0);
                    if (++tail >= OPEN) {
                        return true;
                    }
                    queue[tail - 1] = next;
                }
            }
        }
        return false;
    }

    static boolean confirmed(List<Edge> passages, int from, int to) {
        return passages.stream().anyMatch(e -> near(e.from, from, 4) && near(e.to, to, 16));
    }

    static List<Edge> links(CollisionMap map, String resource) {
        return table(resource, parts -> {
            int a = parts.length < 3 ? -1 : parse(parts[0]), b = a < 0 ? -1 : parse(parts[1]);
            int from = b < 0 ? -1 : map.nearestWalkable(x(a), y(a), z(a), 2);
            int to = from < 0 ? -1 : map.nearestWalkable(x(b), y(b), z(b), 2);
            // An optional fourth column: its cost in half ticks, where it takes longer than stairs.
            int cost = parts.length > 3 && parts[3].trim().matches("\\d{1,4}") ? Integer.parseInt(parts[3].trim()) : STAIRS;
            return to < 0 ? null : new Edge(from, to, Edge.Kind.ENTRANCE, parts[2].trim(), null, cost);
        });
    }

    /** A bundled table's lines split at tabs, without {@code #} comments; what could be read. */
    static List<String[]> rows(String resource) {
        var rows = new ArrayList<String[]>();
        InputStream in = RouteFiles.open(resource);
        if (in != null) {
            try {
                rows(in, 0, rows::add);
            } catch (IOException e) {
                // Those read so far.
            }
        }
        return rows;
    }

    /** Each row of a bundled table as {@code parse} reads it; a row it cannot read (throws, gives null) is left out. */
    static <T> List<T> table(String resource, Function<String[], T> parse) {
        return each(rows(resource), parse);
    }

    static <I, T> List<T> each(List<I> items, Function<I, T> parse) {
        var read = new ArrayList<T>();
        for (var item : items) {
            try {
                T row = parse.apply(item);
                if (row != null) {
                    read.add(row);
                }
            } catch (RuntimeException e) {
                // That one only.
            }
        }
        return Collections.unmodifiableList(read);
    }

    /** Each line but {@code #} comments, split as {@code line.split("\t", limit)} does; closes {@code in}. */
    static void rows(InputStream in, int limit, Consumer<String[]> each) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (!line.startsWith("#")) {
                    each.accept(line.split("\t", limit));
                }
            }
        }
    }

    /** A cell's space-separated integers ("2920 4700" {@literal ->} {2920, 4700}). */
    static int[] ints(String cell) {
        return Arrays.stream(cell.trim().split("\\s+")).mapToInt(Integer::parseInt).toArray();
    }

    /** Folded y: most dungeons lie 6400 tiles north of what is above them. */
    static int fy(int node) {
        int y = y(node);
        return y >= 6400 ? y - 6400 : y;
    }

    /** Round the fold: one step from y 6399 to 6400 goes from the top of the folded range to its bottom. */
    static int folded(int a, int b) {
        int dy = abs(fy(a) - fy(b));
        return max(abs(x(a) - x(b)), min(dy, 6400 - dy));
    }

    /** Jumps the heuristic must know (a tile costing {@code step}), else it would overestimate and miss the best way. */
    static boolean shortCut(Edge e, int step) {
        if (e.from == Edge.ANYWHERE) {
            return false;
        }
        int length = folded(e.from, e.to);
        return length > LONG_JUMP || e.cost < length * step;
    }

    /** {@code out}: more ways out of a pocket than the passages (a request's own: fairy rings, boats, shortcuts). */
    int resolveGoal(int target, IntPredicate out) {
        if (isSea(target)) {
            return target;
        }
        int x = x(target), y = y(target), z = z(target);
        if (map.walkable(x, y, z)) {
            return target;
        }
        // No ground on this floor around the spot, but the very spot is ground on another floor (a dungeon's raised part
        // the map shows as floor 1, ours as floor 2: the Brimhaven Dungeon's at 2633 9491): that floor's spot.
        if (map.nearestWalkable(x, y, z, 2) < 0) {
            for (int dz = 1; dz < 4; dz++) {
                for (int other : new int[]{z - dz, z + dz}) {
                    if (other >= 0 && other <= 3 && map.walkable(x, y, other) && !pocket(pack(x, y, other), out)) {
                        return pack(x, y, other);
                    }
                }
            }
        }
        int near = nearestOpen(x, y, z, SNAP_RADIUS, out);
        if (near >= 0 && !pocket(near, out)) {
            return near;
        }
        if (z == 0 && sea != null && sea.sailable(x / CELL, y / CELL, 99)) {
            // Open water with only an islet or a ledge near (a sea creature): sailed to, not walked to the islet.
            return seaAt(x, y);
        }
        // Only a pocket here: the spot may be given on another floor than the ground it marks.
        int there = otherFloor(x, y, z, 3, true, out);
        if (there >= 0 || near >= 0) {
            return there >= 0 ? there : near;
        }
        // A spot given on the wrong floor: the same spot on another floor first.
        near = otherFloor(x, y, z, 1, false, out);
        if (near < 0) {
            near = otherFloor(x, y, z, SNAP_RADIUS, false, out);
        }
        return near;
    }

    /** {@code open}: the nearest open non-pocket tile, else any walkable one. */
    int otherFloor(int x, int y, int z, int radius, boolean open, IntPredicate out) {
        for (int dz = 1; dz < 4; dz++) {
            for (int other : new int[]{z - dz, z + dz}) {
                int near = other < 0 || other > 3 ? -1
                    : open ? nearestOpen(x, y, other, radius, out) : map.nearestWalkable(x, y, other, radius);
                if (near >= 0 && !(open && pocket(near, out))) {
                    return near;
                }
            }
        }
        return -1;
    }

    /** A start the data calls unwalkable (a boat's deck, a stepping stone) begins from the nearest open tile. */
    int startNode(int start) {
        if (start < 0 || isSea(start) || map.walkable(x(start), y(start), z(start))) {
            return start;
        }
        int near = nearestOpen(x(start), y(start), z(start), 5, at -> false);
        return near >= 0 ? near : start;
    }

    /** Nearest walkable non-pocket tile round (not on) the spot, else the first walkable one met, else -1. */
    int nearestOpen(int x, int y, int z, int radius, IntPredicate out) {
        int open = map.nearest(x, y, z, 1, radius, x, y, at -> pocket(at, out), false);
        return open >= 0 ? open : map.nearest(x, y, z, 1, radius, x, y, null, true);
    }

    /** A small shut-in area (behind bank booths) with no passages; ground left by an Agility shortcut is not one. */
    boolean pocket(int node) {
        return pocket(node, at -> false);
    }

    boolean pocket(int node, IntPredicate out) {
        return !reaches(map, node, at -> passagesByOrigin.get(at) != null || shortcutStarts.get(at) != MISSING
            || out.test(at));
    }

    /** Where a request's own jumps start (fairy rings, boats, a shortcut's icon): ways out of a pocket for its target. */
    static IntPredicate waysOut(RouteRequest request) {
        var starts = new IntMap(64);
        request.edges.forEach(e -> starts.put(e.from, 0));
        return at -> starts.get(at) != MISSING;
    }

    Route find(RouteRequest request, BooleanSupplier cancelled) {
        return find(request, cancelled, Integer.MAX_VALUE);
    }

    /**
     * The same, giving up (as at the node limit, without a verdict) once no way under {@code costLimit} half ticks can
     * be found: looking for the quickest of several places, a place that cannot beat the best so far stops early.
     */
    Route find(RouteRequest request, BooleanSupplier cancelled, int costLimit) {
        return outside(request, kept(request, cancelled, costLimit), cancelled, costLimit);
    }

    Route kept(RouteRequest request, BooleanSupplier cancelled, int costLimit) {
        Route route = walkOrSail(request, cancelled, costLimit);
        // Kept out of the Wilderness, yet no way around it gets there (the Mage Arena's bank, Entrana's dungeon without a
        // boat): through it after all, and the route says so.
        // Not when it only stopped at a cost limit: a way around may cost more, and the Wilderness is no cheaper way.
        if (avoids(request) && route.keptOut && (route.outcome == NEAREST
            || route.outcome == NONE) && !route.limited && !cancelled.getAsBoolean()) {
            Route through = walkOrSail(request.throughWilderness(), cancelled, costLimit);
            // Out of reach only if the search through it tried everything too; then its proof is the one kept (the one
            // kept out counts every Wilderness tile as out of reach, so a way in there would not be searched again).
            return through.outcome == FOUND ? through.withThroughWilderness(true) : through.outcome == CANCELLED ? route
                : through.exhausted ? route.withReached(through.reached) : route.withExhausted(false).withReached(null);
        }
        return route;
    }

    /**
     * A target the search proved shut off (ground the game never lets one walk, past a cave's wall: an icon drawn there):
     * the route to the nearest open ground outside it instead, which that search gave up on before getting to.
     */
    Route outside(RouteRequest request, Route route, BooleanSupplier cancelled, int costLimit) {
        int target = request.target;
        if (route.outcome != NEAREST || route.reached == null || !route.reached.complement || target < 0 || isSea(target)) {
            return route;
        }
        // The first open tile round the target that the target is not reached from.
        int near = map.nearest(x(target), y(target), z(target), 1, SNAP_RADIUS, 0, 0,
            at -> !route.reached.contains(at) || pocket(at) || cancelled.getAsBoolean(), true);
        if (near < 0) {
            return route;
        }
        var ways = route.throughWilderness ? request.throughWilderness() : request;
        Route there = walkOrSail(ways.between(request.start, near).withBank(request.bank), cancelled, costLimit);
        // Still out of reach itself: the proof stays, so it is not searched again from here.
        return there.outcome != FOUND ? route : there.near(route);
    }

    Route walkOrSail(RouteRequest request, BooleanSupplier cancelled, int costLimit) {
        Route route = search(request, cancelled, costLimit);
        // Left without their boat (a place on land): sail only when walking does not get there (an island).
        if (request.boat != null && (route.outcome == NEAREST || route.outcome == NONE) && !route.limited
            && !cancelled.getAsBoolean()) {
            Route sailed = search(request.boat, cancelled, costLimit);
            if (sailed.outcome == FOUND) {
                return sailed;
            }
            Route walked = sailed.keptOut && !route.keptOut ? route.withKeptOut(true) : route;
            // Out of reach only if the boat's search tried everything too (not when it stopped at a limit).
            return sailed.outcome == CANCELLED || sailed.exhausted ? walked : walked.withExhausted(false).withReached(null);
        }
        return route;
    }

    /** Whether a search for this request stays out of the Wilderness: asked to, and it neither starts nor ends there. */
    boolean avoids(RouteRequest request) {
        int start = startNode(request.start);
        return request.avoidWilderness && !wilderness(request.target) && !(start >= 0 && wilderness(start));
    }

    Route search(RouteRequest request, BooleanSupplier cancelled, int costLimit) {
        int target = request.target, goal = resolveGoal(target, waysOut(request));
        var all = new ArrayList<Edge>(passages.size() + request.edges.size() + request.startEdges.size());
        all.addAll(passages);
        int requestOffset = all.size();
        all.addAll(request.edges);
        int startOffset = all.size();
        all.addAll(request.startEdges);
        var requestByOrigin = EdgeIndex.of(request.edges, requestOffset, false);
        int towards = goal >= 0 ? goal : target, step = request.running ? RUN : WALK;
        // Walking, each tile costs a whole tick; at sea a half one (a block of 4 tiles costs 2 ticks).
        int scale = request.sailingLevel < 0 ? step : RUN;
        var jumps = new ArrayList<Edge>(scale == RUN ? heuristicPassages : walkingPassages);
        var key = new ArrayList<Integer>(List.of(towards, scale));
        for (Edge e : request.edges) {
            if (shortCut(e, scale)) {
                jumps.add(e);
                key.addAll(List.of(e.from, e.to, e.cost));
            }
        }
        // Worked out without the map's lock: a tour's searches must not hold up the player's.
        var made = bounds.get(key);
        if (made == null) {
            made = Heuristic.bounds(jumps, towards, scale);
            bounds.putIfAbsent(key, made);
        }
        var heuristic = new Heuristic(towards, scale, made[0], made[1]);

        var nodes = new NodeTable();
        var open = new LongHeap();
        int start = startNode(request.start);
        // Out of the Wilderness, unless the route starts or ends there (from a house, start is -1: not in it).
        boolean avoid = avoids(request);
        if (start >= 0) {
            relax(nodes, open, heuristic, start, 0, NodeTable.START);
        }
        // Whether a step or jump into the Wilderness was left out (else going through it gets nowhere further).
        boolean keptOut = jumps(IntStream.range(startOffset, all.size()).toArray(), all, nodes, open, heuristic, 0, avoid);
        // Back from the goal, in turns with the search: once it runs out, the goal is in reach from exactly what it
        // flooded. No goal (no ground near the target): out of reach from anywhere.
        var back = goal >= 0 ? new Backward(goal, all, requestOffset, request, avoid) : null;
        Reached proof = goal >= 0 ? null : new Reached(new NodeSet(), true);
        int stopAt = goal >= 0 ? Integer.MAX_VALUE : NEAREST_NODES, best = -1;
        long bestScore = Long.MAX_VALUE;
        int popped = 0;
        boolean limited = false;
        int found = -1;
        while (!open.isEmpty() && popped < stopAt) {
            long item = open.pop();
            int node = (int) item, g = nodes.cost(node);
            if ((int) (item >>> 32) != g + heuristic.h(node)) {
                continue;
            }
            if ((int) (item >>> 32) > costLimit) {
                // Every way left costs at least this (the heuristic never overestimates): nothing under the limit.
                limited = true;
                break;
            }
            if ((++popped & (TURN - 1)) == 0) {
                if (cancelled.getAsBoolean()) {
                    return new Route(CANCELLED, List.of(), 0, target, -1, false, false, popped);
                }
                if (back != null && back.flood(TURN)) {
                    if (back.reaches(start, startOffset)) {
                        // In reach: the search alone finds the way.
                        back = null;
                    } else {
                        // Out of reach: on a little further for the nearest spot the way gets to, then done.
                        proof = new Reached(back.seen, true);
                        keptOut |= back.keptOut;
                        stopAt = popped + NEAREST_NODES;
                        back = null;
                    }
                }
            }
            if (node == goal) {
                found = node;
                break;
            }
            if (!isSea(node)) {
                long score = (long) (distance(node, target) + (z(node) != z(target) ? 32 : 0)) << 32 | g;
                if (score < bestScore) {
                    bestScore = score;
                    best = node;
                }
            }
            if (nodes.size > request.nodeLimit) {
                limited = true;
                break;
            }
            if (isSea(node)) {
                int cx = cellX(node), cy = cellY(node);
                for (int d = 0; d < 8; d++) {
                    int nx = cx + DX[d], ny = cy + DY[d];
                    if (sea.sailable(nx, ny, request.sailingLevel)) {
                        relax(nodes, open, heuristic, sea(nx, ny), g + SAIL, -1 - d);
                    }
                }
            } else {
                int x = x(node), y = y(node), z = z(node), closed = closedSteps.get(node);
                for (int d = 0; d < 8; d++) {
                    if (closed != MISSING && (closed & 1 << d) != 0) {
                        continue;
                    }
                    if (map.canStep(x, y, z, DX[d], DY[d])) {
                        int next = pack(x + DX[d], y + DY[d], z);
                        if (avoid && wilderness(next)) {
                            keptOut = true;
                            continue;
                        }
                        int cost = step + (d < 4 && map.door(x, y, z, DX[d], DY[d]) ? DOOR : 0)
                            + (map.isObstacle(x + DX[d], y + DY[d], next) ? OBSTACLE : 0);
                        relax(nodes, open, heuristic, next, g + cost, -1 - d);
                    }
                }
                keptOut |= jumps(passagesByOrigin.get(node), all, nodes, open, heuristic, g, avoid);
            }
            keptOut |= jumps(requestByOrigin.get(node), all, nodes, open, heuristic, g, avoid);
        }
        // Tried everything, or proved from the goal's side: what was reached (or not) tells where else the target is out
        // of reach.
        boolean tried = !limited && open.isEmpty(), exhausted = found < 0 && (proof != null || tried);
        Reached reached = found >= 0 ? null : tried ? new Reached(nodes.reached(), false) : proof;
        int end = found >= 0 ? found : best;
        var steps = end < 0 ? List.<Step>of() : steps(nodes, all, startOffset, end, found >= 0 ? target : -1, avoid);
        Route route = end < 0 ? new Route(NONE, steps, 0, target, -1, exhausted, limited, popped, reached)
            : new Route(found >= 0 ? FOUND : NEAREST, steps, nodes.cost(end), target,
            steps.isEmpty() ? end : steps.get(steps.size() - 1).last(), exhausted, limited, popped, reached);
        return keptOut ? route.withKeptOut(true) : route;
    }

    /** Nodes the search pops between turns of the flood back from the goal (and checks for a cancel); a power of two. */
    static final int TURN = 4096;
    /**
     * Nodes the search goes on for once the goal is proved out of reach, for the nearest spot it gets to (the far side
     * of a locked door): the way there is what the player sees. A flood of the world would be about 1.7 million.
     */
    static final int NEAREST_NODES = 100_000;

    /**
     * The nodes the goal can be reached from, flooded back from it over the same ways the search takes forward (walking,
     * sailing, passages, the request's jumps), a turn at a time. Done, it holds them all: the goal is in reach from the
     * start or a teleport's landing only if they are in it.
     */
    final class Backward {
        final NodeSet seen = new NodeSet();
        int[] queue = new int[TURN];
        int head, tail;
        final List<Edge> all;
        final EdgeIndex requestByDestination;
        final int sailingLevel;
        final boolean avoid;
        /** A step or jump out of the Wilderness was left out: through it, the goal may be in reach from more. */
        boolean keptOut;

        Backward(int goal, List<Edge> all, int requestOffset, RouteRequest request, boolean avoid) {
            this.all = all;
            requestByDestination = EdgeIndex.of(request.edges, requestOffset, true);
            sailingLevel = request.sailingLevel;
            this.avoid = avoid;
            visit(goal);
        }

        void visit(int node) {
            if (seen.add(node)) {
                if (tail == queue.length) {
                    if (head > queue.length / 2) {
                        System.arraycopy(queue, head, queue, 0, tail - head);
                        tail -= head;
                        head = 0;
                    } else {
                        queue = Arrays.copyOf(queue, queue.length * 2);
                    }
                }
                queue[tail++] = node;
            }
        }

        /** Floods up to {@code budget} nodes more; whether it is done (nothing more leads to the goal). */
        boolean flood(int budget) {
            while (head < tail && budget-- > 0) {
                int node = queue[head++];
                if (isSea(node)) {
                    // Sailed into from any block around it, when it can be sailed.
                    int cx = cellX(node), cy = cellY(node);
                    if (sailingLevel >= 0 && sea.sailable(cx, cy, sailingLevel)) {
                        for (int d = 0; d < 8; d++) {
                            visit(sea(cx + DX[d], cy + DY[d]));
                        }
                    }
                } else {
                    int x = x(node), y = y(node), z = z(node);
                    for (int d = 0; d < 8; d++) {
                        int px = x - DX[d], py = y - DY[d];
                        if (!map.canStep(px, py, z, DX[d], DY[d])) {
                            continue;
                        }
                        int from = pack(px, py, z), closed = closedSteps.get(from);
                        if (closed == MISSING || (closed & 1 << d) == 0) {
                            visitFrom(from);
                        }
                    }
                }
                jumpsTo(passagesByDestination.get(node));
                jumpsTo(requestByDestination.get(node));
            }
            return head == tail;
        }

        void jumpsTo(int[] edges) {
            if (edges != null) {
                for (int i : edges) {
                    int from = all.get(i).from;
                    if (from != Edge.ANYWHERE) {
                        visitFrom(from);
                    }
                }
            }
        }

        /** Kept out of the Wilderness, the search never stands there: not a node the goal is reached from. */
        void visitFrom(int from) {
            if (avoid && wilderness(from)) {
                keptOut = true;
            } else {
                visit(from);
            }
        }

        /** Done: whether the goal is in reach from the start or from where a jump from anywhere lands. */
        boolean reaches(int start, int startOffset) {
            if (start >= 0 && seen.contains(start)) {
                return true;
            }
            for (int i = startOffset; i < all.size(); i++) {
                int to = all.get(i).to;
                if (!(avoid && wilderness(to)) && seen.contains(to)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Relaxes the jumps; whether one into the Wilderness was left out. */
    static boolean jumps(int[] edges, List<Edge> all, NodeTable nodes, LongHeap open, Heuristic heuristic, int g,
        boolean avoidWilderness) {
        if (edges == null) {
            return false;
        }
        boolean keptOut = false;
        for (int i : edges) {
            Edge e = all.get(i);
            if (!(avoidWilderness && wilderness(e.to))) {
                relax(nodes, open, heuristic, e.to, g + e.cost, i);
            } else {
                keptOut = true;
            }
        }
        return keptOut;
    }

    static void relax(NodeTable nodes, LongHeap open, Heuristic heuristic, int to, int cost, int via) {
        if (cost < nodes.cost(to)) {
            nodes.put(to, cost, via);
            open.push(key(cost + heuristic.h(to), to));
        }
    }

    static long key(int f, int node) {
        return (long) f << 32 | (node & 0xffffffffL);
    }

    /** The node before on the way found: where its jump or step began; -1 at the start (or a jump from anywhere). */
    static int parent(NodeTable nodes, List<Edge> all, int startOffset, int node) {
        int via = nodes.via(node), d = -1 - via;
        return via >= startOffset || via == NodeTable.START ? -1 : via >= 0 ? all.get(via).from : isSea(node)
            ? sea(cellX(node) - DX[d], cellY(node) - DY[d])
            : pack(x(node) - DX[d], y(node) - DY[d], z(node));
    }

    List<Step> steps(NodeTable nodes, List<Edge> all, int startOffset, int end, int target, boolean avoid) {
        // The walk drawn as the game walks it keeps to the steps the search kept to: no shut gate, no Wilderness.
        Shut shut = (x, y, z, dx, dy) -> closed(pack(x, y, z), dx, dy)
            || avoid && wilderness(pack(x + dx, y + dy, z));
        var reversed = new ArrayList<Step>();
        var run = new ArrayList<Integer>();
        int node = end;
        run.add(node);
        while (true) {
            int via = nodes.via(node), parent = parent(nodes, all, startOffset, node);
            if (via >= 0) {
                flush(reversed, run, nodes, shut);
                Edge e = all.get(via);
                int from = parent >= 0 ? parent : node;
                reversed.add(new Step(Step.Kind.valueOf(e.kind.name()), new int[]{from, node}, e.name,
                    e.detail, e.time, 0, List.of(), e.category));
                run.clear();
            }
            if (parent < 0) {
                break;
            }
            run.add(parent);
            node = parent;
        }
        flush(reversed, run, nodes, shut);
        Collections.reverse(reversed);
        int x = x(target), y = y(target), z = z(target);
        // A target one cannot stand on (a ladder clicked on the map): the free tiles beside it with no wall between.
        int[] beside = target < 0 || isSea(target) || map.walkable(x, y, z) ? new int[0] : IntStream.range(0, 4)
            .filter(d -> map.walkable(x + DX[d], y + DY[d], z) && map.straightOpen(x + DX[d], y + DY[d], z, -DX[d], -DY[d]))
            .map(d -> pack(x + DX[d], y + DY[d], z)).toArray();
        for (int i = 1; i <= reversed.size(); i++) {
            // Where the game walks as a passage (its sides) or that target is clicked where the walk to it starts.
            var walk = reversed.get(i - 1);
            var jump = i < reversed.size() ? reversed.get(i) : null;
            int[] game = walk.kind != Step.Kind.WALK ? null : path(map, shut, walk.first(), jump == null ? beside
                : all.stream().filter(e -> e.from >= 0 && e.to == jump.last() && Objects.equals(e.name, jump.name))
                .mapToInt(e -> e.from).toArray());
            if (game != null && game.length <= walk.points.length) {
                int doors = (int) IntStream.range(1, game.length).filter(k -> map.door(x(game[k - 1]), y(game[k - 1]),
                    z(game[k]), x(game[k]) - x(game[k - 1]), y(game[k]) - y(game[k - 1]))).count();
                reversed.set(i - 1, new Step(Step.Kind.WALK, game, null, walk.detail,
                    walk.cost * (game.length - 1) / (walk.points.length - 1), doors, walk.obstacles, null));
                if (jump != null) {
                    jump.points[0] = game[game.length - 1];
                }
            }
        }
        return reversed;
    }

    /** {@code run} is collected backwards. */
    void flush(List<Step> reversed, List<Integer> run, NodeTable nodes, Shut shut) {
        if (run.size() >= 2) {
            int[] points = new int[run.size()];
            for (int i = 0; i < points.length; i++) {
                points[i] = run.get(points.length - 1 - i);
            }
            int cost = nodes.cost(points[points.length - 1]) - nodes.cost(points[0]);
            boolean sailing = isSea(points[0]);
            var obstacles = new ArrayList<Obstacle>();
            StringBuilder detail = new StringBuilder();
            if (!sailing) {
                points = straighten(map, shut, follow(map, shut, points));
                for (int point : points) {
                    String obstacle = map.obstacle(point);
                    if (obstacle != null) {
                        obstacles.add(new Obstacle(point, obstacle));
                        if (detail.indexOf(obstacle) < 0) {
                            detail.append(detail.length() > 0 ? "; " : "").append(obstacle);
                        }
                    }
                }
            }
            // Doors on the walk as drawn.
            int doors = 0;
            for (int i = 1; i < points.length; i++) {
                int a = points[i - 1], b = points[i], dx = x(b) - x(a), dy = y(b) - y(a);
                if (!isSea(a) && (dx == 0 || dy == 0) && map.door(x(a), y(a), z(a), dx, dy)) {
                    doors++;
                }
            }
            reversed.add(new Step(sailing ? Step.Kind.SAIL : Step.Kind.WALK, points, null,
                sailing ? hazards(points) : detail.length() > 0 ? detail.toString() : null, cost, doors, obstacles, null));
        }
        // Only the last point stays.
        run.subList(0, max(0, run.size() - 1)).clear();
    }

    String hazards(int[] points) {
        var names = new LinkedHashSet<String>();
        for (int i = 0; i < points.length; i += 3) {
            var area = sea.nearest(x(points[i]), y(points[i]));
            if (area != null && area.level > 0 && !area.hazard.isEmpty()) {
                names.add(area.hazard + " (" + area.name + ", " + area.level + " Sailing)");
            }
        }
        return names.isEmpty() ? null : "Crosses " + String.join(", ", names);
    }

    /** Folded distance, or via a jump's backward-searched bound; jump bounds cached per 8×8 block. */
    @RequiredArgsConstructor
    static final class Heuristic {
        final int goal;
        /** What a tile walked costs at least. */
        final int scale;
        final int[] origins, bounds;
        /** Per 128 × 128 folded tiles, its 8 × 8 blocks' bounds once worked out (-1 not yet): a flood needs 30,000. */
        final int[][] blocks = new int[1 << 14][];

        /** Per jump origin, the least cost from there to the goal by jumps, walking between them as the crow flies. */
        static int[][] bounds(List<Edge> jumps, int goal, int scale) {
            int n = jumps.size();
            long[] bound = new long[n];
            boolean[] done = new boolean[n];
            int[] from = new int[n], to = new int[n], cost = new int[n];
            for (int i = 0; i < n; i++) {
                Edge e = jumps.get(i);
                from[i] = e.from;
                to[i] = e.to;
                cost[i] = e.cost;
                bound[i] = (long) e.cost + scale * folded(e.to, goal);
            }
            // Backward Dijkstra over the jumps.
            for (int round = 0; round < n; round++) {
                int pick = -1;
                for (int i = 0; i < n; i++) {
                    if (!done[i] && (pick < 0 || bound[i] < bound[pick])) {
                        pick = i;
                    }
                }
                done[pick] = true;
                for (int i = 0; i < n; i++) {
                    bound[i] = done[i] ? bound[i]
                        : min(bound[i], cost[i] + scale * folded(to[i], from[pick]) + bound[pick]);
                }
            }
            // Each origin once, in the order first met, with its least bound.
            var byOrigin = new LinkedHashMap<Integer, Integer>();
            for (int i = 0; i < n; i++) {
                byOrigin.merge(from[i], (int) min(Integer.MAX_VALUE, bound[i]), Math::min);
            }
            return new int[][]{byOrigin.keySet().stream().mapToInt(Integer::intValue).toArray(),
                byOrigin.values().stream().mapToInt(Integer::intValue).toArray()};
        }

        int h(int node) {
            int direct = scale * folded(node, goal);
            if (origins.length == 0) {
                return direct;
            }
            int bx = x(node) / BLOCK, by = fy(node) / BLOCK, block = (bx >> 4) << 7 | by >> 4;
            int cell = (bx & 15) << 4 | by & 15;
            var area = blocks[block];
            if (area == null) {
                area = new int[256];
                Arrays.fill(area, -1);
                blocks[block] = area;
            }
            int cached = area[cell];
            if (cached < 0) {
                int cx = bx * BLOCK + BLOCK / 2, cy = by * BLOCK + BLOCK / 2, min = Integer.MAX_VALUE;
                for (int i = 0; i < origins.length; i++) {
                    int d = max(abs(x(origins[i]) - cx), abs(fy(origins[i]) - cy));
                    min = min(min, scale * max(0, d - BLOCK / 2) + bounds[i]);
                }
                cached = min;
                area[cell] = cached;
            }
            return min(direct, cached);
        }
    }
}
