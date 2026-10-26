package com.hdmapreforged;

import java.awt.Color;
import java.util.*;
import lombok.*;
import net.runelite.api.coords.*;

/** Where a monster or NPC is, as places of close spawns, and what it drops ({@link WorldData#spawns}); also a kind's places. */
@RequiredArgsConstructor
final class Spawns {
    @RequiredArgsConstructor
    static final class Group {
        final String name, location;
        final List<WorldPoint> points;
        final String category;
        /** What the list says of it ("7 here", "4 spots"); empty or null for nothing. */
        final String note;
        // Not in search2.json.
        final transient Color color;
        transient Poi poi;
        /** How many stand here (see {@link WorldData#spawns}); 0 when not counted. */
        int seen;
        /** The way in to a boss fought in an instance, not where it stands. */
        boolean entrance;
        /** A shop's stock of the item searched ({@link WorldData#ENDLESS} for endless); 0 for other places. */
        int stock;

        Group(String name, String location, List<WorldPoint> points) {
            this(name, location, points, null, null, null);
        }

        Group withNote(String note) {
            return copy(location, note);
        }

        /** The same place under a place name ("Waterfall Dungeon"). */
        Group at(String place) {
            return copy(place, note);
        }

        Group copy(String place, String text) {
            Group group = new Group(name, place, points, category, text, color);
            group.poi = poi;
            group.seen = seen;
            group.entrance = entrance;
            group.stock = stock;
            return group;
        }

        WorldPoint center() {
            return Spawns.center(points);
        }
    }

    /** The point nearest the middle of them all, so a mark sits on a real tile; null for none. */
    static WorldPoint center(List<WorldPoint> points) {
        double x = points.stream().mapToInt(WorldPoint::getX).average().orElse(0);
        double y = points.stream().mapToInt(WorldPoint::getY).average().orElse(0);
        return points.stream().min(Comparator.comparingDouble(p -> (p.getX() - x) * (p.getX() - x)
            + (p.getY() - y) * (p.getY() - y))).orElse(null);
    }

    final String page;
    final List<Group> groups;
    /** The kind of the name most seen, whose picture stands for them all; -1 for none. */
    final int npc;
    /** The page's drop tables, the standard one first, the items named; empty when its drops are not known. */
    final List<WorldData.Table> tables;
}
