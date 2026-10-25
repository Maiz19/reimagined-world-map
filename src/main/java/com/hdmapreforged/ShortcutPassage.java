package com.hdmapreforged;

import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.stream.*;
import lombok.*;

/**
 * A planner passage that is an Agility shortcut (shortcuts.tsv). It knows nothing of the shortcut's level, so
 * RouteSource adds it per request only when the requirements are met.
 */
final class ShortcutPassage {
    /** How near the shortcut's start (or end) the passage must be, in tiles. */
    static final int RADIUS = 3;

    final Edge edge;
    /**
     * Each row's Needs columns (skills, items, quests, varbits), first row first: any one met will do (a grapple
     * shortcut with its grapple, or bare-handed at a higher Agility level).
     */
    final List<String[]> ways;

    ShortcutPassage(Edge edge, List<Shortcut> shortcuts) {
        this.edge = edge;
        ways = shortcuts.stream().map(s -> s.way).collect(Collectors.toUnmodifiableList());
    }

    @RequiredArgsConstructor
    static final class Shortcut {
        /** Lower case: "Rocks (Waterbirth Island)" is "rocks". */
        final String object;
        final int origin, destination;
        final String[] way;

        /** It starts or ends here, and its name names the object (looked at only then: 15,000 passages, 200 shortcuts). */
        boolean stands(Edge e) {
            return e.name != null && (near(e.from, origin) || near(e.to, origin) || destination >= 0
                && (near(e.from, destination) || near(e.to, destination))) && e.name.toLowerCase(Locale.ROOT).contains(object);
        }

        static boolean near(int a, int b) {
            return Tiles.near(a, b, RADIUS);
        }
    }

    static final RouteFiles.Table<List<Shortcut>> ALL = new RouteFiles.Table<>(ShortcutPassage::shortcuts);

    /**
     * The Agility shortcut a passage crosses (one of the cache's, say), with what the shortcut needs; null when it
     * crosses none.
     */
    static ShortcutPassage of(Edge edge) {
        var standing = known().stream().filter(s -> s.stands(edge)).collect(Collectors.toList());
        return standing.isEmpty() ? null : new ShortcutPassage(edge, standing);
    }

    /** Every row's Needs columns (skills, items, quests, varbits): what the requirements read must watch. */
    static List<String[]> allWays() {
        return known().stream().map(s -> s.way).collect(Collectors.toList());
    }

    static List<Shortcut> known() {
        return ALL.get();
    }

    /** "Rocks (Waterbirth Island)" → "rocks"; "Pillar Jump (Easy) (Revenant Caves)" → "pillar". */
    static String object(String name) {
        String plain = name.replaceAll("\\s*\\(.*$", "").trim().toLowerCase(Locale.ROOT);
        return plain.startsWith("pillar jump") ? "pillar" : plain;
    }

    static List<Shortcut> shortcuts() {
        InputStream in = RouteFiles.open("/com/hdmapreforged/data/shortcuts.tsv");
        if (in == null) {
            return List.of();
        }
        var list = new ArrayList<Shortcut>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String[] header = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("# Name")) {
                    header = line.substring(2).split("\t", -1);
                    continue;
                }
                if (line.startsWith("#") || line.trim().isEmpty() || header == null) {
                    continue;
                }
                var cells = line.split("\t", -1);
                String object = object(cell(header, cells, "Name"));
                int origin = Tiles.parse(cell(header, cells, "Origin"));
                // An empty object name would match every edge nearby.
                if (origin < 0 || object.isEmpty()) {
                    continue;
                }
                list.add(new Shortcut(object, origin, Tiles.parse(cell(header, cells, "Destination")), new String[]{
                    cell(header, cells, "Skills"), cell(header, cells, "Items"), cell(header, cells, "Quests"),
                    cell(header, cells, "Varbits")}));
            }
        } catch (IOException | RuntimeException e) {
            // Those read so far.
        }
        return list;
    }

    static String cell(String[] header, String[] cells, String column) {
        for (int i = 0; i < header.length; i++) {
            if (header[i].trim().equals(column)) {
                return i < cells.length ? cells[i].trim() : "";
            }
        }
        return "";
    }
}
