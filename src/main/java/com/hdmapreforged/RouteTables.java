package com.hdmapreforged;

import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * A map version's route tables as Gson binds them (route.zip's tables.json, worked out once by the map server's build):
 * lists, own ways, banks, ports, areas, and the jumps alike for every player (teleport landings, transport stops, gates,
 * sea passages: ends snapped to ground, named, priced), each with its needs, which {@link RouteSource} checks per player.
 */
final class RouteTables {
    static final String FILE = "tables.json";

    /** Lists of names (charged, house features, staff runes, jewellery box teleports...), by name. */
    Map<String, List<String>> lists = Map.of();
    /** A charged teleport's group to its item without limit; a portal's place to the spell's; our place to the box's. */
    Map<String, String> unlimited = Map.of(), portalNames = Map.of(), box = Map.of();
    /** A planted spirit tree's patch: its varbit, then its regions. */
    Map<String, int[]> patches = Map.of();
    /** A combination rune's id to the two runes it counts as. */
    Map<Integer, int[]> combinations = Map.of();
    /** A Sailing port (SAILING_BOAT_n_PORT value) to where a boat lies there. */
    Map<Integer, Integer> ports = Map.of();
    /** Own ways (kind, name, skills, items, quests, varbits, short name, tablet or outside); combination runes by name. */
    String[][] own = {}, combined = {};
    /** Every Needs the jumps and shortcuts name (skills, items, quests, varbits), the first none. */
    String[][] needs = {{"", "", "", ""}};
    /**
     * Banks (icon, stand, booth); instance copies (min x y, max x y, by x y); the Wilderness's safe zones; house
     * portals (x y) by varbit 2187.
     */
    int[][] banks = {}, copies = {}, zones = {}, housePortals = {};
    /** The varbits of the portal nexus' 45 slots, in order. */
    int[] nexusSlots = {};
    List<ReturnAreas.Area> returns = List.of();
    /** Every part of the world map listed, and those drawn elsewhere. */
    List<WorldMapMoves.Move> allMoves = List.of(), moves = List.of();
    List<SeaMap.Area> seas = List.of();
    List<Jump> gates = List.of(), seaPassages = List.of();
    /** The places' jumps by the place's name. */
    Map<String, List<Jump>> places = Map.of();
    transient List<Needs> made = List.of();

    static final class Jump {
        /** The place's tile and type: with its name, the place it is a jump of. */
        int at;
        PoiType type;
        int from = Edge.ANYWHERE, to, cost, needs, poiNeeds;
        /** Of one of several places alike (name, type, tile): the trip's destination ({@link Poi.Link#point}) it is. */
        int via;
        /** {@code tree}: the planted spirit tree it goes to. */
        String name, category, group, fee, tree, note;
        /** The house's way to the place (its fairy ring, spirit tree or obelisk), and what it uses there as the game says it ("Fairy ring BKS"). */
        boolean house;
        String inHouse;
        /** A fairy ring's codes in the order dialled. */
        List<String> codes;
        transient Needs need, poiNeed;

        /** Whether it is this place's (found by its name). */
        boolean of(Poi place) {
            return at == RouteFeature.pack(place.location) && type == place.type && (via == 0 || place.needs.equals(poiNeed)
                && place.links().stream().anyMatch(link -> RouteFeature.pack(link.point) == via && link.needs.equals(need)));
        }
    }

    /** As read: each jump with its Needs. */
    RouteTables ready() {
        made = Arrays.stream(needs).map(n -> new Needs(n[0], n[1], n[2], n[3])).collect(Collectors.toUnmodifiableList());
        Stream.of(gates, seaPassages).flatMap(List::stream).forEach(this::link);
        // Looked up per place and request: hashed, not the tree Gson binds.
        places = new HashMap<>(places);
        places.values().forEach(list -> list.forEach(this::link));
        return this;
    }

    void link(Jump jump) {
        jump.need = made.get(jump.needs);
        jump.poiNeed = made.get(jump.poiNeeds);
    }

    List<String> list(String name) {
        return lists.getOrDefault(name, List.of());
    }

    /** Each rule's pattern compiled once, by its row. */
    static final Map<String, Pattern> PATTERNS = new ConcurrentHashMap<>();

    /**
     * Rule list {@code name} (lists.tsv, rows "pattern=replacement"): the replacement of each row whose pattern matches
     * all of {@code text}, case aside, up to the first unless {@code all}; an empty one says none.
     */
    List<String> rules(String name, String text, boolean all) {
        var said = new ArrayList<String>();
        for (String row : list(name)) {
            int eq = row.lastIndexOf('=');
            Matcher m = PATTERNS.computeIfAbsent(row, r -> Pattern.compile(r.substring(0, eq), Pattern.CASE_INSENSITIVE
                | Pattern.UNICODE_CASE)).matcher(text);
            if (m.matches()) {
                var out = new StringBuilder();
                m.appendReplacement(out, row.substring(eq + 1));
                if (out.length() > 0) {
                    said.add(out.toString());
                }
                if (!all) {
                    break;
                }
            }
        }
        return said;
    }
}
