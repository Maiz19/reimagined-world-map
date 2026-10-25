package com.hdmapreforged;

import java.util.*;
import java.util.stream.*;
import lombok.*;

/** Immutable; {@code withX}: the same with another field. */
@With
@AllArgsConstructor
final class RouteRequest {
    /**
     * Past it the search stops where it is, without a verdict. A flood of the world is about 1.7 million nodes (and
     * the search table about 100 MB), which only a far trip with no way there at all still takes: a target shut off
     * from the player is proved out of reach from its own side (Pathfinder's flood back from the goal).
     */
    static final int DEFAULT_NODE_LIMIT = 3_000_000;

    /** -1 when the route can only start with {@link #startEdges} (in a house). */
    final int start, target;
    /** Teleports, and the ways out of a player-owned house. */
    final List<Edge> startEdges, edges;
    /** -1 when they cannot sail their own boat. */
    final int sailingLevel;
    final boolean running;
    final int nodeLimit;
    /**
     * Stay out of the Wilderness, unless the route starts or ends in it; when no way around it gets there, the route goes
     * through it ({@link #throughWilderness()}).
     */
    final boolean avoidWilderness;
    /** The same with their boat, searched when this one (left without it) does not arrive; else null. */
    final RouteRequest boat;
    /** A way past a bank first, for what is kept there (setting); else null. */
    final BankDetour bank;

    RouteRequest(int start, int target, List<Edge> startEdges, List<Edge> edges, int sailingLevel, boolean running,
        int nodeLimit) {
        this(start, target, startEdges, edges, sailingLevel, running, nodeLimit, false);
    }

    RouteRequest(int start, int target, List<Edge> startEdges, List<Edge> edges, int sailingLevel, boolean running,
        int nodeLimit, boolean avoidWilderness) {
        this(start, target, Collections.unmodifiableList(startEdges), Collections.unmodifiableList(edges), sailingLevel,
            running, nodeLimit, avoidWilderness, null, null);
    }

    /** The same ways between other ends, with no bank first. */
    RouteRequest between(int start, int target) {
        return withStart(start).withTarget(target).withBank(null)
            .withBoat(boat == null ? null : boat.between(start, target));
    }

    /** The same ways, through the Wilderness too: searched when staying out of it does not arrive. */
    RouteRequest throughWilderness() {
        return withAvoidWilderness(false).withBoat(boat == null ? null : boat.throughWilderness());
    }

    /** Whether a search may use the same ways as with {@code other} (jumps and sea): what it reaches differs by start only. */
    boolean sameWays(RouteRequest other) {
        return sailingLevel == other.sailingLevel && avoidWilderness == other.avoidWilderness
            && (boat == null) == (other.boat == null) && same(edges, other.edges)
            && same(startEdges, other.startEdges);
    }

    static boolean same(List<Edge> a, List<Edge> b) {
        return a.size() == b.size() && IntStream.range(0, a.size())
            .allMatch(i -> a.get(i).from == b.get(i).from && a.get(i).to == b.get(i).to);
    }
}
