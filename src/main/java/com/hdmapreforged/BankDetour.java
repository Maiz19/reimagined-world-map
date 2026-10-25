package com.hdmapreforged;

import static com.hdmapreforged.Route.Outcome.*;

import java.util.*;
import java.util.function.*;
import java.util.regex.*;
import java.util.stream.*;
import lombok.*;

/**
 * A way past a bank first, for teleports and transport kept in the bank (setting, with the bank as last seen): to the
 * nearest of a few banks with what is carried, take out what the rest needs, and on from there with the bank's items
 * too. Taken instead of the way with what is carried only when it gets there notably sooner, or only it gets there.
 */
@RequiredArgsConstructor
final class BankDetour {
    /** How a step's detail starts when it needs something only the bank has (RouteSource names it). */
    static final String FROM_BANK = "From the bank: ";

    /** Joins the name of the teleport or transport that takes one to the bank to what is taken there ("... Castle Wars → Bank: Dramen staff"). */
    static final String TO_BANK = " → Bank: ";

    /** A step's name without {@link #TO_BANK} and what follows: what it is left out of a route by. */
    static String plain(String name) {
        int at = name == null ? -1 : name.lastIndexOf(TO_BANK);
        return at < 0 ? name : name.substring(0, at);
    }

    /** Half ticks to open the bank and take things out. */
    static final int WITHDRAW = 6;

    /**
     * Per bank, where to stand at it: beside each of its booths or chests, nearest its icon first (or the icon). The few
     * banks quickest to reach are tried, each at its first; the way in then goes where the game walks from where it comes in.
     */
    final int[][] banks;
    /** The same search with the bank's items as well. */
    final RouteRequest withBank;
    /** Half ticks it must save: the setting's tiles, run. */
    final int saves;
    /** What the rune pouch in the bank holds, by name: taken as the pouch. */
    final Map<String, Long> pouch;

    /**
     * The route for {@code request}: past a bank when its {@link RouteRequest#bank} makes it notably quicker. The way
     * without the bank gives up at {@code costLimit}, as {@link Pathfinder#find(RouteRequest, BooleanSupplier, int)}.
     */
    static Route find(Pathfinder pathfinder, RouteRequest request, BooleanSupplier cancelled, int costLimit) {
        Route plain = pathfinder.find(request, cancelled, costLimit);
        var detour = request.bank;
        if (detour == null || plain.outcome == CANCELLED || cancelled.getAsBoolean()) {
            return plain;
        }
        // Notably quicker or nothing: no search of the detour goes past what would still beat the plain way (nor the
        // caller's limit), so it costs little where it cannot win.
        long limit = Math.min(costLimit, plain.outcome == FOUND ? (long) plain.cost - detour.saves - WITHDRAW
            : Integer.MAX_VALUE);
        Route banked = limit < 0 ? null : detour.route(pathfinder, request, cancelled, (int) limit);
        boolean better = banked != null && (plain.outcome != FOUND
            || banked.cost + detour.saves <= plain.cost);
        return better && !cancelled.getAsBoolean() ? banked : plain;
    }

    /**
     * To the nearest bank, then on with the bank's items; null when no bank is reached or nothing banked is used, or
     * the whole (the bank step's {@link #WITHDRAW} besides) costs more than {@code limit}.
     */
    Route route(Pathfinder pathfinder, RouteRequest request, BooleanSupplier cancelled, int limit) {
        Route toBank = null;
        int[] stands = null;
        for (int bank : quickest(request)) {
            Route way = pathfinder.find(request.between(request.start, banks[bank][0]), cancelled, limit);
            if (way.outcome == FOUND && (toBank == null || way.cost < toBank.cost)) {
                toBank = way;
                stands = banks[bank];
            }
        }
        if (toBank == null || cancelled.getAsBoolean()) {
            return null;
        }
        int nearer = stand(pathfinder.map, (x, y, z, dx, dy) -> pathfinder.closed(Tiles.pack(x, y, z), dx, dy), stands,
            entry(toBank, stands[0]));
        if (nearer != stands[0]) {
            // Another booth or side of the same bank, where the game walks from where the way comes in.
            Route way = pathfinder.find(request.between(request.start, nearer), cancelled, limit);
            toBank = way.outcome == FOUND ? way : toBank;
        }
        int at = toBank.end >= 0 ? toBank.end : toBank.target;
        Route on = pathfinder.find(withBank.between(at, request.target), cancelled, limit - toBank.cost);
        if (on.outcome != FOUND) {
            return null;
        }
        // Only what the bank's items add (not a ladder or cave on the way, which no item opens).
        var fromBank = new HashSet<String>();
        // Not the ways back into one's house, which the plain search from inside it lacks: no item of the bank opens them.
        Stream.concat(withBank.startEdges.stream(), withBank.edges.stream()).filter(e -> !HousePlan.contains(e.to))
            .forEach(e -> fromBank.add(e.name));
        Stream.concat(request.startEdges.stream(), request.edges.stream()).forEach(e -> fromBank.remove(e.name));
        var take = new LinkedHashSet<String>();
        for (var step : on.steps) {
            String named = step.detail != null && step.detail.startsWith(FROM_BANK)
                ? step.detail.substring(FROM_BANK.length()) : null;
            // "items": the game had not named them yet.
            if (named != null && !named.equals("items")) {
                take.addAll(Arrays.asList(named.split(", ")));
            } else if (named != null || step.isJump() && fromBank.contains(step.name)) {
                take.add(taken(step));
            }
        }
        if (take.isEmpty()) {
            return null;
        }
        var taken = viaPouch(take);
        String items = String.join(", ", taken);
        var steps = new ArrayList<Route.Step>(toBank.steps);
        // The last teleport or transport on the way names what is taken at the bank (not a staircase or cave).
        for (int i = steps.size() - 1; i >= 0; i--) {
            var s = steps.get(i);
            if (s.name != null && EnumSet.of(Route.Step.Kind.TELEPORT, Route.Step.Kind.TRANSPORT, Route.Step.Kind.SHIP,
                Route.Step.Kind.HOUSE).contains(s.kind)) {
                steps.set(i, s.withName(s.name + TO_BANK + items));
                break;
            }
        }
        steps.add(new Route.Step(Route.Step.Kind.BANK, new int[]{at, at}, "Bank: " + items, null,
            WITHDRAW, 0, List.of(), null));
        for (var s : on.steps) {
            // What the steps name from the bank: the pouch's runes as the pouch, as the bank step does.
            steps.add(s.detail != null && s.detail.startsWith(FROM_BANK)
                ? s.withDetail(FROM_BANK + String.join(", ", viaPouch(Arrays.asList(
                    s.detail.substring(FROM_BANK.length()).split(", "))))) : s);
        }
        return new Route(FOUND, steps, toBank.cost + WITHDRAW + on.cost, request.target, on.end, false,
            false, toBank.nodes + on.nodes).withThroughWilderness(toBank.throughWilderness || on.throughWilderness)
            .withKeptOut(toBank.keptOut || on.keptOut);
    }

    /** What is taken: runes the pouch holds enough of (a combination rune in it counts as its two) as "Rune pouch". */
    Set<String> viaPouch(Collection<String> take) {
        var held = new HashMap<String, Long>();
        pouch.forEach((name, n) -> {
            if (name == null) {
                return;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            held.merge(lower, n, Long::sum);
            for (var c : COMBINED.get()) {
                if (lower.equals(c[0] + " rune")) {
                    held.merge(c[1] + " rune", n, Long::sum);
                    held.merge(c[2] + " rune", n, Long::sum);
                }
            }
        });
        var taken = new LinkedHashSet<String>();
        for (String t : take) {
            var m = AMOUNT.matcher(t);
            taken.add(m.matches() && enough(m, held) ? "Rune pouch" : t);
        }
        return taken;
    }

    /** Combination runes and the two runes each holds (list "combination runes"). */
    static final RouteFiles.Table<String[][]> COMBINED = new RouteFiles.Table<>(() -> Pathfinder.list(
        "combination runes", r -> r.split(" ")).stream().filter(c -> c.length > 2).toArray(String[][]::new));

    /** An item as a bank step names it: "3 × Air rune", or "Dramen staff" (one). */
    static final Pattern AMOUNT = Pattern.compile("(?:(\\d+) × )?(.+)");

    /** Whether {@code held} (by lower-case name) has as many as {@code m} (matched {@link #AMOUNT}) names. */
    static boolean enough(Matcher m, Map<String, Long> held) {
        return held.getOrDefault(m.group(2).toLowerCase(Locale.ROOT), 0L) >= (m.group(1) == null ? 1
            : Long.parseLong(m.group(1)));
    }

    /** Tiles from a booth within which a way counts as come into its bank. */
    static final int ENTRY = 8;

    /** Where {@code way} first comes within {@link #ENTRY} tiles of {@code stand} on its floor; else its end. */
    static int entry(Route way, int stand) {
        for (var step : way.steps) {
            for (int point : step.kind == Route.Step.Kind.WALK ? step.points : new int[0]) {
                if (Tiles.near(point, stand, ENTRY)) {
                    return point;
                }
            }
        }
        return way.end >= 0 ? way.end : stand;
    }

    /**
     * Of {@code stands}, where the game walks from {@code point} to use the bank: the first its search reaches (it tries
     * west, east, south, north, then the diagonals, so not always the nearest side), else the first.
     */
    static int stand(CollisionMap map, GameWalk.Shut shut, int[] stands, int point) {
        int[] walk = GameWalk.path(map, shut, point, stands);
        return walk == null ? stands[0] : walk[walk.length - 1];
    }

    /** Half ticks a guess adds for leaving the house before a teleport cast outside it. */
    static final int OUT_OF_HOUSE = 12;

    /** Banks searched for the way to one: the quickest by {@link #guess}. */
    static final int TRIED = 3;

    int[] quickest(RouteRequest request) {
        return quickest(request, Arrays.stream(banks).mapToInt(b -> b[0]).toArray(), TRIED);
    }

    /**
     * The {@code n} of {@code places} (indices) quickest to reach by a guess (no search): from where the player stands
     * (not in a house, whose plan lies far off the map) and from where each way out of it lands (a teleport, a house's
     * portal, box or glory), its time plus the run on in a straight line (a tile a half tick). Underground and above are
     * apart, so a place 6400 tiles north is not counted as near.
     */
    static int[] quickest(RouteRequest request, int[] places, int n) {
        var from = new ArrayList<int[]>();
        int start = request.start;
        if (start >= 0 && !HousePlan.contains(start)) {
            from.add(new int[]{start, 0});
        }
        // Ways into the house (from outside it) come before any way out of it: its time, then the walk in the plan.
        var in = new ArrayList<int[]>();
        for (Edge e : request.startEdges) {
            from.add(new int[]{e.to, e.cost});
            if (start >= 0 && HousePlan.contains(e.to)) {
                in.add(new int[]{e.to, e.cost});
            }
        }
        for (Edge e : request.edges) {
            // Out of one's house: its portals, the box, the glory and the like; a teleport cast once out of it (a
            // minigame teleport by the house portal).
            if (e.from >= 0 && (e.kind == Edge.Kind.TELEPORT || HousePlan.contains(e.from)
                && !HousePlan.contains(e.to))) {
                int enter = start >= 0 && !HousePlan.contains(start) ? Integer.MAX_VALUE / 4 : 0;
                for (int[] i : in) {
                    enter = Math.min(enter, i[1] + Tiles.distance(i[0], e.from) * Pathfinder.RUN);
                }
                from.add(new int[]{e.to, e.cost + (e.kind == Edge.Kind.TELEPORT ? OUT_OF_HOUSE : enter)});
            }
        }
        long[] guessed = new long[places.length];
        for (int i = 0; i < places.length; i++) {
            guessed[i] = (long) guess(from, places[i]) << 32 | i;
        }
        Arrays.sort(guessed);
        return Arrays.stream(guessed, 0, Math.min(n, places.length)).mapToInt(g -> (int) g).toArray();
    }

    /** Half ticks from the nearest of {@code from} ({tile, half ticks to get there}) to {@code place}, as the crow flies. */
    static int guess(List<int[]> from, int place) {
        int best = Integer.MAX_VALUE / 2;
        for (var f : from) {
            if (f[0] >= 0 && Tiles.y(f[0]) / 6400 == Tiles.y(place) / 6400) {
                best = Math.min(best, f[1] + Tiles.distance(f[0], place) * Pathfinder.RUN);
            }
        }
        return best;
    }

    /**
     * What a step takes from the bank when its items are not named (a house's fairy ring): "runes" for a spell, the item
     * of a teleport ("Ring of dueling"), else "items for" what it is ("items for Fairy ring").
     */
    static String taken(Route.Step step) {
        String name = step.name;
        int arrow = name.indexOf(" → ");
        name = arrow > 0 ? name.substring(0, arrow) : name;
        int colon = name.indexOf(": ");
        String what = colon > 0 ? name.substring(0, colon) : name;
        return step.category != null && step.category.endsWith("Spellbook") ? "runes"
            : step.kind == Route.Step.Kind.TELEPORT || step.kind == Route.Step.Kind.HOUSE ? what : "items for " + what;
    }
}
