package com.hdmapreforged;

import static com.hdmapreforged.Tiles.*;
import static lombok.AccessLevel.*;

import java.util.*;
import java.util.stream.*;
import lombok.*;

/** The result of a search: the steps, and how the target was reached (or why not). */
@AllArgsConstructor(access = PRIVATE)
final class Route {
    enum Outcome {
        FOUND,
        NEAREST,
        NONE,
        CANCELLED
    }

    @AllArgsConstructor
    static final class Obstacle {
        final int at;
        /** "Chop-down Vines (bring an axe)", with what it takes ("an axe"). */
        final String text, tool;
    }

    /**
     * What a step says beyond its name and detail, as parts: put into words where shown ({@code RouteText}), never read
     * back out of a text.
     */
    @AllArgsConstructor
    @NoArgsConstructor(force = true)
    @With(PACKAGE)
    static final class Said {
        static final Said NONE = new Said();

        /** What the player lacks ("45 Sailing, 3200 × Coins"), as "You lack: " says it; else null. */
        final String lack;
        /** What it needs from the bank ("Law rune", "3 × Air rune"), as "From the bank: " names it; else null. */
        final List<String> bank;
        /** Any other detail its needs give ("Needs: 25 Magic, items"). */
        final String note;
        /** What is taken at the bank: on the bank step, and on the jump there, whose name it follows (" → Bank: "). */
        final List<String> taken;
        /** A house step's way in from outside ("Teleport to House"); else null. */
        final String into;
        /** A way into one's house as said in a long text ("TP house"). */
        final String brief;
        /** What a house step uses in the house, as the game says it ("Jewellery box 5 Burthorpe"), and in a long text ("Jwlry box 5"). */
        final String inHouse, boxed;
        /** A fairy ring's codes, in the order dialled. */
        final List<String> codes;
        /** The ids of items in {@link #lack} the game had not named yet ("Item 995"). */
        final List<Integer> unnamed;

        /** With {@code more} after what it says ("You lack: 3200 × Coins. Also needs: …"). */
        Said and(String more) {
            String text = text();
            return more == null || more.isEmpty() ? this : text == null ? withNote(more) : lack != null
                ? withLack(lack + ". " + more) : withBank(null).withNote(text + ". " + more);
        }

        /** As a step's detail says it: "You lack: …", "From the bank: …" or the note. */
        String text() {
            return lack != null ? "You lack: " + lack : bank != null ? BankDetour.FROM_BANK + String.join(", ", bank) : note;
        }
    }

    @AllArgsConstructor
    static final class Step {
        enum Kind {
            WALK, SAIL, STAIRS, ENTRANCE, TELEPORT, TRANSPORT, SHIP, BOARD, DISEMBARK, HOUSE,
            /** Take from the bank what the steps after it need ({@link BankDetour}): both points the bank's tile. */
            BANK
        }

        final Kind kind;
        /** Packed: all tiles walked or sea blocks sailed, or the two ends of a jump. */
        final int[] points;
        @With(PACKAGE)
        final String name;
        @With(PACKAGE)
        final String detail;
        final int cost;
        /** A walk's doors: for each, the index in {@link #points} of the tile it opens onto. */
        final int[] doors;
        final List<Obstacle> obstacles;
        /** Teleport or transport kind ("Hot air balloon"), to leave out all of that kind; or null. */
        final String category;
        @With(PACKAGE)
        final Said said;

        int first() {
            return points[0];
        }

        int last() {
            return points[points.length - 1];
        }

        boolean isJump() {
            return kind != Kind.WALK && kind != Kind.SAIL;
        }
    }

    final Outcome outcome;
    @With(PACKAGE)
    final List<Step> steps;
    /** Half ticks. */
    final int cost, target, end;
    @With(PACKAGE)
    final boolean exhausted;
    final boolean limited;
    final int nodes;
    /** Where a search that proved the target out of reach proved it out of reach from; else null. */
    @With(PACKAGE)
    final Reached reached;
    /** Through the Wilderness though the player keeps out of it: no way around it gets there. */
    @With(PACKAGE)
    final boolean viaWild;
    /** The search left out a step or jump into the Wilderness (kept out of it): through it, it may get further. */
    @With(PACKAGE)
    final boolean keptOut;

    /** Without the extra weight of teleports kept for longer trips. */
    int time() {
        return steps.stream().mapToInt(step -> step.cost).sum();
    }

    Route(Outcome outcome, List<Step> steps, int cost, int target, int end, boolean exhausted, boolean limited,
        int nodes) {
        this(outcome, steps, cost, target, end, exhausted, limited, nodes, null);
    }

    Route(Outcome outcome, List<Step> steps, int cost, int target, int end, boolean exhausted, boolean limited, int nodes,
        Reached reached) {
        this(outcome, Collections.unmodifiableList(steps), cost, target, end, exhausted, limited, nodes, reached, false,
            false);
    }

    /** This route, ending near the target of {@code shut}, which proved it out of reach. */
    Route near(Route shut) {
        return new Route(Outcome.NEAREST, steps, cost, shut.target, end, shut.exhausted, limited, nodes, shut.reached,
            viaWild, keptOut);
    }

    static final int NEAR = 12;

    /**
     * Up to its first teleport (or way of one's house): what comes after is drawn in the game once it is taken, so a
     * route out of the house and back in (to its fairy ring) does not draw both visits at once.
     */
    Route untilTeleport() {
        for (int s = 0; s < steps.size() - 1; s++) {
            var kind = steps.get(s).kind;
            if (kind == Step.Kind.TELEPORT || kind == Step.Kind.HOUSE) {
                return withSteps(steps.subList(0, s + 1));
            }
        }
        return this;
    }

    /** What is still ahead of the player, like a navigation app; the route itself when the player is not near it. */
    Route ahead(int player) {
        if (player < 0 || steps.isEmpty()) {
            return this;
        }
        int bestStep = -1, bestIndex = 0;
        long bestDistance = (long) (NEAR + 1) * (NEAR + 1);
        // Past a bank step nothing in one's house counts while one is in it: the route may go from the house to the bank
        // and back (its fairy ring), and in the house before it the bank is still to do.
        boolean banked = false, home = HousePlan.contains(player);
        for (int s = 0; s < steps.size(); s++) {
            Step step = steps.get(s);
            if (step.kind == Step.Kind.BANK) {
                // Done only once what it takes out is used: standing at the bank it is still to do.
                banked = true;
                continue;
            }
            if (step.isJump()) {
                int landing = step.last(), from = step.first();
                // Nearer where it starts (a gate, a door): not crossed yet.
                boolean before = from >= 0 && !isSea(from) && sameLevel(player, from)
                    && distance(player, from) < distance(player, landing);
                if (banked && home && HousePlan.contains(landing)) {
                    continue;
                }
                // Into one's house (a teleport, its portal): anywhere in it, it was taken, wherever the game put one.
                boolean entered = from >= 0 && !HousePlan.contains(from) && HousePlan.contains(landing) && home;
                if (sameLevel(player, landing) && (distance(player, landing) <= 2 || entered) && !before) {
                    bestStep = s + 1;
                    bestIndex = 0;
                    bestDistance = 0;
                }
                continue;
            }
            for (int i = 0; i < step.points.length; i++) {
                int point = step.points[i];
                if (!sameLevel(player, point) || banked && home && HousePlan.contains(point)) {
                    continue;
                }
                long dx = x(player) - x(point), dy = y(player) - y(point), d = dx * dx + dy * dy;
                if (isSea(point) && isSea(player)) {
                    d /= (long) CELL * CELL;
                }
                // Ties go to the later point, so a route passing a spot twice follows the player forward.
                if (d <= bestDistance) {
                    bestStep = s;
                    bestIndex = i;
                    bestDistance = d;
                }
            }
        }
        if (bestStep < 0 || bestStep == 0 && bestIndex == 0) {
            return this;
        }
        var left = new ArrayList<Step>();
        for (int s = bestStep; s < steps.size(); s++) {
            Step step = steps.get(s);
            if (s == bestStep && bestIndex > 0) {
                var rest = Arrays.copyOfRange(step.points, bestIndex, step.points.length);
                if (rest.length < 2) {
                    continue;
                }
                var obstaclesLeft = step.obstacles.stream()
                    .filter(o -> Arrays.stream(rest).anyMatch(p -> p == o.at)).collect(Collectors.toList());
                int cut = bestIndex;
                int[] doorsLeft = Arrays.stream(step.doors).filter(k -> k > cut).map(k -> k - cut).toArray();
                // A walk's detail names its obstacles: only those still ahead.
                String detail = step.kind != Step.Kind.WALK ? step.detail : obstaclesLeft.isEmpty() ? null
                    : obstaclesLeft.stream().map(o -> o.text).distinct().collect(Collectors.joining("; "));
                step = new Step(step.kind, rest, step.name, detail,
                    step.cost * (rest.length - 1) / (step.points.length - 1), doorsLeft, obstaclesLeft, step.category,
                    step.said);
            }
            left.add(step);
        }
        return withSteps(Collections.unmodifiableList(left)).withReached(null);
    }

    static boolean sameLevel(int a, int b) {
        return isSea(a) || isSea(b) || z(a) == z(b);
    }

    /**
     * Whether this search proved the target out of reach from the node too (it reached the node and tried everything,
     * or flooded back from the target and never came to the node), as long as the same ways may be used.
     */
    boolean reached(int node) {
        return reached != null && reached.contains(node);
    }

    boolean snapped() {
        return end >= 0 && end != target;
    }

    boolean has(Step.Kind kind) {
        return first(kind) != null;
    }

    Step first(Step.Kind kind) {
        return steps.stream().filter(step -> step.kind == kind).findFirst().orElse(null);
    }
}
