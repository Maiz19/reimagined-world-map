package com.hdmapreforged;

import lombok.*;

/** A non-walking jump in the route graph; costs in half game ticks. */
@AllArgsConstructor(access = AccessLevel.PRIVATE)
final class Edge {
    enum Kind {
        STAIRS,
        ENTRANCE,
        TELEPORT,
        TRANSPORT,
        SHIP,
        BOARD,
        DISEMBARK,
        HOUSE
    }

    static final int ANYWHERE = -1;

    /** {@code withFrom}: the same from another start. */
    @With
    final int from;
    final int to;
    final Kind kind;
    final String name, detail;
    /** As the search weighs it: {@link #time} plus any extra the player asked for. */
    @With(AccessLevel.PRIVATE)
    final int cost;
    final int time;
    final String category;

    Edge(int from, int to, Kind kind, String name, String detail, int cost) {
        this(from, to, kind, name, detail, cost, null);
    }

    Edge(int from, int to, Kind kind, String name, String detail, int cost, String category) {
        this(from, to, kind, name, detail, Math.max(1, cost), Math.max(1, cost), category);
    }

    /** Heavier in the search only ("only use a teleport when it saves at least 10 tiles"). */
    Edge weighed(int extra) {
        return extra <= 0 ? this : withCost(cost + extra);
    }

    public String toString() {
        return kind + " " + name + " " + (from == ANYWHERE ? "anywhere" : Tiles.format(from)) + " -> " + Tiles.format(to);
    }
}
