package com.hdmapreforged;

import static java.util.Arrays.*;
import static java.util.stream.Collectors.*;
import static com.hdmapreforged.Pathfinder.*;
import static com.hdmapreforged.Tiles.*;
import static java.util.Locale.ROOT;
import static com.hdmapreforged.PoiType.*;
import static com.hdmapreforged.RouteFeature.pack;

import com.hdmapreforged.Edge.Kind;
import com.hdmapreforged.Route.Said;
import com.hdmapreforged.RouteTables.Jump;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.regex.*;
import java.util.stream.*;
import lombok.*;
import net.runelite.api.coords.*;

/** The jumps a route may take for this player. Thread-safe: requests come from Swing and the extras thread. */
@RequiredArgsConstructor
final class RouteSource {
    static final int SNAP = 5, DOCK_REACH = 18, PORT_RADIUS = 24;
    /** How far from its dock a port's boat lies, at most (Port Piscarilius: 34 tiles). */
    static final int BOAT_REACH = 40;
    static final Pattern SAILING = Pattern.compile("\\b(\\d{1,3})\\s+Sailing", Pattern.CASE_INSENSITIVE);

    @RequiredArgsConstructor
    static final class Options {
        final boolean teleports;
        @With
        final boolean sailing;
        /** Where shipwrights stand (not changed once given). */
        final int[] shipwrights;
        final boolean ignoreLevels, ignoreItems;
        final Set<String> avoid;
        final Saving saving;
        /** The way anyone could go, shown when the player's way does not arrive (not the "ignore" settings). */
        final boolean fallback;
        /** Stay out of the Wilderness unless the route starts or ends there. */
        final boolean noWild;
        /** Sail also to places on land that can be walked to; else only from or to the sea, or where walking does not get. */
        @With
        final boolean sailToLand;
    }

    /** Tiles a kind of trip must save to be taken (a teleport for a few tiles wastes runes); search weight only. */
    @RequiredArgsConstructor
    static final class Saving {
        static final Saving NONE = new Saving(0, 0, 0, 0, 0, 0);

        final int teleport, shortcut, transport, canoe, carpet, ship;

        /** By {@link PoiType#saving}: none, or the shortcut, transport, canoe, carpet or ship setting. */
        int tiles(PoiType type) {
            return new int[]{0, shortcut, transport, canoe, carpet, ship}[type.saving];
        }
    }

    /** How near a dock a shipwright, or a port where one's boat may lie, must be to count as there. */
    static final int SHIPWRIGHT_REACH = 40;

    final CollisionMap map;
    final SeaMap sea;
    /** The planner's passages that are Agility shortcuts ({@link Pathfinder#shortcutPassages}). */
    final List<ShortcutPassage> shortcuts;
    /**
     * {@link Pathfinder#pocket}: a jump's end snaps to open ground, not a shut-in tile beside it, from which no route goes
     * on (Tal Teklan's quetzal, several fairy rings).
     */
    final IntPredicate pocket;
    /** The game's item names, for what a step lacks ("3200 × Coins"); null: "items you do not have". */
    volatile IntFunction<String> itemNames;

    /** Tiles walked at most to get out to a teleport's Wilderness level (the Wilderness is about 450 by 450). */
    static final int WALK_OUT = 300_000;
    /** Snapped ends by spot (and the spot to keep away from): the map does not change, and requests snap thousands. */
    final Map<Long, Integer> snaps = new ConcurrentHashMap<>();
    /** Where the walk out of the Wilderness gets to, by start and level: the map does not change. */
    final Map<Long, Integer> walkOuts = new ConcurrentHashMap<>();

    RouteRequest request(int start, int target, List<Poi> pois, Unlocks unlocks, PlayerState state, Options options,
        int nodeLimit) {
        if (options.sailing && !options.sailToLand && !isSea(target) && !(start >= 0 && isSea(start))) {
            // A place on land: walk; the boat only when walking does not get there.
            var walking = request(start, target, pois, unlocks, state, options.withSailing(false), nodeLimit);
            var sailing = request(start, target, pois, unlocks, state, options.withSailToLand(true), nodeLimit);
            return walking == null || sailing == null ? sailing : walking.withBoat(sailing);
        }
        if (start >= 0 && (HousePlan.contains(start) || state.inHouse)) {
            // On the plan of one's house: in it, also before the player's state (read every few ticks) says so. A start
            // off it (a custom route's leg from a stop, the ticks after leaving) is outside any house: the player's own
            // in a friend's house or one not planned yet starts nowhere (-1), as the house's ways out then do.
            state = state.inOwnHouse(HousePlan.contains(start));
        }
        return new Call(options, unlocks, state).request(start, target, pois, nodeLimit);
    }

    /** Half ticks (tiles run) a spell or item in the house must be quicker than its own ways. */
    static final int HOUSE_FIRST = 10;

    final class Call {
        final Unlocks realUnlocks, missingLevels;
        final ItemSnapshot missingItems;
        /** What may be used: carried, and with the bank's items too. */
        final ItemSnapshot items;
        final int realSailing;
        final long minute = System.currentTimeMillis() / 60_000;

        final IntFunction<String> itemNames;
        final RouteTables tables = RouteFiles.tables();

        final Options options;
        /** As the options make them: no unlocks when levels are ignored, every item when items are. */
        final Unlocks unlocks;
        final PlayerState state;

        Call(Options options, Unlocks unlocks, PlayerState state) {
            this.options = options;
            itemNames = RouteSource.this.itemNames;
            realUnlocks = unlocks;
            missingLevels = options.ignoreLevels ? unlocks : null;
            missingItems = options.ignoreItems ? state.items : null;
            items = state.items;
            realSailing = !options.ignoreLevels ? 99 : state.sailing ? state.sailingLevel : 0;
            this.unlocks = options.ignoreLevels ? null : unlocks;
            state = options.ignoreLevels ? state.with(state.items, 99, true) : state;
            this.state = options.ignoreItems ? state.with(ItemSnapshot.EVERYTHING, state.sailingLevel, state.sailing) : state;
        }

        /** Whether the route leaves out a way by its name or by its kind; either may be null. */
        boolean avoids(String name, String category) {
            return name != null && options.avoid.contains(name.toLowerCase(ROOT))
                || category != null && options.avoid.contains(typeKey(category));
        }

        RouteRequest request(int start, int target, List<Poi> pois, int nodeLimit) {
            var startEdges = new ArrayList<Edge>();
            var edges = new ArrayList<Edge>();
            var teleportsByName = new HashMap<String, Jump>();
            // The house's ways to the places (their fairy rings, spirit trees, obelisks).
            var homes = new ArrayList<Jump>();
            for (var poi : Poi.flatten(pois)) {
                if (poi.type == MOORING) {
                    if (options.sailing) {
                        mooring(poi, start >= 0 && isSea(start), edges);
                    }
                    continue;
                }
                // The place's jumps as the map version's build made them (a teleport stack's: each teleport's).
                for (var place : poi.type == TELEPORT ? poi.members() : List.of(poi)) {
                    for (var jump : tables.places.getOrDefault(place.name, List.of())) {
                        if (!jump.of(place) || jump.type != TELEPORT && notPlanted(place)) {
                            continue;
                        }
                        if (jump.type == TELEPORT) {
                            teleportsByName.putIfAbsent(jump.name.toLowerCase(ROOT), jump);
                            if (options.teleports) {
                                teleport(jump, startEdges, edges);
                            }
                        } else if (jump.house) {
                            homes.add(jump);
                        } else {
                            transport(jump, edges);
                        }
                    }
                }
            }
            gates(edges);
            shortcuts(edges);
            if (options.sailing && state.sailing) {
                seaPassages(edges);
            }
            // In an area whose way out leads back to where one came from (the Rune essence mine, the Shipyard): its exits,
            // there, when the player was seen coming in.
            var back = start >= 0 ? ReturnAreas.at(start) : null;
            if (back != null && state.returnExit >= 0) {
                for (int exit : back.exits) {
                    int from = snap(RouteFeature.worldPoint(exit));
                    if (from >= 0) {
                        edges.add(new Edge(from, state.returnExit, Kind.ENTRANCE, back.exitName, null, 6));
                    }
                }
            }
            if (options.sailing && options.teleports) {
                teleportToBoat(startEdges);
            }
            if (state.inHouse || options.teleports) {
                house(homes, teleportsByName, startEdges, edges);
            }
            // No teleport above Wilderness level 30, above 20 only dragonstone jewellery and the royal seed pod: deeper
            // in, they go from where the walk first gets to their level.
            int wild = start >= 0 && !isSea(start) ? wildernessLevel(x(start), y(start)) : 0;
            if (wild > 20 && !startEdges.isEmpty()) {
                var upTo30 = list("teleports to level 30");
                int at20 = walkedOut(start, 20), at30 = wild > 30 ? walkedOut(start, 30) : start;
                for (var e : List.copyOf(startEdges)) {
                    // A way without a kind (Teleport to Boat) is none of the level 30 teleports.
                    int from = e.category != null && upTo30.contains(e.category) ? at30 : at20;
                    if (from != start) {
                        startEdges.remove(e);
                        if (from >= 0) {
                            edges.add(e.withFrom(from));
                        }
                    }
                }
            }
            if (!options.avoid.isEmpty()) {
                // Also the house's ways (its jewellery box, portals), by name or kind as the route's steps show them.
                Predicate<Edge> avoided = e -> avoids(e.name, e.category);
                startEdges.removeIf(avoided);
                edges.removeIf(avoided);
            }
            if (state.inHouse) {
                // In the house its own portal, box or amulet goes before a spell or item, unless that is over 10 tiles quicker.
                startEdges.replaceAll(e -> e.kind == Kind.TELEPORT ? e.weighed(HOUSE_FIRST) : e);
            }
            if (start < 0 && startEdges.isEmpty()) {
                return null;
            }
            int level = options.sailing && state.sailing ? state.sailingLevel : -1;
            if (start >= 0 && isSea(start) && level < 0) {
                // On a boat without the Sailing data: still let the route sail home.
                level = Math.max(1, state.sailingLevel);
            }
            return new RouteRequest(start, target, startEdges, edges, level, state.running, nodeLimit, options.noWild);
        }

        void gates(List<Edge> into) {
            for (var gate : tables.gates) {
                // Its quests and levels, and the items it takes (a key, a skavid map and a light) carried or worn.
                if (meets(gate.need)) {
                    into.add(new Edge(gate.from, gate.to, Kind.ENTRANCE, gate.name, null, 4).saying(said(null, gate.need)));
                }
            }
        }

        /** The sea caves and the like (sea_passages.tsv) between blocks of sea the player's boat can sail. */
        void seaPassages(List<Edge> into) {
            for (var passage : tables.seaPassages) {
                if (usable(passage.need) && sea.sailable(cellX(passage.from), cellY(passage.from), state.sailingLevel)
                    && sea.sailable(cellX(passage.to), cellY(passage.to), state.sailingLevel)) {
                    // What it needs, and what the step should say besides (the boat's kind).
                    into.add(new Edge(passage.from, passage.to, Kind.ENTRANCE, passage.name, null, 10)
                        .saying(said(null, passage.need).and(passage.note)));
                }
            }
        }

        void shortcuts(List<Edge> into) {
            for (var passage : shortcuts) {
                // Any of its ways the player meets: with a grapple, or bare-handed at a higher level.
                for (var way : passage.ways) {
                    Needs needs = new Needs(way[0], way[1], way[2], way[3]);
                    if (meets(needs)) {
                        Edge e = passage.edge;
                        into.add(new Edge(e.from, e.to, e.kind, e.name, null, e.cost).saying(said(null, needs)));
                        break;
                    }
                }
            }
        }

        boolean usable(Needs needs) {
            return unlocks == null || unlocks.usable(needs);
        }

        /** Usable, and its items carried or worn. */
        boolean meets(Needs needs) {
            return usable(needs) && state.items.has(needs.items);
        }

        /**
         * A teleport from anywhere ({@code into}); in a house a minigame teleport only from outside its portal
         * ({@code edges}): the game does not let one cast it in a player-owned house.
         */
        void teleport(Jump jump, List<Edge> into, List<Edge> edges) {
            if (!meets(jump.need) || unlocks != null && unlocks.waiting(jump.name, jump.group, minute)) {
                return;
            }
            int lacking = lacking(jump.need);
            // Outside one's own house only (a friend's house tells nothing of it).
            if (jump.to < 0 || jump.name.contains("(Outside)") && (state.houseExit < 0 || state.inHouse && !state.ownHouse
                || distance(jump.to, state.houseExit) > PORT_RADIUS)) {
                return;
            }
            String name = jump.name.toLowerCase(ROOT);
            boolean once = onceOnly(name, jump.group);
            if (avoids(name, jump.category) || once && options.avoid.contains(ONCE_ONLY)) {
                return;
            }
            boolean outside = state.inHouse && "Minigame teleports".equals(jump.group);
            int from = outside && state.houseExit >= 0 ? snap(RouteFeature.worldPoint(state.houseExit)) : Edge.ANYWHERE;
            if (outside && from < 0) {
                return;
            }
            (outside ? edges : into).add(new Edge(from, jump.to, Kind.TELEPORT, jump.name, null, jump.cost, jump.category)
                .saying(said(TELEPORT, jump.need)).weighed(options.saving.teleport + lacking + (charged(jump.group, state) ? 1 : 0)));
        }

        /** {@link #walkOut}, once per start and level. */
        int walkedOut(int start, int level) {
            return walkOuts.computeIfAbsent((long) start << 32 | level, k -> walkOut(start, level));
        }

        /** The tile nearest on foot where the Wilderness is at most {@code level} (a teleport's limit); else -1. */
        int walkOut(int start, int level) {
            return Math.max(-1, flood(map, start, at -> wildernessLevel(x(at), y(at)) <= level,
                WALK_OUT, 8));
        }

        void transport(Jump jump, List<Edge> into) {
            // Only what is carried: a route never fetches an item (a fare's coins, a fairy ring's staff) from the bank.
            // (The bank only as a way past it first: BankDetour, a setting.)
            if (!usable(jump.poiNeed) || !carried(jump.type, jump.poiNeed) || !usable(jump.need)
                || !carried(jump.type, jump.need) || jump.tree != null && !plantedTrees.contains(jump.tree)
                || avoids(jump.name, jump.type.displayName)) {
                return;
            }
            var needs = said(jump.type, jump.need).withCodes(jump.codes);
            if (jump.fee != null) {
                // The fee apart: it cannot be checked, so it is not something the player lacks.
                needs = needs.text() == null ? needs.withNote("Needs: " + jump.fee) : needs.and("Also needs: " + jump.fee);
            }
            into.add(new Edge(jump.from, jump.to, jump.type.kind, jump.name, null, jump.cost, jump.type.displayName)
                .saying(needs).weighed(options.saving.tiles(jump.type)));
        }

        /**
         * A dock: leaving the boat needs its Sailing level; boarding needs the boat there, a shipwright to bring it, or a
         * port when no boat's port is known. {@code onBoat}: the route starts at sea on the boat, so it lies at no dock.
         */
        void mooring(Poi poi, boolean onBoat, List<Edge> into) {
            if (!state.sailing || state.sailingLevel < level(poi.needs) || !usable(poi.needs)) {
                return;
            }
            int x = poi.location.getX(), y = poi.location.getY(), land = map.walkableNear(x, y, 0, 8);
            int water = sea.nearestBlock(x, y, DOCK_REACH, state.sailingLevel);
            if (land < 0 || water < 0) {
                return;
            }
            into.add(new Edge(water, land, Kind.DISEMBARK, "Dock at " + poi.name + dockLevel(poi), null, 10));
            int needed = Math.max(1, level(poi.needs));
            String name = t("rsBoard", poi.name);
            String hint = null;
            int cost = 10;
            String summon = stream(state.boatFocus).anyMatch(focus -> focus >= 1) ? boatSpell("summon boat") : null;
            if (stream(state.boats).anyMatch(boat -> distance(boat, land) <= BOAT_REACH)) {
                name += dockLevel(poi);
            } else if (summon != null) {
                name = t("rsSummon", poi.name);
                hint = summon;
                cost = 16;
            } else if (stream(options.shipwrights).anyMatch(s -> distance(s, land) <= SHIPWRIGHT_REACH)) {
                hint = t("shipwright");
                cost = 60;
            } else if (state.boats.length == 0 && !onBoat
                && tables.ports.values().stream().anyMatch(port -> distance(port, land) <= SHIPWRIGHT_REACH)) {
                // Where the boat lies is not known yet (not logged in): a port, where it may lie; not any dock.
                hint = t("boatMaybe");
            } else {
                return;
            }
            var board = new Edge(land, water, Kind.BOARD, name, hint, cost);
            into.add(realSailing >= needed ? board
                : board.saying(Said.NONE.withLack(realSailing <= 0 ? "Sailing" : needed + " Sailing")));
        }

        /** How the player casts a boat spell now: its tablet, the spell itself, or null when neither. */
        String boatSpell(String kind) {
            var spell = own(kind).stream().findFirst().orElse(null);
            return spell == null ? null : state.items.has(spell[7]) ? spell[1]
                : meets(needs(spell)) ? "Spell (standard spellbook)" : null;
        }

        /** To each boat with the greater teleport focus: by the Sailing cape, a tablet or the spell, when the player can. */
        void teleportToBoat(List<Edge> into) {
            String means = !state.sailing ? null : own("sailing cape").stream().filter(c -> state.items.has(c[3]))
                .map(c -> c[1]).findFirst().orElseGet(() -> boatSpell("boat teleport"));
            for (int i = 0; means != null && i < state.boats.length; i++) {
                int land = i >= state.boatFocus.length || state.boatFocus[i] < 2 ? -1
                    : map.walkableNear(x(state.boats[i]), y(state.boats[i]), 0, 12);
                if (land >= 0) {
                    into.add(new Edge(Edge.ANYWHERE, land, Kind.TELEPORT, "Teleport to Boat", means, 10));
                }
            }
        }

        /**
         * Whether what a step needs is carried. A fairy ring's staff (Dramen or Lunar) is not needed once the Lumbridge &
         * Draynor Elite Diary is done.
         */
        boolean carried(PoiType type, Needs needs) {
            return state.items.has(needs.items) || type == FAIRY_RING && unlocks != null && unlocks.lumbridgeElite();
        }

        /** The fallback takes a teleport whose item the player lacks only as a last resort (else all of it would be red). */
        int lacking(Needs needs) {
            return options.fallback && missingItems != null && !needs.items.isEmpty()
                && !missingItems.has(needs.items) ? LACKING_ITEM : 0;
        }

        /**
         * The house's ways out. With its plan seen (own house) they stand where they are on it, routes walk to them and
         * teleport in to its arrival; else, in the house, the route starts with them, and from outside a teleport in or
         * its portal leads to them at once.
         */
        void house(List<Jump> homes, Map<String, Jump> teleports, List<Edge> starts, List<Edge> edges) {
            var plan = state.inHouse && !state.ownHouse ? null : map.house;
            var into = new ArrayList<Edge>();
            var portal = state.houseExit < 0 ? null : new WorldPoint(x(state.houseExit), y(state.houseExit), 0);
            if (state.inHouse && portal != null && (plan == null || !plan.things.containsValue("Portal"))) {
                addHouse(into, snap(portal), t("rsLeave"), 6, Said.NONE, 0);
            }
            var placed = new HashSet<String>();
            if (plan != null) {
                plan.things.forEach((node, name) -> {
                    // Each side: the route takes the one the game walks to as it is clicked (Pathfinder.steps).
                    int[] at = plan.approach(node);
                    var out = new ArrayList<Edge>();
                    List<String> features = name.startsWith(NEXUS + ": ") ? stream(name.substring(NEXUS.length() + 2)
                        .split(", ")).map(place -> "nexus:" + place).collect(toList())
                        : name.startsWith(NEXUS) ? List.of() : Collections.singletonList(HouseTracker.feature(name));
                    // A spiritual fairy tree is both: of it what the settings have (only its ring, say).
                    if (features.contains(HouseSettings.BOTH) && !state.houseFeatures.contains(HouseSettings.BOTH)) {
                        features = HouseSettings.PARTS;
                    }
                    // As set for the account (the panel may have taken one off); one not reachable is used from the arrival.
                    // Each of several alike (two spirit trees) from where it stands: the route takes the nearest.
                    for (String feature : features) {
                        if (feature != null && at.length > 0 && HouseSettings.has(state.houseFeatures, feature)) {
                            placed.add(feature);
                            ways(feature, homes, teleports, out);
                        }
                    }
                    if (name.equals("Portal") && portal != null) {
                        addHouse(out, snap(portal), t("rsLeave"), 6, Said.NONE, 0);
                    }
                    for (int dz = -1; stairs(name) && dz <= 1; dz += 2) {
                        int other = z(node) + dz;
                        // Only to a floor with stairs where these stand: they go up or down, seldom both.
                        if (plan.things.entrySet().stream().anyMatch(t -> z(t.getKey()) == other
                            && distance(t.getKey(), node) <= 3 && stairs(t.getValue()))) {
                            out.add(new Edge(Edge.ANYWHERE, map.walkableNear(x(node), y(node), other, 3),
                                Kind.STAIRS, name, null, 5));
                        }
                    }
                    for (Edge e : out) {
                        for (int side : e.to >= 0 ? at : new int[0]) {
                            edges.add(e.withFrom(side));
                        }
                    }
                });
            }
            // Not seen on the plan (typed in by hand), or no plan: usable at once in the house.
            for (String feature : state.inHouse && !state.ownHouse ? Set.<String>of() : state.houseFeatures) {
                // "Both" is a ring and a tree, maybe in two gardens: only a part not on the plan is used at once.
                boolean both = feature.equals(HouseSettings.BOTH) && !placed.contains(feature);
                for (String part : both ? HouseSettings.PARTS : List.of(feature)) {
                    if (!HouseSettings.has(placed, part)) {
                        ways(part, homes, teleports, into);
                    }
                }
            }
            if (plan != null) {
                into.forEach(e -> edges.add(e.withFrom(plan.arrival)));
                into.clear();
            }
            if (state.inHouse) {
                starts.addAll(into);
                return;
            }
            String[] best = null;
            int lacking = 0;
            for (var way : own("house")) {
                Needs needs = needs(way);
                int lack = lacking(needs);
                // Set to land outside, the capes do so; the spell and the tablet keep an option to land inside.
                if ((state.landsInside || !way[7].equals("outside")) && meets(needs) && (best == null || lack < lacking)
                    && !avoids(way[1] + (state.landsInside ? "" : " (inside)"), "Teleports")) {
                    best = way;
                    lacking = lack;
                }
            }
            int door = portal == null ? -1 : snap(portal);
            String teleport = best == null ? null : best[1] + (state.landsInside ? "" : " (Inside)");
            if (plan != null) {
                if (best != null) {
                    starts.add(new Edge(Edge.ANYWHERE, plan.arrival, Kind.TELEPORT, teleport, null, 10, HOUSE)
                        .saying(said(TELEPORT, needs(best)).withBrief(best[6])).weighed(options.saving.teleport + lacking));
                }
                if (door >= 0) {
                    edges.add(new Edge(door, plan.arrival, Kind.ENTRANCE, "Enter your house", null, 6, HOUSE));
                }
                return;
            }
            for (Edge e : into) {
                if (best != null) {
                    // Both ways' ticks, and both ways' "only when it saves" weights.
                    starts.add(new Edge(Edge.ANYWHERE, e.to, Kind.HOUSE, teleport + " → " + e.name, null, 10 + e.time, HOUSE)
                        .saying(both(said(TELEPORT, needs(best)), e.said).withInto(teleport).withBrief(best[6])).weighed(e.cost - e.time + options.saving.teleport + lacking));
                }
                if (door >= 0) {
                    edges.add(new Edge(door, e.to, Kind.HOUSE, "Enter your house → " + e.name, e.detail,
                        6 + e.cost, HOUSE).withSaid(e.said.withInto("Enter your house")));
                }
            }
        }

        /** Where a feature of the house ("portal:Varrock", "box:ornate", "glory", "xeric", "fairy ring") leads, from anywhere. */
        void ways(String feature, List<Jump> homes, Map<String, Jump> teleports, List<Edge> into) {
            int mounted = asList(HouseSettings.MOUNTED).indexOf(feature);
            if (feature.startsWith("portal:") || feature.startsWith("nexus:")) {
                String place = feature.substring(feature.indexOf(':') + 1);
                var teleport = portalTeleport(place, teleports);
                if (teleport != null) {
                    // Named as the portal is ("Troll Stronghold"), not as the teleport that says where it leads.
                    boolean nexus = feature.startsWith("nexus:");
                    addHouse(into, teleport.to, (nexus ? "Portal nexus: " : "House portal: ") + place, 6,
                        Said.NONE.withInHouse((nexus ? "Portal nexus " : "Portal ") + place), options.saving.teleport);
                }
            } else if (feature.startsWith("box:") || mounted >= 0) {
                var groups = mounted >= 0 ? List.of(HouseSettings.MOUNTED_GROUPS[mounted])
                    : list("jewellery box teleports").stream().limit(feature.equals("box:basic") ? 2
                    : feature.equals("box:ornate") ? 6 : 4).collect(toList());
                String kind = mounted >= 0 ? MOUNTED_NAMES[mounted] : "Jewellery box: ";
                for (var teleport : teleports.values()) {
                    if (teleport.group != null && groups.contains(teleport.group) && usable(teleport.need)) {
                        // As the game says it: the box's key and name for our place ("Jewellery box 5 Burthorpe").
                        String place = teleport.name.substring(teleport.name.lastIndexOf(": ") + 2);
                        String keyed = RouteFiles.tables().box.get(place.toLowerCase(ROOT));
                        addHouse(into, teleport.to, kind + teleport.name, 8, Said.NONE.withInHouse(kind.replace(": ", " ")
                            + (keyed == null || mounted > 0 ? place : mounted == 0 ? keyed.substring(2) : keyed))
                            .withBoxed(keyed == null || mounted >= 0 ? null : "Jwlry box " + keyed.charAt(0)),
                            options.saving.teleport);
                    }
                }
            }
            for (var home : homes) {
                // An obelisk as those in the Wilderness: to the one one chooses, with the Hard Wilderness Diary. The
                // house's ring needs the staff as any ring does.
                boolean obelisk = home.type == OBELISK && feature.equals("obelisk");
                if ((obelisk || home.type == FAIRY_RING && feature.contains("fairy ring") || home.type == SPIRIT_TREE
                    && feature.contains("spirit tree")) && usable(home.need) && (obelisk || carried(home.type, home.need))) {
                    addHouse(into, home.to, home.name, home.cost, Said.NONE.withBank(obelisk ? null
                        : bankNeeds(home.type, home.need)).withInHouse(home.inHouse).withCodes(home.codes),
                        options.saving.tiles(home.type));
                }
            }
        }

        /** {@code weight}: the way's "only when it saves" setting, as the same way outside the house carries it. */
        void addHouse(List<Edge> into, int to, String name, int cost, Said said, int weight) {
            if (to >= 0) {
                // One kind (as minigame teleports are), to leave all out at once; the way out of the house is none.
                into.add(new Edge(Edge.ANYWHERE, to, Kind.HOUSE, name, null, cost, name.contains(": ")
                    ? "House teleports" : null).saying(said).weighed(weight));
            }
        }

        Said needsText(Needs needs) {
            if (needs.isEmpty()) {
                return Said.NONE;
            }
            if (missingLevels == null && missingItems == null) {
                return Said.NONE.withNote(uncheckedText(realUnlocks, needs));
            }
            var parts = new ArrayList<String>();
            if (missingLevels != null) {
                parts.addAll(unmetLines(missingLevels, needs));
            }
            List<Integer> unnamed = null;
            if (missingItems != null && !missingItems.has(needs.items)) {
                parts.add(missingItems(missingItems, needs.items, itemNames));
                // Those the game names only once asked ("Item 995").
                unnamed = Requirements.itemIds(needs.items).stream().filter(id -> itemNames != null
                    && Requirements.itemName(id + "", itemNames).equals("Item " + id)).collect(toList());
            }
            return parts.isEmpty() ? Said.NONE : Said.NONE.withLack(String.join(", ", parts)).withUnnamed(unnamed);
        }

        /** What a step needs, said: from the bank ({@link #bankNeeds}), else {@link #needsText}. */
        Said said(PoiType type, Needs needs) {
            var bank = bankNeeds(type, needs);
            return bank != null ? Said.NONE.withBank(bank) : needsText(needs);
        }

        /**
         * What a step needs from the bank, when the bank is counted and what is carried is not enough; else null. A fairy
         * ring needs no staff after the Lumbridge & Draynor Elite Diary.
         */
        List<String> bankNeeds(PoiType type, Needs needs) {
            boolean enough = state.carried == null || state.carried.has(needs.items)
                || type == FAIRY_RING && unlocks != null && unlocks.lumbridgeElite();
            if (enough) {
                return null;
            }
            // Of each part not carried, the alternatives the bank holds, by name ("Dramen staff", "3 × Air rune").
            var lines = itemNames == null ? List.<String>of() : Requirements.items(stream(ItemSnapshot.ALL.split(needs.items))
                .filter(all -> !state.carried.has(all)).map(all -> stream(ItemSnapshot.ANY.split(all)).filter(items::has)
                    .findFirst().map(String::trim).orElse(all)).collect(joining("&&")), itemNames);
            return lines.isEmpty() ? BankDetour.UNNAMED : lines;
        }
    }

    /**
     * The needs of the map version's jumps, gates, shortcuts and own ways (a house tablet), whose quests and varbits the
     * requirements read must watch too, and whose items are named and kept of the bank.
     */
    static List<Needs> fixedNeeds() {
        var needs = new ArrayList<>(RouteFiles.tables().made);
        // And a boat spell's tablet (its eighth column), which it is cast by too.
        for (var way : RouteFiles.tables().own) {
            needs.add(way.length > 7 && !way[7].isEmpty() ? new Needs(way[2], way[3] + "||" + way[7], way[4], way[5])
                : needs(way));
        }
        return needs;
    }

    /**
     * Where a bank step is named: on the booth or chest used from {@code stand}, else the stand. Each bank's rows: its
     * icon's tile, a tile to stand on beside one of its booths or chests (nearest the icon first), and that booth; -1
     * where none.
     */
    static int booth(int stand) {
        for (int[] row : RouteFiles.tables().banks) {
            if (row[1] == stand && stand >= 0 && row[2] >= 0) {
                return row[2];
            }
        }
        return stand;
    }

    /** Where to stand to use the bank whose icon is at {@code icon}: by each of its booths, else the icon itself. */
    static int[] bankStands(int icon) {
        int[] stands = stream(RouteFiles.tables().banks).filter(row -> row[0] == icon && row[1] >= 0)
            .mapToInt(row -> row[1]).toArray();
        return stands.length > 0 ? stands : new int[]{icon};
    }

    boolean walkable(WorldPoint point) {
        return map.walkable(point.getX(), point.getY(), point.getPlane());
    }

    int snap(WorldPoint point) {
        return snap(point, point);
    }

    int snap(WorldPoint point, WorldPoint other) {
        long key = (long) pack(point) << 32 | pack(other) & 0xffffffffL;
        // A spot one can stand on is where the game puts one, also in ground shut in but for the jump itself (the fairy
        // ring BIS in the zoo's unicorn pen): only a spot on an object or a wall moves to the nearest open ground.
        return snaps.computeIfAbsent(key, k -> walkable(point) ? pack(point) : map.walkableNear(point.getX(),
            point.getY(), point.getPlane(), SNAP, other.getX(), other.getY(), pocket));
    }

    /**
     * Jewellery and such with charges (list "charged"): at a tie a spell's runes or a teleport without limit (list
     * "unlimited": the amulet of eternal glory, the eternal slayer ring) go first.
     */
    static boolean charged(String group, PlayerState state) {
        if (group == null || !list("charged").contains(group)) {
            return false;
        }
        String unlimited = RouteFiles.tables().unlimited.get(group);
        return unlimited == null || !state.items.has(unlimited);
    }

    /** The planted spirit trees that stand grown for this account (see {@link Planted}). */
    volatile Set<String> plantedTrees = Collections.emptySet();

    /** A planted spirit tree's stop the player has no grown tree at: not travelled from or to. */
    boolean notPlanted(Poi poi) {
        return Planted.planted(poi) && !plantedTrees.contains(poi.name);
    }

    static final String TYPE = "type:";
    /** The kind of the house's teleports and its entrance, in one: "Avoid every House teleport" leaves the house out. */
    static final String HOUSE = "House teleport";

    static String typeKey(String category) {
        return TYPE + category.toLowerCase(ROOT);
    }

    /** In the avoid set: no home or minigame teleport at all (an order's trips without the one cast they get). */
    static final String ONCE_ONLY = TYPE + "home and minigame teleports";

    /** A home or minigame teleport: cast once, then its cooldown (30 and 20 minutes). {@code name} lower case. */
    static boolean onceOnly(String name, String group) {
        return name.contains("home teleport") || "Minigame teleports".equals(group);
    }

    /** Whether a route's step is such a teleport. */
    static boolean onceOnly(Route.Step step) {
        return step.kind == Route.Step.Kind.TELEPORT && step.name != null
            && onceOnly(step.name.toLowerCase(ROOT), step.category);
    }

    static String dockLevel(Poi poi) {
        int level = level(poi.needs);
        return level > 1 ? " (" + level + " Sailing)" : "";
    }

    static int level(Needs needs) {
        Matcher m = SAILING.matcher(needs.skills);
        return m.find() ? Integer.parseInt(m.group(1)) : 1;
    }

    static boolean stairs(String thing) {
        return HouseTracker.feature(thing) == null && !thing.equals("Portal") && !thing.startsWith(NEXUS);
    }

    /**
     * The player's own ways the planner adds itself (the map version's own_ways.tsv) by kind: into one's own house best
     * first, the boat spells (Summon Boat brings a boat with a teleport focus to the dock one stands at, Teleport to
     * Boat takes one to a boat with the greater focus), the Sailing cape. Each: kind, name, needs (skills, items,
     * quests, varbits), short name, then a tablet's item id or whether it lands outside the house.
     */
    static List<String[]> own(String kind) {
        return stream(RouteFiles.tables().own).filter(c -> c[0].equals(kind)).collect(toList());
    }

    static Needs needs(String[] way) {
        return new Needs(way[2], way[3], way[4], way[5]);
    }

    /**
     * What one step of two ways needs (the way into the house, {@code a}, and what is used there, {@code b}, whose house
     * parts it keeps): what the bank gives as one list.
     */
    static Said both(Said a, Said b) {
        Said need = a.text() == null ? b : b.text() == null ? a : a.bank != null && b.bank != null
            ? a.withBank(Stream.concat(a.bank.stream(), b.bank.stream()).collect(toList())) : a.and(b.text());
        return b.withLack(need.lack).withBank(need.bank).withNote(need.note).withUnnamed(need.unnamed);
    }

    static final String NEXUS = "Portal Nexus";
    /** How a mounted amulet's house step starts, in {@link HouseSettings#MOUNTED}'s order. */
    static final String[] MOUNTED_NAMES = {"Mounted glory: ", "Mounted talisman: ", "Mounted pendant: "};

    /** A spell starting with the place's name first, else a teleport to it by name ("Stony basalt: Troll Stronghold"). */
    static Jump portalTeleport(String place, Map<String, Jump> teleports) {
        // Portal and nexus places the spellbooks name otherwise.
        String given = place.trim().toLowerCase(ROOT), wanted = RouteFiles.tables().portalNames.getOrDefault(given, given);
        int bestRank = 0;
        String bestName = null;
        Jump best = null;
        for (var entry : teleports.entrySet()) {
            String name = entry.getKey();
            var teleport = entry.getValue();
            boolean spell = name.startsWith(wanted) && teleport.group != null && teleport.group.contains("Spellbook");
            if (!spell && !name.contains(": " + wanted)) {
                continue;
            }
            if (name.equals(wanted) || name.equals(wanted + " teleport")) {
                return teleport;
            }
            // Spells first, then the shortest name, then the first alphabetically.
            int rank = (spell ? 0 : 1 << 16) + name.length();
            if (best == null || rank < bestRank || rank == bestRank && name.compareTo(bestName) < 0) {
                bestRank = rank;
                bestName = name;
                best = teleport;
            }
        }
        return best;
    }

    static final int LACKING_ITEM = 2000;

    /** The skill/quest lines of {@code needs} that {@code unlocks} says are not met, by their text. */
    static List<String> unmetLines(Unlocks unlocks, Needs needs) {
        return Requirements.skillsAndQuests(needs).stream().filter(line -> Boolean.FALSE.equals(unlocks.met(line)))
            .map(line -> line.text).collect(toList());
    }

    /** The groups of a column like {@code "563=1&&556=3"} that are not carried, by name: "1 × Law rune". */
    static String missingItems(ItemSnapshot carried, String column, IntFunction<String> names) {
        var lines = names == null ? List.<String>of() : Requirements.items(stream(ItemSnapshot.ALL.split(column))
            .filter(all -> !carried.has(all)).collect(joining("&&")), names);
        return lines.isEmpty() ? "items you do not have" : String.join(", ", lines);
    }

    /** A step's requirements when not all could be checked (logged out, unknown quest or item); else null. */
    static String uncheckedText(Unlocks realUnlocks, Needs needs) {
        var lines = Requirements.skillsAndQuests(needs);
        if (lines.stream().allMatch(line -> realUnlocks != null && realUnlocks.met(line) != null)) {
            return null;
        }
        var parts = lines.stream().map(line -> line.text).collect(toList());
        if (!needs.items.trim().isEmpty() && realUnlocks == null) {
            parts.add("items");
        }
        return parts.isEmpty() ? null : "Needs: " + String.join(", ", parts);
    }
}
