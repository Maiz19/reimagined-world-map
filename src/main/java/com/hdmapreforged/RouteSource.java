package com.hdmapreforged;

import static java.util.Arrays.*;
import static java.util.stream.Collectors.*;
import static com.hdmapreforged.RouteFiles.*;
import static com.hdmapreforged.Pathfinder.*;
import static com.hdmapreforged.Tiles.*;
import static java.util.Locale.ROOT;
import static com.hdmapreforged.PoiType.*;
import static com.hdmapreforged.RouteFeature.pack;

import com.hdmapreforged.Edge.Kind;
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
        final boolean avoidWilderness;
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

    /** Snapped ends by spot (and the spot to keep away from): the map does not change, and requests snap thousands. */
    final Map<Long, Integer> snaps = new ConcurrentHashMap<>();

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
        /** The stops of planted spirit trees not grown for this player: not travelled to. */
        Set<WorldPoint> notGrown = Collections.emptySet();

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
            var teleportsByName = new HashMap<String, Poi>();
            var all = Poi.flatten(pois);
            notGrown = all.stream().filter(RouteSource.this::notPlanted).map(poi -> poi.location).collect(toSet());
            for (var poi : all) {
                if (poi.type == TELEPORT) {
                    for (var member : poi.members()) {
                        teleportsByName.putIfAbsent(member.name.toLowerCase(ROOT), member);
                        if (options.teleports) {
                            teleport(member, startEdges, edges);
                        }
                    }
                } else if (poi.type == MOORING) {
                    if (options.sailing) {
                        mooring(poi, start >= 0 && isSea(start), edges);
                    }
                } else if (isTransport(poi.type)) {
                    transport(poi, edges);
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
                house(all, teleportsByName, startEdges, edges);
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
            return new RouteRequest(start, target, startEdges, edges, level, state.running, nodeLimit, options.avoidWilderness);
        }

        void gates(List<Edge> into) {
            for (Gate gate : GATES.get()) {
                // Its quests and levels, and the items it takes (a key, a skavid map and a light) carried or worn.
                if (!usable(gate.needs) || !state.items.has(gate.needs.items)) {
                    continue;
                }
                int a = snap(gate.from, gate.to), b = snap(gate.to, gate.from);
                if (a >= 0 && b >= 0 && a != b) {
                    // A row named by its action ("Climb-down Ladder (Mining Guild)") as it is; a door or gate opened.
                    String name = ACTION.get().matcher(gate.name).lookingAt() ? gate.name : "Open " + gate.name;
                    into.add(new Edge(a, b, Kind.ENTRANCE, name, detail(null, gate.needs), 4));
                }
            }
        }

        /** The sea caves and the like (sea_passages.tsv) between blocks of sea the player's boat can sail. */
        void seaPassages(List<Edge> into) {
            for (var passage : SEA_PASSAGES.get()) {
                int from = seaAt(passage.fromX, passage.fromY), to = seaAt(passage.toX, passage.toY);
                if (usable(passage.needs) && sea.sailable(cellX(from), cellY(from), state.sailingLevel)
                    && sea.sailable(cellX(to), cellY(to), state.sailingLevel)) {
                    // What it needs, and what the step should say besides (the boat's kind).
                    String needs = detail(null, passage.needs);
                    String detail = needs == null ? passage.note : passage.note.isEmpty() ? needs : needs + ". " + passage.note;
                    into.add(new Edge(from, to, Kind.ENTRANCE, passage.name, detail.isEmpty() ? null : detail, 10));
                }
            }
        }

        void shortcuts(List<Edge> into) {
            for (var passage : shortcuts) {
                // Any of its ways the player meets: with a grapple, or bare-handed at a higher level.
                for (var way : passage.ways) {
                    Needs needs = new Needs(way[0], way[1], way[2], way[3]);
                    if (usable(needs) && state.items.has(needs.items)) {
                        Edge e = passage.edge;
                        into.add(new Edge(e.from, e.to, e.kind, e.name, detail(null, needs), e.cost));
                        break;
                    }
                }
            }
        }

        boolean usable(Needs needs) {
            return unlocks == null || unlocks.usable(needs);
        }

        /**
         * A teleport from anywhere ({@code into}); in a house a minigame teleport only from outside its portal
         * ({@code edges}): the game does not let one cast it in a player-owned house.
         */
        void teleport(Poi member, List<Edge> into, List<Edge> edges) {
            if (HouseTracker.isTemplate(member.location.getX(), member.location.getY())
                || !usable(member.needs) || !state.items.has(member.needs.items)
                || unlocks != null && unlocks.waiting(member.name, member.group, minute)) {
                return;
            }
            int lacking = lacking(member.needs), to = snap(member.location);
            // Outside one's own house only (a friend's house tells nothing of it).
            if (to < 0 || member.name.contains("(Outside)") && (state.houseExit < 0 || state.inHouse && !state.ownHouse
                || distance(to, state.houseExit) > PORT_RADIUS)) {
                return;
            }
            String name = member.name.toLowerCase(ROOT);
            // The house's teleports are one kind with its entrance: leaving them out leaves the house out.
            String category = member.name.startsWith("Teleport to House") ? HOUSE
                : member.group != null && !member.group.isEmpty() ? member.group : "Teleports";
            boolean once = onceOnly(name, member.group);
            if (avoids(name, category) || once && options.avoid.contains(ONCE_ONLY)) {
                return;
            }
            boolean outside = state.inHouse && "Minigame teleports".equals(member.group);
            int from = outside && state.houseExit >= 0 ? snap(RouteFeature.worldPoint(state.houseExit)) : Edge.ANYWHERE;
            if (outside && from < 0) {
                return;
            }
            (outside ? edges : into).add(new Edge(from, to, Kind.TELEPORT, member.name,
                detail(TELEPORT, member.needs),
                // Half ticks: 4 ticks (a teleport spell, a tablet); a home or minigame teleport takes 24 ticks to cast.
                once ? 48 : 8, category)
                .weighed(options.saving.teleport + lacking + (charged(member.group, state) ? 1 : 0)));
        }

        void transport(Poi poi, List<Edge> into) {
            if (notPlanted(poi)) {
                return;
            }
            // Only what is carried: a route never fetches an item (a fare's coins, a fairy ring's staff) from the bank.
            // (The bank only as a way past it first: BankDetour, a setting.)
            boolean exactEnds = poi.type == AGILITY_SHORTCUT && walkable(poi.location);
            if (!usable(poi.needs) || !carried(poi.type, poi.needs)
                || !exactEnds && snap(poi.location) < 0) {
                return;
            }
            for (var link : poi.links()) {
                if (!usable(link.needs) || !carried(poi.type, link.needs)
                    || poi.type == SPIRIT_TREE && notGrown.contains(link.point)) {
                    continue;
                }
                // Each end on its own side: stepping stones stand on lava or water, with pockets between them.
                boolean sameFloor = link.point.getPlane() == poi.location.getPlane();
                // A shortcut's ends are its two sides as given: kept where walkable, also inside ground shut in but for
                // shortcuts and gates (Gu'Tanoth behind its crumbling wall), which a snap would leave for open ground.
                int from = exactEnds ? pack(poi.location) : snap(poi.location, sameFloor ? link.point : poi.location);
                int to = poi.type == AGILITY_SHORTCUT && walkable(link.point) ? pack(link.point)
                    : snap(link.point, sameFloor ? poi.location : link.point);
                if (to < 0 || to == from) {
                    continue;
                }
                String name = describe(poi, link);
                // A shortcut left out before 0.3.2 was saved with its other side ("… → other side").
                if (avoids(name, poi.type.displayName) || poi.type == AGILITY_SHORTCUT && link.label != null
                    && options.avoid.contains((name + " → " + link.label).toLowerCase(ROOT))) {
                    continue;
                }
                var fee = Fees.at(poi.location.getX(), poi.location.getY(), poi.location.getPlane());
                String needs = detail(poi.type, link.needs);
                if (fee != null) {
                    // The fee apart: it cannot be checked, so it is not something the player lacks.
                    needs = needs == null ? "Needs: " + fee.text : needs + ". Also needs: " + fee.text;
                }
                into.add(new Edge(from, to, poi.type.kind, name, needs, poi.type.cost * dials(poi.type, link.label)
                    + (fee == null ? 0 : fee.cost),
                    poi.type.displayName).weighed(options.saving.tiles(poi.type)));
            }
        }

        /**
         * A dock: leaving the boat needs its Sailing level; boarding needs the boat there, a shipwright to bring it, or a
         * port when no boat's port is known. {@code onBoat}: the route starts at sea on the boat, so it lies at no dock.
         */
        void mooring(Poi poi, boolean onBoat, List<Edge> into) {
            if (!state.sailing || state.sailingLevel < level(poi.needs) || !usable(poi.needs)) {
                return;
            }
            int x = poi.location.getX(), y = poi.location.getY(), land = map.nearestWalkable(x, y, 0, 8);
            int water = sea.nearestBlock(x, y, DOCK_REACH, state.sailingLevel);
            if (land < 0 || water < 0) {
                return;
            }
            into.add(new Edge(water, land, Kind.DISEMBARK, "Dock at " + poi.name + dockLevel(poi), null, 10));
            int needed = Math.max(1, level(poi.needs));
            String lacking = realSailing < needed
                ? "You lack: " + (realSailing <= 0 ? "Sailing" : needed + " Sailing")
                : null;
            String name = "Board your boat at " + poi.name;
            String hint = null;
            int cost = 10;
            String summon = stream(state.boatFocus).anyMatch(focus -> focus >= 1)
                ? boatSpell("summon boat") : null;
            if (stream(state.boats).anyMatch(boat -> distance(boat, land) <= BOAT_REACH)) {
                name += dockLevel(poi);
            } else if (summon != null) {
                name = "Summon your boat and board it at " + poi.name;
                hint = summon;
                cost = 16;
            } else if (stream(options.shipwrights).anyMatch(s -> distance(s, land) <= SHIPWRIGHT_REACH)) {
                hint = "Ask the shipwright here to bring your boat (fee)";
                cost = 60;
            } else if (state.boats.length == 0 && !onBoat
                && stream(PORTS.get()).anyMatch(port -> distance(port, land) <= SHIPWRIGHT_REACH)) {
                // Where the boat lies is not known yet (not logged in): a port, where it may lie; not any dock.
                hint = "If your boat is moored here (a shipwright can bring it)";
            } else {
                return;
            }
            into.add(new Edge(land, water, Kind.BOARD, name, lacking != null ? lacking : hint, cost));
        }

        /** How the player casts a boat spell now: its tablet, the spell itself, or null when neither. */
        String boatSpell(String kind) {
            var spell = own(kind).stream().findFirst().orElse(null);
            return spell == null ? null : state.items.has(spell[7]) ? spell[1]
                : usable(needs(spell)) && state.items.has(needs(spell).items) ? "Spell (standard spellbook)" : null;
        }

        /** To each boat with the greater teleport focus: by the Sailing cape, a tablet or the spell, when the player can. */
        void teleportToBoat(List<Edge> into) {
            String means = !state.sailing ? null : own("sailing cape").stream().filter(c -> state.items.has(c[3]))
                .map(c -> c[1]).findFirst().orElseGet(() -> boatSpell("boat teleport"));
            for (int i = 0; means != null && i < state.boats.length; i++) {
                int land = i >= state.boatFocus.length || state.boatFocus[i] < 2 ? -1
                    : map.nearestWalkable(x(state.boats[i]), y(state.boats[i]), 0, 12);
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
        void house(List<Poi> pois, Map<String, Poi> teleports,
            List<Edge> starts, List<Edge> edges) {
            var plan = state.inHouse && !state.ownHouse ? null : map.house;
            var into = new ArrayList<Edge>();
            var portal = state.houseExit < 0 ? null
                : new WorldPoint(x(state.houseExit), y(state.houseExit), 0);
            if (state.inHouse && portal != null && (plan == null || !plan.things.containsValue("Portal"))) {
                addHouse(into, portal, "Leave the house by its portal", 6, null, 0);
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
                            ways(feature, pois, teleports, out);
                        }
                    }
                    if (name.equals("Portal") && portal != null) {
                        addHouse(out, portal, "Leave the house by its portal", 6, null, 0);
                    }
                    for (int dz = -1; stairs(name) && dz <= 1; dz += 2) {
                        int other = z(node) + dz;
                        // Only to a floor with stairs where these stand: they go up or down, seldom both.
                        if (plan.things.entrySet().stream().anyMatch(t -> z(t.getKey()) == other
                            && distance(t.getKey(), node) <= 3 && stairs(t.getValue()))) {
                            out.add(new Edge(Edge.ANYWHERE, map.nearestWalkable(x(node), y(node), other, 3),
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
                        ways(part, pois, teleports, into);
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
                if ((state.landsInside || !way[7].equals("outside")) && usable(needs) && state.items.has(needs.items)
                    && (best == null || lack < lacking)
                    && !avoids(way[1] + (state.landsInside ? "" : " (inside)"), "Teleports")) {
                    best = way;
                    lacking = lack;
                }
            }
            int door = portal == null ? -1 : snap(portal);
            String teleport = best == null ? null : best[1] + (state.landsInside ? "" : " (Inside)");
            if (plan != null) {
                if (best != null) {
                    starts.add(new Edge(Edge.ANYWHERE, plan.arrival, Kind.TELEPORT, teleport,
                        detail(TELEPORT, needs(best)), 10, HOUSE)
                        .weighed(options.saving.teleport + lacking));
                }
                if (door >= 0) {
                    edges.add(new Edge(door, plan.arrival, Kind.ENTRANCE, "Enter your house", null, 6, HOUSE));
                }
                return;
            }
            for (Edge e : into) {
                if (best != null) {
                    // Both ways' ticks, and both ways' "only when it saves" weights.
                    starts.add(new Edge(Edge.ANYWHERE, e.to, Kind.HOUSE, teleport + " → " + e.name,
                        both(detail(TELEPORT, needs(best)), e.detail), 10 + e.time, HOUSE)
                        .weighed(e.cost - e.time + options.saving.teleport + lacking));
                }
                if (door >= 0) {
                    edges.add(new Edge(door, e.to, Kind.HOUSE, "Enter your house → " + e.name, e.detail,
                        6 + e.cost, HOUSE));
                }
            }
        }

        /** Where a feature of the house ("portal:Varrock", "box:ornate", "glory", "xeric", "fairy ring") leads, from anywhere. */
        void ways(String feature, List<Poi> pois, Map<String, Poi> teleports, List<Edge> into) {
            int mounted = asList(HouseSettings.MOUNTED).indexOf(feature);
            if (feature.startsWith("portal:") || feature.startsWith("nexus:")) {
                String place = feature.substring(feature.indexOf(':') + 1);
                var teleport = portalTeleport(place, teleports);
                if (teleport != null) {
                    // Named as the portal is ("Troll Stronghold"), not as the teleport that says where it leads.
                    addHouse(into, teleport.location, (feature.startsWith("nexus:") ? "Portal nexus: " : "House portal: ")
                        + place, 6, null, options.saving.teleport);
                }
            } else if (feature.startsWith("box:") || mounted >= 0) {
                var groups = mounted >= 0 ? List.of(HouseSettings.MOUNTED_GROUPS[mounted])
                    : list("jewellery box teleports").stream().limit(feature.equals("box:basic") ? 2
                    : feature.equals("box:ornate") ? 6 : 4).collect(toList());
                for (var teleport : teleports.values()) {
                    if (teleport.group != null && groups.contains(teleport.group) && usable(teleport.needs)) {
                        addHouse(into, teleport.location, (mounted >= 0 ? MOUNTED_NAMES[mounted] : "Jewellery box: ")
                            + teleport.name, 8, null, options.saving.teleport);
                    }
                }
            }
            if (feature.equals("obelisk")) {
                // As those in the Wilderness: to the obelisk one chooses, with the Hard Wilderness Diary.
                for (var poi : pois) {
                    if (poi.type == OBELISK && usable(poi.needs)) {
                        addHouse(into, poi.location, "House obelisk: " + poi.name, OBELISK.cost, null,
                            options.saving.tiles(OBELISK));
                    }
                }
            }
            if (feature.contains("fairy ring") || feature.contains("spirit tree")) {
                for (var poi : pois) {
                    boolean ring = poi.type == FAIRY_RING && feature.contains("fairy ring");
                    boolean tree = poi.type == SPIRIT_TREE && feature.contains("spirit tree");
                    // The house's ring needs the staff as any ring does.
                    if ((ring || tree) && usable(poi.needs) && carried(poi.type, poi.needs)
                        && !notPlanted(poi)) {
                        addHouse(into, poi.location, "House " + poi.type.displayName.toLowerCase(ROOT) + ": "
                            + code(poi.type, poi.name), 10 * dials(poi.type, poi.name), bankNeeds(poi.type,
                            poi.needs), options.saving.tiles(poi.type));
                    }
                }
            }
        }

        /** {@code weight}: the way's "only when it saves" setting, as the same way outside the house carries it. */
        void addHouse(List<Edge> into, WorldPoint point, String name, int cost, String detail, int weight) {
            int to = snap(point);
            if (to >= 0) {
                // One kind (as minigame teleports are), to leave all out at once; the way out of the house is none.
                into.add(new Edge(Edge.ANYWHERE, to, Kind.HOUSE, name, detail, cost, name.contains(": ")
                    ? "House teleports" : null).weighed(weight));
            }
        }

        String needsText(Needs needs) {
            if (needs.isEmpty()) {
                return null;
            }
            if (missingLevels == null && missingItems == null) {
                return uncheckedText(realUnlocks, needs);
            }
            var parts = new ArrayList<String>();
            if (missingLevels != null) {
                parts.addAll(unmetLines(missingLevels, needs));
            }
            if (missingItems != null && !missingItems.has(needs.items)) {
                parts.add(missingItems(missingItems, needs.items, itemNames));
            }
            return parts.isEmpty() ? null : "You lack: " + String.join(", ", parts);
        }

        /** A step's detail: what it needs from the bank ({@link #bankNeeds}), else {@link #needsText}. */
        String detail(PoiType type, Needs needs) {
            String bank = bankNeeds(type, needs);
            return bank != null ? bank : needsText(needs);
        }

        /**
         * What a step needs from the bank, when the bank is counted and what is carried is not enough; else null. A fairy
         * ring needs no staff after the Lumbridge & Draynor Elite Diary.
         */
        String bankNeeds(PoiType type, Needs needs) {
            boolean enough = state.carried == null || state.carried.has(needs.items)
                || type == FAIRY_RING && unlocks != null && unlocks.lumbridgeElite();
            return enough ? null : BankDetour.FROM_BANK + fromBank(needs.items);
        }

        /**
         * What of {@code column} only the bank has, by name: of each part not carried, the alternatives the bank holds
         * ("Dramen staff", "Law rune, Air rune ×3").
         */
        String fromBank(String column) {
            return itemLines(stream(ItemSnapshot.ALL.split(column)).filter(all -> !state.carried.has(all))
                .map(all -> stream(ItemSnapshot.ANY.split(all)).filter(items::has).findFirst().map(String::trim)
                    .orElse(all)).collect(toList()), itemNames, "items");
        }
    }

    @RequiredArgsConstructor
    static final class Gate {
        final WorldPoint from, to;
        final String name;
        final Needs needs;
    }

    /** A way between two seas that sailing alone does not join (sea_passages.tsv): a sea cave. */
    @RequiredArgsConstructor
    static final class SeaPassage {
        final int fromX, fromY, toX, toY;
        final String name;
        final Needs needs;
        final String note;
    }

    /** Read once per route.zip, so the unlocks' per-requirement cache hits. */
    /** gates.tsv by column: Name, Origin, Destination, Skills, Items, Quests, Varbits; {@code #} lines are comments. */
    static final Table<List<Gate>> GATES = new Table<>(() -> table(DIR
        + "gates.tsv", cells -> {
            var from = point(cell(cells, 1));
            var to = point(cell(cells, 2));
            return from == null || to == null ? null : new Gate(from, to, cell(cells, 0), new Needs(cell(cells, 3),
                cell(cells, 4), cell(cells, 5), cell(cells, 6)));
        }));
    /** sea_passages.tsv by column: from x y, to x y, Name, Quests, Note; {@code #} lines are comments. */
    static final Table<List<SeaPassage>> SEA_PASSAGES = new Table<>(() -> table(
        DIR + "sea_passages.tsv", cells -> {
            var from = ints(cell(cells, 0));
            var to = ints(cell(cells, 1));
            return new SeaPassage(from[0], from[1], to[0], to[1], cell(cells, 2), new Needs("", "", cell(cells, 3), ""),
                cell(cells, 4));
        }));
    /**
     * The needs of the bundled gates, shortcuts and own ways (a house tablet), whose quests and varbits the requirements
     * read must watch too, and whose items are named and kept of the bank.
     */
    static List<Needs> fixedNeeds() {
        var needs = new ArrayList<Needs>();
        GATES.get().forEach(gate -> needs.add(gate.needs));
        // And a boat spell's tablet (its eighth column), which it is cast by too.
        OWN.get().forEach(way -> needs.add(way.length > 7 && !way[7].isEmpty()
            ? new Needs(way[2], way[3] + "||" + way[7], way[4], way[5]) : needs(way)));
        SEA_PASSAGES.get().forEach(passage -> needs.add(passage.needs));
        ShortcutPassage.allWays().forEach(way -> needs.add(new Needs(way[0], way[1], way[2], way[3])));
        return needs;
    }

    /**
     * Each bank's rows of banks.tsv: its icon's tile, a tile to stand on beside one of its booths or chests (nearest the
     * icon first), and that booth (its fifth column); -1 where a cell is no tile.
     */
    static final Table<List<int[]>> BANKS = new Table<>(() -> table(DIR
        + "banks.tsv", cells -> new int[]{parse(cells[0]), parse(cells[1]), parse(cells[4])}));

    /** Where a bank step is named: on the booth or chest used from {@code stand}, else the stand. */
    static int booth(int stand) {
        for (int[] row : BANKS.get()) {
            if (row[1] == stand && stand >= 0 && row[2] >= 0) {
                return row[2];
            }
        }
        return stand;
    }

    /** Where to stand to use the bank whose icon is at {@code icon}: by each of its booths, else the icon itself. */
    static int[] bankStands(int icon) {
        int[] stands = BANKS.get().stream().filter(row -> row[0] == icon && row[1] >= 0).mapToInt(row -> row[1]).toArray();
        return stands.length > 0 ? stands : new int[]{icon};
    }

    /** Where a boat can lie: the game's ports. */
    static final Table<int[]> PORTS = new Table<>(() -> Ports.load().values().stream()
        .mapToInt(Integer::intValue).toArray());

    static String cell(String[] cells, int column) {
        return column < cells.length ? cells[column].trim() : "";
    }

    /** {@code "x y plane"} on the map, or null. */
    static WorldPoint point(String cell) {
        int at = parse(cell);
        return at < 0 ? null : RouteFeature.worldPoint(at);
    }

    /** How a gate named by its action starts (list "actions"); (?!) matches nothing: without the list all are opened. */
    static final Table<Pattern> ACTION = new Table<>(() -> Pattern.compile("((?!)"
        + list("actions").stream().map(a -> "|" + Pattern.quote(a)).collect(joining()) + ")\\b"));

    // Obelisks only with the Hard Wilderness Diary, which their stops need (it lets one choose where they lead; without
    // it they lead to a random other obelisk). Not entrances (map links, in Pathfinder, not our icons).
    static final Set<PoiType> TRANSPORTS = EnumSet.of(FAIRY_RING, SPIRIT_TREE,
        GNOME_GLIDER, BALLOON, QUETZAL, MUSHTREE, OBELISK, BOAT,
        CHARTER, CANOE, CARPET, MINECART, PORTAL, LEVER, GUIDE,
        TRANSPORT, AGILITY_SHORTCUT);

    static boolean isTransport(PoiType type) {
        return TRANSPORTS.contains(type);
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
        return snaps.computeIfAbsent(key, k -> walkable(point) ? pack(point) : map.nearestWalkable(point.getX(),
            point.getY(), point.getPlane(), SNAP, other.getX(), other.getY(), pocket));
    }

    static final Table<Map<String, String>> UNLIMITED = new Table<>(() -> pairs(
        "unlimited", v -> v));

    /**
     * Jewellery and such with charges (list "charged"): at a tie a spell's runes or a teleport without limit (list
     * "unlimited": the amulet of eternal glory, the eternal slayer ring) go first.
     */
    static boolean charged(String group, PlayerState state) {
        if (group == null || !list("charged").contains(group)) {
            return false;
        }
        String unlimited = UNLIMITED.get().get(group);
        return unlimited == null || !state.items.has(unlimited);
    }

    /** The planted spirit trees that stand grown for this account (see {@link PlantedSpiritTrees}). */
    volatile Set<String> plantedTrees = Collections.emptySet();

    /** A planted spirit tree's stop the player has no grown tree at: not travelled from or to. */
    boolean notPlanted(Poi poi) {
        return PlantedSpiritTrees.planted(poi) && !plantedTrees.contains(poi.name);
    }

    static final String TYPE = "type:";
    /** The kind of the house's teleports and its entrance, in one: "Avoid every House teleport" leaves the house out. */
    static final String HOUSE = "House teleport";

    static String typeKey(String category) {
        return TYPE + category.toLowerCase(ROOT);
    }

    static String describe(Poi poi, Poi.Link link) {
        String label = link.label == null || link.label.isEmpty() ? null : link.label;
        switch (poi.type) {
            // The obstacle is enough: its other side ("Other side", "Go over") says nothing.
            case AGILITY_SHORTCUT:
                return poi.name;
            case LEVER:
            case PORTAL:
            case GUIDE:
                return poi.name + (label != null && !poi.name.contains(label) ? " → " + label : "");
            default:
                return poi.type.displayName + ": " + code(poi.type, label != null ? label : poi.name);
        }
    }

    /**
     * A fairy ring by the code one dials ("BIP"), not its whole title; codes dialled one after another ("AIR-DLR-DJQ-AJS",
     * the Fairy Resistance Hideout) as "AIR, DLR, DJQ, then AJS".
     */
    static String code(PoiType type, String name) {
        int dash = type == FAIRY_RING ? name.indexOf(" – ") : -1;
        String code = dash < 0 ? name : name.substring(0, dash);
        var codes = code.split("-");
        return dials(type, code) == 1 ? code
            : String.join(", ", copyOf(codes, codes.length - 1)) + ", then " + codes[codes.length - 1];
    }

    /** Codes dialled one after another ("AIR-DLR-DJQ-AJS"). */
    static final Pattern CODES = Pattern.compile("[A-Z]{3}(-[A-Z]{3})+");

    /** How many rings one goes through to a stop: the codes of a sequence ("AIR-DLR-DJQ-AJS"), else one. */
    static int dials(PoiType type, String name) {
        if (type != FAIRY_RING || name == null) {
            return 1;
        }
        int dash = name.indexOf(" – ");
        String code = dash < 0 ? name : name.substring(0, dash);
        return CODES.matcher(code).matches() ? code.split("-").length : 1;
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
    static final Table<List<String[]>> OWN = new Table<>(() -> table(
        DIR + "own_ways.tsv", c -> c.length < 8 ? null : c));

    static List<String[]> own(String kind) {
        return OWN.get().stream().filter(c -> c[0].equals(kind)).collect(toList());
    }

    static Needs needs(String[] way) {
        return new Needs(way[2], way[3], way[4], way[5]);
    }

    /** Two details of one step (the way into the house and what is used there): what the bank gives as one line. */
    static String both(String a, String b) {
        String bank = BankDetour.FROM_BANK;
        if (a == null || b == null) {
            return a == null ? b : a;
        }
        return a.startsWith(bank) && b.startsWith(bank) ? a + ", " + b.substring(bank.length()) : a + ". " + b;
    }

    static final String NEXUS = "Portal Nexus";
    /** How a mounted amulet's house step starts, in {@link HouseSettings#MOUNTED}'s order. */
    static final String[] MOUNTED_NAMES = {"Mounted glory: ", "Mounted talisman: ", "Mounted pendant: "};

    /** Portal and nexus places the spellbooks name otherwise (list "portal names"). */
    static final Table<Map<String, String>> PORTAL_NAMES = new Table<>(() -> pairs(
        "portal names", v -> v));

    /** A spell starting with the place's name first, else a teleport to it by name ("Stony basalt: Troll Stronghold"). */
    static Poi portalTeleport(String place, Map<String, Poi> teleports) {
        String given = place.trim().toLowerCase(ROOT), wanted = PORTAL_NAMES.get().getOrDefault(given, given);
        int bestRank = 0;
        String bestName = null;
        Poi best = null;
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
        return itemLines(stream(ItemSnapshot.ALL.split(column)).filter(all -> !carried.has(all))
            .collect(toList()), names, "items you do not have");
    }

    static String itemLines(List<String> all, IntFunction<String> names, String none) {
        var lines = names == null ? List.<String>of() : Requirements.items(String.join("&&", all), names);
        return lines.isEmpty() ? none : String.join(", ", lines);
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
